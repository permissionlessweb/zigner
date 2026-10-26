package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import android.util.Base64
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher

/** Seals one signer key to the KMS static identity with a fresh ephemeral key. */
internal object KeyExport {
	/** Eight-byte sha256 prefix, grouped, matching the KMS page. */
	fun fingerprint(pub: ByteArray): String {
		val sum = sha256(pub)
		return sum.copyOfRange(0, 8).joinToString(" ") { "%02x".format(it) }
	}

	fun kmsPublic(payload: String): ByteArray {
		val prefix = "zcinti-kms:1:"
		val text = payload.trim()
		if (!text.startsWith(prefix)) throw IllegalArgumentException("scan the KMS identity QR on the zcinti page")
		val raw = Base64.decode(text.removePrefix(prefix), Base64.DEFAULT)
		if (raw.size != 32) throw IllegalArgumentException("KMS identity is not a public key")
		return raw
	}

	fun box(context: Context, id: String, kmsPub: ByteArray): String {
		val secret = secret(context, id)
		val chain = ValidatorKeys.list(context).firstOrNull { it.id == id }?.chainId ?: ""
		val eph = ByteArray(32).also { SecureRandom().nextBytes(it) }
		val ephPub = X25519.base(eph)
		val shared = X25519.shared(eph, kmsPub)
		eph.fill(0)
		val key = sha256(shared)
		val plain = byteArrayOf(chain.length.toByte()) + chain.toByteArray() + secret
		val sealed = chaCha(Cipher.ENCRYPT_MODE, key, nonce(0), "zcinti-export-v1".toByteArray(), plain)
		secret.fill(0)
		plain.fill(0)
		return "zcinti-recover:1:" + Base64.encodeToString(ephPub + sealed, Base64.NO_WRAP)
	}

	private fun secret(context: Context, id: String): ByteArray {
		val folder = File(context.filesDir, "validator-keys/$id")
		val pkcs8 = DeviceKey.readPkcs8(folder)
		val pub = File(folder, "raw").readBytes()
		if (pkcs8.size < 32 || pub.size != 32) throw IllegalStateException("key set")
		val seed = Ed25519.seedFromPkcs8(pkcs8)
		pkcs8.fill(0)
		return seed + pub
	}
}
