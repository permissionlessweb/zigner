package net.rotko.zigner.domain.scan

import io.parity.signer.uniffi.ErrorDisplayed
import io.parity.signer.uniffi.scanReadable
import timber.log.Timber

/**
 * What the camera is allowed to hand to a parser.
 *
 * The camera is this app's only untrusted input. Everything an attacker
 * controls arrives through it, and the scan dispatcher routes on the content
 * of the payload. A network's content parser is reachable only when this
 * device holds something it would be used with: no Penumbra key, no
 * Penumbra parsing; no Zcash key or FROST wallet, no Zcash parsing; no Cosmos
 * key, no Cosmos parsing. Polkadot is never reachable.
 *
 * The set comes from the Rust core (`db_handling::scan_policy`), derived
 * from the database each time the camera opens. There is no stored setting
 * that could drift from what the device actually holds.
 *
 * What this does NOT gate: the multi-frame transport decoder
 * (`qrparserGetPacketsTotal` / `qrparserTryDecodeQrSequence`: RaptorQ,
 * legacy frames, banana-split shares) runs on byte-mode codes before
 * classification, and FROST P-frames are base64- and JSON-decoded before
 * their gate.
 */
enum class ScanCapability(val id: String, val label: String) {
	/** Signed protocol-module packages and release-signing prefixes. */
	MODULE("module", "Module updates"),

	/** Seed recovery (banana split) and backup import. */
	BACKUP("backup", "Recovery"),

	/** FROST key generation, which starts before any FROST wallet exists. */
	FROST_DKG("frost-dkg", "FROST wallet creation"),

	/** FROST signing rounds. */
	FROST("frost", "FROST signing"),

	/** Zcash: notes, PCZT, simple sign. */
	ZCASH("zcash", "Zcash"),

	PENUMBRA("penumbra", "Penumbra"),

	/** ZID challenge-response identity, derived from a seed. */
	AUTH("auth", "Identity"),

	/** Never readable: no screen left to route them to. */
	SUBSTRATE("substrate", "Polkadot / Substrate"),

	COSMOS("cosmos", "Cosmos"),
	;

	companion object {
		fun fromId(id: String): ScanCapability? = entries.firstOrNull { it.id == id }
	}
}

object ScanPolicy {
	/**
	 * Open on an empty device, because they are how a device stops being
	 * empty. Also what the camera falls back to if the lookup fails.
	 */
	val ALWAYS = setOf(
		ScanCapability.MODULE,
		ScanCapability.BACKUP,
		ScanCapability.FROST_DKG,
	)

	/** null when the lookup failed; the camera then reads only [ALWAYS]. */
	fun readable(): Set<ScanCapability>? = try {
		scanReadable().mapNotNull { ScanCapability.fromId(it) }.toSet()
	} catch (e: ErrorDisplayed) {
		Timber.e("[SCAN] policy lookup failed, reading only recovery and updates: $e")
		null
	}
}
