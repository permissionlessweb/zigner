package net.rotko.zigner.screens.keysetdetails.seedselectmenu

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.BottomSheetHeader
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.KeySetModel
import net.rotko.zigner.domain.KeySetsListModel
import net.rotko.zigner.screens.keydetails.MenuItemForBottomSheet
import net.rotko.zigner.ui.helpers.PreviewData
import net.rotko.zigner.ui.theme.SignerNewTheme


@Composable
internal fun SeedSelectMenuView(
	keySetsListModel: KeySetsListModel,
	selectedSeed: String,
	onSelectSeed: (String) -> Unit,
	onNewKeySet: Callback,
	onRecoverKeySet: Callback,
	onClose: Callback,
) {
	Column(
		modifier = Modifier
			.heightIn(max = 520.dp)
			.verticalScroll(rememberScrollState()),
	) {
		BottomSheetHeader(
			title = stringResource(R.string.key_sets_screem_title),
			onClose = onClose
		)
		SignerDivider()
		keySetsListModel.keys.forEach { item ->
			KeySetItem(
				model = item,
				isSelected = item.seedName == selectedSeed,
				onClick = {
					onSelectSeed(item.seedName)
				},
			)
		}
		SignerDivider()
		MenuItemForBottomSheet(
			iconId = R.drawable.ic_add_28,
			label = stringResource(R.string.add_key_set_menu_add),
			onclick = onNewKeySet,
			modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
		)
		MenuItemForBottomSheet(
			iconId = R.drawable.ic_download_offline_28,
			label = stringResource(R.string.add_key_set_menu_recover),
			onclick = onRecoverKeySet,
			modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
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
private fun PreviewSeedSelectMenu() {
	val keys = mutableListOf(
		KeySetModel(
			"first seed name",
			PreviewData.Identicon.dotIcon,
			listOf("westend", "some"),
			1.toUInt()
		),
		KeySetModel(
			"second seed name",
			PreviewData.Identicon.dotIcon,
			listOf("kusama", "some"),
			3.toUInt()
		),
	)
	val mockModel = KeySetsListModel(keys)
	SignerNewTheme {
		SeedSelectMenuView(keySetsListModel = mockModel,
			selectedSeed = "first seed name",
			onSelectSeed = {},
			onNewKeySet = {},
			onRecoverKeySet = {},
			onClose = {})
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
private fun PreviewSeedSelectMenuALot() {
	val keys = mutableListOf(
		KeySetModel(
			"first seed name",
			PreviewData.Identicon.dotIcon,
			listOf("westend", "some"),
			1.toUInt()
		),
		KeySetModel(
			"second seed name",
			PreviewData.Identicon.dotIcon,
			listOf("kusama", "some"),
			3.toUInt()
		),
	)
	repeat(30) {
		keys.add(
			KeySetModel(
				"second seed name2",
				PreviewData.Identicon.dotIcon,
				listOf("westend", "some"),
				3.toUInt()
			)
		)
	}
	val mockModel = KeySetsListModel(keys)
	SignerNewTheme {
		SeedSelectMenuView(keySetsListModel = mockModel,
			selectedSeed = "second seed name",
			onSelectSeed = {},
			onNewKeySet = {},
			onRecoverKeySet = {},
			onClose = {})
	}
}
