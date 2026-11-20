package net.rotko.zigner.screens.scan.camera

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.KeepScreenOn
import net.rotko.zigner.screens.settings.bluetooth.TicketCanon

/**
 * Full-screen ticket camera. Reuses the offline signer scanner:
 * [CameraViewWithPermission], [TransparentCutoutLayout], [ScanHeader]
 * (torch and close) and the same permission flow.
 */
@Composable
fun SezsTicketScanScreen(
	onClose: Callback,
	onTicket: (String) -> Unit,
) {
	val viewModel: CameraViewModel = viewModel(key = "sezs-ticket")
	viewModel.interceptPlainQr = true
	val plain by viewModel.plainQr.collectAsStateWithLifecycle()
	val currentOnTicket by rememberUpdatedState(onTicket)

	LaunchedEffect(plain) {
		val raw = plain ?: return@LaunchedEffect
		viewModel.resetPlainQr()
		val payload = TicketCanon.parseSezs3(raw) ?: return@LaunchedEffect
		currentOnTicket(payload)
	}

	Box(
		Modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background),
	) {
		CameraViewWithPermission(viewModel)
		CameraBottomText()
		Column {
			Spacer(
				modifier = Modifier
					.statusBarsPadding()
					.padding(top = 4.dp),
			)
			ScanHeader(onClose = onClose, viewModel = viewModel)
		}
	}
	KeepScreenOn()
}
