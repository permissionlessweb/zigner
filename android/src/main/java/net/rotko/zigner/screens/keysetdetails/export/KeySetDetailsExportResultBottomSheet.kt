package net.rotko.zigner.screens.keysetdetails.export

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.BottomSheetHeader
import net.rotko.zigner.components.base.NotificationFrameText
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.qrcode.AnimatedQrKeysInfo
import net.rotko.zigner.components.qrcode.EmptyAnimatedQrKeysProvider
import net.rotko.zigner.components.sharedcomponents.KeyCard
import net.rotko.zigner.components.sharedcomponents.KeyCardModel
import net.rotko.zigner.components.sharedcomponents.KeySeedCard
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.KeySetDetailsModel
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.fill6

@Composable
fun KeySetDetailsExportResultBottomSheet(
	model: KeySetDetailsModel,
	selectedKeys: Set<String>,
	onClose: Callback,
) {
	Column() {
		val keysToExport = selectedKeys.size + 1 // + root key
		BottomSheetHeader(
			title = pluralStringResource(
				id = R.plurals.key_export_title,
				count = keysToExport,
				keysToExport,
			),
			onClose = onClose
		)
		val plateShape =
			RectangleShape
		//scrollable part
		Column(
			modifier = Modifier
				.verticalScroll(rememberScrollState())
				.weight(weight = 1f, fill = false)
				.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
				.background(MaterialTheme.colors.fill6, plateShape)
		) {
			if (LocalInspectionMode.current) {
				AnimatedQrKeysInfo(
					input = Unit,
					provider = EmptyAnimatedQrKeysProvider(),
					modifier = Modifier.padding(8.dp)
				)
			} else {
				AnimatedQrKeysInfo(
					input = KeySetDetailsExportService.GetQrCodesListRequest(
						seedName = model.root.seedName,
						keys = model.keysAndNetwork.map { it.key }
							.filter { selectedKeys.contains(it.addressKey) }),
					provider = KeySetDetailsExportService(),
					modifier = Modifier.padding(8.dp)
				)
			}
			NotificationFrameText(message = stringResource(id = R.string.key_set_export_description_content))
			KeySeedCard(
				model.root.identicon,
				base58 = model.root.base58,
			)
			SignerDivider()
			val seedList = selectedKeys.toList()
			for (i in 0..seedList.lastIndex) {
				val seed = seedList[i]
				val keyModel = model.keysAndNetwork
					.first { it.key.addressKey == seed }
				KeyCard(
					KeyCardModel.fromKeyModel(
						keyModel.key,
						keyModel.network
					),
				)
				if (i != seedList.lastIndex) {
					SignerDivider()
				}
			}
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
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewKeySetDetailsExportResultBottomSheet() {
	val model = KeySetDetailsModel.createStub()
	val selected = setOf(
		model.keysAndNetwork[0].key.addressKey,
		model.keysAndNetwork[1].key.addressKey,
	)
	SignerNewTheme {
		Box(modifier = Modifier.size(350.dp, 700.dp)) {
			KeySetDetailsExportResultBottomSheet(model, selected, {})
		}
	}
}
