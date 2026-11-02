package net.rotko.zigner.screens.settings.frost

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.frostDeleteWallet
import io.parity.signer.uniffi.frostLoadWallet
import io.parity.signer.uniffi.frostRenameWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.DisableScreenshots
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.accentGreen
import net.rotko.zigner.ui.theme.appliedStroke
import net.rotko.zigner.ui.theme.backgroundSecondary
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.networkAccent
import net.rotko.zigner.ui.theme.networkAccentTint
import net.rotko.zigner.ui.theme.red400
import net.rotko.zigner.ui.theme.red500
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary
import org.json.JSONObject
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Per-wallet multisig detail screen.
 *
 * Reached by short-tapping a row in the home MultisigSection (long-press goes
 * to the universal [FrostWalletListScreen]). Pulls full metadata via
 * frostLoadWallet — wallet ID, address, Orchard FVK, threshold, mainnet,
 * created date, optional relay URL.
 *
 * Sensitive viewing key (Orchard FVK) is hidden behind a reveal toggle and
 * tagged with a warning, per threat model — a leaked viewing key lets an
 * attacker decrypt every shielded note ever sent to this wallet.
 */
@Composable
fun FrostWalletDetailScreen(
	walletId: String,
	onBack: Callback,
	onSendToZafu: Callback,
	onDeleted: Callback,
) {
	// FVK appears revealed on this screen; block screenshots, screen-recorders,
	// and the Android recents thumbnail.
	DisableScreenshots()

	val ctx = LocalContext.current
	val scope = rememberCoroutineScope()

	var data by remember { mutableStateOf<FrostWalletDetailData?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	var toast by remember { mutableStateOf<String?>(null) }
	var fvkRevealed by remember { mutableStateOf(false) }
	var renameOpen by remember { mutableStateOf(false) }
	var confirmDelete by remember { mutableStateOf(false) }

	// Backup export
	var exportOpen by remember { mutableStateOf(false) }
	var pendingEnvelope by remember { mutableStateOf<String?>(null) }
	val saveLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/json")
	) { uri: Uri? ->
		val envelope = pendingEnvelope
		pendingEnvelope = null
		if (uri != null && envelope != null) {
			scope.launch {
				try {
					withContext(Dispatchers.IO) { writeFile(ctx, uri, envelope) }
					toast = "backup saved"
				} catch (e: Exception) {
					Timber.e(e, "[frost-backup] save failed")
					error = e.message ?: "save failed"
				}
			}
		}
	}

	fun reload() {
		scope.launch {
			try {
				val raw = withContext(Dispatchers.Default) { frostLoadWallet(walletId) }
				if (raw.isBlank()) {
					error = "Wallet not found or empty data"
					return@launch
				}
				data = try {
					FrostWalletDetailData.fromJson(walletId, raw)
				} catch (e: Exception) {
					// keep `raw` out of logs: it names the group and its address
					Timber.e(e, "[frost-detail] parse failed")
					throw Exception("Failed to parse wallet data: ${e.message}")
				}
				error = null
			} catch (e: Exception) {
				Timber.e(e, "[frost-detail] load failed for id: $walletId")
				error = e.message ?: "Failed to load wallet"
			}
		}
	}

	LaunchedEffect(walletId) { reload() }

	FrostWalletDetailContent(
		data = data,
		error = error,
		toast = toast,
		onToastShown = { toast = null },
		fvkRevealed = fvkRevealed,
		onToggleFvk = { fvkRevealed = !fvkRevealed },
		onCopy = { label, value ->
			copyToClipboard(ctx, value)
			toast = "$label copied"
		},
		onRenameTap = { renameOpen = true },
		onSendToZafu = onSendToZafu,
		onExportBackup = { exportOpen = true },
		onDeleteTap = { confirmDelete = true },
		onBack = onBack,
	)

	// ── Rename dialog ──
	if (renameOpen) {
		val current = data?.label ?: ""
		FrostRenameDialog(
			currentLabel = current,
			onConfirm = { newLabel ->
				renameOpen = false
				scope.launch {
					try {
						withContext(Dispatchers.Default) {
							frostRenameWallet(walletId, newLabel)
						}
						toast = "renamed to \"$newLabel\""
						reload()
					} catch (e: Exception) {
						Timber.e(e, "[frost-rename] failed")
						error = e.message ?: "rename failed"
					}
				}
			},
			onCancel = { renameOpen = false },
		)
	}

	// ── Export passphrase dialog ──
	val currentLabel = data?.label
	if (exportOpen && currentLabel != null) {
		FrostBackupDialog(
			title = "Export \"$currentLabel\"",
			subtitle = "Encrypts the FROST share with a passphrase you choose. Save the file somewhere safe — paper, USB drive, password manager.",
			confirmLabel = "Export",
			requireConfirmField = true,
			onConfirm = { passphrase ->
				exportOpen = false
				scope.launch {
					try {
						// auth gate owns the export; null = auth cancelled/failed
						val envelope = FrostExportUseCase.exportSingle(walletId, passphrase)
							?: return@launch
						pendingEnvelope = envelope
						val filename =
							"frost-backup-${sanitizeLabel(currentLabel)}-${ymdToday()}.json"
						saveLauncher.launch(filename)
					} catch (e: Exception) {
						Timber.e(e, "[frost-backup] export failed")
						error = e.message ?: "export failed"
					}
				}
			},
			onCancel = { exportOpen = false },
		)
	}

	// ── Delete confirm dialog ──
	if (confirmDelete) {
		FrostBackupDialog(
			title = "Delete \"${data?.label ?: ""}\"?",
			subtitle = "This permanently removes the FROST share from this device. You will not be able to participate in signing for this wallet again unless you restore from a backup.",
			confirmLabel = "Delete",
			requireConfirmField = false,
			onConfirm = {
				confirmDelete = false
				scope.launch {
					try {
						withContext(Dispatchers.Default) { frostDeleteWallet(walletId) }
						onDeleted()
					} catch (e: Exception) {
						Timber.e(e, "[frost-delete] failed")
						error = e.message ?: "delete failed"
					}
				}
			},
			onCancel = { confirmDelete = false },
		)
	}
}

@Composable
private fun FrostWalletDetailContent(
	data: FrostWalletDetailData?,
	error: String?,
	toast: String?,
	onToastShown: Callback,
	fvkRevealed: Boolean,
	onToggleFvk: Callback,
	onCopy: (label: String, value: String) -> Unit,
	onRenameTap: Callback,
	onSendToZafu: Callback,
	onExportBackup: Callback,
	onDeleteTap: Callback,
	onBack: Callback,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.statusBarsPadding(),
	) {
		ScreenHeaderClose(
			title = data?.label ?: "Multisig",
			onClose = onBack,
		)

		if (error != null) {
			Text(
				text = error,
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.red500,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
			)
		}
		if (toast != null) {
			Text(
				text = toast,
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.accentGreen,
				modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
			)
			LaunchedEffect(toast) {
				kotlinx.coroutines.delay(3000)
				onToastShown()
			}
		}

		when {
			data == null && error == null -> {
				Box(
					modifier = Modifier.weight(1f).fillMaxWidth(),
					contentAlignment = Alignment.Center,
				) {
					Text(
						text = "Loading…",
						style = SignerTypeface.BodyL,
						color = MaterialTheme.colors.textSecondary,
					)
				}
			}
			data != null -> {
				Column(
					modifier = Modifier
						.weight(1f)
						.verticalScroll(rememberScrollState()),
					verticalArrangement = Arrangement.spacedBy(12.dp),
				) {
					Spacer(modifier = Modifier.height(4.dp))
					IdentityCard(
						data = data,
						onRenameTap = onRenameTap,
					)
					DetailRow(
						label = "Wallet ID",
						value = data.walletId,
						onCopy = { onCopy("Wallet ID", data.walletId) },
						monospace = true,
						oneLine = false,
					)
					if (data.address.isNotEmpty()) {
						DetailRow(
							label = "Address",
							value = data.address,
							onCopy = { onCopy("Address", data.address) },
							monospace = true,
							oneLine = false,
						)
					}
					if (data.relayUrl.isNotEmpty()) {
						DetailRow(
							label = "Relay URL",
							value = data.relayUrl,
							onCopy = { onCopy("Relay URL", data.relayUrl) },
							monospace = false,
							oneLine = true,
						)
					}
					Text(
						text = "Created ${formatCreatedAt(data.createdAtSec)}",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
						modifier = Modifier.padding(horizontal = 24.dp),
					)
					if (data.orchardFvk.isNotEmpty()) {
						FvkRevealCard(
							fvk = data.orchardFvk,
							revealed = fvkRevealed,
							onToggle = onToggleFvk,
							onCopy = { onCopy("Viewing key", data.orchardFvk) },
						)
					}
					ActionButtons(
						onSendToZafu = onSendToZafu,
						onExportBackup = onExportBackup,
						onDeleteTap = onDeleteTap,
					)
					Spacer(modifier = Modifier.height(24.dp))
				}
			}
		}
	}
}

@Composable
private fun IdentityCard(
	data: FrostWalletDetailData,
	onRenameTap: Callback,
) {
	val accent = networkAccent("zcash")
	val shape = RoundedCornerShape(16.dp)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.clip(shape)
			.background(MaterialTheme.colors.backgroundSecondary)
			.border(
				width = 1.dp,
				color = MaterialTheme.colors.appliedStroke,
				shape = shape,
			),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(IntrinsicSize.Min),
		) {
			Box(
				modifier = Modifier
					.width(3.dp)
					.fillMaxHeight()
					.background(accent),
			)
			Column(
				modifier = Modifier
					.weight(1f)
					.padding(start = 14.dp, end = 14.dp, top = 16.dp, bottom = 16.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = "${data.minSigners}-of-${data.maxSigners}",
						style = SignerTypeface.TitleL,
						color = accent,
					)
					Spacer(modifier = Modifier.width(8.dp))
					Text(
						text = "threshold",
						style = SignerTypeface.BodyL,
						color = MaterialTheme.colors.textSecondary,
					)
					Spacer(modifier = Modifier.weight(1f))
					NetworkPillSmall(
						label = if (data.mainnet) "mainnet" else "testnet",
						accent = if (data.mainnet) accent else MaterialTheme.colors.textTertiary,
					)
				}
				Spacer(modifier = Modifier.height(8.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = data.label,
						style = SignerTypeface.TitleS,
						color = MaterialTheme.colors.primary,
						modifier = Modifier.weight(1f, fill = false),
					)
					Box(
						modifier = Modifier
							.padding(start = 8.dp)
							.size(28.dp)
							.clip(RoundedCornerShape(6.dp))
							.clickable(onClick = onRenameTap),
						contentAlignment = Alignment.Center,
					) {
						Icon(
							imageVector = Icons.Outlined.Edit,
							contentDescription = "Rename",
							tint = MaterialTheme.colors.textTertiary,
							modifier = Modifier.size(14.dp),
						)
					}
				}
			}
		}
	}
}

@Composable
private fun NetworkPillSmall(label: String, accent: Color) {
	Text(
		text = label,
		style = SignerTypeface.CaptionM,
		color = accent,
		modifier = Modifier
			.clip(RoundedCornerShape(6.dp))
			.background(accent.copy(alpha = 0.12f))
			.padding(horizontal = 8.dp, vertical = 3.dp),
	)
}

@Composable
private fun DetailRow(
	label: String,
	value: String,
	onCopy: Callback,
	monospace: Boolean,
	oneLine: Boolean,
) {
	Column(modifier = Modifier.padding(horizontal = 24.dp)) {
		Text(
			text = label,
			style = SignerTypeface.LabelS,
			color = MaterialTheme.colors.textSecondary,
		)
		Spacer(modifier = Modifier.height(4.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = value,
				style = if (monospace) {
					SignerTypeface.BodyM.copy(fontFamily = FontFamily.Monospace)
				} else {
					SignerTypeface.BodyM
				},
				color = MaterialTheme.colors.primary,
				maxLines = if (oneLine) 1 else Int.MAX_VALUE,
				modifier = Modifier.weight(1f),
			)
			Box(
				modifier = Modifier
					.padding(start = 8.dp)
					.size(32.dp)
					.clip(RoundedCornerShape(8.dp))
					.background(MaterialTheme.colors.fill6)
					.clickable(onClick = onCopy),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = Icons.Outlined.ContentCopy,
					contentDescription = "Copy $label",
					tint = MaterialTheme.colors.textSecondary,
					modifier = Modifier.size(16.dp),
				)
			}
		}
	}
}

@Composable
private fun FvkRevealCard(
	fvk: String,
	revealed: Boolean,
	onToggle: Callback,
	onCopy: Callback,
) {
	val shape = RoundedCornerShape(14.dp)
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.clip(shape)
			.background(networkAccentTint("zcash"))
			.padding(14.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			Icon(
				imageVector = Icons.Outlined.WarningAmber,
				contentDescription = null,
				tint = networkAccent("zcash"),
				modifier = Modifier.size(18.dp),
			)
			Spacer(modifier = Modifier.width(8.dp))
			Text(
				text = "Orchard viewing key",
				style = SignerTypeface.LabelM,
				color = MaterialTheme.colors.primary,
				modifier = Modifier.weight(1f),
			)
			Icon(
				imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
				contentDescription = if (revealed) "Hide viewing key" else "Reveal viewing key",
				tint = MaterialTheme.colors.textSecondary,
				modifier = Modifier
					.size(28.dp)
					.clip(RoundedCornerShape(6.dp))
					.clickable(onClick = onToggle)
					.padding(4.dp),
			)
		}
		Spacer(modifier = Modifier.height(4.dp))
		Text(
			text = "Anyone with this key can decrypt every shielded note ever sent to this wallet. Treat it as a secret, not a watch-only credential.",
			style = SignerTypeface.CaptionM,
			color = MaterialTheme.colors.textSecondary,
		)
		if (revealed) {
			Spacer(modifier = Modifier.height(10.dp))
			Row(verticalAlignment = Alignment.Top) {
				Text(
					text = fvk,
					style = SignerTypeface.BodyS.copy(fontFamily = FontFamily.Monospace),
					color = MaterialTheme.colors.primary,
					modifier = Modifier.weight(1f),
				)
				Box(
					modifier = Modifier
						.padding(start = 8.dp)
						.size(32.dp)
						.clip(RoundedCornerShape(8.dp))
						.background(MaterialTheme.colors.fill6)
						.clickable(onClick = onCopy),
					contentAlignment = Alignment.Center,
				) {
					Icon(
						imageVector = Icons.Outlined.ContentCopy,
						contentDescription = "Copy viewing key",
						tint = MaterialTheme.colors.textSecondary,
						modifier = Modifier.size(16.dp),
					)
				}
			}
		}
	}
}

@Composable
private fun ActionButtons(
	onSendToZafu: Callback,
	onExportBackup: Callback,
	onDeleteTap: Callback,
) {
	Column(
		modifier = Modifier.padding(horizontal = 16.dp),
		verticalArrangement = Arrangement.spacedBy(8.dp),
	) {
		PrimaryRowAction(
			label = "Send to Zafu",
			icon = Icons.Outlined.QrCode2,
			onClick = onSendToZafu,
		)
		GhostRowAction(
			label = "Export backup",
			icon = Icons.Outlined.FileDownload,
			onClick = onExportBackup,
		)
		GhostRowAction(
			label = "Delete wallet",
			icon = Icons.Outlined.Delete,
			onClick = onDeleteTap,
			danger = true,
		)
	}
}

@Composable
private fun PrimaryRowAction(
	label: String,
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	onClick: Callback,
) {
	val shape = RoundedCornerShape(14.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(52.dp)
			.clip(shape)
			.background(MaterialTheme.colors.primary)
			.clickable(onClick = onClick),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.Center,
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = MaterialTheme.colors.primaryVariant,
			modifier = Modifier.size(20.dp),
		)
		Spacer(modifier = Modifier.width(8.dp))
		Text(
			text = label,
			style = SignerTypeface.LabelM,
			color = MaterialTheme.colors.primaryVariant,
		)
	}
}

@Composable
private fun GhostRowAction(
	label: String,
	icon: androidx.compose.ui.graphics.vector.ImageVector,
	onClick: Callback,
	danger: Boolean = false,
) {
	val shape = RoundedCornerShape(14.dp)
	val tint = if (danger) MaterialTheme.colors.red400 else MaterialTheme.colors.primary
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.height(48.dp)
			.clip(shape)
			.background(MaterialTheme.colors.backgroundSecondary)
			.border(
				width = 1.dp,
				color = if (danger) MaterialTheme.colors.red400.copy(alpha = 0.4f)
				else MaterialTheme.colors.appliedStroke,
				shape = shape,
			)
			.clickable(onClick = onClick),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.Center,
	) {
		Icon(
			imageVector = icon,
			contentDescription = null,
			tint = tint,
			modifier = Modifier.size(18.dp),
		)
		Spacer(modifier = Modifier.width(8.dp))
		Text(
			text = label,
			style = SignerTypeface.LabelM,
			color = tint,
		)
	}
}

// ── data + helpers ──

internal data class FrostWalletDetailData(
	val walletId: String,
	val label: String,
	val minSigners: Int,
	val maxSigners: Int,
	val mainnet: Boolean,
	val createdAtSec: Long,
	val orchardFvk: String,
	val address: String,
	val relayUrl: String,
) {
	companion object {
		fun fromJson(walletId: String, rawJson: String): FrostWalletDetailData {
			val o = JSONObject(rawJson)
			return FrostWalletDetailData(
				walletId = walletId,
				label = o.optString("label", ""),
				minSigners = o.optInt("min_signers", 0),
				maxSigners = o.optInt("max_signers", 0),
				mainnet = o.optBoolean("mainnet", false),
				createdAtSec = o.optLong("created_at", 0L),
				orchardFvk = o.optString("orchard_fvk_uview", ""),
				address = o.optString("address", ""),
				relayUrl = o.optString("relay_url", ""),
			)
		}
	}
}

private fun copyToClipboard(ctx: Context, text: String) {
	val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
		?: return
	cm.setPrimaryClip(android.content.ClipData.newPlainText("zigner", text))
}

private fun formatCreatedAt(epochSec: Long): String {
	if (epochSec <= 0L) return "—"
	val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
	return df.format(Date(epochSec * 1000L))
}

// ── previews ──

@Preview(name = "light_loaded", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFFFFFFF)
@Preview(name = "dark_loaded", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0D0D12)
@Composable
private fun PreviewDetailLoaded() {
	SignerNewTheme {
		FrostWalletDetailContent(
			data = FrostWalletDetailData(
				walletId = "abcdef0123456789abcdef0123456789",
				label = "Treasury",
				minSigners = 2,
				maxSigners = 3,
				mainnet = true,
				createdAtSec = 1_700_000_000L,
				orchardFvk = "uview1qabc...xyz",
				address = "u1abcdefghij1234567890klmnopqr",
				relayUrl = "https://relay.example.org",
			),
			error = null,
			toast = null,
			onToastShown = {},
			fvkRevealed = false,
			onToggleFvk = {},
			onCopy = { _, _ -> },
			onRenameTap = {},
			onSendToZafu = {},
			onExportBackup = {},
			onDeleteTap = {},
			onBack = {},
		)
	}
}

@Preview(name = "light_loading", uiMode = Configuration.UI_MODE_NIGHT_NO, showBackground = true, backgroundColor = 0xFFFFFFFF)
@Preview(name = "dark_loading", uiMode = Configuration.UI_MODE_NIGHT_YES, showBackground = true, backgroundColor = 0xFF0D0D12)
@Composable
private fun PreviewDetailLoading() {
	SignerNewTheme {
		FrostWalletDetailContent(
			data = null,
			error = null,
			toast = null,
			onToastShown = {},
			fvkRevealed = false,
			onToggleFvk = {},
			onCopy = { _, _ -> },
			onRenameTap = {},
			onSendToZafu = {},
			onExportBackup = {},
			onDeleteTap = {},
			onBack = {},
		)
	}
}
