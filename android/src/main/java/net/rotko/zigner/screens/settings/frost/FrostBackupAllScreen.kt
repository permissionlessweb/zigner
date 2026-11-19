package net.rotko.zigner.screens.settings.frost

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.frostListWallets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.DisableScreenshots
import net.rotko.zigner.ui.theme.*
import timber.log.Timber

/** Batch encrypted backup of every FROST multisig wallet on this device.
 *  Single passphrase encrypts a `frost-share-batch-backup` envelope; user
 *  picks where to save via SAF. */
@Composable
fun FrostBackupAllScreen(onBack: Callback) {
	// Passphrase entry + count of wallets; keep out of recents.
	DisableScreenshots()
	val ctx = LocalContext.current
	val scope = rememberCoroutineScope()

	var walletCount by remember { mutableStateOf(0) }
	var loaded by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	var success by remember { mutableStateOf(false) }
	var showDialog by remember { mutableStateOf(false) }
	var pendingEnvelope by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(Unit) {
		runCatching {
			withContext(Dispatchers.Default) { frostListWallets() }
		}.onSuccess {
			walletCount = it.size
			loaded = true
		}.onFailure {
			error = it.message ?: "Failed to count wallets"
			loaded = true
		}
	}

	val saveLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.CreateDocument("application/json")
	) { uri: Uri? ->
		val envelope = pendingEnvelope
		pendingEnvelope = null
		if (uri != null && envelope != null) {
			scope.launch {
				try {
					withContext(Dispatchers.IO) { writeBackupFile(ctx, uri, envelope) }
					success = true
				} catch (e: Exception) {
					Timber.e(e, "[frost-backup-all] save failed")
					error = e.message ?: "Save failed"
				}
			}
		}
	}

	Column(
		modifier = Modifier
			.fillMaxSize()
			.background(MaterialTheme.colors.background)
			.statusBarsPadding(),
	) {
		ScreenHeaderClose(title = "Backup all multisig", onClose = onBack)

		Box(
			modifier = Modifier
				.weight(1f)
				.fillMaxWidth()
				.padding(horizontal = 24.dp, vertical = 16.dp),
			contentAlignment = Alignment.Center,
		) {
			if (success) {
				AnimatedSuccessBadge(visible = true, message = "Backup saved")
				LaunchedEffect(Unit) {
					kotlinx.coroutines.delay(2500)
					success = false
				}
			} else {
				Column(horizontalAlignment = Alignment.CenterHorizontally) {
					when {
						!loaded -> Text(
							"Loading…",
							style = SignerTypeface.BodyL,
							color = MaterialTheme.colors.textSecondary,
						)
						walletCount == 0 -> Text(
							"No multisig wallets to backup.",
							style = SignerTypeface.BodyL,
							color = MaterialTheme.colors.textTertiary,
						)
						else -> {
							Icon(
								imageVector = Icons.Outlined.Archive,
								contentDescription = null,
								tint = MaterialTheme.colors.primary,
								modifier = Modifier.size(48.dp),
							)
							Spacer(modifier = Modifier.height(12.dp))
							Text(
								text = "$walletCount multisig wallet${if (walletCount == 1) "" else "s"}",
								style = SignerTypeface.TitleS,
								color = MaterialTheme.colors.primary,
							)
							Spacer(modifier = Modifier.height(8.dp))
							Text(
								text = "Encrypts every wallet's FROST share into one .json file. Pick a save location after entering a passphrase.",
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.textTertiary,
							)
							Spacer(modifier = Modifier.height(16.dp))
							Text(
								text = "⚠ this passphrase cannot be reset. Losing it means the backup is unusable.",
								style = SignerTypeface.CaptionM,
								color = MaterialTheme.colors.red400,
							)
						}
					}

					error?.let {
						Spacer(modifier = Modifier.height(12.dp))
						Text(it, style = SignerTypeface.BodyL, color = MaterialTheme.colors.red500)
					}
				}
			}
		}

		if (loaded && walletCount > 0 && !success) {
			SecondaryButtonWide(
				label = "Export all backup",
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
				onClicked = { showDialog = true },
			)
		}
	}

	if (showDialog) {
		FrostBackupDialog(
			title = "Export all multisig",
			subtitle = "$walletCount wallet${if (walletCount == 1) "" else "s"} will be encrypted into one .json file.",
			confirmLabel = "Export",
			requireConfirmField = true,
			onConfirm = { passphrase ->
				showDialog = false
				scope.launch {
					try {
						// auth gate owns the export; null = auth cancelled/failed
						val envelope = FrostExportUseCase.exportAll(passphrase)
							?: return@launch
						pendingEnvelope = envelope
						saveLauncher.launch("frost-backup-all-${ymdToday()}.json")
					} catch (e: Exception) {
						Timber.e(e, "[frost-backup-all] export failed")
						error = e.message ?: "Export failed"
					}
				}
			},
			onCancel = { showDialog = false },
		)
	}
}

private fun writeBackupFile(ctx: Context, uri: Uri, text: String) {
	ctx.contentResolver.openOutputStream(uri)?.use { os ->
		os.write(text.toByteArray(Charsets.UTF_8))
	} ?: throw IllegalStateException("could not open output stream for $uri")
}
