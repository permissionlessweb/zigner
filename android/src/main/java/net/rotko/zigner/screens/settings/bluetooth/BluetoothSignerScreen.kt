package net.rotko.zigner.screens.settings.bluetooth

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.material.Divider
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.appliedStroke
import net.rotko.zigner.ui.theme.backgroundSecondary
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary
import java.security.Signature

/**
 * Controls for the remote signer. Back closes this screen only.
 * The foreground service keeps the Bluetooth session.
 */
@Composable
fun BluetoothSignerScreen(onBack: () -> Unit) {
	val context = LocalContext.current
	var scanning by remember { mutableStateOf(false) }
	var kmsPub by remember { mutableStateOf<ByteArray?>(null) }
	var openId by remember { mutableStateOf<String?>(null) }
	var qrText by remember { mutableStateOf<String?>(null) }
	var typedFingerprint by remember { mutableStateOf("") }
	var fingerprintMatches by remember { mutableStateOf(false) }
	var exportMenu by remember { mutableStateOf(false) }
	val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		scanning = granted
	}
	LaunchedEffect(Unit) {
		SignerRuntime.refreshKeys(context)
		SignerRuntime.refreshGranter(context)
	}
	Column(
		Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		ScreenHeaderClose(title = "Export signer key", onClose = onBack)
		Text(
			"This screen sends one signer key set back to the KMS. A fresh ephemeral key seals it to the KMS identity on the zcinti page. The Bluetooth session key is not used.",
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.onBackground,
		)
		if (scanning) {
			ZcintiQrScan { text ->
				scanning = false
				try {
					kmsPub = KeyExport.kmsPublic(text)
				} catch (err: Exception) {
					SignerRuntime.status = err.message ?: "scan the KMS identity QR"
				}
			}
			SecondaryButtonWide(label = "Cancel", onClicked = { scanning = false })
		} else {
			ValidatorMenu(
				keys = SignerRuntime.keys,
				selected = SignerRuntime.keys.firstOrNull { it.id == openId },
				expanded = exportMenu,
				onExpand = { exportMenu = true },
				onDismiss = { exportMenu = false },
				onPick = { id ->
					openId = id
					exportMenu = false
					qrText = null
					fingerprintMatches = false
				},
			)
			SecondaryButtonWide(label = "Scan KMS identity", onClicked = {
				if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
					scanning = true
				} else {
					camera.launch(Manifest.permission.CAMERA)
				}
			})
			val chosen = SignerRuntime.keys.firstOrNull { it.id == openId }
			val scanned = kmsPub
			if (chosen != null && scanned != null) {
				val expected = KeyExport.fingerprint(scanned)
				Text(
					"Type the fingerprint from the zcinti page. It is not shown here. A different code means this QR is not that KMS.",
					style = SignerTypeface.BodyM,
					color = MaterialTheme.colors.textSecondary,
				)
				OutlinedTextField(
					value = typedFingerprint,
					onValueChange = {
						typedFingerprint = it
						fingerprintMatches = false
						qrText = null
					},
					label = { Text("Fingerprint from the page") },
					modifier = Modifier.fillMaxWidth(),
				)
				SecondaryButtonWide(label = "Check fingerprint", onClicked = {
					fingerprintMatches = typedFingerprint.trim().lowercase() == expected
					if (!fingerprintMatches) {
						SignerRuntime.status = "fingerprint does not match this QR"
						qrText = null
					}
				})
				if (fingerprintMatches) {
					PrimaryButtonWide(label = "Seal this key", onClicked = {
						try {
							val payload = KeyExport.box(context, chosen.id, scanned)
							qrText = payload
						} catch (_: Exception) {
							SignerRuntime.status = "could not seal this key set"
						}
					})
				}
			}
			if (qrText != null) {
				Text(
					"Show this QR to the zcinti page. Only that KMS identity can open it.",
					style = SignerTypeface.BodyM,
					color = MaterialTheme.colors.onBackground,
				)
				RecoveryQr(qrText!!)
			}
		}
		Text(SignerRuntime.status, style = SignerTypeface.BodyM, color = MaterialTheme.colors.onBackground)
	}
}

@Composable
private fun RecoveryQr(payload: String) {
	var image by remember(payload) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
	LaunchedEffect(payload) {
		try {
			val png = io.parity.signer.uniffi.encodeToQr(payload.encodeToByteArray().map { it.toUByte() }, false)
			val bytes = png.map { it.toByte() }.toByteArray()
			image = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
		} catch (_: Exception) {
			image = null
		}
	}
	if (image != null) {
		androidx.compose.foundation.Image(bitmap = image!!, contentDescription = "Recovery QR", modifier = Modifier.height(220.dp).fillMaxWidth())
	}
}

@Composable
private fun SignerKeySets() {
	val context = LocalContext.current
	var scanning by remember { mutableStateOf(false) }
	var scanBind by remember { mutableStateOf<String?>(null) }
	var openId by remember { mutableStateOf<String?>(null) }
	var tab by remember { mutableStateOf(0) }
	val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
		scanning = granted
		if (!granted) SignerRuntime.status = "allow the camera to scan the chain QR"
	}
	LaunchedEffect(Unit) {
		SignerRuntime.refreshKeys(context)
		SignerRuntime.refreshGranter(context)
	}
	fun beginScan(bind: String?) {
		scanBind = bind
		if (context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
			scanning = true
		} else {
			camera.launch(Manifest.permission.CAMERA)
		}
	}
	val keys = SignerRuntime.keys
	val open = keys.firstOrNull { it.id == openId }
	Column(Modifier.fillMaxSize()) {
		when {
			scanning -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					"Scan the chain QR on the zcinti page. Only Terp is accepted.",
					style = SignerTypeface.BodyM,
					color = MaterialTheme.colors.onBackground,
				)
				ZcintiQrScan { text ->
					scanning = false
					try {
						SignerRuntime.acceptQr(context, text, scanBind)
					} catch (err: Exception) {
						SignerRuntime.status = err.message ?: "that QR was not a Terp chain"
					}
				}
				SecondaryButtonWide(label = "Cancel scan", onClicked = { scanning = false })
			}
			open != null -> ValidatorKeyPage(
				key = open,
				tab = tab,
				onTab = { tab = it },
				onBack = { openId = null },
				onScan = { beginScan(open.id) },
				onNew = { beginScan(null) },
			)
			else -> Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
				Text("Validators", style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
				if (keys.isEmpty()) {
					Text(
						"No validator yet. Scan a chain QR to create the first key set.",
						style = SignerTypeface.BodyM,
						color = MaterialTheme.colors.textSecondary,
					)
				}
				keys.forEach { key ->
					Surface(
						color = MaterialTheme.colors.fill6,
						shape = RoundedCornerShape(16.dp),
						modifier = Modifier.fillMaxWidth().clickable {
							SignerRuntime.selectKey(context, key.id)
							tab = 0
							openId = key.id
						},
					) {
						Row(
							Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
							verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
						) {
							Column(Modifier.weight(1f)) {
								Text(key.name, style = SignerTypeface.BodyL, color = MaterialTheme.colors.primary)
								Text(
									if (key.chainId.isBlank()) "Terp · no chain yet" else "Terp · ${key.chainId}",
									style = SignerTypeface.CaptionM,
									color = MaterialTheme.colors.textSecondary,
								)
							}
							Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = MaterialTheme.colors.textTertiary)
						}
					}
				}
				SecondaryButtonWide(label = "New validator", onClicked = { beginScan(null) })
				if (SignerRuntime.status.isNotBlank()) {
					Text(SignerRuntime.status, style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
				}
			}
		}
	}
}

@Composable
private fun ValidatorKeyPage(
	key: ValidatorKey,
	tab: Int,
	onTab: (Int) -> Unit,
	onBack: () -> Unit,
	onScan: () -> Unit,
	onNew: () -> Unit,
) {
	Column(Modifier.fillMaxSize()) {
		ScreenHeaderClose(
			title = key.name,
			subtitle = if (key.chainId.isBlank()) "Terp" else "Terp · ${key.chainId}",
			onClose = onBack,
		)
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
			PageTab("Status", tab == 0) { onTab(0) }
			PageTab("History", tab == 1) { onTab(1) }
			PageTab("Chain", tab == 2) { onTab(2) }
		}
		Column(
			Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(bottom = 16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			when (tab) {
				0 -> ValidatorStatusPage(key)
				1 -> ValidatorHistoryPage(key)
				else -> ValidatorChainPage(key, onScan, onNew)
			}
		}
	}
}

@Composable
private fun ValidatorMenu(
	keys: List<ValidatorKey>,
	selected: ValidatorKey?,
	expanded: Boolean,
	onExpand: () -> Unit,
	onDismiss: () -> Unit,
	onPick: (String) -> Unit,
) {
	Box(Modifier.fillMaxWidth()) {
		Surface(
			shape = RoundedCornerShape(20.dp),
			color = MaterialTheme.colors.backgroundSecondary,
			modifier = Modifier
				.fillMaxWidth()
				.border(1.dp, MaterialTheme.colors.appliedStroke, RoundedCornerShape(20.dp))
				.clickable(onClick = onExpand),
		) {
			Row(
				Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
				verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
			) {
				Column(Modifier.weight(1f)) {
					Text(selected?.name ?: "Validator", style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
					Text(
						if (selected == null || selected.chainId.isBlank()) "Terp" else "Terp · ${selected.chainId}",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textSecondary,
					)
				}
				Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colors.textTertiary, modifier = Modifier.size(20.dp))
			}
		}
		DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, modifier = Modifier.fillMaxWidth()) {
			keys.forEach { key ->
				DropdownMenuItem(onClick = { onPick(key.id) }) {
					Column {
						Text(key.name, style = SignerTypeface.BodyL, color = MaterialTheme.colors.primary)
						Text(
							if (key.chainId.isBlank()) "No chain yet" else key.chainId,
							style = SignerTypeface.CaptionM,
							color = MaterialTheme.colors.textSecondary,
						)
					}
				}
			}
		}
	}
}

@Composable
private fun PageTab(label: String, selected: Boolean, onClick: () -> Unit) {
	Text(
		label,
		style = if (selected) SignerTypeface.LabelL else SignerTypeface.BodyL,
		color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.textSecondary,
		modifier = Modifier.clickable(onClick = onClick).padding(vertical = 8.dp),
	)
}

@Composable
private fun ValidatorStatusPage(key: ValidatorKey) {
	val context = LocalContext.current
	var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(1000)
			now = SystemClock.elapsedRealtime()
		}
	}
	val seen = SignerRuntime.blockSeenAt
	val mine = key.chainId.isBlank() || SignerRuntime.nodeChain.isBlank() || SignerRuntime.nodeChain == key.chainId
	val updated = if (seen == 0L) "—" else "${(now - seen) / 1000}s ago"
	val height = if (!mine || SignerRuntime.blockHeight == 0L) "—" else SignerRuntime.blockHeight.toString()
	val lag = if (!mine || SignerRuntime.blockLagMs < 0) "—" else "${SignerRuntime.blockLagMs} ms"
	val role = when (SignerRuntime.nodeValidator) {
		true -> "Validator"
		false -> "Not a validator"
		null -> "—"
	}
	val runningThis = SignerRuntime.running && SignerRuntime.selected == key.id
	val permit = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions(),
	) { granted ->
		if (granted.values.all { it }) {
			SignerRuntime.startKey(context, key.id)
		} else {
			SignerRuntime.status = "allow Bluetooth and notifications, then start the signer"
		}
	}
	Column(
		Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colors.fill6, RoundedCornerShape(16.dp))
			.padding(horizontal = 16.dp),
	) {
		DetailLine("Network", "Terp")
		DetailLine("Chain", key.chainId.ifBlank { "—" })
		DetailLine("Height", height)
		DetailLine("Lag", lag)
		DetailLine("Updated", updated)
		DetailLine("Role", role)
		DetailLine("Consensus", SignerRuntime.consensusAddr.ifBlank { "—" })
		DetailLine("Granter", SignerRuntime.granterAccount.ifBlank { "—" })
		DetailLine("Radio", if (runningThis) "On" else "Off", last = true)
	}
	PrimaryButtonWide(
		label = if (runningThis) "Stop this signer" else "Start this signer",
		onClicked = {
			if (runningThis) {
				SignerRuntime.stop(context)
			} else {
				val missing = runtimeBluetoothPermissions().filter {
					context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
				}
				if (missing.isNotEmpty()) {
					permit.launch(missing.toTypedArray())
				} else {
					SignerRuntime.startKey(context, key.id)
				}
			}
		},
	)
	Text(SignerRuntime.status, style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
}

@Composable
private fun DetailLine(label: String, value: String, last: Boolean = false) {
	Row(
		verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
		modifier = Modifier.defaultMinSize(minHeight = 48.dp).padding(vertical = 8.dp),
	) {
		Text(label, style = SignerTypeface.BodyL, color = MaterialTheme.colors.textTertiary)
		Spacer(Modifier.weight(1f))
		Text(
			value,
			style = SignerTypeface.BodyL,
			color = MaterialTheme.colors.primary,
			textAlign = androidx.compose.ui.text.style.TextAlign.End,
			modifier = Modifier.padding(start = 16.dp),
		)
	}
	if (!last) SignerDivider(sidePadding = 0.dp)
}

@Composable
private fun ValidatorHistoryPage(key: ValidatorKey) {
	val mine = rowsFor(key).take(100)
	if (mine.isEmpty()) {
		Text("No block signed for this validator", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
		return
	}
	Column(Modifier.fillMaxWidth().height(280.dp).verticalScroll(rememberScrollState())) {
		SignHeader()
		mine.forEach { SignRow(it) }
	}
}

@Composable
private fun ValidatorChainPage(key: ValidatorKey, onScan: () -> Unit, onNew: () -> Unit) {
	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text(
			if (key.chainId.isBlank()) "This validator has no chain yet." else "Connected to ${key.chainId}.",
			style = SignerTypeface.BodyL,
			color = MaterialTheme.colors.onBackground,
		)
		Text(
			"Scan the chain QR on the zcinti page. This key set keeps its own consensus key.",
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.textSecondary,
		)
		SecondaryButtonWide(
			label = if (key.chainId.isBlank()) "Scan chain QR" else "Scan this chain again",
			onClicked = onScan,
		)
		SecondaryButtonWide(label = "New validator", onClicked = onNew)
	}
}

private fun rowsFor(key: ValidatorKey): List<SignedRow> {
	return SignerRuntime.rows.filter {
		it.height != 0L && (it.keyId == key.id || (it.keyId.isEmpty() && key.id == SignerRuntime.keys.firstOrNull()?.id))
	}
}

@Composable
private fun SessionLine() {
	var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
	LaunchedEffect(Unit) {
		while (true) {
			delay(1000)
			now = SystemClock.elapsedRealtime()
		}
	}
	val seen = SignerRuntime.blockSeenAt
	val age = if (seen == 0L) "no block session yet" else "updated ${(now - seen) / 1000}s ago"
	ChainSession(age)
}

@Composable
private fun ChainSession(age: String? = null) {
	var now by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
	if (age == null) {
		LaunchedEffect(Unit) {
			while (true) {
				delay(1000)
				now = SystemClock.elapsedRealtime()
			}
		}
	}
	val seen = SignerRuntime.blockSeenAt
	val shownAge = age ?: if (seen == 0L) "no session yet" else "updated ${(now - seen) / 1000}s ago"
	val chain = if (SignerRuntime.nodeChain.isBlank()) "chain —" else "chain ${SignerRuntime.nodeChain}"
	val height = if (SignerRuntime.blockHeight == 0L) "height —" else "height ${SignerRuntime.blockHeight}"
	val lag = if (SignerRuntime.blockLagMs < 0) "lag —" else "lag ${SignerRuntime.blockLagMs} ms"
	val context = LocalContext.current
	val phoneFp = remember {
		runCatching {
			KeyExport.fingerprint(X25519.base(ValidatorKeys.noiseSeed(context)))
		}.getOrDefault("")
	}
	Text(
		"Phone identity $phoneFp",
		style = SignerTypeface.LabelM,
		color = MaterialTheme.colors.primary,
	)
	Text(
		"KMS session",
		style = SignerTypeface.LabelM,
		color = MaterialTheme.colors.primary,
	)
	Text(
		"$chain · $height · $lag · $shownAge",
		style = SignerTypeface.TitleS,
		color = MaterialTheme.colors.onBackground,
	)
	val prompt = when (SignerRuntime.nodeValidator) {
		null -> "Validator state arrives with the block session."
		true -> "Validator ${SignerRuntime.consensusAddr}."
		false -> "Not a validator ${SignerRuntime.consensusAddr}. On the KMS, scan the authorize QR with this phone's account scan."
	}
	Text(prompt, style = SignerTypeface.BodyL, color = MaterialTheme.colors.primary)
	Text(
		if (SignerRuntime.granterAccount.isBlank()) {
			"Granter comes from the selected Terp account on this phone."
		} else {
			"Granter ${SignerRuntime.granterAccount}"
		},
		style = SignerTypeface.BodyM,
		color = MaterialTheme.colors.onBackground,
	)
	Text(
		"Scan on this tab is the account scan. Point it at the authorize QR on the KMS.",
		style = SignerTypeface.BodyM,
		color = MaterialTheme.colors.textSecondary,
	)
}

@Composable
internal fun SignerSummary() {
	Spacer(Modifier.height(8.dp))
	SessionLine()
	val latest = SignerRuntime.rows.firstOrNull { it.height != 0L }
	Text(
		if (latest == null) "No vote signed yet" else "Last vote ${latest.height} · ${latest.latencyMs} ms",
		style = SignerTypeface.BodyM,
		color = MaterialTheme.colors.textSecondary,
	)
}

@Composable
private fun SignHeader() {
	Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
		Text("Height", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary, modifier = Modifier.weight(1.2f))
		Text("Round", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
		Text("Step", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary, modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
		Text("Latency", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
		Text("Time", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
	}
}

@Composable
private fun SignRow(row: SignedRow) {
	Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
		Text(
			if (row.height == 0L) "key" else row.height.toString(),
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.primary,
			modifier = Modifier.weight(1.2f),
		)
		Text(row.round.toString(), style = SignerTypeface.BodyM, color = MaterialTheme.colors.primary, modifier = Modifier.weight(0.8f), textAlign = TextAlign.End)
		Text(row.step.toString(), style = SignerTypeface.BodyM, color = MaterialTheme.colors.primary, modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
		Text("${row.latencyMs} ms", style = SignerTypeface.BodyM, color = MaterialTheme.colors.primary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
		Text(row.at, style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textSecondary, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
	}
	Divider(color = MaterialTheme.colors.onBackground.copy(alpha = 0.12f))
}

@Composable
fun RemoteSignerHomeTab() {
	val context = LocalContext.current
	LaunchedEffect(Unit) {
		SignerRuntime.refreshKeys(context)
		SignerRuntime.refreshGranter(context)
	}
	SignerKeySets()
}

private fun runtimeBluetoothPermissions(): Array<String> {
	val base = if (Build.VERSION.SDK_INT >= 31) {
		arrayOf(
			Manifest.permission.BLUETOOTH_CONNECT,
			Manifest.permission.BLUETOOTH_SCAN,
			Manifest.permission.BLUETOOTH_ADVERTISE,
		)
	} else {
		arrayOf(Manifest.permission.BLUETOOTH)
	}
	return if (Build.VERSION.SDK_INT >= 33) {
		base + Manifest.permission.POST_NOTIFICATIONS
	} else {
		base
	}
}

private fun radioOn(adapter: BluetoothAdapter?, context: android.content.Context): Boolean {
	if (Build.VERSION.SDK_INT >= 31 &&
		context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
	) {
		return false
	}
	return try {
		adapter?.isEnabled == true
	} catch (_: SecurityException) {
		false
	}
}
