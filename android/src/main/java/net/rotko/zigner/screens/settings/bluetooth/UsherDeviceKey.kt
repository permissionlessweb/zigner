package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import java.io.File

/**
 * Ed25519 device signing key plus an X25519 Noise static. Keys are
 * derived from a selected Zigner key set (see [SezsDeviceDerivation])
 * and sealed at rest. This is not a validator key and is distinct
 * from the Terp bech32 account in that key set.
 */
internal object UsherDeviceKey {
	private const val DIR = "usher-device"
	private const val PREFS = "usher-device"

	data class Material(
		val sign: Ed25519Key,
		val signPub: ByteArray,
		val noiseSeed: ByteArray,
		val noisePub: ByteArray,
	)

	data class Binding(
		val seedName: String,
		val index: Int,
	)

	fun prefs(context: Context) =
		context.applicationContext.getSharedPreferences(PREFS, 0)

	fun binding(context: Context): Binding? {
		val name = prefs(context).getString("seed_name", "").orEmpty()
		if (name.isBlank()) return null
		return Binding(name, prefs(context).getInt("key_index", 0).coerceAtLeast(0))
	}

	fun tryLoad(context: Context): Material? {
		val folder = File(context.applicationContext.filesDir, DIR)
		if (!File(folder, "raw").exists()) return null
		return open(folder)
	}

	fun load(context: Context): Material =
		tryLoad(context) ?: throw IllegalStateException("select a key set")

	fun open(folder: File): Material {
		val signPub = File(folder, "raw").readBytes()
		val pkcs8 = DeviceKey.readPkcs8(folder)
		val seed = try {
			Ed25519.seedFromPkcs8(pkcs8)
		} finally {
			pkcs8.fill(0)
		}
		val sign = Ed25519Key(seed)
		if (!sign.pub().contentEquals(signPub)) {
			sign.wipe()
			throw IllegalStateException("usher device key does not match its public key")
		}
		val noiseSeed = DeviceKey.readPkcs8(File(folder, "noise")).let { der ->
			try {
				Ed25519.seedFromPkcs8(der)
			} finally {
				der.fill(0)
			}
		}
		val noisePub = X25519.base(noiseSeed)
		return Material(sign, signPub, noiseSeed, noisePub)
	}

	/**
	 * Derive the device keys from [mnemonic] for [seedName] and [index],
	 * seal them, and remember the binding. The mnemonic is not stored.
	 * A change of public key drops the previous grant, which was bound
	 * to the old sign key.
	 */
	fun bind(context: Context, seedName: String, index: Int, mnemonic: String): Material {
		require(seedName.isNotBlank()) { "key set" }
		require(index >= 0) { "index" }
		val derived = SezsDeviceDerivation.derive(mnemonic, index)
		return try {
			val app = context.applicationContext
			val folder = File(app.filesDir, DIR)
			val previous = tryLoad(app)?.signPub
			val sign = Ed25519Key(derived.signSeed)
			val signPub = sign.pub()
			DeviceKey.writeSeed(folder, derived.signSeed, signPub)
			val noiseFolder = File(folder, "noise")
			val noisePub = X25519.base(derived.noiseSeed)
			DeviceKey.writeSeed(noiseFolder, derived.noiseSeed, noisePub)
			val edit = prefs(app).edit()
				.putString("seed_name", seedName)
				.putInt("key_index", index)
			if (previous != null && !previous.contentEquals(signPub)) {
				edit.remove("grant_hash")
					.remove("event_id")
					.remove("event_name")
					.remove("grant_expiry")
					.remove("grant_capability")
					.remove("grant_admin")
					.putLong("seq", 1L)
			}
			edit.apply()
			Material(sign, signPub, derived.noiseSeed.copyOf(), noisePub)
		} finally {
			derived.wipe()
		}
	}

	/** Noise static used on a SEZS desk session once a key set is bound. */
	fun sessionNoiseSeed(context: Context): ByteArray? =
		tryLoad(context)?.noiseSeed?.copyOf()

	fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
