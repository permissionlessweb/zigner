package net.rotko.zigner.domain.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

class PreferencesRepository(private val context: Context) {

	private val networksFilterKey = stringSetPreferencesKey("network_filter")
	private val lastSelectedKeySet = stringPreferencesKey("last_selected_seed_name")
	private val onlineModeEnabledKey = booleanPreferencesKey("online_mode_enabled")
	private val onlineModeWasEverEnabledKey = booleanPreferencesKey("online_mode_was_ever_enabled")
	private val thresholdSigningKey = booleanPreferencesKey("threshold_signing_not_airgapped")
	private val lightThemeEnabledKey = booleanPreferencesKey("light_theme_enabled")
	private val developerOptionsRevealedKey = booleanPreferencesKey("developer_options_revealed")

	val networksFilter = context.dataStore.data
		.map { preferences ->
			// No type safety.
			preferences[networksFilterKey] ?: emptySet()
		}

	suspend fun setNetworksFilter(newFilters: Set<String>) {
		context.dataStore.edit { settings ->
			settings[networksFilterKey] = newFilters
		}
	}

	suspend fun setLastSelectedSeed(seedName: String?) {
		context.dataStore.edit { settings ->
			when (seedName) {
				is String -> settings[lastSelectedKeySet] = seedName
				null -> settings -= lastSelectedKeySet
			}
		}
	}

	suspend fun getLastSelectedSeed(): String? {
		return context.dataStore.data.first()[lastSelectedKeySet]
	}

	/**
	 * Flow for observing online mode setting.
	 * When enabled, the app will not enforce airgap (airplane mode, WiFi off, etc.)
	 * This is an opt-in feature for users who want to use the signer with network enabled.
	 */
	val onlineModeEnabled: Flow<Boolean> = context.dataStore.data
		.map { preferences ->
			preferences[onlineModeEnabledKey] ?: false
		}

	/**
	 * Get current online mode setting synchronously
	 */
	suspend fun isOnlineModeEnabled(): Boolean {
		return context.dataStore.data.first()[onlineModeEnabledKey] ?: false
	}

	/** User accepted that this phone is not air-gapped so it can do threshold signing. */
	val thresholdSigningAcknowledged: Flow<Boolean> = context.dataStore.data
		.map { preferences -> preferences[thresholdSigningKey] ?: false }

	suspend fun acknowledgeThresholdSigning() {
		context.dataStore.edit { settings ->
			settings[thresholdSigningKey] = true
		}
	}

	/**
	 * Set online mode enabled/disabled.
	 * When enabling online mode, also sets the permanent "was ever enabled" flag.
	 */
	suspend fun setOnlineModeEnabled(enabled: Boolean) {
		context.dataStore.edit { settings ->
			settings[onlineModeEnabledKey] = enabled
			if (enabled) {
				// Permanent flag - once online mode is enabled, this is set forever
				settings[onlineModeWasEverEnabledKey] = true
			}
		}
	}

	/**
	 * Flow for observing if online mode was ever enabled.
	 * This is a permanent flag that can never be reset (except by factory reset).
	 * Used to warn users that the wallet has been used in online mode at some point.
	 */
	val onlineModeWasEverEnabled: Flow<Boolean> = context.dataStore.data
		.map { preferences ->
			preferences[onlineModeWasEverEnabledKey] ?: false
		}

	/**
	 * Get if online mode was ever enabled synchronously
	 */
	suspend fun wasOnlineModeEverEnabled(): Boolean {
		return context.dataStore.data.first()[onlineModeWasEverEnabledKey] ?: false
	}

	// Light theme toggle. Default off (= dark theme). Light gives black-on-white
	// QR codes that webcams scan more reliably than the inverted dark theme.
	val lightThemeEnabled: Flow<Boolean> = context.dataStore.data
		.map { preferences -> preferences[lightThemeEnabledKey] ?: false }

	suspend fun setLightThemeEnabled(enabled: Boolean) {
		context.dataStore.edit { settings -> settings[lightThemeEnabledKey] = enabled }
	}

	// Developer options gate. Default off. The online mode toggle is hidden from the
	// Advanced Settings screen until this flag is set. Setting it only REVEALS the
	// option - it does not enable online mode. Online mode stays off and still
	// requires the explicit confirmation + authentication flow to turn on.
	// Revealed by tapping the verifier certificate value 5 times (Android
	// developer-mode / bootloader-unlock style gesture on a screen normal users
	// never touch).
	val developerOptionsRevealed: Flow<Boolean> = context.dataStore.data
		.map { preferences -> preferences[developerOptionsRevealedKey] ?: false }

	suspend fun isDeveloperOptionsRevealed(): Boolean {
		return context.dataStore.data.first()[developerOptionsRevealedKey] ?: false
	}

	suspend fun setDeveloperOptionsRevealed(revealed: Boolean) {
		context.dataStore.edit { settings ->
			settings[developerOptionsRevealedKey] = revealed
		}
	}
}
