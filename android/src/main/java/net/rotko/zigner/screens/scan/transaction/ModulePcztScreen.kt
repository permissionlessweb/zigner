package net.rotko.zigner.screens.scan.transaction

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import net.rotko.zigner.components.security.TapjackGuard
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.qrcode.QrPlaybackSpeedSlider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.QrPlaybackSpeed
import net.rotko.zigner.domain.decodeHex
import net.rotko.zigner.domain.module.ProtocolModule
import net.rotko.zigner.ui.theme.*
import io.parity.signer.uniffi.ModulePcztSummary
import io.parity.signer.uniffi.encodeToQr
import io.parity.signer.uniffi.moduleResponseToUr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import kotlin.time.Duration.Companion.milliseconds

// PCZT signing via the wasm protocol module (docs/update-architecture.md):
// scan dispatcher hands us the raw [0x53][0x04][0x03|0x04] payload, the
// module summarizes it for the confirm screen, and after user approval the
// module signs and we show the response envelope as an animated UR QR.

enum class ModulePcztState {
	SUMMARIZING,
	REVIEW,
	SIGNING,
	DISPLAY_RESPONSE,
	MODULE_ERROR,
}

// V6-friendly failure: a payload this module version cannot parse must never
// crash the signer - it renders this message instead (the module ships as an
// updatable asset precisely so new tx formats only need a module update).
internal const val MODULE_CANNOT_PARSE_MESSAGE =
	"protocol module cannot parse this request - update your zigner module"

@Composable
fun ModulePcztScreen(
	payloadHex: String,
	getSeedPhrase: suspend () -> String,
	getSeedName: suspend () -> String,
	onDone: Callback,
	modifier: Modifier = Modifier,
) {
	// Approval screen: reject touches while an overlay is up.
	TapjackGuard()

	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	LaunchedEffect(Unit) { QrPlaybackSpeed.init(context.applicationContext) }

	var state by remember { mutableStateOf(ModulePcztState.SUMMARIZING) }
	var errorDetail by remember { mutableStateOf("") }
	var summaries by remember { mutableStateOf<List<ModulePcztSummary>>(emptyList()) }
	var responseQrFrames by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
	// True when the module answered with the compact signatures-only envelope
	// (tx_type 0x07/0x08) - the ~20x QR return shrink for compact requests.
	var compactResponse by remember { mutableStateOf(false) }
	var currentFrameIdx by remember { mutableStateOf(0) }
	// Captured at summarize time so the on-screen identity matches the seed
	// that will sign (see ZcashPcztScreen for rationale).
	var signingSeedName by remember { mutableStateOf("") }
	val accountIndex: UInt = 0u
	// v1: module #0 signs mainnet PCZTs; the payload prelude carries no
	// network byte, testnet arrives with a later module slot.
	val mainnet = true

	val payload = remember(payloadHex) {
		runCatching { payloadHex.decodeHex() }.getOrDefault(ByteArray(0))
	}

	LaunchedEffect(payloadHex) {
		try {
			if (payload.isEmpty()) error("empty payload")
			val result = withContext(Dispatchers.Default) {
				ProtocolModule.summarize(context, payload)
			}
			if (result.isEmpty()) error("module returned no messages")
			summaries = result
			signingSeedName = getSeedName()
			state = ModulePcztState.REVIEW
		} catch (e: Exception) {
			// Any module/runtime failure fails closed into the update hint.
			Timber.e(e, "protocol module summarize failed")
			errorDetail = e.message ?: ""
			state = ModulePcztState.MODULE_ERROR
		}
	}

	// Playback speed from the shared QR-speed preference (see QrPlaybackSpeed).
	LaunchedEffect(responseQrFrames) {
		if (responseQrFrames.isEmpty()) return@LaunchedEffect
		var idx = 0
		while (true) {
			currentFrameIdx = idx
			delay(QrPlaybackSpeed.delayMs.value.milliseconds)
			idx = (idx + 1) % responseQrFrames.size
		}
	}

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.padding(16.dp)
	) {
		Text(
			text = "Zcash Transaction",
			style = SignerTypeface.TitleL,
			color = MaterialTheme.colors.primary,
			modifier = Modifier.padding(bottom = 16.dp)
		)

		when (state) {
			ModulePcztState.SUMMARIZING -> ModuleCenteredSpinner("Parsing request...")

			ModulePcztState.REVIEW -> {
				Text(
					text = "Signing as: ${signingSeedName.ifEmpty { "(no seed available)" }} · account $accountIndex",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textTertiary,
					modifier = Modifier.padding(bottom = 8.dp),
				)

				Column(
					modifier = Modifier
						.weight(1f)
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					summaries.forEachIndexed { i, summary ->
						ModulePcztSummaryCard(
							summary = summary,
							index = i,
							total = summaries.size,
						)
					}
				}

				SignerDivider()
				Spacer(modifier = Modifier.height(8.dp))
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					PrimaryButtonWide(
						label = "Approve & Sign",
						onClicked = {
							state = ModulePcztState.SIGNING
							scope.launch {
								try {
									val seedPhrase = getSeedPhrase()
									if (seedPhrase.isEmpty()) {
										errorDetail = "no seed phrase available"
										state = ModulePcztState.MODULE_ERROR
										return@launch
									}
									// Compact requests (tx_type 0x05/0x06) come back as the
									// signatures-only envelope (0x07/0x08) - the Ironwood
									// QR shrink. Detect it on the main thread for the UI flag.
									var isCompact: Boolean
									val urParts = withContext(Dispatchers.Default) {
										val response = ProtocolModule.sign(
											context = context,
											payload = payload,
											seedPhrase = seedPhrase,
											account = accountIndex,
											mainnet = mainnet,
										)
										isCompact = response.size > 3 &&
											(response[2] == 0x07.toByte() || response[2] == 0x08.toByte())
										// 200-byte fragments keep frames webcam-readable
										// (same bound as the signed-PCZT UR path).
										moduleResponseToUr(
											response.map { it.toUByte() },
											ProtocolModule.activeVersion(context),
											200u,
										)
									}
									compactResponse = isCompact
									// Pre-render bitmaps so the ticker only swaps images.
									val bitmaps = withContext(Dispatchers.Default) {
										urParts.map { urString ->
											val bytes = urString
												.toByteArray(Charsets.UTF_8)
												.map { it.toUByte() }
											val png = encodeToQr(bytes, false)
												.map { it.toByte() }
												.toByteArray()
											BitmapFactory.decodeByteArray(png, 0, png.size)
												.asImageBitmap()
										}
									}
									responseQrFrames = bitmaps
									state = ModulePcztState.DISPLAY_RESPONSE
								} catch (e: Exception) {
									Timber.e(e, "protocol module sign failed")
									errorDetail = e.message ?: ""
									state = ModulePcztState.MODULE_ERROR
								}
							}
						},
					)
					SecondaryButtonWide(label = "Reject", onClicked = onDone)
				}
			}

			ModulePcztState.SIGNING -> ModuleCenteredSpinner("Signing...")

			ModulePcztState.DISPLAY_RESPONSE -> {
				Text(
					text = "Show this QR to your companion wallet",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textTertiary,
					modifier = Modifier.padding(bottom = 8.dp),
				)
				Box(
					modifier = Modifier.weight(1f).fillMaxWidth(),
					contentAlignment = Alignment.Center,
				) {
					responseQrFrames.getOrNull(currentFrameIdx)?.let { bitmap ->
						Box(
							modifier = Modifier
								.fillMaxWidth()
								.aspectRatio(1f)
								.clip(RectangleShape)
								.background(Color.White),
							contentAlignment = Alignment.Center,
						) {
							Image(
								bitmap = bitmap,
								contentDescription = "Signed response QR frame",
								contentScale = ContentScale.Fit,
								modifier = Modifier.fillMaxSize(),
							)
						}
					}
				}
				if (responseQrFrames.size > 1) {
					Text(
						text = "frame ${currentFrameIdx + 1} / ${responseQrFrames.size}",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
						modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
					)
					QrPlaybackSpeedSlider(modifier = Modifier.align(Alignment.CenterHorizontally))
				}
				SignerDivider()
				Spacer(modifier = Modifier.height(16.dp))
				PrimaryButtonWide(label = "Done", onClicked = onDone)
			}

			ModulePcztState.MODULE_ERROR -> {
				Box(
					modifier = Modifier.weight(1f).fillMaxWidth(),
					contentAlignment = Alignment.Center,
				) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text(
							text = "Cannot process request",
							style = SignerTypeface.TitleS,
							color = MaterialTheme.colors.red500,
						)
						Spacer(modifier = Modifier.height(8.dp))
						Text(
							text = MODULE_CANNOT_PARSE_MESSAGE,
							style = SignerTypeface.BodyL,
							color = MaterialTheme.colors.textSecondary,
						)
						if (errorDetail.isNotEmpty()) {
							Spacer(modifier = Modifier.height(8.dp))
							Text(
								text = errorDetail,
								style = SignerTypeface.CaptionM.copy(
									fontFamily = FontFamily.Monospace,
									fontSize = 11.sp,
								),
								color = MaterialTheme.colors.textTertiary,
							)
						}
					}
				}
				SignerDivider()
				Spacer(modifier = Modifier.height(16.dp))
				SecondaryButtonWide(label = "Dismiss", onClicked = onDone)
			}
		}
	}
}

@Composable
private fun ColumnScope.ModuleCenteredSpinner(label: String) {
	Box(
		modifier = Modifier.weight(1f).fillMaxWidth(),
		contentAlignment = Alignment.Center,
	) {
		Column(horizontalAlignment = Alignment.CenterHorizontally) {
			CircularProgressIndicator(
				color = MaterialTheme.colors.pink500,
				modifier = Modifier.size(48.dp),
			)
			Spacer(modifier = Modifier.height(16.dp))
			Text(label, style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
		}
	}
}

// One card per PCZT message: action/input counts plus the module's
// preformatted "label=zatoshi" output lines. The screen renders what the
// module reported - it never re-derives amounts from wallet-supplied data.
@Composable
private fun ModulePcztSummaryCard(
	summary: ModulePcztSummary,
	index: Int,
	total: Int,
) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RectangleShape)
			.background(MaterialTheme.colors.fill6)
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
	) {
		if (total > 1) {
			Text(
				text = "Message ${index + 1} of $total",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.primary,
			)
		}
		Text(
			text = "Orchard actions: ${summary.orchardActions}",
			style = SignerTypeface.CaptionM,
			color = MaterialTheme.colors.textSecondary,
		)
		// Ironwood (NU6.3 / V6) actions. Nonzero => this is a turnstile
		// migration and the ironwood destination below is real; the count
		// is shown prominently so it can never be signed invisibly. Zero
		// means the active module is ironwood-blind (default module) OR the
		// tx has no ironwood outputs - either way there is nothing to hide.
		if (summary.ironwoodActions > 0u) {
			Text(
				text = "Ironwood migration - actions: ${summary.ironwoodActions}",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.pink500,
			)
		} else {
			Text(
				text = "Ironwood actions: 0",
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textTertiary,
			)
		}
		Text(
			text = "Transparent inputs: ${summary.transparentInputs}",
			style = SignerTypeface.CaptionM,
			color = MaterialTheme.colors.textSecondary,
		)
		// Canonical network fee straight from the PCZT (already accounts for
		// the ironwood value balance). "unknown" when the module could not
		// derive it - never an understated number re-derived by the app.
		Text(
			text = summary.feeZat?.let { "Fee: %.8f ZEC".format(it.toLong() / 100_000_000.0) }
				?: "Fee: unknown",
			style = SignerTypeface.CaptionM,
			color = MaterialTheme.colors.textSecondary,
		)
		if (summary.outputLines.isNotEmpty()) {
			Text(
				text = "Outputs",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.textTertiary,
				modifier = Modifier.padding(top = 4.dp),
			)
			summary.outputLines.forEach { line ->
				ModuleOutputLine(line)
			}
		}
	}
}

// Lines arrive as "label=zatoshi" (label is "orchard:<raw-addr-hex>",
// "t-script:<hex>", "change:<hex>", "thorchain:<words>|<memo>",
// "memo:<text>", "op-return:<hex>" or "shielded"). Show the ZEC value
// prominently and the label monospaced underneath for cross-checking against
// the coordinator. A memo carries no value: its words are what matter.
@Composable
private fun ModuleOutputLine(line: String) {
	val eq = line.lastIndexOf('=')
	val label = if (eq > 0) line.substring(0, eq) else line
	val zats = if (eq > 0) line.substring(eq + 1).toLongOrNull() else null
	val memo = memoOf(label)
	if (memo != null) {
		ModuleMemoLine(memo, zats)
		return
	}
	// Ironwood destination lines ("ironwood:<hex>=<zat>") are the turnstile
	// migration target - tag them so the user sees the pool they are moving
	// to, not just an address+amount.
	val isIronwood = label.startsWith("ironwood:")
	val isChange = label.startsWith("change:")
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RectangleShape)
			.background(MaterialTheme.colors.fill6)
			.padding(8.dp),
	) {
		if (isIronwood) {
			Text(
				text = "-> Ironwood pool",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.pink500,
			)
		}
		if (isChange) {
			Text(
				text = "Change, back to the address these coins came from",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.textTertiary,
			)
		}
		Text(
			text = if (zats != null) "%.8f ZEC".format(zats / 100_000_000.0) else line,
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.primary,
		)
		if (zats != null) {
			Text(
				text = label,
				style = SignerTypeface.CaptionM.copy(
					fontFamily = FontFamily.Monospace,
					fontSize = 11.sp,
				),
				color = MaterialTheme.colors.textSecondary,
			)
		}
	}
}

private class OutputMemo(val title: String, val words: String?, val raw: String)

private fun memoOf(label: String): OutputMemo? = when {
	label.startsWith("thorchain:") -> {
		val body = label.removePrefix("thorchain:")
		val bar = body.indexOf('|')
		if (bar < 0) OutputMemo("Memo", null, body)
		else OutputMemo("THORChain instruction", body.substring(0, bar), body.substring(bar + 1))
	}
	label.startsWith("memo:") -> OutputMemo("Memo", null, label.removePrefix("memo:"))
	label.startsWith("op-return:") -> OutputMemo("Data, not readable as text", null, label.removePrefix("op-return:"))
	else -> null
}

// The instruction a THORChain deposit carries decides where the money ends
// up, so its words come first and the raw memo stays underneath to compare
// with the wallet's screen.
@Composable
private fun ModuleMemoLine(memo: OutputMemo, zats: Long?) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RectangleShape)
			.background(MaterialTheme.colors.fill6)
			.padding(8.dp),
	) {
		Text(
			text = memo.title,
			style = SignerTypeface.LabelM,
			color = MaterialTheme.colors.pink500,
		)
		// value on a data output can never be spent again: say so loudly
		if (zats == null || zats != 0L) {
			Text(
				text = zats?.let { "%.8f ZEC burned with this data".format(it / 100_000_000.0) }
					?: "amount unreadable",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.red500,
			)
		}
		if (memo.words != null) {
			Text(
				text = memo.words,
				style = SignerTypeface.BodyM,
				color = MaterialTheme.colors.primary,
			)
		}
		Text(
			text = memo.raw,
			style = SignerTypeface.CaptionM.copy(
				fontFamily = FontFamily.Monospace,
				fontSize = 11.sp,
			),
			color = MaterialTheme.colors.textSecondary,
		)
	}
}
