package net.rotko.zigner.ui.theme

import androidx.compose.material.Colors
import androidx.compose.ui.graphics.Color

/**
 * Zafu's palette, shared with the extension (packages/ui/styles/globals.css).
 * Dark is sumi (ink), light is washi (paper). Gold is the only action colour;
 * hanko red is for errors and the seal. Edit values here, never at call sites.
 */

// sumi (dark)
val ZafuBackground = Color(0xFF0C0A08)       // surface-canvas
val ZafuSurface = Color(0xFF131009)          // surface-elev-1
val ZafuSurfaceSecondary = Color(0xFF1A1611) // surface-elev-2
val ZafuBorder = Color(0xFF241F18)           // surface-border-soft
val ZafuBorderStrong = Color(0xFF322A21)     // surface-border
val ZafuGold = Color(0xFFF4B728)             // the one action colour
val ZafuGoldDim = Color(0xFFB8860B)
val ZafuGoldLine = Color(0xFF3A2F14)
val ZafuTextHigh = Color(0xFFF2ECDF)         // fg-high
val ZafuText = Color(0xFFDED5C4)             // fg
val ZafuTextDim = Color(0xFF948A79)          // fg-muted
val ZafuOk = Color(0xFF8FBF73)
val ZafuWarn = Color(0xFFD9773F)
val ZafuErr = Color(0xFFC73E3A)              // hanko

// washi (light)
val WashiBackground = Color(0xFFEFE9DC)
val WashiSurface = Color(0xFFF6F2E8)
val WashiSurfaceSecondary = Color(0xFFFBF8F1)
val WashiBorder = Color(0xFFDDD3C1)
val WashiGold = Color(0xFF8A6508)
val WashiTextHigh = Color(0xFF171310)
val WashiText = Color(0xFF2B241C)
val WashiTextDim = Color(0xFF6F6555)
val WashiOk = Color(0xFF4B7A33)
val WashiErr = Color(0xFFB3322E)

val Colors.backgroundSystem: Color
	get() = ZafuBackground

val Colors.backgroundPrimary: Color
	get() = if (isLight) WashiBackground else ZafuBackground

val Colors.backgroundSecondary: Color
	get() = if (isLight) WashiSurface else ZafuSurface

val Colors.backgroundTertiary: Color
	get() = if (isLight) WashiSurfaceSecondary else ZafuSurfaceSecondary

val Colors.textSecondary: Color
	get() = if (isLight) WashiText else ZafuText

val Colors.textTertiary: Color
	get() = if (isLight) WashiTextDim else ZafuTextDim

val Colors.textTertiaryDarkForced: Color
	get() = ZafuTextDim

val Colors.textDisabled: Color
	get() = if (isLight) WashiTextDim.copy(alpha = 0.5f) else ZafuTextDim.copy(alpha = 0.5f)


val Colors.fill30: Color
	get() = if (isLight) Color(0x4D171310) else Color(0x4DF2ECDF)

/**
 * light fill for light theme and dark for dark. Used to made button disabled
 */
val Colors.fill30Inverted: Color
	get() = if (!isLight) Color(0x4D171310) else Color(0x4DF2ECDF)

val Colors.fill24: Color
	get() = if (isLight) Color(0x3D171310) else Color(0x3DF2ECDF)

val Colors.fill18: Color
	get() = if (isLight) Color(0x2E171310) else Color(0x2EF2ECDF)

val Colors.fill12: Color
	get() = if (isLight) Color(0x1F171310) else Color(0x1FF2ECDF)

val Colors.fill6: Color
	get() = if (isLight) Color(0x0F171310) else Color(0x0FF2ECDF)


val Colors.appliedOverlay: Color
	get() = if (isLight) Color(0x7A000000) else Color(0xB3000000)

val Colors.appliedHover: Color
	get() = if (isLight) Color(0xD2171310) else Color(0xD2F2ECDF)

val Colors.appliedStroke: Color
	get() = if (isLight) WashiBorder else ZafuBorder

val Colors.appliedSeparator: Color
	get() = if (isLight) Color(0x14171310) else Color(0x14F2ECDF)


// Gold accent replaces pink as primary accent
val Colors.accentPink: Color
	get() = if (isLight) WashiGold else ZafuGold

val Colors.accentRed: Color
	get() = if (isLight) WashiErr else ZafuErr

val Colors.accentGreen: Color
	get() = if (isLight) WashiOk else ZafuOk


// Gold variants (replacing pink variants)
val Colors.pink500: Color
	get() = if (isLight) WashiGold else ZafuGold

val Colors.pink300: Color
	get() = Color(0xFFFCD34D)  // gold-light

val Colors.backgroundDanger: Color
	get() = ZafuErr.copy(alpha = 0.12f)

val Colors.red400: Color
	get() = ZafuErr

val Colors.red500: Color
	get() = if (isLight) WashiErr else ZafuErr

val Colors.snackBarBackground: Color
	get() = if (isLight) WashiSurfaceSecondary else ZafuSurfaceSecondary

val Colors.forcedFill30: Color
	get() = Color(0x4D000000)

val Colors.forcedFill40: Color
	get() = Color(0x66000000)

val Colors.red500fill12: Color
	get() = ZafuErr.copy(alpha = 0.12f)


// Gold button variants (replacing pink)
val Colors.primaryButtonDisabledBackground: Color
	get() = if (isLight) WashiBorder else ZafuGoldLine

val Colors.primaryButtonDisabledText: Color
	get() = if (isLight) WashiTextDim else ZafuTextDim


/**
 * Per-network accent colors. Used as section accents on the home screen and
 * anywhere a chain's identity should be visible. Chrome (top bar, scan button,
 * wallet card) stays neutral so these can carry the visual weight.
 *
 * Lookup is by [NetworkInfoModel.networkLogo] (lower-case logo slug), so the
 * same matching logic as [NetworkIcon.getResourceIdForNetwork] applies.
 */
private val NetworkAccentZcash = ZafuGold
private val NetworkAccentPenumbra = Color(0xFFFB923C)  // zafu penumbra orange
private val NetworkAccentCosmos = Color(0xFF7FB3D5)    // device blue, muted to sit on sumi
private val NetworkAccentOther = ZafuTextDim

fun networkAccent(networkLogo: String): Color =
	when (networkLogo.lowercase()) {
		"zcash" -> NetworkAccentZcash
		"penumbra" -> NetworkAccentPenumbra
		"cosmos", "cosmoshub", "atom", "noble", "osmosis", "celestia" -> NetworkAccentCosmos
		else -> NetworkAccentOther
	}

/**
 * 12% alpha tint of the network accent — for soft fills (chip backgrounds,
 * section card backgrounds) that should still let surrounding text read clearly.
 */
fun networkAccentTint(networkLogo: String): Color = networkAccent(networkLogo).copy(alpha = 0.12f)

val Colors.warn: Color
	get() = ZafuWarn
