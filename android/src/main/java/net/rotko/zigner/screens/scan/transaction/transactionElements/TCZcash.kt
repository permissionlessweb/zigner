package net.rotko.zigner.screens.scan.transaction.transactionElements

import android.content.res.Configuration
import net.rotko.zigner.ui.theme.pink500
import net.rotko.zigner.ui.theme.warn
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.ui.theme.*
import io.parity.signer.uniffi.*

/**
 * Zcash Transaction Summary Card
 * Shows overview: network, fee, action counts, sighash
 */
@Composable
fun TCZcashSummary(summary: ZcashTransactionSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Zcash transaction",
                style = SignerTypeface.TitleS,
                color = MaterialTheme.colors.primary
            )
            Text(
                text = if (summary.mainnet) "Mainnet" else "Testnet",
                style = SignerTypeface.LabelM,
                color = if (summary.mainnet) MaterialTheme.colors.pink500 else MaterialTheme.colors.warn,
                modifier = Modifier
                    .clip(RectangleShape)
                    .background(MaterialTheme.colors.fill6)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Fee:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = "${summary.fee} ZEC",
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
                text = "${summary.spendCount} spends, ${summary.outputCount} outputs",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Sighash:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = summary.sighash.take(32) + "...",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textSecondary
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Anchor:",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = summary.anchor.take(24) + "...",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textSecondary
            )
        }
    }
}

/**
 * Zcash Orchard Spend Action Card
 */
@Composable
fun TCZcashOrchardSpend(spend: ZcashOrchardSpend) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "Orchard spend",
            style = SignerTypeface.LabelM,
            color = MaterialTheme.colors.pink300
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = spend.value,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
            Text(
                text = "ZEC",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "Nullifier:",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textTertiary
            )
            Text(
                text = spend.nullifier.take(24) + "...",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textSecondary
            )
        }
    }
}

/**
 * Zcash Orchard Output Action Card
 */
@Composable
fun TCZcashOrchardOutput(output: ZcashOrchardOutput) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.fill6)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = if (output.isChange) "Orchard Change" else "Orchard Output",
                style = SignerTypeface.LabelM,
                color = if (output.isChange) MaterialTheme.colors.textTertiary else MaterialTheme.colors.Crypto400
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = output.value,
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.primary
            )
            Text(
                text = "ZEC",
                style = SignerTypeface.BodyL,
                color = MaterialTheme.colors.textTertiary
            )
        }

        if (!output.isChange) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "To:",
                    style = SignerTypeface.CaptionM,
                    color = MaterialTheme.colors.textTertiary
                )
                Text(
                    text = output.recipient.take(30) + "...",
                    style = SignerTypeface.CaptionM,
                    color = MaterialTheme.colors.textSecondary
                )
            }
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
private fun PreviewTCZcashSummary() {
    SignerNewTheme {
        TCZcashSummary(
            ZcashTransactionSummary(
                mainnet = true,
                fee = "0.0001",
                spendCount = 2u,
                outputCount = 2u,
                sighash = "abc123def456789012345678901234567890123456789012345678901234",
                anchor = "def456789012345678901234567890123456789012345678901234567890"
            )
        )
    }
}

@Preview(
    name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
    showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Composable
private fun PreviewTCZcashOrchardSpend() {
    SignerNewTheme {
        TCZcashOrchardSpend(
            ZcashOrchardSpend(
                nullifier = "abc123def456789012345678901234567890",
                value = "1.5",
                cmx = "xyz789"
            )
        )
    }
}

@Preview(
    name = "light", group = "themes", uiMode = Configuration.UI_MODE_NIGHT_NO,
    showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Composable
private fun PreviewTCZcashOrchardOutput() {
    SignerNewTheme {
        TCZcashOrchardOutput(
            ZcashOrchardOutput(
                value = "1.0",
                recipient = "u1abc123def456789012345678901234567890",
                isChange = false
            )
        )
    }
}
