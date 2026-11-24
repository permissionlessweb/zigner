package net.rotko.zigner.screens.keysets.restore.restorephrase

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import net.rotko.zigner.R
import net.rotko.zigner.components.base.ScreenHeaderProgressWithButton
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.screens.keysets.restore.KeysetRecoverModel
import net.rotko.zigner.ui.theme.SignerNewTheme
import net.rotko.zigner.ui.theme.SignerTypeface


@Composable
fun KeysetRecoverPhraseScreen(
	model: KeysetRecoverModel,
	backAction: Callback,
	onNewInput: (input: String) -> Unit,
	onAddSuggestedWord: (input: String) -> Unit,
	onDone: Callback,
	onScanOpen: Callback,
	modifier: Modifier = Modifier,
) {
	Column(
		modifier
			.fillMaxSize(1f)
			.background(MaterialTheme.colors.background)
			.verticalScroll(rememberScrollState()),
	) {
		ScreenHeaderProgressWithButton(
			canProceed = model.canProceed,
			currentStep = 2,
			allSteps = 3,
			btnText = stringResource(R.string.button_next),
			onClose = backAction,
			onButton = onDone,
			backNotClose = true,
		)
		Text(
			text = stringResource(R.string.recover_key_set_phrase_title),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.TitleL,
			modifier = Modifier.padding(horizontal = 24.dp),
		)
		Text(
			text = stringResource(R.string.recover_key_set_phrase_subtitle),
			color = MaterialTheme.colors.primary,
			style = SignerTypeface.BodyL,
			modifier = Modifier
				.padding(horizontal = 24.dp)
				.padding(top = 8.dp, bottom = 2.dp),
		)
		EnterSeedPhraseBox(
			enteredWords = model.enteredWords,
			rawUserInput = model.rawUserInput,
			modifier = Modifier
				.padding(horizontal = 16.dp)
				.padding(top = 8.dp, bottom = 12.dp),
			onEnteredChange = onNewInput,
			onScanOpen = onScanOpen,
		)
		RestoreSeedPhraseSuggest(
			guessWord = model.suggestedWords,
			onClicked = onAddSuggestedWord,
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
private fun PreviewKeysetRecoverPhraseScreenView() {
	SignerNewTheme {
		KeysetRecoverPhraseScreen(
			model = KeysetRecoverModel.stub(),
			backAction = {},
			onNewInput = { _ -> },
			onAddSuggestedWord = { _ -> },
			onDone = {},
			onScanOpen = {},
		)
	}
}
