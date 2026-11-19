package net.rotko.zigner.screens.scan.transaction

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.rotko.zigner.components.security.TapjackGuard
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.qrcode.AnimatedQrKeysInfo
import net.rotko.zigner.components.qrcode.EmptyQrCodeProvider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*
import io.parity.signer.uniffi.authSignChallenge
import io.parity.signer.uniffi.authDeriveIdentity
import io.parity.signer.uniffi.encodeToQr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Auth challenge screen — scan {"auth":"challenge",...} QR, review domain,
 * sign with identity, display response QR.
 *
 * The user sees the domain prominently and must approve before signing.
 */

enum class AuthState {
	REVIEW,
	SIGNING,
	DISPLAY_RESPONSE,
	ERROR,
}

@Composable
fun AuthChallengeScreen(
	domain: String,
	nonce: String,
	timestamp: ULong,
	seedPhrase: String,
	identityIndex: UInt = 0u,
	onDone: Callback,
	modifier: Modifier = Modifier,
) {
	// Approval screen: reject touches while an overlay is up.
	TapjackGuard()

	val scope = rememberCoroutineScope()
	var state by remember { mutableStateOf(AuthState.REVIEW) }
	var errorMsg by remember { mutableStateOf("") }
	var responseJson by remember { mutableStateOf("") }
	var pubkeyHex by remember { mutableStateOf("") }

	// Pre-derive identity to show the user their pubkey
	LaunchedEffect(Unit) {
		try {
			pubkeyHex = withContext(Dispatchers.Default) {
				authDeriveIdentity(seedPhrase, identityIndex)
			}
		} catch (_: Exception) {}
	}

	// Check timestamp freshness (warn if > 5 minutes old)
	val now = System.currentTimeMillis() / 1000
	val age = now - timestamp.toLong()
	val isStale = age > 300 // 5 minutes

	Column(
		modifier = modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.padding(16.dp)
	) {
		Text(
			text = "Sign in",
			style = SignerTypeface.TitleL,
			color = MaterialTheme.colors.primary,
			modifier = Modifier.padding(bottom = 16.dp)
		)

		when (state) {
			AuthState.REVIEW -> {
				Column(
					modifier = Modifier.weight(1f),
					verticalArrangement = Arrangement.spacedBy(12.dp)
				) {
					// Domain — big and prominent
					Column(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RectangleShape)
							.background(MaterialTheme.colors.fill6)
							.padding(24.dp),
						horizontalAlignment = Alignment.CenterHorizontally,
						verticalArrangement = Arrangement.spacedBy(8.dp)
					) {
						Text(
							text = "Authenticate to",
							style = SignerTypeface.LabelM,
							color = MaterialTheme.colors.textTertiary
						)
						Text(
							text = domain,
							style = SignerTypeface.TitleL,
							color = MaterialTheme.colors.primary,
							textAlign = TextAlign.Center
						)
					}

					// Identity
					if (pubkeyHex.isNotEmpty()) {
						Column(
							modifier = Modifier
								.fillMaxWidth()
								.clip(RectangleShape)
								.background(MaterialTheme.colors.fill6)
								.padding(12.dp),
							verticalArrangement = Arrangement.spacedBy(4.dp)
						) {
							Text(
								text = "Identity #$identityIndex",
								style = SignerTypeface.LabelM,
								color = MaterialTheme.colors.textTertiary
							)
							Text(
								text = "zid${pubkeyHex.take(16)}",
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.textSecondary
							)
						}
					}

					// Stale warning
					if (isStale) {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clip(RectangleShape)
								.background(MaterialTheme.colors.red500fill12)
								.padding(12.dp),
							horizontalArrangement = Arrangement.spacedBy(8.dp)
						) {
							Text(text = "\u26A0", style = SignerTypeface.TitleS)
							Text(
								text = "Challenge is ${age / 60} minutes old. It may have expired.",
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.primary
							)
						}
					}
				}

				SignerDivider()
				Spacer(modifier = Modifier.height(8.dp))
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					PrimaryButtonWide(
						label = "Approve",
						onClicked = {
							state = AuthState.SIGNING
							scope.launch {
								try {
									val result = withContext(Dispatchers.Default) {
										authSignChallenge(seedPhrase, identityIndex, domain, nonce, timestamp)
									}
									responseJson = org.json.JSONObject().apply {
										put("auth", "response")
										put("pubkey", result.pubkeyHex)
										put("sig", result.signatureHex)
										put("domain", result.domain)
									}.toString()
									state = AuthState.DISPLAY_RESPONSE
								} catch (e: Exception) {
									errorMsg = e.message ?: "Signing failed"
									state = AuthState.ERROR
								}
							}
						}
					)
					SecondaryButtonWide(label = "Decline", onClicked = onDone)
				}
			}

			AuthState.SIGNING -> {
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						CircularProgressIndicator(color = MaterialTheme.colors.pink500, modifier = Modifier.size(48.dp))
						Spacer(modifier = Modifier.height(16.dp))
						Text("Signing...", style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
					}
				}
			}

			AuthState.DISPLAY_RESPONSE -> {
				Text(
					text = "Show this to $domain",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textTertiary,
					modifier = Modifier.padding(bottom = 8.dp)
				)
				val qrBytes = remember(responseJson) {
					try {
						val bytes = responseJson.toByteArray(Charsets.UTF_8).map { it.toUByte() }
						kotlinx.coroutines.runBlocking { encodeToQr(bytes, false) }
					} catch (_: Exception) { null }
				}
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					if (qrBytes != null) {
						AnimatedQrKeysInfo<List<List<UByte>>>(
							input = listOf(qrBytes),
							provider = EmptyQrCodeProvider(),
							modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
						)
					}
				}
				SignerDivider()
				Spacer(modifier = Modifier.height(16.dp))
				PrimaryButtonWide(label = "Done", onClicked = onDone)
			}

			AuthState.ERROR -> {
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text("Error", style = SignerTypeface.TitleS, color = MaterialTheme.colors.red500)
						Spacer(modifier = Modifier.height(8.dp))
						Text(errorMsg, style = SignerTypeface.BodyL, color = MaterialTheme.colors.textSecondary)
					}
				}
				SignerDivider()
				Spacer(modifier = Modifier.height(16.dp))
				SecondaryButtonWide(label = "Dismiss", onClicked = onDone)
			}
		}
	}
}
