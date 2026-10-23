package net.rotko.zigner.domain.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SeedPhraseTest {
	@Test
	fun normalize_collapses_spacing_and_case() {
		assertEquals(
			"abandon ability able",
			normalizeSeedPhrase("  Abandon   ability\nable  "),
		)
	}

	@Test
	fun different_phrases_stay_different() {
		assertNotEquals(
			normalizeSeedPhrase("abandon ability able"),
			normalizeSeedPhrase("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"),
		)
	}
}
