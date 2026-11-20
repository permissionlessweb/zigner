package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TicketCanonTest {
	@Test
	fun eventTagIsFirstEightBytesOfSha256() {
		val tag = TicketCanon.eventTagHex("speakez-sessions")
		val full = TicketCanon.hex(sha256("speakez-sessions".toByteArray(Charsets.UTF_8)))
		assertEquals(full.substring(0, 16), tag)
		assertEquals(16, tag.length)
	}

	@Test
	fun frameSignsAndIsStableForTheSameKey() {
		val seed = ByteArray(32) { 0x05 }
		val key = Ed25519Key(seed)
		val pub = TicketCanon.hex(key.pub())
		val text = TicketCanon.encodeFrame(
			grantHash = "11".repeat(32),
			deviceSignPubkey = pub,
			eventId = "speakez-sessions",
			seq = 1,
			ts = 1_800_000_000L,
			payload = "sezs3qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
		)
		assertTrue(text.startsWith(TicketCanon.FRAME_DOMAIN))
		assertTrue(text.contains("event_tag: " + TicketCanon.eventTagHex("speakez-sessions")))
		val sig = TicketCanon.signFrame(text, key)
		assertEquals(128, sig.length)
		val sig2 = TicketCanon.signFrame(text, Ed25519Key(seed.copyOf()))
		assertEquals(sig, sig2)
		val other = TicketCanon.signFrame(text, Ed25519Key(ByteArray(32) { 0x07 }))
		assertNotEquals(sig, other)
	}

	@Test
	fun parseRejectsNonSezs3() {
		assertEquals(
			"sezs3qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
			TicketCanon.parseSezs3("sezs3qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq"),
		)
		assertNull(TicketCanon.parseSezs3("zcinti-handoff:nope"))
		assertNull(TicketCanon.parseSezs3("sezs1://ticket?x=1"))
	}
}
