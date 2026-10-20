package net.rotko.zigner.components.networkicon

import android.annotation.SuppressLint
import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.AutoSizeText
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.ui.theme.SignerNewTheme


@Composable
fun NetworkIcon(
	networkLogoName: String,
	modifier: Modifier = Modifier,
	size: Dp = 32.dp,
) {
	val icon = getIconForNetwork(networkLogoName.lowercase())
	if (icon != null) {
		Image(
			painter = icon,
			contentDescription = null,
			modifier = modifier
				.clip(CircleShape)
				.size(size),
		)
	} else {
		val networkColors = ServiceLocator.unknownNetworkColorsGenerator
			.getBackground(networkLogoName.lowercase())
			.toUnknownNetworkColorsDrawable()
		val chars = networkLogoName.take(1).uppercase()
		UnknownNetworkIcon(networkColors, chars, size, modifier)
	}
}

@Composable
private fun UnknownNetworkIcon(
	networkColors: UnknownNetworkColorDrawable,
	chars: String,
	size: Dp,
	modifier: Modifier = Modifier
) {
	Box(
		modifier = modifier
			.size(size)
			.background(networkColors.background, CircleShape),
		contentAlignment = Alignment.Center
	) {
		AutoSizeText(
			text = chars,
			color = networkColors.text,
		)
	}
}

@Composable
@SuppressLint("DiscouragedApi")
private fun getIconForNetwork(networkName: String): Painter? {
//	val resource = resources.getIdentifier(/* name = */ "network_$networkName",
//		/* defType = */"drawable",/* defPackage = */packageName)

	val id = getResourceIdForNetwork(networkName)

	return if (id > 0) {
		painterResource(id = id)
	} else {
		null
	}
}

/**
 * Those icons and names taken from iOS where they taken from
 * https://metadata.novasama.io/
 * It is used just to show some nice icons for known networks, orherwise
 * generated unknown icon will be shown
 */
@Composable
private fun getResourceIdForNetwork(networkName: String) =
	when (networkName) {
		"penumbra" -> R.drawable.network_penumbra
		"zcash" -> R.drawable.network_zcash
		else -> -1
	}


@Preview(
	name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewEmptyIcon() {
	SignerNewTheme {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			NetworkIcon("")
		}
	}
}

@Preview(
	name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewNetworkIconSizes() {
	SignerNewTheme {
		Column(
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			NetworkIcon("zcash")
			NetworkIcon("some_unknown")
			NetworkIcon("zcash", size = 18.dp)
			NetworkIcon("some_unknown2", size = 18.dp)
			NetworkIcon("zcash", size = 56.dp)
			NetworkIcon("some_unknown3", size = 56.dp)
		}
	}
}


@Preview(
	name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewNetworkIconUnknownIcons() {
	SignerNewTheme {
		Column {
			val colors = UnknownNetworkColors.values()
			colors.forEach { color ->
				UnknownNetworkIcon(
					networkColors = color.toUnknownNetworkColorsDrawable(),
					chars = "W",
					size = 24.dp
				)
			}
		}
	}
}



