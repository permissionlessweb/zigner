package net.rotko.zigner.domain

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Key sets whose recovery phrase has not been backed up yet.
 *
 * A new key set can be created without putting its phrase on screen (for
 * onboarding in public places); the key set screen then keeps a "back up your
 * recovery phrase" prompt up until the phrase has been shown in a backup.
 *
 * Only seed names are stored, never phrases.
 */
object BackupReminder {
	private const val PREFS_NAME = "backup_reminder"
	private const val KEY_PENDING = "pending_seed_names"

	private val _pending = MutableStateFlow<Set<String>>(emptySet())

	/** Seed names still waiting for a backup; composables collect this. */
	val pending: StateFlow<Set<String>> = _pending

	@Volatile
	private var prefs: SharedPreferences? = null

	private fun prefs(context: Context): SharedPreferences =
		prefs ?: context.applicationContext
			.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
			.also {
				prefs = it
				_pending.value = it.getStringSet(KEY_PENDING, emptySet())?.toSet() ?: emptySet()
			}

	/** Load the persisted set (idempotent). */
	fun init(context: Context) {
		prefs(context)
	}

	fun markPending(seedName: String, context: Context) {
		update(context) { it + seedName }
	}

	fun clear(seedName: String, context: Context) {
		update(context) { it - seedName }
	}

	private fun update(context: Context, change: (Set<String>) -> Set<String>) {
		val p = prefs(context)
		val next = change(_pending.value)
		if (next == _pending.value) return
		_pending.value = next
		p.edit().putStringSet(KEY_PENDING, next).apply()
	}
}
