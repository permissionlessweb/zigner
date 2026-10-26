package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HandoffTest {
	private val t = Handoff.Ticket("morocco-1", "zcinti2", ByteArray(16) { it.toByte() }, 1234, ByteArray(32))

	@Test fun exportPlainLayout() {
		val secret = ByteArray(64) { 7 }
		val p = Handoff.exportPlain(t, 99, 2, 3, "terp1abc", secret)
		val b = ByteBuffer.wrap(p)
		fun str(): String { val n = b.get().toInt(); val a = ByteArray(n); b.get(a); return String(a) }
		assertEquals("morocco-1", str()); assertEquals("zcinti2", str())
		val n = ByteArray(16); b.get(n); assertTrue(n.contentEquals(t.nonce))
		assertEquals(99L, b.long); assertEquals(2, b.int); assertEquals(3, b.get().toInt())
		assertEquals("terp1abc", str()); assertEquals(0, b.get().toInt())
		val s = ByteArray(64); b.get(s); assertTrue(s.contentEquals(secret)); assertTrue(!b.hasRemaining())
	}

	@Test fun retireMessageMatchesKms() {
		val m = String(Handoff.retireMessage(t, ByteArray(32)))
		assertTrue(m.startsWith("zcinti-retire:1|morocco-1|zcinti2|"))
		assertTrue(m.endsWith("|1234|000102030405060708090a0b0c0d0e0f"))
	}

	@Test fun framesReassemble() {
		val payload = "x".repeat(1000) + "y"
		val f = Handoff.frames(payload, 220)
		assertEquals(5, f.size)
		assertTrue(f[0].startsWith("zc-anim:1:0/5:"))
		assertEquals(payload, f.joinToString("") { it.split(":", limit = 4)[3] })
	}

	@Test fun tilesMapping() {
		val tiles = FingerprintTiles.of(ByteArray(32))
		assertEquals(8, tiles.size)
		// sha256(32 zero bytes)[0] = 0x66 -> glyph 6 (♠), colour 6
		assertEquals("♠", tiles[0].first); assertEquals(0xFF7A4FC4, tiles[0].second)
	}
}
