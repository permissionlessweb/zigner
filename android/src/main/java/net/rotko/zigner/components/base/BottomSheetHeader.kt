package net.rotko.zigner.components.base

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material.Divider
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textSecondary

@Composable
fun BottomSheetHeader(
	title: String,
	modifier: Modifier = Modifier,
	subtitile: String? = null,
	onClose: Callback?
) {
	Row(
		modifier = modifier
			.padding(start = 24.dp, end = 16.dp)
			.padding(vertical = 20.dp)
			.fillMaxWidth(),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(modifier = Modifier.weight(1.0f)) {
			Text(
				text = title,
				color = MaterialTheme.colors.primary,
				style = SignerTypeface.TitleS,
			)
			if (subtitile != null) {
				Text(
					text = subtitile,
					color = MaterialTheme.colors.textSecondary,
					style = SignerTypeface.BodyM,
				)
			}
		}
		if (onClose != null) {
			CloseIcon(
				onCloseClicked = onClose,
				modifier = Modifier.padding(start = 16.dp)
			)
		}
	}
}

@Composable
fun BottomSheetSubtitle(
	@StringRes id: Int,
	modifier: Modifier = Modifier,
) {
	Text(
		text = stringResource(id),
		color = MaterialTheme.colors.primary,
		style = SignerTypeface.BodyL,
		modifier = modifier.padding(start = 24.dp)
	)
}

@Preview(
	name = "day",
	group = "themes",
	uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true,
	backgroundColor = 0xFFFFFFFF
)
@Preview(
	name = "dark theme",
	group = "themes",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true,
	backgroundColor = 0xFF000000
)
@Composable
private fun PreviewHeaderWithClose() {
	SignerNewTheme {
		Column() {
			BottomSheetHeader(title = "Title", onClose = {})
			Divider()
			BottomSheetHeader(title = "Very very very very long title very very very very long title") {}
			Divider()
			BottomSheetHeader(title = "Title", subtitile = "With subtitle", onClose = {})
			Divider()
			BottomSheetSubtitle(R.string.subtitle_secret_recovery_phrase)
		}
	}
}
