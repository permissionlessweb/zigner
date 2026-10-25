package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignHistoryTest {
	@Test
	fun roundTrip() {
		val r = SignedRow(24068440, 0, 2, 812, "02:06:21", "abcd", "signed", 1_761_357_981_000)
		assertEquals(r, SignHistory.decode(SignHistory.encode(r)))
		assertNull(SignHistory.decode("garbage"))
	}

	@Test
	fun parseNewestFirstAndCapped() {
		val lines = (1..5).map { SignHistory.encode(SignedRow(it.toLong(), 0, 1, 1, "t", "k", "signed", it.toLong())) } + "bad"
		val out = SignHistory.parse(lines, 3)
		assertEquals(listOf(5L, 4L, 3L), out.map { it.height })
	}

	@Test
	fun liveness() {
		val now = 1_000_000L
		assertEquals("Stopped", SignHistory.liveLabel(false, now, now))
		assertEquals("Idle · no requests yet", SignHistory.liveLabel(true, 0, now))
		assertEquals("Signing", SignHistory.liveLabel(true, now - 5_000, now))
		assertEquals("Idle · last sign 8m ago", SignHistory.liveLabel(true, now - 480_000, now))
		assertTrue(SignHistory.isLive(true, now - 29_000, now))
		assertFalse(SignHistory.isLive(true, now - 31_000, now))
		assertFalse(SignHistory.isLive(false, now - 1_000, now))
	}
}
