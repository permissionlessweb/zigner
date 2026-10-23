package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Ed25519 key kept in the app's private files. Android's Ed25519 generator
 * rejects a 256-bit initialize, so this never calls initialize(256).
 * An import file is read once and deleted. Its bytes are not logged.
 */
internal object DeviceKey {
	private val pkcs8Prefix = byteArrayOf(
		0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20,
	)
	private val spkiPrefix = byteArrayOf(
		0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
	)

	fun load(context: Context): KeyPair {
		if (ValidatorKeys.list(context).isEmpty()) importIfPresent(context)
		val key = ValidatorKeys.bootstrap(context).first
		val folder = File(context.filesDir, "validator-keys/${key.id}")
		val factory = KeyFactory.getInstance("Ed25519")
		val priv = factory.generatePrivate(PKCS8EncodedKeySpec(readPkcs8(folder)))
		val pub = factory.generatePublic(X509EncodedKeySpec(File(folder, "spki").readBytes()))
		return KeyPair(pub, priv)
	}

	internal fun writeSeed(folder: File, seed: ByteArray, pub: ByteArray) {
		val pkcs8 = pkcs8Prefix + seed
		val spki = spkiPrefix + pub
		folder.mkdirs()
		writePkcs8(folder, pkcs8)
		File(folder, "spki").writeBytes(spki)
		File(folder, "raw").writeBytes(pub)
		pkcs8.fill(0)
	}

	fun readPkcs8(folder: File): ByteArray {
		val raw = File(folder, "pkcs8").readBytes()
		if (!AtRest.isSealed(raw)) {
			writePkcs8(folder, raw)
			return raw
		}
		return AtRest.open(VaultKey.get(), raw)
	}

	fun writePkcs8(folder: File, pkcs8: ByteArray) {
		folder.mkdirs()
		File(folder, "pkcs8").writeBytes(AtRest.seal(VaultKey.get(), pkcs8))
	}

	fun rawPublic(pair: KeyPair): ByteArray {
		val encoded = pair.public.encoded
		if (encoded.size >= 32) return encoded.copyOfRange(encoded.size - 32, encoded.size)
		return encoded
	}

	private fun importIfPresent(context: Context) {
		val external = context.getExternalFilesDir(null)
		val candidates = listOfNotNull(
			File(context.filesDir, "import-priv-validator.json"),
			external?.let { File(it, "import-priv-validator.json") },
		)
		for (file in candidates) {
			if (!file.exists()) continue
			importFile(context, file)
			file.delete()
			return
		}
	}

	private fun importFile(context: Context, file: File) {
		val root = JSONObject(file.readText())
		val priv = root.getJSONObject("priv_key")
		val type = priv.getString("type")
		if (!type.endsWith("Ed25519")) {
			throw IllegalStateException("consensus key is not ed25519")
		}
		val raw = Base64.decode(priv.getString("value"), Base64.DEFAULT)
		try {
			val seed = when (raw.size) {
				32 -> raw.copyOf()
				64 -> raw.copyOf(32)
				else -> throw IllegalStateException("unexpected ed25519 key length")
			}
			val pubRaw = Base64.decode(root.getJSONObject("pub_key").getString("value"), Base64.DEFAULT)
			if (pubRaw.size != 32) throw IllegalStateException("unexpected ed25519 public key length")
			val secret = seed + pubRaw
			ValidatorKeys.importSecret(context, "import", secret)
			secret.fill(0)
			seed.fill(0)
		} finally {
			raw.fill(0)
		}
	}
}
