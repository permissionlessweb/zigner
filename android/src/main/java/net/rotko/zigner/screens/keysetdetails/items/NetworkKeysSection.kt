package net.rotko.zigner.screens.keysetdetails.items

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.Icon
import net.rotko.zigner.domain.BASE58_STYLE_ABBREVIATE
import net.rotko.zigner.domain.abbreviateString
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.networkicon.NetworkIcon
import net.rotko.zigner.domain.KeyAndNetworkModel
import net.rotko.zigner.domain.KeyModel
import net.rotko.zigner.domain.NetworkInfoModel
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.appliedStroke
import net.rotko.zigner.ui.theme.backgroundSecondary
import net.rotko.zigner.ui.theme.networkAccent
import net.rotko.zigner.ui.theme.networkAccentTint
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary

/**
 * Per-network grouped keys card on the KeySetDetails screen.
 *
 * Header: accent-stripe | network icon | title | key count | chevron.
 * Body (when expanded): the keys derived for this network, separated by
 * dividers. Tap a key to navigate to its detail screen.
 *
 * Color comes from the chain itself (see [networkAccent]); the surrounding
 * card is neutral so the accent reads as identity rather than decoration.
 */
@Composable
fun NetworkKeysSection(
	network: NetworkInfoModel,
	keys: List<KeyModel>,
	onKeyClick: (keyAddr: String, keySpecs: String) -> Unit,
	modifier: Modifier = Modifier,
	initiallyExpanded: Boolean = true,
) {
	// One plain row per key: network, address, open. Paths, key counts and
	// collapse toggles meant nothing to people and are gone from home.
	Column(modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
		keys.forEach { key ->
			Row(
				modifier = Modifier
					.fillMaxWidth()
					.clickable { onKeyClick(key.addressKey, network.networkSpecsKey) }
					.padding(vertical = 16.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				NetworkIcon(networkLogoName = network.networkLogo, size = 32.dp)
				Spacer(modifier = Modifier.width(14.dp))
				Column(modifier = Modifier.weight(1f)) {
					Text(
						text = network.networkTitle,
						color = MaterialTheme.colors.primary,
						style = SignerTypeface.TitleS,
					)
					Text(
						text = key.base58.abbreviateString(BASE58_STYLE_ABBREVIATE),
						color = MaterialTheme.colors.textTertiary,
						style = SignerTypeface.BodyS,
					)
				}
				Icon(
					imageVector = Icons.Outlined.ChevronRight,
					contentDescription = null,
					tint = MaterialTheme.colors.textTertiary,
				)
			}
			SignerDivider()
		}
	}
}

@Composable
private fun NetworkKeysSectionContent(
	network: NetworkInfoModel,
	keys: List<KeyModel>,
	expanded: Boolean,
	onToggle: () -> Unit,
	onKeyClick: (keyAddr: String, keySpecs: String) -> Unit,
	modifier: Modifier = Modifier,
) {
	val accent = networkAccent(network.networkLogo)
	val shape = RectangleShape
	val chevronRotation by animateFloatAsState(
		targetValue = if (expanded) 180f else 0f,
		animationSpec = tween(durationMillis = 220),
		label = "Chevron-rotation",
	)

	Column(
		modifier = modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.clip(shape)
			.background(MaterialTheme.colors.backgroundSecondary)
			.border(
				width = 1.dp,
				color = MaterialTheme.colors.appliedStroke,
				shape = shape,
			),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.height(IntrinsicSize.Min)
				.clickable(onClick = onToggle),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Box(
				modifier = Modifier
					.width(3.dp)
					.fillMaxHeight()
					.background(accent),
			)
			NetworkIcon(
				networkLogoName = network.networkLogo,
				size = 28.dp,
				modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
			)
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = network.networkTitle,
					color = MaterialTheme.colors.primary,
					style = SignerTypeface.TitleS,
				)
				Text(
					text = if (keys.size == 1) "1 key" else "${keys.size} keys",
					color = MaterialTheme.colors.textSecondary,
					style = SignerTypeface.CaptionM,
				)
			}
			Box(
				modifier = Modifier
					.padding(end = 12.dp)
					.size(28.dp)
					.clip(RectangleShape)
					.background(networkAccentTint(network.networkLogo)),
				contentAlignment = Alignment.Center,
			) {
				Icon(
					imageVector = Icons.Outlined.ExpandMore,
					contentDescription = null,
					tint = accent,
					modifier = Modifier
						.size(18.dp)
						.rotate(chevronRotation),
				)
			}
		}
		AnimatedVisibility(
			visible = expanded && keys.isNotEmpty(),
			enter = expandVertically(animationSpec = tween(durationMillis = 220)) +
				fadeIn(animationSpec = tween(durationMillis = 220)),
			exit = shrinkVertically(animationSpec = tween(durationMillis = 180)) +
				fadeOut(animationSpec = tween(durationMillis = 120)),
		) {
			Column {
				SignerDivider()
				keys.forEachIndexed { index, key ->
					if (index > 0) SignerDivider(modifier = Modifier.padding(start = 56.dp))
					KeyDerivedItem(
						model = key,
						networkLogo = network.networkLogo,
						onClick = { onKeyClick(key.addressKey, network.networkSpecsKey) },
					)
				}
			}
		}
	}
}

/**
 * Stable preview-friendly grouping of [KeyAndNetworkModel]s into one section
 * per network. Order is preserved from the first occurrence of each network.
 */
fun groupKeysByNetwork(
	keysAndNetwork: List<KeyAndNetworkModel>,
): List<Pair<NetworkInfoModel, List<KeyModel>>> {
	val ordering = LinkedHashMap<String, NetworkInfoModel>()
	val buckets = LinkedHashMap<String, MutableList<KeyModel>>()
	keysAndNetwork.forEach { entry ->
		val key = entry.network.networkSpecsKey
		ordering.putIfAbsent(key, entry.network)
		buckets.getOrPut(key) { mutableListOf() }.add(entry.key)
	}
	return ordering.map { (key, network) -> network to (buckets[key] ?: emptyList()) }
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
private fun PreviewNetworkKeysSection() {
	SignerNewTheme {
		Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Spacer(modifier = Modifier.padding(8.dp))
			NetworkKeysSection(
				network = NetworkInfoModel(
					networkTitle = "Zcash",
					networkLogo = "zcash",
					networkSpecsKey = "spec-zcash",
				),
				keys = listOf(
					KeyModel.createStub().copy(path = "//zcash//main"),
					KeyModel.createStub().copy(path = "//zcash//savings"),
				),
				onKeyClick = { _, _ -> },
			)
			NetworkKeysSection(
				network = NetworkInfoModel(
					networkTitle = "Penumbra",
					networkLogo = "penumbra",
					networkSpecsKey = "spec-penumbra",
				),
				keys = listOf(KeyModel.createStub().copy(path = "//penumbra")),
				onKeyClick = { _, _ -> },
			)
			NetworkKeysSection(
				network = NetworkInfoModel(
					networkTitle = "Polkadot",
					networkLogo = "polkadot",
					networkSpecsKey = "spec-polkadot",
				),
				keys = listOf(KeyModel.createStub()),
				onKeyClick = { _, _ -> },
				initiallyExpanded = false,
			)
		}
	}
}
