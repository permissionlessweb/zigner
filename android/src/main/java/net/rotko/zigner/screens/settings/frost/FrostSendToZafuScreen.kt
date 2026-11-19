package net.rotko.zigner.screens.settings.frost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.frostListWallets
import io.parity.signer.uniffi.frostLoadWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.qrcode.AnimatedQrKeysInfo
import net.rotko.zigner.components.qrcode.EmptyQrCodeProvider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.DisableScreenshots
import net.rotko.zigner.screens.scan.transaction.frost.toAnimatedQrFrames
import net.rotko.zigner.ui.theme.*
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** Animated P-frame QR carrying multisig wallet metadata for zafu to
 *  re-add as airgap. No secret material; no encryption.
 *  walletIdFilter == null → every multisig wallet on this device.
 *  walletIdFilter == "abc123" → just that wallet (single-wallet send). */
@Composable
fun FrostSendToZafuScreen(
	onBack: Callback,
	walletIdFilter: String? = null,
) {
	// QR contains the wallet's UFVK; keep it out of screenshots and recents.
	DisableScreenshots()
	val scope = rememberCoroutineScope()
	var qrJson by remember { mutableStateOf<String?>(null) }
	var walletCount by remember { mutableStateOf(0) }
	var skipped by remember { mutableStateOf(0) }
	var error by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(walletIdFilter) {
		scope.launch {
			runCatching {
				val all = withContext(Dispatchers.Default) { frostListWallets() }
				val wallets = if (walletIdFilter != null)
					all.filter { it.walletId == walletIdFilter }
				else all
				val arr = JSONArray()
				var skip = 0
				for (w in wallets) {
					val raw = withContext(Dispatchers.Default) { frostLoadWallet(w.walletId) }
					val obj = JSONObject(raw)
					val pkg = obj.optString("public_key_package", "")
					val fvk = obj.optString("orchard_fvk_uview", "")
					val addr = obj.optString("address", "")
					val relay = obj.optString("relay_url", "")
					if (pkg.isEmpty() || fvk.isEmpty() || addr.isEmpty()) {
						Timber.w("[frost-send-zafu] skipping wallet ${w.walletId}: missing metadata")
						skip++
						continue
					}
					arr.put(JSONObject().apply {
						put("label", w.label)
						// wallet_id is deterministic from publicKeyPackage (FNV hash on zigner),
						// so zafu can persist this to multisig.zignerWalletId for display
						// + O(1) lookup at sign time.
						put("walletId", w.walletId)
						put("publicKeyPackage", pkg)
						put("threshold", w.minSigners.toInt())
						put("maxSigners", w.maxSigners.toInt())
						put("mainnet", w.mainnet)
						put("orchardFvk", fvk)
						put("address", addr)
						put("relayUrl", relay)
					})
				}
				JSONObject().apply {
					put("frost", "airgap-import")
					put("version", 1)
					put("wallets", arr)
				}.toString() to (arr.length() to skip)
			}.onSuccess { (json, counts) ->
				qrJson = json
				walletCount = counts.first
				skipped = counts.second
			}.onFailure {
				Timber.e(it, "[frost-send-zafu] build failed")
				error = it.message ?: "Failed to build payload"
			}
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.statusBarsPadding(),
	) {
		ScreenHeaderClose(title = "Send to Zafu", onClose = onBack)

		Column(
			modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			when {
				error != null -> Text(
					error!!,
					style = SignerTypeface.BodyL,
					color = MaterialTheme.colors.red500,
				)
				qrJson == null -> Text(
					"Loading…",
					style = SignerTypeface.BodyL,
					color = MaterialTheme.colors.textSecondary,
				)
				walletCount == 0 -> {
					Text(
						"No multisig wallets with full metadata.",
						style = SignerTypeface.TitleS,
						color = MaterialTheme.colors.primary,
					)
					if (skipped > 0) {
						Spacer(modifier = Modifier.height(8.dp))
						Text(
							"$skipped wallet${if (skipped == 1) "" else "s"} skipped — created on an older zigner without metadata. Re-create via DKG to enable airgap export.",
							style = SignerTypeface.CaptionM,
							color = MaterialTheme.colors.textTertiary,
						)
					}
				}
				else -> {
					Text(
						"$walletCount multisig wallet${if (walletCount == 1) "" else "s"}",
						style = SignerTypeface.TitleS,
						color = MaterialTheme.colors.primary,
					)
					Spacer(modifier = Modifier.height(4.dp))
					Text(
						"Show this animated QR to Zafu. Public metadata only — no secret material.",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
					)
					if (skipped > 0) {
						Spacer(modifier = Modifier.height(4.dp))
						Text(
							"$skipped wallet${if (skipped == 1) "" else "s"} skipped (missing metadata).",
							style = SignerTypeface.CaptionM,
							color = MaterialTheme.colors.red400,
						)
					}
				}
			}
		}

		qrJson?.takeIf { walletCount > 0 }?.let { json ->
			val frames = remember(json) { toAnimatedQrFrames(json, "zafu-airgap-import") }
			Box(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentAlignment = Alignment.Center,
			) {
				if (frames == null) {
					Text(
						"Failed to prepare QR payload",
						style = SignerTypeface.BodyL,
						color = MaterialTheme.colors.red500,
					)
				} else {
					AnimatedQrKeysInfo<List<List<UByte>>>(
						input = frames,
						provider = EmptyQrCodeProvider(),
						modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
					)
				}
			}
			if (frames != null && frames.size > 1) {
				Text(
					text = "${frames.size} frames cycling — let zafu scan one full cycle",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary,
					modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
				)
			}
		}
	}
}
