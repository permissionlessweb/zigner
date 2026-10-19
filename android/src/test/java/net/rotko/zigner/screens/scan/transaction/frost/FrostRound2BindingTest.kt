package net.rotko.zigner.screens.scan.transaction.frost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrostRound2BindingTest {

	private val sighashA = "aa".repeat(32)
	private val sighashB = "bb".repeat(32)
	private val alphas = """["${"01".repeat(32)}","${"02".repeat(32)}"]"""

	@Test
	fun matchingRound2IsAllowed() {
		val approved = FrostRound2Binding.approve(sighashA, alphas)
		assertNull(FrostRound2Binding.check(approved, sighashA, alphas))
	}

	// The attack: approve transaction A in round 1, then a round-2 QR for B.
	@Test
	fun differentSighashIsRefused() {
		val approved = FrostRound2Binding.approve(sighashA, alphas)
		val reason = FrostRound2Binding.check(approved, sighashB, alphas)
		assertNotNull(reason)
		assertTrue(reason!!.contains("DIFFERENT transaction"))
	}

	@Test
	fun changedReorderedOrExtraAlphasAreRefused() {
		val approved = FrostRound2Binding.approve(sighashA, alphas)
		val swapped = """["${"02".repeat(32)}","${"01".repeat(32)}"]"""
		val extra = """["${"01".repeat(32)}","${"02".repeat(32)}","${"03".repeat(32)}"]"""
		val changed = """["${"01".repeat(32)}","${"09".repeat(32)}"]"""
		for (bad in listOf(swapped, extra, changed)) {
			assertNotNull(bad, FrostRound2Binding.check(approved, sighashA, bad))
		}
	}

	// Fail closed: no approval on this device means no round-2 signature.
	@Test
	fun round2WithoutApprovalIsRefused() {
		assertNotNull(FrostRound2Binding.check(null, sighashA, alphas))
	}

	@Test
	fun malformedAlphasAreRefusedNotThrown() {
		val approved = FrostRound2Binding.approve(sighashA, alphas)
		assertNotNull(FrostRound2Binding.check(approved, sighashA, "not json"))
	}

	// Hex case/whitespace is encoding noise, not a different transaction.
	@Test
	fun hexCaseAndWhitespaceAreNormalized() {
		val approved = FrostRound2Binding.approve(sighashA, alphas)
		assertNull(FrostRound2Binding.check(approved, " ${sighashA.uppercase()} ", alphas.uppercase()))
	}

	@Test
	fun approvalRecordsNormalizedValues() {
		val approved = FrostRound2Binding.approve(sighashA.uppercase(), alphas)
		assertEquals(sighashA, approved.sighashHex)
		assertEquals(2, approved.alphas.size)
	}
}
