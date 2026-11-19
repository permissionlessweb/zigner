package net.rotko.zigner.ui.theme

import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// sumi: zafu's dark theme. primary is the text/icon colour, not an accent.
private val DarkColorPaletteNew = darkColors(
	primary = ZafuTextHigh,
	primaryVariant = ZafuBackground,
	secondary = ZafuTextDim,
	secondaryVariant = ZafuBorder,
	background = ZafuBackground,
	surface = Color(0x00110000), // transparent so text will be primary on it
	error = ZafuErr,
	onPrimary = ZafuSurface,
	onSecondary = ZafuText,
	onBackground = ZafuText,
	onSurface = ZafuText,
	onError = ZafuErr,
)

// washi: zafu's light theme.
private val LightColorPaletteNew = lightColors(
	primary = WashiTextHigh,
	primaryVariant = WashiBackground,
	secondary = WashiTextDim,
	secondaryVariant = WashiBorder,
	background = WashiBackground,
	surface = Color(0x00110000), // transparent, used for rich text defaults
	error = WashiErr,
	onPrimary = WashiSurface,
	onSecondary = WashiText,
	onBackground = WashiText,
	onSurface = WashiText,
	onError = WashiErr,
)

@Composable
fun SignerNewTheme(
	darkTheme: Boolean = true,
	content: @Composable () -> Unit
) {
	val colors = if (darkTheme) DarkColorPaletteNew else LightColorPaletteNew

	MaterialTheme(
		colors = colors,
		typography = Typography,
		shapes = Shapes,
		content = content
	)
}

