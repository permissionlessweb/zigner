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
		assertTrue(f.all { it.startsWith("zc-anim:2:") })
		val c = AnimCollector()
		// out of order and repeated frames still assemble
		assertNull(c.add(f[2])); assertNull(c.add(f[0])); assertNull(c.add(f[2])); assertNull(c.add(f[3]))
		assertEquals(payload, c.add(f[1]))
	}

	@Test fun collectorPassesPlainText() {
		assertEquals("zcinti-imported:1:x", AnimCollector().add("zcinti-imported:1:x"))
	}

	@Test fun collectorRejectsHugeFrameCounts() {
		assertNull(AnimCollector().add("zc-anim:2:abcd:0/65:abc"))
		assertNull(AnimCollector().add("zc-anim:2:abcd:3/2:abc"))
	}

	@Test fun collectorRefusesUnsessionedFrames() {
		assertNull(AnimCollector().add("zc-anim:1:0/1:abc"))
	}

	@Test fun collectorNeverMixesSessions() {
		val a = Handoff.frames("A".repeat(440), 220, "aaaa1111")
		val b = Handoff.frames("B".repeat(440), 220, "bbbb2222")
		val c = AnimCollector()
		assertNull(c.add(a[0]))
		assertNull(c.add(b[1])) // another QR in view: starts over
		assertEquals("B".repeat(440), c.add(b[0]))
	}

	@Test fun tileChallengeHasTwoRealTilesAndDecoys() {
		repeat(50) { n ->
			val id = ByteArray(32) { (it * 7 + n).toByte() }
			val tiles = FingerprintTiles.of(id)
			val q = TileChallenge.make(id, java.util.Random(n.toLong()))
			assertEquals(TileChallenge.PICK + TileChallenge.DECOYS, q.options.size)
			assertEquals(TileChallenge.PICK, q.answer.size)
			q.options.forEachIndexed { i, t ->
				if (i in q.answer) assertTrue(t in tiles.take(4)) else assertTrue(t !in tiles)
			}
		}
	}

	@Test fun tilesAreStable() {
		val a = FingerprintTiles.of(ByteArray(32) { 1 })
		assertEquals(8, a.size)
		assertEquals(a, FingerprintTiles.of(ByteArray(32) { 1 }))
	}
}
