package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import javax.crypto.Cipher

/**
 * Stored-key compatibility for the move from the JCA Ed25519 provider to
 * zigner's Rust Ed25519. The Rust side (rust/signer/src/ed25519_ffi.rs)
 * checks the same RFC 8032 vectors and the same PKCS#8, so equal outputs
 * here and there mean JCA-written keys give the same pub and signatures.
 */
class Ed25519CompatTest {
	private fun h(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

	// RFC 8032 7.1: TEST 1, 2, 3, SHA(abc).
	private val rfc = listOf(
		arrayOf("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
			"d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
			"e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
		arrayOf("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
			"3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
			"92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
		arrayOf("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
			"fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
			"6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
		arrayOf("833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42",
			"ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf",
			"ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
			"dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704"),
	)

	@Test
	fun rfc8032VectorsThroughStoredPkcs8() {
		for ((seed, pk, msg, sig) in rfc) {
			val stored = Ed25519.pkcs8(h(seed))
			assertEquals(48, stored.size)
			val back = Ed25519.seedFromPkcs8(stored)
			assertArrayEquals(h(seed), back)
			assertArrayEquals(h(pk), Ed25519Ref.pub(back))
			assertArrayEquals(h(sig), JcaCrypto.sign(back, h(msg)))
			assertArrayEquals(h(sig), Ed25519Key(back.copyOf()).sign(h(msg)))
			assertTrue(Ed25519.verify(h(pk), h(msg), h(sig)))
			assertFalse(Ed25519.verify(h(pk), h(msg) + 1, h(sig)))
		}
	}

	/** Keys the JCA generator wrote (old builds) load to the same pub and signatures. */
	@Test
	fun jcaGeneratedKeysStayCompatible() {
		val gen = KeyPairGenerator.getInstance("Ed25519")
		repeat(16) { n ->
			val pair = gen.generateKeyPair()
			val pkcs8 = pair.private.encoded
			val spki = pair.public.encoded
			val raw = spki.copyOfRange(spki.size - 32, spki.size)
			val seed = Ed25519.seedFromPkcs8(pkcs8)
			// What new builds write is byte-identical to what the JCA wrote.
			assertArrayEquals(pkcs8, Ed25519.pkcs8(seed))
			assertArrayEquals(spki, Ed25519.spki(raw))
			assertArrayEquals(raw, Ed25519Ref.pub(seed))
			val msg = "vote-$n".toByteArray()
			val old = Signature.getInstance("Ed25519").run { initSign(pair.private); update(msg); sign() }
			assertArrayEquals(old, Ed25519Key(seed).sign(msg))
		}
	}

	@Test
	fun pkcs8V2WithPublicKeyParses() {
		val (seed, pk) = rfc[0]
		// OneAsymmetricKey v2: version 1, then [1] IMPLICIT BIT STRING public key.
		val body = h("020101300506032b657004220420") + h(seed) + h("8121" + "00") + h(pk)
		val der = byteArrayOf(0x30, body.size.toByte()) + body
		assertArrayEquals(h(seed), Ed25519.seedFromPkcs8(der))
	}

	@Test
	fun pkcs8RejectsOtherKeys() {
		val x25519 = h("302e020100300506032b656e04220420") + ByteArray(32)
		assertTrue(runCatching { Ed25519.seedFromPkcs8(x25519) }.isFailure)
		assertTrue(runCatching { Ed25519.seedFromPkcs8(Ed25519.PKCS8_PREFIX + ByteArray(31)) }.isFailure)
	}

	/** RFC 8439 2.8.2 through the chaCha() the Noise / move / export code uses. */
	@Test
	fun chachaRfc8439() {
		val key = h("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
		val nonce = h("070000004041424344454647")
		val aad = h("50515253c0c1c2c3c4c5c6c7")
		val pt = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.".toByteArray()
		val ct = chaCha(Cipher.ENCRYPT_MODE, key, nonce, aad, pt)
		assertArrayEquals(h("1ae10b594f09e26a7e902ecbd0600691"), ct.copyOfRange(ct.size - 16, ct.size))
		assertArrayEquals(pt, chaCha(Cipher.DECRYPT_MODE, key, nonce, aad, ct))
	}
}
