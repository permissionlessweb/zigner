package net.rotko.zigner.screens.settings.general

import android.content.Context
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.rotko.zigner.R
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.AuthResult
import net.rotko.zigner.domain.Callback
import net.rotko.zigner.domain.NetworkState
import net.rotko.zigner.domain.backend.OperationResult
import net.rotko.zigner.domain.security.KeyStorageLevel
import net.rotko.zigner.domain.security.MemoryProtection
import net.rotko.zigner.domain.usecases.ResetUseCase
import net.rotko.zigner.screens.error.ErrorStateDestinationState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber


class SettingsGeneralViewModel: ViewModel() {

	private val resetUseCase: ResetUseCase = ResetUseCase()
	private val preferencesRepository = ServiceLocator.preferencesRepository
	private val authentication = ServiceLocator.authentication
	private val activity: FragmentActivity
		get() = ServiceLocator.activityScope!!.activity

	/**
	 * Security summary for settings: only what was read back from the
	 * keystore and the kernel, never what was requested or assumed.
	 * See KeyStorageLevel and MemoryProtection.
	 */
	fun getSecuritySummary(context: Context): String {
		return listOf(
			KeyStorageLevel.describe(KeyStorageLevel.of()),
			MemoryProtection.describe(MemoryProtection.getMteStatus()),
		).joinToString(" · ").also { Timber.d("security summary: $it") }
	}

	fun getAppVersion(context: Context): String {
		// versionName is nullable as of the SDK 35 stubs
		return context.packageManager.getPackageInfo(
			context.packageName,
			0
		).versionName ?: "Unknown"
	}

	val networkState: StateFlow<NetworkState> =
		ServiceLocator.networkExposedStateKeeper.airGapModeState

	/**
	 * Online mode allows using Zigner with network radios enabled.
	 * This is an opt-in feature that bypasses airgap checks.
	 */
	val onlineModeEnabled: StateFlow<Boolean> =
		preferencesRepository.onlineModeEnabled
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

	/**
	 * Enable online mode with authentication.
	 * This is a security-sensitive operation that:
	 * 1. Requires biometric/password authentication
	 * 2. Permanently marks the wallet as having been used in online mode
	 * 3. Records the event in history
	 */
	suspend fun enableOnlineModeWithAuth(onSuccess: Callback): OperationResult<Unit, ErrorStateDestinationState> {
		return when (authentication.authenticate(activity)) {
			AuthResult.AuthError,
			AuthResult.AuthFailed,
			AuthResult.AuthUnavailable -> {
				Toast.makeText(
					activity.baseContext,
					activity.baseContext.getString(R.string.auth_failed_message),
					Toast.LENGTH_SHORT
				).show()
				OperationResult.Ok(Unit)
			}
			AuthResult.AuthSuccess -> {
				preferencesRepository.setOnlineModeEnabled(true)
				// Log to history that online mode was enabled
				ServiceLocator.uniffiInteractor.historyDeviceWasOnline()
				onSuccess()
				OperationResult.Ok(Unit)
			}
		}
	}

	/**
	 * Disable online mode with authentication.
	 * Requires authentication to prevent unauthorized changes.
	 */
	suspend fun disableOnlineModeWithAuth(onSuccess: Callback): OperationResult<Unit, ErrorStateDestinationState> {
		return when (authentication.authenticate(activity)) {
			AuthResult.AuthError,
			AuthResult.AuthFailed,
			AuthResult.AuthUnavailable -> {
				Toast.makeText(
					activity.baseContext,
					activity.baseContext.getString(R.string.auth_failed_message),
					Toast.LENGTH_SHORT
				).show()
				OperationResult.Ok(Unit)
			}
			AuthResult.AuthSuccess -> {
				preferencesRepository.setOnlineModeEnabled(false)
				onSuccess()
				OperationResult.Ok(Unit)
			}
		}
	}

	/**
	 * Check if online mode was ever enabled (permanent flag).
	 */
	val onlineModeWasEverEnabled: StateFlow<Boolean> =
		preferencesRepository.onlineModeWasEverEnabled
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

	/**
	 * Auth user and wipe the Vault to initial state
	 */
	suspend fun wipeToFactory(onAfterWipe: Callback): OperationResult<Unit, ErrorStateDestinationState> {
		return resetUseCase.wipeToFactoryWithAuth(onAfterWipe)
	}

	val lightThemeEnabled: StateFlow<Boolean> =
		preferencesRepository.lightThemeEnabled
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

	/**
	 * Whether developer options have been revealed via the hidden 5-tap gesture on
	 * the verifier certificate screen. When false (the default), the online mode
	 * toggle is not shown at all and the app stays fully air-gapped.
	 */
	val developerOptionsRevealed: StateFlow<Boolean> =
		preferencesRepository.developerOptionsRevealed
			.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

	fun toggleLightTheme() {
		viewModelScope.launch {
			preferencesRepository.setLightThemeEnabled(!lightThemeEnabled.value)
		}
	}
}
