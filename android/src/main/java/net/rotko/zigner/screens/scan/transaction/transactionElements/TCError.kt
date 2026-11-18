package net.rotko.zigner.screens.scan.transaction.transactionElements

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.backgroundDanger
import net.rotko.zigner.ui.theme.pink300

@Composable
fun TCError(error: String) {
	Row(
		modifier = Modifier.background(
			color = MaterialTheme.colors.backgroundDanger,
			shape = RectangleShape
		),
		horizontalArrangement = Arrangement.spacedBy(8.dp),
	) {
		Text(
			text = stringResource(R.string.transaction_field_error),
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.pink300
		)
		Text(
			text = error,
			style = SignerTypeface.BodyM,
			color = MaterialTheme.colors.pink300
		)
	}
}


@Preview(
	name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewTCError() {
	SignerNewTheme {
		Column {
			TCError("Something went wrong!")
//			SignerDivider()
		}
	}
}
