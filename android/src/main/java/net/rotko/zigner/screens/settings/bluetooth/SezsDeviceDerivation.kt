package net.rotko.zigner.screens.settings.bluetooth

import java.text.Normalizer
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Deterministic SEZS usher device keys from a Zigner key-set seed.
 *
 * The BIP-39 seed is the same one Terp/Cosmos already use (empty
 * passphrase). The device keys then follow SLIP-0010 on a purpose that
 * is not a SLIP-44 coin type, so they cannot collide with the Terp
 * account at `m/44'/118'/0'/0/0` (secp256k1 BIP-32).
 *
 * Paths (all hardened, as required by SLIP-0010):
 * - Sign (ed25519, ticket frames): `m/1009'/0'/{index}'`
 *   HMAC key: "ed25519 seed"
 * - Noise static (X25519): `m/1009'/1'/{index}'`
 *   HMAC key: "curve25519 seed"
 *
 * `1009'` is a BIP-43 purpose local to SEZS usher device keys, not a
 * registered SLIP-44 coin. `{index}` lets one key set hold several
 * device keys (one per event, or reused).
 *
 * Callers must wipe [Derived] and must not log seeds or private keys.
 */
internal object SezsDeviceDerivation {
	const val PURPOSE = 1009
	const val ROLE_SIGN = 0
	const val ROLE_NOISE = 1
	private const val HARDENED = 0x80000000.toInt()

	data class Derived(
		val signSeed: ByteArray,
		val noiseSeed: ByteArray,
	) {
		fun wipe() {
			signSeed.fill(0)
			noiseSeed.fill(0)
		}
	}

	fun pathSign(index: Int): String = "m/$PURPOSE'/$ROLE_SIGN'/$index'"

	fun pathNoise(index: Int): String = "m/$PURPOSE'/$ROLE_NOISE'/$index'"

	fun bip39Seed(mnemonic: String, passphrase: String = ""): ByteArray {
		val normalized = Normalizer.normalize(mnemonic.trim(), Normalizer.Form.NFKD)
		val salt = Normalizer.normalize("mnemonic$passphrase", Normalizer.Form.NFKD)
		val spec = PBEKeySpec(normalized.toCharArray(), salt.toByteArray(Charsets.UTF_8), 2048, 512)
		val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
		return try {
			factory.generateSecret(spec).encoded
		} finally {
			spec.clearPassword()
		}
	}

	fun slip10Master(seed: ByteArray, curve: String): ByteArray {
		return hmacSha512(curve.toByteArray(Charsets.UTF_8), seed)
	}

	fun slip10Child(parent: ByteArray, index: Int): ByteArray {
		require(parent.size == 64) { "slip10 node" }
		require(index >= 0) { "index" }
		val hardened = index or HARDENED
		val data = ByteArray(37)
		data[0] = 0
		System.arraycopy(parent, 0, data, 1, 32)
		data[33] = (hardened ushr 24).toByte()
		data[34] = (hardened ushr 16).toByte()
		data[35] = (hardened ushr 8).toByte()
		data[36] = hardened.toByte()
		return try {
			hmacSha512(parent.copyOfRange(32, 64), data)
		} finally {
			data.fill(0)
		}
	}

	fun deriveNode(seed: ByteArray, curve: String, path: IntArray): ByteArray {
		var node = slip10Master(seed, curve)
		for (index in path) {
			val child = slip10Child(node, index)
			node.fill(0)
			node = child
		}
		return node
	}

	fun derive(mnemonic: String, index: Int, passphrase: String = ""): Derived {
		require(index >= 0) { "index" }
		val seed = bip39Seed(mnemonic, passphrase)
		return try {
			deriveFromSeed(seed, index)
		} finally {
			seed.fill(0)
		}
	}

	fun deriveFromSeed(bip39Seed: ByteArray, index: Int): Derived {
		require(index >= 0) { "index" }
		val signNode = deriveNode(bip39Seed, "ed25519 seed", intArrayOf(PURPOSE, ROLE_SIGN, index))
		val noiseNode = deriveNode(bip39Seed, "curve25519 seed", intArrayOf(PURPOSE, ROLE_NOISE, index))
		return try {
			Derived(signNode.copyOfRange(0, 32), noiseNode.copyOfRange(0, 32))
		} finally {
			signNode.fill(0)
			noiseNode.fill(0)
		}
	}

	fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

	private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
		val mac = Mac.getInstance("HmacSHA512")
		mac.init(SecretKeySpec(key, "HmacSHA512"))
		return mac.doFinal(data)
	}
}
