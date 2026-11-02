package net.rotko.zigner.screens.settings.frost

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.GroupWork
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.FrostWalletSummaryFfi
import io.parity.signer.uniffi.frostDeleteWallet
import io.parity.signer.uniffi.frostListWallets
import io.parity.signer.uniffi.frostRenameWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*
import timber.log.Timber

@Composable
fun FrostWalletListScreen(
	onBack: Callback,
	onSendWalletToZafu: (walletId: String) -> Unit = {},
	onWalletTap: (walletId: String) -> Unit = {},
) {
	val ctx = LocalContext.current
	var wallets by remember { mutableStateOf<List<FrostWalletSummaryFfi>>(emptyList()) }
	var error by remember { mutableStateOf<String?>(null) }
	var toast by remember { mutableStateOf<String?>(null) }
	var confirmDeleteId by remember { mutableStateOf<String?>(null) }
	var renameTarget by remember { mutableStateOf<FrostWalletSummaryFfi?>(null) }
	val scope = rememberCoroutineScope()

	// Backup-export flow: passphrase entry → SAF write
	var exportTarget by remember { mutableStateOf<FrostWalletSummaryFfi?>(null) }
	var pendingEnvelope by remember { mutableStateOf<String?>(null) }
	var pendingFilename by remember { mutableStateOf("frost-backup.json") }
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

	// Backup-import flow: SAF read → passphrase entry → frost_import_backup_envelope
	var importEnvelopeJson by remember { mutableStateOf<String?>(null) }
	val openLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri: Uri? ->
		if (uri != null) {
			scope.launch {
				try {
					val text = withContext(Dispatchers.IO) { readFile(ctx, uri) }
					importEnvelopeJson = text
				} catch (e: Exception) {
					Timber.e(e, "[frost-backup] read failed")
					error = e.message ?: "read failed"
				}
			}
		}
	}

	fun loadWallets() {
		scope.launch {
			try {
				val result = withContext(Dispatchers.Default) {
					frostListWallets()
				}
				wallets = result
				error = null
			} catch (e: Exception) {
				error = e.message ?: "Failed to load wallets"
			}
		}
	}

	LaunchedEffect(Unit) {
		loadWallets()
	}

	FrostWalletListContent(
		wallets = wallets,
		error = error,
		toast = toast,
		onToastShown = { toast = null },
		confirmDeleteId = confirmDeleteId,
		onDeleteTap = { id -> confirmDeleteId = id },
		onDeleteConfirm = {
			val id = confirmDeleteId ?: return@FrostWalletListContent
			scope.launch {
				try {
					withContext(Dispatchers.Default) { frostDeleteWallet(id) }
					confirmDeleteId = null
					loadWallets()
				} catch (e: Exception) {
					error = e.message ?: "Failed to delete wallet"
				}
			}
		},
		onDeleteCancel = { confirmDeleteId = null },
		onExportBackup = { wallet -> exportTarget = wallet },
		onSendToZafu = { wallet -> onSendWalletToZafu(wallet.walletId) },
		onRenameTap = { wallet -> renameTarget = wallet },
		onWalletTap = { wallet -> onWalletTap(wallet.walletId) },
		onRestoreFromFile = { openLauncher.launch(arrayOf("application/json")) },
		onBack = onBack,
	)

	// ── Export passphrase dialog ──
	exportTarget?.let { wallet ->
		FrostBackupDialog(
			title = "Export \"${wallet.label}\"",
			subtitle = "Encrypts the FROST share with a passphrase you choose. Save the file somewhere safe — paper, USB drive, password manager.",
			confirmLabel = "Export",
			requireConfirmField = true,
			onConfirm = { passphrase ->
				exportTarget = null
				scope.launch {
					try {
						// auth gate owns the export; null = auth cancelled/failed
						val envelope = FrostExportUseCase.exportSingle(wallet.walletId, passphrase)
							?: return@launch
						pendingEnvelope = envelope
						pendingFilename = "frost-backup-${sanitizeLabel(wallet.label)}-${ymdToday()}.json"
						saveLauncher.launch(pendingFilename)
					} catch (e: Exception) {
						Timber.e(e, "[frost-backup] export failed")
						error = e.message ?: "export failed"
					}
				}
			},
			onCancel = { exportTarget = null },
		)
	}

	// ── Rename dialog ──
	renameTarget?.let { wallet ->
		FrostRenameDialog(
			currentLabel = wallet.label,
			onConfirm = { newLabel ->
				renameTarget = null
				scope.launch {
					try {
						withContext(Dispatchers.Default) {
							frostRenameWallet(wallet.walletId, newLabel)
						}
						toast = "renamed to \"$newLabel\""
						loadWallets()
					} catch (e: Exception) {
						Timber.e(e, "[frost-rename] failed")
						error = e.message ?: "rename failed"
					}
				}
			},
			onCancel = { renameTarget = null },
		)
	}

	// ── Import passphrase dialog ──
	importEnvelopeJson?.let { json ->
		FrostBackupDialog(
			title = "Restore backup",
			subtitle = "Enter the passphrase used when this file was exported.",
			confirmLabel = "Restore",
			requireConfirmField = false,
			onConfirm = { passphrase ->
				importEnvelopeJson = null
				scope.launch {
					try {
						val envelopeType = org.json.JSONObject(json).optString("type", "")
						if (envelopeType == "frost-share-batch-backup") {
							val resultJson = FrostExportUseCase.importAll(json, passphrase)
								?: return@launch
							val result = org.json.JSONObject(resultJson)
							val imported = result.optInt("imported", 0)
							val skipped = result.optInt("skipped", 0)
							toast = buildString {
								append("$imported wallet${if (imported == 1) "" else "s"} restored")
								if (skipped > 0) append(" ($skipped already present)")
							}
							Timber.d("[frost-backup] batch import: imported=$imported skipped=$skipped")
						} else {
							val newId = FrostExportUseCase.importSingle(json, passphrase)
								?: return@launch
							toast = "wallet restored"
							Timber.d("[frost-backup] imported wallet=$newId")
						}
						loadWallets()
					} catch (e: Exception) {
						Timber.e(e, "[frost-backup] import failed")
						error = e.message ?: "import failed"
					}
				}
			},
			onCancel = { importEnvelopeJson = null },
		)
	}
}

/**
 * Stateless layout. All side effects live in [FrostWalletListScreen]; this
 * composable just renders the current state. Used by both the live screen and
 * the @Preview functions so both code paths share the same render code.
 */
@Composable
private fun FrostWalletListContent(
	wallets: List<FrostWalletSummaryFfi>,
	error: String?,
	toast: String?,
	onToastShown: Callback,
	confirmDeleteId: String?,
	onDeleteTap: (String) -> Unit,
	onDeleteConfirm: Callback,
	onDeleteCancel: Callback,
	onExportBackup: (FrostWalletSummaryFfi) -> Unit,
	onSendToZafu: (FrostWalletSummaryFfi) -> Unit,
	onRenameTap: (FrostWalletSummaryFfi) -> Unit,
	onWalletTap: (FrostWalletSummaryFfi) -> Unit,
	onRestoreFromFile: Callback,
	onBack: Callback,
) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.statusBarsPadding(),
	) {
		ScreenHeaderClose(
			title = "Multisig Wallets",
			onClose = onBack,
		)

		if (error != null) {
			Text(
				text = error,
				style = SignerTypeface.BodyL,
				color = MaterialTheme.colors.red500,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
			)
		}
		if (toast != null) {
			Text(
				text = toast,
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.accentGreen,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
			)
			LaunchedEffect(toast) {
				kotlinx.coroutines.delay(3000)
				onToastShown()
			}
		}

		if (wallets.isEmpty() && error == null) {
			FrostEmptyState(
				modifier = Modifier
					.weight(1f)
					.fillMaxWidth(),
				onRestoreFromFile = onRestoreFromFile,
			)
		} else {
			Column(
				modifier = Modifier
					.weight(1f)
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				Spacer(modifier = Modifier.height(4.dp))
				wallets.forEach { wallet ->
					FrostWalletCard(
						wallet = wallet,
						isConfirmingDelete = confirmDeleteId == wallet.walletId,
						onDeleteTap = { onDeleteTap(wallet.walletId) },
						onDeleteConfirm = onDeleteConfirm,
						onDeleteCancel = onDeleteCancel,
						onExportBackup = { onExportBackup(wallet) },
						onSendToZafu = { onSendToZafu(wallet) },
						onRenameTap = { onRenameTap(wallet) },
						onClick = { onWalletTap(wallet) },
					)
				}
				SecondaryButtonWide(
					label = "Restore from file",
					modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
					onClicked = onRestoreFromFile,
				)
				Spacer(modifier = Modifier.height(24.dp))
			}
		}
	}
}

/**
 * Empty-state card for the multisig list. Big neutral icon, clear identity,
 * single CTA. Mirrors the empty state on the home screen.
 */
@Composable
private fun FrostEmptyState(
	modifier: Modifier = Modifier,
	onRestoreFromFile: Callback,
) {
	val accent = networkAccent("zcash")
	Column(
		modifier = modifier.padding(horizontal = 16.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center,
	) {
		Box(
			modifier = Modifier
				.size(72.dp)
				.clip(CircleShape)
				.background(networkAccentTint("zcash")),
			contentAlignment = Alignment.Center,
		) {
			Icon(
				imageVector = Icons.Outlined.GroupWork,
				contentDescription = null,
				tint = accent,
				modifier = Modifier.size(36.dp),
			)
		}
		Spacer(modifier = Modifier.height(16.dp))
		Text(
			text = "No multisig wallets",
			style = SignerTypeface.TitleM,
			color = MaterialTheme.colors.primary,
			textAlign = TextAlign.Center,
		)
		Spacer(modifier = Modifier.height(8.dp))
		Text(
			text = "Run a FROST DKG ceremony with your co-signers to create one, or restore from an encrypted backup file.",
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.textSecondary,
			textAlign = TextAlign.Center,
			modifier = Modifier.padding(horizontal = 24.dp),
		)
		Spacer(modifier = Modifier.height(24.dp))
		SecondaryButtonWide(
			label = "Restore from file",
			modifier = Modifier.padding(horizontal = 24.dp),
			onClicked = onRestoreFromFile,
		)
	}
}

/**
 * Per-wallet card. Gold accent stripe (FROST is Zcash-only today), label with
 * inline rename, threshold + network pill, truncated wallet ID, and an action
 * row at the bottom that flips to a confirm prompt during a destructive op.
 */
@Composable
private fun FrostWalletCard(
	wallet: FrostWalletSummaryFfi,
	isConfirmingDelete: Boolean,
	onDeleteTap: Callback,
	onDeleteConfirm: Callback,
	onDeleteCancel: Callback,
	onExportBackup: Callback,
	onSendToZafu: Callback,
	onRenameTap: Callback,
	onClick: Callback,
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
				.height(IntrinsicSize.Min)
				.clickable(onClick = onClick),
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
					.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = wallet.label,
						style = SignerTypeface.TitleS,
						color = MaterialTheme.colors.primary,
						modifier = Modifier.weight(1f, fill = false),
					)
					Box(
						modifier = Modifier
							.padding(start = 6.dp)
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
					Spacer(modifier = Modifier.weight(1f))
					NetworkPill(
						label = if (wallet.mainnet) "mainnet" else "testnet",
						accent = if (wallet.mainnet) accent else MaterialTheme.colors.textTertiary,
					)
				}
				Spacer(modifier = Modifier.height(6.dp))
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = "${wallet.minSigners}-of-${wallet.maxSigners}",
						style = SignerTypeface.LabelM,
						color = accent,
					)
					Spacer(modifier = Modifier.width(6.dp))
					Text(
						text = "threshold",
						style = SignerTypeface.BodyM,
						color = MaterialTheme.colors.textSecondary,
					)
				}
				Spacer(modifier = Modifier.height(2.dp))
				Text(
					text = wallet.walletId.take(16) + "…",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary,
				)
			}
		}

		Spacer(modifier = Modifier.height(2.dp))
		Box(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 14.dp)
				.height(1.dp)
				.background(MaterialTheme.colors.appliedStroke),
		)

		if (isConfirmingDelete) {
			Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
				Text(
					text = "Delete this wallet? This cannot be undone.",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.red500,
				)
				Spacer(modifier = Modifier.height(8.dp))
				Row(
					modifier = Modifier.fillMaxWidth(),
					horizontalArrangement = Arrangement.spacedBy(8.dp),
				) {
					SecondaryButtonWide(
						label = "Cancel",
						modifier = Modifier.weight(1f),
						onClicked = onDeleteCancel,
					)
					SecondaryButtonWide(
						label = "Delete",
						modifier = Modifier.weight(1f),
						onClicked = onDeleteConfirm,
					)
				}
			}
		} else {
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.padding(horizontal = 8.dp, vertical = 6.dp),
				horizontalArrangement = Arrangement.spacedBy(4.dp),
			) {
				IconActionButton(
					icon = Icons.Outlined.QrCode2,
					label = "Send to Zafu",
					onClick = onSendToZafu,
					modifier = Modifier.weight(1f),
				)
				IconActionButton(
					icon = Icons.Outlined.FileDownload,
					label = "Backup",
					onClick = onExportBackup,
					modifier = Modifier.weight(1f),
				)
				IconActionButton(
					icon = Icons.Outlined.Delete,
					label = "Delete",
					onClick = onDeleteTap,
					tint = MaterialTheme.colors.red400,
					modifier = Modifier.weight(1f),
				)
			}
		}
	}
}

/**
 * Small accent-tinted pill — mainnet uses the chain accent, anything else
 * (testnet, etc.) gets the neutral tertiary tint so mainnet stands out.
 */
@Composable
private fun NetworkPill(label: String, accent: androidx.compose.ui.graphics.Color) {
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

// ── helpers ──

@Preview(
	name = "light_empty", group = "themes",
	uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark_empty", group = "themes",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF0D0D12,
)
@Composable
private fun PreviewFrostWalletListEmpty() {
	SignerNewTheme {
		FrostWalletListContent(
			wallets = emptyList(),
			error = null,
			toast = null,
			onToastShown = {},
			confirmDeleteId = null,
			onDeleteTap = {},
			onDeleteConfirm = {},
			onDeleteCancel = {},
			onExportBackup = {},
			onSendToZafu = {},
			onRenameTap = {},
			onWalletTap = {},
			onRestoreFromFile = {},
			onBack = {},
		)
	}
}

@Preview(
	name = "light_populated", group = "themes",
	uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark_populated", group = "themes",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF0D0D12,
)
@Composable
private fun PreviewFrostWalletListPopulated() {
	SignerNewTheme {
		FrostWalletListContent(
			wallets = listOf(
				FrostWalletSummaryFfi(
					walletId = "abcdef0123456789abcdef0123456789",
					label = "Treasury",
					minSigners = 2.toUShort(),
					maxSigners = 3.toUShort(),
					mainnet = true,
					createdAt = 1700000000uL,
				),
				FrostWalletSummaryFfi(
					walletId = "1234567890abcdef1234567890abcdef",
					label = "Test Wallet",
					minSigners = 3.toUShort(),
					maxSigners = 5.toUShort(),
					mainnet = false,
					createdAt = 1700100000uL,
				),
			),
			error = null,
			toast = null,
			onToastShown = {},
			confirmDeleteId = null,
			onDeleteTap = {},
			onDeleteConfirm = {},
			onDeleteCancel = {},
			onExportBackup = {},
			onSendToZafu = {},
			onRenameTap = {},
			onWalletTap = {},
			onRestoreFromFile = {},
			onBack = {},
		)
	}
}
