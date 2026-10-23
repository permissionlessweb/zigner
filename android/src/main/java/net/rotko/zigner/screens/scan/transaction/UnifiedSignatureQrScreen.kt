package net.rotko.zigner.screens.scan.transaction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.qrcode.AnimatedQrKeysInfo
import net.rotko.zigner.components.qrcode.EmptyQrCodeProvider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*

/**
 * Unified signature QR display screen.
 * Replaces per-network signature screens (Penumbra, Cosmos, etc.)
 * Supports both hex QR (single) and UR parts (animated) display.
 */
@Composable
fun UnifiedSignatureQrScreen(
	result: SignatureResult,
	onDone: Callback,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.padding(16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Text(
			text = "${result.networkName} Signature",
			style = SignerTypeface.TitleL,
			color = MaterialTheme.colors.primary,
			modifier = Modifier.padding(bottom = 8.dp),
		)

		Text(
			text = "Scan this QR code with your companion wallet to complete the transaction",
			style = SignerTypeface.BodyL,
			color = MaterialTheme.colors.textSecondary,
			modifier = Modifier.padding(bottom = 24.dp),
		)

		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth(),
			contentAlignment = Alignment.Center,
		) {
			when (result) {
				is SignatureResult.HexQr -> {
					val qrData: List<List<UByte>> = listOf(
						result.hexString.toByteArray(Charsets.US_ASCII).map { it.toUByte() }
					)
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						AnimatedQrKeysInfo<List<List<UByte>>>(
							input = qrData,
							provider = EmptyQrCodeProvider(),
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 24.dp),
						)
						SelectionContainer {
							Text(
								text = result.hexString,
								style = SignerTypeface.BodyM,
								color = MaterialTheme.colors.textSecondary,
								modifier = Modifier.padding(top = 12.dp),
							)
						}
						if (result.publicKeyHex != null) {
							SelectionContainer {
								Text(
									text = "public key ${result.publicKeyHex}",
									style = SignerTypeface.BodyM,
									color = MaterialTheme.colors.textSecondary,
									modifier = Modifier.padding(top = 8.dp),
								)
							}
						}
					}
				}
				is SignatureResult.UrParts -> {
					val qrData: List<List<UByte>> = result.parts.map { part ->
						part.toByteArray(Charsets.US_ASCII).map { it.toUByte() }
					}
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						AnimatedQrKeysInfo<List<List<UByte>>>(
							input = qrData,
							provider = EmptyQrCodeProvider(),
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 24.dp),
						)
						if (result.parts.size > 1) {
							Text(
								text = "${result.parts.size} codes. Keep the camera on them until the page fills in.",
								style = SignerTypeface.BodyM,
								color = MaterialTheme.colors.textSecondary,
								modifier = Modifier.padding(top = 12.dp),
							)
						}
						if (result.publicKeyHex != null) {
							SelectionContainer {
								Text(
									text = "public key ${result.publicKeyHex}",
									style = SignerTypeface.BodyM,
									color = MaterialTheme.colors.textSecondary,
									modifier = Modifier.padding(top = 8.dp),
								)
							}
						}
					}
				}
			}
		}

		SignerDivider()
		Spacer(modifier = Modifier.height(16.dp))

		PrimaryButtonWide(
			label = stringResource(R.string.generic_done),
			onClicked = onDone,
		)
	}
}
