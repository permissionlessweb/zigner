package net.rotko.zigner.screens.keysetdetails.items

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.onGold
import net.rotko.zigner.ui.theme.pink500
import net.rotko.zigner.ui.theme.SignerTypeface

/**
 * Bottom action bar for the KeySetDetails screen.
 *
 * Single full-width Scan CTA — on an air-gapped cold signer, scanning is THE
 * action (scan a sign request, scan a wallet import, scan a backup). Adding a
 * derived key is a one-time setup task and lives inline below the networks
 * list, not in the always-visible chrome.
 */
@Composable
fun KeySetBottomBar(
	onScan: Callback,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(56.dp)
				.clip(RectangleShape)
				.background(MaterialTheme.colors.pink500)
				.clickable(onClick = onScan),
			verticalAlignment = Alignment.CenterVertically,
			horizontalArrangement = Arrangement.Center,
		) {
			Icon(
				imageVector = Icons.Outlined.QrCodeScanner,
				contentDescription = null,
				tint = MaterialTheme.colors.onGold,
				modifier = Modifier.size(22.dp),
			)
			Spacer(modifier = Modifier.width(10.dp))
			Text(
				text = "Scan",
				color = MaterialTheme.colors.onGold,
				style = SignerTypeface.LabelL,
			)
		}
	}
}

@Preview(
	name = "light", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF0D0D12,
)
@Composable
private fun PreviewKeySetBottomBar() {
	SignerNewTheme {
		KeySetBottomBar(onScan = {})
	}
}
