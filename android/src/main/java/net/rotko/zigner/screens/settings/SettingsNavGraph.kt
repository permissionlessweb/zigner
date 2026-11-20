package net.rotko.zigner.screens.settings

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation
import net.rotko.zigner.components.documents.PpScreen
import net.rotko.zigner.components.documents.TosScreen
import net.rotko.zigner.screens.settings.backup.SeedBackupIntegratedScreen
import net.rotko.zigner.screens.settings.frost.FrostBackupAgeScreen
import net.rotko.zigner.screens.settings.frost.FrostBackupSelectionScreen
import net.rotko.zigner.screens.settings.frost.FrostSettingsScreen
import net.rotko.zigner.screens.settings.general.SettingsAdvancedNavSubgraph
import net.rotko.zigner.screens.settings.general.SettingsGeneralNavSubgraph
import net.rotko.zigner.screens.settings.logs.logsNavigationSubgraph
import net.rotko.zigner.screens.settings.releasekey.ReleaseKeyIntegratedScreen
import net.rotko.zigner.screens.settings.verifiercert.verifierSettingsDestination
import net.rotko.zigner.ui.mainnavigation.CoreUnlockedNavSubgraph

/**
 * Settings screen; General purpose stuff like legal info, networks management
 * and history should be here. This is final point in navigation:
 * all subsequent interactions should be in modals or drop-down menus
 */
fun NavGraphBuilder.settingsFullSubgraph(
	coreNavController: NavController,
) {
	navigation(
		route = CoreUnlockedNavSubgraph.settings,
		startDestination = SettingsNavSubgraph.home,
	) {
		composable(
			SettingsNavSubgraph.home,
			enterTransition = { fadeIn(animationSpec = tween(700)) },
			exitTransition = { fadeOut(animationSpec = tween(700)) },
		) {
			SettingsGeneralNavSubgraph(coreNavController = coreNavController)
		}
		composable(SettingsNavSubgraph.terms) {
			Box(modifier = Modifier.statusBarsPadding()) {
				TosScreen(onBack = {
					coreNavController.popBackStack(SettingsNavSubgraph.home, false)
				})
			}
		}
		composable(SettingsNavSubgraph.privacyPolicy) {
			Box(modifier = Modifier.statusBarsPadding()) {
				PpScreen(onBack = {
					coreNavController.popBackStack(SettingsNavSubgraph.home, false)
				})
			}
		}
		composable(SettingsNavSubgraph.backup) {
			SeedBackupIntegratedScreen(coreNavController) {
				coreNavController.popBackStack(SettingsNavSubgraph.home, false)
			}
		}
		logsNavigationSubgraph(
			navController = coreNavController,
		)
		verifierSettingsDestination(coreNavController)
		composable(SettingsNavSubgraph.multisig) {
			FrostSettingsScreen(
				onBack = { coreNavController.popBackStack() },
				onBackup = { coreNavController.navigate(SettingsNavSubgraph.multisigBackup) },
				onBackupToKey = { coreNavController.navigate(SettingsNavSubgraph.multisigBackupAge) },
			)
		}
		composable(SettingsNavSubgraph.multisigBackup) {
			FrostBackupSelectionScreen(
				onBack = { coreNavController.popBackStack() },
			)
		}
		composable(SettingsNavSubgraph.multisigBackupAge) {
			FrostBackupAgeScreen(
				onBack = { coreNavController.popBackStack() },
			)
		}
		composable(SettingsNavSubgraph.releaseKey) {
			ReleaseKeyIntegratedScreen {
				coreNavController.popBackStack(SettingsNavSubgraph.home, false)
			}
		}
		composable(SettingsNavSubgraph.advanced) {
			SettingsAdvancedNavSubgraph(coreNavController = coreNavController)
		}
	}
}

internal object SettingsNavSubgraph {
	const val home = "settings_home"
	const val terms = "settings_terms_of_service"
	const val privacyPolicy = "settings_privacy_polcy"
	const val backup = "settings_backup"
	const val advanced = "settings_advanced"
	const val logs = "settings_logs"
	const val generalVerifier = "settings_general_verifier"
	const val zcashTestQr = "settings_zcash_test_qr"
	const val multisig = "settings_multisig"
	const val multisigBackup = "settings_multisig_backup"
	const val multisigBackupAge = "settings_multisig_backup_age"
	const val releaseKey = "settings_release_key"
}

