package net.rotko.zigner.domain.scan

import android.content.Context

/**
 * What the camera is allowed to hand to a parser.
 *
 * The camera is this app's only untrusted input. Everything an attacker
 * controls arrives through it, and the scan dispatcher routes purely on the
 * content of the payload - a six-character prefix decides which parser runs.
 * Nothing consulted the user about it, so the reachable attack surface was
 * the UNION of every chain compiled in, not the subset anyone actually uses.
 *
 * A user who has never touched Penumbra could still have 433 lines of
 * hand-rolled protobuf parsing executed by pointing their camera at a QR
 * code. That parser is also among the least exercised code in the app, which
 * makes it a worse place to be wrong than the code people use daily.
 *
 * So: deny by default, and route only what has been explicitly turned on.
 * This does not delete anything - it makes unused parsers unreachable, which
 * is the property that actually matters and is reversible in a way that
 * deleting 30k lines is not.
 *
 * The gate is checked BEFORE a payload reaches a parser. Prefix matching to
 * classify the payload is a fixed-length string comparison and is not itself
 * the thing being defended against.
 */
enum class ScanCapability(val id: String, val label: String) {
	/** Zcash: notes, PCZT, simple sign. The reason this device exists. */
	ZCASH("zcash", "Zcash"),

	/** Signed protocol-module packages and release-signing prefixes. */
	MODULE("module", "Module updates"),

	/** Encrypted backup and contact bundles. */
	BACKUP("backup", "Encrypted backup"),

	/** ZID challenge-response identity. */
	AUTH("auth", "Identity"),

	/** FROST threshold signing (Zcash multisig). */
	FROST("frost", "FROST multisig"),

	/** Substrate / Polkadot: SCALE payloads and runtime metadata. */
	SUBSTRATE("substrate", "Polkadot / Substrate"),

	PENUMBRA("penumbra", "Penumbra"),

	COSMOS("cosmos", "Cosmos"),
	;

	companion object {
		fun fromId(id: String): ScanCapability? = entries.firstOrNull { it.id == id }
	}
}

object ScanPolicy {
	private const val PREFS = "scan_policy"
	private const val KEY_ENABLED = "enabled"
	private const val KEY_INITIALISED = "initialised"

	/**
	 * On by default: the flows this device is for.
	 *
	 * Penumbra is here because zafu signs Penumbra through this device; it
	 * was off with no switch to turn it on (setEnabled has no UI), so every
	 * Penumbra request was refused. Substrate and Cosmos stay off: nothing
	 * we ship sends them, and an attacker cannot reach a parser that is off.
	 */
	val DEFAULT_ON = setOf(
		ScanCapability.ZCASH,
		ScanCapability.PENUMBRA,
		ScanCapability.MODULE,
		ScanCapability.BACKUP,
		ScanCapability.AUTH,
		ScanCapability.FROST,
	)

	private fun prefs(context: Context) =
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

	fun enabled(context: Context): Set<ScanCapability> {
		val p = prefs(context)
		// Distinguish "never configured" from "configured to allow nothing".
		// Without this, a user who disables everything gets the defaults back
		// on next launch, which would quietly undo an explicit decision.
		if (!p.getBoolean(KEY_INITIALISED, false)) return DEFAULT_ON
		val ids = p.getStringSet(KEY_ENABLED, emptySet()) ?: emptySet()
		return ids.mapNotNull { ScanCapability.fromId(it) }.toSet()
	}

	fun isEnabled(context: Context, cap: ScanCapability): Boolean =
		enabled(context).contains(cap)

	fun setEnabled(context: Context, cap: ScanCapability, on: Boolean) {
		val next = enabled(context).toMutableSet()
		if (on) next.add(cap) else next.remove(cap)
		prefs(context).edit()
			.putBoolean(KEY_INITIALISED, true)
			.putStringSet(KEY_ENABLED, next.map { it.id }.toSet())
			.apply()
	}
}
