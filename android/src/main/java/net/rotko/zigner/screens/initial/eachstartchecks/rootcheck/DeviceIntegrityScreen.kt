package net.rotko.zigner.screens.initial.eachstartchecks.rootcheck

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import net.rotko.zigner.ui.theme.red500fill12
import net.rotko.zigner.ui.theme.red500
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.security.DeviceIntegrity
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textTertiary


/**
 * States each integrity finding and what it means, then lets the owner
 * decide. Shown only when [DeviceIntegrity.Report.needsAttention].
 */
@Composable
fun DeviceIntegrityScreen(report: DeviceIntegrity.Report, onProceed: Callback) {
	val iconBackground = MaterialTheme.colors.red500fill12
	val iconTint = MaterialTheme.colors.red500

	val findings = buildList {
		if (report.bootloaderUnlocked) add(stringResource(R.string.device_integrity_bootloader_unlocked))
		if (report.bootState == DeviceIntegrity.BootState.RED) add(stringResource(R.string.device_integrity_boot_red))
		if (report.testKeys) add(stringResource(R.string.device_integrity_test_keys))
		report.rootEvidence.forEach { add(stringResource(R.string.device_integrity_su_found, it)) }
	}

	Column(
		horizontalAlignment = Alignment.CenterHorizontally,
		modifier = Modifier
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 24.dp, vertical = 32.dp),
	) {
		Box(
			contentAlignment = Alignment.Center,
			modifier = Modifier
				.size(120.dp)
				.background(iconBackground, CircleShape)
		) {
			Image(
				imageVector = Icons.Rounded.Warning,
				contentDescription = null,
				colorFilter = ColorFilter.tint(iconTint),
				modifier = Modifier.size(64.dp)
			)
		}
		Text(
			modifier = Modifier
				.fillMaxWidth(1f)
				.padding(vertical = 16.dp),
			text = stringResource(R.string.device_integrity_title),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleL,
			textAlign = TextAlign.Center,
		)
		findings.forEach { finding ->
			Text(
				modifier = Modifier
					.fillMaxWidth(1f)
					.padding(vertical = 6.dp),
				text = finding,
				color = MaterialTheme.colors.primary,
				style = SignerTypeface.BodyL,
			)
		}
		Text(
			modifier = Modifier
				.fillMaxWidth(1f)
				.padding(top = 16.dp),
			text = stringResource(R.string.device_integrity_self_reported_note),
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.CaptionM,
		)
		PrimaryButtonWide(
			modifier = Modifier.padding(top = 24.dp),
			label = stringResource(R.string.device_integrity_proceed),
			onClicked = onProceed,
		)
	}
}


@Preview(
	name = "light", group = "themes", uiMode = UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "themes", uiMode = UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewDeviceIntegrityScreen() {
	Box(modifier = Modifier.fillMaxSize(1f)) {
		SignerNewTheme() {
			DeviceIntegrityScreen(
				DeviceIntegrity.Report(
					bootState = DeviceIntegrity.BootState.ORANGE,
					deviceState = "unlocked",
					rootEvidence = listOf("/system/xbin/su"),
					testKeys = false,
				),
				onProceed = {},
			)
		}
	}
}
