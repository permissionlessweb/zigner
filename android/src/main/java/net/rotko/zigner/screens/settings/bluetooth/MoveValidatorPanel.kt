package net.rotko.zigner.screens.settings.bluetooth

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.DisableScreenshots
import net.rotko.zigner.domain.findActivity
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.accentGreen
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.fill12
import net.rotko.zigner.ui.theme.pink500
import net.rotko.zigner.ui.theme.red500
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary

/** Seconds a move QR stays up before it hides itself. */
private const val SECRET_QR_SECONDS = 90

/* ---------- small shared pieces ---------- */

@Composable
internal fun MoveCard(icon: ImageVector, label: String, trailing: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
	Column(
		Modifier.fillMaxWidth().background(MaterialTheme.colors.fill6, RoundedCornerShape(16.dp)).padding(14.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(icon, contentDescription = null, tint = MaterialTheme.colors.textSecondary, modifier = Modifier.size(16.dp))
			Spacer(Modifier.width(6.dp))
			Text(label.uppercase(), style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textSecondary, modifier = Modifier.weight(1f))
			trailing?.invoke()
		}
		content()
	}
}

@Composable
private fun Pill(text: String, color: Color) {
	Text(text, style = SignerTypeface.CaptionM, color = color,
		modifier = Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
}

/** 1-2-3 stepper: done steps get a check, the current one is filled. */
@Composable
private fun Steps(labels: List<String>, current: Int) {
	Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
		labels.forEachIndexed { i, l ->
			Row(verticalAlignment = Alignment.CenterVertically) {
				val done = i < current
				val now = i == current
				Box(
					Modifier.size(20.dp).clip(CircleShape).background(
						when { done -> MaterialTheme.colors.accentGreen; now -> MaterialTheme.colors.pink500; else -> MaterialTheme.colors.fill12 },
					),
					contentAlignment = Alignment.Center,
				) {
					Text(if (done) "✓" else "${i + 1}", fontSize = 11.sp, color = if (done || now) Color.White else MaterialTheme.colors.textTertiary)
				}
				Spacer(Modifier.width(8.dp))
				Text(l, style = SignerTypeface.BodyM, color = if (now) MaterialTheme.colors.primary else MaterialTheme.colors.textSecondary)
			}
		}
	}
}

@Composable
internal fun Tiles(pub: ByteArray, size: Int = 30) {
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FingerprintTiles.of(pub).forEach { (g, c) ->
			Box(Modifier.size(size.dp).clip(RoundedCornerShape(7.dp)).background(Color(c)), contentAlignment = Alignment.Center) {
				Text(g, color = Color.White, fontSize = (size / 2).sp)
			}
		}
	}
}

@Composable
private fun Identity(label: String, pub: ByteArray) {
	Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
		Text(label, style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary)
		Tiles(pub)
		Text(KeyExport.fingerprint(pub), style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary)
	}
}

@Composable
private fun AnimatedQr(payload: String, description: String) {
	val frames = remember(payload) { if (payload.length <= 300) listOf(payload) else Handoff.frames(payload) }
	var i by remember(payload) { mutableStateOf(0) }
	var image by remember(payload) { mutableStateOf<ImageBitmap?>(null) }
	LaunchedEffect(payload) {
		while (true) {
			try {
				val png = io.parity.signer.uniffi.encodeToQr(frames[i].encodeToByteArray().map { it.toUByte() }, false)
				val bytes = png.map { it.toByte() }.toByteArray()
				image = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
			} catch (_: Exception) { image = null }
			if (frames.size == 1) break
			delay(350)
			i = (i + 1) % frames.size
		}
	}
	Box(Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(8.dp), contentAlignment = Alignment.Center) {
		image?.let { androidx.compose.foundation.Image(bitmap = it, contentDescription = description, modifier = Modifier.height(260.dp).fillMaxWidth()) }
	}
	if (frames.size > 1) Text("animated · frame ${i + 1}/${frames.size}", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary)
}

/**
 * A QR that carries key material: FLAG_SECURE while shown, hides itself
 * after SECRET_QR_SECONDS and as soon as the app goes to the background
 * (showing it again needs BiometricPrompt again).
 */
@Composable
private fun SecretQr(payload: String, onHide: () -> Unit) {
	DisableScreenshots()
	val owner = LocalLifecycleOwner.current
	DisposableEffect(owner) {
		val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP || e == Lifecycle.Event.ON_PAUSE) onHide() }
		owner.lifecycle.addObserver(obs)
		onDispose { owner.lifecycle.removeObserver(obs) }
	}
	var left by remember(payload) { mutableStateOf(SECRET_QR_SECONDS) }
	LaunchedEffect(payload) {
		while (left > 0) { delay(1000); left-- }
		onHide()
	}
	AnimatedQr(payload, "Sealed move QR")
	Row(verticalAlignment = Alignment.CenterVertically) {
		Pill("hides in ${left}s", MaterialTheme.colors.textSecondary)
		Spacer(Modifier.width(8.dp))
		Pill("screenshots blocked", MaterialTheme.colors.textSecondary)
	}
}

/** BiometricPrompt (strong biometric or device credential). onOk runs only on success. */
private fun withAuth(context: android.content.Context, onOk: () -> Unit) {
	val activity = context.findActivity() as? FragmentActivity
	if (activity == null) {
		SignerRuntime.status = "unlock is unavailable here"
		return
	}
	ServiceLocator.authentication.authenticate(activity, validatorSigner = true, onSuccess = onOk)
}

/* ---------- old phone: Chain tab ---------- */

@Composable
internal fun MoveOutCard(key: ValidatorKey, onScan: () -> Unit) {
	val context = LocalContext.current
	val start = MoveFlow.outStart[key.id]
	val target = MoveFlow.outTarget[key.id]
	var tick by remember { mutableStateOf(0) }
	val waiting = remember(key.id, tick, start, target) { MoveFlow.awaitingAck(context, key.id) }
	var payload by remember(key.id) { mutableStateOf<String?>(null) }
	var confirmCancel by remember(key.id) { mutableStateOf(false) }
	var error by remember(key.id) { mutableStateOf("") }
	val me = remember { runCatching { MoveFlow.phoneId(context) }.getOrNull() }
	val quiz = remember(key.id, target) { target?.let { TileChallenge.make(it.id) } }
	var picked by remember(key.id, target) { mutableStateOf(setOf<Int>()) }
	val quizDone = quiz != null && picked == quiz.answer
	val frozen = PhoneGate.frozen(context.filesDir, key.id)
	val step = when {
		start == null && !waiting -> 0
		target == null && !waiting -> 1
		!waiting -> 2
		else -> 3
	}
	MoveCard(Icons.Outlined.SwapHoriz, "Move out", trailing = { if (frozen) Pill("frozen", MaterialTheme.colors.red500) }) {
		Steps(listOf("Scan the console's Move QR", "Scan the new phone's Receive QR", "Pick the new phone's tiles · show move QR", "Scan the new phone's Imported QR"), step)
		start?.let { Text("floor ${it.floor} · ${it.chain}/${it.slot}", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary) }
		if (target != null && me != null && payload == null) {
			Identity("This phone", me)
			if (quiz != null && !waiting) {
				Text("Look at the NEW phone's Receive screen. Tap the ${TileChallenge.PICK} tiles below that appear among its first 4 tiles. A wrong tap cancels.",
					style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
				Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
					quiz.options.forEachIndexed { i, (g, c) ->
						val on = i in picked
						Box(
							Modifier.size(38.dp).clip(RoundedCornerShape(7.dp))
								.background(Color(c).copy(alpha = if (on) 0.35f else 1f))
								.clickable(enabled = !on && !quizDone) {
									if (quiz.correct(i)) picked = picked + i
									else {
										picked = emptySet()
										MoveFlow.outTarget = MoveFlow.outTarget - key.id
										error = "That tile is not on the new phone. Scan its Receive QR again."
									}
								},
							contentAlignment = Alignment.Center,
						) { Text(if (on) "✓" else g, color = Color.White, fontSize = 19.sp) }
					}
				}
			}
		}
		payload?.let { p ->
			SecretQr(p) { payload = null; tick++ }
			Text("Only the new phone above can open this. Do not show it to anything else.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		}
		when (step) {
			0 -> PrimaryButtonWide(label = "Scan console QR", onClicked = onScan)
			1 -> PrimaryButtonWide(label = "Scan new phone", onClicked = onScan)
			2 -> PrimaryButtonWide(label = "Show move QR", isEnabled = quizDone, onClicked = {
				if (quizDone) withAuth(context) {
					try { payload = MoveFlow.sealOut(context, key.id); error = "" } catch (e: Exception) { error = e.message ?: "could not seal" }
					tick++
				}
			})
			else -> {
				if (payload == null && target != null) {
					SecondaryButtonWide(label = "Show move QR again", withBackground = true, onClicked = {
						withAuth(context) { try { payload = MoveFlow.sealOut(context, key.id) } catch (e: Exception) { error = e.message ?: "could not seal" } }
					})
				}
				PrimaryButtonWide(label = "Scan Imported QR", onClicked = { payload = null; onScan() })
			}
		}
		if (error.isNotEmpty()) Text(error, style = SignerTypeface.BodyM, color = MaterialTheme.colors.red500)
		if (step > 0) {
			if (!confirmCancel) {
				SecondaryButtonWide(label = "Cancel move", onClicked = { if (waiting) confirmCancel = true else { MoveFlow.cancelOut(context, key.id); tick++ } })
			} else {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Icon(Icons.Outlined.WarningAmber, null, tint = MaterialTheme.colors.red500, modifier = Modifier.size(16.dp))
					Spacer(Modifier.width(6.dp))
					Text("The move QR was shown. Cancel only if the new phone did NOT import it, and cancel on the console too.",
						style = SignerTypeface.BodyM, color = MaterialTheme.colors.red500)
				}
				PrimaryButtonWide(label = "Cancel and unfreeze", activeBackground = MaterialTheme.colors.red500, onClicked = {
					payload = null; MoveFlow.cancelOut(context, key.id); confirmCancel = false; tick++
				})
				SecondaryButtonWide(label = "Keep the move", onClicked = { confirmCancel = false })
			}
		}
	}
}

/* ---------- main tab ---------- */

@Composable
internal fun PairingCard(onScan: () -> Unit) {
	val context = LocalContext.current
	var tick by remember { mutableStateOf(0) }
	val paired = remember(tick, MoveFlow.kmsCandidate) { MoveFlow.pairedKms(context) }
	val cand = MoveFlow.kmsCandidate
	MoveCard(Icons.Outlined.Link, "KMS", trailing = {
		when {
			!SignerRuntime.running -> Pill("off", MaterialTheme.colors.textSecondary)
			SignerRuntime.sessions > 0 -> Pill("connected", MaterialTheme.colors.accentGreen)
			paired != null -> Pill("connecting", MaterialTheme.colors.textSecondary)
			else -> Pill("not paired", MaterialTheme.colors.textSecondary)
		}
	}) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(Icons.Outlined.Bluetooth, null, tint = if (SignerRuntime.running) MaterialTheme.colors.accentGreen else MaterialTheme.colors.textTertiary, modifier = Modifier.size(16.dp))
			Spacer(Modifier.width(6.dp))
			Text(if (SignerRuntime.running) "Bluetooth signer on · ${SignerRuntime.sessions} session(s)" else "Bluetooth signer off",
				style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textSecondary, modifier = Modifier.weight(1f))
			Text("Settings", style = SignerTypeface.LabelM, color = MaterialTheme.colors.pink500, modifier = Modifier.padding(start = 8.dp).clickableNoRipple {
				context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
			})
		}
		if (cand != null) {
			Identity("Scanned KMS", cand)
			if (paired != null && !paired.contentEquals(cand)) {
				Text("This replaces the KMS this phone is paired to.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.red500)
			}
			PrimaryButtonWide(label = "Tiles match · Pair this KMS", onClicked = { MoveFlow.pairKms(context, cand); tick++ })
			SecondaryButtonWide(label = "Not this one", onClicked = { MoveFlow.kmsCandidate = null })
		} else if (paired != null) {
			Identity("Paired KMS", paired)
		} else {
			// First pairing only.
			Steps(listOf("Turn on Bluetooth", "On the console open Phones and show the KMS QR", "Scan it here and compare tiles"),
				if (SignerRuntime.running) 1 else 0)
			if (SignerRuntime.pendingKms.isNotBlank()) {
				Text("A KMS knocked: ${SignerRuntime.pendingKms}. Scan its QR to pair.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
			}
			PrimaryButtonWide(label = "Scan KMS QR", onClicked = onScan)
		}
	}
}

@Composable
internal fun ReceiveKeyCard(onScan: () -> Unit) {
	val context = LocalContext.current
	val qr = MoveFlow.receiveQr
	val done = MoveFlow.imported
	val me = remember { runCatching { MoveFlow.phoneId(context) }.getOrNull() }
	MoveCard(Icons.Outlined.Download, "Receive a key") {
		when {
			done != null -> {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Icon(Icons.Outlined.CheckCircle, null, tint = MaterialTheme.colors.accentGreen, modifier = Modifier.size(18.dp))
					Spacer(Modifier.width(6.dp))
					Text("${done.keyName} imported · floor ${done.floor}", style = SignerTypeface.BodyL, color = MaterialTheme.colors.primary)
				}
				Identity("Old phone", done.oldId)
				Text("Show this to the old phone (Scan Imported QR). It then retires its copy.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
				AnimatedQr(done.ack, "Imported QR")
				SecondaryButtonWide(label = "Done", onClicked = { MoveFlow.imported = null })
			}
			qr != null -> {
				Steps(listOf("Show this Receive QR to the old phone", "Scan the old phone's move QR"), 0)
				AnimatedQr(qr, "Receive QR")
				me?.let { Identity("This phone", it) }
				PrimaryButtonWide(label = "Scan move QR", onClicked = onScan)
				SecondaryButtonWide(label = "Cancel", onClicked = { MoveFlow.cancelReceive() })
			}
			else -> {
				Text("Moving a validator to this phone? The key travels by QR only, never by radio.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
				SecondaryButtonWide(label = "Receive a key", withBackground = true, onClicked = {
					try { MoveFlow.newReceive(context) } catch (e: Exception) { SignerRuntime.status = e.message ?: "could not start Receive" }
				})
			}
		}
	}
}

@Composable
internal fun RetireProofCard() {
	val context = LocalContext.current
	val proof = MoveFlow.retireProof ?: remember { MoveFlow.storedProof(context) }?.also { MoveFlow.retireProof = it }
	if (proof == null) return
	MoveCard(Icons.Outlined.TaskAlt, "Retire proof") {
		Text("The moved key is gone from this phone. Show this to the console's Scan.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		AnimatedQr(proof, "Retire proof QR")
		SecondaryButtonWide(label = "Console has it · Done", onClicked = { MoveFlow.clearProof(context) })
	}
}

@Composable
internal fun ScanHeader(onScan: () -> Unit) {
	Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
		Text("SIGNER", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textSecondary, modifier = Modifier.weight(1f))
		Row(
			Modifier.clip(RoundedCornerShape(999.dp)).background(MaterialTheme.colors.pink500).clickableNoRipple(onScan).padding(horizontal = 14.dp, vertical = 8.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan", tint = Color.White, modifier = Modifier.size(18.dp))
			Spacer(Modifier.width(6.dp))
			Text("Scan", style = SignerTypeface.LabelM, color = Color.White)
		}
	}
}

@Composable
internal fun BiometricHint() {
	Row(verticalAlignment = Alignment.CenterVertically) {
		Icon(Icons.Outlined.Fingerprint, null, tint = MaterialTheme.colors.textTertiary, modifier = Modifier.size(14.dp))
		Spacer(Modifier.width(4.dp))
		Text("Unlock needed to show a move QR", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary)
	}
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)
