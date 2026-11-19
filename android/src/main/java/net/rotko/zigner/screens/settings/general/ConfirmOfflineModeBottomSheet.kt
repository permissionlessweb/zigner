package net.rotko.zigner.screens.settings.general

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import net.rotko.zigner.components.base.BottomSheetConfirmDialog
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.SignerNewTheme


/**
 * Confirmation dialog for disabling online mode (returning to offline/airgap mode).
 * Reminds user that the wallet is permanently marked as having used online mode.
 */
@Composable
internal fun ConfirmOfflineModeBottomSheet(
	onCancel: Callback,
	onConfirm: Callback,
) {
	BottomSheetConfirmDialog(
		title = "Return to offline mode?",
		message = "This will re-enable airgap enforcement.\n\n" +
			"Note: Your wallet history permanently shows that online mode was used. " +
			"This cannot be reversed.\n\n" +
			"When offline mode is active:\n" +
			"• You'll be warned if WiFi/Bluetooth is enabled\n" +
			"• Network exposure is logged to history\n" +
			"• Full airgap protection is restored\n\n" +
			"Authentication is required to confirm.",
		ctaLabel = "Return to Offline Mode",
		onCancel = onCancel,
		isCtaDangerous = false,
		onCta = onConfirm,
	)
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
private fun PreviewConfirmOfflineModeBottomSheet() {
	SignerNewTheme {
		ConfirmOfflineModeBottomSheet(
			onCancel = {},
			onConfirm = {},
		)
	}
}
