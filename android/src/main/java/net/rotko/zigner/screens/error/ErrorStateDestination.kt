package net.rotko.zigner.screens.error

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.backend.UniffiResult
import net.rotko.zigner.domain.backend.toOperationResult
import net.rotko.zigner.screens.error.wrongversion.errorWrongVersionSubgraph
import net.rotko.zigner.screens.initial.eachstartchecks.airgap.AirgapScreen
import net.rotko.zigner.ui.mainnavigation.CoreUnlockedNavSubgraph
import kotlinx.coroutines.launch


fun NavGraphBuilder.errorStateDestination(
	navController: NavController,
) {
	composable(
		route = CoreUnlockedNavSubgraph.ErrorScreenGeneral.route,
		arguments = listOf(
			navArgument(CoreUnlockedNavSubgraph.ErrorScreenGeneral.argHeader) {
				type = NavType.StringType
			},
			navArgument(CoreUnlockedNavSubgraph.ErrorScreenGeneral.argDescription) {
				type = NavType.StringType
			},
			navArgument(CoreUnlockedNavSubgraph.ErrorScreenGeneral.argVerbose) {
				type = NavType.StringType
			},
		),
	) {
		val argHeader =
			it.arguments?.getString(CoreUnlockedNavSubgraph.ErrorScreenGeneral.argHeader)!!
		val argDescr =
			it.arguments?.getString(CoreUnlockedNavSubgraph.ErrorScreenGeneral.argDescription)!!
		val argVerbose =
			it.arguments?.getString(CoreUnlockedNavSubgraph.ErrorScreenGeneral.argVerbose)!!

		ErrorStateScreen(
			header = argHeader,
			description = argDescr,
			verbose = argVerbose,
			onBack = { navController.popBackStack() },
			modifier = Modifier.statusBarsPadding()
		)
	}
	errorWrongVersionSubgraph(navController)
	composable(route = CoreUnlockedNavSubgraph.airgapBreached) {
		BackHandler {
			//disable back navigation on this screen
		}
		val scope = rememberCoroutineScope()
		AirgapScreen(
			isInitialOnboarding = false,
			onProceed = {
				navController.popBackStack()
			},
			onEnableOnlineMode = {
				ServiceLocator.networkExposedStateKeeper.acceptThresholdSigning()
				scope.launch {
					navController.popBackStack()
				}
			}
		)
	}
}


inline fun <reified T> UniffiResult<T>.handleErrorAppState(coreNavController: NavController): T? {
	return this.toOperationResult().handleErrorAppState(coreNavController)
}

