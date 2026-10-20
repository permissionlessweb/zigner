package net.rotko.zigner.screens.keysetdetails.empty

import android.content.res.Configuration
import androidx.compose.foundation.layout.navigationBarsPadding
import net.rotko.zigner.components.base.ScrollArt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.PrimaryButtonWide
import net.rotko.zigner.components.base.SecondaryButtonWide
import net.rotko.zigner.components.exposesecurity.ExposedIcon
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.NetworkState
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface
import net.rotko.zigner.ui.theme.textSecondary

@Composable
fun NoKeySetEmptyWelcomeScreen(
	onExposedShow: Callback,
	onNewKeySet: Callback,
	onRecoverKeySet: Callback,
	networkState: State<NetworkState?>, //for shield icon
) {
	Column(
		modifier = Modifier
			.fillMaxHeight(1f)
			.navigationBarsPadding(),
		horizontalAlignment = Alignment.CenterHorizontally
	) {
		ScrollArt(art = R.drawable.art_samurai, modifier = Modifier.weight(1f))
		Text(
			text = stringResource(R.string.key_sets_empty_title),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleXl,
			textAlign = TextAlign.Center,
			modifier = Modifier
				.padding(horizontal = 64.dp),
		)
		Text(
			text = stringResource(R.string.key_sets_empty_message),
			color = MaterialTheme.colors.textSecondary,
			style = SignerTypeface.BodyL,
			textAlign = TextAlign.Center,
			modifier = Modifier
				.padding(horizontal = 64.dp),
		)
		Spacer(modifier = Modifier.padding(top = 24.dp))
		ExposedIcon(
			networkState = networkState,
			onClick = onExposedShow,
			Modifier
				.align(Alignment.End)
				.padding(end = 16.dp)
		)
		PrimaryButtonWide(
			label = stringResource(R.string.key_sets_screem_add_key_button),
			modifier = Modifier
				.padding(top = 16.dp, bottom = 8.dp, start = 24.dp, end = 24.dp),
			onClicked = onNewKeySet,
		)
		SecondaryButtonWide(
			label = stringResource(R.string.add_key_set_menu_recover),
			modifier = Modifier
				.padding(top = 0.dp, bottom = 24.dp, start = 24.dp, end = 24.dp),
			withBackground = true,
			onClicked = onRecoverKeySet,
		)
	}
}


@Preview(
	name = "light", group = "few", uiMode = Configuration.UI_MODE_NIGHT_NO,
	showBackground = true, backgroundColor = 0xFFFFFFFF,
)
@Preview(
	name = "dark", group = "few",
	uiMode = Configuration.UI_MODE_NIGHT_YES,
	showBackground = true, backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewNoKeySetEmptyScreen() {
	val state = remember { mutableStateOf(NetworkState.Past) }
	SignerNewTheme {
			NoKeySetEmptyWelcomeScreen(
				{},{}, {}, state,
			)
	}
}
