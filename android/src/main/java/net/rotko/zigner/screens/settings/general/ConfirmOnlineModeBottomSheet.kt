package net.rotko.zigner.screens.settings.general

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.rotko.zigner.components.base.BottomSheetConfirmDialog
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.textSecondary


/**
 * Confirmation dialog for enabling online mode (first time).
 * This is a significant security decision that requires explicit user consent
 * and will permanently mark the wallet.
 */
@Composable
internal fun ConfirmOnlineModeBottomSheet(
	onCancel: Callback,
	onConfirm: Callback,
) {
	BottomSheetConfirmDialog(
		title = "Enable online mode?",
		message = "This will PERMANENTLY mark your wallet as having used online mode.\n\n" +
			"When enabled:\n" +
			"• Network radios (WiFi, Bluetooth) can be active\n" +
			"• Airgap warnings will be disabled\n" +
			"• History will record this change\n\n" +
			"This flag CANNOT be undone. Even if you switch back to offline mode, " +
			"the wallet will always show that online mode was used.\n\n" +
			"Authentication is required to confirm.",
		ctaLabel = "Enable Online Mode",
		onCancel = onCancel,
		isCtaDangerous = true,
		onCta = onConfirm,
	)
}

/**
 * Confirmation dialog for RE-enabling online mode (after having used it before).
 * Uses a countdown timer to ensure user reads the security implications.
 */
@Composable
internal fun ConfirmReEnableOnlineModeBottomSheet(
	onCancel: Callback,
	onConfirm: Callback,
) {
	var countdown by remember { mutableIntStateOf(5) }
	val canConfirm = countdown <= 0

	LaunchedEffect(Unit) {
		while (countdown > 0) {
			delay(1000)
			countdown--
		}
	}

	Column(
		modifier = Modifier
			.background(MaterialTheme.colors.fill6)
			.padding(24.dp)
	) {
		Text(
			text = "Re-enable online mode?",
			style = SignerTypeface.TitleL,
			color = MaterialTheme.colors.primary,
		)
		Spacer(modifier = Modifier.height(16.dp))
		Text(
			text = "You are switching back to online mode.\n\n" +
				"Security implications:\n" +
				"• Airgap protection will be disabled\n" +
				"• Network radios (WiFi, Bluetooth) can be active\n" +
				"• Your keys may be exposed to network-based attacks\n\n" +
				"Only enable this if you understand the risks and need network connectivity for your use case.",
			style = SignerTypeface.BodyL,
			color = MaterialTheme.colors.textSecondary,
		)
		Spacer(modifier = Modifier.height(24.dp))
		PrimaryButtonWide(
			label = if (canConfirm) "Enable Online Mode" else "Wait ${countdown}s...",
			isEnabled = canConfirm,
			modifier = Modifier.fillMaxWidth(),
		) {
			if (canConfirm) onConfirm()
		}
		Spacer(modifier = Modifier.height(8.dp))
		SecondaryButtonWide(
			label = "Cancel",
			modifier = Modifier.fillMaxWidth(),
			onClicked = onCancel,
		)
	}
}


@Preview(
	name = "light", group = "general", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "general",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewConfirmOnlineModeBottomSheet() {
	SignerNewTheme {
		ConfirmOnlineModeBottomSheet(
			{}, {},
		)
	}
}
