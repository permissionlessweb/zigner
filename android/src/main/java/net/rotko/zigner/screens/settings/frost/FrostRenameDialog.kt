package net.rotko.zigner.screens.settings.frost

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*

/** Rename dialog for a FROST multisig wallet. Pre-fills with the current
 *  label, trims whitespace, blocks save on empty / unchanged input. */
@Composable
fun FrostRenameDialog(
	currentLabel: String,
	onConfirm: (newLabel: String) -> Unit,
	onCancel: Callback,
) {
	var label by remember { mutableStateOf(currentLabel) }
	val trimmed = label.trim()
	val canConfirm = trimmed.isNotEmpty() && trimmed != currentLabel.trim()

	Dialog(onDismissRequest = onCancel) {
		Column(
			modifier = Modifier
				.clip(RectangleShape)
				.background(MaterialTheme.colors.background)
				.padding(20.dp),
		) {
			Text(
				text = "Rename multisig",
				style = SignerTypeface.TitleS,
				color = MaterialTheme.colors.primary,
			)
			Spacer(modifier = Modifier.height(6.dp))
			Text(
				text = "Local label only — peers and Zafu keep their own labels.",
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textTertiary,
			)

			Spacer(modifier = Modifier.height(16.dp))
			OutlinedTextField(
				value = label,
				onValueChange = { label = it },
				label = { Text("Label") },
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
				singleLine = true,
				modifier = Modifier.fillMaxWidth(),
			)

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
					label = "Save",
					modifier = Modifier.weight(1f),
					onClicked = { if (canConfirm) onConfirm(trimmed) },
				)
			}
		}
	}
}
