package net.rotko.zigner.screens.keysetdetails

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import io.parity.signer.uniffi.FrostWalletSummaryFfi
import io.parity.signer.uniffi.frostListWallets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.rotko.zigner.R
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.base.SettingsIcon
import net.rotko.zigner.domain.BASE58_STYLE_ABBREVIATE
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.KeyModel
import net.rotko.zigner.domain.KeySetDetailsModel
import net.rotko.zigner.domain.NetworkState
import net.rotko.zigner.domain.abbreviateString
import net.rotko.zigner.domain.conditional
import net.rotko.zigner.screens.settings.bluetooth.RemoteSignerHomeTab
import net.rotko.zigner.screens.keysetdetails.items.HotWalletQrSection
import net.rotko.zigner.screens.keysetdetails.items.KeySetBottomBar
import net.rotko.zigner.screens.keysetdetails.items.NetworkKeysSection
import net.rotko.zigner.screens.keysetdetails.items.WalletHeaderCard
import net.rotko.zigner.screens.keysetdetails.items.groupKeysByNetwork
import net.rotko.zigner.ui.mainnavigation.CoreUnlockedNavSubgraph
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.appliedStroke
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.textDisabled
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary

/**
 * Home screen for the selected wallet.
 *
 * Layout (Zafu-style identity-first hierarchy):
 *   1. Thin neutral chrome (Settings | Menu)
 *   2. WalletHeaderCard — wallet name, "Air-gapped cold signer", network badges
 *   3. Per-network grouped key sections with chain accent stripes
 *   4. Hot wallet QR + multisig sections (secondary)
 *   5. Bottom action bar (Scan | Add key) — labelled, not floating
 */
@Composable
fun KeySetDetailsScreenView(
	model: KeySetDetailsModel,
	navController: NavController,
	networkState: State<NetworkState?>,
	fullModelWasEmpty: Boolean,
	onExposedClicked: Callback,
	onFilterClicked: Callback,
	onMenu: Callback,
	onSeedSelect: Callback,
	onAddNewDerivation: Callback,
	onShowRoot: Callback,
	onOpenKey: (keyAddr: String, keySpecs: String) -> Unit,
	getSeedPhrase: suspend (String) -> String? = { null },
) {
	var homeTab by remember { mutableStateOf("keys") }
	Column(modifier = Modifier.fillMaxHeight()) {
		KeySetDetailsHeader(
			onSettings = { navController.navigate(CoreUnlockedNavSubgraph.settings) },
			onMenu = onMenu,
		)
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.padding(horizontal = 16.dp, vertical = 4.dp),
			horizontalArrangement = Arrangement.spacedBy(16.dp),
		) {
			HomeTabLabel("Keys", homeTab == "keys") { homeTab = "keys" }
			HomeTabLabel("Signer", homeTab == "signer") { homeTab = "signer" }
		}

		if (homeTab == "signer") {
			Box(modifier = Modifier.weight(1f)) {
				RemoteSignerHomeTab()
			}
		} else {
		Box(modifier = Modifier.weight(1f)) {
			Column(
				modifier = Modifier
					.fillMaxHeight()
					.verticalScroll(rememberScrollState()),
				verticalArrangement = Arrangement.spacedBy(12.dp),
			) {
				WalletHeaderCard(
					model = model,
					networkState = networkState,
					onSeedSelect = onSeedSelect,
					onShowRoot = onShowRoot,
					onExposedClicked = onExposedClicked,
					modifier = Modifier.padding(top = 8.dp),
				)

				if (model.keysAndNetwork.isNotEmpty()) {
					FilterRow(onFilterClicked)
					val groups = remember(model.keysAndNetwork) {
						groupKeysByNetwork(model.keysAndNetwork)
					}
					groups.forEach { (network, keys) ->
						NetworkKeysSection(
							network = network,
							keys = keys,
							onKeyClick = onOpenKey,
						)
					}
					AddDerivedKeyInlineButton(onClick = onAddNewDerivation)
				} else if (fullModelWasEmpty) {
					KeySetDetailsEmptyList(onAdd = onAddNewDerivation)
				} else {
					FilterRow(onFilterClicked)
					Spacer(modifier = Modifier.padding(top = 32.dp))
					Text(
						text = stringResource(R.string.key_set_details_all_filtered_keys_title),
						color = MaterialTheme.colors.primary,
						style = SignerTypeface.TitleM,
						textAlign = TextAlign.Center,
						modifier = Modifier
							.fillMaxWidth()
							.padding(horizontal = 40.dp),
					)
				}

				HotWalletQrSection(
					seedName = model.root.seedName,
					getSeedPhrase = getSeedPhrase,
				)

				MultisigSection(
					onManage = {
						navController.navigate(CoreUnlockedNavSubgraph.frostWalletList)
					},
					onDetail = { walletId ->
						navController.navigate(CoreUnlockedNavSubgraph.FrostWalletDetail.destination(walletId))
					},
				)

				Spacer(modifier = Modifier.padding(bottom = 8.dp))
			}
		}
		}

		KeySetBottomBar(
			onScan = {
				navController.navigate(CoreUnlockedNavSubgraph.Camera.destination(null))
			},
		)
	}
}

@Composable
private fun HomeTabLabel(label: String, selected: Boolean, onClick: Callback) {
	Text(
		text = label,
		style = if (selected) SignerTypeface.LabelL else SignerTypeface.BodyL,
		color = if (selected) MaterialTheme.colors.primary else MaterialTheme.colors.textSecondary,
		modifier = Modifier
			.clickable(onClick = onClick)
			.padding(vertical = 8.dp),
	)
}

@Composable
private fun AddDerivedKeyInlineButton(onClick: Callback) {
	val shape = RoundedCornerShape(14.dp)
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp)
			.clip(shape)
			.border(
				width = 1.dp,
				color = MaterialTheme.colors.appliedStroke,
				shape = shape,
			)
			.clickable(onClick = onClick)
			.padding(vertical = 14.dp),
		verticalAlignment = Alignment.CenterVertically,
		horizontalArrangement = Arrangement.Center,
	) {
		Icon(
			imageVector = Icons.Filled.Add,
			contentDescription = null,
			tint = MaterialTheme.colors.textSecondary,
			modifier = Modifier.size(18.dp),
		)
		Spacer(modifier = Modifier.size(8.dp))
		Text(
			text = stringResource(R.string.key_sets_details_screem_create_derived_button),
			color = MaterialTheme.colors.textSecondary,
			style = SignerTypeface.LabelM,
		)
	}
}

@Composable
private fun FilterRow(onFilterClicked: Callback) {
	Row(
		modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Text(
			text = "Networks",
			color = MaterialTheme.colors.textSecondary,
			style = SignerTypeface.LabelS,
			modifier = Modifier.weight(1f),
		)
		Icon(
			painter = painterResource(id = R.drawable.ic_tune_28),
			contentDescription = stringResource(R.string.key_sets_details_screem_filter_icon_description),
			modifier = Modifier
				.clickable(onClick = onFilterClicked)
				.size(24.dp),
			tint = MaterialTheme.colors.textSecondary,
		)
	}
}

@Composable
fun KeySetDetailsHeader(
	onSettings: Callback,
	onMenu: Callback,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth(1f)
			.defaultMinSize(minHeight = 56.dp)
			.padding(horizontal = 8.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		SettingsIcon(
			onClick = onSettings,
			noBackground = true,
			modifier = Modifier
				.size(40.dp)
				.padding(8.dp),
		)
		Spacer(modifier = Modifier.weight(1f))
		Image(
			imageVector = Icons.Filled.MoreHoriz,
			contentDescription = stringResource(R.string.description_menu_button),
			colorFilter = ColorFilter.tint(MaterialTheme.colors.primary),
			modifier = Modifier
				.clickable(onClick = onMenu)
				.padding(8.dp)
				.size(24.dp),
		)
	}
}

/**
 * Not clickable item - disabled automatically.
 * Kept for callers outside of the home screen.
 */
@Composable
fun SeedKeyViewItem(
	seedKeyModel: KeyModel,
	onClick: Callback?,
) {
	Surface(
		modifier = Modifier
			.conditional(onClick != null) {
				clickable(onClick = onClick!!)
			},
		color = Color.Transparent,
	) {
		Row(
			modifier = Modifier
				.padding(top = 16.dp, bottom = 16.dp, start = 24.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(Modifier.weight(1f)) {
				Text(
					text = seedKeyModel.seedName,
					color = if (onClick != null) MaterialTheme.colors.primary else MaterialTheme.colors.textDisabled,
					style = SignerTypeface.TitleL,
				)
				Text(
					text = seedKeyModel.base58.abbreviateString(BASE58_STYLE_ABBREVIATE),
					color = if (onClick != null) MaterialTheme.colors.textTertiary else MaterialTheme.colors.textDisabled,
					style = SignerTypeface.BodyM,
				)
			}
			if (onClick != null) {
				Image(
					imageVector = Icons.Filled.ChevronRight,
					contentDescription = null,
					colorFilter = ColorFilter.tint(MaterialTheme.colors.textDisabled),
					modifier = Modifier
						.padding(end = 16.dp)
						.size(28.dp),
				)
			}
		}
	}
}

@Composable
private fun KeySetDetailsEmptyList(onAdd: Callback) {
	Column(
		modifier = Modifier
			.fillMaxWidth()
			.padding(horizontal = 16.dp, vertical = 24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
	) {
		Column(
			modifier = Modifier
				.fillMaxWidth()
				.clip(RoundedCornerShape(dimensionResource(id = R.dimen.bigCornerRadius)))
				.background(MaterialTheme.colors.fill6)
				.padding(24.dp),
			horizontalAlignment = Alignment.CenterHorizontally,
		) {
			Text(
				text = stringResource(R.string.key_set_details_no_keys_title),
				color = MaterialTheme.colors.primary,
				style = SignerTypeface.TitleM,
				textAlign = TextAlign.Center,
			)
			SecondaryButtonWide(
				label = stringResource(R.string.key_sets_details_screem_create_derived_button),
				withBackground = true,
				modifier = Modifier
					.padding(top = 16.dp, start = 8.dp, end = 8.dp),
				onClicked = onAdd,
			)
		}
	}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MultisigSection(
	onManage: Callback,
	onDetail: (String) -> Unit,
) {
	val scope = rememberCoroutineScope()
	var wallets by remember { mutableStateOf<List<FrostWalletSummaryFfi>>(emptyList()) }
	var expanded by remember { mutableStateOf(false) }

	LaunchedEffect(Unit) {
		scope.launch {
			runCatching {
				withContext(Dispatchers.Default) { frostListWallets() }
			}.onSuccess { wallets = it }
		}
	}

	if (wallets.isEmpty()) return

	val shape = RoundedCornerShape(16.dp)
	val chevronRotation by animateFloatAsState(
		targetValue = if (expanded) 180f else 0f,
		animationSpec = tween(durationMillis = 220),
		label = "multisig-chevron-rotation",
	)

	Column(
		modifier = Modifier
			.padding(horizontal = 16.dp)
			.clip(shape)
			.background(MaterialTheme.colors.fill6),
	) {
		Row(
			modifier = Modifier
				.fillMaxWidth()
				.combinedClickable(
					onClick = { expanded = !expanded },
					onLongClick = onManage,
				)
				.padding(horizontal = 16.dp, vertical = 14.dp),
			verticalAlignment = Alignment.CenterVertically,
		) {
			Column(modifier = Modifier.weight(1f)) {
				Text(
					text = "Multisig",
					color = MaterialTheme.colors.primary,
					style = SignerTypeface.TitleS,
				)
				Text(
					text = if (wallets.size == 1) "1 wallet" else "${wallets.size} wallets",
					color = MaterialTheme.colors.textSecondary,
					style = SignerTypeface.CaptionM,
				)
			}
			Icon(
				imageVector = Icons.Outlined.ExpandMore,
				contentDescription = null,
				tint = MaterialTheme.colors.textSecondary,
				modifier = Modifier
					.size(20.dp)
					.rotate(chevronRotation),
			)
		}
		AnimatedVisibility(
			visible = expanded,
			enter = expandVertically(animationSpec = tween(durationMillis = 220)) +
				fadeIn(animationSpec = tween(durationMillis = 220)),
			exit = shrinkVertically(animationSpec = tween(durationMillis = 180)) +
				fadeOut(animationSpec = tween(durationMillis = 120)),
		) {
			Column {
				for (wallet in wallets) {
					MultisigWalletRow(
						wallet = wallet,
						onClick = { onDetail(wallet.walletId) },
					)
				}
			}
		}
	}
}

@Composable
private fun MultisigWalletRow(
	wallet: FrostWalletSummaryFfi,
	onClick: Callback,
) {
	Row(
		modifier = Modifier
			.fillMaxWidth()
			.clickable(onClick = onClick)
			.padding(horizontal = 16.dp, vertical = 12.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(modifier = Modifier.weight(1f)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(
					text = wallet.label,
					color = MaterialTheme.colors.primary,
					style = SignerTypeface.TitleS,
					modifier = Modifier.weight(1f, fill = false),
				)
				Spacer(modifier = Modifier.size(8.dp))
				Text(
					text = if (wallet.mainnet) "mainnet" else "testnet",
					style = SignerTypeface.CaptionM,
					color = MaterialTheme.colors.textTertiary,
					modifier = Modifier
						.clip(RoundedCornerShape(4.dp))
						.background(MaterialTheme.colors.appliedStroke)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				)
			}
			Text(
				text = "${wallet.minSigners}-of-${wallet.maxSigners} threshold",
				color = MaterialTheme.colors.textSecondary,
				style = SignerTypeface.BodyM,
			)
		}
		Image(
			imageVector = Icons.Filled.ChevronRight,
			contentDescription = null,
			colorFilter = ColorFilter.tint(MaterialTheme.colors.textDisabled),
			modifier = Modifier.size(20.dp),
		)
	}
}

@Preview(
	name = "light", group = "general", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "general",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF0D0D12,
)
@Composable
private fun PreviewKeySetDetailsScreen() {
	val state = remember { mutableStateOf<NetworkState?>(NetworkState.None) }
	val mockModel = KeySetDetailsModel.createStub()
	val navController = rememberNavController()
	SignerNewTheme {
		Box(modifier = Modifier.size(360.dp, 720.dp)) {
			KeySetDetailsScreenView(
				model = mockModel,
				navController = navController,
				networkState = state,
				fullModelWasEmpty = false,
				onExposedClicked = {},
				onFilterClicked = {},
				onMenu = {},
				onAddNewDerivation = {},
				onSeedSelect = {},
				onShowRoot = {},
				onOpenKey = { _, _ -> },
			)
		}
	}
}

@Preview(
	name = "light", group = "general", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "general",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF0D0D12,
)
@Composable
private fun PreviewKeySetDetailsScreenEmpty() {
	val state = remember { mutableStateOf<NetworkState?>(NetworkState.None) }
	val mockModel =
		KeySetDetailsModel.createStub().copy(keysAndNetwork = emptyList())
	val navController = rememberNavController()
	SignerNewTheme {
		Box(modifier = Modifier.size(360.dp, 720.dp)) {
			KeySetDetailsScreenView(
				model = mockModel,
				navController = navController,
				networkState = state,
				fullModelWasEmpty = true,
				onExposedClicked = {},
				onFilterClicked = {},
				onMenu = {},
				onAddNewDerivation = {},
				onSeedSelect = {},
				onShowRoot = {},
				onOpenKey = { _, _ -> },
			)
		}
	}
}

@Preview(
	name = "light", group = "general", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "general",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF0D0D12,
)
@Composable
private fun PreviewKeySetDetailsScreenFiltered() {
	val state = remember { mutableStateOf<NetworkState?>(NetworkState.None) }
	val mockModel =
		KeySetDetailsModel.createStub().copy(keysAndNetwork = emptyList())
	val navController = rememberNavController()
	SignerNewTheme {
		Box(modifier = Modifier.size(360.dp, 720.dp)) {
			KeySetDetailsScreenView(
				model = mockModel,
				navController = navController,
				networkState = state,
				fullModelWasEmpty = false,
				onExposedClicked = {},
				onFilterClicked = {},
				onSeedSelect = {},
				onMenu = {},
				onAddNewDerivation = {},
				onShowRoot = {},
				onOpenKey = { _, _ -> },
			)
		}
	}
}
