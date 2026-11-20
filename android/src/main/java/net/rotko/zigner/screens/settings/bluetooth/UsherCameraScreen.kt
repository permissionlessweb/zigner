package net.rotko.zigner.screens.settings.bluetooth

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.storage.RepoResult
import net.rotko.zigner.screens.scan.camera.SezsTicketScanScreen
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.textSecondary

@Composable
fun UsherCameraScreen(onClose: Callback) {
	// Kept for any leftover deep link; the production entry is the home SEZS tab.
	SezsUsherTab()
}

@Composable
fun SezsUsherTab(
	currentSeedName: String = "",
	onFullscreenScan: (Boolean) -> Unit = {},
) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var tab by remember { mutableStateOf(0) }
	var scanning by remember { mutableStateOf(false) }
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
	var seedName by remember { mutableStateOf("") }
	var keyIndex by remember { mutableStateOf(0) }
	var seedNames by remember { mutableStateOf(listOf<String>()) }
	var confirmRadio by remember { mutableStateOf<String?>(null) }
	var history by remember { mutableStateOf(listOf<SezsTicketHistory.Row>()) }
	var bindingBusy by remember { mutableStateOf(false) }

	fun reload() {
		val material = UsherDeviceKey.tryLoad(context)
		pub = material?.let { UsherDeviceKey.hex(it.signPub) }.orEmpty()
		noise = material?.let { UsherDeviceKey.hex(it.noisePub) }.orEmpty()
		val prefs = UsherDeviceKey.prefs(context)
		grantHash = prefs.getString("grant_hash", "").orEmpty()
		eventId = prefs.getString("event_id", "").orEmpty()
		eventName = prefs.getString("event_name", "").orEmpty()
		grantExpiry = prefs.getString("grant_expiry", "").orEmpty()
		grantCapability = prefs.getString("grant_capability", "").orEmpty()
		grantAdmin = prefs.getString("grant_admin", "").orEmpty()
		seq = prefs.getLong("seq", 1L)
		val bound = UsherDeviceKey.binding(context)
		seedName = bound?.seedName ?: currentSeedName
		keyIndex = bound?.index ?: 0
		seedNames = SezsKeysetAccess.names()
		history = SezsTicketHistory.load(context)
	}

	LaunchedEffect(currentSeedName) { reload() }

	LaunchedEffect(scanning) { onFullscreenScan(scanning) }

	val permit = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions(),
	) { granted ->
		if (granted.values.all { it }) {
			SignerRuntime.ensureStarted(context)
			status = "Radio opening."
		} else {
			status = "Allow Bluetooth to start."
		}
		confirmRadio = null
	}

	val eventTitle = eventName.ifBlank { eventId.ifBlank { "SEZS" } }
	val deskOn = SignerRuntime.sessions > 0

	if (scanning) {
		SezsTicketScanScreen(
			onClose = { scanning = false },
			onTicket = { payload ->
				scanning = false
				status = dispatchTicket(context, payload, grantHash, eventId, seq)
				reload()
			},
		)
	} else {

	Column(Modifier.fillMaxSize()) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			SezsSubTab("Scan", tab == 0) { tab = 0 }
			SezsSubTab("Session", tab == 1) { tab = 1 }
			SezsSubTab("History", tab == 2) { tab = 2 }
		}
		when (tab) {
			0 -> ScanPane(
				eventTitle = eventTitle,
				deskOn = deskOn,
				status = status,
				canScan = pub.isNotBlank() && grantHash.length == 64,
				onScan = { scanning = true },
			)
			1 -> ConfigPane(
				eventTitle = eventTitle,
				eventId = eventId,
				pub = pub,
				noise = noise,
				grantHash = grantHash,
				grantExpiry = grantExpiry,
				grantCapability = grantCapability,
				grantAdmin = grantAdmin,
				seq = seq,
				seedName = seedName,
				keyIndex = keyIndex,
				seedNames = seedNames,
				bindingBusy = bindingBusy,
				status = status,
				onSelectSeed = { seedName = it },
				onIndex = { keyIndex = it.coerceAtLeast(0) },
				onBind = {
					if (seedName.isBlank() || bindingBusy) return@ConfigPane
					bindingBusy = true
					scope.launch {
						val phrase = SezsKeysetAccess.unlock(seedName)
						status = if (phrase.isNullOrBlank()) {
							"Unlock cancelled."
						} else {
							try {
								withContext(Dispatchers.Default) {
									UsherDeviceKey.bind(context, seedName, keyIndex, phrase)
								}
								"Device key ready."
							} catch (_: Exception) {
								"Could not derive the device key."
							}
						}
						bindingBusy = false
						reload()
					}
				},
				onConfirmRadio = { confirmRadio = it },
			)
			else -> HistoryPane(history)
		}
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
						confirmRadio = null
					} else {
						val missing = runtimeBluetoothPermissions().filter {
							context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
						}
						if (missing.isNotEmpty()) {
							permit.launch(missing.toTypedArray())
						} else {
							SignerRuntime.ensureStarted(context)
							status = "Radio opening."
							confirmRadio = null
						}
					}
				}) { Text("Confirm") }
			},
			dismissButton = {
				TextButton(onClick = { confirmRadio = null }) { Text("Cancel") }
			},
		)
	}
}

@Composable
private fun ScanPane(
	eventTitle: String,
	deskOn: Boolean,
	status: String,
	canScan: Boolean,
	onScan: () -> Unit,
) {
	Column(
		Modifier
			.fillMaxSize()
			.padding(horizontal = 16.dp, vertical = 12.dp),
	) {
		Row(
			Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(eventTitle, style = SignerTypeface.TitleS)
			StatusPill(deskOn, if (deskOn) "Desk connected" else "Desk off")
		}
		if (status.isNotBlank()) {
			Spacer(Modifier.height(12.dp))
			Text(status, style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		}
		Spacer(Modifier.weight(1f))
		PrimaryButtonWide(
			label = "Scan ticket",
			onClicked = onScan,
		)
		if (!canScan) {
			Spacer(Modifier.height(8.dp))
			Text(
				if (!canScan) "Pair this phone on the desk first." else "",
				style = SignerTypeface.LabelS,
				color = MaterialTheme.colors.textSecondary,
			)
		}
	}
}

@Composable
private fun ConfigPane(
	eventTitle: String,
	eventId: String,
	pub: String,
	noise: String,
	grantHash: String,
	grantExpiry: String,
	grantCapability: String,
	grantAdmin: String,
	seq: Long,
	seedName: String,
	keyIndex: Int,
	seedNames: List<String>,
	bindingBusy: Boolean,
	status: String,
	onSelectSeed: (String) -> Unit,
	onIndex: (Int) -> Unit,
	onBind: () -> Unit,
	onConfirmRadio: (String) -> Unit,
) {
	val desk = when {
		SignerRuntime.sessions > 0 -> "Connected"
		SignerRuntime.running -> "Radio on"
		else -> "Off"
	}
	val bound = seedName.isNotBlank() && pub.isNotBlank()
	Column(
		Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 8.dp),
	) {
		SectionLabel("Event")
		MetaRow("Name", eventTitle)
		MetaRow("Tag", if (eventId.isBlank()) "—" else SezsDisplay.eventTag(eventId), eventId)
		Spacer(Modifier.height(12.dp))
		SectionLabel("Key set")
		if (seedNames.isEmpty()) {
			Text("—", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		} else {
			seedNames.forEach { name ->
				val selected = name == seedName
				Text(
					name,
					style = if (selected) SignerTypeface.LabelL else SignerTypeface.BodyM,
					color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.textSecondary,
					modifier = Modifier
						.fillMaxWidth()
						.clickable { onSelectSeed(name) }
						.padding(vertical = 8.dp),
				)
			}
		}
		Row(
			Modifier
				.fillMaxWidth()
				.padding(vertical = 4.dp),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.SpaceBetween,
		) {
			Text("Index", style = SignerTypeface.LabelS, color = MaterialTheme.colors.textSecondary)
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					"–",
					style = SignerTypeface.TitleS,
					modifier = Modifier
						.clickable { onIndex(keyIndex - 1) }
						.padding(8.dp),
				)
				Text(keyIndex.toString(), style = SignerTypeface.BodyL)
				Text(
					"+",
					style = SignerTypeface.TitleS,
					modifier = Modifier
						.clickable { onIndex(keyIndex + 1) }
						.padding(8.dp),
				)
			}
		}
		PrimaryButtonWide(
			label = if (bindingBusy) "Deriving…" else if (bound) "Use this key" else "Derive key",
			onClicked = onBind,
		)
		Spacer(Modifier.height(12.dp))
		SectionLabel("Device")
		FingerprintRow("Sign key", pub)
		MetaRow("Path", if (seedName.isBlank()) "—" else SezsDeviceDerivation.pathSign(keyIndex))
		FingerprintRow("Noise static", noise)
		MetaRow("Path", if (seedName.isBlank()) "—" else SezsDeviceDerivation.pathNoise(keyIndex))
		Spacer(Modifier.height(12.dp))
		SectionLabel("Grant")
		MetaRow("Hash", SezsDisplay.shortHex(grantHash), grantHash)
		MetaRow("Expiry", grantExpiry.ifBlank { "—" })
		MetaRow("Capability", grantCapability.ifBlank { "—" })
		MetaRow("Admin", SezsDisplay.shortHex(grantAdmin), grantAdmin)
		Spacer(Modifier.height(12.dp))
		SectionLabel("Desk")
		Row(
			Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			StatusPill(SignerRuntime.sessions > 0, desk)
			Text(
				if (SignerRuntime.running) "Stop radio" else "Start radio",
				style = SignerTypeface.LabelS,
				color = MaterialTheme.colors.textSecondary,
				modifier = Modifier
					.clickable { onConfirmRadio(if (SignerRuntime.running) "stop" else "start") }
					.padding(8.dp),
			)
		}
		MetaRow("Last seq", if (seq > 0 && pub.isNotBlank()) seq.toString() else "—")
		if (status.isNotBlank()) {
			Spacer(Modifier.height(12.dp))
			Text(status, style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		}
	}
}

@Composable
private fun HistoryPane(rows: List<SezsTicketHistory.Row>) {
	Column(
		Modifier
			.fillMaxSize()
			.verticalScroll(rememberScrollState())
			.padding(horizontal = 16.dp, vertical = 8.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		if (rows.isEmpty()) {
			Text("—", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
			return
		}
		rows.forEach { row ->
			HistoryCard(row)
		}
	}
}

@Composable
private fun HistoryCard(row: SezsTicketHistory.Row) {
	val admitted = row.result == "sent"
	Column(
		Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colors.fill6, RoundedCornerShape(8.dp))
			.padding(12.dp),
	) {
		Row(
			Modifier.fillMaxWidth(),
			horizontalArrangement = Arrangement.SpaceBetween,
			verticalAlignment = Alignment.CenterVertically,
		) {
			StatusPill(admitted, if (admitted) "Sent" else "Refused")
			Text(formatTime(row.atMs), style = SignerTypeface.LabelS, color = MaterialTheme.colors.textSecondary)
		}
		Spacer(Modifier.height(6.dp))
		MetaRow("Ticket", row.ticketTag)
		MetaRow("Seq", row.seq.toString())
		if (row.reason.isNotBlank()) {
			MetaRow("Reason", row.reason)
		}
	}
}

@Composable
private fun SezsSubTab(label: String, selected: Boolean, onClick: () -> Unit) {
	Text(
		label,
		style = if (selected) SignerTypeface.LabelL else SignerTypeface.BodyL,
		color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.textSecondary,
		modifier = Modifier
			.clickable(onClick = onClick)
			.padding(vertical = 8.dp),
	)
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

private fun formatTime(atMs: Long): String {
	if (atMs <= 0L) return "—"
	val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
	return fmt.format(java.util.Date(atMs))
}

internal fun dispatchTicket(
	context: Context,
	payload: String,
	grantHash: String,
	eventId: String,
	seq: Long,
): String {
	val tag = SezsTicketHistory.ticketTag(payload)
	val result: String
	val reason: String
	val usedSeq: Long
	if (grantHash.length != 64) {
		result = "refused"
		reason = "unpaired"
		usedSeq = seq
	} else {
		val material = UsherDeviceKey.tryLoad(context)
		if (material == null) {
			result = "refused"
			reason = "no-key"
			usedSeq = seq
		} else {
			val pub = UsherDeviceKey.hex(material.signPub)
			val text = TicketCanon.encodeFrame(
				grantHash = grantHash,
				deviceSignPubkey = pub,
				eventId = eventId.ifBlank { "speakez-sessions" },
				seq = seq,
				ts = System.currentTimeMillis() / 1000,
				payload = payload,
			)
			val sig = TicketCanon.signFrame(text, material.sign)
			val plain = TicketCanon.envelope(text, sig)
			if (SignerRuntime.notifyTicket(plain)) {
				result = "sent"
				reason = ""
				usedSeq = seq
				context.getSharedPreferences("usher-device", 0).edit().putLong("seq", seq + 1).apply()
			} else {
				result = "refused"
				reason = "no-session"
				usedSeq = seq
			}
		}
	}
	SezsTicketHistory.append(
		context,
		SezsTicketHistory.Row(
			atMs = System.currentTimeMillis(),
			seq = usedSeq,
			ticketTag = tag,
			result = result,
			reason = reason,
		),
	)
	return when {
		result == "sent" -> "Sent ticket frame $usedSeq."
		reason == "unpaired" -> "Pair this phone on the desk first."
		reason == "no-key" -> "Derive a device key first."
		else -> "No Noise session is ready."
	}
}

internal object SezsKeysetAccess {
	fun names(): List<String> =
		try {
			ServiceLocator.activityScope?.seedRepository?.getLastKnownSeedNames()?.toList().orEmpty()
		} catch (_: Exception) {
			emptyList()
		}

	suspend fun unlock(seedName: String): String? {
		val repo = ServiceLocator.activityScope?.seedRepository ?: return null
		return when (val result = repo.getSeedPhraseForceAuth(seedName)) {
			is RepoResult.Success -> result.result
			is RepoResult.Failure -> null
		}
	}
}
