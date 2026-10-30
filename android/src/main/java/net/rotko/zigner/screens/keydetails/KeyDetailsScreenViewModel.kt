package net.rotko.zigner.screens.keydetails

import androidx.lifecycle.ViewModel
import net.rotko.zigner.bottomsheets.password.EnterPasswordModel
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.KeyDetailsModel
import net.rotko.zigner.domain.backend.OperationResult
import net.rotko.zigner.domain.backend.UniffiResult
import net.rotko.zigner.domain.storage.RepoResult
import net.rotko.zigner.screens.keydetails.exportprivatekey.PrivateKeyExportModel
import io.parity.signer.uniffi.MKeyDetails
import io.parity.signer.uniffi.ZcashVerifiedNoteDisplay
import io.parity.signer.uniffi.encodeToQr
import io.parity.signer.uniffi.exportZcashFvk
import io.parity.signer.uniffi.exportPenumbraFvk
import io.parity.signer.uniffi.exportCosmosAccounts
import io.parity.signer.uniffi.getZcashDiversifiedAddress
import io.parity.signer.uniffi.getZcashTransparentAddress
import io.parity.signer.uniffi.ZcashSyncInfo
import io.parity.signer.uniffi.getZcashSyncInfo
import io.parity.signer.uniffi.getZcashVerifiedBalance
import io.parity.signer.uniffi.getZcashVerifiedNotes


class KeyDetailsScreenViewModel : ViewModel() {
	private val uniFfi = ServiceLocator.uniffiInteractor
	private val repo = ServiceLocator.activityScope!!.seedRepository

	//keeping this state because we don't want to ask for password twice
	private var privateExportStateModel: PrivateKeyExportModel? = null

	suspend fun fetchModel(
		keyAddr: String,
		networkSpecs: String
	): UniffiResult<MKeyDetails> {
		return uniFfi.getKeyPublicKey(keyAddr, networkSpecs)
	}

	suspend fun getPrivateExportKey(
		model: KeyDetailsModel,
		password: String?
	): OperationResult<PrivateKeyExportModel, Any> {

		privateExportStateModel?.let { saved ->
			if (saved.keyCard == model.address) {
				//password check result saved or already saved before config change
				return OperationResult.Ok(saved)
			}
		}

		val seedResult =
			repo.getSeedPhraseForceAuth(model.address.cardBase.seedName)
		return when (seedResult) {
			is RepoResult.Failure -> OperationResult.Err(seedResult)
			is RepoResult.Success -> {
				val secretKeyDetailsQR = uniFfi.generateSecretKeyQr(
					publicKey = model.pubkey,
					expectedSeedName = model.address.cardBase.seedName,
					networkSpecsKey = model.networkInfo.networkSpecsKey,
					seedPhrase = seedResult.result,
					keyPassword = password,
				)
				System.gc()
				when (secretKeyDetailsQR) {
					is UniffiResult.Error -> OperationResult.Err(secretKeyDetailsQR.error)
					is UniffiResult.Success -> {
						privateExportStateModel = secretKeyDetailsQR.result
						OperationResult.Ok(secretKeyDetailsQR.result)
					}
				}
			}
		}
	}

	fun clearExportResultState() {
		privateExportStateModel = null
	}

	fun createPasswordModel(keyModel: KeyDetailsModel): EnterPasswordModel {
		return EnterPasswordModel(
			keyCard = keyModel.address.cardBase,
			showError = false,
			attempt = 0,
		)
	}

	suspend fun tryPassword(
		keyModel: KeyDetailsModel,
		passwordModel: EnterPasswordModel,
		password: String
	): ExportTryPasswordReply {
		val seedResult = if (passwordModel.attempt == 0) {
			repo.getSeedPhraseForceAuth(keyModel.address.cardBase.seedName)
		} else {
			repo.getSeedPhrases(listOf(keyModel.address.cardBase.seedName))
		}

		return when (seedResult) {
			is RepoResult.Failure -> {
				ExportTryPasswordReply.ErrorAuthWrong
			}

			is RepoResult.Success -> {
				val secretKeyDetailsQR = uniFfi.generateSecretKeyQr(
					publicKey = keyModel.pubkey,
					expectedSeedName = keyModel.address.cardBase.seedName,
					networkSpecsKey = keyModel.networkInfo.networkSpecsKey,
					seedPhrase = seedResult.result,
					keyPassword = password,
				)
				System.gc()

				when (secretKeyDetailsQR) {
					is UniffiResult.Error -> {
						if (passwordModel.attempt > 3) {
							ExportTryPasswordReply.ErrorAttemptsExceeded
						} else {
							ExportTryPasswordReply.UpdatePassword(
								passwordModel.copy(
									showError = true,
									attempt = passwordModel.attempt + 1
								)
							)
						}
					}

					is UniffiResult.Success -> {
						privateExportStateModel = secretKeyDetailsQR.result
						ExportTryPasswordReply.OK(password)
					}
				}
			}
		}
	}

	suspend fun removeDerivedKey(
		addressKey: String,
		networkSpecsKey: String,
	): UniffiResult<Unit> {
		return uniFfi.removedDerivedKey(addressKey, networkSpecsKey)
	}

	suspend fun getZcashDiversifiedAddress(
		seedName: String,
		accountIndex: UInt = 0u,
		diversifierIndex: UInt = 0u,
		mainnet: Boolean = true
	): String? {
		val seedResult = repo.getSeedPhrases(listOf(seedName))
		return when (seedResult) {
			is RepoResult.Failure -> null
			is RepoResult.Success -> {
				try {
					io.parity.signer.uniffi.getZcashDiversifiedAddress(seedResult.result, accountIndex, diversifierIndex, mainnet)
				} catch (e: Exception) {
					null
				}
			}
		}
	}

	suspend fun getZcashTransparentAddress(
		seedName: String,
		account: UInt = 0u,
		mainnet: Boolean = true
	): String? {
		val seedResult = repo.getSeedPhrases(listOf(seedName))
		return when (seedResult) {
			is RepoResult.Failure -> null
			is RepoResult.Success -> {
				try {
					io.parity.signer.uniffi.getZcashTransparentAddress(seedResult.result, account, mainnet)
				} catch (e: Exception) {
					null
				}
			}
		}
	}

	fun fetchZcashVerifiedBalance(): ULong? {
		return try {
			io.parity.signer.uniffi.getZcashVerifiedBalance()
		} catch (e: Exception) {
			null
		}
	}

	fun fetchZcashVerifiedNotes(): List<ZcashVerifiedNoteDisplay> {
		return try {
			io.parity.signer.uniffi.getZcashVerifiedNotes()
		} catch (e: Exception) {
			emptyList()
		}
	}

	fun fetchZcashSyncInfo(): ZcashSyncInfo? {
		return try {
			io.parity.signer.uniffi.getZcashSyncInfo()
		} catch (e: Exception) {
			null
		}
	}

	suspend fun exportFvk(
		seedName: String,
		networkLogo: String,
		accountIndex: UInt = 0u,
		label: String = "",
		mainnet: Boolean = true
	): FvkExportResult? {
		val seedResult = repo.getSeedPhraseForceAuth(seedName)
		return when (seedResult) {
			is RepoResult.Failure -> null
			is RepoResult.Success -> {
				try {
					when {
						networkLogo.lowercase().contains("zcash") -> {
							val export = exportZcashFvk(seedResult.result, accountIndex, label, mainnet)
							// Use UR-encoded string for Zashi/Keystone compatibility
							// The ur_string is "ur:zcash-accounts/..." which can be encoded directly as text QR
							val urBytes = export.urString.toByteArray(Charsets.UTF_8).map { it.toUByte() }
							val urQrPng = encodeToQr(urBytes, false)
							FvkExportResult(
								displayKey = export.ufvk,  // Show UFVK instead of raw hex
								qrPng = urQrPng.toList(),
								label = export.label,
								networkType = "zcash"
							)
						}
						networkLogo.lowercase().contains("penumbra") -> {
							val export = exportPenumbraFvk(seedResult.result, accountIndex, label)
							// Use UR-encoded string for hardware wallet QR compatibility
							// Format: "ur:penumbra-accounts/..." (CBOR tag 49301)
							val urBytes = export.urString.toByteArray(Charsets.UTF_8).map { it.toUByte() }
							val urQrPng = encodeToQr(urBytes, false)
							val receiveBytes = export.receiveQrText.toByteArray(Charsets.UTF_8).map { it.toUByte() }
							FvkExportResult(
								displayKey = export.fvkBech32m,
								qrPng = urQrPng.toList(),
								label = export.label,
								networkType = "penumbra",
								receiveAddress = export.receiveAddress,
								receiveQrPng = encodeToQr(receiveBytes, false).toList(),
							)
						}
						isCosmosNetwork(networkLogo) -> {
							val cosmosChainName = cosmosNetworkName(networkLogo)
							val export = exportCosmosAccounts(seedResult.result, accountIndex, label, cosmosChainName)
							FvkExportResult(
								displayKey = export.publicKeyHex.take(20) + "...",
								qrPng = export.qrData.toList(),
								label = export.label,
								networkType = "cosmos"
							)
						}
						else -> null
					}
				} catch (e: Exception) {
					null
				}
			}
		}
	}
}

private fun isCosmosNetwork(networkLogo: String): Boolean {
	val logo = networkLogo.lowercase()
	return logo.contains("noble") || logo.contains("osmosis") ||
		logo.contains("celestia") || logo.contains("cosmos")
}

/** Extract the specific cosmos chain name from network logo for single-chain export */
private fun cosmosNetworkName(networkLogo: String): String {
	val logo = networkLogo.lowercase()
	return when {
		logo.contains("noble") -> "noble"
		logo.contains("osmosis") -> "osmosis"
		logo.contains("celestia") -> "celestia"
		else -> "" // empty = export all
	}
}

data class FvkExportResult(
	val displayKey: String,
	val qrPng: List<UByte>,
	val label: String,
	val networkType: String,
	/** Penumbra only: the receive address, derived from the seed with the exported key */
	val receiveAddress: String? = null,
	val receiveQrPng: List<UByte>? = null,
)

sealed class ExportTryPasswordReply {
	data class OK(val password: String) : ExportTryPasswordReply()
	data class UpdatePassword(val model: EnterPasswordModel) :
		ExportTryPasswordReply()

	data object ErrorAttemptsExceeded : ExportTryPasswordReply()
	data object ErrorAuthWrong : ExportTryPasswordReply()
}

