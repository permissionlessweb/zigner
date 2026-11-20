package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SezsDeviceDerivationTest {
	@Test
	fun slip10Ed25519OfficialVector1() {
		val seed = hex("000102030405060708090a0b0c0d0e0f")
		val master = SezsDeviceDerivation.slip10Master(seed, "ed25519 seed")
		assertEquals(
			"2b4be7f19ee27bbf30c667b642d5f4aa69fd169872f8fc3059c08ebae2eb19e7",
			SezsDeviceDerivation.hex(master.copyOfRange(0, 32)),
		)
		assertEquals(
			"90046a93de5380a72b5e45010748567d5ea02bbf6522f979e05c0d8d8ca9fffb",
			SezsDeviceDerivation.hex(master.copyOfRange(32, 64)),
		)
		val child = SezsDeviceDerivation.slip10Child(master, 0)
		assertEquals(
			"68e0fe46dfb67e368c75379acec591dad19df3cde26e63b93a8e704f1dade7a3",
			SezsDeviceDerivation.hex(child.copyOfRange(0, 32)),
		)
	}

	@Test
	fun slip10Curve25519OfficialVector1Master() {
		val seed = hex("000102030405060708090a0b0c0d0e0f")
		val master = SezsDeviceDerivation.slip10Master(seed, "curve25519 seed")
		assertEquals(
			"d70a59c2e68b836cc4bbe8bcae425169b9e2384f3905091e3d60b890e90cd92c",
			SezsDeviceDerivation.hex(master.copyOfRange(0, 32)),
		)
	}

	@Test
	fun bip39SeedMatchesEmptyPassphraseVector() {
		// Public BIP-39 test mnemonic. Not a user seed.
		val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
		val seed = SezsDeviceDerivation.bip39Seed(mnemonic)
		assertEquals(
			"5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
			SezsDeviceDerivation.hex(seed),
		)
	}

	@Test
	fun deriveIsDeterministicAndIndexAndRoleSeparated() {
		val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
		val a = SezsDeviceDerivation.derive(mnemonic, 0)
		val b = SezsDeviceDerivation.derive(mnemonic, 0)
		assertArrayEquals(a.signSeed, b.signSeed)
		assertArrayEquals(a.noiseSeed, b.noiseSeed)
		val otherIndex = SezsDeviceDerivation.derive(mnemonic, 1)
		assertFalse(a.signSeed.contentEquals(otherIndex.signSeed))
		assertFalse(a.noiseSeed.contentEquals(otherIndex.noiseSeed))
		assertFalse(a.signSeed.contentEquals(a.noiseSeed))
		assertEquals("m/1009'/0'/0'", SezsDeviceDerivation.pathSign(0))
		assertEquals("m/1009'/1'/3'", SezsDeviceDerivation.pathNoise(3))
		a.wipe()
		b.wipe()
		otherIndex.wipe()
	}

	@Test
	fun sezsPathsDifferFromSlip10Ed25519AtTerpLikePath() {
		val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
		val seed = SezsDeviceDerivation.bip39Seed(mnemonic)
		val sezs = SezsDeviceDerivation.deriveFromSeed(seed, 0)
		// If someone wrongly used SLIP-0010 ed25519 on a hardened stand-in
		// for the Terp BIP-44 path, the resulting key must still differ.
		val terpLike = SezsDeviceDerivation.deriveNode(
			seed,
			"ed25519 seed",
			intArrayOf(44, 118, 0, 0, 0),
		)
		assertFalse(sezs.signSeed.contentEquals(terpLike.copyOfRange(0, 32)))
		assertFalse(sezs.noiseSeed.contentEquals(terpLike.copyOfRange(0, 32)))
		sezs.wipe()
		terpLike.fill(0)
		seed.fill(0)
	}

	private fun hex(s: String): ByteArray {
		require(s.length % 2 == 0)
		return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
	}
}
