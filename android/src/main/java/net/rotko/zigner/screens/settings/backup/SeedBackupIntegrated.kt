package net.rotko.zigner.screens.settings.backup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import net.rotko.zigner.domain.BackupReminder
import net.rotko.zigner.domain.Callback


@Composable
fun SeedBackupIntegratedScreen(
	coreNavController: NavController,
	onBack: Callback
) {
	var selectedSeed by remember { mutableStateOf<String?>(null) }

	BackHandler(enabled = selectedSeed != null) {
		selectedSeed = null
	}
	val viewModel = viewModel<SeedBackupViewModel>()
	val context = LocalContext.current
	val seeds = viewModel.getSeeds()

	// content
	Box(modifier = Modifier.statusBarsPadding()) {
		SeedBackupScreen(
			seeds = seeds,
			coreNavController = coreNavController,
			onBack = onBack
		) { seed ->
			selectedSeed = seed
		}
	}

	// bottomsheet and it is full screen
	selectedSeed?.let { selectedSeedValue ->
		SeedBackupFullOverlayBottomSheet(
			seedName = selectedSeedValue,
			getSeedPhraseForBackup = viewModel::getSeedPhrase,
			onClose = {
				// the phrase has been shown in a backup: drop the key set's prompt
				BackupReminder.clear(selectedSeedValue, context)
				selectedSeed = null
			}
		)
	}
}
