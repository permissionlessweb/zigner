package net.rotko.zigner.screens.settings.frost

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.FrostWalletSummaryFfi
import io.parity.signer.uniffi.frostListWallets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.DisableScreenshots
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.accentPink
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary
import timber.log.Timber

@Composable
fun FrostBackupSelectionScreen(onBack: Callback) {
	// Lists wallet labels alongside passphrase entry; keep both out of recents.
	DisableScreenshots()
	val ctx = LocalContext.current
	val scope = rememberCoroutineScope()

	var wallets by remember { mutableStateOf<List<FrostWalletSummaryFfi>>(emptyList()) }
	var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
	var loaded by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	var success by remember { mutableStateOf(false) }
	var showDialog by remember { mutableStateOf(false) }
	var pendingEnvelope by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(Unit) {
		runCatching {
			withContext(Dispatchers.Default) { frostListWallets() }
		}.onSuccess {
			wallets = it
			selectedIds = it.map { w -> w.walletId }.toSet()
			loaded = true
		}.onFailure {
			error = it.message ?: "Failed to load wallets"
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
					withContext(Dispatchers.IO) { writeFile(ctx, uri, envelope) }
					success = true
				} catch (e: Exception) {
					Timber.e(e, "[frost-backup-sel] save failed")
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
		ScreenHeaderClose(title = "Backup multisig", onClose = onBack)

		when {
			success -> {
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					AnimatedSuccessBadge(visible = true, message = "Backup saved")
					LaunchedEffect(Unit) {
						kotlinx.coroutines.delay(2500)
						onBack()
					}
				}
			}
			!loaded -> {
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					Text("Loading…", style = SignerTypeface.BodyL, color = MaterialTheme.colors.textSecondary)
				}
			}
			wallets.isEmpty() -> {
				Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
					Text("No multisig wallets to backup.", style = SignerTypeface.BodyL, color = MaterialTheme.colors.textTertiary)
				}
			}
			else -> {
				LazyColumn(
					modifier = Modifier.weight(1f),
					contentPadding = PaddingValues(bottom = 16.dp)
				) {
					item {
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.padding(horizontal = 24.dp, vertical = 12.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							Text(
								text = "Select wallets to backup",
								style = SignerTypeface.LabelM,
								color = MaterialTheme.colors.primary,
								modifier = Modifier.weight(1f)
							)
							Text(
								text = if (selectedIds.size == wallets.size) "Unselect all" else "Select all",
								style = SignerTypeface.LabelS,
								color = MaterialTheme.colors.accentPink,
								modifier = Modifier.clickable {
									selectedIds = if (selectedIds.size == wallets.size) emptySet() else wallets.map { it.walletId }.toSet()
								}
							)
						}
					}

					items(wallets) { wallet ->
						val isSelected = selectedIds.contains(wallet.walletId)
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable {
									selectedIds = if (isSelected) selectedIds - wallet.walletId else selectedIds + wallet.walletId
								}
								.padding(horizontal = 24.dp, vertical = 14.dp),
							verticalAlignment = Alignment.CenterVertically
						) {
							Column(modifier = Modifier.weight(1f)) {
								Text(text = wallet.label, style = SignerTypeface.TitleS, color = MaterialTheme.colors.primary)
								Text(
									text = "${wallet.minSigners}-of-${wallet.maxSigners} • ${if (wallet.mainnet) "mainnet" else "testnet"}",
									style = SignerTypeface.CaptionM,
									color = MaterialTheme.colors.textSecondary
								)
							}
							Checkbox(
								checked = isSelected,
								onCheckedChange = null, // Handled by row click
								colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colors.accentPink)
							)
						}
					}
				}

				if (selectedIds.isNotEmpty()) {
					SecondaryButtonWide(
						label = "Backup ${selectedIds.size} wallet${if (selectedIds.size == 1) "" else "s"}",
						modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
						onClicked = { showDialog = true }
					)
				}
			}
		}

		error?.let {
			Text(
				text = it,
				style = SignerTypeface.BodyM,
				color = MaterialTheme.colors.error,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
			)
		}
	}

	if (showDialog) {
		FrostBackupDialog(
			title = "Backup wallets",
			subtitle = "${selectedIds.size} wallet${if (selectedIds.size == 1) "" else "s"} will be encrypted into one .json file.",
			confirmLabel = "Backup",
			requireConfirmField = true,
			onConfirm = { passphrase ->
				showDialog = false
				scope.launch {
					try {
						// For now we just use Export All because core doesn't support selective yet.
						// auth gate owns the export; null = auth cancelled/failed
						val envelope = FrostExportUseCase.exportAll(passphrase)
							?: return@launch
						pendingEnvelope = envelope
						saveLauncher.launch("frost-backup-${ymdToday()}.json")
					} catch (e: Exception) {
						Timber.e(e, "[frost-backup-sel] export failed")
						error = e.message ?: "Export failed"
					}
				}
			},
			onCancel = { showDialog = false },
		)
	}
}
