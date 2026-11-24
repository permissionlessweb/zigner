package net.rotko.zigner.screens.keysets.restore

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KeysetRecoverViewModelTest {

	private val testDispatcher = StandardTestDispatcher()

	private val wordlist = listOf("abandon", "ability", "able", "about", "art")
	private val valid24 = List(23) { "abandon" } + "art"
	private val valid12 = List(11) { "abandon" } + "about"

	@Before
	fun setUp() {
		Dispatchers.setMain(testDispatcher)
	}

	@After
	fun tearDown() {
		Dispatchers.resetMain()
	}

	private fun makeViewModel(
		validate: suspend (List<String>) -> Boolean = { it == valid24 || it == valid12 },
	): KeysetRecoverViewModel {
		return KeysetRecoverViewModel(
			guessWordsFn = { prefix -> wordlist.filter { it.startsWith(prefix) } },
			validateSeedFn = validate,
			existingSeeds = MutableStateFlow(emptyArray()),
			initialSuggestions = wordlist,
		)
	}

	@Test
	fun twentyFourWordsWithLastWordUncommittedCannotProceed() = runTest(testDispatcher) {
		val vm = makeViewModel()
		repeat(23) { vm.onAddword("abandon") }
		advanceUntilIdle()

		vm.onUserInput(" art")
		advanceUntilIdle()

		val state = vm.recoverSeed.value
		assertEquals(23, state.enteredWords.size)
		assertFalse(state.validSeed)
		assertFalse(state.canProceed)
	}

	@Test
	fun deleting24thWordImmediatelyDisablesNext() = runTest(testDispatcher) {
		val vm = makeViewModel()
		repeat(23) { vm.onAddword("abandon") }
		vm.onAddword("art")
		advanceUntilIdle()
		assertTrue(vm.recoverSeed.value.canProceed)

		// Backspace on the sentinel drops the 24th word and clears validity synchronously.
		vm.onUserInput("")
		assertEquals(23, vm.recoverSeed.value.enteredWords.size)
		assertFalse(vm.recoverSeed.value.validSeed)
		assertFalse(vm.recoverSeed.value.canProceed)

		// Typing the 24th word without committing keeps Next disabled.
		vm.onUserInput(" art")
		advanceUntilIdle()
		assertEquals(23, vm.recoverSeed.value.enteredWords.size)
		assertFalse(vm.recoverSeed.value.canProceed)
	}

	@Test
	fun uncommittedTypingAfterValid12WordsDisablesProceed() = runTest(testDispatcher) {
		val vm = makeViewModel()
		repeat(11) { vm.onAddword("abandon") }
		vm.onAddword("about")
		advanceUntilIdle()
		assertTrue(vm.recoverSeed.value.canProceed)

		// Starting word 13 leaves enteredWords at 12, but Next must stay disabled until committed/cleared.
		vm.onUserInput(" ab")
		advanceUntilIdle()
		assertFalse(vm.recoverSeed.value.canProceed)

		// Committing word 24 with trailing space enables proceed once valid.
		vm.onUserInput(" abandon ")
		advanceUntilIdle()
		assertEquals(13, vm.recoverSeed.value.enteredWords.size)
		assertFalse(vm.recoverSeed.value.canProceed)
	}

	@Test
	fun staleAsyncValidationCannotMarkModifiedPhraseValid() = runTest(testDispatcher) {
		val gate12 = CompletableDeferred<Boolean>()
		val vm = makeViewModel(validate = { phrase ->
			when (phrase.size) {
				12 -> gate12.await()
				else -> false
			}
		})
		repeat(11) { vm.onAddword("abandon") }
		vm.onAddword("about")
		// Backspace word 12 before its validation completes.
		vm.onUserInput("")
		gate12.complete(true)
		advanceUntilIdle()

		assertEquals(11, vm.recoverSeed.value.enteredWords.size)
		assertFalse(vm.recoverSeed.value.validSeed)
		assertFalse(vm.recoverSeed.value.canProceed)
	}
}
