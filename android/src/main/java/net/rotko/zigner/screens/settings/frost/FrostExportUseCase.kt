package net.rotko.zigner.screens.settings.frost

import io.parity.signer.uniffi.ageDeviceRecipient
import io.parity.signer.uniffi.frostExportAllBackupAge
import io.parity.signer.uniffi.frostExportAllBackupEnvelope
import io.parity.signer.uniffi.frostExportBackupEnvelope
import io.parity.signer.uniffi.frostImportAllBackupAge
import io.parity.signer.uniffi.frostImportAllBackupEnvelope
import io.parity.signer.uniffi.frostImportBackupEnvelope
import net.rotko.zigner.domain.frost.FrostSeeds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.storage.mapError

/**
 * Single owner of the system-auth gate for FROST share export.
 *
 * A FROST backup envelope is encrypted with only a user-chosen passphrase, so
 * an unattended unlocked device would otherwise let anyone write a share to disk
 * and crack the passphrase offline. Every export path funnels through here so a
 * fresh biometric / device-credential check can't be bypassed by a future call
 * site — mirroring the auth gate already on online-mode toggle, seed export, and
 * device wipe. See rotkonetworks/zigner#14.
 *
 * Every helper returns null when authentication fails or is cancelled; callers
 * treat null as "aborted, do nothing" (the auth layer already surfaces its own
 * failure toast). A thrown exception still means a genuine export failure.
 *
 * Every helper's gate is the seed read in [FrostSeeds]: shares are sealed under
 * a seed, so they cannot be read or stored without it, and reading it is the
 * fresh unlock. One prompt per user action; a second prompt is the kind of
 * thing people learn to dismiss without reading.
 */
object FrostExportUseCase {

	/** Export one wallet's encrypted backup envelope. The shares are sealed
	 *  under a seed, so reading them is the fresh unlock. */
	suspend fun exportSingle(walletId: String, passphrase: String): String? {
		val seeds = FrostSeeds.unlockAll() ?: return null
		return withContext(Dispatchers.Default) {
			frostExportBackupEnvelope(walletId, passphrase, seeds)
		}
	}

	/** Export every wallet into one encrypted envelope, behind one unlock. */
	suspend fun exportAll(passphrase: String): String? {
		val seeds = FrostSeeds.unlockAll() ?: return null
		return withContext(Dispatchers.Default) {
			frostExportAllBackupEnvelope(passphrase, seeds)
		}
	}

	/** Import one passphrase envelope; the share is sealed under the first seed. */
	suspend fun importSingle(json: String, passphrase: String): String? {
		val seeds = FrostSeeds.unlockAll() ?: return null
		return withContext(Dispatchers.Default) {
			frostImportBackupEnvelope(json, passphrase, seeds)
		}
	}

	/** Import a passphrase batch envelope; shares are sealed under the first seed. */
	suspend fun importAll(json: String, passphrase: String): String? {
		val seeds = FrostSeeds.unlockAll() ?: return null
		return withContext(Dispatchers.Default) {
			frostImportAllBackupEnvelope(json, passphrase, seeds)
		}
	}

	/**
	 * Export every wallet encrypted to public keys instead of a passphrase.
	 *
	 * `extraRecipients` are the co-signers. This device is always a recipient
	 * regardless, added in Rust so a UI mistake cannot produce a backup this
	 * device is unable to restore.
	 *
	 * Returns null if the seed read was cancelled or failed.
	 */
	suspend fun exportAllToRecipients(
		seedName: String,
		extraRecipients: List<String>,
	): String? {
		val (phrase, seeds) = FrostSeeds.unlockWith(seedName) ?: return null
		return withContext(Dispatchers.Default) {
			frostExportAllBackupAge(phrase, extraRecipients, seeds)
		}
	}

	/** This device's recipient line, for showing and sharing. Force-auths. */
	suspend fun deviceRecipient(seedName: String): String? {
		val repository = ServiceLocator.activityScope?.seedRepository ?: return null
		val phrase = repository.getSeedPhraseForceAuth(seedName).mapError() ?: return null
		return withContext(Dispatchers.Default) { ageDeviceRecipient(phrase) }
	}

	/** Import an age-encrypted batch backup addressed to this device. */
	suspend fun importAllFromAge(seedName: String, armored: String): String? {
		val repository = ServiceLocator.activityScope?.seedRepository ?: return null
		val phrase = repository.getSeedPhraseForceAuth(seedName).mapError() ?: return null
		return withContext(Dispatchers.Default) {
			frostImportAllBackupAge(phrase, armored)
		}
	}
}
