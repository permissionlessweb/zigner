package net.rotko.zigner.ui.theme

import androidx.compose.material.Colors
import androidx.compose.ui.graphics.Color

/**
 * Names kept from the Parity-era palette so older screens compile; every value
 * now points at the sumi / washi tokens in Color.kt.
 */
private fun Colors.pick(light: Color, dark: Color) = if (isLight) light else dark

val Colors.Border600: Color get() = pick(WashiBorder, ZafuBorderStrong)
val Colors.Border500: Color get() = pick(WashiBorder, ZafuBorderStrong)
val Colors.Border400: Color get() = pick(WashiBorder, ZafuBorder)

val Colors.Bg600: Color get() = pick(WashiSurfaceSecondary, ZafuBorderStrong)
val Colors.Bg500: Color get() = pick(WashiSurfaceSecondary, ZafuBorder)
val Colors.Bg400: Color get() = pick(WashiSurfaceSecondary, ZafuSurfaceSecondary)
val Colors.Bg300: Color get() = pick(WashiSurfaceSecondary, ZafuSurfaceSecondary)
val Colors.Bg200: Color get() = pick(WashiSurface, ZafuSurface)
val Colors.Bg100: Color get() = pick(WashiBackground, ZafuBackground)
val Colors.Bg000: Color get() = pick(WashiBackground, ZafuBackground)
val Colors.BgDanger: Color get() = pick(WashiErr, ZafuErr).copy(alpha = 0.12f)

val Colors.Text600: Color get() = pick(WashiTextHigh, ZafuTextHigh)
val Colors.Text500: Color get() = pick(WashiText, ZafuText)
val Colors.Text400: Color get() = pick(WashiTextDim, ZafuTextDim)
val Colors.Text300: Color get() = pick(WashiTextDim, ZafuTextDim)
val Colors.Text100: Color get() = pick(WashiBorder, ZafuBorderStrong)

// Action was Parity blue; zafu has one action colour, gold.
val Colors.Action600: Color get() = pick(WashiTextHigh, ZafuTextHigh)
val Colors.Action400: Color get() = pick(WashiGold, ZafuGold)
val Colors.Action300: Color get() = pick(WashiGold, ZafuGoldDim)
val Colors.Action200: Color get() = pick(WashiBorder, ZafuGoldLine)
val Colors.Action100: Color get() = pick(WashiSurfaceSecondary, ZafuSurfaceSecondary)

val Colors.Crypto500: Color get() = pick(WashiSurfaceSecondary, ZafuGoldLine)
val Colors.Crypto400: Color get() = pick(WashiGold, ZafuGold)
val Colors.Crypto200: Color get() = pick(WashiGold, ZafuGoldDim)
val Colors.Crypto100: Color get() = pick(WashiSurfaceSecondary, ZafuGoldLine)

val Colors.SignalDanger: Color get() = pick(WashiErr, ZafuErr)
val Colors.SignalOn: Color get() = pick(WashiOk, ZafuOk)
val Colors.SignalWarning: Color get() = ZafuWarn
