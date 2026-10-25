package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignRouteTest {
	private fun le(v: Long) = ByteArray(8) { ((v ushr (8 * it)) and 0xff).toByte() }
	private fun vote(type: Int, h: Long, r: Long, chain: String): ByteArray {
		val c = chain.toByteArray()
		val body = byteArrayOf(0x08, type.toByte(), 0x11) + le(h) + byteArrayOf(0x19) + le(r) +
			byteArrayOf(0x2a, 0x02, 0x08, 0x01) + byteArrayOf(0x32, c.size.toByte()) + c
		return byteArrayOf(body.size.toByte()) + body
	}
	private fun key(id: String, chain: String) = ValidatorKey(id, "terp", chain, id, "")

	@Test fun parsesPrecommit() {
		val sb = parseSignBytes(vote(2, 24057108, 1, "morocco-1"))!!
		assertEquals(3, sb.kind); assertEquals(24057108L, sb.height); assertEquals(1L, sb.round); assertEquals("morocco-1", sb.chainId)
		assertNull(signBytesProblem(sb, 24057108, 1, 3))
		assertTrue(signBytesProblem(sb, 24057109, 1, 3) != null)
		assertTrue(signBytesProblem(sb, 24057108, 1, 2) != null)
	}

	@Test fun routesByChain() {
		val keys = listOf(key("terp-1", "test-1"), key("terp-2", "morocco-1"))
		assertEquals("terp-2", (routeKey(keys, "", "morocco-1") as Route.Use).key.id)
		assertTrue(routeKey(keys, "terp-1", "morocco-1") is Route.Refuse)
		assertTrue(routeKey(keys, "", "other-1") is Route.Refuse)
		val dup = keys + key("terp-3", "morocco-1")
		assertTrue(routeKey(dup, "", "morocco-1") is Route.Refuse)
		assertEquals("terp-3", (routeKey(dup, "terp-3", "morocco-1") as Route.Use).key.id)
	}

	// Same vector as kms keylink.go KeyID: sha256(32 zero bytes)[:8].
	@Test fun keyIdMatchesKms() {
		assertEquals("66687aadf862bd77", keyIdHex("00".repeat(32)))
		assertEquals("", keyIdHex("abcd"))
	}

	private fun frame(keyId: String?, h: Long, r: Int, step: Int, msg: ByteArray): ByteArray {
		val head = java.nio.ByteBuffer.allocate(15).putLong(h).putInt(r).put(step.toByte()).putShort(msg.size.toShort()).array()
		val id = keyId?.let { k -> ByteArray(8) { k.substring(it * 2, it * 2 + 2).toInt(16).toByte() } }
		val body = (if (id == null) byteArrayOf(0x10) else byteArrayOf(0x12) + id) + head + msg
		return body + ByteArray(maxOf(0, 64 - body.size))
	}

	@Test fun parsesBothFrames() {
		val msg = vote(2, 24058800, 0, "morocco-1")
		val legacy = parseSignFrame(frame(null, 24058800, 0, 3, msg))!!
		assertNull(legacy.keyId); assertEquals(24058800L, legacy.height); assertEquals(3, legacy.step)
		assertTrue(legacy.msg.contentEquals(msg))
		val keyed = parseSignFrame(frame("0102030405060708", 24058800, 0, 3, msg))!!
		assertEquals("0102030405060708", keyed.keyId); assertEquals(24058800L, keyed.height)
		assertTrue(keyed.msg.contentEquals(msg))
		assertNull(parseSignFrame(byteArrayOf(0x11, 0, 0)))
		assertNull(parseSignFrame(byteArrayOf(0x12, 1, 2, 3)))
	}

	@Test fun twoKeysOneChainByKeyId() {
		val keys = listOf(key("terp-2", "morocco-1"), key("terp-3", "morocco-1"))
		// Keyed frames reach either key, whatever is started.
		assertEquals("terp-2", (routeKey(keys, "terp-3", "morocco-1", "terp-2") as Route.Use).key.id)
		assertEquals("terp-3", (routeKey(keys, "terp-3", "morocco-1", "terp-3") as Route.Use).key.id)
		assertEquals("terp-3", (routeKey(keys, "", "morocco-1", "terp-3") as Route.Use).key.id)
		// The named key must still be for the request's chain.
		assertTrue(routeKey(keys, "", "test-1", "terp-2") is Route.Refuse)
		assertTrue(routeKey(keys, "", "morocco-1", "terp-9") is Route.Refuse)
		// Legacy 0x10: only the started key, never a guess.
		assertTrue(routeKey(keys, "", "morocco-1") is Route.Refuse)
		assertEquals("terp-2", (routeKey(keys, "terp-2", "morocco-1") as Route.Use).key.id)
	}

	@Test fun keyListCarriesEveryKey() {
		val a = ValidatorKey("terp-2", "terp", "morocco-1", "zcinti-1", "00".repeat(32))
		val b = ValidatorKey("terp-3", "terp", "morocco-1", "zcinti2", "11".repeat(32))
		val out = encodeKeyList(listOf(a, b, key("broken", "x")))
		assertEquals(1, out[0].toInt()); assertEquals(2, out[1].toInt())
		var i = 2
		for (k in listOf(a, b)) {
			assertEquals(keyIdHex(k.pubHex), out.copyOfRange(i, i + 8).joinToString("") { "%02x".format(it) }); i += 8
			val cl = out[i++].toInt(); assertEquals(k.chainId, String(out, i, cl)); i += cl
			val ll = out[i++].toInt(); assertEquals(k.name, String(out, i, ll)); i += ll
			assertEquals(k.pubHex, out.copyOfRange(i, i + 32).joinToString("") { "%02x".format(it) }); i += 32
		}
		// name trailer after the counted entries
		assertEquals(0x4E, out[i++].toInt()); assertEquals(2, out[i++].toInt())
		for (k in listOf(a, b)) {
			val nl = out[i++].toInt(); assertEquals(k.name, String(out, i, nl)); i += nl
		}
		assertEquals(out.size, i)
	}

	@Test fun nameTrailerCutsAtUtf8Boundary() {
		val long = "é".repeat(30) // 60 bytes
		val cut = utf8Cut(long, 40)
		assertEquals(40, cut.size); assertEquals("é".repeat(20), String(cut, Charsets.UTF_8))
		val odd = utf8Cut("a" + "é".repeat(30), 40) // 1 + 2n: cut must drop to 39
		assertEquals(39, odd.size); assertEquals("a" + "é".repeat(19), String(odd, Charsets.UTF_8))
		val emoji = utf8Cut("x".repeat(38) + "\uD83D\uDE80", 40) // 4-byte code point does not fit
		assertEquals(38, emoji.size)
		val k = ValidatorKey("terp-2", "terp", "morocco-1", long, "00".repeat(32), "morocco-1_zcinti")
		val out = encodeKeyList(listOf(k))
		assertEquals(40, out[out.size - 41].toInt()); assertEquals(0x4E, out[out.size - 43].toInt())
	}

	@Test fun parsesRenameFrame() {
		val id = ByteArray(8) { (it + 1).toByte() }
		val name = "zcinti ✓".toByteArray()
		val ok = parseRenameFrame(byteArrayOf(0x01) + id + byteArrayOf(name.size.toByte()) + name)!!
		assertEquals("0102030405060708", ok.keyIdHex); assertEquals("zcinti ✓", ok.name)
		assertNull(parseRenameFrame(byteArrayOf(0x02) + id + byteArrayOf(1, 0x41)))
		assertNull(parseRenameFrame(byteArrayOf(0x01) + id + byteArrayOf(0)))
		assertNull(parseRenameFrame(byteArrayOf(0x01) + id + byteArrayOf(2, 0x41)))
		assertNull(parseRenameFrame(byteArrayOf(0x01) + id + byteArrayOf(1, 0xC3.toByte())))
		assertNull(parseRenameFrame(byteArrayOf(0x01) + id + byteArrayOf(41) + ByteArray(41) { 0x41 }))
	}

	@Test fun renameNeedsSession() {
		assertEquals("rename", admit(true, false, byteArrayOf(0x56, 1)))
		assertEquals("reject", admit(false, false, byteArrayOf(0x56, 1)))
		assertEquals("reject", admit(true, true, byteArrayOf(0x56, 1)))
	}

	@Test fun refusalsMatchKms() {
		val stale = staleRefusal(24059000, 1, 3)
		assertEquals(15, stale.size); assertEquals(0x5f.toByte(), stale[0]); assertEquals(REFUSE_STALE, stale[1])
		val bb = java.nio.ByteBuffer.wrap(stale, 2, 13)
		assertEquals(24059000L, bb.long); assertEquals(1, bb.int); assertEquals(3, bb.get().toInt())
		val unk = refusal(REFUSE_UNKNOWN_KEY, "0102030405060708")
		assertEquals(REFUSE_UNKNOWN_KEY, unk[1]); assertEquals("0102030405060708", String(unk, 2, unk.size - 2))
		assertTrue(refusal(REFUSE_CHAIN, "x".repeat(500)).size <= 122)
		val keys = listOf(key("terp-2", "morocco-1"), key("terp-3", "morocco-1"))
		assertEquals(REFUSE_UNKNOWN_KEY, (routeKey(keys, "", "morocco-1", "nope") as Route.Refuse).code)
		assertEquals(REFUSE_CHAIN, (routeKey(keys, "", "test-1", "terp-2") as Route.Refuse).code)
		assertEquals(REFUSE_LOCKED, (routeKey(keys, "", "morocco-1") as Route.Refuse).code)
	}

	@Test fun slotsMatchKmsLabels() {
		val ks = assignSlots(listOf(
			ValidatorKey("terp-2", "terp", "morocco-1", "zcinti-1", "00".repeat(32)),
			ValidatorKey("terp-3", "terp", "morocco-1", "morocco-1 #2", "11".repeat(32)),
		))
		assertEquals("morocco-1_zcinti", ks[0].slot)
		assertEquals("morocco-1_zcinti2", ks[1].slot)
		val pinned = assignSlots(listOf(ValidatorKey("a", "terp", "morocco-1", "a", "00".repeat(32), "morocco-1_zcinti2"),
			ValidatorKey("b", "terp", "morocco-1", "b", "11".repeat(32))))
		assertEquals("morocco-1_zcinti", pinned[1].slot)
	}

	@Test fun keyedNeedsStartedKey() {
		val a = ValidatorKey("terp-2", "terp", "morocco-1", "zcinti-1", "00".repeat(32), "morocco-1_zcinti")
		val b = ValidatorKey("terp-3", "terp", "morocco-1", "zcinti2", "11".repeat(32), "morocco-1_zcinti2")
		val ks = listOf(a, b)
		assertEquals(REFUSE_LOCKED, (routeKeyed(ks, setOf("terp-2"), "morocco-1", keyIdHex(b.pubHex)) as Route.Refuse).code)
		assertEquals(b, (routeKeyed(ks, setOf("terp-2", "terp-3"), "morocco-1", keyIdHex(b.pubHex)) as Route.Use).key)
		assertEquals(REFUSE_CHAIN, (routeKeyed(ks, setOf("terp-2"), "test-1", keyIdHex(a.pubHex)) as Route.Refuse).code)
		assertEquals(REFUSE_UNKNOWN_KEY, (routeKeyed(ks, setOf("terp-2"), "morocco-1", "ffffffffffffffff") as Route.Refuse).code)
		// legacy 0x10 still follows the active key only
		assertEquals(a, (routeKey(ks, "terp-2", "morocco-1") as Route.Use).key)
		val list = encodeKeyList(ks)
		assertTrue(String(list, Charsets.ISO_8859_1).contains("morocco-1_zcinti2"))
	}
}
