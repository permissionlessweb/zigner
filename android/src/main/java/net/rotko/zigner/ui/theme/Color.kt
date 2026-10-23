package net.rotko.zigner.ui.theme

import androidx.compose.material.Colors
import androidx.compose.ui.graphics.Color

/**
 * Zigner color scheme - dark theme with gold accent (zafu.rotko.net style)
 * Focused on Penumbra and Zcash cold wallet signing
 */

// Zafu-inspired dark theme colors
val ZafuBackground = Color(0xFF0D0D12)      // Deep charcoal
val ZafuSurface = Color(0xFF16161D)          // Slightly lighter surface
val ZafuSurfaceSecondary = Color(0xFF1E1E26) // Card backgrounds
val ZafuBorder = Color(0xFF2A2A35)           // Subtle borders
val ZafuGold = Color(0xFFF4A31E)             // Primary accent - warm gold
val ZafuGoldDim = Color(0xFFB8841A)          // Dimmed gold
val ZafuText = Color(0xFFE0E0E4)             // Primary text
val ZafuTextDim = Color(0xFF707078)          // Secondary/disabled text
val ZafuOk = Color(0xFF50C878)               // Success green
val ZafuErr = Color(0xFFC05050)              // Error red

val Colors.backgroundSystem: Color
	get() = ZafuBackground

val Colors.backgroundPrimary: Color
	get() = if (isLight) Color(0xFFFAFAFA) else ZafuBackground

val Colors.backgroundSecondary: Color
	get() = if (isLight) Color(0xFFFFFFFF) else ZafuSurface

val Colors.backgroundTertiary: Color
	get() = if (isLight) Color(0xFFFFFFFF) else ZafuSurfaceSecondary

val Colors.textSecondary: Color
	get() = if (isLight) Color(0xA8000000) else Color(0xB0E0E0E4)

val Colors.textTertiary: Color
	get() = if (isLight) Color(0x73000000) else ZafuTextDim

val Colors.textTertiaryDarkForced: Color
	get() = ZafuTextDim

val Colors.textDisabled: Color
	get() = if (isLight) Color(0x40000000) else Color(0x45E0E0E4)


val Colors.fill30: Color
	get() = if (isLight) Color(0x4D000000) else Color(0x4DE0E0E4)

/**
 * light fill for light theme and dark for dark. Used to made button disabled
 */
val Colors.fill30Inverted: Color
	get() = if (!isLight) Color(0x4D000000) else Color(0x4DE0E0E4)

val Colors.fill24: Color
	get() = if (isLight) Color(0x3D000000) else Color(0x3DE0E0E4)

val Colors.fill18: Color
	get() = if (isLight) Color(0x2E000000) else Color(0x2EE0E0E4)

val Colors.fill12: Color
	get() = if (isLight) Color(0x1F000000) else Color(0x1FE0E0E4)

val Colors.fill6: Color
	get() = if (isLight) Color(0x0F000000) else Color(0x0FE0E0E4)


val Colors.appliedOverlay: Color
	get() = if (isLight) Color(0x7A000000) else Color(0xB3000000)

val Colors.appliedHover: Color
	get() = if (isLight) Color(0xD2000000) else Color(0xD2E0E0E4)

val Colors.appliedStroke: Color
	get() = if (isLight) Color(0x1F000000) else ZafuBorder

val Colors.appliedSeparator: Color
	get() = if (isLight) Color(0x14000000) else Color(0x14E0E0E4)


// Gold accent replaces pink as primary accent
val Colors.accentPink: Color
	get() = if (isLight) ZafuGold else ZafuGold

val Colors.accentRed: Color
	get() = if (isLight) ZafuErr else Color(0xFFE06060)

val Colors.accentGreen: Color
	get() = if (isLight) ZafuOk else Color(0xFF60D890)


// Gold variants (replacing pink variants)
val Colors.pink500: Color
	get() = ZafuGold

val Colors.pink300: Color
	get() = Color(0xFFFFC04A)  // Lighter gold

val Colors.backgroundDanger: Color
	get() = Color(0x1FC05050)

val Colors.red400: Color
	get() = ZafuErr

val Colors.red500: Color
	get() = Color(0xFFE06060)

val Colors.snackBarBackground: Color
	get() = ZafuSurfaceSecondary

val Colors.forcedFill30: Color
	get() = Color(0x4D000000)

val Colors.forcedFill40: Color
	get() = Color(0x66000000)

val Colors.red500fill12: Color
	get() = Color(0x1FE06060)


// Gold button variants (replacing pink)
val Colors.primaryButtonDisabledBackground: Color
	get() = if (isLight) Color(0xFFE8D4A8) else Color(0xFF3D3020)

val Colors.primaryButtonDisabledText: Color
	get() = if (isLight) Color(0xFFF0E0C0) else Color(0xFF5A4A30)


/**
 * Per-network accent colors. Used as section accents on the home screen and
 * anywhere a chain's identity should be visible. Chrome (top bar, scan button,
 * wallet card) stays neutral so these can carry the visual weight.
 *
 * Lookup is by [NetworkInfoModel.networkLogo] (lower-case logo slug), so the
 * same matching logic as [NetworkIcon.getResourceIdForNetwork] applies.
 */
private val NetworkAccentZcash = Color(0xFFF4B73B)     // warm gold
private val NetworkAccentPenumbra = Color(0xFFBD1212)  // protocol red
private val NetworkAccentPolkadot = Color(0xFFE6007A)  // brand pink
private val NetworkAccentKusama = Color(0xFFE0E0E4)    // bright neutral
private val NetworkAccentCosmos = Color(0xFF5064FB)    // ATOM blue
private val NetworkAccentNoble = Color(0xFF7B61FF)
private val NetworkAccentOsmosis = Color(0xFF8E5BFF)
private val NetworkAccentCelestia = Color(0xFF7B2BF9)
private val NetworkAccentBitcoin = Color(0xFFF7931A)
private val NetworkAccentNostr = Color(0xFF8A2BE2)
private val NetworkAccentEthereum = Color(0xFF8A92B2)
private val NetworkAccentAtproto = Color(0xFF1185FE)
private val NetworkAccentSubstrate = Color(0xFFE6007A) // Polkadot pink fallback

fun networkAccent(networkLogo: String): Color =
	when (networkLogo.lowercase()) {
		"zcash" -> NetworkAccentZcash
		"penumbra" -> NetworkAccentPenumbra
		"polkadot", "statemint" -> NetworkAccentPolkadot
		"kusama", "statemine" -> NetworkAccentKusama
		"cosmos", "cosmoshub", "atom" -> NetworkAccentCosmos
		"noble" -> NetworkAccentNoble
		"osmosis" -> NetworkAccentOsmosis
		"celestia" -> NetworkAccentCelestia
		"terp" -> Color(0xFF1B8A5A)
		"bitcoin", "btc" -> NetworkAccentBitcoin
		"nostr" -> NetworkAccentNostr
		"ethereum", "eth" -> NetworkAccentEthereum
		"atprotocol", "atproto", "bluesky" -> NetworkAccentAtproto
		"westend", "rococo", "westmint" -> NetworkAccentKusama
		else -> NetworkAccentSubstrate
	}

/**
 * 12% alpha tint of the network accent — for soft fills (chip backgrounds,
 * section card backgrounds) that should still let surrounding text read clearly.
 */
fun networkAccentTint(networkLogo: String): Color = networkAccent(networkLogo).copy(alpha = 0.12f)
