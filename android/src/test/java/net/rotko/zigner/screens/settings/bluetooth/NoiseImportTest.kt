package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class NoiseImportTest {
	@Test
	fun ephemeralPublicMatchesTheGoVector() {
		val seed = ByteArray(32) { 0x03 }
		val pub = X25519.base(seed).joinToString("") { "%02x".format(it) }
		assertEquals("5dfedd3b6bd47f6fa28ee15d969d5bb0ea53774d488bdaf9df1c6e0124b3ef22", pub)
	}

	@Test
	fun message2MatchesTheGoVector() {
		val phone = NoiseResponder(ByteArray(32) { 0x02 })
		val msg1 = "5dfedd3b6bd47f6fa28ee15d969d5bb0ea53774d488bdaf9df1c6e0124b3ef22".hex()
		phone.readMessage1(msg1)
		val msg2 = phone.message2(ByteArray(32) { 0x04 }).joinToString("") { "%02x".format(it) }
		assertEquals(
			"ac01b2209e86354fb853237b5de0f4fab13c7fcbf433a61c019369617fecf10b0301d5221716fa2be9baa96416f4d480bd33b2af1f61bf699ff92b480b35f8603b07d5505ca312ace28713717b14f279",
			msg2,
		)
	}
}

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
