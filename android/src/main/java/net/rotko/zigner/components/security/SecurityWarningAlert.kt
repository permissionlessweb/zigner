package net.rotko.zigner.components.security

import android.content.res.Configuration.UI_MODE_NIGHT_NO
import androidx.compose.ui.graphics.RectangleShape
import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.theme.*

/**
 * Full-screen security warning shown before signing on insecure devices.
 * User must acknowledge the risk to continue.
 */
@Composable
fun SecurityWarningAlert(
    status: SecurityDisplayStatus,
    onContinueAnyway: Callback,
    onCancel: Callback,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Warning icon
        Box(
            modifier = Modifier
                .padding(vertical = 24.dp)
                .size(80.dp)
                .clip(RectangleShape)
                .background(MaterialTheme.colors.red400.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colors.red400,
                modifier = Modifier.size(40.dp)
            )
        }

        Text(
            text = "Security Warning",
            color = MaterialTheme.colors.red400,
            style = SignerTypeface.TitleL,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = when (status.level) {
                SecurityLevel.INSECURE ->
                    "This device lacks hardware security protection. " +
                    "Your private keys could be extracted by malware or a physical attacker."
                SecurityLevel.WARNING ->
                    "This device has reduced security. " +
                    "Some protection features are unavailable."
                SecurityLevel.SECURE ->
                    "Device security is strong."
            },
            color = MaterialTheme.colors.textSecondary,
            style = SignerTypeface.BodyL,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Show what's missing
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RectangleShape)
                .background(MaterialTheme.colors.fill6)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Security Status:",
                style = SignerTypeface.LabelM,
                color = MaterialTheme.colors.textTertiary
            )

            status.details.filter { !it.isSecure }.forEach { detail ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colors.red400,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "${detail.label}: ${detail.value}",
                        style = SignerTypeface.BodyL,
                        color = MaterialTheme.colors.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Cancel button (primary action - safer choice)
        PrimaryButtonWide(
            label = "Cancel",
            modifier = Modifier.fillMaxWidth(),
        ) {
            onCancel()
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Continue anyway (secondary - risky choice)
        SecondaryButtonWide(
            label = "I understand the risks",
            modifier = Modifier.fillMaxWidth(),
            withBackground = false,
        ) {
            onContinueAnyway()
        }
    }
}

/**
 * Compact inline warning banner for transaction preview.
 */
@Composable
fun SecurityWarningBanner(
    message: String,
    onClick: Callback? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RectangleShape)
            .background(MaterialTheme.colors.red400.copy(alpha = 0.15f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colors.red400,
            modifier = Modifier.size(20.dp)
        )

        Text(
            text = message,
            style = SignerTypeface.BodyL,
            color = MaterialTheme.colors.red400,
            modifier = Modifier.weight(1f)
        )
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
private fun PreviewSecurityWarningAlert() {
    SignerNewTheme {
        SecurityWarningAlert(
            status = SecurityDisplayStatus(
                level = SecurityLevel.INSECURE,
                summary = "Software only",
                details = listOf(
                    SecurityDetail("Key Storage", "Software only (no TEE)", false),
                    SecurityDetail("Memory Protection", "Unavailable", false),
                    SecurityDetail("Biometric Binding", "Disabled", false)
                )
            ),
            onContinueAnyway = {},
            onCancel = {}
        )
    }
}

@Preview(
    name = "banner", group = "components", uiMode = UI_MODE_NIGHT_NO,
    showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Composable
private fun PreviewSecurityWarningBanner() {
    SignerNewTheme {
        SecurityWarningBanner(
            message = "Device lacks hardware security"
        )
    }
}
