package net.rotko.zigner.screens.keysets.restore

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import net.rotko.zigner.dependencygraph.ServiceLocator
import net.rotko.zigner.domain.backend.RecoverSeedInteractor
import net.rotko.zigner.domain.backend.mapError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class KeysetRecoverViewModel internal constructor(
	private val guessWordsFn: suspend (String) -> List<String>,
	private val validateSeedFn: suspend (List<String>) -> Boolean,
	val existingSeeds: StateFlow<Array<String>>,
	initialSuggestions: List<String>,
) : ViewModel() {

	constructor() : this(RecoverSeedInteractor())

	private constructor(interactor: RecoverSeedInteractor) : this(
		guessWordsFn = { input -> interactor.seedPhraseGuessWords(input).mapError() ?: emptyList() },
		validateSeedFn = { phrase ->
			interactor.validateSeedPhrase(phrase.joinToString(separator = " ")).mapError() ?: false
		},
		existingSeeds = ServiceLocator.seedStorage.lastKnownSeedNames,
		initialSuggestions = runBlocking {
			interactor.seedPhraseGuessWords("").mapError() ?: emptyList()
		},
	)

	private val _recoverSeed = MutableStateFlow<KeysetRecoverModel>(
		KeysetRecoverModel.new(initialSuggestions)
	)
	val recoverSeed = _recoverSeed.asStateFlow()

	fun onSelectWord(index: Int) {
		val current = _recoverSeed.value
		if (index !in current.enteredWords.indices) return
		val word = current.enteredWords[index]
		val newRawInput = KeysetRecoverModel.SPACE_CHARACTER.toString() + word
		_recoverSeed.update {
			it.copy(
				rawUserInput = newRawInput,
				editingIndex = index,
				suggestedWords = emptyList(),
				validSeed = false,
			)
		}
		viewModelScope.launch {
			val guessing = guessWordsFn(word.lowercase())
			_recoverSeed.update { state ->
				if (state.editingIndex == index && state.rawUserInput == newRawInput) {
					state.copy(suggestedWords = guessing)
				} else {
					state
				}
			}
		}
	}

	fun onUserInput(rawUserInput: String) {
		if (rawUserInput.isEmpty()) {
			var updatedDraft: List<String>? = null
			_recoverSeed.update {
				val editIdx = it.editingIndex
				val newDraft = if (editIdx != null && editIdx in it.enteredWords.indices) {
					it.enteredWords.filterIndexed { i, _ -> i != editIdx }
				} else {
					it.enteredWords.dropLast(1)
				}
				updatedDraft = newDraft
				it.copy(
					rawUserInput = KeysetRecoverModel.SPACE_CHARACTER.toString(),
					enteredWords = newDraft,
					editingIndex = null,
					validSeed = false,
				)
			}
			updatedDraft?.let { revalidate(it) }
			return
		}

		if (_recoverSeed.value.editingIndex == null &&
			_recoverSeed.value.enteredWords.size >= KeysetRecoverModel.WORDS_CAP
		) {
			_recoverSeed.update {
				it.copy(rawUserInput = KeysetRecoverModel.SPACE_CHARACTER.toString())
			}
			return
		}

		if (rawUserInput.first() != KeysetRecoverModel.SPACE_CHARACTER) {
			_recoverSeed.update {
				it.copy(
					rawUserInput = KeysetRecoverModel.SPACE_CHARACTER.toString() + rawUserInput,
				)
			}
			return
		}

		// Always accept the input immediately so the text field stays responsive
		_recoverSeed.update {
			it.copy(rawUserInput = rawUserInput)
		}

		// Fetch suggestions asynchronously
		val input = rawUserInput.trim().lowercase()
		val editIdx = _recoverSeed.value.editingIndex
		viewModelScope.launch {
			val guessing = guessWordsFn(input)
			if (rawUserInput.length > 1 && rawUserInput.endsWith(KeysetRecoverModel.SPACE_CHARACTER)) {
				val state = _recoverSeed.value
				if (state.rawUserInput == rawUserInput && state.editingIndex == editIdx && guessing.contains(input)) {
					onAddword(input)
				}
			} else {
				_recoverSeed.update { current ->
					if (current.rawUserInput == rawUserInput && current.editingIndex == editIdx) {
						current.copy(suggestedWords = guessing)
					} else {
						current
					}
				}
				if (editIdx == null && guessing.contains(input)) {
					checkCompletingWord(rawUserInput, input)
				}
			}
		}
	}

	/**
	 * The last word is often left in the field without a trailing space (a
	 * keyboard suggestion pick, or simply typing it out). If that word completes
	 * a valid phrase, remember the full phrase so Next can be enabled; it is
	 * committed by [commitForProceed] before continuing.
	 */
	private suspend fun checkCompletingWord(rawUserInput: String, word: String) {
		val committed = _recoverSeed.value.enteredWords
		if (committed.size >= KeysetRecoverModel.WORDS_CAP) return
		val candidate = committed + word
		if (!validateSeedFn(candidate)) return
		_recoverSeed.update { current ->
			if (current.rawUserInput == rawUserInput &&
				current.editingIndex == null &&
				current.enteredWords == committed
			) {
				current.copy(completingPhrase = candidate)
			} else {
				current
			}
		}
	}

	/**
	 * Commits a valid final word still in the input field, so the phrase used to
	 * continue is exactly the one on screen. Returns null if Next must stay disabled.
	 */
	fun commitForProceed(): List<String>? {
		var phrase: List<String>? = null
		_recoverSeed.update {
			phrase = it.proceedPhrase
			val complete = phrase ?: return@update it
			it.copy(
				rawUserInput = KeysetRecoverModel.SPACE_CHARACTER.toString(),
				enteredWords = complete,
				validSeed = true,
				completingPhrase = null,
			)
		}
		return phrase
	}

	fun onAddword(word: String) {
		var committedDraft: List<String>? = null
		_recoverSeed.update {
			val editIdx = it.editingIndex
			val newDraft = if (editIdx != null && editIdx in it.enteredWords.indices) {
				it.enteredWords.toMutableList().apply { set(editIdx, word) }
			} else {
				if (it.enteredWords.size >= KeysetRecoverModel.WORDS_CAP) {
					return@update it.copy(rawUserInput = KeysetRecoverModel.SPACE_CHARACTER.toString())
				}
				it.enteredWords + word
			}
			committedDraft = newDraft
			it.copy(
				rawUserInput = KeysetRecoverModel.SPACE_CHARACTER.toString(),
				enteredWords = newDraft,
				editingIndex = null,
				suggestedWords = emptyList(),
				validSeed = false,
			)
		}
		committedDraft?.let { revalidate(it) }
	}

	private fun revalidate(draft: List<String>) {
		viewModelScope.launch {
			val valid = validateSeedFn(draft)
			val suggestions = guessWordsFn("")
			_recoverSeed.update { current ->
				if (current.enteredWords == draft && current.editingIndex == null) {
					current.copy(
						validSeed = valid,
						suggestedWords = if (current.rawUserInput.trim().isEmpty()) {
							suggestions
						} else {
							current.suggestedWords
						},
					)
				} else {
					current
				}
			}
		}
	}
}


data class KeysetRecoverModel(
	val rawUserInput: String,
	val suggestedWords: List<String>,
	val enteredWords: List<String>,
	val validSeed: Boolean,
	val editingIndex: Int? = null,
	// Committed words plus the word still in the field, when that completes a valid phrase.
	val completingPhrase: List<String>? = null,
) {
	/** Phrase Next would continue with, or null while Next must stay disabled. */
	val proceedPhrase: List<String>?
		get() = when {
			editingIndex != null -> null
			validSeed && rawUserInput.isBlank() -> enteredWords
			completingPhrase != null &&
				completingPhrase == enteredWords + rawUserInput.trim().lowercase() -> completingPhrase
			else -> null
		}

	val canProceed: Boolean
		get() = proceedPhrase != null

	companion object {

		// Maximum word count in `bip39` standard.
		// See <https://docs.rs/tiny-bip39/0.8.2/src/bip39/mnemonic_type.rs.html#60>
		const val WORDS_CAP: Int = 24;

		const val SPACE_CHARACTER: Char = ' '
		const val NEW_LINE: Char = '\n'

		fun new(suggestedWords: List<String>): KeysetRecoverModel {
			return KeysetRecoverModel(
				rawUserInput = SPACE_CHARACTER.toString(),
				suggestedWords = suggestedWords,
				enteredWords = emptyList(),
				validSeed = false,
				editingIndex = null,
			)
		}

		fun stub(): KeysetRecoverModel {
			return KeysetRecoverModel(
				rawUserInput = "ggf",
				suggestedWords = listOf("ggfhg", "goha"),
				enteredWords = listOf("somve", "words", "that", "are", "draft"),
				validSeed = false,
				editingIndex = null,
			)
		}
	}
}
