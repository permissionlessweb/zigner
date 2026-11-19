package net.rotko.zigner.screens.settings.verifiercert

import android.content.res.Configuration
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.ScreenHeader
import net.rotko.zigner.components.base.SignerDivider
import net.rotko.zigner.domain.*
import net.rotko.zigner.screens.scan.transaction.transactionElements.TCNameValueOppositeElement
import net.rotko.zigner.ui.theme.*


// Number of taps on the verifier certificate value that reveals developer options.
private const val REVEAL_TAP_TARGET = 5
// Start showing the "N taps away" hint from this tap onwards (Android-style).
private const val REVEAL_TAP_HINT_AFTER = 2
// Taps must land within this window of each other or the counter resets.
private const val TAP_WINDOW_MS = 3_000L

// Mutable, non-recomposing holder for the tap gesture. Kept out of snapshot state
// on purpose: taps must not trigger recomposition, only accumulate.
private class VerifierTapState {
	var count: Int = 0
	var lastTapMs: Long = 0L
}

@Composable
internal fun VerifierScreen(
    verifierDetails: VerifierDetailsModel,
    onBack: Callback,
    onRemove: Callback,
    developerOptionsRevealed: Boolean = false,
    onRevealDeveloperOptions: Callback = {},
) {
	val context = LocalContext.current
	// Hidden 5-tap gesture, styled after Android's "tap Build number 7 times"
	// developer-mode unlock. Tapping the verifier certificate value (a field a
	// normal user never interacts with) 5 times within a short window REVEALS the
	// developer options. It does not enable anything on its own - online mode stays
	// off and still requires an explicit, authenticated confirmation to turn on.
	val tapState = remember { VerifierTapState() }
	val tapGesture: Callback = {
		val now = SystemClock.elapsedRealtime()
		if (now - tapState.lastTapMs > TAP_WINDOW_MS) {
			tapState.count = 0
		}
		tapState.lastTapMs = now
		tapState.count += 1

		when {
			developerOptionsRevealed -> {
				// Already unlocked; silently ignore further taps.
			}
			tapState.count >= REVEAL_TAP_TARGET -> {
				tapState.count = 0
				onRevealDeveloperOptions()
				Toast.makeText(
					context,
					context.getString(R.string.developer_options_revealed_toast),
					Toast.LENGTH_LONG,
				).show()
			}
			tapState.count >= REVEAL_TAP_HINT_AFTER -> {
				val remaining = REVEAL_TAP_TARGET - tapState.count
				Toast.makeText(
					context,
					context.resources.getQuantityString(
						R.plurals.developer_options_countdown_toast,
						remaining,
						remaining,
					),
					Toast.LENGTH_SHORT,
				).show()
			}
		}
	}

	Column(
		Modifier
			.background(MaterialTheme.colors.backgroundPrimary)
			.fillMaxSize(1f)
	) {
		ScreenHeader(
			title = stringResource(R.string.verifier_certificate_title),
			onBack = onBack,
		)
		Column(
			verticalArrangement = Arrangement.spacedBy(8.dp),
			modifier = Modifier
				.padding(top = 16.dp, bottom = 8.dp, start = 8.dp, end = 8.dp)
				.background(
					MaterialTheme.colors.fill6,
					RectangleShape
				)
				.clickable(
					indication = null,
					interactionSource = remember { MutableInteractionSource() },
					onClick = tapGesture,
				)
				.padding(16.dp),
		) {
			TCNameValueOppositeElement(
				name = stringResource(R.string.network_details_verifier_public_key_for_general),
				value = verifierDetails.publicKey,
				valueInSameLine = false,
			)
			SignerDivider(sidePadding = 0.dp)
			TCNameValueOppositeElement(
				name = stringResource(R.string.network_details_verifier_crypto_field),
				value = verifierDetails.encryption
			)
		}
		Text(
			text = stringResource(R.string.verifier_certificate_remove_cta),
			style = SignerTypeface.TitleS,
			color = MaterialTheme.colors.red400,
			modifier = Modifier
				.clickable(onClick = onRemove)
				.fillMaxWidth(1f)
				.padding(vertical = 14.dp, horizontal = 24.dp)
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
private fun PreviewNetworksList() {
	SignerNewTheme {
		VerifierScreen(VerifierDetailsModel.createStub(), {}, {})
	}
}
