package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SezsTicketHistoryTest {
	@Test
	fun ticketTagIsFirstEightBytesOfSha256AndHidesPayload() {
		val payload = "sezs3qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"
		val tag = SezsTicketHistory.ticketTag(payload)
		assertEquals(16, tag.length)
		assertEquals(tag, SezsTicketHistory.ticketTag(payload))
		assertNotEquals(tag, SezsTicketHistory.ticketTag(payload + "q"))
		assertTrue(!tag.contains("sezs3"))
	}

	@Test
	fun encodeDecodeRoundTripAndParseNewestFirst() {
		val a = SezsTicketHistory.Row(1_800_000_000_000L, 1, "aabbccdd11223344", "sent", "")
		val b = SezsTicketHistory.Row(1_800_000_001_000L, 2, "0102030405060708", "refused", "no-session")
		assertEquals(a, SezsTicketHistory.decode(SezsTicketHistory.encode(a)))
		assertEquals(b, SezsTicketHistory.decode(SezsTicketHistory.encode(b)))
		assertNull(SezsTicketHistory.decode("bad"))
		val parsed = SezsTicketHistory.parse(
			listOf(SezsTicketHistory.encode(a), SezsTicketHistory.encode(b)),
		)
		assertEquals(listOf(b, a), parsed)
	}
}
