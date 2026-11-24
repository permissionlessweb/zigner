package net.rotko.zigner.screens.keysets.create

import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import net.rotko.zigner.domain.BackupReminder
import net.rotko.zigner.domain.backend.AuthOperationResult
import net.rotko.zigner.domain.backend.toOperationResult
import net.rotko.zigner.domain.popUpToTop
import net.rotko.zigner.screens.error.handleErrorAppState
import net.rotko.zigner.screens.keysets.create.backupstepscreens.NewKeySetBackupBottomSheet
import net.rotko.zigner.screens.keysets.create.backupstepscreens.NewKeySetBackupScreen
import net.rotko.zigner.screens.keysets.create.backupstepscreens.NewKeySetSelectNetworkScreen
import net.rotko.zigner.ui.BottomSheetWrapperRoot
import net.rotko.zigner.ui.mainnavigation.CoreUnlockedNavSubgraph


@Composable
fun NewKeysetSubgraph(
	coreNavController: NavHostController,
) {

	val subgraphNavController = rememberNavController()

	val vm: NewKeysetNameViewModel = viewModel()
	var seedName by rememberSaveable() {
		mutableStateOf("")
	}
	// True when the key set is finished without a confirmed paper backup;
	// the key set screen then keeps a backup prompt up (BackupReminder).
	var backupPending by rememberSaveable() {
		mutableStateOf(false)
	}
	val seedPhrase = rememberSaveable() {
		vm.createNewSeedPhrase().toOperationResult().handleErrorAppState(coreNavController) ?: ""
	}

	NavHost(
		navController = subgraphNavController,
		startDestination = NewKeySetBackupStepSubgraph.NewKeySetName,
	) {
		composable(NewKeySetBackupStepSubgraph.NewKeySetName) {
			NewKeySetNameScreen(
				prefilledName = seedName,
				onBack = { coreNavController.popBackStack() },
				onNextStep = {
					seedName = it
					subgraphNavController.navigate(
						NewKeySetBackupStepSubgraph.NewKeySetBackup
					)
				},
				modifier = Modifier
					.statusBarsPadding()
					.imePadding(),
			)
		}
		composable(NewKeySetBackupStepSubgraph.NewKeySetBackup) {
			NewKeySetBackupScreen(
				seedPhrase = seedPhrase,
				onProceed = {
					subgraphNavController.navigate(
						NewKeySetBackupStepSubgraph.NewKeySetBackupConfirmation
					)
				},
				onBananaSplit = {
					// Skip to network selection - Banana Split backup can be created after key set creation
					backupPending = true
					subgraphNavController.navigate(NewKeySetBackupStepSubgraph.NewKeySetSelectNetworks) {
						popUpTo(NewKeySetBackupStepSubgraph.NewKeySetBackup)
					}
				},
				onBackupLater = {
					// Create the key set without showing the phrase; the backup
					// is offered from the key set screen until it is done.
					backupPending = true
					subgraphNavController.navigate(NewKeySetBackupStepSubgraph.NewKeySetSelectNetworks) {
						popUpTo(NewKeySetBackupStepSubgraph.NewKeySetBackup)
					}
				},
				onBack = { subgraphNavController.popBackStack() },
				modifier = Modifier.statusBarsPadding(),
			)
		}
		composable(NewKeySetBackupStepSubgraph.NewKeySetBackupConfirmation) {
			NewKeySetBackupScreen(
				seedPhrase = seedPhrase,
				initiallyRevealed = true,
				onProceed = {
					subgraphNavController.navigate(
						NewKeySetBackupStepSubgraph.NewKeySetBackupConfirmation
					)
				},
				onBananaSplit = {
					// Skip to network selection - Banana Split backup can be created after key set creation
					subgraphNavController.navigate(NewKeySetBackupStepSubgraph.NewKeySetSelectNetworks) {
						popUpTo(NewKeySetBackupStepSubgraph.NewKeySetBackup)
					}
				},
				onBack = { subgraphNavController.popBackStack() },
				modifier = Modifier.statusBarsPadding(),
			)
			BottomSheetWrapperRoot(onClosedAction = subgraphNavController::popBackStack) {
				NewKeySetBackupBottomSheet(
					onProceed = {
						backupPending = false
						subgraphNavController.navigate(NewKeySetBackupStepSubgraph.NewKeySetSelectNetworks) {
							popUpTo(NewKeySetBackupStepSubgraph.NewKeySetBackup)
						}
					},
					onCancel = subgraphNavController::popBackStack,
				)
			}
		}
		composable(NewKeySetBackupStepSubgraph.NewKeySetSelectNetworks) {
			val context = LocalContext.current
			NewKeySetSelectNetworkScreen(
				seedName = seedName,
				seedPhrase = seedPhrase,
				onSuccess = {
					if (backupPending) {
						BackupReminder.markPending(seedName, context)
					}
					coreNavController.navigate(
						CoreUnlockedNavSubgraph.KeySet.destination(
							seedName
						)
					) {
							popUpToTop(coreNavController)
					}
				},
				showError = { error: AuthOperationResult ->
					error.handleErrorAppState(coreNavController, context)
				},
				onBack = subgraphNavController::popBackStack,
			)
		}
	}
}

internal object NewKeySetBackupStepSubgraph {
	const val NewKeySetName = "new_keyset_name_select"
	const val NewKeySetBackup = "new_keyset_backup_main"
	const val NewKeySetBackupConfirmation = "new_keyset_backup_confirmation"
	const val NewKeySetSelectNetworks = "new_keyset_select_networkis"
}
