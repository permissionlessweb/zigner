package net.rotko.zigner.screens.scan.transaction.components

import android.content.res.Configuration
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.components.base.TappableAddress
import net.rotko.zigner.components.sharedcomponents.KeyCardSignature
import net.rotko.zigner.screens.scan.transaction.transactionElements.TCNameValueElement
import net.rotko.zigner.ui.theme.*
import io.parity.signer.uniffi.ZcashTransactionSummary
import io.parity.signer.uniffi.ZcashOrchardSpend
import io.parity.signer.uniffi.ZcashOrchardOutput

@Composable
fun TransactionSummaryView(
	model: SigningTransactionModel,
	onTransactionClicked: (mTransactionInex: Int) -> Unit,
) {
	val plateShape =
		RoundedCornerShape(dimensionResource(id = R.dimen.qrShapeCornerRadius))
	Column(
		modifier = Modifier
			.padding(vertical = 8.dp, horizontal = 16.dp)
			.background(
				MaterialTheme.colors.fill6,
				plateShape
			)
			.padding(16.dp)
	) {
		Text(
			text = stringResource(R.string.transaction_summary_field_transaction_details),
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.CaptionM,
			modifier = Modifier.padding(bottom = 8.dp)
		)
		model.summaryModels.forEach { summary ->
			when (summary) {
				is TransactionSummaryModel.Zcash -> ZcashSummaryRow(
					summary = summary,
					onClick = { onTransactionClicked(summary.mTransactionIndex) }
				)
				is TransactionSummaryModel.Substrate -> SubstrateSummaryRow(
					summary = summary,
					onClick = { onTransactionClicked(summary.mTransactionIndex) }
				)
			}
			SignerDivider(
				modifier = Modifier.padding(vertical = 8.dp),
				sidePadding = 0.dp
			)
		}
		model.keyModel?.let { keyModel ->
			Column() {
				Text(
					text = stringResource(R.string.transaction_summary_field_sign_with),
					color = MaterialTheme.colors.textTertiary,
					style = SignerTypeface.CaptionM,
					modifier = Modifier.padding(bottom = 8.dp)
				)
				KeyCardSignature(model = keyModel,)
			}
		}
	}
}

/** Substrate transaction summary (original) */
@Composable
private fun SubstrateSummaryRow(
	summary: TransactionSummaryModel.Substrate,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier.clickable(onClick = onClick),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column() {
			TCNameValueElement(
				name = stringResource(R.string.transaction_summary_field_pallet),
				value = summary.pallet
			)
			TCNameValueElement(
				name = stringResource(R.string.transaction_summary_field_method),
				value = summary.method
			)
			TCNameValueElement(
				name = stringResource(R.string.transaction_summary_field_destination),
				value = summary.destination
			)
			TCNameValueElement(
				name = stringResource(R.string.transaction_summary_field_value),
				value = summary.value
			)
		}
		Spacer(modifier = Modifier.weight(1f))
		ChevronIcon()
	}
}

/** Zcash transaction summary */
@Composable
private fun ZcashSummaryRow(
	summary: TransactionSummaryModel.Zcash,
	onClick: () -> Unit,
) {
	Row(
		modifier = Modifier.clickable(onClick = onClick),
		verticalAlignment = Alignment.CenterVertically,
	) {
		Column(
			modifier = Modifier.weight(1f),
			verticalArrangement = Arrangement.spacedBy(6.dp),
		) {
			// header
			Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				Text(
					text = "Zcash",
					style = SignerTypeface.TitleS,
					color = MaterialTheme.colors.primary,
				)
				Text(
					text = if (summary.summary.mainnet) "Mainnet" else "Testnet",
					style = SignerTypeface.LabelM,
					color = if (summary.summary.mainnet) Color(0xFF3498db) else Color(0xFFe74c3c),
					modifier = Modifier
						.clip(RoundedCornerShape(4.dp))
						.background(MaterialTheme.colors.fill6)
						.padding(horizontal = 6.dp, vertical = 2.dp),
				)
			}

			// send details from outputs
			for (output in summary.outputs.filter { !it.isChange }) {
				TCNameValueElement(name = "Send", value = "${output.value} ZEC")
				Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
					Text(
						text = "To",
						style = SignerTypeface.BodyL,
						color = MaterialTheme.colors.textTertiary,
					)
					TappableAddress(address = output.recipient)
				}
			}

			// change
			for (output in summary.outputs.filter { it.isChange }) {
				TCNameValueElement(name = "Change", value = "${output.value} ZEC")
			}

			// fee + actions
			TCNameValueElement(name = "Fee", value = "${summary.summary.fee} ZEC")
			TCNameValueElement(
				name = "Actions",
				value = "${summary.summary.spendCount} spends, ${summary.summary.outputCount} outputs"
			)
		}
		ChevronIcon()
	}
}

@Composable
private fun ChevronIcon() {
	Image(
		imageVector = Icons.Filled.ChevronRight,
		contentDescription = stringResource(R.string.transaction_summary_field_chrvron_description),
		colorFilter = ColorFilter.tint(MaterialTheme.colors.textTertiary),
		modifier = Modifier
			.size(28.dp)
			.padding(end = 8.dp)
	)
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
private fun PreviewTransactionSummaryView() {
	SignerNewTheme {
		TransactionSummaryView(SigningTransactionModel.createStub()) {}
	}
}
