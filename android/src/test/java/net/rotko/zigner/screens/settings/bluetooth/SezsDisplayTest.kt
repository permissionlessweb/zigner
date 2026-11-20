package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Test

class SezsDisplayTest {
	@Test
	fun shortHexAbbrAndBlank() {
		assertEquals("—", SezsDisplay.shortHex(""))
		assertEquals("aabbcc", SezsDisplay.shortHex("aabbcc"))
		assertEquals("aaaaaaaa…bbbbbb", SezsDisplay.shortHex("aaaaaaaa" + "00".repeat(10) + "bbbbbb"))
	}

	@Test
	fun eventTagIsFirstEightBytesOfSha256() {
		assertEquals("69f58cc4047c20a9", SezsDisplay.eventTag("speakez-sessions"))
	}
}
