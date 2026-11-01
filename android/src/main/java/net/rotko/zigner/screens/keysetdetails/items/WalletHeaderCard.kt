package net.rotko.zigner.screens.keysetdetails.items

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.components.exposesecurity.ExposedIcon
import net.rotko.zigner.components.networkicon.IdentIconImage
import net.rotko.zigner.components.networkicon.NetworkIcon
import net.rotko.zigner.domain.BASE58_STYLE_ABBREVIATE
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.KeyModel
import net.rotko.zigner.domain.KeySetDetailsModel
import net.rotko.zigner.domain.NetworkState
import net.rotko.zigner.domain.abbreviateString
import net.rotko.zigner.ui.helpers.PreviewData
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.appliedStroke
import net.rotko.zigner.ui.theme.backgroundSecondary
import net.rotko.zigner.ui.theme.networkAccent
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary
import net.rotko.zigner.ui.theme.red500fill12
import net.rotko.zigner.ui.theme.red500

/**
 * Top of the home screen: the wallet's name (tap to switch wallets) and, only
 * when it matters, a small tag saying this phone is or was online. Everything
 * else that used to sit here repeated the list below or meant nothing.
 */
@Composable
fun WalletHeaderCard(
	model: KeySetDetailsModel,
	networkState: State<NetworkState?>,
	onSeedSelect: Callback,
	onShowRoot: Callback,
	onExposedClicked: Callback,
	modifier: Modifier = Modifier,
) {
	Row(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 24.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Row(
			modifier = Modifier
				.weight(1f)
				.clickable(onClick = onSeedSelect),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Text(
				text = model.root.seedName,
				color = MaterialTheme.colors.primary,
				style = SignerTypeface.TitleXl,
			)
			Icon(
				imageVector = Icons.Outlined.ExpandMore,
				contentDescription = null,
				tint = MaterialTheme.colors.textTertiary,
				modifier = Modifier
					.padding(start = 6.dp)
					.size(24.dp),
			)
		}
		NetworkStatusTag(networkState.value, onExposedClicked)
	}
}

@Composable
private fun NetworkStatusTag(state: NetworkState?, onClick: Callback) {
	val label = when (state) {
		NetworkState.Active -> "Online"
		NetworkState.Past -> "Was online"
		else -> return
	}
	Text(
		text = label,
		color = MaterialTheme.colors.red500,
		style = SignerTypeface.LabelS,
		modifier = Modifier
			.clip(RectangleShape)
			.background(MaterialTheme.colors.red500fill12)
			.clickable(onClick = onClick)
			.padding(horizontal = 10.dp, vertical = 6.dp),
	)
}

internal fun hasSubstrateNetworks(model: KeySetDetailsModel): Boolean =
	model.keysAndNetwork.any { keyAndNetwork ->
		val logo = keyAndNetwork.network.networkLogo.lowercase()
		!logo.contains("zcash") && !logo.contains("penumbra") &&
			!logo.contains("noble") && !logo.contains("osmosis") &&
			!logo.contains("celestia") && !logo.contains("cosmos") &&
			!logo.contains("bitcoin") && !logo.contains("nostr") &&
			!logo.contains("atprotocol") && !logo.contains("ethereum")
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
private fun PreviewWalletHeaderCard() {
	val state = remember { mutableStateOf<NetworkState?>(NetworkState.None) }
	SignerNewTheme {
		WalletHeaderCard(
			model = KeySetDetailsModel.createStub().copy(
				root = KeyModel.createStub().copy(
					identicon = PreviewData.Identicon.jdenticonIcon,
					seedName = "My Zigner Wallet",
				),
			),
			networkState = state,
			onSeedSelect = {},
			onShowRoot = {},
			onExposedClicked = {},
		)
	}
}
