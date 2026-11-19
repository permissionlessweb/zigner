package net.rotko.zigner.screens.scan.transaction

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.parity.signer.uniffi.ReleaseSigningRequest
import io.parity.signer.uniffi.encodeToQr
import io.parity.signer.uniffi.releaseSignRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.security.TapjackGuard
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.qrcode.AnimatedQrKeysInfo
import net.rotko.zigner.components.qrcode.EmptyQrCodeProvider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*

// Release signing: this device as one of the 2-of-3 module release keys.
//
// The operator is attesting "module version N, with THIS hash, does what this
// changelog says". The device cannot check that claim - it has no way to know
// what the wasm does - so the whole ceremony rests on the operator having
// built the module themselves and comparing hashes. The screen is built around
// making that comparison possible rather than around looking reassuring.
//
// The signed message is constructed on-device as "zigner-module-v1" || prefix.
// The host sends only the manifest, never a pre-domained blob, so a compromised
// coordinator cannot obtain a signature over bytes this device never parsed.

enum class ReleaseSignState {
	REVIEW,
	SIGNING,
	DISPLAY_SIGNATURE,
	ERROR,
}

@Composable
fun ReleaseSignScreen(
	request: ReleaseSigningRequest,
	prefixBytes: ByteArray,
	seedPhrase: String,
	onDone: Callback,
	modifier: Modifier = Modifier,
) {
	// Approval screen: reject touches while an overlay is up.
	TapjackGuard()

	val scope = rememberCoroutineScope()
	var state by remember { mutableStateOf(ReleaseSignState.REVIEW) }
	var errorMsg by remember { mutableStateOf("") }
	var signatureHex by remember { mutableStateOf("") }

	Column(
		modifier = modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 24.dp)
			.padding(top = 32.dp, bottom = 24.dp),
	) {
		when (state) {
			ReleaseSignState.REVIEW, ReleaseSignState.SIGNING -> {
				Text(
					text = "Sign a module release",
					color = MaterialTheme.colors.primary,
					style = SignerTypeface.TitleL,
				)
				Spacer(Modifier.padding(top = 8.dp))
				Text(
					text = "You are attesting that this module is the build you " +
						"verified. This device cannot check that for you.",
					color = MaterialTheme.colors.textSecondary,
					style = SignerTypeface.BodyM,
				)

				Spacer(Modifier.padding(top = 24.dp))
				ReleaseRow("Module version", request.moduleVersion.toString())
				SignerDivider()
				ReleaseRow("Minimum app version", request.minKernelVersion.toString())

				Spacer(Modifier.padding(top = 24.dp))
				Text(
					text = "Module hash",
					color = MaterialTheme.colors.textTertiary,
					style = SignerTypeface.BodyM,
				)
				Spacer(Modifier.padding(top = 4.dp))
				Text(
					text = "Compare against sha256 of the module you built. " +
						"Not against what the coordinator shows you.",
					color = MaterialTheme.colors.textTertiary,
					style = SignerTypeface.CaptionM,
				)
				Spacer(Modifier.padding(top = 8.dp))
				// Full digest, grouped. The confirm-screen fingerprint is a
				// shortened form for a glance check; a signer is committing a
				// key and should see every byte they are attesting to.
				Text(
					text = request.moduleHashHex.chunked(8).joinToString(" ").uppercase(),
					color = MaterialTheme.colors.primary,
					fontFamily = FontFamily.Monospace,
					fontSize = 14.sp,
					modifier = Modifier
						.fillMaxWidth()
						.clip(RectangleShape)
						.background(MaterialTheme.colors.fill6)
						.padding(16.dp),
				)

				Spacer(Modifier.padding(top = 24.dp))
				Text(
					text = "Changelog",
					color = MaterialTheme.colors.textTertiary,
					style = SignerTypeface.BodyM,
				)
				Spacer(Modifier.padding(top = 8.dp))
				Text(
					text = request.description.ifBlank {
						"(no changelog - a release we publish should never have one)"
					},
					color = MaterialTheme.colors.primary,
					style = SignerTypeface.BodyM,
					modifier = Modifier
						.fillMaxWidth()
						.clip(RectangleShape)
						.background(MaterialTheme.colors.fill6)
						.padding(16.dp),
				)

				Spacer(Modifier.padding(top = 32.dp))
				PrimaryButtonWide(
					label = if (state == ReleaseSignState.SIGNING) "Signing…" else "Sign",
					isEnabled = state == ReleaseSignState.REVIEW,
				) {
					state = ReleaseSignState.SIGNING
					scope.launch {
						try {
							signatureHex = withContext(Dispatchers.Default) {
								releaseSignRequest(
									seedPhrase,
									prefixBytes.map { it.toUByte() },
								)
							}
							state = ReleaseSignState.DISPLAY_SIGNATURE
						} catch (e: Exception) {
							errorMsg = e.message ?: "Signing failed"
							state = ReleaseSignState.ERROR
						}
					}
				}
				Spacer(Modifier.padding(top = 8.dp))
				SecondaryButtonWide(label = "Cancel", onClicked = onDone)
			}

			ReleaseSignState.DISPLAY_SIGNATURE -> {
				Text(
					text = "Signature",
					color = MaterialTheme.colors.primary,
					style = SignerTypeface.TitleL,
				)
				Spacer(Modifier.padding(top = 8.dp))
				Text(
					text = "Scan this back into the coordinator. Two of three " +
						"signatures make a release.",
					color = MaterialTheme.colors.textSecondary,
					style = SignerTypeface.BodyM,
				)

				Spacer(Modifier.padding(top = 16.dp))
				val qr = remember(signatureHex) {
					runCatching {
						// Just the signature hex - `modpack assemble --sig` takes it
						// untagged; the verifier matches it against the pinned keys.
						kotlinx.coroutines.runBlocking {
							encodeToQr(signatureHex.toByteArray(Charsets.UTF_8).map { it.toUByte() }, false)
						}
					}.getOrNull()
				}
				Box(
					modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
					contentAlignment = Alignment.Center,
				) {
					if (qr != null) {
						AnimatedQrKeysInfo<List<List<UByte>>>(
							input = listOf(qr),
							provider = EmptyQrCodeProvider(),
							modifier = Modifier.fillMaxWidth(),
						)
					} else {
						Text(
							text = signatureHex,
							color = MaterialTheme.colors.primary,
							fontFamily = FontFamily.Monospace,
							fontSize = 11.sp,
						)
					}
				}

				Spacer(Modifier.padding(top = 16.dp))
				// Also readable as text: a holder signing remotely can type or
				// dictate this rather than needing a camera pointed at them.
				Text(
					text = signatureHex,
					color = MaterialTheme.colors.textTertiary,
					fontFamily = FontFamily.Monospace,
					fontSize = 10.sp,
					modifier = Modifier
						.fillMaxWidth()
						.clip(RectangleShape)
						.background(MaterialTheme.colors.fill6)
						.padding(12.dp),
				)

				Spacer(Modifier.padding(top = 24.dp))
				PrimaryButtonWide(label = "Done", onClicked = onDone)
			}

			ReleaseSignState.ERROR -> {
				Text(
					text = "Refused",
					color = MaterialTheme.colors.red500,
					style = SignerTypeface.TitleL,
				)
				Spacer(Modifier.padding(top = 8.dp))
				Text(
					text = errorMsg,
					color = MaterialTheme.colors.primary,
					style = SignerTypeface.BodyM,
				)
				Spacer(Modifier.padding(top = 24.dp))
				PrimaryButtonWide(label = "Close", onClicked = onDone)
			}
		}
	}
}

@Composable
private fun ReleaseRow(label: String, value: String) {
	Row(
		modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(
			text = label,
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.BodyM,
		)
		Text(
			text = value,
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.BodyM,
		)
	}
}
