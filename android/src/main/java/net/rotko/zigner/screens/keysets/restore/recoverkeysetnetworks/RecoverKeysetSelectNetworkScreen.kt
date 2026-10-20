package net.rotko.zigner.screens.keysets.restore.recoverkeysetnetworks

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ModalBottomSheetValue
import androidx.compose.material.Text
import androidx.compose.material.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.bottomsheets.ProceedEmptyKeysetConfirmation
import net.rotko.zigner.components.base.NotificationFrameText
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.ScreenHeaderProgressWithButton
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.items.NetworkItemMultiselect
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.NetworkModel
import net.rotko.zigner.screens.keysets.create.backupstepscreens.NetworkItemMultiselectAll
import net.rotko.zigner.ui.BottomSheetWrapperContent
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.fill6
import kotlinx.coroutines.launch


@Composable
@OptIn(ExperimentalMaterialApi::class)
fun RecoverKeysetSelectNetworkScreenBase(
	networks: List<NetworkModel>,
	selected: MutableState<Set<String>>,
	defaultSelectedNetworks: Set<String>,
	onProceedAction: Callback,
	onBack: Callback,
) {
	val confirmBottomSheetState =
		rememberModalBottomSheetState(
			ModalBottomSheetValue.Hidden,
			confirmValueChange = {
				it != ModalBottomSheetValue.HalfExpanded
			},
			skipHalfExpanded = false
		)
	val scope = rememberCoroutineScope()

	BottomSheetWrapperContent(
		bottomSheetState = confirmBottomSheetState,
		bottomSheetContent = {
			ProceedEmptyKeysetConfirmation(
				onCancel = { scope.launch { confirmBottomSheetState.hide() } },
				onProceed = onProceedAction,
			)
		},
		mainContent = {
			RecoverKeysetSelectNetworkScreenPrivate(
				networks = networks,
				selectedNetworkKeys = selected.value,
				onNetworkClick = { network ->
					selected.value = if (selected.value.contains(network.key)) {
						selected.value - network.key
					} else {
						selected.value + network.key
					}
				},
				onProceed = {
					if (selected.value.isNotEmpty()) {
						onProceedAction()
					} else {
						scope.launch { confirmBottomSheetState.show() }
					}
				},
				onAddAll = {
					selected.value = if (selected.value.size == networks.size) {
						defaultSelectedNetworks
					} else {
						networks.map { it.key }.toSet()
					}
				},
				onBack = onBack,
			)
		},
	)
}

@Composable
private fun RecoverKeysetSelectNetworkScreenPrivate(
	networks: List<NetworkModel>,
	selectedNetworkKeys: Set<String>,
	onNetworkClick: (NetworkModel) -> Unit,
	onProceed: Callback,
	onAddAll: Callback,
	onBack: Callback
) {
	Column(
		modifier = Modifier
			.fillMaxSize(1f)
			.background(MaterialTheme.colors.background)
			.verticalScroll(rememberScrollState()),
	) {
		ScreenHeaderProgressWithButton(
			canProceed = false,
			currentStep = 3,
			allSteps = 3,
			btnText = stringResource(R.string.button_next),
			onClose = onBack,
			onButton = null,
			backNotClose = true,
		)
		Text(
			text = stringResource(R.string.keyset_recover_keys_title),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleL,
			modifier = Modifier.padding(horizontal = 24.dp),
		)
		Text(
			text = stringResource(R.string.keyset_recover_keys_subtitle),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.BodyL,
			modifier = Modifier
				.padding(horizontal = 24.dp)
		)
		Column(
			modifier = Modifier
				.padding(horizontal = 8.dp, vertical = 16.dp)
				.background(
					MaterialTheme.colors.fill6,
					RectangleShape
				)
		) {
			networks.forEach { network ->
				NetworkItemMultiselect(
					network = network,
					isSelected = selectedNetworkKeys.contains(network.key)
				) { network ->
					onNetworkClick(network)
				}
				SignerDivider()
			}
			NetworkItemMultiselectAll(onAddAll)
		}
		NotificationFrameText(
			message = stringResource(R.string.keyset_recover_keys_notification_text),
			modifier = Modifier
				.padding(horizontal = 16.dp)
		)
		Spacer(modifier = Modifier.weight(1f))

		PrimaryButtonWide(
			label = stringResource(R.string.generic_done),
			modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
			onClicked = onProceed,
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
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewNewKeySetSelectNetwork() {
	val networks = listOf(
		NetworkModel(
			key = "0",
			logo = "polkadot",
			title = "Polkadot",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "1",
			logo = "Kusama",
			title = "Kusama",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "2",
			logo = "Wastend",
			title = "Wastend",
			pathId = "polkadot",
		),
	)
	val selected = setOf(networks[1].key)
	SignerNewTheme {
		RecoverKeysetSelectNetworkScreenPrivate(networks, selected, {}, {}, {}, {})
	}
}

@Preview(
	name = "light", group = "general", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "general",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewNewKeySetSelectNetworkVeryLong() {
	val networks = listOf(
		NetworkModel(
			key = "0",
			logo = "polkadot",
			title = "Polkadot",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "1",
			logo = "Kusama",
			title = "Kusama",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "2",
			logo = "Wastend",
			title = "Wastend",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "0",
			logo = "polkadot",
			title = "Polkadot",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "1",
			logo = "Kusama",
			title = "Kusama",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "2",
			logo = "Wastend",
			title = "Wastend",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "0",
			logo = "polkadot",
			title = "Polkadot",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "1",
			logo = "Kusama",
			title = "Kusama",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "2",
			logo = "Wastend",
			title = "Wastend",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "0",
			logo = "polkadot",
			title = "Polkadot",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "1",
			logo = "Kusama",
			title = "Kusama",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "2",
			logo = "Wastend",
			title = "Wastend",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "0",
			logo = "polkadot",
			title = "Polkadot",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "1",
			logo = "Kusama",
			title = "Kusama",
			pathId = "polkadot",
		),
		NetworkModel(
			key = "2",
			logo = "Wastend",
			title = "Wastend",
			pathId = "polkadot",
		),
	)
	val selected = setOf(networks[1].key)
	SignerNewTheme {
		RecoverKeysetSelectNetworkScreenPrivate(networks, selected, {}, {}, {}, {})
	}
}
