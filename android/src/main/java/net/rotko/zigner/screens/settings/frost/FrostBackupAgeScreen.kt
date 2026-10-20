package net.rotko.zigner.screens.settings.frost

import android.content.Context
import androidx.compose.ui.graphics.RectangleShape
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.parity.signer.uniffi.frostListWallets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.components.base.ScreenHeaderClose
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.DisableScreenshots
import net.rotko.zigner.ui.theme.*
import timber.log.Timber

/**
 * First line of an ASCII-armored age file. Used to tell the two backup
 * formats apart on restore, so the user does not have to remember which way
 * they exported months ago.
 */
internal const val AGE_ARMOR_HEADER = "-----BEGIN AGE ENCRYPTED FILE-----"

/**
 * Back up every FROST share encrypted to public keys instead of a passphrase.
 *
 * The difference that matters is not the cipher - both paths are fine - it is
 * what the user must not lose. A passphrase is a second secret, and forgetting
 * it is one of the commonest ways a backup turns out to be worthless. It also
 * fails silently: you find out at restore time, which is the worst possible
 * moment. Encrypting to this device's seed-derived key instead means the 24
 * words already being kept are what opens the file.
 *
 * The share itself is still not derivable from the seed - FROST shares come
 * out of the DKG with their own entropy, which is what makes the threshold
 * worth having. This changes what must be kept, not what can be regenerated.
 *
 * Adding co-signers' keys is the point of the recipient list. A backup only
 * this seed can open dies at the same moment the seed does, which is precisely
 * the correlated failure a threshold exists to avoid.
 */
@Composable
fun FrostBackupAgeScreen(onBack: Callback) {
	// Wallet labels and recipient keys on screen; keep out of recents.
	DisableScreenshots()
	val ctx = LocalContext.current
	val scope = rememberCoroutineScope()

	val seedNames = remember { ServiceLocator.seedStorage.getSeedNames().toList() }
	var selectedSeed by remember { mutableStateOf(seedNames.firstOrNull()) }

	var walletCount by remember { mutableStateOf(0) }
	var loaded by remember { mutableStateOf(false) }
	var error by remember { mutableStateOf<String?>(null) }
	var success by remember { mutableStateOf(false) }
	var busy by remember { mutableStateOf(false) }

	var recipients by remember { mutableStateOf<List<String>>(emptyList()) }
	var draft by remember { mutableStateOf("") }
	var pendingFile by remember { mutableStateOf<String?>(null) }

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

	fun addDraft() {
		val line = draft.trim()
		if (line.isEmpty()) return
		// Catch the obvious mistake here rather than letting Rust reject the
		// whole export after the user has already authenticated.
		if (!looksLikeRecipient(line)) {
			error = "not a public key: expected an ssh-... line or an age1... recipient"
			return
		}
		if (recipients.contains(line)) {
			draft = ""
			return
		}
		recipients = recipients + line
		draft = ""
		error = null
	}

	val saveLauncher = rememberLauncherForActivityResult(
		// age armor is text, but it is not a document format anything should
		// try to open - octet-stream keeps other apps from claiming it.
		ActivityResultContracts.CreateDocument("application/octet-stream")
	) { uri: Uri? ->
		val text = pendingFile
		pendingFile = null
		if (uri != null && text != null) {
			scope.launch {
				try {
					withContext(Dispatchers.IO) { writeAgeBackupFile(ctx, uri, text) }
					success = true
				} catch (e: Exception) {
					Timber.e(e, "[frost-backup-age] save failed")
					error = e.message ?: "save failed"
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
		ScreenHeaderClose(title = "Backup to a key", onClose = onBack)

		if (success) {
			Box(
				modifier = Modifier.weight(1f).fillMaxWidth(),
				contentAlignment = Alignment.Center,
			) {
				AnimatedSuccessBadge(visible = true, message = "backup saved")
				LaunchedEffect(Unit) {
					kotlinx.coroutines.delay(2500)
					onBack()
				}
			}
		} else {
			Column(
				modifier = Modifier
					.weight(1f)
					.verticalScroll(rememberScrollState())
					.padding(horizontal = 24.dp),
			) {
				Text(
					text = when {
						!loaded -> "Loading…"
						walletCount == 0 -> "No multisig wallets to backup."
						else -> "$walletCount multisig wallet${if (walletCount == 1) "" else "s"}"
					},
					style = SignerTypeface.TitleS,
					color = MaterialTheme.colors.primary,
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = "Encrypted to public keys instead of a passphrase. Your seed " +
						"phrase opens it — there is no second secret to remember.",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary,
				)

				if (seedNames.size > 1) {
					Spacer(Modifier.height(24.dp))
					Text(
						text = "Seed",
						style = SignerTypeface.LabelM,
						color = MaterialTheme.colors.primary,
					)
					Spacer(Modifier.height(4.dp))
					Text(
						text = "The one that will be able to restore this file.",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
					)
					Spacer(Modifier.height(8.dp))
					seedNames.forEach { name ->
						Row(
							modifier = Modifier
								.fillMaxWidth()
								.clickable { selectedSeed = name }
								.padding(vertical = 10.dp),
							verticalAlignment = Alignment.CenterVertically,
						) {
							RadioButton(
								selected = selectedSeed == name,
								onClick = null,
								colors = RadioButtonDefaults.colors(
									selectedColor = MaterialTheme.colors.accentPink,
								),
							)
							Spacer(Modifier.width(12.dp))
							Text(
								text = name,
								style = SignerTypeface.BodyL,
								color = MaterialTheme.colors.primary,
							)
						}
					}
				}

				Spacer(Modifier.height(24.dp))
				Text(
					text = "Who can restore this",
					style = SignerTypeface.LabelM,
					color = MaterialTheme.colors.primary,
				)
				Spacer(Modifier.height(4.dp))
				Text(
					text = "Add a co-signer's key, or another device's. A backup only " +
						"this phone can open is lost at the same moment this phone is.",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary,
				)

				Spacer(Modifier.height(12.dp))
				// Always-present first row: the device adds itself in Rust, and
				// showing it here means the list on screen is the real list.
				Row(
					modifier = Modifier
						.fillMaxWidth()
						.clip(RectangleShape)
						.background(MaterialTheme.colors.fill6)
						.padding(horizontal = 12.dp, vertical = 12.dp),
					verticalAlignment = Alignment.CenterVertically,
				) {
					Text(
						text = "This device",
						style = SignerTypeface.BodyM,
						color = MaterialTheme.colors.primary,
						modifier = Modifier.weight(1f),
					)
					Text(
						text = "always",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.textTertiary,
					)
				}

				recipients.forEach { line ->
					Spacer(Modifier.height(8.dp))
					Row(
						modifier = Modifier
							.fillMaxWidth()
							.clip(RectangleShape)
							.background(MaterialTheme.colors.fill6)
							.padding(start = 12.dp, top = 12.dp, bottom = 12.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = shortenRecipient(line),
							style = SignerTypeface.CaptionM,
							fontFamily = FontFamily.Monospace,
							color = MaterialTheme.colors.primary,
							modifier = Modifier.weight(1f),
						)
						IconButton(onClick = { recipients = recipients - line }) {
							Icon(
								imageVector = Icons.Outlined.Close,
								contentDescription = "Remove recipient",
								tint = MaterialTheme.colors.textTertiary,
							)
						}
					}
				}

				Spacer(Modifier.height(12.dp))
				OutlinedTextField(
					value = draft,
					onValueChange = { draft = it },
					modifier = Modifier.fillMaxWidth(),
					label = { Text("ssh-ed25519 AAAA… or age1…") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
					colors = TextFieldDefaults.outlinedTextFieldColors(
						textColor = MaterialTheme.colors.primary,
						cursorColor = MaterialTheme.colors.accentPink,
						focusedBorderColor = MaterialTheme.colors.accentPink,
					),
				)
				Spacer(Modifier.height(8.dp))
				Text(
					text = "Add recipient",
					style = SignerTypeface.LabelS,
					color = if (draft.isBlank()) {
						MaterialTheme.colors.textTertiary
					} else {
						MaterialTheme.colors.accentPink
					},
					modifier = Modifier.clickable(enabled = draft.isNotBlank()) { addDraft() },
				)

				error?.let {
					Spacer(Modifier.height(16.dp))
					Text(it, style = SignerTypeface.BodyM, color = MaterialTheme.colors.red500)
				}

				Spacer(Modifier.height(24.dp))
			}

			if (loaded && walletCount > 0) {
				SecondaryButtonWide(
					label = if (busy) "Exporting…" else "Export backup",
					modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
					onClicked = {
						val seed = selectedSeed
						if (seed == null) {
							error = "no seed on this device"
							return@SecondaryButtonWide
						}
						if (busy) return@SecondaryButtonWide
						busy = true
						scope.launch {
							try {
								// Anything left in the field is intent to
								// include it; dropping it silently is how a
								// co-signer ends up quietly left out. It gets
								// the same check the Add button applies, so a
								// half-typed line fails here rather than after
								// the user has already authenticated.
								val pending = draft.trim()
								if (pending.isNotEmpty() && !looksLikeRecipient(pending)) {
									error = "not a public key: $pending"
									busy = false
									return@launch
								}
								val extras =
									recipients + listOfNotNull(pending.takeIf { it.isNotEmpty() })
								val armored =
									FrostExportUseCase.exportAllToRecipients(seed, extras)
								if (armored == null) {
									// auth cancelled - the auth layer said so already
									busy = false
									return@launch
								}
								pendingFile = armored
								saveLauncher.launch("frost-backup-${ymdToday()}.age")
							} catch (e: Exception) {
								Timber.e(e, "[frost-backup-age] export failed")
								error = e.message ?: "export failed"
							} finally {
								busy = false
							}
						}
					},
				)
			}
		}
	}
}

/**
 * Cheap shape check, not validation — Rust does the real parse. This only
 * exists so an obvious typo is caught before the user authenticates rather
 * than after.
 */
private fun looksLikeRecipient(line: String): Boolean =
	line.startsWith("ssh-") || line.startsWith("age1")

/**
 * Show enough of a key to be recognised, not enough to be compared.
 *
 * Recipients are public, so a truncated display is not a security question -
 * it is a layout one. Anyone checking a key properly does it against the
 * source they got it from, not against a row in a list.
 */
private fun shortenRecipient(line: String): String {
	val parts = line.split(" ")
	val body = parts.getOrNull(1) ?: line
	val comment = parts.getOrNull(2)
	val head = parts.getOrNull(0)?.takeIf { it.startsWith("ssh-") }?.plus(" ") ?: ""
	val shortened = if (body.length > 24) "${body.take(12)}…${body.takeLast(8)}" else body
	return head + shortened + (comment?.let { "  $it" } ?: "")
}

private fun writeAgeBackupFile(ctx: Context, uri: Uri, text: String) {
	ctx.contentResolver.openOutputStream(uri)?.use { os ->
		os.write(text.toByteArray(Charsets.UTF_8))
	} ?: throw IllegalStateException("could not open output stream for $uri")
}
