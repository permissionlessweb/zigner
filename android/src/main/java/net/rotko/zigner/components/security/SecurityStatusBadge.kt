package net.rotko.zigner.components.security

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import androidx.compose.ui.graphics.RectangleShape
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.ui.theme.*

/**
 * Compact security status badge for displaying in transaction screens.
 * Shows green when secure, yellow for warnings, red for problems.
 */
@Composable
fun SecurityStatusBadge(
    status: SecurityDisplayStatus,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when (status.level) {
        SecurityLevel.SECURE -> MaterialTheme.colors.Crypto400.copy(alpha = 0.15f)
        SecurityLevel.WARNING -> MaterialTheme.colors.accentPink.copy(alpha = 0.15f)
        SecurityLevel.INSECURE -> MaterialTheme.colors.red400.copy(alpha = 0.15f)
    }

    val iconColor = when (status.level) {
        SecurityLevel.SECURE -> MaterialTheme.colors.Crypto400
        SecurityLevel.WARNING -> MaterialTheme.colors.accentPink
        SecurityLevel.INSECURE -> MaterialTheme.colors.red400
    }

    val icon = when (status.level) {
        SecurityLevel.SECURE -> Icons.Default.CheckCircle
        SecurityLevel.WARNING -> Icons.Default.Info
        SecurityLevel.INSECURE -> Icons.Default.Warning
    }

    Row(
        modifier = modifier
            .clip(RectangleShape)
            .background(backgroundColor)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(16.dp)
        )

        Text(
            text = status.summary,
            style = SignerTypeface.CaptionM,
            color = iconColor
        )
    }
}

data class SecurityDisplayStatus(
    val level: SecurityLevel,
    val summary: String,
    val details: List<SecurityDetail>
)

data class SecurityDetail(
    val label: String,
    val value: String,
    val isSecure: Boolean
)

enum class SecurityLevel {
    SECURE,
    WARNING,
    INSECURE
}

@Preview(
    name = "light", group = "themes", uiMode = UI_MODE_NIGHT_NO,
    showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
    name = "dark", group = "themes", uiMode = UI_MODE_NIGHT_YES,
    showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewSecurityStatusBadgeSecure() {
    SignerNewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SecurityStatusBadge(
                status = SecurityDisplayStatus(
                    level = SecurityLevel.SECURE,
                    summary = "StrongBox + MTE",
                    details = emptyList()
                )
            )
            SecurityStatusBadge(
                status = SecurityDisplayStatus(
                    level = SecurityLevel.WARNING,
                    summary = "TEE (no MTE)",
                    details = emptyList()
                )
            )
            SecurityStatusBadge(
                status = SecurityDisplayStatus(
                    level = SecurityLevel.INSECURE,
                    summary = "Software only",
                    details = emptyList()
                )
            )
        }
    }
}
