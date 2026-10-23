package net.rotko.zigner.screens.scan

import net.rotko.zigner.screens.scan.transaction.frost.FrostRound2Binding
import android.content.Context
import timber.log.Timber
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.rotko.zigner.R
import net.rotko.zigner.bottomsheets.password.EnterPasswordModel
import net.rotko.zigner.bottomsheets.password.toEnterPasswordModel
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.FakeNavigator
import net.rotko.zigner.domain.backend.OperationResult
import net.rotko.zigner.domain.backend.ScanFlowInteractor
import net.rotko.zigner.domain.backend.UniffiInteractor
import net.rotko.zigner.domain.backend.UniffiResult
import net.rotko.zigner.domain.storage.RepoResult
import net.rotko.zigner.domain.storage.SeedRepository
import net.rotko.zigner.screens.scan.errors.LocalErrorSheetModel
import net.rotko.zigner.screens.scan.errors.toBottomSheetModel
import net.rotko.zigner.screens.scan.importderivations.ImportDerivedKeysRepository
import net.rotko.zigner.screens.scan.importderivations.ImportDerivedKeysRepository.ImportDerivedKeyError
import net.rotko.zigner.screens.scan.importderivations.allImportDerivedKeys
import net.rotko.zigner.screens.scan.importderivations.dominantImportError
import net.rotko.zigner.screens.scan.importderivations.hasImportableKeys
import net.rotko.zigner.screens.scan.importderivations.importableSeedKeysPreviews
import net.rotko.zigner.screens.scan.transaction.isDisplayingErrorOnly
import net.rotko.zigner.screens.scan.transaction.transactionIssues
import io.parity.signer.uniffi.Action
import io.parity.signer.uniffi.ActionResult
import io.parity.signer.uniffi.Card
import io.parity.signer.uniffi.DdKeySet
import io.parity.signer.uniffi.DerivedKeyError
import io.parity.signer.uniffi.MSignatureReady
import io.parity.signer.uniffi.MTransaction
import io.parity.signer.uniffi.ModalData
import io.parity.signer.uniffi.ScreenData
import io.parity.signer.uniffi.SeedKeysPreview
import io.parity.signer.uniffi.TransactionType
import io.parity.signer.uniffi.PenumbraSignRequest
import io.parity.signer.uniffi.parsePenumbraSignRequest
import io.parity.signer.uniffi.signPenumbraTransaction as uniffiSignPenumbraTransaction
import io.parity.signer.uniffi.CosmosSignRequest
import io.parity.signer.uniffi.cosmosPublicKeyForSigners
import io.parity.signer.uniffi.encodeCworchSignature
import io.parity.signer.uniffi.parseCosmosSignRequest
import io.parity.signer.uniffi.signCosmosTransaction as uniffiSignCosmosTransaction
import io.parity.signer.uniffi.ZcashNoteSyncResult
import io.parity.signer.uniffi.ZcashSimpleSignRequest
import io.parity.signer.uniffi.ModulePackageInfo
import io.parity.signer.uniffi.decodeAndVerifyZcashNotes
import io.parity.signer.uniffi.parseZcashSignRequest
import io.parity.signer.uniffi.signZcashSimple
import io.parity.signer.uniffi.decodeUrZcashPczt
import io.parity.signer.uniffi.inspectZcashPczt
import io.parity.signer.uniffi.signZcashPcztUr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.screens.scan.transaction.SignRequest
import net.rotko.zigner.screens.scan.transaction.SignatureResult


private const val TAG = "ScanViewModelTag"

/**
 * Shared ViewModel for all Scan flow components, not only camera related.
 */
class ScanViewModel : ViewModel() {

	private val scanFlowInteractor = ScanFlowInteractor()
	private val uniffiInteractor: UniffiInteractor =
		ServiceLocator.uniffiInteractor
	private val seedRepository: SeedRepository by lazy { ServiceLocator.activityScope!!.seedRepository }

	/** Get first available seed phrase for auth signing */
	suspend fun getFirstSeedPhrase(): String {
		return when (val result = seedRepository.getAllSeeds()) {
			is RepoResult.Success -> result.result.values.firstOrNull() ?: ""
			is RepoResult.Failure -> ""
		}
	}

	/** Get first available seed name (NOT phrase) for display before signing.
	 *  Map iteration is unspecified on multi-seed devices; showing the chosen
	 *  name on-screen makes "which key is about to sign" visible to the user.
	 */
	suspend fun getFirstSeedName(): String {
		// Display-only: the seed NAME is not a secret (already shown without auth
		// on the key list, e.g. KeySetDetailsViewModel). Read it from the cached
		// non-auth source so the summarize/review step does NOT trigger a PIN /
		// biometric prompt - auth must fire exactly once, at Approve & Sign.
		// lastKnownSeedNames is populated at unlock (ResetUseCase.totalRefreshDbExist
		// -> getSeedNames) and stays sorted, matching the seed getFirstSeedPhrase
		// selects (getAllSeeds().values.firstOrNull() over the same sorted order).
		return seedRepository.getLastKnownSeedNames().firstOrNull() ?: ""
	}
	private val importKeysRepository: ImportDerivedKeysRepository by lazy {
		ImportDerivedKeysRepository(seedRepository)
	}

	data class TransactionsState(val transactions: List<MTransaction>)

	var transactions: MutableStateFlow<TransactionsState?> =
		MutableStateFlow(null)
	var signature: MutableStateFlow<MSignatureReady?> = MutableStateFlow(null)
	var bananaSplitPassword: MutableStateFlow<List<String>?> =
		MutableStateFlow(null)
	var passwordModel: MutableStateFlow<EnterPasswordModel?> =
		MutableStateFlow(null)
	val transactionError: MutableStateFlow<LocalErrorSheetModel?> =
		MutableStateFlow(null)
	val errorWrongPassword = MutableStateFlow<Boolean>(false)

	// Penumbra signing state
	var penumbraSignRequest: MutableStateFlow<PenumbraSignRequest?> = MutableStateFlow(null)
	var penumbraSignatureQr: MutableStateFlow<ByteArray?> = MutableStateFlow(null)

	// Cosmos signing state
	var cosmosSignRequest: MutableStateFlow<CosmosSignRequest?> = MutableStateFlow(null)
	var cosmosSignatureQr: MutableStateFlow<ByteArray?> = MutableStateFlow(null)

	// UR backup restore state
	var urBackupFrames: MutableStateFlow<List<String>?> = MutableStateFlow(null)

	// Zcash note sync state
	var zcashNoteSyncResult: MutableStateFlow<ZcashNoteSyncResult?> = MutableStateFlow(null)
	var zcashNoteSyncFrames: MutableStateFlow<List<String>?> = MutableStateFlow(null)

	// PCZT signing state
	var zcashPcztUrParts: MutableStateFlow<List<String>?> = MutableStateFlow(null)

	// Protocol-module PCZT request state (prelude 530403/530404, hex payload)
	var modulePcztPayload: MutableStateFlow<String?> = MutableStateFlow(null)

	// Module package state (signed package bytes for installation)
	var modulePackageBytes: MutableStateFlow<ByteArray?> = MutableStateFlow(null)
	var modulePackageInfo: MutableStateFlow<ModulePackageInfo?> = MutableStateFlow(null)
	var modulePackageError: MutableStateFlow<String?> = MutableStateFlow(null)
	var releaseSignPrefix: MutableStateFlow<ByteArray?> = MutableStateFlow(null)

	// Unified signing state (replaces per-network state above)
	var signRequest: MutableStateFlow<SignRequest?> = MutableStateFlow(null)
	var signatureResult: MutableStateFlow<SignatureResult?> = MutableStateFlow(null)

	// FROST multisig state
	var frostPayload: MutableStateFlow<org.json.JSONObject?> = MutableStateFlow(null)
	// FROST DKG secrets held across rounds (memory only, cleared on completion/cancel)
	var frostDkgSecret: String = ""
	var frostDkgMaxSigners: UShort = 0u
	var frostDkgMinSigners: UShort = 0u
	var frostDkgLabel: String = ""
	var frostDkgMainnet: Boolean = true
	// FROST signing state held across rounds.
	// One nonce blob per Orchard action — round 1 generates them, round 2 consumes
	// per-action with that action's bundled commitments. Single-blob legacy field
	// kept for the older mnemonic-flow callers that sign one action at a time.
	var frostSignNonces: String = ""
	var frostSignNoncesPerAction: List<String> = emptyList()
	var frostSignKeyPackage: String = ""
	var frostSignEphemeralSeed: String = ""
	var frostSignWalletId: String = ""
	// What the user approved in round 1 (verified sighash + per-action alphas).
	// Round 2 signs only if its QR repeats exactly this - see FrostRound2Binding.
	var frostSignApproved: FrostRound2Binding.Approved? = null

	fun clearFrostDkgState() {
		frostDkgSecret = ""
	}

	fun clearFrostSignState() {
		frostSignNonces = ""
		frostSignNoncesPerAction = emptyList()
		frostSignKeyPackage = ""
		frostSignEphemeralSeed = ""
		frostSignApproved = null
	}

	// Auth challenge state
	var authPayload: MutableStateFlow<org.json.JSONObject?> = MutableStateFlow(null)
	// ZID sign request state
	var zidSignPayload: MutableStateFlow<org.json.JSONObject?> = MutableStateFlow(null)

	private val transactionIsInProgress = MutableStateFlow<Boolean>(false)

	suspend fun performTransactionPayload(payload: String, context: Context) {
		val fakeNavigator = FakeNavigator()
		if (transactionIsInProgress.value) {
			Timber.e(TAG, "started transaction while it was in progress, ignoring")
			return
		}
		transactionIsInProgress.value = true
		val navigateResponse = scanFlowInteractor.performTransaction(payload)

		when (navigateResponse) {
			is OperationResult.Err -> {
				transactionError.value =
					navigateResponse.error.toBottomSheetModel(context)
			}

			is OperationResult.Ok -> {
				val screenData = navigateResponse.result.screenData
				val transactions: List<MTransaction> =
					(screenData as? ScreenData.Transaction)?.f ?: run {
						Timber.e(
							TAG,
							"Error in getting transaction from qr payload, " + "screenData is $screenData, navigation resp is $navigateResponse"
						)
						clearState()
						return
					}

				// Handle transactions with just error payload
				if (transactions.all { it.isDisplayingErrorOnly() }) {
					transactionError.value = LocalErrorSheetModel(context = context,
						details = transactions.joinToString("\n") { it.transactionIssues() })
					fakeNavigator.navigate(Action.GO_BACK) //fake call
					clearState()
					return
				}

				when (transactions.firstOrNull()?.ttype) {
					TransactionType.SIGN -> {
						val seedNames =
							transactions.filter { it.ttype == TransactionType.SIGN }
								.mapNotNull { it.authorInfo?.address?.seedName }
						val actionResult = signTransaction("", seedNames)

						//password protected key, show password
						when (val modalData = actionResult?.modalData) {
							is ModalData.EnterPassword -> {
								passwordModel.value =
									modalData.f.toEnterPasswordModel(withShowError = false)
							}

							is ModalData.SignatureReady -> {
								signature.value = modalData.f
							}
							//ignore the rest modals
							else -> {
								//actually we won't ask for signature in this case ^^^
//						we can get result.navResult.alertData with error from Rust but it's not in new design
							}
						}
						this.transactions.value = TransactionsState(transactions)
					}

					TransactionType.IMPORT_DERIVATIONS -> {
						// We always need to `.goBack` as even if camera is dismissed without import, navigation "forward" already happened
						fakeNavigator.navigate(Action.GO_BACK)
						when (transactions.dominantImportError()) {
							DerivedKeyError.BadFormat -> {
								transactionError.value = LocalErrorSheetModel(
									title = context.getString(R.string.scan_screen_error_bad_format_title),
									subtitle = context.getString(R.string.scan_screen_error_bad_format_message),
								)
								clearState()
								return
							}

							DerivedKeyError.KeySetMissing -> {
								transactionError.value = LocalErrorSheetModel(
									title = context.getString(R.string.scan_screen_error_missing_key_set_title),
									subtitle = context.getString(R.string.scan_screen_error_missing_key_set_message),
								)
								clearState()
								return
							}

							DerivedKeyError.NetworkMissing -> {
								transactionError.value = LocalErrorSheetModel(
									title = context.getString(R.string.scan_screen_error_missing_network_title),
									subtitle = context.getString(R.string.scan_screen_error_missing_network_message),
								)
								clearState()
								return
							}

							null -> {
								//proceed, all good, now check if we need to update for derivations keys
								if (transactions.hasImportableKeys()) {
									val importDerivedKeys =
										transactions.flatMap { it.allImportDerivedKeys() }
									if (importDerivedKeys.isEmpty()) {
										this.transactions.value = TransactionsState(transactions)
									}

									when (val result =
										importKeysRepository.updateWithSeed(importDerivedKeys)) {
										is RepoResult.Success -> {
											val updatedKeys = result.result
											val newTransactionsState =
												updateTransactionsWithImportDerivations(
													transactions = transactions, updatedKeys = updatedKeys
												)
											this.transactions.value =
												TransactionsState(newTransactionsState)
										}

										is RepoResult.Failure -> {
											Toast.makeText(                        /* context = */
												context,                        /* text = */
												context.getString(R.string.import_derivations_failure_update_toast),                        /* duration = */
												Toast.LENGTH_LONG
											).show()
											clearState()
										}
									}
								} else {
									transactionError.value = LocalErrorSheetModel(
										title = context.getString(R.string.scan_screen_error_derivation_no_keys_and_no_errors_title),
										subtitle = context.getString(R.string.scan_screen_error_derivation_no_keys_and_no_errors_message),
									)
									return
								}
							}
						}
					}

					else -> {
						// Transaction with error OR
						// Transaction that does not require signing (i.e. adding network or metadata)
						// will set them below for any case and show anyway
						this.transactions.value = TransactionsState(transactions)
					}
					//handle alert error rust/navigator/src/navstate.rs:396
				}
			}
		}
	}

	private fun updateTransactionsWithImportDerivations(
		transactions: List<MTransaction>, updatedKeys: List<SeedKeysPreview>
	): List<MTransaction> = transactions.map { transaction ->
		if (transaction.hasImportableKeys()) {
			transaction.content.importingDerivations =
				transaction.content.importingDerivations?.map { transactionCard ->
					transactionCard.copy(
						card = when (val card = transactionCard.card) {
							is Card.DerivationsCard -> {
								card.copy(f = card.f.map { originalKey ->
									updatedKeys.firstOrNull { resultKey ->
										areSeedKeysTheSameButUpdated(originalKey, resultKey)
									} ?: originalKey
								})
							}

							else -> {
								card
								//don't update
							}
						}
					)
				}
			transaction
		} else {
			transaction
		}
	}

	private fun areSeedKeysTheSameButUpdated(
		originalKey: SeedKeysPreview, resultKey: SeedKeysPreview
	): Boolean =
		originalKey.name == resultKey.name && resultKey.derivedKeys.all { derKey ->
			originalKey.derivedKeys.any { it.derivationPath == derKey.derivationPath }
		}

	fun onImportKeysTap(transactions: TransactionsState, context: Context) {
		val importableKeys =
			transactions.transactions.flatMap { it.importableSeedKeysPreviews() }

		val importResult = importKeysRepository.importDerivedKeys(importableKeys)
		val derivedKeysCount = importableKeys.sumOf { it.derivedKeys.size }

		when (importResult) {
			is RepoResult.Success -> {
				Toast.makeText(          /* context = */ context,          /* text = */
					context.resources.getQuantityString(
						R.plurals.import_derivations_success_keys_imported,
						derivedKeysCount,
						derivedKeysCount,
					), /* duration = */
					Toast.LENGTH_LONG
				).show()
			}

			is RepoResult.Failure -> {
				Toast.makeText(          /* context = */ context,          /* text = */
					context.getString(R.string.import_derivations_failure_toast),          /* duration = */
					Toast.LENGTH_LONG
				).show()
			}
		}

		this.transactions.value = null
	}


	fun ifHasStateThenClear(): Boolean {
		return if (transactions.value != null || signature.value != null || passwordModel.value != null || transactionError.value != null || transactionIsInProgress.value || errorWrongPassword.value || bananaSplitPassword.value != null || penumbraSignRequest.value != null || penumbraSignatureQr.value != null || cosmosSignRequest.value != null || cosmosSignatureQr.value != null || urBackupFrames.value != null || zcashNoteSyncResult.value != null || zcashNoteSyncFrames.value != null || modulePcztPayload.value != null || modulePackageBytes.value != null || releaseSignPrefix.value != null) {
			clearState()
			true
		} else {
			false
		}
	}

	fun clearState() {
		transactions.value = null
		signature.value = null
		passwordModel.value = null
		bananaSplitPassword.value = null
		transactionError.value = null
		transactionIsInProgress.value = false
		errorWrongPassword.value = false
		penumbraSignRequest.value = null
		penumbraSignatureQr.value = null
		cosmosSignRequest.value = null
		cosmosSignatureQr.value = null
		urBackupFrames.value = null
		zcashNoteSyncResult.value = null
		zcashNoteSyncFrames.value = null
		zcashPcztUrParts.value = null
		modulePcztPayload.value = null
		modulePackageBytes.value = null
		modulePackageInfo.value = null
		modulePackageError.value = null
		releaseSignPrefix.value = null
		frostPayload.value = null
		clearFrostDkgState()
		clearFrostSignState()
		authPayload.value = null
		zidSignPayload.value = null
	}

	private suspend fun signTransaction(
		comment: String,
		seedNames: List<String>,
	): ActionResult? {
		return when (val phrases = seedRepository.getSeedPhrases(seedNames)) {
			is RepoResult.Failure -> {
				Timber.w(TAG, "signature transactions failure ${phrases.error}")
				null
			}

			is RepoResult.Success -> {
				val result = scanFlowInteractor.continueSigningTransaction(
					comment,
					phrases.result,
				)
				System.gc()
				result
			}
		}
	}

	suspend fun handlePasswordEntered(password: String) {
		val navigateResponse =
			scanFlowInteractor.handlePasswordEntered(password)
		val actionResult =
			(navigateResponse as? OperationResult.Ok)?.result ?: run {
				Timber.e(
					TAG,
					"Error in entering password for a key, " + "navigation resp is $navigateResponse"
				)
				return
			}

		when (val modalData = actionResult.modalData) {
			// If navigation returned `enterPassword`, it means password is invalid
			is ModalData.EnterPassword -> {
				val model = modalData.f.toEnterPasswordModel(true)
				if (model.attempt > 3) {
					proceedWrongPassword()
					return
				}
				passwordModel.value = model
			}
			//password success
			is ModalData.SignatureReady -> {
				//                navigation.performFake(navigation: .init(action: .goBack))
				signature.value = modalData.f
				passwordModel.value = null
			}
			//ignore the rest modals
			else -> {
				Timber.e(
					TAG,
					"Password is entered for transaction, but neither new password or signature is passed! Should not happen" + "actionResult is $actionResult"
				)
			}
		}
		// If we got `Log`, it's out of attempts to enter password
		if (actionResult.screenData is ScreenData.Log) {
			proceedWrongPassword()
		}
	}

	private fun proceedWrongPassword() {
		errorWrongPassword.value = true
		passwordModel.value = null
		resetRustModalToNewScan()
	}

	fun resetRustModalToNewScan() {
		val fakeNavigator = FakeNavigator()
		// Dismissing password modal goes to `Log` screen
		fakeNavigator.backAction()
		// Pretending to navigate back to `Scan` so navigation states for new QR code scan will work
		fakeNavigator.navigate(Action.NAVBAR_SCAN, "", "")
	}

	/**
	 * Verify zcash notes from UR frames and store in sled
	 */
	fun performZcashNoteSync(urFrames: List<String>, context: Context) {
		viewModelScope.launch {
			try {
				val result = decodeAndVerifyZcashNotes(urFrames)
				zcashNoteSyncResult.value = result
			} catch (e: Exception) {
				Timber.e(e, "Failed to verify zcash notes")
				transactionError.value = LocalErrorSheetModel(
					title = "Note Sync Failed",
					subtitle = e.message ?: "Failed to verify zcash notes"
				)
				zcashNoteSyncFrames.value = null
			}
		}
	}

	fun clearZcashNoteSyncState() {
		zcashNoteSyncResult.value = null
		zcashNoteSyncFrames.value = null
	}

	/**
	 * Parse Penumbra sign request from QR hex and set it for display
	 */
	fun performPenumbraSignRequest(qrHex: String, context: Context) {
		try {
			val request = parsePenumbraSignRequest(qrHex)
			penumbraSignRequest.value = request
		} catch (e: Exception) {
			Timber.e(e, "Failed to parse Penumbra sign request")
			transactionError.value = LocalErrorSheetModel(
				title = context.getString(R.string.scan_screen_error_bad_format_title),
				subtitle = e.message ?: "Failed to parse Penumbra transaction"
			)
		}
	}

	/**
	 * Sign Penumbra transaction and generate signature QR bytes
	 */
	fun signPenumbraTransaction(context: Context) {
		val request = penumbraSignRequest.value ?: return

		viewModelScope.launch {
			try {
				when (val seeds = seedRepository.getAllSeeds()) {
					is RepoResult.Failure -> {
						Timber.e(TAG, "Failed to get seeds for Penumbra signing: ${seeds.error}")
						transactionError.value = LocalErrorSheetModel(
							title = "Signing Error",
							subtitle = "Could not access seed phrases"
						)
					}
					is RepoResult.Success -> {
						val seedPhrase = seeds.result.values.firstOrNull()
						if (seedPhrase == null) {
							transactionError.value = LocalErrorSheetModel(
								title = "No Seed Found",
								subtitle = "No seed phrase available for signing"
							)
							return@launch
						}

						val signatureBytes = uniffiSignPenumbraTransaction(seedPhrase, request)
						penumbraSignatureQr.value = signatureBytes.map { it.toByte() }.toByteArray()
					}
				}
			} catch (e: Exception) {
				Timber.e(e, "Failed to sign Penumbra transaction")
				transactionError.value = LocalErrorSheetModel(
					title = "Signing Failed",
					subtitle = e.message ?: "Unknown error during signing"
				)
			}
		}
	}

	/**
	 * Clear Penumbra signing state
	 */
	fun clearPenumbraState() {
		penumbraSignRequest.value = null
		penumbraSignatureQr.value = null
	}

	/**
	 * Parse Cosmos sign request from QR hex and set it for display
	 */
	fun performCosmosSignRequest(qrHex: String, context: Context) {
		try {
			val request = parseCosmosSignRequest(qrHex)
			cosmosSignRequest.value = request
		} catch (e: Exception) {
			Timber.e(e, "Failed to parse Cosmos sign request")
			transactionError.value = LocalErrorSheetModel(
				title = context.getString(R.string.scan_screen_error_bad_format_title),
				subtitle = e.message ?: "Failed to parse Cosmos transaction"
			)
		}
	}

	/**
	 * Sign Cosmos transaction and generate 64-byte signature
	 */
	fun signCosmosTransaction(context: Context) {
		val request = cosmosSignRequest.value ?: return

		viewModelScope.launch {
			try {
				when (val seeds = seedRepository.getAllSeeds()) {
					is RepoResult.Failure -> {
						Timber.e(TAG, "Failed to get seeds for Cosmos signing: ${seeds.error}")
						transactionError.value = LocalErrorSheetModel(
							title = "Signing Error",
							subtitle = "Could not access seed phrases"
						)
					}
					is RepoResult.Success -> {
						val signatureBytes = signCosmosMatchingKeySet(seeds.result, request)
							?: return@launch
						cosmosSignatureQr.value = signatureBytes.map { it.toByte() }.toByteArray()
					}
				}
			} catch (e: Exception) {
				Timber.e(e, "Failed to sign Cosmos transaction")
				transactionError.value = LocalErrorSheetModel(
					title = "Signing Failed",
					subtitle = e.message ?: "Unknown error during signing"
				)
			}
		}
	}

	/**
	 * Clear Cosmos signing state
	 */
	fun clearCosmosState() {
		cosmosSignRequest.value = null
		cosmosSignatureQr.value = null
	}

	// ========================================================================
	// Unified signing methods
	// ========================================================================

	/**
	 * Parse any hex-based sign request (Penumbra, Cosmos, or Zcash simple)
	 * and set the unified signRequest state.
	 */
	fun performUnifiedSignRequest(qrHex: String, context: Context) {
		try {
			val prefix = if (qrHex.length >= 6) qrHex.substring(0, 6).lowercase() else ""
			val request: SignRequest = when {
				prefix == "530310" -> {
					SignRequest.Penumbra(parsePenumbraSignRequest(qrHex))
				}
				prefix == "530510" -> {
					SignRequest.Cosmos(parseCosmosSignRequest(qrHex))
				}
				prefix == "530402" -> {
					SignRequest.ZcashSimple(parseZcashSignRequest(qrHex))
				}
				else -> {
					throw IllegalArgumentException("Unknown QR prefix: $prefix")
				}
			}
			signRequest.value = request
		} catch (e: Exception) {
			Timber.e(e, "Failed to parse sign request")
			transactionError.value = LocalErrorSheetModel(
				title = context.getString(R.string.scan_screen_error_bad_format_title),
				subtitle = e.message ?: "Failed to parse transaction"
			)
		}
	}

	/**
	 * Parse Zcash PCZT from UR parts: decode → inspect → set signRequest.
	 */
	fun performZcashPcztRequest(urParts: List<String>, context: Context) {
		viewModelScope.launch {
			try {
				val pcztBytes = withContext(Dispatchers.Default) {
					decodeUrZcashPczt(urParts)
				}
				val inspection = withContext(Dispatchers.Default) {
					inspectZcashPczt(pcztBytes)
				}
				signRequest.value = SignRequest.ZcashPczt(
					urParts = urParts,
					inspection = inspection,
					pcztBytes = pcztBytes,
				)
			} catch (e: Exception) {
				Timber.e(e, "Failed to inspect PCZT")
				transactionError.value = LocalErrorSheetModel(
					title = "PCZT Error",
					subtitle = e.message ?: "Failed to decode or inspect PCZT"
				)
			}
		}
	}

	/**
	 * Sign the current signRequest using the appropriate FFI function.
	 */
	fun signUnifiedTransaction(context: Context) {
		val req = signRequest.value ?: return

		viewModelScope.launch {
			try {
				when (req) {
					is SignRequest.Penumbra -> {
						val seedPhrase = getSeedPhrase() ?: return@launch
						val sigBytes = uniffiSignPenumbraTransaction(seedPhrase, req.request)
						val hex = sigBytes.joinToString("") { "%02x".format(it.toByte()) }
						signatureResult.value = SignatureResult.HexQr(hex, req.networkName)
					}
					is SignRequest.Cosmos -> {
						val seeds = when (val loaded = seedRepository.getAllSeeds()) {
							is RepoResult.Failure -> {
								transactionError.value = LocalErrorSheetModel(
									title = "Signing Error",
									subtitle = "Could not access seed phrases"
								)
								return@launch
							}
							is RepoResult.Success -> loaded.result
						}
						val seedPhrase = cosmosPhraseForRequest(seeds, req.request)
							?: return@launch
						val sigBytes = uniffiSignCosmosTransaction(seedPhrase, req.request)
						val hex = sigBytes.joinToString("") { "%02x".format(it.toByte()) }
						val publicKey = cosmosPublicKeyForSigners(seedPhrase, req.request)
						// The page camera accepts ur:cworch-sig (33-byte pubkey || 64-byte
						// signature). A granter challenge has an empty memo, and a hex-only
						// signature is rejected as incomplete.
						val granterCard = req.request.msgs.size == 1 &&
							req.request.msgs[0].msgType == "Granter"
						signatureResult.value = if (req.request.memo.startsWith("cw-orch-") || granterCard) {
							val frames = encodeCworchSignature(publicKey, sigBytes, 0u)
							SignatureResult.UrParts(frames, req.networkName, publicKey)
						} else {
							SignatureResult.HexQr(hex, req.networkName, publicKey)
						}
					}
					is SignRequest.ZcashSimple -> {
						val seedPhrase = getSeedPhrase() ?: return@launch
						val hex = signZcashSimple(seedPhrase, req.request)
						signatureResult.value = SignatureResult.HexQr(hex, req.networkName)
					}
					is SignRequest.ZcashPczt -> {
						val seedPhrase = getSeedPhrase() ?: return@launch
						val signedUrParts = withContext(Dispatchers.Default) {
							signZcashPcztUr(seedPhrase, 0u, req.urParts, 200u)
						}
						signatureResult.value = SignatureResult.UrParts(signedUrParts, req.networkName)
					}
				}
			} catch (e: Exception) {
				Timber.e(e, "Failed to sign transaction")
				transactionError.value = LocalErrorSheetModel(
					title = "Signing Failed",
					subtitle = e.message ?: "Unknown error during signing"
				)
			}
		}
	}

	/**
	 * Clear unified signing state.
	 */
	fun clearSignState() {
		signRequest.value = null
		signatureResult.value = null
	}

	/**
	 * Seed whose derived Terp address is the transaction signer.
	 *
	 * The open key set is tried first. Signing used to take the first imported
	 * phrase, so a later key set such as terp1fx was previewed and then refused
	 * as a different address.
	 */
	private suspend fun cosmosPhraseForRequest(
		seeds: Map<String, String>,
		request: CosmosSignRequest,
	): String? {
		if (seeds.isEmpty()) {
			transactionError.value = LocalErrorSheetModel(
				title = "No Seed Found",
				subtitle = "No seed phrase available for signing"
			)
			return null
		}
		val preferred = ServiceLocator.preferencesRepository.getLastSelectedSeed()
		val ordered = buildList {
			if (preferred != null && seeds.containsKey(preferred)) add(preferred)
			for (name in seedRepository.getLastKnownSeedNames()) {
				if (name != preferred && seeds.containsKey(name)) add(name)
			}
			for (name in seeds.keys) {
				if (!contains(name)) add(name)
			}
		}
		var mismatch: Exception? = null
		for (name in ordered) {
			val phrase = seeds[name] ?: continue
			try {
				uniffiSignCosmosTransaction(phrase, request)
				return phrase
			} catch (e: Exception) {
				val message = e.message.orEmpty()
				if (message.contains("Source mismatch")) {
					mismatch = e
					continue
				}
				throw e
			}
		}
		transactionError.value = LocalErrorSheetModel(
			title = "Signing Failed",
			subtitle = mismatch?.message
				?: "No key set on this device matches the transaction signer"
		)
		return null
	}

	private suspend fun signCosmosMatchingKeySet(
		seeds: Map<String, String>,
		request: CosmosSignRequest,
	): List<UByte>? {
		val phrase = cosmosPhraseForRequest(seeds, request) ?: return null
		return uniffiSignCosmosTransaction(phrase, request)
	}

	/**
	 * Get the first available seed phrase, or show error and return null.
	 */
	private suspend fun getSeedPhrase(): String? {
		return when (val seeds = seedRepository.getAllSeeds()) {
			is RepoResult.Failure -> {
				Timber.e(TAG, "Failed to get seeds: ${seeds.error}")
				transactionError.value = LocalErrorSheetModel(
					title = "Signing Error",
					subtitle = "Could not access seed phrases"
				)
				null
			}
			is RepoResult.Success -> {
				val phrase = seeds.result.values.firstOrNull()
				if (phrase == null) {
					transactionError.value = LocalErrorSheetModel(
						title = "No Seed Found",
						subtitle = "No seed phrase available for signing"
					)
				}
				phrase
			}
		}
	}
}
