package net.rotko.zigner.screens.settings.bluetooth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-GCM file header. The Android keystore holds the key, not the file. */
internal object AtRest {
	private val magic = byteArrayOf('Z'.code.toByte(), 'C'.code.toByte(), '0'.code.toByte(), '1'.code.toByte())

	fun isSealed(blob: ByteArray): Boolean =
		blob.size > 4 && blob.copyOfRange(0, 4).contentEquals(magic)

	fun seal(key: SecretKey, plain: ByteArray): ByteArray {
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.ENCRYPT_MODE, key)
		val iv = cipher.iv
		val ct = cipher.doFinal(plain)
		return magic + iv + ct
	}

	fun open(key: SecretKey, blob: ByteArray): ByteArray {
		if (!isSealed(blob) || blob.size < 4 + 12 + 16) {
			throw IllegalArgumentException("secret is not sealed")
		}
		val iv = blob.copyOfRange(4, 4 + 12)
		val ct = blob.copyOfRange(4 + 12, blob.size)
		val cipher = Cipher.getInstance("AES/GCM/NoPadding")
		cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
		return cipher.doFinal(ct)
	}
}

internal object VaultKey {
	private const val alias = "zigner.validator.atrest"

	fun get(): SecretKey {
		val ks = KeyStore.getInstance("AndroidKeyStore")
		ks.load(null)
		(ks.getKey(alias, null) as? SecretKey)?.let { return it }
		val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
		gen.init(
			KeyGenParameterSpec.Builder(
				alias,
				KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
			)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build(),
		)
		return gen.generateKey()
	}
}
