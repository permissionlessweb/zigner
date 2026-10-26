package net.rotko.zigner.screens.settings.general

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.rotko.zigner.components.exposesecurity.ExposedAlert
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.screens.error.handleErrorAppState
import net.rotko.zigner.screens.settings.SettingsNavSubgraph
import net.rotko.zigner.ui.BottomSheetWrapperRoot
import net.rotko.zigner.ui.mainnavigation.CoreUnlockedNavSubgraph
import kotlinx.coroutines.launch


@Composable
internal fun SettingsGeneralNavSubgraph(
	coreNavController: NavController,
) {
	val context = LocalContext.current
	val vm: SettingsGeneralViewModel = viewModel()

	val appVersion = rememberSaveable { vm.getAppVersion(context) }
	val shieldState = vm.networkState.collectAsStateWithLifecycle()
	val lightThemeEnabled = vm.lightThemeEnabled.collectAsStateWithLifecycle()

	val menuNavController = rememberNavController()

	val securitySummary = rememberSaveable { vm.getSecuritySummary(context) }

	Box(modifier = Modifier.statusBarsPadding()) {
		SettingsScreenGeneralView(
			navController = coreNavController,
			onShowTerms = { coreNavController.navigate(SettingsNavSubgraph.terms) },
			onShowPrivacyPolicy = {
				coreNavController.navigate(SettingsNavSubgraph.privacyPolicy)
			},
			onBackup = { coreNavController.navigate(SettingsNavSubgraph.backup) },
			onMultisigSettings = { coreNavController.navigate(SettingsNavSubgraph.multisig) },
			onReleaseKey = { coreNavController.navigate(SettingsNavSubgraph.releaseKey) },
			onAdvancedSettings = { coreNavController.navigate(SettingsNavSubgraph.advanced) },
			onExposedClicked = { menuNavController.navigate(SettingsGeneralMenu.exposed_shield_alert) },
			onLightThemeToggle = { vm.toggleLightTheme() },
			isLightThemeEnabled = lightThemeEnabled.value,
			securitySummary = securitySummary,
			appVersion = appVersion,
			networkState = shieldState,
		)
	}

	NavHost(
		navController = menuNavController,
		startDestination = SettingsGeneralMenu.empty,
	) {
		val closeAction: Callback = {
			menuNavController.popBackStack()
		}
		composable(SettingsGeneralMenu.empty) {
			//no menu - Spacer element so when other part shown there won't
			// be an appearance animation from top left part despite there shouldn't be
			Spacer(modifier = Modifier.fillMaxSize(1f))
		}
		composable(SettingsGeneralMenu.exposed_shield_alert) {
			ExposedAlert(navigateBack = closeAction)
		}
	}
}


private object SettingsGeneralMenu {
	const val empty = "settings_menu_empty"
	const val exposed_shield_alert = "settings_exposed"
}
