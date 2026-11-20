package net.rotko.zigner.screens.settings.bluetooth

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textSecondary

/**
 * Camera-only usher mode: scan a sezs3 ticket, sign a device frame, send it
 * on the TICKET characteristic of the existing GATT server, inside the
 * Noise XX session. No validator keys are required.
 */
@Composable
fun UsherCameraScreen(onClose: Callback) {
	val context = LocalContext.current
	var status by remember { mutableStateOf("Turn on the radio, then scan a guest ticket.") }
	var pub by remember { mutableStateOf("") }
	var noise by remember { mutableStateOf("") }
	var grantHash by remember { mutableStateOf("") }
	var eventId by remember { mutableStateOf("speakez-sessions") }
	var seq by remember { mutableStateOf(1L) }
	var scanning by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		val material = UsherDeviceKey.load(context)
		pub = UsherDeviceKey.hex(material.signPub)
		noise = UsherDeviceKey.hex(material.noisePub)
		val prefs = context.getSharedPreferences("usher-device", 0)
		grantHash = prefs.getString("grant_hash", "").orEmpty()
		eventId = prefs.getString("event_id", eventId).orEmpty().ifBlank { eventId }
		seq = prefs.getLong("seq", 1L)
	}

	Column(Modifier.fillMaxSize()) {
		ScreenHeaderClose(title = "Usher door camera", onClose = onClose)
		Column(
			Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(horizontal = 16.dp, vertical = 8.dp),
		) {
			Text(
				"This phone is a camera for the desk. It does not build the door proof and it does not hold the admin key.",
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.textSecondary,
			)
			Spacer(Modifier.height(12.dp))
			Text("Device sign key", style = SignerTypeface.LabelS, color = MaterialTheme.colors.textSecondary)
			Text(pub.ifBlank { "…" }, style = SignerTypeface.BodyM)
			Text("Noise static", style = SignerTypeface.LabelS, color = MaterialTheme.colors.textSecondary)
			Text(noise.ifBlank { "…" }, style = SignerTypeface.BodyM)
			Spacer(Modifier.height(12.dp))
			Text(status, style = SignerTypeface.BodyL)
			Spacer(Modifier.height(12.dp))
			if (scanning) {
				ZcintiQrStream { raw ->
					val payload = TicketCanon.parseSezs3(raw) ?: return@ZcintiQrStream false
					scanning = false
					status = sendTicket(context, payload, pub, grantHash, eventId, seq)
					if (status.startsWith("Sent")) {
						seq += 1
						context.getSharedPreferences("usher-device", 0).edit().putLong("seq", seq).apply()
					}
					true
				}
			}
			Spacer(Modifier.height(16.dp))
			PrimaryButtonWide(label = "Start radio", onClicked = {
				SignerRuntime.ensureStarted(context)
				status = "Radio opening. Confirm the fingerprints on the desk, then scan."
			})
			Spacer(Modifier.height(8.dp))
			SecondaryButtonWide(label = if (scanning) "Stop camera" else "Scan a ticket", onClicked = {
				val ok = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
				if (!ok) {
					status = "Allow the camera, then try again."
					return@SecondaryButtonWide
				}
				scanning = !scanning
			})
		}
	}
}

internal fun sendTicket(
	context: android.content.Context,
	payload: String,
	pub: String,
	grantHash: String,
	eventId: String,
	seq: Long,
): String {
	if (grantHash.length != 64) return "Pair this phone on the desk first. The grant hash is not stored yet."
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
	return if (SignerRuntime.notifyTicket(plain)) "Sent ticket frame $seq. The desk will authorize the consume."
	else "No Noise session is ready. Start the radio and finish pairing first. The ticket was not sent."
}
