package net.rotko.zigner.screens.keysetdetails.exportroot

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.networkicon.IdentIconImage
import net.rotko.zigner.components.qrcode.AnimatedQrKeysInfo
import net.rotko.zigner.components.qrcode.EmptyAnimatedQrKeysProvider
import net.rotko.zigner.components.sharedcomponents.ShowBase58Collapsible
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.KeyModel
import net.rotko.zigner.screens.keysetdetails.export.KeySetDetailsExportService
import net.rotko.zigner.ui.helpers.PreviewData
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.appliedStroke
import net.rotko.zigner.ui.theme.fill6

@Composable
fun KeySetRootExportBottomSheet(
	model: KeyModel,
	onClose: Callback,
) {
	Column() {
		val plateShape =
			RectangleShape
		Column(
			modifier = Modifier
				.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
				.clip(plateShape)
				.border(
					BorderStroke(1.dp, MaterialTheme.colors.appliedStroke),
					plateShape
				)
				.background(MaterialTheme.colors.fill6, plateShape)
		) {
			Box(
				modifier = Modifier
					.padding(top = 8.dp, start = 8.dp, end = 8.dp)
					.fillMaxWidth(1f)
					.aspectRatio(1.1f)
					.background(
						Color.White,
						plateShape
					),
				contentAlignment = Alignment.Center,
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
							seedName = model.seedName,
							keys = emptyList()
						),
						provider = KeySetDetailsExportService(),
						modifier = Modifier.padding(8.dp)
					)
				}
			}
			Row(
				Modifier.padding(16.dp),
				verticalAlignment = Alignment.CenterVertically,
			) {
				ShowBase58Collapsible(
					base58 = model.base58,
					modifier = Modifier
						.weight(1f)
				)
				IdentIconImage(
					identicon = model.identicon,
					size = 36.dp,
					modifier = Modifier.padding(start = 8.dp)
				)
			}
		}
		SecondaryButtonWide(
			label = stringResource(R.string.generic_close_button),
			withBackground = true,
			onClicked = onClose,
			modifier = Modifier.padding(24.dp)
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
private fun PreviewKeySetDetailsExportResultBottomSheet() {
	val model = KeyModel.createStub()
		.copy(identicon = PreviewData.Identicon.jdenticonIcon)
	SignerNewTheme {
		Box(modifier = Modifier.size(350.dp, 700.dp)) {
			KeySetRootExportBottomSheet(model, {})
		}
	}
}
