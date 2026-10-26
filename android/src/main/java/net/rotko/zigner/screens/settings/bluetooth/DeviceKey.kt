package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.io.File

/**
 * Ed25519 key kept in the app's private files: pkcs8 (RFC 8410 v1, sealed by
 * AtRest), spki and raw public key. Same bytes the JCA generator used to
 * write; signing now runs in Rust ([Ed25519]) so API 23+ works.
 * An import file is read once and deleted. Its bytes are not logged.
 */
internal object DeviceKey {
	/** The selected key and its raw public key. */
	fun load(context: Context): Pair<Ed25519Key, ByteArray> {
		if (ValidatorKeys.list(context).isEmpty()) importIfPresent(context)
		val key = ValidatorKeys.bootstrap(context).first
		return ValidatorKeys.materialFor(context, key.id)
	}

	/**
	 * Open a stored key. The seed comes out of the sealed PKCS#8 and its
	 * derived public key must equal the stored raw one, so a wrong or damaged
	 * file can never sign under a validator's name. Nothing is rewritten.
	 */
	fun open(folder: File): Pair<Ed25519Key, ByteArray> {
		val raw = File(folder, "raw").readBytes()
		val pkcs8 = readPkcs8(folder)
		val seed = try { Ed25519.seedFromPkcs8(pkcs8) } finally { pkcs8.fill(0) }
		val key = Ed25519Key(seed)
		if (!key.pub().contentEquals(raw)) {
			key.wipe()
			throw IllegalStateException("stored key does not match its public key")
		}
		return key to raw
	}

	internal fun writeSeed(folder: File, seed: ByteArray, pub: ByteArray) {
		val pkcs8 = Ed25519.pkcs8(seed)
		val spki = Ed25519.spki(pub)
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
