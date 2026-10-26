package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffTest {
	@Test fun framesRoundTripThroughCollector() {
		val payload = "zcinti-p2p:1:" + "A".repeat(700)
		val f = Handoff.frames(payload, 220)
		assertEquals(4, f.size)
		assertTrue(f.all { it.startsWith("zc-anim:1:") })
		val c = AnimCollector()
		// out of order and repeated frames still assemble
		assertNull(c.add(f[2])); assertNull(c.add(f[0])); assertNull(c.add(f[2])); assertNull(c.add(f[3]))
		assertEquals(payload, c.add(f[1]))
	}

	@Test fun collectorPassesPlainText() {
		assertEquals("zcinti-imported:1:x", AnimCollector().add("zcinti-imported:1:x"))
	}

	@Test fun collectorRejectsHugeFrameCounts() {
		assertNull(AnimCollector().add("zc-anim:1:0/65:abc"))
		assertNull(AnimCollector().add("zc-anim:1:3/2:abc"))
	}

	@Test fun tilesAreStable() {
		val a = FingerprintTiles.of(ByteArray(32) { 1 })
		assertEquals(8, a.size)
		assertEquals(a, FingerprintTiles.of(ByteArray(32) { 1 }))
	}
}
