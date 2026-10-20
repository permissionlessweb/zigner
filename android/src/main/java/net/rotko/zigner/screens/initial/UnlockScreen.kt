package net.rotko.zigner.screens.initial

import android.content.res.Configuration
import androidx.compose.foundation.layout.fillMaxSize
import net.rotko.zigner.components.base.ScrollArt
import timber.log.Timber
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import net.rotko.zigner.R
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.ui.rootnavigation.MainGraphRoutes
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.pink500
import net.rotko.zigner.ui.theme.textTertiary


@Composable
fun UnlockAppAuthScreen(onUnlockClicked: Callback) {
	Column(modifier = Modifier.fillMaxSize()) {
		ScrollArt(art = R.drawable.art_enso, modifier = Modifier.weight(1f))
		Text(
			modifier = Modifier
				.fillMaxWidth(1f)
				.padding(horizontal = 24.dp),
			text = stringResource(R.string.unlock_screen_title),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleXl,
			textAlign = TextAlign.Center,
		)
		Spacer(modifier = Modifier.padding(top = 40.dp))
		PrimaryButtonWide(
			label = stringResource(R.string.unlock_app_button),
			onClicked = onUnlockClicked,
			modifier = Modifier.padding(horizontal = 24.dp),
		)
		Spacer(modifier = Modifier.padding(top = 32.dp))
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
private fun PreviewUnlockAppAuthScreen() {
	SignerNewTheme {
		UnlockAppAuthScreen {}
	}
}


/**
 * Screen shown while unlock is in progress to prevent flicker
 */
@Composable
fun UnlockingScreen(status: String) {
	Column(
		modifier = Modifier
			.fillMaxSize()
			.padding(24.dp),
		horizontalAlignment = Alignment.CenterHorizontally,
		verticalArrangement = Arrangement.Center
	) {
		Image(
			painter = painterResource(id = R.drawable.app_logo),
			modifier = Modifier.size(64.dp),
			contentDescription = "Icon"
		)
		Spacer(modifier = Modifier.height(24.dp))
		Text(
			text = stringResource(R.string.unlock_screen_title),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleL,
			textAlign = TextAlign.Center,
		)
		Spacer(modifier = Modifier.height(16.dp))
		CircularProgressIndicator(
			color = MaterialTheme.colors.pink500,
			modifier = Modifier.size(32.dp)
		)
		Spacer(modifier = Modifier.height(16.dp))
		Text(
			text = status,
			color = MaterialTheme.colors.textTertiary,
			style = SignerTypeface.BodyL,
			textAlign = TextAlign.Center,
		)
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
private fun PreviewUnlockingScreen() {
	SignerNewTheme {
		UnlockingScreen(status = "Initializing database...")
	}
}
