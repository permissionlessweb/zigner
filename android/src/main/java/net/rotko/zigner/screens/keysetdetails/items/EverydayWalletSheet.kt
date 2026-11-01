package net.rotko.zigner.screens.keysetdetails.items

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import net.rotko.zigner.components.base.PrimaryButtonWide
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.rotko.zigner.domain.DisableScreenshots
import net.rotko.zigner.screens.keysetdetails.export.HotWalletExportService
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary

/**
 * The everyday wallet: a separate 12-word phrase derived from this wallet's
 * seed that zafu keeps online for daily use (ZID, pro, small payments).
 * It cannot spend this wallet's funds. Opened from the wallet menu; the QR
 * shows only after the screen lock, and screenshots are blocked while it is up.
 */
@Composable
fun EverydayWalletSheet(
	seedName: String,
	getSeedPhrase: suspend (String) -> String?,
	modifier: Modifier = Modifier,
) {
	var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
	var loading by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp, vertical = 16.dp),
		verticalArrangement = Arrangement.spacedBy(12.dp),
	) {
		Text(
			text = "Everyday wallet",
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleL,
		)
		Text(
			text = "A separate wallet Zafu keeps online for daily use. It cannot spend this wallet's funds.",
			color = MaterialTheme.colors.textSecondary,
			style = SignerTypeface.BodyM,
		)

		val bitmap = qrBitmap
		if (bitmap == null) {
			PrimaryButtonWide(
				label = if (loading) "Unlocking..." else "Show QR",
				isEnabled = !loading,
				onClicked = {
					loading = true
					scope.launch {
						getSeedPhrase(seedName)?.let { phrase ->
							qrBitmap = HotWalletExportService().getHotWalletQr(phrase)
						}
						loading = false
					}
				},
			)
		} else {
			// The QR is a spending phrase: no screenshots, no recents thumbnail.
			DisableScreenshots()
			Box(
				modifier = Modifier
					.fillMaxWidth()
					.aspectRatio(1f)
					.background(Color.White),
				contentAlignment = Alignment.Center,
			) {
				Image(
					bitmap = bitmap,
					contentDescription = "Everyday wallet QR",
					contentScale = ContentScale.Fit,
					filterQuality = FilterQuality.None,
					modifier = Modifier
						.fillMaxSize()
						.padding(8.dp),
				)
			}
			Text(
				text = "Scan with Zafu",
				color = MaterialTheme.colors.textTertiary,
				style = SignerTypeface.BodyS,
				textAlign = TextAlign.Center,
				modifier = Modifier.fillMaxWidth(),
			)
		}
	}
}
