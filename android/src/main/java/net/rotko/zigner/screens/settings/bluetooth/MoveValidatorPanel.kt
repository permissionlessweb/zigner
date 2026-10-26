package net.rotko.zigner.screens.settings.bluetooth

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textSecondary

/** Move validator: Move out (old phone) and Receive (new phone). No typing. */
@Composable
internal fun MoveValidatorPanel() {
	val context = LocalContext.current
	var mode by remember { mutableStateOf(0) } // 0 idle, 1 move out, 2 receive, 3 retire
	var scanning by remember { mutableStateOf(false) }
	var ticket by remember { mutableStateOf<Handoff.Ticket?>(null) }
	var payload by remember { mutableStateOf<String?>(null) }
	var tilesOk by remember { mutableStateOf(false) }
	var note by remember { mutableStateOf("") }
	val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { scanning = it }
	fun scan() {
		if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = true
		else camera.launch(Manifest.permission.CAMERA)
	}
	fun reset(m: Int) { mode = m; ticket = null; payload = null; tilesOk = false; note = ""; scan() }

	Text("Move validator", style = SignerTypeface.TitleS, color = MaterialTheme.colors.onBackground)
	if (scanning) {
		ZcintiQrScan { text ->
			scanning = false
			try {
				ticket = Handoff.parse(text)
				note = ""
			} catch (e: Exception) {
				note = e.message ?: "scan the Move QR on the console"
			}
		}
		SecondaryButtonWide(label = "Cancel", onClicked = { scanning = false })
		return
	}
	if (mode == 0) {
		Text("Start Move on the console first, then scan its Move QR here.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		SecondaryButtonWide(label = "Move out (this phone has the key)", onClicked = { reset(1) })
		SecondaryButtonWide(label = "Receive (this is the new phone)", onClicked = { reset(2) })
		SecondaryButtonWide(label = "Retire (after the console says sent)", onClicked = { reset(3) })
		ValidatorKeys.list(context).filter { PhoneGate.frozen(context.filesDir, it.id) }.forEach { k ->
			Text("❄ ${k.slot.ifBlank { k.name }} is frozen for a move and will not sign here.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
			SecondaryButtonWide(label = "Cancel move · keep signing ${k.slot.ifBlank { k.name }} here", onClicked = {
				if (Handoff.tombstoned(context, k.pubHex)) {
					note = "that key was retired; it cannot sign here again"
				} else {
					PhoneGate.unfreeze(context.filesDir, k.id)
					note = "${k.slot.ifBlank { k.name }} signs on this phone again. Cancel the move on the console too."
				}
			})
		}
		if (note.isNotEmpty()) Text(note, style = SignerTypeface.BodyM, color = MaterialTheme.colors.onBackground)
		return
	}
	val t = ticket
	if (t != null) {
		Text("${t.chain} / ${t.slot}  ·  floor ${t.floor}", style = SignerTypeface.BodyM, color = MaterialTheme.colors.onBackground)
		Text("Do these tiles match the console?", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		Tiles(t.kmsPub)
		if (!tilesOk && payload == null) {
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
				Box(Modifier.weight(1f)) { PrimaryButtonWide(label = "They match", onClicked = {
					tilesOk = true
					try {
						when (mode) {
							1 -> payload = Handoff.exportV2(context, t)
							2 -> {
								Handoff.arm(context, t)
								note = "Ready. On the console, confirm this phone's tiles, then Send."
							}
							3 -> payload = Handoff.retire(context, t).also { SignerRuntime.refreshKeys(context) }
						}
					} catch (e: Exception) {
						note = e.message ?: "failed"
						tilesOk = false
					}
				}) }
				Box(Modifier.weight(1f)) { SecondaryButtonWide(label = "No", onClicked = { mode = 0; ticket = null }) }
			}
		}
		if (mode == 2 && tilesOk) {
			Text("This phone's Bluetooth identity:", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
			Tiles(X25519.base(ValidatorKeys.noiseSeed(context)))
		}
	}
	payload?.let {
		Text(if (mode == 3) "Show this retire proof to the console. The key is gone from this phone." else "Show this to the console's Scan.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.onBackground)
		AnimatedQr(it)
	}
	if (note.isNotEmpty()) Text(note, style = SignerTypeface.BodyM, color = MaterialTheme.colors.onBackground)
	SecondaryButtonWide(label = "Done", onClicked = { mode = 0; ticket = null; payload = null })
}

@Composable
internal fun Tiles(pub: ByteArray) {
	Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
		FingerprintTiles.of(pub).forEach { (g, c) ->
			Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(c)), contentAlignment = Alignment.Center) {
				Text(g, color = Color.White, fontSize = 18.sp)
			}
		}
	}
}

@Composable
private fun AnimatedQr(payload: String) {
	val frames = remember(payload) { if (payload.length <= 300) listOf(payload) else Handoff.frames(payload) }
	var i by remember(payload) { mutableStateOf(0) }
	var image by remember { mutableStateOf<ImageBitmap?>(null) }
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
	image?.let { androidx.compose.foundation.Image(bitmap = it, contentDescription = "Move QR", modifier = Modifier.height(260.dp).fillMaxWidth()) }
	if (frames.size > 1) Text("frame ${i + 1}/${frames.size}", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
}
