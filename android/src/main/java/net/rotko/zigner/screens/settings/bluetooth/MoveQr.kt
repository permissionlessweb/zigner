package net.rotko.zigner.screens.settings.bluetooth

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher

/**
 * Phone-to-phone validator key move over QR codes only. Key bytes never
 * cross Bluetooth or any network; the KMS never sees the secret.
 *
 *   console   START     zcinti-move:2:<chain>:<slot>:<nonceHex>:<floor>:<kmsB64>
 *   new phone RECEIVE   zcinti-recv:1:<ephB64>:<idB64>:<fp16hex>
 *   old phone SEALED    zcinti-p2p:1:<b64(oldId | oldEph | ct)>      (animated zc-anim:2 frames)
 *   new phone IMPORTED  zcinti-imported:2:<sigB64>:<macB64>        (consensus key signature + identity MAC)
 *   old phone RETIRED   zcinti-retired:2:<targetIdB64>:<sigB64>      (consensus key signature)
 *
 * Seal key = sha256(label | DH(oldEph,newEph) | DH(oldId,newEph) | DH(oldEph,newId) | transcript):
 * only the holder of the new phone's ephemeral AND static key can open it, and
 * only the holder of the old phone's static key can have made it. Inside, an
 * Ed25519 signature by the consensus key binds chain, slot, floor, nonce,
 * target identity, both ephemerals and the KMS identity.
 *
 * Everything here is pure (files under a base dir) so it runs in JVM tests.
 */
internal object MoveQr {
	const val START = "zcinti-move:2:"
	const val RECV = "zcinti-recv:1:"
	const val SEALED = "zcinti-p2p:1:"
	const val IMPORTED = "zcinti-imported:2:"
	const val RETIRED = "zcinti-retired:2:"
	private const val LABEL = "zcinti-p2p-v1"
	/** Matches handoffTTL in the KMS console. */
	const val RECV_TTL_MS = 15 * 60 * 1000L

	data class Start(val chain: String, val slot: String, val nonce: ByteArray, val floor: Long, val kmsPub: ByteArray) {
		val label get() = "${chain}_$slot"
		val nonceHex get() = hex(nonce)
	}

	data class Receiver(val eph: ByteArray, val id: ByteArray) {
		val fp get() = KeyExport.fingerprint(id)
	}

	data class Mark(val height: Long, val round: Int, val step: Int)

	class Opened(
		val start: Start,
		val owner: String,
		val mark: Mark,
		val secret: ByteArray,
		val oldId: ByteArray,
		val target: ByteArray,
	) {
		val pub get() = secret.copyOfRange(32, 64)
		fun wipe() = secret.fill(0)
	}

	private fun ok(s: String) = s.isNotEmpty() && s.length <= 64 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

	fun startText(s: Start) = "$START${s.chain}:${s.slot}:${s.nonceHex}:${s.floor}:${B64.enc(s.kmsPub)}"

	fun parseStart(payload: String): Start {
		val t = payload.trim()
		if (!t.startsWith(START)) throw IllegalArgumentException("scan the Move QR on the console")
		val p = t.removePrefix(START).split(":")
		if (p.size != 5) throw IllegalArgumentException("not a move QR")
		if (!ok(p[0]) || !ok(p[1])) throw IllegalArgumentException("chain or slot is not usable")
		val nonce = p[2].hexOrNull()?.takeIf { it.size == 16 } ?: throw IllegalArgumentException("bad nonce")
		val floor = p[3].toLongOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("a move needs a height floor")
		val kms = B64.dec(p[4])?.takeIf { it.size == 32 } ?: throw IllegalArgumentException("KMS identity is not a public key")
		return Start(p[0], p[1], nonce, floor, kms)
	}

	fun receiveText(ephPub: ByteArray, idPub: ByteArray) =
		"$RECV${B64.enc(ephPub)}:${B64.enc(idPub)}:${KeyExport.fingerprint(idPub).replace(" ", "")}"

	fun parseReceive(payload: String): Receiver {
		val t = payload.trim()
		if (!t.startsWith(RECV)) throw IllegalArgumentException("scan the Receive QR on the new phone")
		val p = t.removePrefix(RECV).split(":")
		if (p.size != 3) throw IllegalArgumentException("not a receive QR")
		val eph = B64.dec(p[0])?.takeIf { it.size == 32 } ?: throw IllegalArgumentException("bad receive key")
		val id = B64.dec(p[1])?.takeIf { it.size == 32 } ?: throw IllegalArgumentException("bad phone identity")
		if (eph.contentEquals(id)) throw IllegalArgumentException("bad receive key")
		if (KeyExport.fingerprint(id).replace(" ", "") != p[2].lowercase()) throw IllegalArgumentException("receive QR fingerprint does not match")
		return Receiver(eph, id)
	}

	fun keyId(pub: ByteArray) = hex(sha256(pub).copyOfRange(0, 8))

	fun bindMessage(s: Start, keyPub: ByteArray, target: Receiver, oldId: ByteArray, oldEph: ByteArray): ByteArray =
		("zcinti-p2p-bind:1|${s.chain}|${s.slot}|${keyId(keyPub)}|${s.floor}|${s.nonceHex}|${hex(target.id)}|" +
			"${hex(target.eph)}|${hex(oldId)}|${hex(oldEph)}|${hex(s.kmsPub)}").toByteArray()

	fun ackMessage(s: Start, keyPub: ByteArray, target: ByteArray): ByteArray =
		"zcinti-imported:1|${s.chain}|${s.slot}|${keyId(keyPub)}|${s.floor}|${s.nonceHex}|${hex(target)}".toByteArray()

	/** Matches bluetooth.RetireMessageV2 in the KMS. */
	fun retireMessage(s: Start, keyPub: ByteArray, target: ByteArray): ByteArray =
		"zcinti-retire:2|${s.chain}|${s.slot}|${keyId(keyPub)}|${s.floor}|${s.nonceHex}|${hex(target)}".toByteArray()

	private fun dh(scalar: ByteArray, point: ByteArray): ByteArray {
		val out = X25519.shared(scalar, point)
		if (out.all { it == 0.toByte() }) throw IllegalArgumentException("bad public key")
		return out
	}

	private fun sealKey(dh1: ByteArray, dh2: ByteArray, dh3: ByteArray, oldId: ByteArray, oldEph: ByteArray, r: Receiver): ByteArray {
		val k = sha256(LABEL.toByteArray() + dh1 + dh2 + dh3 + oldId + oldEph + r.id + r.eph)
		dh1.fill(0); dh2.fill(0); dh3.fill(0)
		return k
	}

	private fun ad(oldId: ByteArray, oldEph: ByteArray, r: Receiver) = LABEL.toByteArray() + oldId + oldEph + r.id + r.eph

	/** Old phone: seal secret64 (seed|pub) to the receiver. sign() signs with the consensus key. */
	fun seal(
		s: Start, r: Receiver, oldIdSeed: ByteArray, owner: String, mark: Mark, secret: ByteArray,
		sign: (ByteArray) -> ByteArray, ephSeed: ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) },
	): String {
		require(secret.size == 64) { "consensus secret" }
		val oldId = X25519.base(oldIdSeed)
		if (oldId.contentEquals(r.id)) throw IllegalArgumentException("that is this phone: scan the NEW phone")
		val oldEph = X25519.base(ephSeed)
		val pub = secret.copyOfRange(32, 64)
		val sig = sign(bindMessage(s, pub, r, oldId, oldEph))
		val k = sealKey(dh(ephSeed, r.eph), dh(oldIdSeed, r.eph), dh(ephSeed, r.id), oldId, oldEph, r)
		ephSeed.fill(0)
		val chain = s.chain.toByteArray()
		val slot = s.slot.toByteArray()
		val own = owner.toByteArray().let { if (it.size > 90) ByteArray(0) else it }
		val b = ByteBuffer.allocate(1 + chain.size + 1 + slot.size + 16 + 8 + 8 + 4 + 1 + 1 + own.size + 32 + 64 + 64)
		b.put(chain.size.toByte()).put(chain).put(slot.size.toByte()).put(slot).put(s.nonce).putLong(s.floor)
		b.putLong(mark.height).putInt(mark.round).put(mark.step.coerceIn(0, 255).toByte())
		b.put(own.size.toByte()).put(own).put(s.kmsPub).put(secret).put(sig)
		val plain = b.array()
		val ct = chaCha(Cipher.ENCRYPT_MODE, k, nonce(0), ad(oldId, oldEph, r), plain)
		k.fill(0); plain.fill(0)
		return SEALED + B64.enc(oldId + oldEph + ct)
	}

	/** New phone: open with this phone's receive ephemeral and identity. Verifies the binding and the key. */
	fun open(payload: String, myEphSeed: ByteArray, myIdSeed: ByteArray): Opened {
		val t = payload.trim()
		if (!t.startsWith(SEALED)) throw IllegalArgumentException("not a sealed move QR")
		val raw = B64.dec(t.removePrefix(SEALED)) ?: throw IllegalArgumentException("damaged move QR")
		if (raw.size < 64 + 16) throw IllegalArgumentException("damaged move QR")
		val oldId = raw.copyOfRange(0, 32)
		val oldEph = raw.copyOfRange(32, 64)
		val r = Receiver(X25519.base(myEphSeed), X25519.base(myIdSeed))
		if (oldId.contentEquals(r.id)) throw IllegalArgumentException("this QR came from this phone")
		val k = sealKey(dh(myEphSeed, oldEph), dh(myEphSeed, oldId), dh(myIdSeed, oldEph), oldId, oldEph, r)
		val plain = try {
			chaCha(Cipher.DECRYPT_MODE, k, nonce(0), ad(oldId, oldEph, r), raw.copyOfRange(64, raw.size))
		} catch (_: Exception) {
			throw IllegalArgumentException("this move QR was sealed for another phone or another Receive")
		} finally { k.fill(0) }
		val secret = ByteArray(64)
		try {
			val b = ByteBuffer.wrap(plain)
			fun str(): String { val n = b.get().toInt() and 0xff; val a = ByteArray(n); b.get(a); return a.decodeToString() }
			val chain = str(); val slot = str()
			val n = ByteArray(16).also { b.get(it) }
			val floor = b.long
			val mark = Mark(b.long, b.int, b.get().toInt() and 0xff)
			val owner = str()
			val kms = ByteArray(32).also { b.get(it) }
			b.get(secret)
			val sig = ByteArray(64).also { b.get(it) }
			if (b.hasRemaining()) throw IllegalArgumentException("trailing bytes")
			if (!ok(chain) || !ok(slot)) throw IllegalArgumentException("chain or slot is not usable")
			if (floor <= 0) throw IllegalArgumentException("key arrived without a height floor")
			if (mark.height >= floor) throw IllegalArgumentException("old phone signed at or above the move floor")
			val s = Start(chain, slot, n, floor, kms)
			val pub = secret.copyOfRange(32, 64)
			if (!seedMatches(secret)) throw IllegalArgumentException("key seed and public key differ")
			if (!verify(pub, bindMessage(s, pub, r, oldId, oldEph), sig)) throw IllegalArgumentException("move binding signature is wrong")
			return Opened(s, owner, mark, secret.copyOf(), oldId, r.id)
		} catch (e: java.nio.BufferUnderflowException) {
			throw IllegalArgumentException("damaged move QR")
		} finally {
			plain.fill(0); secret.fill(0)
		}
	}

	fun ackText(sig: ByteArray, mac: ByteArray) = IMPORTED + B64.enc(sig) + ":" + B64.enc(mac)

	/** (consensus signature, identity MAC) from an Imported QR. */
	fun ackParse(payload: String): Pair<ByteArray, ByteArray> {
		val t = payload.trim()
		if (!t.startsWith(IMPORTED)) throw IllegalArgumentException("scan the Imported QR on the new phone")
		val p = t.removePrefix(IMPORTED).split(":")
		if (p.size != 2) throw IllegalArgumentException("damaged Imported QR")
		val sig = B64.dec(p[0])?.takeIf { it.size == 64 } ?: throw IllegalArgumentException("damaged Imported QR")
		val mac = B64.dec(p[1])?.takeIf { it.size == 32 } ?: throw IllegalArgumentException("damaged Imported QR")
		return sig to mac
	}

	/**
	 * Proof by the new phone's identity (X25519, so a DH MAC rather than a
	 * signature): HMAC-SHA256 under sha256(label | DH(myId, peerId) | oldId | newId).
	 * The new phone computes it with its identity seed and the old phone's id;
	 * the old phone checks it with its seed and the id from the Receive QR.
	 */
	fun ackMac(myIdSeed: ByteArray, peerId: ByteArray, oldId: ByteArray, newId: ByteArray, msg: ByteArray): ByteArray {
		val shared = dh(myIdSeed, peerId)
		val k = sha256("zcinti-imported-id:1".toByteArray() + shared + oldId + newId)
		shared.fill(0)
		return try {
			val m = javax.crypto.Mac.getInstance("HmacSHA256")
			m.init(javax.crypto.spec.SecretKeySpec(k, "HmacSHA256"))
			m.doFinal(msg)
		} finally { k.fill(0) }
	}

	fun ackMacOk(myIdSeed: ByteArray, peerId: ByteArray, oldId: ByteArray, newId: ByteArray, msg: ByteArray, mac: ByteArray): Boolean =
		java.security.MessageDigest.isEqual(ackMac(myIdSeed, peerId, oldId, newId, msg), mac)

	fun retiredText(target: ByteArray, sig: ByteArray) = RETIRED + B64.enc(target) + ":" + B64.enc(sig)

	// Ed25519 helpers: zigner's Rust core on a phone (any API level), see Ed25519.kt.
	fun privateFromSeed(seed: ByteArray): Ed25519Key = Ed25519Key(seed.copyOf())

	fun sign(priv: Ed25519Key, msg: ByteArray): ByteArray = priv.sign(msg)

	fun verify(pub: ByteArray, msg: ByteArray, sig: ByteArray): Boolean = Ed25519.verify(pub, msg, sig)

	fun seedMatches(secret: ByteArray): Boolean {
		if (secret.size != 64) return false
		val seed = secret.copyOf(32)
		return try { Ed25519.pub(seed).contentEquals(secret.copyOfRange(32, 64)) } catch (_: Exception) { false } finally { seed.fill(0) }
	}

	fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}

/** Move state files under filesDir (no secrets in any of them). */
internal object MoveStore {
	private fun outFile(base: File, keyId: String) = File(base, "validator-keys/$keyId/move-out.json")
	private fun usedFile(base: File) = File(base, "validator-keys/move-nonces.json")
	fun proofFile(base: File) = File(base, "validator-keys/retire-proof.txt")
	fun ackFile(base: File) = File(base, "validator-keys/import-ack.txt")

	/** Old phone: the target this key was (or is about to be) sealed to. One target per nonce. */
	fun outSet(base: File, keyId: String, s: MoveQr.Start, target: ByteArray) {
		val cur = outGet(base, keyId)
		if (cur != null && cur.first.nonceHex == s.nonceHex && !cur.second.contentEquals(target)) {
			throw IllegalStateException("this move was already shown to another phone; cancel it on the console and start again")
		}
		write(outFile(base, keyId), JSONObject()
			.put("start", MoveQr.startText(s)).put("target", MoveQr.hex(target)).put("at", System.currentTimeMillis()).toString())
	}

	fun outGet(base: File, keyId: String): Pair<MoveQr.Start, ByteArray>? = try {
		val o = JSONObject(outFile(base, keyId).readText())
		MoveQr.parseStart(o.getString("start")) to o.getString("target").hexOrNull()!!
	} catch (_: Exception) { null }

	fun outClear(base: File, keyId: String) { outFile(base, keyId).delete() }

	fun nonceUsed(base: File, nonceHex: String): Boolean {
		val f = usedFile(base)
		if (!f.exists()) return false
		return try { val a = JSONArray(f.readText()); (0 until a.length()).any { a.optString(it) == nonceHex } } catch (_: Exception) { true }
	}

	/** A damaged list throws: rewriting it empty would forget every nonce. */
	fun markNonce(base: File, nonceHex: String) {
		val f = usedFile(base)
		val a = readList(f, "move nonce list")
		a.put(nonceHex)
		write(f, a.toString())
	}

	fun addTombstone(base: File, pubHex: String) {
		val f = File(base, "validator-keys/tombstones.json")
		val a = readList(f, "tombstone list")
		a.put(pubHex)
		write(f, a.toString())
	}

	private fun readList(f: File, what: String): JSONArray {
		if (!f.exists()) return JSONArray()
		return try { JSONArray(f.readText()) } catch (e: Exception) { throw IllegalStateException("$what is damaged; refusing to overwrite it", e) }
	}

	/** tmp + fsync + rename, so a crash leaves either the old or the new file. */
	fun write(f: File, s: String) {
		f.parentFile?.mkdirs()
		val tmp = File(f.parentFile, f.name + ".tmp")
		java.io.FileOutputStream(tmp).use { out -> out.write(s.toByteArray()); out.flush(); out.fd.sync() }
		if (!tmp.renameTo(f)) {
			java.io.FileOutputStream(f).use { out -> out.write(s.toByteArray()); out.flush(); out.fd.sync() }
			tmp.delete()
		}
	}
}

/** Base64 without android.util so JVM tests run (java.util.Base64 needs API 26). */
internal object B64 {
	private const val A = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
	fun enc(b: ByteArray): String {
		val sb = StringBuilder()
		var i = 0
		while (i < b.size) {
			val n = minOf(3, b.size - i)
			var v = 0
			for (j in 0 until 3) v = (v shl 8) or (if (j < n) b[i + j].toInt() and 0xff else 0)
			for (j in 0 until 4) sb.append(if (j <= n) A[(v shr (18 - 6 * j)) and 63] else '=')
			i += 3
		}
		return sb.toString()
	}

	fun dec(s0: String): ByteArray? {
		val s = s0.trim().trimEnd('=').replace('-', '+').replace('_', '/')
		if (s.length % 4 == 1) return null
		val out = java.io.ByteArrayOutputStream()
		var v = 0; var bits = 0
		for (c in s) {
			val d = A.indexOf(c); if (d < 0) return null
			v = (v shl 6) or d; bits += 6
			if (bits >= 8) { bits -= 8; out.write((v shr bits) and 0xff) }
		}
		return out.toByteArray()
	}
}

/**
 * Collects zc-anim:2:<session>:<i>/<n>:<part> frames; returns the payload once
 * all arrived. A frame from another session (or with another count) starts over,
 * so two animated QRs in view never mix.
 */
internal class AnimCollector {
	private var parts: Array<String?> = emptyArray()
	private var session = ""
	val have get() = parts.count { it != null }
	val total get() = parts.size

	/** Returns the whole payload, or null while frames are missing. Non-frame text returns as is. */
	fun add(text: String): String? {
		if (text.startsWith("zc-anim:1:")) return null // unsessioned frames are no longer accepted
		if (!text.startsWith("zc-anim:2:")) return text
		val p = text.split(":", limit = 5)
		if (p.size != 5) return null
		val sid = p[2]
		if (sid.length !in 4..32 || !sid.all { it.isLetterOrDigit() }) return null
		val ix = p[3].split("/")
		val i = ix.getOrNull(0)?.toIntOrNull() ?: return null
		val n = ix.getOrNull(1)?.toIntOrNull() ?: return null
		if (n <= 0 || n > 64 || i < 0 || i >= n) return null
		if (sid != session || parts.size != n) { session = sid; parts = arrayOfNulls(n) }
		parts[i] = p[4]
		return if (parts.all { it != null }) parts.joinToString("").also { parts = emptyArray(); session = "" } else null
	}
}

internal fun String.hexOrNull(): ByteArray? {
	if (length % 2 != 0) return null
	return try { chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (_: NumberFormatException) { null }
}
