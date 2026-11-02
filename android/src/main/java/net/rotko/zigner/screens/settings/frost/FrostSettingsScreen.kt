package net.rotko.zigner.screens.settings.frost

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.screens.settings.general.SettingsElement
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.accentGreen
import timber.log.Timber

@Composable
fun FrostSettingsScreen(
	onBack: Callback,
	onBackup: Callback,
	onBackupToKey: Callback,
) {
	val ctx = LocalContext.current
	val scope = rememberCoroutineScope()
	var toast by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }
	var importEnvelopeJson by remember { mutableStateOf<String?>(null) }
	var importAgeText by remember { mutableStateOf<String?>(null) }

	val seedNames = remember { ServiceLocator.seedStorage.getSeedNames().toList() }

	/** Restore an age backup with `seedName`, reporting the same summary. */
	fun restoreFromAge(armored: String, seedName: String) {
		scope.launch {
			try {
				val resultJson = FrostExportUseCase.importAllFromAge(seedName, armored)
					?: return@launch // auth cancelled; the auth layer already said so
				val result = org.json.JSONObject(resultJson)
				val imported = result.optInt("imported", 0)
				val skipped = result.optInt("skipped", 0)
				toast = buildString {
					append("$imported wallet${if (imported == 1) "" else "s"} restored")
					if (skipped > 0) append(" ($skipped already present)")
				}
				error = null
			} catch (e: Exception) {
				Timber.e(e, "[frost-settings] age restore failed")
				error = e.message ?: "restore failed"
			}
		}
	}

	val openLauncher = rememberLauncherForActivityResult(
		ActivityResultContracts.OpenDocument()
	) { uri: Uri? ->
		if (uri != null) {
			scope.launch {
				try {
					val text = withContext(Dispatchers.IO) { readFile(ctx, uri) }
					// Dispatch on what the file IS, rather than making the user
					// remember which of two ways they backed up months ago.
					if (text.trimStart().startsWith(AGE_ARMOR_HEADER)) {
						if (seedNames.size == 1) {
							restoreFromAge(text, seedNames.first())
						} else {
							// Ambiguous: only one of these seeds can open it,
							// and trying each would force-auth once per seed.
							importAgeText = text
						}
					} else {
						importEnvelopeJson = text
					}
				} catch (e: Exception) {
					Timber.e(e, "[frost-settings] read failed")
					error = e.message ?: "read failed"
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
		ScreenHeaderClose(title = "Multisig", onClose = onBack)

		if (error != null) {
			Text(
				text = error!!,
				style = SignerTypeface.BodyM,
				color = MaterialTheme.colors.error,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
			)
		}
		if (toast != null) {
			Text(
				text = toast!!,
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.accentGreen,
				modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
			)
			LaunchedEffect(toast) {
				kotlinx.coroutines.delay(3000)
				toast = null
			}
		}

		SettingsElement(
			name = "Backup wallets",
			onClick = onBackup
		)
		SettingsElement(
			name = "Backup to a key",
			onClick = onBackupToKey
		)
		SettingsElement(
			name = "Restore from file",
			// Both backup formats, because the file identifies itself and the
			// user should not have to recall which way they exported.
			onClick = { openLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
		)
	}

	importAgeText?.let { armored ->
		FrostSeedPickerDialog(
			seedNames = seedNames,
			onPick = { name ->
				importAgeText = null
				restoreFromAge(armored, name)
			},
			onCancel = { importAgeText = null },
		)
	}

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
						} else {
							FrostExportUseCase.importSingle(json, passphrase)
								?: return@launch
							toast = "Wallet restored"
						}
						error = null
					} catch (e: Exception) {
						Timber.e(e, "[frost-settings] restore failed")
						error = e.message ?: "restore failed"
					}
				}
			},
			onCancel = { importEnvelopeJson = null },
		)
	}
}
