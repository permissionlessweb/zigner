package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PhoneGateTest {
	private fun base(): File = Files.createTempDirectory("gate").toFile()
	private val pub = "ab".repeat(32)

	@Test fun freshKeySigns() {
		assertNull(PhoneGate.check(base(), "k1", pub, 100))
	}

	@Test fun frozenKeyRefusesUntilUnfrozen() {
		val b = base()
		PhoneGate.freeze(b, "k1", 90)
		assertNotNull(PhoneGate.check(b, "k1", pub, 100))
		assertNull(PhoneGate.check(b, "k2", pub, 100))
		PhoneGate.unfreeze(b, "k1")
		assertNull(PhoneGate.check(b, "k1", pub, 100))
	}

	@Test fun floorRefusesAtOrBelow() {
		val b = base()
		PhoneGate.setFloor(b, "k1", 500)
		assertNotNull(PhoneGate.check(b, "k1", pub, 499))
		assertNotNull(PhoneGate.check(b, "k1", pub, 500))
		assertNull(PhoneGate.check(b, "k1", pub, 501))
	}

	@Test fun damagedFloorRefusesEverything() {
		val b = base()
		File(b, "validator-keys/k1").mkdirs()
		File(b, "validator-keys/k1/floor").writeText("garbage")
		assertEquals(Long.MAX_VALUE, PhoneGate.floor(b, "k1"))
		assertNotNull(PhoneGate.check(b, "k1", pub, 10_000_000))
	}

	@Test fun tombstonedKeyNeverSigns() {
		val b = base()
		File(b, "validator-keys").mkdirs()
		File(b, "validator-keys/tombstones.json").writeText("[\"$pub\"]")
		assertNotNull(PhoneGate.check(b, "k1", pub, 1_000_000))
		File(b, "validator-keys/tombstones.json").writeText("{bad")
		assertNotNull(PhoneGate.check(b, "k1", "cd".repeat(32), 1))
	}
}
