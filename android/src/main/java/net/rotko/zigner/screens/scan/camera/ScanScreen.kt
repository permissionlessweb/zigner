package net.rotko.zigner.screens.scan.camera

import android.content.res.Configuration
import androidx.camera.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import net.rotko.zigner.R
import net.rotko.zigner.domain.scan.ScanCapability
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.KeepScreenOn
import net.rotko.zigner.screens.scan.camera.*
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

@Composable
fun ScanScreen(
	onClose: Callback,
	performPayloads: suspend (String) -> Unit,
	onBananaSplit: (List<String>) -> Unit,
	onPenumbraSignRequest: (String) -> Unit = {},
	onCosmosSignRequest: (String) -> Unit = {},
	onZcashSimpleSign: (String) -> Unit = {},
	onZcashModulePczt: (String) -> Unit = {},
	onModulePackage: (ByteArray) -> Unit = {},
	onReleaseSignPrefix: (ByteArray) -> Unit = {},
	onUrBackupRestore: (List<String>) -> Unit = {},
	onZcashNotes: (List<String>) -> Unit = {},
	onZcashPczt: (List<String>) -> Unit = {},
	onFrost: (org.json.JSONObject) -> Unit = {},
	onAuth: (org.json.JSONObject) -> Unit = {},
	onZidSign: (org.json.JSONObject) -> Unit = {},
) {
	val viewModel: CameraViewModel = viewModel()

	// Load the scan policy before any frame is processed. Without this the
	// view model falls back to defaults, which still exclude Substrate,
	// Penumbra and Cosmos - a missed call weakens the user's own choices but
	// never opens the parsers the gate exists to keep unreachable.
	val policyContext = LocalContext.current
	LaunchedEffect(policyContext) { viewModel.applyPolicy(policyContext) }

	// A refused payload must say so. Silently ignoring a QR code looks like a
	// broken camera, and someone debugging that will reach for the setting
	// that turns the parser back on without knowing why it was off.
	val refused by viewModel.refusedCapability.collectAsStateWithLifecycle()
	LaunchedEffect(refused) {
		val cap = refused ?: return@LaunchedEffect
		val message = if (cap == ScanCapability.SUBSTRATE || cap == ScanCapability.COSMOS) {
			// Substrate and Cosmos have no screens left to route to - this is
			// not "off, enable it", it is not a request zigner reads at all.
			policyContext.getString(R.string.scan_unsupported_request)
		} else {
			"Zigner doesn't read ${cap.label} codes."
		}
		Toast.makeText(
			policyContext,
			message,
			Toast.LENGTH_LONG,
		).show()
		viewModel.clearRefusal()
	}

	val captured by viewModel.captured.collectAsStateWithLifecycle()
	val total by viewModel.total.collectAsStateWithLifecycle()

	val currentPerformPayloads by rememberUpdatedState(performPayloads)
	val currentOnBananaSplit by rememberUpdatedState(onBananaSplit)
	val currentOnPenumbraSignRequest by rememberUpdatedState(onPenumbraSignRequest)
	val currentOnCosmosSignRequest by rememberUpdatedState(onCosmosSignRequest)
	val currentOnZcashSimpleSign by rememberUpdatedState(onZcashSimpleSign)
	val currentOnZcashModulePczt by rememberUpdatedState(onZcashModulePczt)
	val currentOnModulePackage by rememberUpdatedState(onModulePackage)
	val currentOnReleaseSignPrefix by rememberUpdatedState(onReleaseSignPrefix)
	val currentOnUrBackupRestore by rememberUpdatedState(onUrBackupRestore)
	val currentOnZcashNotes by rememberUpdatedState(onZcashNotes)
	val currentOnZcashPczt by rememberUpdatedState(onZcashPczt)
	val currentOnFrost by rememberUpdatedState(onFrost)
	val currentOnAuth by rememberUpdatedState(onAuth)
	val currentOnZidSign by rememberUpdatedState(onZidSign)

	LaunchedEffect(viewModel) {
		//there can be data from last time camera was open since it's scanning during transition to a new screen
		viewModel.resetPendingTransactions()

		launch {
			viewModel.pendingTransactionPayloads
				.filter { it.isNotEmpty() }
				.collect {
					//scanned qr codes is signer transaction qr code
					currentPerformPayloads(it.joinToString(separator = ""))
					viewModel.resetPendingTransactions()
				}
		}

		launch {
			viewModel.bananaSplitPayload
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { qrData ->
					currentOnBananaSplit(qrData)
				}
		}

		// Penumbra sign request handler
		launch {
			viewModel.penumbraSignRequestPayload
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { qrData ->
					currentOnPenumbraSignRequest(qrData)
					viewModel.resetPenumbraSignRequest()
				}
		}

		// Cosmos sign request handler
		launch {
			viewModel.cosmosSignRequestPayload
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { qrData ->
					currentOnCosmosSignRequest(qrData)
					viewModel.resetCosmosSignRequest()
				}
		}

		// Zcash simple sign request handler
		launch {
			viewModel.zcashSimpleSignPayload
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { qrData ->
					currentOnZcashSimpleSign(qrData)
					viewModel.resetZcashSimpleSign()
				}
		}

		// Zcash PCZT protocol-module request handler (530403/530404)
		launch {
			viewModel.zcashModulePcztPayload
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { qrData ->
					currentOnZcashModulePczt(qrData)
					viewModel.resetZcashModulePczt()
				}
		}

		// Module package handler (ur:zigner-module with package payload)
		launch {
			viewModel.modulePackageBytes
				.filterNotNull()
				.collect { packageBytes ->
					currentOnModulePackage(packageBytes)
					viewModel.resetModulePackageBytes()
				}
		}

		// Release-signing prefix handler (plain base64 "ZIGM" manifest prefix)
		launch {
			viewModel.releaseSignPrefix
				.filterNotNull()
				.collect { prefixBytes ->
					currentOnReleaseSignPrefix(prefixBytes)
					viewModel.resetReleaseSignPrefix()
				}
		}

		// UR backup restore handler
		launch {
			viewModel.urBackupComplete
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { urFrames ->
					currentOnUrBackupRestore(urFrames)
					viewModel.resetUrBackup()
				}
		}

		// Zcash notes sync handler
		launch {
			viewModel.zcashNotesComplete
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { urFrames ->
					currentOnZcashNotes(urFrames)
					viewModel.resetZcashNotes()
				}
		}

		// PCZT handler
		launch {
			viewModel.zcashPcztComplete
				.filterNotNull()
				.filter { it.isNotEmpty() }
				.collect { urFrames ->
					currentOnZcashPczt(urFrames)
					viewModel.resetZcashPczt()
				}
		}

		// FROST multisig handler
		launch {
			viewModel.frostPayload
				.filterNotNull()
				.collect { json ->
					currentOnFrost(json)
					viewModel.resetFrostPayload()
				}
		}

		// Auth handler
		launch {
			viewModel.authPayload
				.filterNotNull()
				.collect { json ->
					currentOnAuth(json)
					viewModel.resetAuthPayload()
				}
		}

		// ZID sign handler
		launch {
			viewModel.zidSignPayload
				.filterNotNull()
				.collect { json ->
					currentOnZidSign(json)
					viewModel.resetZidSignPayload()
				}
		}
	}

	Box(
		Modifier
			.fillMaxSize(1f)
			.background(MaterialTheme.colors.background)
	) {
		CameraViewWithPermission(viewModel)
		CameraBottomText()
		Column() {
			Spacer(
				modifier = Modifier
					.statusBarsPadding()
					.padding(top = 4.dp)
			)
			ScanHeader(onClose = onClose, viewModel = viewModel)

			Spacer(modifier = Modifier.weight(1f))
			val capturedCpy = captured
			if (capturedCpy != null) {
				ScanProgressBar(capturedCpy, total) { viewModel.resetScanValues() }
			}
		}

	}
	KeepScreenOn()
}

@Composable
private fun CameraBottomText() {
	Column(
		Modifier
			.fillMaxSize(1f)
			.padding(horizontal = 48.dp),
	) {
		val paddingTillEndOfCutout =
			(LocalConfiguration.current.screenHeightDp.dp / 2).plus(
				(LocalConfiguration.current.screenWidthDp.dp / 2) - ScanConstants.CLIP_SIDE_PADDING * 2
			)
		Spacer(modifier = Modifier.padding(top = paddingTillEndOfCutout))
		Spacer(modifier = Modifier.weight(0.5f))
		Text(
			text = stringResource(R.string.camera_screen_header_single),
			color = Color.White,
			style = SignerTypeface.TitleL,
			textAlign = TextAlign.Center,
			modifier = Modifier.fillMaxWidth(1f),
		)
		Spacer(modifier = Modifier.padding(bottom = 12.dp))
		Text(
			text = stringResource(R.string.camera_screen_description_single),
			color = Color.White,
			style = SignerTypeface.TitleS,
			textAlign = TextAlign.Center,
			modifier = Modifier.fillMaxWidth(1f),
		)
		Spacer(modifier = Modifier.weight(0.5f))
	}
}


@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun CameraViewWithPermission(viewModel: CameraViewModel) {
	if (LocalInspectionMode.current) return

	val rationalShown = remember {
		mutableStateOf(false)
	}
	val cameraPermissionState =
		rememberPermissionState(android.Manifest.permission.CAMERA)
	if (cameraPermissionState.status.isGranted) {

		//camera content itself!
		CameraViewInternal(viewModel)
		TransparentCutoutLayout()

	} else if (cameraPermissionState.status.shouldShowRationale
		&& !rationalShown.value
	) {
		AlertDialog(
			onDismissRequest = {
				rationalShown.value = true
			},
			confirmButton = {
				val scope = rememberCoroutineScope()
				Button(
					colors = ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.background),
					onClick = {
						scope.launch { cameraPermissionState.launchPermissionRequest() }
						rationalShown.value = true
					},
				) {
					Text(text = stringResource(R.string.generic_ok))
				}
			},
			title = { Text(text = stringResource(R.string.camera_jastification_title)) },
			text = { Text(text = stringResource(R.string.camera_jastification_message)) },
		)
	} else {
		Text(
			text = stringResource(R.string.camera_no_permission_text),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.BodyL,
			textAlign = TextAlign.Center,
			modifier = Modifier
				.fillMaxWidth(1f)
				.padding(top = 150.dp),
		)
		rationalShown.value = true
		LaunchedEffect(key1 = Unit) {
			launch { cameraPermissionState.launchPermissionRequest() }
		}
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
private fun PreviewScanScreen() {
	SignerNewTheme {
		Box(modifier = Modifier.size(350.dp, 550.dp)) {
			ScanScreen({}, { _ -> }, { _ -> }, { _ -> }, { _ -> }, { _ -> })
		}
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
private fun PreviewBottomText() {
	SignerNewTheme {
		Box() {
			TransparentCutoutLayout()
			CameraBottomText()
		}
	}
}
