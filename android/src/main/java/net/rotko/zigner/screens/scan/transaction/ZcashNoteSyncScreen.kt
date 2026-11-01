package net.rotko.zigner.screens.scan.transaction

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*
import io.parity.signer.uniffi.ZcashNoteSyncResult
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Zcash note sync result screen
 * Shows verified note count, balance, and anchor info after QR scan
 */
@Composable
fun ZcashNoteSyncScreen(
	result: ZcashNoteSyncResult,
	onDone: Callback,
	modifier: Modifier = Modifier,
) {
	// The anchor can be read as its block height or that block's real date (the
	// block header's own time — chain truth, not this device's clock). Tap to
	// toggle; the date only exists when the bundle carried anchorTime.
	var showAnchorDate by remember { mutableStateOf(false) }
	Column(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.padding(16.dp)
	) {
		// Header
		Text(
			text = "Zcash note sync",
			style = SignerTypeface.TitleL,
			color = MaterialTheme.colors.primary,
			modifier = Modifier.padding(bottom = 16.dp)
		)

		// Scrollable content
		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(12.dp)
		) {
			// Status card
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RectangleShape)
					.background(MaterialTheme.colors.fill6)
					.padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Text(
					text = "Verified",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textTertiary
				)
				Text(
					text = "${result.notesVerified} note${if (result.notesVerified.toInt() == 1) "" else "s"} verified",
					style = SignerTypeface.TitleS,
					color = MaterialTheme.colors.primary
				)
			}

			// Balance card — freshness is the point: the chain block this balance
			// was proven at, plus the device-clock time the notes were scanned.
			// A verified balance can go stale (funds moved after the scan), so the
			// block number is shown right here, not buried in a caption below.
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RectangleShape)
					.background(MaterialTheme.colors.fill6)
					.padding(16.dp),
				verticalArrangement = Arrangement.spacedBy(8.dp)
			) {
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.SpaceBetween,
					verticalAlignment = Alignment.CenterVertically
				) {
					Text(
						text = "Verified balance",
						style = SignerTypeface.LabelM,
						color = MaterialTheme.colors.textTertiary
					)
					val hasAnchorDate = result.anchorTime > 0u
					Text(
						text = if (showAnchorDate && hasAnchorDate)
							"as of ${formatSyncDate(result.anchorTime.toULong())}"
						else
							"as of block ${NumberFormat.getNumberInstance(Locale.US).format(result.anchorHeight.toLong())}",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textSecondary,
						modifier = if (hasAnchorDate)
							Modifier.clickable { showAnchorDate = !showAnchorDate }
						else
							Modifier
					)
				}
				Text(
					text = formatZec(result.totalBalance),
					style = SignerTypeface.TitleL,
					color = MaterialTheme.colors.primary
				)
				Text(
					text = if (result.syncedAt > 0uL)
						"verified ${formatSyncDate(result.syncedAt)}"
					else
						"verification time unavailable",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary
				)
			}

			// Network badge
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RectangleShape)
					.background(MaterialTheme.colors.fill6)
					.padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Text(
					text = if (result.mainnet) "Mainnet" else "Testnet",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textTertiary
				)
				Text(
					text = "Anchor height: ${NumberFormat.getNumberInstance(Locale.US).format(result.anchorHeight.toLong())}",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textSecondary
				)
				Text(
					text = "Anchor: ${result.anchorHex.take(16)}...",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textSecondary
				)
			}

			// Anchor attestation status
			Column(
				modifier = Modifier
					.fillMaxWidth()
					.clip(RectangleShape)
					.background(MaterialTheme.colors.fill6)
					.padding(12.dp),
				verticalArrangement = Arrangement.spacedBy(4.dp)
			) {
				Text(
					text = if (result.anchorVerified) "Anchor: Attested by threshold group"
						else "Anchor: From zcli (single-signer)",
					style = SignerTypeface.LabelM,
					color = if (result.anchorVerified) MaterialTheme.colors.primary
						else MaterialTheme.colors.textTertiary
				)
				if (!result.anchorVerified) {
					Text(
						text = "No FROST wallet — anchor trust relies on zcli. Set up a threshold wallet for independent verification.",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary
					)
				}
			}
		}

		SignerDivider()
		Spacer(modifier = Modifier.height(16.dp))

		PrimaryButtonWide(
			label = "Done",
			onClicked = onDone
		)
	}
}

private fun formatZec(zatoshis: ULong): String {
	val zec = zatoshis.toLong() / 100_000_000.0
	return "%.8f ZEC".format(zec)
}

/** Device-clock time the notes were stored (wall clock on the cold wallet). */
private fun formatSyncDate(syncedAt: ULong): String =
	try {
		SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(syncedAt.toLong() * 1000L))
	} catch (e: Exception) {
		"unknown"
	}
