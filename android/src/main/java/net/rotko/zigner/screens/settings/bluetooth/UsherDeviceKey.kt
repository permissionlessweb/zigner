package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import java.io.File
import java.security.SecureRandom

/**
 * Ed25519 device signing key plus an X25519 Noise static, kept in the app
 * private files and sealed at rest. This is not a validator key: a
 * camera-only usher phone can exist without consensus material.
 */
internal object UsherDeviceKey {
	private const val DIR = "usher-device"

	data class Material(
		val sign: Ed25519Key,
		val signPub: ByteArray,
		val noiseSeed: ByteArray,
		val noisePub: ByteArray,
	)

	fun load(context: Context): Material {
		val folder = File(context.applicationContext.filesDir, DIR)
		if (!File(folder, "raw").exists()) generate(folder)
		return open(folder)
	}

	fun open(folder: File): Material {
		val signPub = File(folder, "raw").readBytes()
		val pkcs8 = DeviceKey.readPkcs8(folder)
		val seed = try { Ed25519.seedFromPkcs8(pkcs8) } finally { pkcs8.fill(0) }
		val sign = Ed25519Key(seed)
		if (!sign.pub().contentEquals(signPub)) {
			sign.wipe()
			throw IllegalStateException("usher device key does not match its public key")
		}
		val noiseSeed = DeviceKey.readPkcs8(File(folder, "noise")).let { der ->
			try { Ed25519.seedFromPkcs8(der) } finally { der.fill(0) }
		}
		val noisePub = X25519.base(noiseSeed)
		return Material(sign, signPub, noiseSeed, noisePub)
	}

	fun generate(folder: File): Material {
		folder.mkdirs()
		val signSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
		val sign = Ed25519Key(signSeed)
		val signPub = sign.pub()
		DeviceKey.writeSeed(folder, signSeed, signPub)
		signSeed.fill(0)
		val noiseFolder = File(folder, "noise")
		val noiseSeed = ByteArray(32).also { SecureRandom().nextBytes(it) }
		val noisePub = X25519.base(noiseSeed)
		DeviceKey.writeSeed(noiseFolder, noiseSeed, noisePub)
		return Material(sign, signPub, noiseSeed, noisePub)
	}

	fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
