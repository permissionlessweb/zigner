package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import kotlinx.coroutines.runBlocking
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.backend.OperationResult

/**
 * The granter is the Terp account of the key set currently selected on this phone.
 * Another key set on the same phone is a different granter. The seed phrase stays in the vault.
 */
internal object GranterAccount {
	private const val PREFS = "remote_signer"
	private const val PINNED = "granter_address"

	fun lookup(context: Context): String {
		return try {
			val selected = runBlocking { ServiceLocator.preferencesRepository.getLastSelectedSeed() }
				?: return ""
			val keys = terpKeys(selected)
			if (keys.isEmpty()) return ""
			val pinned = context.applicationContext
				.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
				.getString(PINNED, "")
				.orEmpty()
			if (pinned in keys) pinned else keys.first()
		} catch (_: Exception) {
			""
		}
	}

	fun pin(context: Context, address: String) {
		if (!address.startsWith("terp1")) return
		context.applicationContext
			.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.edit()
			.putString(PINNED, address)
			.apply()
	}

	private fun terpKeys(seedName: String): List<String> {
		val result = runBlocking { ServiceLocator.uniffiInteractor.keySetBySeedName(seedName) }
		val details = (result as? OperationResult.Ok)?.result ?: return emptyList()
		return details.keysAndNetwork.mapNotNull { card ->
			val address = card.key.base58
			if (card.network.networkTitle.contains("terp", ignoreCase = true) && address.startsWith("terp1")) {
				address
			} else {
				null
			}
		}
	}
}
