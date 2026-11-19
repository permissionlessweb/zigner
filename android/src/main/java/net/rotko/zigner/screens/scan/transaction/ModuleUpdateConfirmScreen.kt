package net.rotko.zigner.screens.scan.transaction

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rotko.zigner.components.security.TapjackGuard
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*
import io.parity.signer.uniffi.ModulePackageInfo

// Confirm screen for a staged protocol-module update.
//
// Everything rendered here comes out of `module_verify_package`, which only
// returns after the 2-of-3 release signature over the manifest has verified.
// So the version, the changelog and the hash below are not advisory text
// travelling next to the update - they are inside the signed bytes. A party
// who tampers with the changelog invalidates the signature and never reaches
// this screen.
//
// The user's job here is the one check the device cannot do for itself:
// deciding whether THIS release is one they meant to install. They do that by
// comparing the fingerprint against the one published alongside the changelog.

/**
 * Group a hex digest into short blocks so it can actually be compared by eye.
 *
 * A bare 64-char hex run does not get checked honestly - people compare the
 * first two and last two characters and call it a match, which is precisely
 * the comparison a preimage-grinding attacker wins. We show the first 16
 * nibbles in 4-char groups: short enough to be read aloud and genuinely
 * compared, and 64 bits of digest is far past what an attacker can grind
 * against a fixed target.
 */
internal fun fingerprintOf(hashHex: String): String =
	hashHex.take(16).chunked(4).joinToString(" ").uppercase()

@Composable
fun ModuleUpdateConfirmScreen(
	info: ModulePackageInfo,
	currentVersion: Long,
	onConfirm: Callback,
	onCancel: Callback,
) {
	// Approval screen: reject touches while an overlay is up.
	TapjackGuard()

	Column(
		modifier = Modifier
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 24.dp)
			.padding(top = 32.dp, bottom = 24.dp),
	) {
		Text(
			text = "Verify this update",
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleL,
		)
		Spacer(Modifier.padding(top = 8.dp))
		Text(
			text = "The signature on this update has been checked. Confirm it is " +
				"the release you intended to install.",
			color = MaterialTheme.colors.textSecondary,
			style = SignerTypeface.BodyM,
		)

		Spacer(Modifier.padding(top = 24.dp))
		LabelledRow("Module version", "$currentVersion → ${info.moduleVersion}")
		SignerDivider()
		LabelledRow("Minimum app version", info.minKernelVersion.toString())

		Spacer(Modifier.padding(top = 24.dp))
		Text(
			text = "Fingerprint",
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.BodyM,
		)
		Spacer(Modifier.padding(top = 4.dp))
		Text(
			text = "Must match the fingerprint published with the changelog.",
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.CaptionM,
		)
		Spacer(Modifier.padding(top = 8.dp))
		Text(
			text = fingerprintOf(info.moduleHashHex),
			color = MaterialTheme.colors.primary,
			fontFamily = FontFamily.Monospace,
			fontSize = 18.sp,
			letterSpacing = 1.sp,
			modifier = Modifier
				.fillMaxWidth()
				.clip(RectangleShape)
				.background(MaterialTheme.colors.fill6)
				.padding(horizontal = 16.dp, vertical = 12.dp),
		)

		Spacer(Modifier.padding(top = 24.dp))
		Text(
			text = "Signed changelog",
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.BodyM,
		)
		Spacer(Modifier.padding(top = 8.dp))
		Text(
			// Empty is legal on the wire but never legitimate for a release we
			// publish, so say so rather than showing a blank panel that reads
			// as "nothing changed".
			text = info.description.ifBlank {
				"(no changelog was included in this signed update)"
			},
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.BodyM,
			modifier = Modifier
				.fillMaxWidth()
				.clip(RectangleShape)
				.background(MaterialTheme.colors.fill6)
				.padding(16.dp),
		)

		Spacer(Modifier.padding(top = 32.dp))
		PrimaryButtonWide(label = "Install update", onClicked = onConfirm)
		Spacer(Modifier.padding(top = 8.dp))
		SecondaryButtonWide(label = "Cancel", onClicked = onCancel)
	}
}

@Composable
private fun LabelledRow(label: String, value: String) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(vertical = 12.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text = label,
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.BodyM,
		)
		Text(
			text = value,
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.BodyM,
		)
	}
}
