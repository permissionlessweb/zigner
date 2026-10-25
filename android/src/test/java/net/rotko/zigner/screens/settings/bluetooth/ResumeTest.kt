package net.rotko.zigner.screens.settings.bluetooth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeTest {
	@Test fun onlyResumesAStartedSigner() {
		val arm = RemoteSignerService.ACTION_ARM
		val resume = RemoteSignerService.ACTION_RESUME
		assertTrue(RemoteSignerService.shouldRun(arm, false, emptySet()))
		assertTrue(RemoteSignerService.shouldRun(null, true, setOf("terp-2")))
		assertTrue(RemoteSignerService.shouldRun(resume, true, setOf("terp-2")))
		assertFalse(RemoteSignerService.shouldRun(null, false, setOf("terp-2")))
		assertFalse(RemoteSignerService.shouldRun(resume, true, emptySet()))
		assertFalse(RemoteSignerService.shouldRun(resume, false, emptySet()))
	}

	@Test fun bootNeedsEveryArmedKeyStored() {
		assertTrue(resumeKeysOk(setOf("a", "b"), setOf("a", "b", "c")))
		assertFalse(resumeKeysOk(setOf("a", "gone"), setOf("a")))
		assertFalse(resumeKeysOk(emptySet(), setOf("a")))
	}
}
