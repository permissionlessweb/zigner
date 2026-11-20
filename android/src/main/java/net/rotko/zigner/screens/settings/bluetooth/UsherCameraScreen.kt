package net.rotko.zigner.screens.settings.bluetooth

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textSecondary

@Composable
fun UsherCameraScreen(onClose: Callback) {
	// Kept for any leftover deep link; the production entry is the home SEZS tab.
	SezsUsherTab()
}

@Composable
fun SezsUsherTab() {
	val context = LocalContext.current
	var status by remember { mutableStateOf("") }
	var pub by remember { mutableStateOf("") }
	var noise by remember { mutableStateOf("") }
	var grantHash by remember { mutableStateOf("") }
	var eventId by remember { mutableStateOf("") }
	var eventName by remember { mutableStateOf("") }
	var grantExpiry by remember { mutableStateOf("") }
	var grantCapability by remember { mutableStateOf("") }
	var grantAdmin by remember { mutableStateOf("") }
	var seq by remember { mutableStateOf(1L) }
	var scanning by remember { mutableStateOf(false) }
	var confirmRadio by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(Unit) {
		val material = UsherDeviceKey.load(context)
		pub = UsherDeviceKey.hex(material.signPub)
		noise = UsherDeviceKey.hex(material.noisePub)
		val prefs = context.getSharedPreferences("usher-device", 0)
		grantHash = prefs.getString("grant_hash", "").orEmpty()
		eventId = prefs.getString("event_id", "").orEmpty()
		eventName = prefs.getString("event_name", "").orEmpty()
		grantExpiry = prefs.getString("grant_expiry", "").orEmpty()
		grantCapability = prefs.getString("grant_capability", "").orEmpty()
		grantAdmin = prefs.getString("grant_admin", "").orEmpty()
		seq = prefs.getLong("seq", 1L)
	}

	val eventTitle = eventName.ifBlank { eventId.ifBlank { "SEZS" } }
	val tag = if (eventId.isBlank()) "—" else SezsDisplay.eventTag(eventId)
	val desk = when {
		SignerRuntime.sessions > 0 -> "Connected"
		SignerRuntime.running -> "Radio on"
		else -> "Off"
	}

	Column(Modifier.fillMaxSize()) {
		Row(
			Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(eventTitle, style = SignerTypeface.TitleS)
				Text(tag, style = SignerTypeface.LabelS, color = MaterialTheme.colors.textSecondary)
			}
			Text(
				if (SignerRuntime.running) "Radio on" else "Radio",
				style = SignerTypeface.LabelS,
				color = MaterialTheme.colors.textSecondary,
				modifier = Modifier
					.clickable { confirmRadio = if (SignerRuntime.running) "stop" else "start" }
					.padding(8.dp),
			)
		}
		Column(
			Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			SectionLabel("Device")
			FingerprintRow("Sign key", pub)
			FingerprintRow("Noise static", noise)
			Spacer(Modifier.height(12.dp))
			SectionLabel("Grant")
			MetaRow("Hash", SezsDisplay.shortHex(grantHash), grantHash)
			MetaRow("Expiry", grantExpiry.ifBlank { "—" })
			MetaRow("Capability", grantCapability.ifBlank { "—" })
			MetaRow("Admin", SezsDisplay.shortHex(grantAdmin), grantAdmin)
			Spacer(Modifier.height(12.dp))
			SectionLabel("Session")
			MetaRow("Desk", desk)
			MetaRow("Last seq", if (seq > 0) seq.toString() else "—")
			if (status.isNotBlank()) {
				Spacer(Modifier.height(12.dp))
				Text(status, style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
			}
			if (scanning) {
				Spacer(Modifier.height(12.dp))
				ZcintiQrStream { raw ->
					val payload = TicketCanon.parseSezs3(raw) ?: return@ZcintiQrStream false
					scanning = false
					status = sendTicket(context, payload, pub, grantHash, eventId.ifBlank { "speakez-sessions" }, seq)
					if (status.startsWith("Sent")) {
						seq += 1
						context.getSharedPreferences("usher-device", 0).edit().putLong("seq", seq).apply()
					}
					true
				}
			}
		}
		Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
			PrimaryButtonWide(
				label = if (scanning) "Stop camera" else "Scan ticket",
				onClicked = {
					if (scanning) {
						scanning = false
						return@PrimaryButtonWide
					}
					val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
					if (!ok) {
						status = "Allow the camera, then try again."
						return@PrimaryButtonWide
					}
					scanning = true
				},
			)
		}
	}

	val pending = confirmRadio
	if (pending != null) {
		AlertDialog(
			onDismissRequest = { confirmRadio = null },
			title = { Text(if (pending == "stop") "Stop radio?" else "Start radio?") },
			confirmButton = {
				TextButton(onClick = {
					if (pending == "stop") {
						SignerRuntime.stop(context)
						status = "Radio off."
					} else {
						SignerRuntime.ensureStarted(context)
						status = "Radio opening."
					}
					confirmRadio = null
				}) { Text("Confirm") }
			},
			dismissButton = {
				TextButton(onClick = { confirmRadio = null }) { Text("Cancel") }
			},
		)
	}
}

@Composable
private fun SectionLabel(label: String) {
	Text(
		label,
		style = SignerTypeface.LabelS,
		color = MaterialTheme.colors.textSecondary,
		modifier = Modifier.padding(bottom = 4.dp),
	)
}

@Composable
private fun FingerprintRow(label: String, hex: String) {
	val context = LocalContext.current
	var expanded by remember { mutableStateOf(false) }
	val shown = if (expanded) hex.ifBlank { "—" } else SezsDisplay.shortHex(hex)
	Row(
		Modifier
			.fillMaxWidth()
			.clickable { if (hex.isNotBlank()) expanded = !expanded }
			.padding(vertical = 4.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Column(Modifier.weight(1f)) {
			Text(label, style = SignerTypeface.LabelS, color = MaterialTheme.colors.textSecondary)
			Text(shown, style = SignerTypeface.BodyM)
		}
		if (hex.isNotBlank()) {
			Text(
				"Copy",
				style = SignerTypeface.LabelS,
				color = MaterialTheme.colors.textSecondary,
				modifier = Modifier
					.clickable { copyText(context, hex) }
					.padding(8.dp),
			)
		}
	}
}

@Composable
private fun MetaRow(label: String, value: String, copy: String = "") {
	val context = LocalContext.current
	Row(
		Modifier
			.fillMaxWidth()
			.padding(vertical = 2.dp),
		horizontalArrangement = Arrangement.SpaceBetween,
	) {
		Text(label, style = SignerTypeface.LabelS, color = MaterialTheme.colors.textSecondary)
		Text(
			value,
			style = SignerTypeface.BodyM,
			modifier = if (copy.isNotBlank()) Modifier.clickable { copyText(context, copy) } else Modifier,
		)
	}
}

private fun copyText(context: Context, value: String) {
	val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
	clipboard.setPrimaryClip(ClipData.newPlainText("sezs", value))
}

internal fun sendTicket(
	context: android.content.Context,
	payload: String,
	pub: String,
	grantHash: String,
	eventId: String,
	seq: Long,
): String {
	if (grantHash.length != 64) return "Pair this phone on the desk first."
	val material = UsherDeviceKey.load(context)
	val text = TicketCanon.encodeFrame(
		grantHash = grantHash,
		deviceSignPubkey = pub,
		eventId = eventId,
		seq = seq,
		ts = System.currentTimeMillis() / 1000,
		payload = payload,
	)
	val sig = TicketCanon.signFrame(text, material.sign)
	val plain = TicketCanon.envelope(text, sig)
	return if (SignerRuntime.notifyTicket(plain)) "Sent ticket frame $seq."
	else "No Noise session is ready."
}
