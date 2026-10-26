package net.rotko.zigner.screens.scan.camera

/**
 * Where a payload goes after the Rust sequence decoder reassembled it
 * (raptorq / legacy multi-frame). Before this, only the first raw frame was
 * checked for chain prefixes, so a multi-frame Cosmos request (530510) fell
 * through to the Substrate parser.
 */
internal enum class ReassembledRoute { PENUMBRA, COSMOS, ZCASH_PCZT, SUBSTRATE }

internal fun hasHexPrefix(hex: String, prefix: String): Boolean =
	hex.length >= prefix.length && hex.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)

internal fun reassembledRoute(hex: String): ReassembledRoute = when {
	hasHexPrefix(hex, "530310") -> ReassembledRoute.PENUMBRA
	hasHexPrefix(hex, "530510") -> ReassembledRoute.COSMOS
	hex.length >= 6 && hasHexPrefix(hex, "5304") -> ReassembledRoute.ZCASH_PCZT
	else -> ReassembledRoute.SUBSTRATE
}
