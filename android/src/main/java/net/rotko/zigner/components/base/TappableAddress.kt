package net.rotko.zigner.components.base

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.fill6
import net.rotko.zigner.ui.theme.textSecondary
import net.rotko.zigner.ui.theme.textTertiary

/**
 * Recipient/spend address that defaults to a middle-truncated form for layout
 * and expands to the full address (monospace, wrapped, 4-char chunked) on tap.
 *
 * Truncation hides exactly the bytes an attacker would substitute, so the
 * user MUST be able to inspect the full address before approving a transaction.
 * See issue #10.
 */
@Composable
fun TappableAddress(
    address: String,
    modifier: Modifier = Modifier,
    headChars: Int = 20,
    tailChars: Int = 8,
) {
    if (address.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }

    val truncated = if (address.length <= headChars + tailChars + 1) {
        address
    } else {
        address.take(headChars) + "…" + address.takeLast(tailChars)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        if (expanded) {
            Text(
                text = chunked4(address),
                style = SignerTypeface.CaptionM.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colors.textSecondary,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RectangleShape)
                    .background(MaterialTheme.colors.fill6)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text(
                text = "Tap to collapse",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp),
            )
        } else {
            Text(
                text = truncated,
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textTertiary,
            )
            Text(
                text = "Tap to reveal full address",
                style = SignerTypeface.CaptionM,
                color = MaterialTheme.colors.textTertiary,
                modifier = Modifier.padding(top = 2.dp, start = 2.dp),
            )
        }
    }
}

/** Group an address into 4-char chunks separated by spaces, preserving order. */
private fun chunked4(s: String): String =
    s.chunked(4).joinToString(" ")
