package net.rotko.zigner.domain.frost

import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.storage.RepoResult

/**
 * FROST shares are sealed under a key derived from one of the device's seeds
 * (Rust `frost_storage`). Anything that stores or reads a share passes the
 * unlocked seeds; Rust picks the one the share is sealed under, and seals new
 * shares under the first.
 */
object FrostSeeds {
	/**
	 * Every seed phrase, ordered by seed name, behind one unlock prompt.
	 * null when the prompt was cancelled or failed; an empty list when the
	 * device holds no key set, which Rust refuses for new shares.
	 */
	suspend fun unlockAll(): List<String>? {
		val repository = ServiceLocator.activityScope?.seedRepository ?: return null
		return when (val seeds = repository.getAllSeeds()) {
			is RepoResult.Success -> seeds.result.toSortedMap().values.toList()
			is RepoResult.Failure -> null
		}
	}

	/** The phrase of [seedName] and every phrase, behind one unlock prompt. */
	suspend fun unlockWith(seedName: String): Pair<String, List<String>>? {
		val repository = ServiceLocator.activityScope?.seedRepository ?: return null
		return when (val seeds = repository.getAllSeeds()) {
			is RepoResult.Success -> {
				val phrase = seeds.result[seedName] ?: return null
				phrase to seeds.result.toSortedMap().values.toList()
			}
			is RepoResult.Failure -> null
		}
	}
}
