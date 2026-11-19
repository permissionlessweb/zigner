package net.rotko.zigner.screens.settings.frost

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*

/**
 * Ask which seed should open an age-encrypted backup.
 *
 * Only needed when the device holds more than one seed. The file is addressed
 * to exactly one of them and carries nothing that says which — age recipient
 * stanzas are deliberately unlabelled, so that a file does not advertise who
 * can read it.
 *
 * The alternative would be to try each seed until one works, but every seed
 * read triggers a biometric prompt, so that turns one restore into a string
 * of prompts the user learns to tap through without reading.
 */
@Composable
fun FrostSeedPickerDialog(
	seedNames: List<String>,
	onPick: (String) -> Unit,
	onCancel: Callback,
) {
	Dialog(onDismissRequest = onCancel) {
		Column(
			modifier = Modifier
				.clip(RectangleShape)
				.background(MaterialTheme.colors.background)
				.padding(20.dp)
		) {
			Text(
				text = "Which seed?",
				style = SignerTypeface.TitleS,
				color = MaterialTheme.colors.primary,
			)
			Spacer(modifier = Modifier.height(6.dp))
			Text(
				text = "This backup was encrypted to one key. Pick the seed it was " +
					"addressed to — the others cannot open it.",
				style = SignerTypeface.CaptionM,
				color = MaterialTheme.colors.textTertiary,
			)

			Spacer(modifier = Modifier.height(16.dp))
			seedNames.forEach { name ->
				Text(
					text = name,
					style = SignerTypeface.BodyL,
					color = MaterialTheme.colors.primary,
					modifier = Modifier
						.fillMaxWidth()
						.clickable { onPick(name) }
						.padding(vertical = 14.dp),
				)
			}

			Spacer(modifier = Modifier.height(8.dp))
			SecondaryButtonWide(label = "Cancel", onClicked = onCancel)
		}
	}
}
