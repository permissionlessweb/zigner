package net.rotko.zigner.screens.scan

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import net.rotko.zigner.R
import net.rotko.zigner.bottomsheets.password.EnterPassword
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.FakeNavigator
import net.rotko.zigner.screens.scan.backuprestore.BackupRestoreSubgraph
import net.rotko.zigner.screens.scan.bananasplitrestore.BananaSplitSubgraph
import net.rotko.zigner.screens.scan.camera.ScanScreen
import net.rotko.zigner.screens.scan.elements.WrongPasswordBottomSheet
import net.rotko.zigner.screens.scan.errors.LocalErrorBottomSheet
import net.rotko.zigner.screens.scan.errors.LocalErrorSheetModel
import net.rotko.zigner.screens.scan.transaction.TransactionPreviewType
import net.rotko.zigner.screens.scan.transaction.TransactionsScreenFull
import net.rotko.zigner.screens.scan.transaction.AuthChallengeScreen
import net.rotko.zigner.screens.scan.transaction.ZidSignScreen
import net.rotko.zigner.screens.scan.transaction.frost.FrostDkgScreen
import net.rotko.zigner.screens.scan.transaction.frost.FrostSignScreen
import net.rotko.zigner.screens.scan.transaction.ModulePcztScreen
import net.rotko.zigner.screens.scan.transaction.ReleaseSignIntegratedScreen
import net.rotko.zigner.screens.scan.transaction.ModuleUpdateConfirmScreen
import net.rotko.zigner.screens.scan.transaction.ZcashNoteSyncScreen
import net.rotko.zigner.screens.scan.transaction.ZcashPcztScreen
import net.rotko.zigner.screens.scan.transaction.UnifiedTransactionScreen
import net.rotko.zigner.screens.scan.transaction.UnifiedSignatureQrScreen
import net.rotko.zigner.screens.scan.transaction.previewType
import net.rotko.zigner.ui.BottomSheetWrapperRoot
import io.parity.signer.uniffi.Action
import net.rotko.zigner.domain.module.ModuleSlotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Navigation Subgraph with compose nav controller for those Key Set screens which are not part of general
 * Rust-controlled navigation
 */
@Composable
fun ScanNavSubgraph(
	onCloseCamera: Callback,
	seedNameSuggestion: String?,
	openKeySet:(seedName: String) -> Unit,
) {
	val scanViewModel: ScanViewModel = viewModel()

	val transactions = scanViewModel.transactions.collectAsStateWithLifecycle()
	val signature = scanViewModel.signature.collectAsStateWithLifecycle()
	val bananaSplitPassword =
		scanViewModel.bananaSplitPassword.collectAsStateWithLifecycle()
	val transactionError =
		scanViewModel.transactionError.collectAsStateWithLifecycle()
	val passwordModel = scanViewModel.passwordModel.collectAsStateWithLifecycle()
	val errorWrongPassword =
		scanViewModel.errorWrongPassword.collectAsStateWithLifecycle()

	// Unified signing state
	val unifiedSignRequest = scanViewModel.signRequest.collectAsStateWithLifecycle()
	val unifiedSignatureResult = scanViewModel.signatureResult.collectAsStateWithLifecycle()

	// UR backup restore state
	val urBackupFrames = scanViewModel.urBackupFrames.collectAsStateWithLifecycle()

	// Zcash note sync state
	val zcashNoteSyncResult = scanViewModel.zcashNoteSyncResult.collectAsStateWithLifecycle()

	// Module package state
	val modulePackageData = scanViewModel.modulePackageBytes.collectAsStateWithLifecycle()
	val releaseSignPrefixValue =
		scanViewModel.releaseSignPrefix.collectAsStateWithLifecycle().value
	val modulePackageInfo = scanViewModel.modulePackageInfo.collectAsStateWithLifecycle()
	val modulePackageError = scanViewModel.modulePackageError.collectAsStateWithLifecycle()

	val showingModals = transactionError.value != null ||
		passwordModel.value != null || errorWrongPassword.value

	val backAction = {
		val wasState = scanViewModel.ifHasStateThenClear()
		if (!wasState) onCloseCamera()
	}
	BackHandler(onBack = backAction)

	val context = LocalContext.current

	// Stage module package when it arrives (verify signature, hash, etc.)
	LaunchedEffect(modulePackageData.value) {
		val packageBytes = modulePackageData.value ?: return@LaunchedEffect
		try {
			// Off the main thread: staging verifies 2-of-3 signatures and
			// hashes a multi-megabyte package. A LaunchedEffect body runs on
			// the composition dispatcher, so doing this inline would block the
			// UI long enough to risk an ANR.
			val info = withContext(Dispatchers.IO) {
				ModuleSlotStore.stage(context, packageBytes)
			}
			scanViewModel.modulePackageInfo.value = info
		} catch (e: Exception) {
			Timber.e(e, "Failed to stage module package")
			scanViewModel.modulePackageError.value = e.message
				?: "Failed to stage module update: ${e::class.simpleName}"
		}
	}

	//Full screens
	val transactionsValue = transactions.value
	val bananaQrData = bananaSplitPassword.value
	val urBackupData = urBackupFrames.value

	val zcashPcztParts = scanViewModel.zcashPcztUrParts.collectAsStateWithLifecycle()
	val modulePcztPayload = scanViewModel.modulePcztPayload.collectAsStateWithLifecycle()
	val frostData = scanViewModel.frostPayload.collectAsStateWithLifecycle()

	// Module package update flow (stage -> confirm -> activate)
	if (modulePackageError.value != null) {
		// Show error from staging
		var showingError by remember { mutableStateOf(true) }
		if (showingError) {
			BottomSheetWrapperRoot(onClosedAction = {
				scanViewModel.modulePackageError.value = null
				showingError = false
			}) {
				LocalErrorBottomSheet(
					error = LocalErrorSheetModel(
						title = "Module Update Failed",
						subtitle = "Failed to stage the module package for installation",
						details = modulePackageError.value
					),
					onOk = {
						scanViewModel.modulePackageError.value = null
						showingError = false
					}
				)
			}
		}
	} else if (modulePackageInfo.value != null) {
		// Show confirm screen after successful stage
		val currentVersion = remember { ModuleSlotStore.activeVersion(context) }
		ModuleUpdateConfirmScreen(
			info = modulePackageInfo.value!!,
			currentVersion = currentVersion,
			onConfirm = {
				// Activation re-verifies the package and runs the wasmi
				// self-test - instantiating the module and probing its ABI.
				// Seconds of work, so not on the main thread.
				scanViewModel.viewModelScope.launch {
					val ok = withContext(Dispatchers.IO) {
						ModuleSlotStore.activateStaged(context)
					}
					if (ok) {
						Timber.d("[MODULE] Module activated successfully")
						Toast.makeText(context, "Module update installed", Toast.LENGTH_SHORT).show()
						scanViewModel.modulePackageInfo.value = null
						scanViewModel.modulePackageBytes.value = null
					} else {
						Timber.e("[MODULE] Module activation failed")
						scanViewModel.modulePackageError.value =
							"Failed to activate module update. Self-test or verification failed."
						scanViewModel.modulePackageInfo.value = null
					}
				}
			},
			onCancel = {
				Timber.d("[MODULE] Module update cancelled by user")
				scanViewModel.modulePackageInfo.value = null
				scanViewModel.modulePackageBytes.value = null
			},
		)
	} else if (modulePcztPayload.value != null) {
		ModulePcztScreen(
			payloadHex = modulePcztPayload.value!!,
			getSeedPhrase = { scanViewModel.getFirstSeedPhrase() },
			getSeedName = { scanViewModel.getFirstSeedName() },
			modifier = Modifier.statusBarsPadding(),
			onDone = { scanViewModel.modulePcztPayload.value = null },
		)
	} else if (zcashPcztParts.value != null) {
		ZcashPcztScreen(
			urParts = zcashPcztParts.value!!,
			getSeedPhrase = { scanViewModel.getFirstSeedPhrase() },
			getSeedName = { scanViewModel.getFirstSeedName() },
			modifier = Modifier.statusBarsPadding(),
			onDone = { scanViewModel.zcashPcztUrParts.value = null },
		)
	} else if (frostData.value != null) {
		val json = frostData.value!!
		val frostType = json.optString("frost", "")
		timber.log.Timber.d("[FROST] routing frostType='$frostType' keys=${json.keys().asSequence().toList()}")
		val dismissFrost = { scanViewModel.frostPayload.value = null }
		val scanNextFrost = {
			// Dismiss screen, back to camera for next round QR
			scanViewModel.frostPayload.value = null
		}
		when (frostType) {
			"dkg1" -> {
				scanViewModel.frostDkgMaxSigners = json.optInt("n", 3).toUShort()
				scanViewModel.frostDkgMinSigners = json.optInt("t", 2).toUShort()
				scanViewModel.frostDkgLabel = json.optString("label", "")
				scanViewModel.frostDkgMainnet = json.optBoolean("mainnet", true)
				FrostDkgScreen(
					round = 1,
					maxSigners = scanViewModel.frostDkgMaxSigners,
					minSigners = scanViewModel.frostDkgMinSigners,
					label = scanViewModel.frostDkgLabel,
					mainnet = scanViewModel.frostDkgMainnet,
					onSecretUpdated = { scanViewModel.frostDkgSecret = it },
					onScanNext = scanNextFrost,
					modifier = Modifier.statusBarsPadding(),
					onDone = { dismissFrost(); scanViewModel.clearFrostDkgState() },
				)
			}
			"dkg2" -> {
				FrostDkgScreen(
					round = 2,
					maxSigners = scanViewModel.frostDkgMaxSigners,
					minSigners = scanViewModel.frostDkgMinSigners,
					label = scanViewModel.frostDkgLabel,
					mainnet = scanViewModel.frostDkgMainnet,
					previousSecret = scanViewModel.frostDkgSecret,
					broadcastsJson = json.optJSONArray("broadcasts")?.toString() ?: "[]",
					onSecretUpdated = { scanViewModel.frostDkgSecret = it },
					onScanNext = scanNextFrost,
					modifier = Modifier.statusBarsPadding(),
					onDone = { dismissFrost(); scanViewModel.clearFrostDkgState() },
				)
			}
			"dkg3" -> {
				// dkg3 trigger now carries `sk` (host-broadcast random) and `relay_url`
				// so zigner derives UFVK/address itself in part-3 and bakes them into
				// the r3 ack — eliminates the post-DKG metadata round-trip.
				FrostDkgScreen(
					round = 3,
					maxSigners = scanViewModel.frostDkgMaxSigners,
					minSigners = scanViewModel.frostDkgMinSigners,
					label = scanViewModel.frostDkgLabel,
					mainnet = json.optBoolean("mainnet", scanViewModel.frostDkgMainnet),
					previousSecret = scanViewModel.frostDkgSecret,
					// keys mirror the relay-tag convention (R1:/R2:) used by
					// the mnemonic flow on the WSS relay.
					round1BroadcastsJson = json.optJSONArray("r1")?.toString() ?: "[]",
					round2PackagesJson = json.optJSONArray("r2")?.toString() ?: "[]",
					skHex = json.optString("sk", ""),
					relayUrl = json.optString("relay_url", ""),
					onSecretUpdated = { scanViewModel.frostDkgSecret = it },
					onScanNext = scanNextFrost,
					modifier = Modifier.statusBarsPadding(),
					onDone = { dismissFrost(); scanViewModel.clearFrostDkgState() },
				)
			}
			"sign1" -> {
				val pkg = json.optString("publicKeyPackage", "")
				val summaryJson = json.optJSONObject("summary")
				val summaryText = summaryJson?.let {
					val rec = it.optString("recipient", "")
					val amount = it.optString("amountZat", "0").toLongOrNull() ?: 0L
					val fee = it.optString("feeZat", "0").toLongOrNull() ?: 0L
					val zec = String.format("%.8f", amount / 1e8).trimEnd('0').trimEnd('.')
					val feeZec = String.format("%.8f", fee / 1e8).trimEnd('0').trimEnd('.')
					val t = it.optInt("threshold", 0)
					val n = it.optInt("maxSigners", 0)
					"$t-of-$n · send $zec ZEC · fee $feeZec ZEC\n→ ${rec.take(16)}…${rec.takeLast(8)}"
				} ?: ""
				FrostSignScreen(
					round = 1,
					publicKeyPackageHex = pkg,
					walletIdHint = json.optString("walletId", ""),
					alphasJson = json.optJSONArray("alphas")?.toString() ?: "[]",
					summary = summaryText,
					sighashHex = json.optString("sighash", ""),
					pcztHex = json.optString("pczt", ""),
					onNoncesUpdated = { noncesArr, k, seed ->
						scanViewModel.frostSignNoncesPerAction = noncesArr
						scanViewModel.frostSignKeyPackage = k
						scanViewModel.frostSignEphemeralSeed = seed
					},
					onApproved = { scanViewModel.frostSignApproved = it },
					onScanNext = scanNextFrost,
					modifier = Modifier.statusBarsPadding(),
					onDone = { dismissFrost(); scanViewModel.clearFrostSignState() },
				)
			}
			"sign2" -> {
				FrostSignScreen(
					round = 2,
					publicKeyPackageHex = json.optString("publicKeyPackage", ""),
					sighashHex = json.optString("sighash", ""),
					alphasJson = json.optJSONArray("alphas")?.toString() ?: "[]",
					bundledCommitmentsJson = json.optJSONArray("bundledCommitments")?.toString() ?: "[]",
					previousNoncesPerAction = scanViewModel.frostSignNoncesPerAction,
					previousKeyPackage = scanViewModel.frostSignKeyPackage,
					previousEphemeralSeed = scanViewModel.frostSignEphemeralSeed,
					approved = scanViewModel.frostSignApproved,
					onNoncesUpdated = { noncesArr, k, seed ->
						scanViewModel.frostSignNoncesPerAction = noncesArr
						scanViewModel.frostSignKeyPackage = k
						scanViewModel.frostSignEphemeralSeed = seed
					},
					onScanNext = scanNextFrost,
					modifier = Modifier.statusBarsPadding(),
					onDone = { dismissFrost(); scanViewModel.clearFrostSignState() },
				)
			}
			else -> {
				scanViewModel.frostPayload.value = null
			}
		}
	} else if (scanViewModel.authPayload.collectAsStateWithLifecycle().value != null) {
		val authJson = scanViewModel.authPayload.collectAsStateWithLifecycle().value!!
		var seedPhrase by remember { mutableStateOf("") }
		LaunchedEffect(authJson) {
			seedPhrase = scanViewModel.getFirstSeedPhrase()
		}
		if (seedPhrase.isNotEmpty()) {
			AuthChallengeScreen(
				domain = authJson.optString("domain", ""),
				nonce = authJson.optString("nonce", ""),
				timestamp = authJson.optLong("ts", 0).toULong(),
				seedPhrase = seedPhrase,
				modifier = Modifier.statusBarsPadding(),
				onDone = { scanViewModel.authPayload.value = null },
			)
		}
	} else if (scanViewModel.zidSignPayload.collectAsStateWithLifecycle().value != null) {
		val zidJson = scanViewModel.zidSignPayload.collectAsStateWithLifecycle().value!!
		ZidSignScreen(
			qrJson = zidJson,
			getSeedPhrase = { scanViewModel.getFirstSeedPhrase() },
			modifier = Modifier.statusBarsPadding(),
			onDone = { scanViewModel.zidSignPayload.value = null },
		)
	} else if (zcashNoteSyncResult.value != null) {
		ZcashNoteSyncScreen(
			result = zcashNoteSyncResult.value!!,
			modifier = Modifier.statusBarsPadding(),
			onDone = {
				scanViewModel.clearZcashNoteSyncState()
			}
		)
	} else if (urBackupData != null) {
		BackupRestoreSubgraph(
			urFrames = urBackupData,
			onClose = {
				backAction()
			},
			onSuccess = { seedName ->
				Toast.makeText(
					context,
					context.getString(
						R.string.backup_restore_success_toast,
						seedName
					),
					Toast.LENGTH_LONG
				).show()
				scanViewModel.clearState()
				openKeySet(seedName)
			},
			onError = { error ->
				scanViewModel.transactionError.value =
					LocalErrorSheetModel(context = context, details = error)
				scanViewModel.urBackupFrames.value = null
			},
		)
	} else if (bananaQrData != null) {
		BananaSplitSubgraph(
			qrData = bananaQrData,
			onClose = {
				backAction()
			},
			onSuccess = { seedName ->
				Toast.makeText(
					context,
					context.getString(
						R.string.key_set_has_been_recovered_toast,
						seedName
					),
					Toast.LENGTH_LONG
				).show()
				scanViewModel.clearState()
				openKeySet(seedName)
			},
			onCustomError = { error ->
				scanViewModel.transactionError.value =
					LocalErrorSheetModel(context = context, details = error)
				scanViewModel.bananaSplitPassword.value = null
			},
			suggestedSeedName = seedNameSuggestion,
			onErrorWrongPassword = {
				scanViewModel.errorWrongPassword.value = true
				scanViewModel.bananaSplitPassword.value = null
			},
		)
	} else if (unifiedSignatureResult.value != null) {
		// Unified signature QR display
		UnifiedSignatureQrScreen(
			result = unifiedSignatureResult.value!!,
			modifier = Modifier.statusBarsPadding(),
			onDone = {
				scanViewModel.clearSignState()
			}
		)
	} else if (unifiedSignRequest.value != null) {
		// Unified transaction screen (Penumbra and Cosmos)
		UnifiedTransactionScreen(
			signRequest = unifiedSignRequest.value!!,
			modifier = Modifier.statusBarsPadding(),
			getSeedName = { scanViewModel.getFirstSeedName() },
			onApprove = {
				scanViewModel.signUnifiedTransaction(context)
			},
			onDecline = {
				scanViewModel.clearSignState()
			}
		)
	} else if (releaseSignPrefixValue != null) {
		ReleaseSignIntegratedScreen(
			prefixBytes = releaseSignPrefixValue,
			onDone = { scanViewModel.releaseSignPrefix.value = null },
		)
	} else if (transactionsValue == null || showingModals) {

		ScanScreen(
			onClose = onCloseCamera,
			performPayloads = { payloads ->
				scanViewModel.performTransactionPayload(payloads, context)
			},
			onBananaSplit = { payloads ->
				scanViewModel.bananaSplitPassword.value = payloads
			},
			onPenumbraSignRequest = { payload ->
				scanViewModel.performUnifiedSignRequest(payload, context)
			},
			onCosmosSignRequest = { payload ->
				scanViewModel.performUnifiedSignRequest(payload, context)
			},
			onUrBackupRestore = { urFrames ->
				scanViewModel.urBackupFrames.value = urFrames
			},
			onZcashNotes = { urFrames ->
				scanViewModel.performZcashNoteSync(urFrames, context)
			},
			onZcashPczt = { urParts ->
				scanViewModel.zcashPcztUrParts.value = urParts
			},
			onZcashModulePczt = { payloadHex ->
				scanViewModel.modulePcztPayload.value = payloadHex
			},
			onModulePackage = { packageBytes ->
				scanViewModel.modulePackageBytes.value = packageBytes
			},
			onReleaseSignPrefix = { prefixBytes ->
				scanViewModel.releaseSignPrefix.value = prefixBytes
			},
			onFrost = { json ->
				scanViewModel.frostPayload.value = json
			},
			onAuth = { json ->
				scanViewModel.authPayload.value = json
			},
			onZidSign = { json ->
				scanViewModel.zidSignPayload.value = json
			},
		)
	} else {

		TransactionsScreenFull(
			transactions = transactionsValue.transactions,
			signature = signature.value,
			modifier = Modifier.statusBarsPadding(),
			onBack = {
				FakeNavigator().backAction()
				scanViewModel.clearState()
			},
			onApprove = {
				when (val previewType =
					transactions.value?.transactions?.previewType) {
					is TransactionPreviewType.Metadata -> {
						Toast.makeText(
							context,
							context.getString(
								R.string.toast_metadata_added,
								previewType.network,
								previewType.version
							),
							Toast.LENGTH_LONG
						).show()
					}

					else -> {
						//nothing
					}
				}
				//finally clear transaction state and stay in scan screen
				scanViewModel.clearState()
				val fakeNavigator = FakeNavigator()
				fakeNavigator.navigate(Action.GO_FORWARD)
				fakeNavigator.navigate(Action.START)
				fakeNavigator.navigate(Action.NAVBAR_SCAN)
			},
			onImportKeys = {
				scanViewModel.onImportKeysTap(transactionsValue, context)
			}
		)
	}
	//Bottom sheets
	transactionError.value?.let { presentableErrorValue ->
		BottomSheetWrapperRoot(onClosedAction = scanViewModel::clearState) {
			LocalErrorBottomSheet(
				error = presentableErrorValue,
				onOk = scanViewModel::clearState,
			)
		}
	} ?: passwordModel.value?.let { passwordModelValue ->
		BottomSheetWrapperRoot(onClosedAction = {
			scanViewModel.resetRustModalToNewScan()
			scanViewModel.clearState()
		}) {
			EnterPassword(
				data = passwordModelValue,
				proceed = { password ->
					scanViewModel.viewModelScope.launch {
						scanViewModel.handlePasswordEntered(password)
					}
				},
				onClose = {
					scanViewModel.resetRustModalToNewScan()
					scanViewModel.clearState()
				},
			)
		}
	} ?: if (errorWrongPassword.value) {
		BottomSheetWrapperRoot(onClosedAction = scanViewModel::clearState) {
			WrongPasswordBottomSheet(
				onOk = scanViewModel::clearState
			)
		}
	} else {
		//no bottom sheet
	}
}


