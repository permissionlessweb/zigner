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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.TextFieldColors
import net.rotko.zigner.ui.theme.accentPink
import net.rotko.zigner.ui.theme.red500
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
import net.rotko.zigner.R
import androidx.compose.ui.res.dimensionResource
import net.rotko.zigner.components.NetworkLabelWithIcon
import net.rotko.zigner.components.base.PrimaryButton
import net.rotko.zigner.ui.theme.fill12
import net.rotko.zigner.ui.theme.accentGreen
import net.rotko.zigner.ui.theme.pink500
import net.rotko.zigner.ui.theme.red500fill12
import androidx.compose.material.icons.outlined.ContentCopy

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
				onDeleted = { openId = null; tab = 0 },
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
	onDeleted: () -> Unit,
) {
	val context = LocalContext.current
	var renaming by remember(key.id) { mutableStateOf(false) }
	var draft by remember(key.id) { mutableStateOf(key.name) }
	Column(Modifier.fillMaxSize()) {
		ScreenHeaderClose(
			title = key.name,
			subtitle = if (key.chainId.isBlank()) "Terp" else "Terp · ${key.chainId}",
			onClose = onBack,
		)
		if (renaming) {
			Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
				OutlinedTextField(
					value = draft,
					onValueChange = { draft = it.take(40) },
					singleLine = true,
					textStyle = SignerTypeface.BodyL,
					placeholder = { Text("Display name", style = SignerTypeface.BodyL) },
					colors = signerFieldColors(),
					modifier = Modifier.weight(1f),
				)
				Text(
					"Save",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.primary,
					modifier = Modifier.padding(start = 12.dp).clickable {
						if (draft.isNotBlank()) {
							SignerRuntime.renameKey(context, key.id, draft)
							renaming = false
						}
					},
				)
				Text(
					"Cancel",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.textSecondary,
					modifier = Modifier.padding(start = 12.dp).clickable { draft = key.name; renaming = false },
				)
			}
		} else {
			Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
				Text(key.name, style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary)
				Box(
					modifier = Modifier
						.padding(start = 8.dp)
						.size(28.dp)
						.clip(RoundedCornerShape(6.dp))
						.clickable { draft = key.name; renaming = true },
					contentAlignment = androidx.compose.ui.Alignment.Center,
				) {
					Icon(
						imageVector = Icons.Outlined.Edit,
						contentDescription = "Rename ${key.name}",
						tint = MaterialTheme.colors.textTertiary,
						modifier = Modifier.size(14.dp),
					)
				}
			}
		}
		KeyChips(key)
		KeyActions(key, onScan)
		BatteryCard()
		Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
			PageTab("Status", tab == 0) { onTab(0) }
			PageTab("History", tab == 1) { onTab(1) }
			PageTab("Chain", tab == 2) { onTab(2) }
		}
		Column(
			Modifier
				.weight(1f)
				.verticalScroll(rememberScrollState())
				.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			when (tab) {
				0 -> ValidatorStatusPage(key)
				1 -> ValidatorHistoryPage(key)
				else -> ValidatorChainPage(key, onScan, onNew, onDeleted)
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
private fun Chip(text: String, color: androidx.compose.ui.graphics.Color = MaterialTheme.colors.textTertiary) {
	// Same look as NetworkLabel on the public key page (fill12, inner frame radius, CaptionM).
	Text(
		text,
		color = color,
		style = SignerTypeface.CaptionM,
		maxLines = 1,
		modifier = Modifier
			.background(MaterialTheme.colors.fill12, RoundedCornerShape(dimensionResource(id = R.dimen.innerFramesCornerRadius)))
			.padding(horizontal = 8.dp, vertical = 2.dp),
	)
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun KeyChips(key: ValidatorKey) {
	val context = LocalContext.current
	val started = SignerRuntime.isKeyStarted(context, key.id)
	val legacy = started && SignerRuntime.activeKey(context) == key.id
	androidx.compose.foundation.layout.FlowRow(
		horizontalArrangement = Arrangement.spacedBy(6.dp),
		verticalArrangement = Arrangement.spacedBy(6.dp),
		modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
	) {
		NetworkLabelWithIcon("Terp", "", iconHeight = 20.dp, style = SignerTypeface.CaptionM)
		Chip(key.chainId.ifBlank { "no chain" })
		Chip(key.slot.ifBlank { "no slot" })
		Chip(keyIdHex(key.pubHex).ifBlank { "—" })
		when (SignerRuntime.nodeValidator) {
			true -> Chip("validator")
			false -> Chip("not a validator", MaterialTheme.colors.red500)
			null -> {}
		}
		if (legacy) Chip("legacy 0x10")
	}
}

@Composable
private fun StatusPill(on: Boolean, text: String) {
	val tint = if (on) MaterialTheme.colors.accentGreen else MaterialTheme.colors.textTertiary
	Row(
		verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
		modifier = Modifier
			.background(MaterialTheme.colors.fill6, RoundedCornerShape(50))
			.padding(horizontal = 10.dp, vertical = 4.dp),
	) {
		Box(Modifier.size(8.dp).background(tint, RoundedCornerShape(50)))
		Text(text, style = SignerTypeface.LabelS, color = MaterialTheme.colors.primary, modifier = Modifier.padding(start = 6.dp))
	}
}

/** Start/Stop and Scan side by side, above the tabs, so neither hides at the bottom. */
@Composable
private fun KeyActions(key: ValidatorKey, onScan: () -> Unit) {
	val context = LocalContext.current
	val started = SignerRuntime.isKeyStarted(context, key.id)
	val permit = rememberLauncherForActivityResult(
		ActivityResultContracts.RequestMultiplePermissions(),
	) { granted ->
		if (granted.values.all { it }) SignerRuntime.startKey(context, key.id)
		else SignerRuntime.status = "allow Bluetooth and notifications to start"
	}
	Row(
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
	) {
		PrimaryButton(
			label = if (started) "Stop key" else "Start key",
			modifier = Modifier.weight(1f),
			activeBackground = if (started) MaterialTheme.colors.fill12 else MaterialTheme.colors.pink500,
			activeText = if (started) MaterialTheme.colors.primary else androidx.compose.ui.graphics.Color.White,
			onClicked = {
				if (started) {
					SignerRuntime.stopKey(context, key.id)
				} else {
					val missing = runtimeBluetoothPermissions().filter {
						context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
					}
					if (missing.isNotEmpty()) permit.launch(missing.toTypedArray()) else SignerRuntime.startKey(context, key.id)
				}
			},
		)
		PrimaryButton(
			label = "Scan",
			modifier = Modifier.weight(1f),
			activeBackground = MaterialTheme.colors.fill12,
			activeText = MaterialTheme.colors.primary,
			onClicked = onScan,
		)
	}
}

/** One tap to exempt zigner from battery optimization, so Doze and app standby do not cut signing. */
@Composable
private fun BatteryCard() {
	val context = LocalContext.current
	var exempt by remember { mutableStateOf(BatteryExemption.isExempt(context)) }
	LaunchedEffect(Unit) {
		while (!exempt) { delay(1500); exempt = BatteryExemption.isExempt(context) }
	}
	if (exempt) return
	Row(
		Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 4.dp)
			.clip(RoundedCornerShape(12.dp))
			.background(MaterialTheme.colors.fill6)
			.clickable { BatteryExemption.request(context) }
			.padding(horizontal = 12.dp, vertical = 10.dp),
		horizontalArrangement = Arrangement.spacedBy(10.dp),
		verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
	) {
		Text("\u26A1", style = MaterialTheme.typography.body1)
		Text("Keep signing when asleep", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.primary, modifier = Modifier.weight(1f))
		Text("Allow", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.pink500)
	}
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
	Column(
		Modifier
			.fillMaxWidth()
			.background(MaterialTheme.colors.fill6, RoundedCornerShape(16.dp))
			.padding(horizontal = 16.dp, vertical = 4.dp),
		content = content,
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
	val started = SignerRuntime.isKeyStarted(context, key.id)
	val seen = SignerRuntime.blockSeenAt
	val mine = key.chainId.isBlank() || SignerRuntime.nodeChain.isBlank() || SignerRuntime.nodeChain == key.chainId
	val last = rowsFor(key).firstOrNull { it.result == "signed" }
	Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
		val lastMs = last?.atMs ?: 0L
		val live = SignHistory.isLive(started, lastMs, System.currentTimeMillis() + (now - now))
		StatusPill(live, SignHistory.liveLabel(started, lastMs, System.currentTimeMillis() + (now - now)))
		StatusPill(SignerRuntime.running, if (SignerRuntime.running) "Radio on · ${SignerRuntime.armedKeys(context).size}" else "Radio off")
	}
	Card {
		Text("Last signed", style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary, modifier = Modifier.padding(top = 12.dp))
		Text(last?.height?.toString() ?: "—", style = SignerTypeface.TitleL, color = MaterialTheme.colors.primary)
		Text(
			if (last == null) "nothing signed yet" else "${last.at} · ${stepName(last.step)} · ${last.latencyMs} ms",
			style = SignerTypeface.BodyM, color = MaterialTheme.colors.textSecondary,
			modifier = Modifier.padding(bottom = 12.dp),
		)
	}
	Card {
		DetailLine("Node height", if (!mine || SignerRuntime.blockHeight == 0L) "—" else SignerRuntime.blockHeight.toString())
		DetailLine("Lag", if (!mine || SignerRuntime.blockLagMs < 0) "—" else formatLag(SignerRuntime.blockLagMs))
		DetailLine("Updated", if (seen == 0L) "—" else "${(now - seen) / 1000}s ago", last = true)
	}
	if (SignerRuntime.status.isNotBlank()) {
		Text(SignerRuntime.status, style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary)
	}
}

private fun formatLag(ms: Long): String = when {
	ms < 10_000 -> "$ms ms"
	ms < 3_600_000 -> "${ms / 60_000} min ${(ms / 1000) % 60} s"
	else -> "${ms / 3_600_000} h ${(ms / 60_000) % 60} min"
}

private fun stepName(step: Int) = when (step) {
	0 -> "proposal"; 1 -> "prevote"; 2 -> "precommit"; 3 -> "vote extension"; else -> "step $step"
}

@Composable
private fun DetailLine(label: String, value: String, last: Boolean = false, copy: Boolean = false) {
	val clip = androidx.compose.ui.platform.LocalClipboardManager.current
	Row(
		verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
		modifier = Modifier
			.fillMaxWidth()
			.defaultMinSize(minHeight = 48.dp)
			.then(if (copy && value != "—") Modifier.clickable {
				clip.setText(androidx.compose.ui.text.AnnotatedString(value))
				SignerRuntime.status = "copied $label"
			} else Modifier)
			.padding(vertical = 8.dp),
	) {
		Text(label, style = SignerTypeface.BodyM, color = MaterialTheme.colors.textTertiary)
		Spacer(Modifier.weight(1f))
		Text(
			value,
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.primary,
			textAlign = TextAlign.End,
			maxLines = 1,
			overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
			modifier = Modifier.padding(start = 12.dp).weight(2f, fill = false),
		)
		if (copy && value != "—") {
			Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy $label", tint = MaterialTheme.colors.textTertiary,
				modifier = Modifier.padding(start = 8.dp).size(16.dp))
		}
	}
	if (!last) SignerDivider()
}

@Composable
private fun ValidatorHistoryPage(key: ValidatorKey) {
	val mine = rowsFor(key).take(100)
	if (mine.isEmpty()) {
		Column(
			Modifier.fillMaxWidth().padding(vertical = 48.dp),
			horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
			verticalArrangement = Arrangement.spacedBy(4.dp),
		) {
			Text("No signatures yet", style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
			Text("Votes this key signs appear here.", style = SignerTypeface.BodyM, color = MaterialTheme.colors.textTertiary)
		}
		return
	}
	Card {
		mine.forEachIndexed { i, row -> SignRow(row, last = i == mine.lastIndex) }
	}
}

@Composable
private fun ValidatorChainPage(key: ValidatorKey, onScan: () -> Unit, onNew: () -> Unit, onDeleted: () -> Unit) {
	val context = LocalContext.current
	var confirming by remember(key.id) { mutableStateOf(false) }
	var typed by remember(key.id) { mutableStateOf("") }
	val phoneFp = remember {
		runCatching { KeyExport.fingerprint(X25519.base(ValidatorKeys.noiseSeed(context))) }.getOrDefault("")
	}
	Card {
		DetailLine("Chain id", key.chainId.ifBlank { "—" }, copy = true)
		DetailLine("KMS slot", key.slot.ifBlank { "—" }, copy = true)
		DetailLine("Key id", keyIdHex(key.pubHex).ifBlank { "—" }, copy = true)
		DetailLine("Consensus pubkey", key.pubHex.ifBlank { "—" }, copy = true)
		DetailLine("Consensus address", SignerRuntime.consensusAddr.ifBlank { "—" }, copy = true)
		DetailLine("Granter", SignerRuntime.granterAccount.ifBlank { "—" }, copy = true)
		DetailLine("Phone identity", phoneFp.ifBlank { "—" }, copy = true, last = true)
	}
	SecondaryButtonWide(label = "New validator", withBackground = true, onClicked = onNew)
	Spacer(Modifier.height(24.dp))
	val chain = key.chainId.ifBlank { "no chain" }
	if (!confirming) {
		SecondaryButtonWide(label = "Delete this key", textColor = MaterialTheme.colors.red500, onClicked = { typed = ""; confirming = true })
	} else {
		Text(
			"Delete \"${key.name}\" (Terp · $chain)? This wipes its consensus key and high-water mark from this phone. Other keys are untouched. Type the name to confirm.",
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.red500,
		)
		OutlinedTextField(
			value = typed,
			onValueChange = { typed = it },
			singleLine = true,
			textStyle = SignerTypeface.BodyL,
			placeholder = { Text("Type ${key.name}", style = SignerTypeface.BodyL) },
			colors = signerFieldColors(focus = MaterialTheme.colors.red500),
			modifier = Modifier.fillMaxWidth(),
		)
		PrimaryButtonWide(label = "Delete ${key.name}", isEnabled = typed.trim() == key.name, activeBackground = MaterialTheme.colors.red500, onClicked = {
			SignerRuntime.deleteKey(context, key.id)
			confirming = false
			onDeleted()
		})
		SecondaryButtonWide(label = "Keep this key", onClicked = { confirming = false })
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
private fun ResultBadge(result: String) {
	val ok = result == "signed"
	Text(
		if (ok) "Signed" else "Refused",
		style = SignerTypeface.CaptionM,
		color = if (ok) MaterialTheme.colors.accentGreen else MaterialTheme.colors.red500,
		modifier = Modifier
			.background(if (ok) MaterialTheme.colors.fill12 else MaterialTheme.colors.red500fill12, RoundedCornerShape(50))
			.padding(horizontal = 8.dp, vertical = 2.dp),
	)
}

@Composable
private fun SignRow(row: SignedRow, last: Boolean = false) {
	Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
		Column(Modifier.weight(1f)) {
			Text(row.height.toString(), style = SignerTypeface.BodyL, color = MaterialTheme.colors.primary)
			Text(
				"round ${row.round} · ${stepName(row.step)}" + if (row.result == "signed") " · ${row.latencyMs} ms" else "",
				style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary,
			)
		}
		Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
			ResultBadge(row.result)
			Text(row.at, style = SignerTypeface.CaptionM, color = MaterialTheme.colors.textTertiary, modifier = Modifier.padding(top = 2.dp))
		}
	}
	if (!last) SignerDivider()
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

/** Outlined field colors from the app theme, so light and dark both read right. */
@Composable
private fun signerFieldColors(focus: androidx.compose.ui.graphics.Color = MaterialTheme.colors.accentPink): TextFieldColors =
	TextFieldDefaults.outlinedTextFieldColors(
		textColor = MaterialTheme.colors.primary,
		backgroundColor = MaterialTheme.colors.fill6,
		cursorColor = focus,
		focusedBorderColor = focus,
		unfocusedBorderColor = MaterialTheme.colors.appliedStroke,
		placeholderColor = MaterialTheme.colors.textTertiary,
	)
