package net.rotko.zigner.screens.settings.bluetooth

import java.math.BigInteger
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Noise_XX_25519_ChaChaPoly_SHA256, phone side.
 * The static key is this phone's long-term identity.
 * The ephemeral key is new for one import, so the key box is not sealed
 * under the long-term Bluetooth key.
 */
internal class NoiseResponder(staticSeed: ByteArray) {
	private val static = staticSeed.copyOf()
	private var ck = sha256("Noise_XX_25519_ChaChaPoly_SHA256".toByteArray())
	private var h = ck.copyOf()
	private var e: ByteArray? = null
	private var re: ByteArray? = null
	private var rs: ByteArray? = null
	private var k = ByteArray(32)
	private var n = 0L
	private var keyed = false
	private var send: ByteArray? = null
	private var recv: ByteArray? = null
	private var sendN = 0L
	private var recvN = 0L
	var ready = false
		private set

	fun readMessage1(msg: ByteArray) {
		if (msg.size != 32) throw IllegalArgumentException("handshake message 1")
		re = msg.copyOf()
		mixHash(msg)
	}

	fun message2(ephemeralSeed: ByteArray): ByteArray {
		e = ephemeralSeed.copyOf()
		val pub = X25519.base(ephemeralSeed)
		mixHash(pub)
		mixKey(X25519.shared(ephemeralSeed, re!!))
		val enc = encryptAndHash(X25519.base(static))
		mixKey(X25519.shared(static, re!!))
		return pub + enc
	}

	fun readMessage3(msg: ByteArray) {
		val remote = decryptAndHash(msg)
		if (remote.size != 32) throw IllegalArgumentException("initiator static")
		rs = remote
		mixKey(X25519.shared(e!!, remote))
		val (k1, k2) = hkdf2(ck, ByteArray(0))
		recv = k1
		send = k2
		ready = true
	}

	fun open(box: ByteArray): ByteArray {
		val cipher = recv ?: throw IllegalStateException("handshake")
		val nonce = nonce(recvN)
		recvN++
		return chaCha(Cipher.DECRYPT_MODE, cipher, nonce, null, box)
	}

	fun seal(plain: ByteArray): ByteArray {
		val cipher = send ?: throw IllegalStateException("handshake")
		val nonce = nonce(sendN)
		sendN++
		return chaCha(Cipher.ENCRYPT_MODE, cipher, nonce, null, plain)
	}

	fun remoteStatic(): ByteArray = rs?.copyOf() ?: throw IllegalStateException("handshake")

	private fun encryptAndHash(plain: ByteArray): ByteArray {
		val ct = if (!keyed) plain.copyOf() else chaCha(Cipher.ENCRYPT_MODE, k, nonce(n).also { n++ }, h, plain)
		mixHash(ct)
		return ct
	}

	private fun decryptAndHash(ct: ByteArray): ByteArray {
		val ad = h.copyOf()
		val pt = if (!keyed) ct.copyOf() else chaCha(Cipher.DECRYPT_MODE, k, nonce(n).also { n++ }, ad, ct)
		mixHash(ct)
		return pt
	}

	private fun mixHash(data: ByteArray) {
		val mac = MessageDigest.getInstance("SHA-256")
		mac.update(h)
		mac.update(data)
		h = mac.digest()
	}

	private fun mixKey(ikm: ByteArray) {
		val (next, temp) = hkdf2(ck, ikm)
		ck = next
		k = temp
		keyed = true
		n = 0
	}
}

internal object X25519 {
	private val p = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))
	private val a24 = BigInteger.valueOf(121665)

	fun base(seed: ByteArray): ByteArray {
		val u = ByteArray(32)
		u[0] = 9
		return shared(seed, u)
	}

	fun shared(scalar: ByteArray, point: ByteArray): ByteArray {
		val n = clamp(scalar)
		val x1 = decode(point)
		var x2 = BigInteger.ONE
		var z2 = BigInteger.ZERO
		var x3 = x1
		var z3 = BigInteger.ONE
		var swap = 0
		for (t in 254 downTo 0) {
			val bit = (n[t ushr 3].toInt() ushr (t and 7)) and 1
			swap = swap xor bit
			val c = cswap(swap, x2, x3)
			x2 = c[0]
			x3 = c[1]
			val d = cswap(swap, z2, z3)
			z2 = d[0]
			z3 = d[1]
			swap = bit
			val a = x2.add(z2).mod(p)
			val aa = a.multiply(a).mod(p)
			val b = x2.subtract(z2).mod(p)
			val bb = b.multiply(b).mod(p)
			val e = aa.subtract(bb).mod(p)
			val c2 = x3.add(z3).mod(p)
			val d2 = x3.subtract(z3).mod(p)
			val da = d2.multiply(a).mod(p)
			val cb = c2.multiply(b).mod(p)
			x3 = da.add(cb).mod(p).let { it.multiply(it).mod(p) }
			z3 = da.subtract(cb).mod(p).let { it.multiply(it).mod(p).multiply(x1).mod(p) }
			x2 = aa.multiply(bb).mod(p)
			z2 = e.multiply(aa.add(a24.multiply(e)).mod(p)).mod(p)
		}
		val c = cswap(swap, x2, x3)
		val d = cswap(swap, z2, z3)
		val out = encode(c[0].multiply(d[0].modInverse(p)).mod(p))
		return out
	}

	private fun clamp(seed: ByteArray): ByteArray {
		val n = seed.copyOf()
		n[0] = (n[0].toInt() and 248).toByte()
		n[31] = (n[31].toInt() and 127).toByte()
		n[31] = (n[31].toInt() or 64).toByte()
		return n
	}

	private fun cswap(swap: Int, a: BigInteger, b: BigInteger): Array<BigInteger> {
		if (swap == 0) return arrayOf(a, b)
		return arrayOf(b, a)
	}

	private fun decode(u: ByteArray): BigInteger {
		val le = u.copyOf()
		le[31] = (le[31].toInt() and 127).toByte()
		return BigInteger(1, le.reversedArray())
	}

	private fun encode(u: BigInteger): ByteArray {
		var raw = u.toByteArray()
		if (raw.isNotEmpty() && raw[0] == 0.toByte()) raw = raw.copyOfRange(1, raw.size)
		val be = ByteArray(32)
		val src = if (raw.size > 32) raw.copyOfRange(raw.size - 32, raw.size) else raw
		src.copyInto(be, 32 - src.size)
		return be.reversedArray()
	}
}

private fun hkdf2(ck: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
	val mac = Mac.getInstance("HmacSHA256")
	mac.init(SecretKeySpec(ck, "HmacSHA256"))
	val temp = mac.doFinal(ikm)
	val one = hmac(temp, byteArrayOf(1))
	val two = hmac(temp, one + byteArrayOf(2))
	return one to two
}

private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
	val mac = Mac.getInstance("HmacSHA256")
	mac.init(SecretKeySpec(key, "HmacSHA256"))
	return mac.doFinal(data)
}

internal fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

internal fun nonce(n: Long): ByteArray {
	val out = ByteArray(12)
	var v = n
	for (i in 4 until 12) {
		out[i] = v.toByte()
		v = v ushr 8
	}
	return out
}

internal fun chaCha(mode: Int, key: ByteArray, nonce: ByteArray, aad: ByteArray?, input: ByteArray): ByteArray {
	// Rust on a phone: Conscrypt has ChaCha20-Poly1305 only from API 28.
	val ad = aad ?: ByteArray(0)
	return try {
		when (mode) {
			Cipher.ENCRYPT_MODE -> Ed25519.backend.seal(key, nonce, ad, input)
			Cipher.DECRYPT_MODE -> Ed25519.backend.open(key, nonce, ad, input)
			else -> throw IllegalArgumentException("cipher mode")
		}
	} catch (e: javax.crypto.AEADBadTagException) {
		throw e
	} catch (e: Exception) {
		if (mode == Cipher.DECRYPT_MODE) throw javax.crypto.AEADBadTagException(e.message) else throw e
	}
}
