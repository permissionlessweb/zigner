package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.spec.SecretKeySpec

class VoteGateTest {
	@Test
	fun cleartextVoteIsRejected() {
		assertEquals("reject", admit(false, false, byteArrayOf(0x10, 1)))
		assertEquals("reject", admit(true, false, byteArrayOf(0x10, 1)))
		assertEquals("reject", admit(false, true, byteArrayOf(0x30, 1)))
		assertEquals("status", admit(true, true, byteArrayOf(0x30, 1)))
		assertEquals("reject", admit(true, false, byteArrayOf(0x30, 1)))
		assertEquals("hello", admit(false, false, byteArrayOf(0x50)))
		assertEquals("reject", admit(false, false, byteArrayOf(0x54)))
		assertEquals("vote", admit(true, false, byteArrayOf(0x54)))
		assertEquals("reject", admit(false, false, byteArrayOf(0x20)))
		assertEquals("import", admit(true, false, byteArrayOf(0x24)))
	}
}

class HighWaterTest {
	@Test
	fun replayReturnsTheSameSignatureAndADifferentMessageIsRefused() {
		val mark = HighWater()
		assertTrue(mark.decide(9, 1, 2, "aa".hex()).isSign)
		mark.commit(9, 1, 2, "aa".hex(), "ab".hex())
		val again = mark.decide(9, 1, 2, "aa".hex())
		assertTrue(again is Decision.Replay)
		assertTrue((again as Decision.Replay).signature.contentEquals("ab".hex()))
		assertTrue(mark.decide(9, 1, 2, "bb".hex()) is Decision.Refuse)
		assertTrue(mark.decide(8, 1, 3, "cc".hex()) is Decision.Refuse)
		assertTrue(mark.decide(9, 1, 3, "dd".hex()).isSign)
		val ext = byteArrayOf(0x09, 0x11, 10, 0, 0, 0, 0, 0, 0, 0)
		val vote = byteArrayOf(0x20, 0x08, 0x02)
		val precommit = HighWater()
		assertTrue(precommit.decide(10, 0, 3, ext).isSign)
		precommit.note(10, 0, 3)
		assertTrue(precommit.decide(10, 0, 3, vote).isSign)
		precommit.commit(10, 0, 3, vote, "ab".hex())
		assertTrue(precommit.decide(10, 0, 3, byteArrayOf(0x20, 0x08, 0x03)) is Decision.Refuse)
		val file = File.createTempFile("hwm", ".txt")
		mark.write(file)
		val loaded = HighWater.read(file)
		assertTrue(loaded.decide(9, 1, 2, "bb".hex()) is Decision.Refuse)
		assertTrue(loaded.decide(9, 1, 2, "aa".hex()) is Decision.Replay)
	}
}

class AtRestTest {
	@Test
	fun sealedBytesDoNotContainTheSeed() {
		val key = SecretKeySpec(ByteArray(16) { 7 }, "AES")
		val plain = ByteArray(32) { 9 }
		val blob = AtRest.seal(key, plain)
		assertTrue(AtRest.isSealed(blob))
		assertTrue(blob.toList().windowed(32).none { it.toByteArray().contentEquals(plain) })
		assertTrue(AtRest.open(key, blob).contentEquals(plain))
	}
}

class FingerprintTest {
	@Test
	fun matchesTheKmsVector() {
		assertEquals("02 d4 49 a3 1f bb 26 7c", KeyExport.fingerprint(ByteArray(32) { 0x11 }))
	}
}

private val Decision.isSign: Boolean
	get() = this is Decision.Sign

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
