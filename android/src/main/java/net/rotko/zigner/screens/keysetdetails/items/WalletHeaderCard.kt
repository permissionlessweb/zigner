package net.rotko.zigner.screens.keysetdetails.items

import android.content.res.Configuration
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
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * Wallet identity card used at the top of the KeySetDetails screen.
 *
 * Replaces the legacy "big root QR + identicon + base58" block with a Zafu-style
 * wallet header: name, an "air-gapped cold signer" tagline, the set of networks
 * this wallet holds keys for as colored chips, and a small ExposedIcon
 * (only rendered when radios have actually been detected as on).
 *
 * The card is one neutral surface — chain accents only appear on the network
 * chips, so the rest of the screen can carry the network colours.
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
	val showRootQr = model.root.addressKey.isNotEmpty() && hasSubstrateNetworks(model)
	val distinctNetworks = remember(model.keysAndNetwork) {
		model.keysAndNetwork
			.map { it.network.networkLogo }
			.distinct()
	}

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.clip(RoundedCornerShape(20.dp))
			.background(MaterialTheme.colors.backgroundSecondary)
			.border(
				width = 1.dp,
				color = MaterialTheme.colors.appliedStroke,
				shape = RoundedCornerShape(20.dp),
			)
			.padding(horizontal = 16.dp, vertical = 16.dp),
	) {
		Row(verticalAlignment = Alignment.CenterVertically) {
			IdentIconImage(
				identicon = model.root.identicon,
				size = 40.dp,
			)
			Spacer(modifier = Modifier.width(12.dp))
			Column(
				modifier = Modifier
					.weight(1f)
					.clickable(onClick = onSeedSelect),
			) {
				Row(verticalAlignment = Alignment.CenterVertically) {
					Text(
						text = model.root.seedName,
						color = MaterialTheme.colors.primary,
						style = SignerTypeface.TitleL,
					)
					Icon(
						imageVector = Icons.Outlined.ExpandMore,
						contentDescription = null,
						tint = MaterialTheme.colors.textTertiary,
						modifier = Modifier
							.padding(start = 4.dp)
							.size(20.dp),
					)
				}
				Text(
					text = "Air-gapped cold signer",
					color = MaterialTheme.colors.textSecondary,
					style = SignerTypeface.CaptionM,
				)
			}
			ExposedIcon(
				networkState = networkState,
				onClick = onExposedClicked,
			)
		}

		if (distinctNetworks.isNotEmpty()) {
			Spacer(modifier = Modifier.padding(top = 14.dp))
			NetworkBadgesRow(networkLogos = distinctNetworks)
		}

		Spacer(modifier = Modifier.padding(top = 12.dp))
		Row(verticalAlignment = Alignment.CenterVertically) {
			Text(
				text = model.root.base58.abbreviateString(BASE58_STYLE_ABBREVIATE),
				color = MaterialTheme.colors.textTertiary,
				style = SignerTypeface.BodyM,
				modifier = Modifier.weight(1f),
			)
			if (showRootQr) {
				ShowRootChip(onClick = onShowRoot)
			}
		}
	}
}

@Composable
private fun NetworkBadgesRow(networkLogos: List<String>) {
	val maxVisible = 6
	val visible = networkLogos.take(maxVisible)
	val overflow = networkLogos.size - visible.size

	Row(
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.spacedBy(8.dp),
		modifier = Modifier.fillMaxWidth(),
	) {
		visible.forEach { logo ->
			NetworkIcon(
				networkLogoName = logo,
				size = 22.dp,
				modifier = Modifier.border(
					width = 1.dp,
					color = networkAccent(logo).copy(alpha = 0.4f),
					shape = CircleShape,
				),
			)
		}
		if (overflow > 0) {
			Box(
				modifier = Modifier
					.size(22.dp)
					.clip(CircleShape)
					.background(MaterialTheme.colors.appliedStroke),
				contentAlignment = Alignment.Center,
			) {
				Text(
					text = "+$overflow",
					color = MaterialTheme.colors.textSecondary,
					style = SignerTypeface.CaptionS,
				)
			}
		}
		Spacer(modifier = Modifier.weight(1f))
		Text(
			text = if (networkLogos.size == 1) "1 network" else "${networkLogos.size} networks",
			color = MaterialTheme.colors.textSecondary,
			style = SignerTypeface.CaptionM,
		)
	}
}

@Composable
private fun ShowRootChip(onClick: Callback) {
	Row(
		modifier = Modifier
			.clip(RoundedCornerShape(8.dp))
			.background(MaterialTheme.colors.appliedStroke)
			.clickable(onClick = onClick)
			.padding(horizontal = 10.dp, vertical = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Icon(
			imageVector = Icons.Filled.QrCode2,
			contentDescription = null,
			tint = MaterialTheme.colors.primary,
			modifier = Modifier.size(16.dp),
		)
		Spacer(modifier = Modifier.width(6.dp))
		Text(
			text = "Show root",
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.CaptionM,
		)
	}
}

internal fun hasSubstrateNetworks(model: KeySetDetailsModel): Boolean =
	model.keysAndNetwork.any { keyAndNetwork ->
		val logo = keyAndNetwork.network.networkLogo.lowercase()
		!logo.contains("zcash") && !logo.contains("penumbra") &&
			!logo.contains("noble") && !logo.contains("osmosis") &&
			!logo.contains("celestia") && !logo.contains("cosmos") &&
			!logo.contains("terp") && !logo.contains("terra") &&
			!logo.contains("kava") && !logo.contains("secret") &&
			!logo.contains("injective") &&
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
