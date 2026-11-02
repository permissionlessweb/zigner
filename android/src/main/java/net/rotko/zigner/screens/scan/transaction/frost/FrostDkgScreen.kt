package net.rotko.zigner.screens.scan.transaction.frost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.frostDeriveMetadata
import io.parity.signer.uniffi.frostDkgPart1
import io.parity.signer.uniffi.frostDkgPart2
import io.parity.signer.uniffi.frostDkgPart3
import io.parity.signer.uniffi.frostStoreWallet
import net.rotko.zigner.domain.frost.FrostSeeds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.qrcode.AnimatedQrKeysInfo
import net.rotko.zigner.components.qrcode.EmptyQrCodeProvider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*
import timber.log.Timber

/** FROST DKG one round at a time. r1 broadcast is hex; r2 peer-packages and r3 ack are JSON. */
enum class DkgState { COMPUTING, DISPLAY_QR, ERROR }

@Composable
fun FrostDkgScreen(
	round: Int,
	maxSigners: UShort,
	minSigners: UShort,
	label: String,
	mainnet: Boolean,
	broadcastsJson: String = "[]",
	round1BroadcastsJson: String = "[]",
	round2PackagesJson: String = "[]",
	previousSecret: String = "",
	// host-broadcast random `sk` + relay url; carried in the dkg3 trigger so
	// zigner derives UFVK/address itself and bakes them into the r3 ack.
	skHex: String = "",
	relayUrl: String = "",
	onSecretUpdated: (String) -> Unit = {},
	onScanNext: Callback,
	onDone: Callback,
	modifier: Modifier = Modifier,
) {
	val scope = rememberCoroutineScope()
	var state by remember { mutableStateOf(DkgState.COMPUTING) }
	var errorMsg by remember { mutableStateOf("") }
	var qrData by remember { mutableStateOf("") }
	var walletId by remember { mutableStateOf("") }

	LaunchedEffect(Unit) {
		Timber.d("[FROST] FrostDkgScreen round=$round t=$minSigners n=$maxSigners label='$label' mainnet=$mainnet")
		scope.launch {
			try {
				when (round) {
					1 -> {
						val result = withContext(Dispatchers.Default) { frostDkgPart1(maxSigners, minSigners) }
						val json = org.json.JSONObject(result)
						onSecretUpdated(json.getString("secret"))
						qrData = json.getString("broadcast")
						state = DkgState.DISPLAY_QR
					}
					2 -> {
						val result = withContext(Dispatchers.Default) { frostDkgPart2(previousSecret, broadcastsJson) }
						val json = org.json.JSONObject(result)
						onSecretUpdated(json.getString("secret"))
						qrData = json.getJSONArray("peer_packages").toString()
						state = DkgState.DISPLAY_QR
					}
					3 -> {
						val result = withContext(Dispatchers.Default) {
							frostDkgPart3(previousSecret, round1BroadcastsJson, round2PackagesJson)
						}
						val json = org.json.JSONObject(result)
						val pkg = json.getString("public_key_package")

						// derive UFVK + unified address from pkg + sk so this wallet record
						// has everything zafu needs to re-add as airgap multisig later.
						// sk is host-broadcast and identical for every participant; derivation
						// is deterministic so values match what zafu computes during FVK echo.
						var ufvk = ""
						var addr = ""
						if (skHex.isNotEmpty()) {
							runCatching {
								val meta = withContext(Dispatchers.Default) {
									frostDeriveMetadata(pkg, skHex, mainnet, 0u)
								}
								val mjson = org.json.JSONObject(meta)
								ufvk = mjson.optString("orchard_fvk_uview", "")
								addr = mjson.optString("address", "")
							}.onFailure {
								Timber.e(it, "[FROST] dkg3 derive_metadata failed — wallet stored without UFVK/address")
							}
						}

						// The share is sealed under a seed, so storing it asks for
						// the unlock. Cancelling leaves no share on this device.
						val seeds = FrostSeeds.unlockAll()
							?: throw IllegalStateException("Unlock cancelled - this device's FROST share was not stored")
						val wid = withContext(Dispatchers.Default) {
							frostStoreWallet(
								json.getString("key_package"), pkg, json.getString("ephemeral_seed"),
								label, minSigners, maxSigners, mainnet,
								ufvk, addr, relayUrl, seeds,
							)
						}
						walletId = wid
						onSecretUpdated("")
						// r3 ack now carries the metadata zafu would otherwise compute itself,
						// so zafu can persist the wallet immediately without a r4-meta round-trip.
						qrData = org.json.JSONObject().apply {
							put("frost", "r3")
							put("public_key_package", pkg)
							put("wallet_id", wid)
							if (ufvk.isNotEmpty()) put("orchard_fvk_uview", ufvk)
							if (addr.isNotEmpty()) put("address", addr)
							if (relayUrl.isNotEmpty()) put("relay_url", relayUrl)
						}.toString()
						state = DkgState.DISPLAY_QR
					}
				}
			} catch (e: Exception) {
				Timber.e(e, "[FROST] DKG round $round failed")
				errorMsg = e.message ?: "DKG round $round failed"
				state = DkgState.ERROR
			}
		}
	}

	Column(
		modifier = modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(16.dp)
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = "FROST DKG — Round $round of 3",
				style = SignerTypeface.TitleL,
				color = MaterialTheme.colors.primary,
				modifier = Modifier.weight(1f),
			)
			if (state != DkgState.ERROR) {
				DontQuitIcon(message = "don't leave this screen — quitting cancels DKG")
			}
		}
		Text(
			text = "${minSigners}-of-${maxSigners} multisig",
			style = SignerTypeface.BodyL,
			color = MaterialTheme.colors.textSecondary,
			modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
		)

		when (state) {
			DkgState.COMPUTING -> Box(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentAlignment = Alignment.Center,
			) {
				Column(horizontalAlignment = Alignment.CenterHorizontally) {
					CircularProgressIndicator(color = MaterialTheme.colors.pink500, modifier = Modifier.size(48.dp))
					Spacer(modifier = Modifier.height(16.dp))
					Text("Computing round $round...", style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
				}
			}

			DkgState.DISPLAY_QR -> {
				Text(
					text = if (round == 3) "Key share stored — show this to zafu"
					       else "Show this to the coordinator",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textTertiary,
					modifier = Modifier.padding(bottom = 8.dp),
				)
				val frames = remember(qrData) {
					// r1 broadcast is hex bytes; r2/r3 are JSON utf-8.
					val raw = hexToBytesOrNull(qrData) ?: qrData.toByteArray(Charsets.UTF_8)
					toAnimatedQrFrames(raw, "zafu-frost-dkg")
				}
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					if (frames == null) Text(
						"failed to prepare QR payload",
						style = SignerTypeface.BodyL,
						color = MaterialTheme.colors.red500,
					) else AnimatedQrKeysInfo<List<List<UByte>>>(
						input = frames,
						provider = EmptyQrCodeProvider(),
						modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
					)
				}
				if (frames != null && frames.size > 1) {
					Spacer(modifier = Modifier.height(4.dp))
					Text(
						text = "${frames.size} frames cycling — let zafu scan one full cycle",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
					)
				}
				SignerDivider()
				Spacer(modifier = Modifier.height(8.dp))
				if (round < 3) {
					Text(
						text = "After the coordinator processes this, scan the round ${round + 1} QR",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
						modifier = Modifier.padding(bottom = 8.dp),
					)
					PrimaryButtonWide(label = "Scan Round ${round + 1}", onClicked = onScanNext)
				} else {
					Text(
						text = "Wallet $walletId saved. Tap Done after zafu confirms it scanned the QR.",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
						modifier = Modifier.padding(bottom = 8.dp),
					)
					PrimaryButtonWide(label = "Done", onClicked = onDone)
				}
			}

			DkgState.ERROR -> {
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text("DKG Failed", style = SignerTypeface.TitleS, color = MaterialTheme.colors.red500)
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
