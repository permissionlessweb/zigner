package net.rotko.zigner.screens.keysetdetails.items

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
 * Shows a "Zafu Hot Wallet" QR code section on the key set details page.
 *
 * When tapped, requests the seed phrase via getSeedPhrase callback
 * (triggers biometric auth), derives the hot wallet mnemonic, and
 * displays it as a ur:zafu-hot-wallet QR code. Zafu scans this
 * to create a deterministic hot wallet for ZID, pro, and daily use.
 *
 * The QR is hidden by default - user must tap and authenticate to reveal.
 */
@Composable
fun HotWalletQrSection(
	seedName: String,
	getSeedPhrase: suspend (String) -> String?,
	modifier: Modifier = Modifier,
) {
	var qrBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
	var loading by remember { mutableStateOf(false) }
	var expanded by remember { mutableStateOf(false) }
	val scope = rememberCoroutineScope()

	val plateShape = RectangleShape

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			text = if (expanded) "zafu hot wallet" else "show zafu hot wallet QR",
			color = MaterialTheme.colors.textSecondary,
			style = SignerTypeface.BodyM,
			textAlign = TextAlign.Center,
			modifier = Modifier
				.clickable {
					if (!expanded && !loading) {
						loading = true
						scope.launch {
							val phrase = getSeedPhrase(seedName)
							if (phrase != null) {
								val service = HotWalletExportService()
								qrBitmap = service.getHotWalletQr(phrase)
								expanded = true
							}
							loading = false
						}
					} else {
						expanded = false
						qrBitmap = null
					}
				}
				.padding(vertical = 8.dp)
		)

		if (loading && !expanded) {
			Text(
				text = "authenticating...",
				color = MaterialTheme.colors.textTertiary,
				style = SignerTypeface.BodyS,
				modifier = Modifier.padding(vertical = 16.dp)
			)
		}

		if (expanded) {
			// The QR encodes a 12-word spending mnemonic for the derived hot
			// wallet - block screenshots and the recents thumbnail while it
			// is visible, same as any other seed/secret QR.
			DisableScreenshots()

			qrBitmap?.let { bitmap ->
				Box(
					modifier = Modifier
						.fillMaxWidth()
						.aspectRatio(1f)
						.clip(plateShape)
						.background(Color.White, plateShape),
					contentAlignment = Alignment.Center,
				) {
					Image(
						bitmap = bitmap,
						contentDescription = "zafu hot wallet QR",
						contentScale = ContentScale.Fit,
						// no size modifier = intrinsic PNG size (tiny on hidpi);
						// fill the plate and keep modules crisp when upscaling
						filterQuality = FilterQuality.None,
						modifier = Modifier
							.fillMaxSize()
							.padding(8.dp)
					)
				}

				Text(
					text = "scan with zafu to create hot wallet",
					color = MaterialTheme.colors.textTertiary,
					style = SignerTypeface.BodyS,
					textAlign = TextAlign.Center,
					modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
				)
			}
		}
	}
}
