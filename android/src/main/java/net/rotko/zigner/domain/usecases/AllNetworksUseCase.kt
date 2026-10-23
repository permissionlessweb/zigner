package net.rotko.zigner.domain.usecases

import net.rotko.zigner.domain.NetworkModel
import net.rotko.zigner.domain.backend.UniffiInteractor
import net.rotko.zigner.domain.backend.mapError
import net.rotko.zigner.domain.backend.mapErrorForce
import kotlinx.coroutines.runBlocking


class AllNetworksUseCase(val uniffiInteractor: UniffiInteractor) {

	// Networks offered when a key is created. Terp is a Cosmos secp256k1 key
	// (coin type 118, bech32 prefix terp). Other Cosmos chains stay compiled
	// in and can be named here when they should appear on that screen.
	private val launchedNetworks = listOf("Zcash", "Penumbra", "Terp")

	fun getAllNetworks(): List<NetworkModel> = runBlocking { getNetworks() }
		.filter { launchedNetworks.contains(it.title) }

	fun getDefaultPreselectedNetworks(): List<NetworkModel> = getAllNetworks()

	private suspend fun getNetworks(): List<NetworkModel> {
		return uniffiInteractor.getAllNetworks().mapErrorForce()
	}
}
