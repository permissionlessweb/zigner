package net.rotko.zigner.screens.keysetdetails

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.KeyModel
import net.rotko.zigner.domain.KeySetDetailsModel
import net.rotko.zigner.domain.NetworkModel
import net.rotko.zigner.domain.NetworkState
import net.rotko.zigner.domain.backend.BackupInteractor
import net.rotko.zigner.domain.backend.OperationResult
import net.rotko.zigner.domain.backend.UniffiResult
import net.rotko.zigner.domain.backend.mapInner
import net.rotko.zigner.domain.storage.RepoResult
import net.rotko.zigner.domain.usecases.AllNetworksUseCase
import net.rotko.zigner.domain.usecases.DBVersionValidationUseCase
import io.parity.signer.uniffi.ErrorDisplayed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch


class KeySetDetailsViewModel : ViewModel() {
	private val preferencesRepository = ServiceLocator.preferencesRepository
	private val uniffiInteractor = ServiceLocator.uniffiInteractor
	private val backupInteractor = BackupInteractor()
	private val allNetworksUseCase = AllNetworksUseCase(uniffiInteractor)
	private val networkExposedStateKeeper =
		ServiceLocator.networkExposedStateKeeper
	private val seedRepository = ServiceLocator.activityScope!!.seedRepository
	private val dbVersionValidate = DBVersionValidationUseCase()

	val filters: StateFlow<Set<String>> =
		preferencesRepository.networksFilter.stateIn(
			viewModelScope,
			SharingStarted.WhileSubscribed(5_000),
			initialValue = emptySet(),
		)
	val networkState: StateFlow<NetworkState> =
		networkExposedStateKeeper.airGapModeState

	private val _shownSeedName: MutableStateFlow<String?> = MutableStateFlow(null)
	val shownSeedName: StateFlow<String?> = _shownSeedName.asStateFlow()

	private val fullScreenState =
		MutableStateFlow<OperationResult<KeySetDetailsScreenState, ErrorDisplayed>>(
			OperationResult.Ok(KeySetDetailsScreenState.LoadingState)
		)

	val filteredScreenState: StateFlow<OperationResult<KeySetDetailsScreenState, ErrorDisplayed>> =
		fullScreenState.combine(filters) { fullState, filter ->
			when (fullState) {
				is OperationResult.Err -> fullState
				is OperationResult.Ok -> {
					if (filter.isEmpty()) fullState else {
						val value = fullState.result
						val result: KeySetDetailsScreenState =
							when (value) {
								is KeySetDetailsScreenState.Data -> {
									KeySetDetailsScreenState.Data(
										filteredModel = value.filteredModel.copy(keysAndNetwork = value.filteredModel.keysAndNetwork.filter {
											filter.contains(
												it.network.networkSpecsKey
											)
										}),
										wasEmptyKeyset = value.wasEmptyKeyset,
									)
								}

								KeySetDetailsScreenState.NoKeySets,
								KeySetDetailsScreenState.LoadingState -> {
									value
								}
							}
						OperationResult.Ok(result)
					}
				}
			}
		}.stateIn(
			viewModelScope,
			SharingStarted.WhileSubscribed(1_000),
			initialValue = fullScreenState.value,
		)

	private suspend fun openKeySet(seedName: String): OperationResult<KeySetDetailsScreenState, ErrorDisplayed> {
		preferencesRepository.setLastSelectedSeed(seedName)
		_shownSeedName.value = seedName
		return uniffiInteractor.keySetBySeedName(seedName)
			.mapInner {
				KeySetDetailsScreenState.Data(
					filteredModel = it,
					wasEmptyKeyset = it.keysAndNetwork.isEmpty()
				)
			}
	}

	private suspend fun getKeySetDetails(requestedSeedName: String?): OperationResult<KeySetDetailsScreenState, ErrorDisplayed> {
		val names = seedRepository.getLastKnownSeedNames()
		if (names.isEmpty() && requestedSeedName == null) {
			return OperationResult.Ok(KeySetDetailsScreenState.NoKeySets)
		}

		// A named selection stays on that key set. Falling through to the
		// first name made the second Terp key set look like it could not be opened.
		val target = requestedSeedName
			?: preferencesRepository.getLastSelectedSeed()?.takeIf { names.contains(it) }
			?: names.firstOrNull()
			?: return OperationResult.Ok(KeySetDetailsScreenState.NoKeySets)

		val opened = openKeySet(target)
		if (opened is OperationResult.Ok || requestedSeedName != null) {
			return opened
		}

		for (name in names) {
			if (name == target) continue
			val other = openKeySet(name)
			if (other is OperationResult.Ok) return other
		}
		return opened
	}

	suspend fun validateDbSchemaCorrect(): UniffiResult<Unit> {
		return dbVersionValidate.validate()
	}

	suspend fun feedModelForSeed(seedName: String?) {
		val result = getKeySetDetails(requestedSeedName = seedName)
		fullScreenState.value = result
	}

	fun getAllNetworks(): List<NetworkModel> {
		return allNetworksUseCase.getAllNetworks()
	}

	fun setFilters(networksToFilter: Set<NetworkModel>) {
		viewModelScope.launch {
			preferencesRepository.setNetworksFilter(networksToFilter.map { it.key }
				.toSet())
		}
	}

	suspend fun removeSeed(root: KeyModel): OperationResult<Unit, Exception> {
		val result = seedRepository.removeKeySet(root.seedName)
		if (result is OperationResult.Ok) {
			preferencesRepository.setLastSelectedSeed(null)
			feedModelForSeed(null)
		}
		return result
	}

	suspend fun getSeedPhrase(seedName: String): String? {
		return when (val result = seedRepository.getSeedPhraseForceAuth(seedName)) {
			is RepoResult.Failure -> {
				null
			}

			is RepoResult.Success -> {
				backupInteractor.notifyRustSeedWasShown(seedName)
				result.result
			}
		}
	}
}

sealed class KeySetDetailsScreenState {

	object NoKeySets : KeySetDetailsScreenState()

	object LoadingState : KeySetDetailsScreenState()

	data class Data(
		val filteredModel: KeySetDetailsModel,
		val wasEmptyKeyset: Boolean
	) : KeySetDetailsScreenState()
}
