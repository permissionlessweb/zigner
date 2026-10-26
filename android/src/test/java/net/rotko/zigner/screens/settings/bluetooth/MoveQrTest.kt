package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.SecureRandom

class MoveQrTest {
	private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

	/** 64-byte seed|pub consensus secret from the JDK provider. */
	private fun consensus(): ByteArray {
		val kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
		val pk = kp.private.encoded; val pub = kp.public.encoded
		return pk.copyOfRange(pk.size - 32, pk.size) + pub.copyOfRange(pub.size - 32, pub.size)
	}

	private val start = MoveQr.Start("morocco-1", "zcinti2", rnd(16), 1234, rnd(32))

	private class Party { val id = ByteArray(32).also { SecureRandom().nextBytes(it) }; val eph = ByteArray(32).also { SecureRandom().nextBytes(it) }
		val recv get() = MoveQr.Receiver(X25519.base(eph), X25519.base(id)) }

	private fun sealFor(secret: ByteArray, to: MoveQr.Receiver, oldId: ByteArray, s: MoveQr.Start = start, mark: MoveQr.Mark = MoveQr.Mark(1200, 0, 3)): String {
		val priv = MoveQr.privateFromSeed(secret.copyOfRange(0, 32))
		return MoveQr.seal(s, to, oldId, "terp1owner", mark, secret.copyOf(), { MoveQr.sign(priv, it) })
	}

	@Test fun startAndReceiveParse() {
		val s = MoveQr.parseStart(MoveQr.startText(start))
		assertEquals("morocco-1", s.chain); assertEquals(1234L, s.floor); assertArrayEquals(start.nonce, s.nonce)
		val p = Party()
		val r = MoveQr.parseReceive(MoveQr.receiveText(p.recv.eph, p.recv.id))
		assertArrayEquals(p.recv.eph, r.eph); assertArrayEquals(p.recv.id, r.id)
	}

	@Test fun startWithoutFloorRefused() {
		try { MoveQr.parseStart("zcinti-move:2:c:s:${"00".repeat(16)}:0:${B64.enc(ByteArray(32))}"); fail() } catch (_: IllegalArgumentException) {}
	}

	@Test fun sealOpensOnlyForTarget() {
		val secret = consensus(); val oldId = rnd(32); val newP = Party()
		val qr = sealFor(secret, newP.recv, oldId)
		val o = MoveQr.open(qr, newP.eph, newP.id)
		assertArrayEquals(secret, o.secret)
		assertEquals(1234L, o.start.floor); assertEquals("terp1owner", o.owner)
		assertArrayEquals(X25519.base(oldId), o.oldId)
		assertArrayEquals(newP.recv.id, o.target)
		o.wipe()
		// Another phone cannot open it.
		val other = Party()
		try { MoveQr.open(qr, other.eph, other.id); fail() } catch (_: IllegalArgumentException) {}
		// Right eph, wrong static identity fails.
		try { MoveQr.open(qr, newP.eph, other.id); fail() } catch (_: IllegalArgumentException) {}
		// Right identity, attacker's eph fails (attacker showing the victim identity with own eph).
		try { MoveQr.open(qr, other.eph, newP.id); fail() } catch (_: IllegalArgumentException) {}
	}

	@Test fun attackerWithVictimIdentityCannotOpen() {
		// Attacker displays victim's identity pub with its own eph; the seal includes DH(oldEph, newId).
		val secret = consensus(); val victim = Party(); val attacker = Party()
		val forged = MoveQr.Receiver(X25519.base(attacker.eph), X25519.base(victim.id))
		val qr = sealFor(secret, forged, rnd(32))
		try { MoveQr.open(qr, attacker.eph, attacker.id); fail() } catch (_: IllegalArgumentException) {}
	}

	@Test fun tamperFails() {
		val secret = consensus(); val p = Party()
		val qr = sealFor(secret, p.recv, rnd(32))
		val raw = B64.dec(qr.removePrefix(MoveQr.SEALED))!!
		raw[raw.size - 5] = (raw[raw.size - 5].toInt() xor 1).toByte()
		try { MoveQr.open(MoveQr.SEALED + B64.enc(raw), p.eph, p.id); fail() } catch (_: IllegalArgumentException) {}
		// Swapping the claimed old identity breaks the AEAD binding.
		val raw2 = B64.dec(qr.removePrefix(MoveQr.SEALED))!!
		rnd(32).copyInto(raw2, 0)
		try { MoveQr.open(MoveQr.SEALED + B64.enc(raw2), p.eph, p.id); fail() } catch (_: IllegalArgumentException) {}
	}

	@Test fun markAtFloorRefused() {
		val secret = consensus(); val p = Party()
		val qr = sealFor(secret, p.recv, rnd(32), mark = MoveQr.Mark(1234, 0, 0))
		try { MoveQr.open(qr, p.eph, p.id); fail() } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("floor")) }
	}

	@Test fun wrongSignerRefused() {
		val secret = consensus(); val p = Party(); val other = consensus()
		val priv = MoveQr.privateFromSeed(other.copyOfRange(0, 32))
		val qr = MoveQr.seal(start, p.recv, rnd(32), "", MoveQr.Mark(1, 0, 0), secret.copyOf(), { MoveQr.sign(priv, it) })
		try { MoveQr.open(qr, p.eph, p.id); fail() } catch (_: IllegalArgumentException) {}
	}

	@Test fun ackAndRetireMessagesBindTarget() {
		val secret = consensus(); val pub = secret.copyOfRange(32, 64); val t = rnd(32)
		val ack = String(MoveQr.ackMessage(start, pub, t))
		assertTrue(ack.startsWith("zcinti-imported:1|morocco-1|zcinti2|${MoveQr.keyId(pub)}|1234|${start.nonceHex}|${MoveQr.hex(t)}"))
		val ret = String(MoveQr.retireMessage(start, pub, t))
		assertEquals("zcinti-retire:2|morocco-1|zcinti2|${MoveQr.keyId(pub)}|1234|${start.nonceHex}|${MoveQr.hex(t)}", ret)
		val priv = MoveQr.privateFromSeed(secret.copyOfRange(0, 32))
		val sig = MoveQr.sign(priv, MoveQr.ackMessage(start, pub, t))
		val mac = ByteArray(32) { 9 }
		val (sig2, mac2) = MoveQr.ackParse(MoveQr.ackText(sig, mac))
		assertArrayEquals(sig, sig2); assertArrayEquals(mac, mac2)
		try { MoveQr.ackParse("zcinti-imported:1:" + B64.enc(sig)); fail() } catch (_: IllegalArgumentException) {}
		assertTrue(MoveQr.verify(pub, MoveQr.ackMessage(start, pub, t), sig))
		assertFalse(MoveQr.verify(pub, MoveQr.ackMessage(start, pub, rnd(32)), sig))
	}

	@Test fun ackMacProvesNewPhoneIdentity() {
		val oldSeed = rnd(32); val newSeed = rnd(32); val otherSeed = rnd(32)
		val oldId = X25519.base(oldSeed); val newId = X25519.base(newSeed)
		val msg = "ack".toByteArray()
		val mac = MoveQr.ackMac(newSeed, oldId, oldId, newId, msg)
		assertTrue(MoveQr.ackMacOk(oldSeed, newId, oldId, newId, msg, mac))
		// another phone (not the one in the Receive QR) cannot make it
		val forged = MoveQr.ackMac(otherSeed, oldId, oldId, newId, msg)
		assertFalse(MoveQr.ackMacOk(oldSeed, newId, oldId, newId, msg, forged))
		assertFalse(MoveQr.ackMacOk(oldSeed, newId, oldId, newId, "other".toByteArray(), mac))
	}

	@Test fun damagedListsThrowInsteadOfReset() {
		val b: File = Files.createTempDirectory("move").toFile()
		File(b, "validator-keys").mkdirs()
		File(b, "validator-keys/move-nonces.json").writeText("{not json")
		File(b, "validator-keys/tombstones.json").writeText("{not json")
		try { MoveStore.markNonce(b, "aa"); fail() } catch (_: IllegalStateException) {}
		try { MoveStore.addTombstone(b, "aa"); fail() } catch (_: IllegalStateException) {}
		assertEquals("{not json", File(b, "validator-keys/move-nonces.json").readText())
		assertTrue(MoveStore.nonceUsed(b, "anything")) // damaged list still fails closed
	}

	@Test fun receiveTtlMatchesConsole() { assertEquals(15 * 60 * 1000L, MoveQr.RECV_TTL_MS) }

	@Test fun nonceReplayAndOneTargetPerNonce() {
		val b: File = Files.createTempDirectory("move").toFile()
		assertFalse(MoveStore.nonceUsed(b, start.nonceHex))
		MoveStore.markNonce(b, start.nonceHex)
		assertTrue(MoveStore.nonceUsed(b, start.nonceHex))
		val t1 = rnd(32)
		MoveStore.outSet(b, "k1", start, t1)
		MoveStore.outSet(b, "k1", start, t1) // same target again is fine
		try { MoveStore.outSet(b, "k1", start, rnd(32)); fail() } catch (_: IllegalStateException) {}
		MoveStore.outClear(b, "k1")
		MoveStore.outSet(b, "k1", start, rnd(32))
	}

	@Test fun b64RoundTrip() {
		for (n in 0..70) { val x = rnd(n); assertArrayEquals(x, B64.dec(B64.enc(x))) }
		assertEquals(null, B64.dec("@@@"))
	}
}
