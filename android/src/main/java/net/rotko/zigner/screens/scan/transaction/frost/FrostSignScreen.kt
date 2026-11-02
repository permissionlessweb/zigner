package net.rotko.zigner.screens.scan.transaction.frost

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.frostListWallets
import io.parity.signer.uniffi.frostLoadWallet
import io.parity.signer.uniffi.frostLoadWalletSecrets
import net.rotko.zigner.domain.frost.FrostSeeds
import io.parity.signer.uniffi.frostSignRound1
import io.parity.signer.uniffi.frostSpendSignRound2Signed
import io.parity.signer.uniffi.frostVerifyPczt
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

/**
 *  Round 1 (sign1): match wallet by public_key_package, VERIFY the PCZT on-device
 *  (gh #17 — recompute sighash + OVK-decode outputs with our stored group UFVK so
 *  we authorize the real recipient/amount, not the host's claim), then on approval
 *  gen per-action commitments.
 *  Round 2 (sign2): consume saved nonces + bundled commitments, emit per-action shares.
 */

enum class FrostSignState { LOADING, REVIEW, DISPLAY_QR, ERROR }

/** Derived from the on-device `frostVerifyPczt`; drives the round-1 review gate. */
private data class FrostVerdict(
	/** recomputed sighash == the sighash we're being asked to sign */
	val verified: Boolean,
	/** ran the verifier and it FAILED — hard-block the signature */
	val hardMismatch: Boolean,
	/** could not verify (no PCZT, no UFVK on file, verifier error) - signing is blocked */
	val unverifiedReason: String?,
	val outputs: List<FrostOutputRow>,
	/** network fee from the PCZT's value balance; null when unverified */
	val feeZat: Long? = null,
)

private data class FrostOutputRow(val recipient: String, val amountZat: Long, val isChange: Boolean)

@Composable
fun FrostSignScreen(
	round: Int,  // 1 or 2
	publicKeyPackageHex: String = "",
	walletIdHint: String = "",  // O(1) lookup if present; falls back to pkg scan
	alphasJson: String = "[]",
	summary: String = "",
	sighashHex: String = "",
	pcztHex: String = "",  // gh #17: host-published PCZT for on-device verification
	bundledCommitmentsJson: String = "[]",
	previousNoncesPerAction: List<String> = emptyList(),
	previousKeyPackage: String = "",
	previousEphemeralSeed: String = "",
	onNoncesUpdated: (noncesPerAction: List<String>, keyPackage: String, ephemeralSeed: String) -> Unit = { _, _, _ -> },
	/** Round 1: called once the user approved and commitments exist. */
	onApproved: (FrostRound2Binding.Approved) -> Unit = {},
	/** Round 2: what round 1 approved on this device, or null if nothing was. */
	approved: FrostRound2Binding.Approved? = null,
	onScanNext: Callback,
	onDone: Callback,
	modifier: Modifier = Modifier,
) {
	val scope = rememberCoroutineScope()
	var state by remember { mutableStateOf(FrostSignState.LOADING) }
	var errorMsg by remember { mutableStateOf("") }
	var qrData by remember { mutableStateOf("") }
	var verdict by remember { mutableStateOf<FrostVerdict?>(null) }
	// The wallet reviewed in round 1. Its sealed share is opened only at
	// Approve, behind the unlock, so commitments use the wallet verified here.
	var loadedWalletId by remember { mutableStateOf("") }

	LaunchedEffect(Unit) {
		Timber.d("[FROST] FrostSignScreen round=$round pkg=…${publicKeyPackageHex.takeLast(8)}")
		scope.launch {
			try {
				when (round) {
					1 -> {
						val (walletId, wallet) = loadWalletJson(publicKeyPackageHex, walletIdHint)
						loadedWalletId = walletId
						val ufvk = wallet.optString("orchard_fvk_uview", "")
						verdict = computeFrostVerdict(pcztHex, sighashHex, ufvk)
						state = FrostSignState.REVIEW
					}
					2 -> {
						// Refuse before touching any key material if this round-2
						// request is not the transaction approved in round 1.
						FrostRound2Binding.check(approved, sighashHex, alphasJson)?.let { reason ->
							throw IllegalStateException(reason)
						}
						runRound2(
							sighashHex, alphasJson, bundledCommitmentsJson,
							previousNoncesPerAction, previousKeyPackage, previousEphemeralSeed,
							onNoncesUpdated,
						) { qr -> qrData = qr }
						state = FrostSignState.DISPLAY_QR
					}
				}
			} catch (e: Exception) {
				Timber.e(e, "[FROST] sign round $round failed")
				errorMsg = e.message ?: "FROST sign round $round failed"
				state = FrostSignState.ERROR
			}
		}
	}

	Column(
		modifier = modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(16.dp)
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = if (round == 1) "FROST Sign — Review" else "FROST Sign — Round 2 (shares)",
				style = SignerTypeface.TitleL,
				color = MaterialTheme.colors.primary,
				modifier = Modifier.weight(1f),
			)
			if (state != FrostSignState.ERROR) {
				DontQuitIcon(message = "don't leave this screen — quitting cancels signing")
			}
		}
		Spacer(modifier = Modifier.height(8.dp))

		when (state) {
			FrostSignState.LOADING -> Box(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentAlignment = Alignment.Center,
			) {
				Column(horizontalAlignment = Alignment.CenterHorizontally) {
					CircularProgressIndicator(color = MaterialTheme.colors.pink500, modifier = Modifier.size(48.dp))
					Spacer(modifier = Modifier.height(16.dp))
					Text(
						if (round == 1) "Verifying transaction..." else "Computing shares...",
						style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary,
					)
				}
			}

			FrostSignState.REVIEW -> {
				val v = verdict ?: return@Column
				Column(
					modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					VerdictBanner(v)
					// The outputs the device DERIVED from the PCZT — this is what
					// you're actually authorizing, independent of the host's claim.
					val externals = v.outputs.filter { !it.isChange }
					// Only warn "no recipient" when we ACTUALLY decoded the PCZT and
					// found none. When unverified (no PCZT at all) the banner already
					// explains it — don't imply a failed decode.
					if (externals.isEmpty() && v.unverifiedReason == null && !v.hardMismatch) {
						OutputCard(
							title = "No payment outside this group",
							body = "Every output goes back to this group's own addresses. Do not sign unless you expect a self-transfer.",
							danger = true,
						)
					}
					externals.forEach { o ->
						OutputCard(
							title = "Send ${zec(o.amountZat)} ZEC",
							body = o.recipient,
							danger = false,
						)
					}
					v.outputs.filter { it.isChange }.forEach { o ->
						OutputCard(
							title = "Change ${zec(o.amountZat)} ZEC (back to you)",
							body = o.recipient,
							danger = false,
						)
					}
					v.feeZat?.let { fee ->
						OutputCard(title = "Fee ${zec(fee)} ZEC", body = "Network fee", danger = false)
					}
					if (summary.isNotEmpty()) {
						Text(
							text = "Host claim: $summary",
							style = SignerTypeface.CaptionM,
							color = MaterialTheme.colors.textTertiary,
						)
					}
				}
				SignerDivider()
				Spacer(modifier = Modifier.height(8.dp))
				Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
					// Only a request the device itself verified can be signed. This
					// used to be `!hardMismatch`, which let every "could not verify"
					// case through - no PCZT from the host, no UFVK on file, or the
					// verifier throwing (e.g. a consensus branch the linked crates
					// do not know). The screen would then show only the
					// coordinator's claim and still offer to sign.
					if (v.verified) {
						PrimaryButtonWide(
							label = "Approve & Sign",
							onClicked = {
								state = FrostSignState.LOADING
								scope.launch {
									try {
										val seeds = FrostSeeds.unlockAll()
											?: throw IllegalStateException("Unlock cancelled")
										val secrets = org.json.JSONObject(withContext(Dispatchers.Default) {
											frostLoadWalletSecrets(loadedWalletId, seeds)
										})
										generateCommitments(
											secrets.getString("key_package"),
											secrets.getString("ephemeral_seed"),
											alphasJson, onNoncesUpdated,
										) { qr -> qrData = qr }
										// Recorded only after commitments succeed, and only
										// on this verified path (Approve needs v.verified).
										onApproved(FrostRound2Binding.approve(sighashHex, alphasJson))
										state = FrostSignState.DISPLAY_QR
									} catch (e: Exception) {
										errorMsg = e.message ?: "failed to generate commitments"
										state = FrostSignState.ERROR
									}
								}
							},
						)
					}
					SecondaryButtonWide(label = if (v.hardMismatch) "Reject" else "Decline", onClicked = onDone)
				}
			}

			FrostSignState.DISPLAY_QR -> {
				Text(
					text = if (round == 1) "Show this to zafu — round 1 commitments"
					       else "Show this to zafu — round 2 shares",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textTertiary,
					modifier = Modifier.padding(bottom = 8.dp),
				)
				val frames = remember(qrData) { toAnimatedQrFrames(qrData, "zafu-frost-sign") }
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
				if (round == 1) {
					Text(
						text = "After zafu collects peer commitments, scan the round-2 trigger QR",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
						modifier = Modifier.padding(bottom = 8.dp),
					)
					PrimaryButtonWide(label = "Scan Round 2", onClicked = onScanNext)
				} else {
					PrimaryButtonWide(label = "Done", onClicked = onDone)
				}
			}

			FrostSignState.ERROR -> {
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					Column(horizontalAlignment = Alignment.CenterHorizontally) {
						Text("Signing Failed", style = SignerTypeface.TitleS, color = MaterialTheme.colors.red500)
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

private fun zec(zat: Long): String =
	String.format("%.8f", zat / 1e8).trimEnd('0').trimEnd('.')

@Composable
private fun VerdictBanner(v: FrostVerdict) {
	val (text, color) = when {
		v.hardMismatch -> "MISMATCH — the transaction does not match the request. Do NOT sign." to MaterialTheme.colors.red500
		v.unverifiedReason != null -> "Cannot verify this request on this device (${v.unverifiedReason}). Signing is blocked." to MaterialTheme.colors.red500
		else -> "Verified on-device: every output, the fee and the sighash come from the PCZT." to MaterialTheme.colors.textSecondary
	}
	Text(
		text = text,
		style = SignerTypeface.LabelM,
		color = color,
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(8.dp))
			.background(MaterialTheme.colors.fill6)
			.padding(12.dp),
	)
}

@Composable
private fun OutputCard(title: String, body: String, danger: Boolean) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colors.fill6)
			.padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(4.dp),
	) {
		Text(
			text = title,
			style = SignerTypeface.TitleS,
			color = if (danger) MaterialTheme.colors.red500 else MaterialTheme.colors.primary,
		)
		Text(text = body, style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textSecondary)
	}
}

/** Find wallet (O(1) via walletIdHint, else O(n) public_key_package scan):
 *  its id and public fields. */
private suspend fun loadWalletJson(
	publicKeyPackageHex: String,
	walletIdHint: String,
): Pair<String, org.json.JSONObject> {
	val walletJson = if (walletIdHint.isNotEmpty()) {
		runCatching {
			val w = withContext(Dispatchers.Default) { frostLoadWallet(walletIdHint) }
			val pkg = org.json.JSONObject(w).getString("public_key_package")
			if (pkg != publicKeyPackageHex) {
				Timber.w("[FROST] walletIdHint=$walletIdHint pkg mismatch — falling back to scan")
				null
			} else {
				Timber.d("[FROST] sign1 walletIdHint hit walletId=$walletIdHint")
				walletIdHint to w
			}
		}.getOrNull()
	} else null

	val finalJson = walletJson ?: run {
		val wallets = withContext(Dispatchers.Default) { frostListWallets() }
		val match = wallets.firstOrNull { w ->
			runCatching {
				val w2 = withContext(Dispatchers.Default) { frostLoadWallet(w.walletId) }
				org.json.JSONObject(w2).getString("public_key_package") == publicKeyPackageHex
			}.getOrDefault(false)
		} ?: throw IllegalStateException(
			"no FROST wallet matches the requested public key package — was it stored on this device?"
		)
		Timber.d("[FROST] sign1 scan-matched wallet=${match.walletId}")
		match.walletId to withContext(Dispatchers.Default) { frostLoadWallet(match.walletId) }
	}
	return finalJson.first to org.json.JSONObject(finalJson.second)
}

/** gh #17: recompute sighash + OVK-decode outputs on-device via the rust verifier. */
private suspend fun computeFrostVerdict(
	pcztHex: String,
	sighashHex: String,
	orchardFvkUview: String,
): FrostVerdict {
	if (pcztHex.isEmpty()) {
		return FrostVerdict(false, false, "host did not publish a PCZT (older client?)", emptyList())
	}
	if (orchardFvkUview.isEmpty()) {
		return FrostVerdict(false, false, "no UFVK stored for this wallet", emptyList())
	}
	return try {
		val json = withContext(Dispatchers.Default) {
			frostVerifyPczt(pcztHex, sighashHex, orchardFvkUview)
		}
		val o = org.json.JSONObject(json)
		val sighashMatch = o.optBoolean("sighash_match", false)
		val arr = o.optJSONArray("outputs") ?: org.json.JSONArray()
		val outs = buildList {
			for (i in 0 until arr.length()) {
				val r = arr.getJSONObject(i)
				add(
					FrostOutputRow(
						recipient = r.optString("recipient", ""),
						amountZat = r.optLong("amount_zat", 0L),
						isChange = r.optBoolean("is_change", false),
					)
				)
			}
		}
		// Hard mismatch = we verified and the sighash did NOT match the PCZT:
		// the host wants us to sign a different tx than the one decoded. Block.
		FrostVerdict(
			verified = sighashMatch,
			hardMismatch = !sighashMatch,
			unverifiedReason = null,
			outputs = outs,
			feeZat = o.optLong("fee_zat", 0L),
		)
	} catch (e: Exception) {
		Timber.e(e, "[FROST] frostVerifyPczt failed")
		FrostVerdict(false, false, e.message ?: "verification error", emptyList())
	}
}

/** Per-action fresh commitments, emitted as the sign1-resp QR for zafu. */
private suspend fun generateCommitments(
	keyPackage: String,
	ephemeralSeed: String,
	alphasJson: String,
	onNoncesUpdated: (List<String>, String, String) -> Unit,
	emit: (qrData: String) -> Unit,
) {
	val alphas = org.json.JSONArray(alphasJson)
	val nonces = mutableListOf<String>()
	val commits = mutableListOf<String>()
	for (i in 0 until alphas.length()) {
		val r1 = withContext(Dispatchers.Default) { frostSignRound1(ephemeralSeed, keyPackage) }
		val j = org.json.JSONObject(r1)
		nonces.add(j.getString("nonces"))
		commits.add(j.getString("commitments"))
	}
	onNoncesUpdated(nonces, keyPackage, ephemeralSeed)
	emit(org.json.JSONObject().apply {
		put("frost", "sign1-resp")
		put("commitments", org.json.JSONArray(commits))
	}.toString())
}

/** Per-action: produce authenticated share (SignedMessage) for each Orchard action. */
private suspend fun runRound2(
	sighashHex: String,
	alphasJson: String,
	bundledCommitmentsJson: String,
	previousNoncesPerAction: List<String>,
	previousKeyPackage: String,
	previousEphemeralSeed: String,
	onNoncesUpdated: (List<String>, String, String) -> Unit,
	emit: (qrData: String) -> Unit,
) {
	val alphas = org.json.JSONArray(alphasJson)
	val bundled = org.json.JSONArray(bundledCommitmentsJson)
	require(alphas.length() == bundled.length() && alphas.length() == previousNoncesPerAction.size) {
		"action count mismatch: alphas=${alphas.length()} bundled=${bundled.length()} nonces=${previousNoncesPerAction.size}"
	}
	val shares = mutableListOf<String>()
	for (i in 0 until alphas.length()) {
		val share = withContext(Dispatchers.Default) {
			frostSpendSignRound2Signed(
				previousEphemeralSeed,
				previousKeyPackage,
				previousNoncesPerAction[i],
				sighashHex,
				alphas.getString(i),
				bundled.getJSONArray(i).toString(),
			)
		}
		shares.add(share)
	}
	onNoncesUpdated(emptyList(), "", "")
	emit(org.json.JSONObject().apply {
		put("frost", "sign2-resp")
		put("shares", org.json.JSONArray(shares))
	}.toString())
}
