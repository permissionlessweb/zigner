package net.rotko.zigner.screens.scan.transaction

import io.parity.signer.uniffi.CosmosSignRequest
import io.parity.signer.uniffi.PenumbraSignRequest
import io.parity.signer.uniffi.ZcashPcztInspection
import io.parity.signer.uniffi.ZcashSimpleSignRequest

/**
 * Unified sign request sealed class.
 * Wraps all network-specific sign request types so the unified transaction
 * screen can render each network differently while sharing the same flow.
 */
sealed class SignRequest {
	/** Network display name for the header */
	abstract val networkName: String

	/** Raw QR payload for the "Complex" tab */
	abstract val rawPayload: String

	data class Penumbra(
		val request: PenumbraSignRequest,
	) : SignRequest() {
		override val networkName = "Penumbra"
		override val rawPayload = request.rawQrHex
	}

	data class Cosmos(
		val request: CosmosSignRequest,
	) : SignRequest() {
		override val networkName: String
			get() = request.chainName.ifEmpty { "Cosmos" }
		override val rawPayload = request.rawQrHex
	}

	data class ZcashPczt(
		val urParts: List<String>,
		val inspection: ZcashPcztInspection? = null,
		val pcztBytes: List<UByte> = emptyList(),
	) : SignRequest() {
		override val networkName = "Zcash (PCZT)"
		override val rawPayload: String
			get() = urParts.joinToString("\n")
	}

	data class ZcashSimple(
		val request: ZcashSimpleSignRequest,
	) : SignRequest() {
		override val networkName: String
			get() = if (request.mainnet) "Zcash" else "Zcash (Testnet)"
		override val rawPayload = request.rawQrHex
	}
}

/**
 * Unified signature result sealed class.
 * Wraps the signing output so the unified signature QR screen can
 * display hex QR or animated UR QR based on the network.
 */
sealed class SignatureResult {
	abstract val networkName: String

	/** Hex-encoded signature bytes displayed as a single QR code */
	data class HexQr(
		val hexString: String,
		override val networkName: String,
		val publicKeyHex: String? = null,
	) : SignatureResult()

	/** UR-encoded parts displayed as animated QR codes */
	data class UrParts(
		val parts: List<String>,
		override val networkName: String,
		val publicKeyHex: String? = null,
	) : SignatureResult()
}
