package net.rotko.zigner.screens.scan.transaction.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.MarkdownText
import net.rotko.zigner.components.base.toRichTextStr
import net.rotko.zigner.screens.scan.transaction.transactionElements.*
import io.parity.signer.uniffi.Card
import io.parity.signer.uniffi.TransactionCard

/**
 * Selector for transaction card appearance
 */
@Composable
fun TransactionElementSelector(card: TransactionCard) {
	Box(
		modifier = Modifier
			.padding(
				start = (card.indent.toInt() * 10).dp + 16.dp,
				end = 16.dp
			)
			.fillMaxWidth()
	) {
		when (val txCard = card.card) {
			// Substrate-only card kinds. Their screens and card renderers are
			// deleted (zigner signs zcash and penumbra only); this branch is
			// unreachable in practice because the scanner refuses substrate
			// payloads by default (ScanCapability.SUBSTRATE), but the `when`
			// must stay exhaustive since the uniffi Card type is untouched.
			is Card.AuthorPlainCard,
			is Card.AuthorPublicKeyCard,
			is Card.CallCard,
			is Card.EnumVariantNameCard,
			is Card.FieldNameCard,
			is Card.FieldNumberCard,
			is Card.NewSpecsCard,
			is Card.MetaCard,
			is Card.VerifierCard,
			is Card.DerivationsCard,
			is Card.TxSpecPlainCard,
			is Card.NetworkInfoCard,
			is Card.TypesInfoCard,
			is Card.EraMortalCard,
			is Card.IdCard -> Unit

			// Error handling
			is Card.ErrorCard -> TCError(error = txCard.f)
			is Card.WarningCard -> TCWarning(txCard.f)

			// Simple values with identicons / icons / markdown
			is Card.TextCard -> MarkdownText(txCard.f.toRichTextStr()) // Markdown text field, not present on new designs

			// Simple values - redesigned
			is Card.AuthorCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_from),
				value = txCard.f.base58,
				valueInSameLine = false
			)
			is Card.BalanceCard -> TCNameValueElement(
				value = "${txCard.f.amount} ${txCard.f.units}",
			)
			is Card.BitVecCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_bitvec),
				value = txCard.f,
			)
			is Card.BlockHashCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_backhash),
				value = txCard.f,
				valueInSameLine = false
			)
			is Card.DefaultCard -> TCNameValueElement(
				name = txCard.f,
			)
			Card.EraImmortalCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_transaction_immortal),
			)
			is Card.IdentityFieldCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_identityfield),
				value = txCard.f,
			)
			is Card.NameVersionCard -> TCNameValueElement(
				name = txCard.f.name,
				value = txCard.f.version,
			)
			is Card.NetworkGenesisHashCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_genesis_hash),
				value = txCard.f,
				valueInSameLine = false,
			)
			is Card.NetworkNameCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_network_name),
				value = txCard.f,
			)
			is Card.NonceCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_nonce),
				value = txCard.f,
			)
			Card.NoneCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_none),
			)
			is Card.PalletCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_pallet),
				value = txCard.f,
			)
			is Card.TipCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_tip),
				value = "${txCard.f.amount} ${txCard.f.units}",
			)
			is Card.TipPlainCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_tip),
				value = txCard.f,
			)
			is Card.TxSpecCard -> TCNameValueElement(
				name = stringResource(R.string.transaction_field_tx_version),
				value = txCard.f,
			)
			is Card.VarNameCard -> TCNameValueElement(
				value = txCard.f,
			)


			// Zcash transaction cards
			is Card.ZcashSummaryCard -> TCZcashSummary(summary = txCard.f)
			is Card.ZcashOrchardSpendCard -> TCZcashOrchardSpend(spend = txCard.f)
			is Card.ZcashOrchardOutputCard -> TCZcashOrchardOutput(output = txCard.f)
		}
	}
}

@Composable
fun TransactionCards(
	transactions: List<TransactionCard>,
) {
	transactions.forEach {
		TransactionElementSelector(it)
	}
}
