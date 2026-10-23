package net.rotko.zigner.domain.storage

/**
 * One line of a BIP39 phrase: trimmed, lowercase, single spaces.
 * Comparison uses this so a second import is rejected only when the words match.
 */
fun normalizeSeedPhrase(phrase: String): String =
	phrase.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
