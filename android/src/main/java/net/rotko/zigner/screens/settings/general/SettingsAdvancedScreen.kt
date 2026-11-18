package net.rotko.zigner.screens.settings.general

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.domain.Callback

@Composable
internal fun SettingsAdvancedScreen(
	onBack: Callback,
	onVerifierCertificate: Callback,
	onOnlineModeToggle: Callback,
	onWipeData: Callback,
	isOnlineModeEnabled: Boolean,
	wasOnlineModeEverEnabled: Boolean,
	developerOptionsRevealed: Boolean,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.statusBarsPadding()
	) {
		ScreenHeaderClose(
			title = "Advanced settings",
			onClose = onBack,
		)
		Column(
			modifier = Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState()),
			verticalArrangement = Arrangement.spacedBy(2.dp)
		) {
			SettingsElement(
				name = stringResource(R.string.settings_verifier_certificate),
				icon = Icons.Outlined.VerifiedUser,
				onClick = onVerifierCertificate,
			)

			// Online Mode is a hidden developer option. It only appears after the user
			// deliberately unlocks developer options via the 5-tap gesture on the
			// verifier certificate screen. Until then, the app is fully air-gapped and
			// this toggle is not rendered at all. Revealing the option does NOT enable
			// online mode - the toggle still defaults off and turning it on requires the
			// explicit confirmation + authentication flow below.
			if (developerOptionsRevealed) {
				SettingsToggleElement(
					name = "Online Mode",
					description = if (wasOnlineModeEverEnabled && !isOnlineModeEnabled) {
						"Previously used in online mode"
					} else {
						"Allow use with WiFi/Bluetooth enabled"
					},
					icon = Icons.Outlined.Wifi,
					isChecked = isOnlineModeEnabled,
					showWarning = wasOnlineModeEverEnabled && !isOnlineModeEnabled,
					onClick = onOnlineModeToggle,
				)
			}

			SettingsElement(
				name = stringResource(R.string.settings_wipe_data),
				icon = Icons.Outlined.DeleteForever,
				isDanger = true,
				skipChevron = true,
				onClick = onWipeData
			)
		}
	}
}
