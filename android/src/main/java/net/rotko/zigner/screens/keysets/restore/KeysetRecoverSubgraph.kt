package net.rotko.zigner.screens.keysets.restore

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.rotko.zigner.domain.backend.AuthOperationResult
import net.rotko.zigner.domain.popUpToTop
import net.rotko.zigner.domain.submitErrorState
import net.rotko.zigner.screens.error.handleErrorAppState
import net.rotko.zigner.screens.keysets.restore.keysetname.KeysetRecoverNameScreen
import net.rotko.zigner.screens.keysets.restore.restorephrase.KeysetRecoverPhraseScreen
import net.rotko.zigner.screens.keysets.restore.restorephrase.RecoverKeysetSelectNetworkRestoreFlowFullScreen
import net.rotko.zigner.ui.mainnavigation.CoreUnlockedNavSubgraph


@Composable
fun KeysetRecoverSubgraph(
	coreNavController: NavController,
) {

	val viewModel: KeysetRecoverViewModel = viewModel()
	val model = viewModel.recoverSeed.collectAsStateWithLifecycle()
	var keysetName by rememberSaveable() {
		mutableStateOf("")
	}
	// The phrase stays on the view model. A navigation path splits on the
	// spaces between words, so the keystore was saving only the first word
	// and the next phrase that started with that word was a collision.

	val localNavController = rememberNavController()
	NavHost(
		navController = localNavController,
		startDestination = KeysetRecoverSubgraph.keysetName,
	) {

		composable(KeysetRecoverSubgraph.keysetName) {
			val existingSeedNames =
				viewModel.existingSeeds.collectAsStateWithLifecycle()
			KeysetRecoverNameScreen(
				initialKeySetName = keysetName,
				onBack = coreNavController::popBackStack,
				onNext = { restoredName ->
					keysetName = restoredName
					localNavController.navigate(KeysetRecoverSubgraph.seedPhrase)
				},
				seedNames = existingSeedNames.value,
				modifier = Modifier
					.statusBarsPadding()
					.imePadding()
			)
		}
		composable(KeysetRecoverSubgraph.seedPhrase) {
			KeysetRecoverPhraseScreen(
				model = model.value,
				backAction = localNavController::popBackStack,
				onNewInput = { newInput -> viewModel.onUserInput(newInput) },
				onAddSuggestedWord = { suggestedWord ->
					viewModel.onAddword(suggestedWord)
				},
				onDone = {
					if (model.value.validSeed) {
						localNavController.navigate(KeysetRecoverSubgraph.NetworksSelection.route)
					} else {
						submitErrorState("navigation to finish called, but seed is not valid")
					}
				},
				onScanOpen = { coreNavController.navigate(CoreUnlockedNavSubgraph.Camera.destination(keysetName)) },
				modifier = Modifier
					.statusBarsPadding()
					.imePadding(),
			)
		}
		composable(route = KeysetRecoverSubgraph.NetworksSelection.route) {
			val context = LocalContext.current

			RecoverKeysetSelectNetworkRestoreFlowFullScreen(
				seedName = keysetName,
				seedPhrase = model.value.enteredWords.joinToString(separator = " "),
				onBack = localNavController::popBackStack,
				showError = { error: AuthOperationResult ->
					error.handleErrorAppState(coreNavController, context)
				},
				navigateOnSuccess = {
					coreNavController.navigate(
						CoreUnlockedNavSubgraph.KeySet.destination(
							keysetName
						)
					) {
						popUpToTop(coreNavController)
					}
				},
			)
		}
	}
}

private object KeysetRecoverSubgraph {
	const val keysetName = "recover_keyset_name"
	const val seedPhrase = "recover_keyset_phrase"

	object NetworksSelection {
		const val route = "recover_keyset_networks_confirmation"
	}
}
