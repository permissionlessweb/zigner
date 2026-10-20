package net.rotko.zigner.screens.scan.transaction.transactionElements

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.components.base.TappableAddress
import net.rotko.zigner.ui.theme.*
import io.parity.signer.uniffi.*

/**
 * Penumbra Transaction Summary Card
 * Shows overview: chain, fee, action counts, effect hash
 */
@Composable
fun TCPenumbraSummary(summary: PenumbraTransactionSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Penumbra Transaction",
            style = SignerTypeface.TitleS,
            color = MaterialTheme.colors.primary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Chain:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = summary.chainId,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Fee:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = "${summary.fee} ${summary.feeAsset}",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        summary.expiryHeight?.let { expiry ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Expiry:",
                    style = SignerTypeface.BodyL,
                    color = MaterialTheme.colors.textTertiary
                )
                Text(
                    text = "Block $expiry",
                    style = SignerTypeface.BodyL,
                    color = MaterialTheme.colors.primary
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Actions:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = "${summary.spendCount} spends, ${summary.outputCount} outputs",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Effect Hash:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = summary.effectHash.take(32) + "...",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textSecondary
            )
        }
    }
}

/**
 * Penumbra Spend Action Card
 */
@Composable
fun TCPenumbraSpend(spend: PenumbraSpendAction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Spend",
            style = SignerTypeface.LabelM,
            color = MaterialTheme.colors.pink300
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = spend.noteValue,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
            Text(
                text = spend.noteAsset,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
        }

        TappableAddress(address = spend.noteAddress)
    }
}

/**
 * Penumbra Output Action Card
 */
@Composable
fun TCPenumbraOutput(output: PenumbraOutputAction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Output",
            style = SignerTypeface.LabelM,
            color = MaterialTheme.colors.Crypto400
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = output.value,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
            Text(
                text = output.asset,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "To:",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = output.destAddress.take(30) + "...",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textSecondary
            )
        }
    }
}

/**
 * Penumbra Swap Action Card
 */
@Composable
fun TCPenumbraSwap(swap: PenumbraSwapAction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Swap",
            style = SignerTypeface.LabelM,
            color = MaterialTheme.colors.accentPink
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "From",
                    style = SignerTypeface.CaptionM,
                    color = MaterialTheme.colors.textTertiary
                )
                Text(
                    text = "${swap.inputValue} ${swap.inputAsset}",
                    style = SignerTypeface.BodyL,
                    color = MaterialTheme.colors.primary
                )
            }

            Text(
                text = "->",
                style = SignerTypeface.TitleS,
                color = MaterialTheme.colors.textTertiary
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "To",
                    style = SignerTypeface.CaptionM,
                    color = MaterialTheme.colors.textTertiary
                )
                Text(
                    text = swap.outputAsset,
                    style = SignerTypeface.BodyL,
                    color = MaterialTheme.colors.primary
                )
            }
        }
    }
}

/**
 * Penumbra Delegate Action Card
 */
@Composable
fun TCPenumbraDelegate(delegate: PenumbraDelegateAction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Delegate",
            style = SignerTypeface.LabelM,
            color = MaterialTheme.colors.Crypto400
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Amount:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = delegate.amount,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Validator:",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = delegate.validator.take(30) + "...",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textSecondary
            )
        }
    }
}

/**
 * Penumbra Vote Action Card
 */
@Composable
fun TCPenumbraVote(vote: PenumbraVoteAction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Vote",
            style = SignerTypeface.LabelM,
            color = MaterialTheme.colors.Crypto400
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Proposal #${vote.proposalId}",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Vote:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = vote.vote,
                style = SignerTypeface.BodyL,
                color = when(vote.vote.lowercase()) {
                    "yes" -> MaterialTheme.colors.Crypto400
                    "no" -> MaterialTheme.colors.red400
                    else -> MaterialTheme.colors.textTertiary
                }
            )
        }
    }
}

/**
 * Penumbra Generic Action Card
 * For schema-parsed actions that aren't specifically recognized
 */
@Composable
fun TCPenumbraGenericAction(action: PenumbraGenericAction) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = action.actionName,
                style = SignerTypeface.LabelM,
                color = if (action.recognized) MaterialTheme.colors.Crypto400 else MaterialTheme.colors.textTertiary
            )
            if (!action.recognized) {
                Text(
                    text = "(#${action.fieldNumber})",
                    style = SignerTypeface.CaptionM,
                    color = MaterialTheme.colors.textTertiary
                )
            }
        }

        if (action.description.isNotEmpty()) {
            Text(
                text = action.description,
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textSecondary
            )
        }

        action.fields.forEach { field ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "${field.first}:",
                    style = SignerTypeface.BodyL,
                    color = MaterialTheme.colors.textTertiary
                )
                Text(
                    text = field.second,
                    style = SignerTypeface.BodyL,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Penumbra Schema Info Card
 * Shows protocol version and action counts
 */
@Composable
fun TCPenumbraSchema(schema: PenumbraSchemaInfo) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Protocol Schema",
            style = SignerTypeface.LabelM,
            color = MaterialTheme.colors.textTertiary
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Chain:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = schema.chainId,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Version:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = schema.protocolVersion,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Actions:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = "${schema.actionCount} types (schema v${schema.schemaVersion})",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }
    }
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
private fun PreviewTCPenumbraSummary() {
    SignerNewTheme {
        TCPenumbraSummary(
            PenumbraTransactionSummary(
                chainId = "penumbra-1",
                expiryHeight = 1234567u,
                fee = "0.001",
                feeAsset = "UM",
                spendCount = 2u,
                outputCount = 2u,
                effectHash = "abc123def456789012345678901234567890123456789012345678901234"
            )
        )
    }
}

@Preview(
    name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
    showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Composable
private fun PreviewTCPenumbraSwap() {
    SignerNewTheme {
        TCPenumbraSwap(
            PenumbraSwapAction(
                inputValue = "100",
                inputAsset = "UM",
                outputAsset = "USDC"
            )
        )
    }
}
