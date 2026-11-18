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
import androidx.compose.material.icons.filled.AirplanemodeActive
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.ui.theme.*

/**
 * Prominent indicator showing whether Zigner is in Online or Offline (airgap) mode.
 * Tapping opens settings to change mode.
 */
@Composable
fun OperatingModeIndicator(
    isOnlineMode: Boolean,
    isAirgapBreached: Boolean,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val backgroundColor = when {
        !isOnlineMode && isAirgapBreached -> MaterialTheme.colors.red400.copy(alpha = 0.15f)
        !isOnlineMode -> MaterialTheme.colors.Crypto400.copy(alpha = 0.15f)
        else -> MaterialTheme.colors.accentPink.copy(alpha = 0.15f)
    }

    val contentColor = when {
        !isOnlineMode && isAirgapBreached -> MaterialTheme.colors.red400
        !isOnlineMode -> MaterialTheme.colors.Crypto400
        else -> MaterialTheme.colors.accentPink
    }

    val icon = when {
        !isOnlineMode && !isAirgapBreached -> Icons.Default.AirplanemodeActive
        !isOnlineMode && isAirgapBreached -> Icons.Default.Wifi
        else -> Icons.Default.Wifi
    }

    val label = when {
        !isOnlineMode && isAirgapBreached -> "AIRGAP BREACHED"
        !isOnlineMode -> "OFFLINE"
        else -> "ONLINE"
    }

    Row(
        modifier = modifier
            .clip(RectangleShape)
            .background(backgroundColor)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = contentColor,
            modifier = Modifier.size(14.dp)
        )

        Text(
            text = label,
            style = SignerTypeface.LabelS,
            color = contentColor
        )
    }
}

/**
 * Full mode status bar showing both operating mode and security status.
 * For use at the top of main screens.
 */
@Composable
fun ModeAndSecurityBar(
    isOnlineMode: Boolean,
    isAirgapBreached: Boolean,
    securityStatus: SecurityDisplayStatus?,
    onModeClick: (() -> Unit)? = null,
    onSecurityClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OperatingModeIndicator(
            isOnlineMode = isOnlineMode,
            isAirgapBreached = isAirgapBreached,
            onClick = onModeClick
        )

        securityStatus?.let { status ->
            SecurityStatusBadge(
                status = status,
                onClick = onSecurityClick
            )
        }
    }
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
private fun PreviewOperatingModeIndicator() {
    SignerNewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OperatingModeIndicator(
                isOnlineMode = false,
                isAirgapBreached = false
            )
            OperatingModeIndicator(
                isOnlineMode = false,
                isAirgapBreached = true
            )
            OperatingModeIndicator(
                isOnlineMode = true,
                isAirgapBreached = false
            )
        }
    }
}

@Preview(
    name = "bar", group = "components", uiMode = UI_MODE_NIGHT_NO,
    showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Composable
private fun PreviewModeAndSecurityBar() {
    SignerNewTheme {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ModeAndSecurityBar(
                isOnlineMode = false,
                isAirgapBreached = false,
                securityStatus = SecurityDisplayStatus(
                    level = SecurityLevel.SECURE,
                    summary = "StrongBox + MTE",
                    details = emptyList()
                )
            )
            ModeAndSecurityBar(
                isOnlineMode = true,
                isAirgapBreached = false,
                securityStatus = SecurityDisplayStatus(
                    level = SecurityLevel.SECURE,
                    summary = "StrongBox + MTE",
                    details = emptyList()
                )
            )
            ModeAndSecurityBar(
                isOnlineMode = false,
                isAirgapBreached = true,
                securityStatus = SecurityDisplayStatus(
                    level = SecurityLevel.WARNING,
                    summary = "TEE",
                    details = emptyList()
                )
            )
        }
    }
}
