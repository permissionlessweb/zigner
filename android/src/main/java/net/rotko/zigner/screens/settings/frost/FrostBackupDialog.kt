package net.rotko.zigner.screens.settings.frost

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*

/** Minimum passphrase length. Encrypted FROST share is what stands between
 *  a disk-read attacker and the spend material; sized for offline crack. */
const val MIN_FROST_PASSPHRASE_LEN = 16

/** Passphrase entry for FROST backup export/import. Always starts empty —
 *  never reuses a passphrase across opens. */
@Composable
fun FrostBackupDialog(
	title: String,
	subtitle: String,
	confirmLabel: String,
	requireConfirmField: Boolean,
	onConfirm: (passphrase: String) -> Unit,
	onCancel: Callback,
) {
	var passphrase by remember { mutableStateOf("") }
	var confirm by remember { mutableStateOf("") }
	// Encrypted FROST share is the only barrier between a disk-read attacker
	// and the spend material. 16-char minimum sized for offline-crack resistance.
	val canConfirm = passphrase.length >= MIN_FROST_PASSPHRASE_LEN &&
		(!requireConfirmField || passphrase == confirm)

	Dialog(onDismissRequest = onCancel) {
		Column(
			modifier = Modifier
				.clip(RectangleShape)
				.background(MaterialTheme.colors.background)
				.padding(20.dp)
		) {
			Text(
				text = title,
				style = SignerTypeface.TitleS,
				color = MaterialTheme.colors.primary,
			)
			Spacer(modifier = Modifier.height(6.dp))
			Text(
				text = subtitle,
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textTertiary,
			)

			if (requireConfirmField) {
				Spacer(modifier = Modifier.height(8.dp))
				Text(
					text = "⚠ this passphrase cannot be reset. Losing it means the backup is unusable.",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.red400,
				)
			}

			Spacer(modifier = Modifier.height(16.dp))
			OutlinedTextField(
				value = passphrase,
				onValueChange = { passphrase = it },
				label = { Text("Passphrase") },
				visualTransformation = PasswordVisualTransformation(),
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
				singleLine = true,
				modifier = Modifier.fillMaxWidth(),
				colors = TextFieldDefaults.outlinedTextFieldColors(
					textColor = MaterialTheme.colors.primary,
					cursorColor = MaterialTheme.colors.primary,
					focusedBorderColor = MaterialTheme.colors.primary,
					unfocusedBorderColor = MaterialTheme.colors.textTertiary,
					focusedLabelColor = MaterialTheme.colors.primary,
					unfocusedLabelColor = MaterialTheme.colors.textTertiary,
				),
			)
			if (passphrase.isNotEmpty() && passphrase.length < MIN_FROST_PASSPHRASE_LEN) {
				Text(
					text = "At least $MIN_FROST_PASSPHRASE_LEN characters",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.red400,
					modifier = Modifier.padding(top = 4.dp),
				)
			}

			if (requireConfirmField) {
				Spacer(modifier = Modifier.height(8.dp))
				OutlinedTextField(
					value = confirm,
					onValueChange = { confirm = it },
					label = { Text("Confirm passphrase") },
					visualTransformation = PasswordVisualTransformation(),
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
					singleLine = true,
					modifier = Modifier.fillMaxWidth(),
					colors = TextFieldDefaults.outlinedTextFieldColors(
						textColor = MaterialTheme.colors.primary,
						cursorColor = MaterialTheme.colors.primary,
						focusedBorderColor = MaterialTheme.colors.primary,
						unfocusedBorderColor = MaterialTheme.colors.textTertiary,
						focusedLabelColor = MaterialTheme.colors.primary,
						unfocusedLabelColor = MaterialTheme.colors.textTertiary,
					),
				)
				if (confirm.isNotEmpty() && passphrase != confirm) {
					Text(
						text = "Passphrases don't match",
						style = SignerTypeface.CaptionM,
						color = MaterialTheme.colors.red400,
						modifier = Modifier.padding(top = 4.dp),
					)
				}
			}

			Spacer(modifier = Modifier.height(20.dp))
			Row(
				modifier = Modifier.fillMaxWidth(),
				horizontalArrangement = Arrangement.spacedBy(12.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				SecondaryButtonWide(
					label = "Cancel",
					modifier = Modifier.weight(1f),
					onClicked = onCancel,
				)
				PrimaryButtonWide(
					label = confirmLabel,
					modifier = Modifier.weight(1f),
					onClicked = { if (canConfirm) onConfirm(passphrase) },
				)
			}
		}
	}
}
