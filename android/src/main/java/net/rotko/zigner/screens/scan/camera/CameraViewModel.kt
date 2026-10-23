package net.rotko.zigner.screens.scan.camera

import android.annotation.SuppressLint
import android.os.Trace
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.common.InputImage
import net.rotko.zigner.domain.encodeHex
import net.rotko.zigner.domain.submitErrorState
import io.parity.signer.uniffi.BananaSplitRecoveryResult
import io.parity.signer.uniffi.DecodeSequenceResult
import io.parity.signer.uniffi.decodeUrModuleRequest
import io.parity.signer.uniffi.decodeUrZcashPczt
import io.parity.signer.uniffi.releaseClassifyRequest
import io.parity.signer.uniffi.zcashNotesScanProgress
import io.parity.signer.uniffi.qrparserGetPacketsTotal
import io.parity.signer.uniffi.qrparserTryDecodeQrSequence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import net.rotko.zigner.domain.scan.ScanCapability
import net.rotko.zigner.domain.scan.ScanPolicy
import timber.log.Timber


private const val PROBE_EVERY_N = 4

class CameraViewModel() : ViewModel() {

	// ── scan policy ─────────────────────────────────────────────────────────
	//
	// The camera is the only untrusted input this app has, and the dispatcher
	// below routes purely on payload content. Without a gate, every parser
	// compiled in is reachable by anyone who can put a QR code in front of the
	// lens - including chains the user never enabled, which are also the least
	// exercised code here.
	//
	// Every route therefore passes through `allow()` BEFORE the payload
	// reaches a parser. Classifying by prefix first is a fixed-length string
	// compare, not the thing being defended against.
	//
	// Defaults rather than empty when unset: a missed wiring call should not
	// brick scanning, and the defaults already exclude Substrate, Penumbra and
	// Cosmos, so the security property survives that mistake.
	private var allowed: Set<ScanCapability> = ScanPolicy.DEFAULT_ON

	private val _refusedCapability = MutableStateFlow<ScanCapability?>(null)

	/** Set when a payload was refused, so the UI can say why rather than
	 *  appearing to ignore the code. */
	val refusedCapability: StateFlow<ScanCapability?> = _refusedCapability.asStateFlow()

	fun applyPolicy(context: android.content.Context) {
		allowed = ScanPolicy.enabled(context)
	}

	fun clearRefusal() {
		_refusedCapability.value = null
	}

	// Upper bound on accumulated frames for one payload.
	//
	// Sized off the largest LEGITIMATE scan, not off what feels tidy: a full
	// 2.7 MB module is ~6,000 fragments and the emitter sends roughly twice
	// that for fountain coverage, so anything under ~15,000 would reject a
	// real module update. Deltas need a few hundred.
	//
	// It bounds memory rather than preventing an attack outright. Exhausting
	// RAM this way means holding the camera at a hostile display for hours at
	// frame rate; the reason to cap is that unbounded growth driven by an
	// attacker-declared count should not be possible at all.
	private val MAX_ACCUMULATED_FRAMES = 20_000

	// Membership sets for frame dedup, one per accumulating flow.
	//
	// The list itself is still the payload; this only answers "seen already".
	// Scanning the list with `any { }` is O(n) per frame and O(n^2) per scan,
	// and a real module update runs to thousands of frames - enough string
	// comparison on the main thread to make scanning visibly stutter.
	private val seenFrameSets =
		mutableMapOf<MutableStateFlow<List<String>>, MutableSet<String>>()

	private fun seenFrames(flow: MutableStateFlow<List<String>>): MutableSet<String> =
		seenFrameSets.getOrPut(flow) {
			// Rebuild from whatever the flow already holds, so a set created
			// mid-scan does not forget frames taken before it existed.
			flow.value.map { it.lowercase() }.toMutableSet()
		}

	private fun forgetFrames(flow: MutableStateFlow<List<String>>) {
		seenFrameSets.remove(flow)
	}

	private fun tooManyFrames(count: Int, what: String): Boolean {
		if (count < MAX_ACCUMULATED_FRAMES) return false
		Timber.w("[SCAN] $what exceeded $MAX_ACCUMULATED_FRAMES frames - discarding")
		return true
	}

	private fun allow(cap: ScanCapability): Boolean {
		if (allowed.contains(cap)) return true
		Timber.w("[SCAN] refused a ${cap.label} payload - capability disabled")
		_refusedCapability.value = cap
		return false
	}


	val isTorchEnabled = MutableStateFlow(false)

	private val _bananaSplitPayload = MutableStateFlow<List<String>?>(null)
	val bananaSplitPayload: StateFlow<List<String>?> =
		_bananaSplitPayload.asStateFlow()

	private val _pendingTransactionPayloads =
		MutableStateFlow<Set<String>>(emptySet())
	val pendingTransactionPayloads: StateFlow<Set<String>> =
		_pendingTransactionPayloads.asStateFlow()

	// Penumbra sign request payload (detected by 530310 prefix)
	private val _penumbraSignRequestPayload = MutableStateFlow<String?>(null)
	val penumbraSignRequestPayload: StateFlow<String?> =
		_penumbraSignRequestPayload.asStateFlow()

	// Cosmos sign request payload (detected by 530510 prefix)
	private val _cosmosSignRequestPayload = MutableStateFlow<String?>(null)
	val cosmosSignRequestPayload: StateFlow<String?> =
		_cosmosSignRequestPayload.asStateFlow()

	// Zcash simple sign request payload (detected by 530402 prefix)
	private val _zcashSimpleSignPayload = MutableStateFlow<String?>(null)
	val zcashSimpleSignPayload: StateFlow<String?> =
		_zcashSimpleSignPayload.asStateFlow()

	// Zcash PCZT protocol-module request payload (530403 single / 530404 batch),
	// routed to the wasm protocol module instead of the built-in parsers.
	private val _zcashModulePcztPayload = MutableStateFlow<String?>(null)
	val zcashModulePcztPayload: StateFlow<String?> =
		_zcashModulePcztPayload.asStateFlow()

	// UR backup frames (multipart UR QR codes starting with "ur:")
	private val _urBackupFrames = MutableStateFlow<List<String>>(emptyList())
	val urBackupFrames: StateFlow<List<String>> = _urBackupFrames.asStateFlow()
	private val _urBackupComplete = MutableStateFlow<List<String>?>(null)
	val urBackupComplete: StateFlow<List<String>?> = _urBackupComplete.asStateFlow()

	// UR zcash-notes frames (note sync via animated QR)
	private val _zcashNotesFrames = MutableStateFlow<List<String>>(emptyList())
	private val _zcashNotesComplete = MutableStateFlow<List<String>?>(null)
	val zcashNotesComplete: StateFlow<List<String>?> = _zcashNotesComplete.asStateFlow()

	// UR zcash-pczt frames (PCZT signing via animated QR)
	private val _zcashPcztFrames = MutableStateFlow<List<String>>(emptyList())
	private val _zcashPcztComplete = MutableStateFlow<List<String>?>(null)
	val zcashPcztComplete: StateFlow<List<String>?> = _zcashPcztComplete.asStateFlow()

	// UR zigner-module request frames (ur:zigner-module protocol-module PCZT request
	// or module package via animated QR); fountain-decoded to either
	// _zcashModulePcztPayload or _modulePackageBytes based on magic bytes.
	private val _zignerModuleFrames = MutableStateFlow<List<String>>(emptyList())

	// UR zigner-module package bytes (module package decoded from ur:zigner-module
	// fountain frames; manifest || wasm starting with "ZIGM").
	private val _modulePackageBytes = MutableStateFlow<ByteArray?>(null)
	val modulePackageBytes: StateFlow<ByteArray?> = _modulePackageBytes.asStateFlow()

	// A base64 release-signing prefix (magic "ZIGM"), scanned in one plain QR
	// from the zafu.pro ceremony. Distinct from a module PACKAGE, which is large
	// and arrives as ur:zigner-module fountain frames.
	private val _releaseSignPrefix = MutableStateFlow<ByteArray?>(null)
	val releaseSignPrefix: StateFlow<ByteArray?> = _releaseSignPrefix.asStateFlow()

	// JSON payloads (detected by {"frost":...} or {"auth":...} prefix)
	private val _frostPayload = MutableStateFlow<JSONObject?>(null)
	val frostPayload: StateFlow<JSONObject?> = _frostPayload.asStateFlow()
	private val _authPayload = MutableStateFlow<JSONObject?>(null)
	val authPayload: StateFlow<JSONObject?> = _authPayload.asStateFlow()
	private val _zidSignPayload = MutableStateFlow<JSONObject?>(null)
	val zidSignPayload: StateFlow<JSONObject?> = _zidSignPayload.asStateFlow()

	private val _total = MutableStateFlow<Int?>(null)
	private val _captured = MutableStateFlow<Int?>(null)

	// Observables for model data
	internal val total: StateFlow<Int?> = _total.asStateFlow()
	internal val captured: StateFlow<Int?> = _captured.asStateFlow()

	// payload of currently scanned qr codes for multiqr transaction like metadata update.
	private var currentMultiQrTransaction = mutableSetOf<String>()

	// FROST DKG multi-frame assembler — frames look like
	// `P<idx>/<total>/zafu-frost-dkg/<base64>`. Reset whenever the
	// (total, type) changes so a stale half-collected sequence doesn't
	// mix with a new one.
	private val pFrameChunks = mutableMapOf<Int, String>()
	private var pFrameTotal = 0
	private var pFrameType = ""

	/**
	 * Barcode detecting function.
	 * This uses experimental features
	 */
	@SuppressLint("UnsafeOptInUsageError")
	fun processFrame(
		barcodeScanner: BarcodeScanner,
		imageProxy: ImageProxy
	) {
		Trace.beginSection("process frame")
		if (imageProxy.image == null) return
		val inputImage = InputImage.fromMediaImage(
			imageProxy.image!!,
			imageProxy.imageInfo.rotationDegrees,
		)

		barcodeScanner.process(inputImage)
			.addOnSuccessListener { barcodes ->
				Trace.beginSection("process frame vault code")
				barcodes.forEach {
					// zafu's QrDisplay / AnimatedQrDisplay render UR, zoda and P-frame
					// transports as BYTE-mode QR codes (lowercase content forces byte
					// mode), so ML Kit leaves rawValue null and the content lives in
					// rawBytes (UTF-8). Resolve a single source that falls back to
					// rawBytes so every transport is recognised regardless of QR mode.
					val textValue = it?.rawValue
					val rawBytesLen = it?.rawBytes?.size ?: -1
					Timber.d("[FROST] barcode: rawValueLen=${textValue?.length ?: -1} rawBytesLen=$rawBytesLen format=${it?.format}")
					val frameSource: String? = textValue
						?: it?.rawBytes?.toString(Charsets.UTF_8)

					// Check for UR QR codes first (start with "ur:")
					if (frameSource != null && frameSource.lowercase().startsWith("ur:")) {
						processUrFrame(frameSource)
						return@forEach
					}

					// zoda transport (`zt:type/hex`) — note-sync and erasure-coded
					// frames. ML Kit returns these as ASCII text or rawBytes.
					if (frameSource != null && frameSource.lowercase().startsWith("zt:")) {
						processZtFrame(frameSource)
						return@forEach
					}

					// Check for FROST/auth/zid JSON QR codes (plain JSON or P-frame).
					// The P-frame multi-frame format from zafu's AnimatedQrDisplay
					// (`P<idx>/<total>/<urType>/<base64>`) accumulates until complete,
					// then reassembles to the original UTF-8 payload string.
					val pFrameMatch = frameSource?.let {
						Regex("""^P(\d+)/(\d+)/([^/]+)/(.+)$""").matchEntire(it)
					}
					val jsonText: String? = if (pFrameMatch != null) {
						val idx = pFrameMatch.groupValues[1].toInt()
						val total = pFrameMatch.groupValues[2].toInt()
						val urType = pFrameMatch.groupValues[3]
						val chunk = pFrameMatch.groupValues[4]
						if (pFrameTotal != total || pFrameType != urType) {
							pFrameChunks.clear()
							pFrameTotal = total
							pFrameType = urType
						}
						val isNew = !pFrameChunks.containsKey(idx)
						pFrameChunks[idx] = chunk
						// surface progress to the camera UI via the same state flows
						// the substrate multi-QR scanner uses, so the user sees a
						// scanned/total counter while frames are still arriving.
						_total.value = total
						_captured.value = pFrameChunks.size
						if (isNew) {
							Timber.d("[FROST] P-frame received: $idx/$total type=$urType have=${pFrameChunks.size}")
						}
						if (pFrameChunks.size >= total) {
							try {
								val b64 = (1..total).joinToString("") { i -> pFrameChunks[i] ?: "" }
								val bytes = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
								val decoded = String(bytes, Charsets.UTF_8)
								Timber.d("[FROST] P-frame assembled: ${bytes.size}B type=$urType head=${decoded.take(60)}")
								pFrameChunks.clear(); pFrameTotal = 0; pFrameType = ""
								_total.value = null; _captured.value = null
								if (decoded.trimStart().startsWith("{")) decoded else null
							} catch (e: Exception) {
								Timber.e(e, "[FROST] P-frame assembly failed")
								pFrameChunks.clear(); pFrameTotal = 0; pFrameType = ""
								_total.value = null; _captured.value = null
								null
							}
						} else null
					} else run {
						if (textValue != null && textValue.trimStart().startsWith("{")) {
							Timber.d("[FROST] JSON via rawValue: ${textValue.take(80)}")
							return@run textValue
						}
						val bytes = it?.rawBytes ?: return@run null
						val decoded = bytes.toString(Charsets.UTF_8)
						val trimmed = decoded.trimStart()
						Timber.d("[FROST] rawBytes decoded head: ${trimmed.take(80)}")
						if (trimmed.startsWith("{")) decoded else null
					}
					if (jsonText != null) {
						try {
							val json = JSONObject(jsonText)
							Timber.d("[FROST] JSON parsed; keys=${json.keys().asSequence().toList()}")
							if (json.has("frost")) {
								if (!allow(ScanCapability.FROST)) return@forEach
								Timber.d("[FROST] frost type=${json.optString("frost")} -> setting frostPayload")
								resetScanValues()
								_frostPayload.value = json
								return@forEach
							}
							if (json.has("auth")) {
								if (!allow(ScanCapability.AUTH)) return@forEach
								resetScanValues()
								_authPayload.value = json
								return@forEach
							}
							if (json.optString("type") == "zid-sign") {
								// zid-sign is the identity surface, same as
								// {"auth":...} above - it was missed when the
								// gate went in, sitting one branch away from a
								// route that is gated.
								if (!allow(ScanCapability.AUTH)) return@forEach
								resetScanValues()
								_zidSignPayload.value = json
								return@forEach
							}
							Timber.w("[FROST] JSON had no frost/auth/zid keys, falling through")
						} catch (e: Exception) {
							Timber.w(e, "[FROST] JSON parse failed for: ${jsonText.take(80)}")
						}
					}
					// P-frame partial (or complete-but-not-FROST): bail before the
					// substrate fallback re-processes the P-frame text.
					if (pFrameMatch != null) {
						return@forEach
					}

					// Release-signing prefix: one plain base64 QR (magic "ZIGM"),
					// authoritative check is releaseClassifyRequest, which accepts
					// only an EXACT prefix - a module package (trailing sigs/payload)
					// is rejected here and stays on its ur:zigner-module path.
					val releasePrefix = frameSource?.let { tryDecodeReleasePrefix(it) }
					if (releasePrefix != null) {
						if (!allow(ScanCapability.MODULE)) return@forEach
						resetScanValues()
						_releaseSignPrefix.value = releasePrefix
						return@forEach
					}

					// Try rawBytes first; fall back to rawValue for byte-mode QR codes
					// where ML Kit may return null rawBytes but valid Latin-1 text
					val fromRawBytes = it?.rawBytes?.encodeHex()
					val fromRawValue = it?.rawValue?.let { text ->
						text.map { ch -> "%02x".format(ch.code and 0xFF) }
							.joinToString("")
							.takeIf { hex -> hex.length >= 6 && hex.startsWith("53") }
					}
					// A framed cosmos QR starts with 530510. ML Kit sometimes
					// returns only the amino JSON, which starts with '{', and
					// that used to fall through without opening the signer.
					val binary = fromRawBytes ?: fromRawValue
					val payloadString = if (binary != null && binary.startsWith("530510")) {
						binary
					} else {
						aminoAsCosmosHex(textValue) ?: binary
					}

					if (!currentMultiQrTransaction.contains(payloadString) && !payloadString.isNullOrEmpty()) {
						val knownTotal = total.value

						if (knownTotal == null) {
							try {
								val proposeTotal =
									qrparserGetPacketsTotal(payloadString, true).toInt()
								if (proposeTotal == 1) {
									decode(listOf(payloadString))
								} else {
									currentMultiQrTransaction += payloadString
									_captured.value = currentMultiQrTransaction.size
									_total.value = proposeTotal
								}
							} catch (e: java.lang.Exception) {
								Timber.d(e, "getPacketsTotal failed")
							}
						} else {
							currentMultiQrTransaction += payloadString

							if (currentMultiQrTransaction.size >= knownTotal) {
								decode(currentMultiQrTransaction.toList())
							} else {
								_captured.value = currentMultiQrTransaction.size
							}
						}
					}
				}
				Trace.endSection()
			}
			.addOnFailureListener {
				Timber.e(it, "Scan failed")
			}
			.addOnCompleteListener {
				Trace.endSection()
				imageProxy.close()
			}
	}

	private fun decode(completePayload: List<String>) {
		try {
			val firstPayload = completePayload.firstOrNull() ?: return

			if (isPenumbraTransaction(firstPayload)) {
				if (!allow(ScanCapability.PENUMBRA)) return
				resetScanValues()
				_penumbraSignRequestPayload.value = firstPayload
				return
			}

			if (isCosmosSignRequest(firstPayload)) {
				if (!allow(ScanCapability.COSMOS)) return
				resetScanValues()
				_cosmosSignRequestPayload.value = firstPayload
				return
			}

			if (isZcashSimpleSignRequest(firstPayload)) {
				if (!allow(ScanCapability.ZCASH)) return
				resetScanValues()
				_zcashSimpleSignPayload.value = firstPayload
				return
			}

			if (isZcashModulePcztRequest(firstPayload)) {
				if (!allow(ScanCapability.ZCASH)) return
				resetScanValues()
				_zcashModulePcztPayload.value = firstPayload
				return
			}

			val payload = qrparserTryDecodeQrSequence(
				data = completePayload,
				password = null,
				cleaned = true,
			)
			when (payload) {
				is DecodeSequenceResult.BBananaSplitRecoveryResult -> {
					when (payload.b) {
						is BananaSplitRecoveryResult.RecoveredSeed -> {
							submitErrorState("cannot happen here that for scanning we don't have password request")
						}

						BananaSplitRecoveryResult.RequestPassword -> {
							if (!allow(ScanCapability.BACKUP)) return
							resetScanValues()
							_bananaSplitPayload.value = completePayload
						}
					}
				}

				is DecodeSequenceResult.Other -> {
					resetScanValues()
					// Multi-frame PCZT module requests arrive here after the
					// Rust sequence decoder reassembles them into one hex payload.
					if (isZcashModulePcztRequest(payload.s)) {
						if (!allow(ScanCapability.ZCASH)) return
						_zcashModulePcztPayload.value = payload.s
					} else {
						// The Substrate path: SCALE plus runtime metadata, the
						// largest parser in the app and the one with the most
						// upstream churn.
						if (!allow(ScanCapability.SUBSTRATE)) return
						addPendingTransaction(payload.s)
					}
				}

				// No screen renders these any more (dynamicderivations deleted);
				// the capability gate refuses them the same way an unknown
				// payload is refused.
				is DecodeSequenceResult.DynamicDerivations,
				is DecodeSequenceResult.DynamicDerivationTransaction -> {
					allow(ScanCapability.SUBSTRATE)
				}
			}

		} catch (e: Exception) {
			Timber.e(e, "Single frame decode failed")
		}
	}

	/**
	 * Check if hex payload is a Penumbra transaction (prefix 530310)
	 */
	/**
	 * Amino JSON that arrived without the 530510 frame. The signed bytes are
	 * the JSON itself. The frame only tells the parser the chain prefix.
	 */
	private fun aminoAsCosmosHex(text: String?): String? {
		val trimmed = text?.trim() ?: return null
		if (!trimmed.startsWith("{")) return null
		val json = try {
			JSONObject(trimmed)
		} catch (_: Exception) {
			return null
		}
		if (!json.has("msgs") || !json.has("chain_id")) return null
		val doc = trimmed.toByteArray(Charsets.UTF_8)
		if (doc.isEmpty() || doc.size > 65536) return null
		val out = java.io.ByteArrayOutputStream(16 + doc.size)
		out.write(byteArrayOf(0x53, 0x05, 0x10))
		out.write(byteArrayOf(0, 0, 0, 0))
		val name = "terp".toByteArray(Charsets.US_ASCII)
		out.write(name.size)
		out.write(name)
		val size = doc.size
		out.write(
			byteArrayOf(
				(size and 0xff).toByte(),
				((size shr 8) and 0xff).toByte(),
				((size shr 16) and 0xff).toByte(),
				((size shr 24) and 0xff).toByte(),
			)
		)
		out.write(doc)
		return out.toByteArray().encodeHex()
	}

	private fun isPenumbraTransaction(hexPayload: String): Boolean {
		return hexPayload.length >= 6 && hexPayload.substring(0, 6).equals("530310", ignoreCase = true)
	}

	/**
	 * Check if hex payload is a Cosmos sign request (prefix 530510)
	 */
	private fun isCosmosSignRequest(hexPayload: String): Boolean {
		return hexPayload.length >= 6 && hexPayload.substring(0, 6).equals("530510", ignoreCase = true)
	}

	/**
	 * Check if hex payload is a Zcash simple sign request (prefix 530402)
	 */
	private fun isZcashSimpleSignRequest(hexPayload: String): Boolean {
		return hexPayload.length >= 6 && hexPayload.substring(0, 6).equals("530402", ignoreCase = true)
	}

	/**
	 * Check if hex payload is a Zcash PCZT protocol-module request:
	 * prelude [0x53][crypto=0x04][tx_type], 0x03 single / 0x04 batch,
	 * 0x05 single-compact / 0x06 batch-compact (signatures-only response -
	 * the Ironwood QR shrink; see rust/pczt_signing/src/envelope.rs).
	 */
	/**
	 * Route any zcash-crypto module envelope to the protocol module.
	 *
	 * Deliberately matches on the [0x53][crypto=zcash 0x04] prelude ONLY, not on
	 * the tx_type byte: the kernel owns trust (it verifies the module's
	 * signature), the signed module owns protocol. Enumerating tx_types here
	 * would mean every new envelope type needs a new APK - and on a device that
	 * never goes online, an APK update costs the user an airgap break (USB +
	 * debugging). The module already fail-closes on anything it does not
	 * recognise with a named "unknown zcash tx type" error, and response
	 * tx_types (0x07/0x08) are refused by the envelope parser rather than
	 * mistaken for requests, so widening this does not weaken any gate.
	 */
	private fun isZcashModulePcztRequest(hexPayload: String): Boolean {
		if (hexPayload.length < 6) return false
		return hexPayload.substring(0, 4).lowercase() == "5304"
	}

	fun resetZcashSimpleSign() {
		_zcashSimpleSignPayload.value = null
	}

	fun resetZcashModulePczt() {
		_zcashModulePcztPayload.value = null
	}

	/**
	 * Process UR (Uniform Resource) QR frame for backup restore
	 * UR format: "ur:type/sequence/fragment" for multipart or "ur:type/fragment" for single
	 */
	private fun processUrFrame(urString: String) {
		val normalizedUr = urString.lowercase()

		when {
			normalizedUr.startsWith("ur:zigner-backup") -> {
				if (!allow(ScanCapability.BACKUP)) return
				processUrFrameForType(urString, normalizedUr, "ur:zigner-backup", _urBackupFrames) { frames ->
					_urBackupComplete.value = frames
				}
			}
			normalizedUr.startsWith("ur:zcash-notes") -> {
				if (!allow(ScanCapability.ZCASH)) return
				processUrFrameForType(urString, normalizedUr, "ur:zcash-notes", _zcashNotesFrames) { frames ->
					_zcashNotesComplete.value = frames
				}
			}
			normalizedUr.startsWith("ur:zcash-pczt") -> {
				if (!allow(ScanCapability.ZCASH)) return
				processUrFountainFrame(urString, normalizedUr, "ur:zcash-pczt", _zcashPcztFrames,
					tryDecode = { frames ->
						try { decodeUrZcashPczt(frames); true } catch (_: Exception) { false }
					},
				) { frames ->
					_zcashPcztComplete.value = frames
				}
			}
			// Protocol-module requests (PCZT) and module packages over BC-UR fountain.
			// ur:zigner-module can carry either:
			// 1. PCZT request: RAW prelude envelope [0x53][0x04][0x03]||pczt
			// 2. Module package: manifest || wasm, starting with magic "ZIGM" [0x5A][0x49][0x47][0x4D]
			// Fountain-decode to raw bytes, then dispatch based on magic/prelude.
			normalizedUr.startsWith("ur:zigner-module") -> {
				// Carries both module packages and Zcash PCZT requests, so it
				// needs either capability to be worth decoding at all; the
				// inner dispatch gates each on its own below.
				if (!allow(ScanCapability.MODULE) && !allow(ScanCapability.ZCASH)) return
				processUrFountainFrame(urString, normalizedUr, "ur:zigner-module", _zignerModuleFrames,
					tryDecode = { frames ->
						try { decodeUrModuleRequest(frames); true } catch (_: Exception) { false }
					},
				) { frames ->
					try {
						val decoded = decodeUrModuleRequest(frames)
						val payloadBytes = ByteArray(decoded.size) { i -> decoded[i].toByte() }

						// Magic is checked on BYTES, and the payload is only
						// hex-encoded on the PCZT branch. A module package runs
						// to megabytes, so hex-encoding it up front to inspect a
						// 3-byte prelude would allocate a string twice its size
						// on every scan.
						if (payloadBytes.size >= 4 &&
							payloadBytes[0] == 0x5A.toByte() &&
							payloadBytes[1] == 0x49.toByte() &&
							payloadBytes[2] == 0x47.toByte() &&
							payloadBytes[3] == 0x4D.toByte()) {
							// Module package detected
							Timber.d("[MODULE] ur:zigner-module decoded to module package: ${payloadBytes.size}B")
							if (!allow(ScanCapability.MODULE)) return@processUrFountainFrame
							resetZignerModuleFrames()
							_modulePackageBytes.value = payloadBytes
						} else if (isZcashModulePcztRequest(payloadBytes.take(4).toByteArray().encodeHex())) {
							// PCZT request detected
							val payloadHex = payloadBytes.encodeHex()
							Timber.d("[MODULE] ur:zigner-module decoded to PCZT request: prelude=${payloadHex.take(6)}")
							if (!allow(ScanCapability.ZCASH)) return@processUrFountainFrame
							resetZignerModuleFrames()
							_zcashModulePcztPayload.value = payloadHex
						} else {
							val prelude = payloadBytes.take(3).toByteArray().encodeHex()
							Timber.w("[MODULE] ur:zigner-module payload is neither module package nor PCZT request; prelude=$prelude")
							resetZignerModuleFrames()
						}
					} catch (e: Exception) {
						Timber.e(e, "[MODULE] ur:zigner-module fountain decode failed")
						resetZignerModuleFrames()
					}
				}
			}
			else -> {
				Timber.d("Ignoring unknown UR type: $normalizedUr")
			}
		}
	}

	// zoda transport (`zt:type/hex`): k-of-n verified erasure frames. Unlike
	// UR there is no per-frame sequence header (the threshold k lives in
	// frame-0 metadata), so accumulate distinct frames and poll the pure Rust
	// completeness probe rather than parsing zoda's wire layout here.
	private fun processZtFrame(ztString: String) {
		val normalized = ztString.lowercase()
		if (!allow(ScanCapability.ZCASH)) return
		if (!normalized.startsWith("zt:zcash-notes/")) {
			Timber.d("Ignoring unknown zt type: ${normalized.take(24)}")
			return
		}
		val current = _zcashNotesFrames.value
		if (current.any { it.lowercase() == normalized }) return

		val updated = current + ztString
		_zcashNotesFrames.value = updated
		// captured = frames scanned so far (keeps the counter moving). total
		// (`needed`) comes from the zoda frame-0 metadata via the probe.
		_captured.value = updated.size

		// poll the pure Rust probe (cheap — receive loop, no reconstruct until
		// enough shards) for the threshold and completion signal.
		val progress = zcashNotesScanProgress(updated)
		if (progress.needed > 0) _total.value = progress.needed
		if (progress.complete) {
			resetScanValues()
			_zcashNotesComplete.value = updated
		}
	}

	// BC-UR fountain: probe decoder past seqLen threshold (typical convergence
	// 1.05–1.20×); captured stays uncapped so the counter keeps moving.
	private fun processUrFountainFrame(
		urString: String,
		normalizedUr: String,
		urPrefix: String,
		framesFlow: MutableStateFlow<List<String>>,
		tryDecode: (List<String>) -> Boolean,
		onComplete: (List<String>) -> Unit,
	) {
		val currentFrames = framesFlow.value
		// Dedup against a set: `any { }` is O(n) per frame and so O(n^2) over a
		// scan. At the frame counts a real module update needs, that is enough
		// string comparison on the main thread to make scanning visibly slow.
		if (normalizedUr in seenFrames(framesFlow)) return
		if (tooManyFrames(currentFrames.size, "fountain frames")) {
			framesFlow.value = emptyList()
			forgetFrames(framesFlow)
			resetScanValues()
			return
		}

		val updatedFrames = currentFrames + urString
		framesFlow.value = updatedFrames
		seenFrames(framesFlow).add(normalizedUr)

		val sequenceMatch = Regex("$urPrefix/(\\d+)-(\\d+)/").find(normalizedUr)
		if (sequenceMatch == null) {
			// Single-part UR — decode immediately.
			resetScanValues()
			onComplete(listOf(urString))
			return
		}

		val seqLen = sequenceMatch.groupValues[2].toIntOrNull() ?: 1
		// NOTE: do NOT surface seqLen as the scanner "total". The zafu/zigner
		// emitter deliberately generates ~2× the fragment count (fountain
		// redundancy) while declaring seqLen = the base fragment count, so the
		// number of frames you actually scan routinely exceeds seqLen — showing
		// "captured / seqLen" renders a bogus overflow like "48 / 24" that looks
		// like a broken scan. A BC-UR fountain has no fixed frame total anyway:
		// it completes once enough *unique* parts are collected to decode. So
		// leave _total null here and let ScanProgressBar render "N / … complete".
		_captured.value = updatedFrames.size

		val framesPastThreshold = updatedFrames.size - seqLen
		val probeNow = framesPastThreshold >= 0 && framesPastThreshold % PROBE_EVERY_N == 0
		if (probeNow && tryDecode(updatedFrames)) {
			resetScanValues()
			onComplete(updatedFrames)
		}
	}

	private fun processUrFrameForType(
		urString: String,
		normalizedUr: String,
		urPrefix: String,
		framesFlow: MutableStateFlow<List<String>>,
		onComplete: (List<String>) -> Unit,
	) {
		val currentFrames = framesFlow.value
		if (currentFrames.any { it.lowercase() == normalizedUr }) {
			return
		}

		val updatedFrames = currentFrames + urString
		framesFlow.value = updatedFrames

		val sequenceMatch = Regex("$urPrefix/(\\d+)-(\\d+)/").find(normalizedUr)
		if (sequenceMatch != null) {
			val (_, totalStr) = sequenceMatch.destructured
			val totalFrames = totalStr.toIntOrNull() ?: 1
			_total.value = totalFrames
			_captured.value = updatedFrames.size

			if (updatedFrames.size >= totalFrames) {
				resetScanValues()
				onComplete(updatedFrames)
			}
		} else {
			resetScanValues()
			onComplete(listOf(urString))
		}
	}

	private fun addPendingTransaction(payload: String) {
		_pendingTransactionPayloads.value += payload
	}

	/**
	 * Clears camera progress
	 */
	fun resetScanValues() {
		currentMultiQrTransaction = mutableSetOf()
		_captured.value = null
		_total.value = null
	}

	fun resetPendingTransactions() {
		_pendingTransactionPayloads.value = emptySet()
		_bananaSplitPayload.value = null
		_penumbraSignRequestPayload.value = null
		_cosmosSignRequestPayload.value = null
		_zcashSimpleSignPayload.value = null
		_zcashModulePcztPayload.value = null
		_urBackupFrames.value = emptyList()
		forgetFrames(_urBackupFrames)
		_urBackupComplete.value = null
		_zcashNotesFrames.value = emptyList()
		forgetFrames(_zcashNotesFrames)
		_zcashNotesComplete.value = null
		_zcashPcztFrames.value = emptyList()
		forgetFrames(_zcashPcztFrames)
		_zcashPcztComplete.value = null
		_zignerModuleFrames.value = emptyList()
		forgetFrames(_zignerModuleFrames)
		_modulePackageBytes.value = null
		_releaseSignPrefix.value = null
		_frostPayload.value = null
		_authPayload.value = null
		_zidSignPayload.value = null
		resetScanValues()
	}

	fun resetUrBackup() {
		_urBackupFrames.value = emptyList()
		forgetFrames(_urBackupFrames)
		_urBackupComplete.value = null
	}

	fun resetZcashNotes() {
		_zcashNotesFrames.value = emptyList()
		forgetFrames(_zcashNotesFrames)
		_zcashNotesComplete.value = null
	}

	fun resetZcashPczt() {
		_zcashPcztFrames.value = emptyList()
		forgetFrames(_zcashPcztFrames)
		_zcashPcztComplete.value = null
	}

	fun resetZignerModuleFrames() {
		_zignerModuleFrames.value = emptyList()
		forgetFrames(_zignerModuleFrames)
	}

	fun resetModulePackageBytes() {
		_modulePackageBytes.value = null
	}

	fun resetReleaseSignPrefix() {
		_releaseSignPrefix.value = null
	}

	// Cheap gates (base64 charset, small, magic "ZIGM") before the FFI, then the
	// authoritative releaseClassifyRequest, which accepts ONLY an exact signing
	// prefix. Returns the raw prefix bytes if this scan is one, else null.
	private fun tryDecodeReleasePrefix(s: String): ByteArray? {
		val t = s.trim()
		if (t.length < 8 || t.length > 8192) return null
		if (!Regex("^[A-Za-z0-9+/=]+$").matches(t)) return null
		val bytes = try {
			android.util.Base64.decode(t, android.util.Base64.DEFAULT)
		} catch (_: Exception) {
			return null
		}
		if (bytes.size < 4 ||
			bytes[0] != 0x5A.toByte() || bytes[1] != 0x49.toByte() ||
			bytes[2] != 0x47.toByte() || bytes[3] != 0x4D.toByte()
		) {
			return null
		}
		return try {
			releaseClassifyRequest(bytes.map { it.toUByte() })
			bytes
		} catch (_: Exception) {
			null
		}
	}

	fun resetFrostPayload() {
		_frostPayload.value = null
	}

	fun resetAuthPayload() {
		_authPayload.value = null
	}

	fun resetZidSignPayload() {
		_zidSignPayload.value = null
	}

	fun resetPenumbraSignRequest() {
		_penumbraSignRequestPayload.value = null
	}

	fun resetCosmosSignRequest() {
		_cosmosSignRequestPayload.value = null
	}
}
