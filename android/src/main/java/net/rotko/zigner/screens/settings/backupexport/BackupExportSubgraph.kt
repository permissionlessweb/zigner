package net.rotko.zigner.screens.settings.backupexport

import androidx.compose.runtime.Composable
import net.rotko.zigner.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.storage.RepoResult
import net.rotko.zigner.screens.initial.WaitingScreen

@Composable
fun BackupExportSubgraph(
	seedName: String,
	onClose: Callback,
) {
	val seedRepository = ServiceLocator.activityScope?.seedRepository
	var seedPhrase by remember { mutableStateOf<String?>(null) }
	var error by remember { mutableStateOf<String?>(null) }

	LaunchedEffect(seedName) {
		if (seedRepository != null) {
			when (val result = seedRepository.getSeedPhraseForceAuth(seedName)) {
				is RepoResult.Success -> {
					seedPhrase = result.result
				}
				is RepoResult.Failure -> {
					error = result.error.message ?: "Failed to get seed phrase"
				}
			}
		}
	}

	when {
		error != null -> {
			onClose()
		}
		seedPhrase != null -> {
			BackupExportScreen(
				seedName = seedName,
				seedPhrase = seedPhrase!!,
				onClose = onClose,
			)
		}
		else -> {
			WaitingScreen(stringResource(R.string.waiting_unlocking_seed))
		}
	}
}
