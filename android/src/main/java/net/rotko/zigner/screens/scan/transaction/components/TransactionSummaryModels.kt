package net.rotko.zigner.screens.scan.transaction.components

import net.rotko.zigner.components.sharedcomponents.KeyCardModelBase
import net.rotko.zigner.domain.BASE58_STYLE_ABBREVIATE
import net.rotko.zigner.domain.abbreviateString
import net.rotko.zigner.ui.helpers.PreviewData
import io.parity.signer.uniffi.Card
import io.parity.signer.uniffi.MTransaction
import io.parity.signer.uniffi.ZcashTransactionSummary
import io.parity.signer.uniffi.ZcashOrchardSpend
import io.parity.signer.uniffi.ZcashOrchardOutput

/**
 * Local version of [Mtransaction] for the case of signing elements
 */

data class SigningTransactionModel(
	val summaryModels: List<TransactionSummaryModel>,
	val keyModel: KeyCardModelBase?,
) {
	companion object {
		fun createStub(): SigningTransactionModel =
			SigningTransactionModel(
				summaryModels = listOf(
					TransactionSummaryModel.Substrate(
						pallet = "Balances",
						method = "transfer_keep_alive",
						destination = "1219xC79CXV31543DDXoQMjuA",
						value = "0.2 WND",
						mTransactionIndex = 0,
					),
				),
				keyModel = KeyCardModelBase(
					path = "//polkadot//1",
					seedName = "Parity Keys",
					base58 = "1219xC79CXV31543DDXoQMjuA",
					identIcon = PreviewData.Identicon.dotIcon,
					hasPassword = true,
					networkLogo = "kusama"
				)
			)
	}
}

fun List<IndexedValue<MTransaction>>.toSigningTransactionModels(): List<SigningTransactionModel> {
	val fullModelsList: List<SigningTransactionModel> =
		map { it.toSigningTransactionModel() }
	val signatures = fullModelsList.map { it.keyModel }.toSet()
	return signatures.map { signature ->
		SigningTransactionModel(keyModel = signature,
			summaryModels = fullModelsList
				.filter { it.keyModel == signature }
				.map { it.summaryModels }
				.flatten())
	}
}

private fun IndexedValue<MTransaction>.toSigningTransactionModel(): SigningTransactionModel {
	val methodCards = value.content.method?.map { it.card } ?: emptyList()

	// detect zcash transaction
	val zcashSummary = methodCards.filterIsInstance<Card.ZcashSummaryCard>().firstOrNull()
	if (zcashSummary != null) {
		val spends = methodCards.filterIsInstance<Card.ZcashOrchardSpendCard>().map { it.f }
		val outputs = methodCards.filterIsInstance<Card.ZcashOrchardOutputCard>().map { it.f }
		return SigningTransactionModel(
			summaryModels = listOf(
				TransactionSummaryModel.Zcash(
					summary = zcashSummary.f,
					spends = spends,
					outputs = outputs,
					mTransactionIndex = index,
				)
			),
			keyModel = extractKeyModel(),
		)
	}

	// substrate (default)
	var pallet = ""
	var method = ""
	var destination = ""
	var parameter = ""

	for (methodCard in methodCards) {
		when (methodCard) {
			is Card.PalletCard -> pallet = methodCard.f
			is Card.CallCard -> method = methodCard.f.methodName
			is Card.IdCard -> destination = methodCard.f.base58.abbreviateString(BASE58_STYLE_ABBREVIATE)
			is Card.BalanceCard -> parameter = "${methodCard.f.amount} ${methodCard.f.units}"
			else -> {}
		}
	}
	return SigningTransactionModel(
		summaryModels = listOf(
			TransactionSummaryModel.Substrate(
				pallet = pallet,
				method = method,
				destination = destination,
				value = parameter,
				mTransactionIndex = index,
			)
		),
		keyModel = extractKeyModel(),
	)
}

private fun IndexedValue<MTransaction>.extractKeyModel(): KeyCardModelBase? {
	return value.authorInfo?.let { author ->
		KeyCardModelBase(
			path = author.address.path,
			seedName = author.address.seedName,
			base58 = author.base58,
			networkLogo = value.networkInfo?.networkLogo,
			identIcon = author.address.identicon,
			hasPassword = author.address.hasPwd,
		)
	}
}

sealed class TransactionSummaryModel {
	abstract val mTransactionIndex: Int

	data class Substrate(
		val pallet: String,
		val method: String,
		val destination: String,
		val value: String,
		override val mTransactionIndex: Int,
	) : TransactionSummaryModel()

	data class Zcash(
		val summary: ZcashTransactionSummary,
		val spends: List<ZcashOrchardSpend>,
		val outputs: List<ZcashOrchardOutput>,
		override val mTransactionIndex: Int,
	) : TransactionSummaryModel()
}
