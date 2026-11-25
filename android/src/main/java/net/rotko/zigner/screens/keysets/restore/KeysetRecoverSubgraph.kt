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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
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
					val phrase = viewModel.commitForProceed()
					if (phrase != null) {
						localNavController.navigate(
							KeysetRecoverSubgraph.NetworksSelection.destination(
								phrase.joinToString(separator = " ")
							)
						)
					} else {
						submitErrorState("navigation to finish called, but seed is not valid")
					}
				},
				onScanOpen = { coreNavController.navigate(CoreUnlockedNavSubgraph.Camera.destination(keysetName)) },
				onSelectWord = { index -> viewModel.onSelectWord(index) },
				modifier = Modifier
					.statusBarsPadding()
					.imePadding(),
			)
		}
		composable(
			route = KeysetRecoverSubgraph.NetworksSelection.route,
			arguments = listOf(
				navArgument(KeysetRecoverSubgraph.NetworksSelection.seedPhrase) {
					type = NavType.StringType
				}
			)
		) {
			val seedPhrase =
				it.arguments?.getString(KeysetRecoverSubgraph.NetworksSelection.seedPhrase)!!
			val context = LocalContext.current

			RecoverKeysetSelectNetworkRestoreFlowFullScreen(
				seedName = keysetName,
				seedPhrase = seedPhrase,
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
		internal const val seedPhrase = "seed_name_arg"
		private const val baseRoute = "recover_keyset_networks_confirmation"
		const val route = "$baseRoute/{$seedPhrase}"
		fun destination(seedName: String) = "$baseRoute/${seedName}"
	}
}
