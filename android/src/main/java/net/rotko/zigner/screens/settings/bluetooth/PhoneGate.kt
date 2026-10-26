package net.rotko.zigner.screens.settings.bluetooth

import org.json.JSONArray
import java.io.File

/**
 * Phone-side move gate (review F2). The console already stops voting for a
 * slot it froze or handed off, but the phone must not trust that:
 *
 *  - a key whose public key is tombstoned never signs again;
 *  - a key frozen by Move out (exportV2) does not sign until the move is
 *    cancelled on this phone (unfreeze), so two phones never both sign;
 *  - an imported key never signs at or below the height floor it arrived
 *    with (validator-keys/<id>/floor), even if its high-water file is lost.
 *
 * Files live under the app's filesDir; tests pass a temp dir.
 */
internal object PhoneGate {
	private fun keyDir(base: File, keyId: String) = File(base, "validator-keys/$keyId")
	private fun frozenFile(base: File, keyId: String) = File(keyDir(base, keyId), "frozen")
	private fun floorFile(base: File, keyId: String) = File(keyDir(base, keyId), "floor")

	fun freeze(base: File, keyId: String, floor: Long) {
		keyDir(base, keyId).mkdirs()
		write(frozenFile(base, keyId), floor.toString())
	}

	fun unfreeze(base: File, keyId: String) { frozenFile(base, keyId).delete() }

	fun frozen(base: File, keyId: String): Boolean = frozenFile(base, keyId).exists()

	fun setFloor(base: File, keyId: String, floor: Long) {
		keyDir(base, keyId).mkdirs()
		write(floorFile(base, keyId), floor.toString())
	}

	/** Height floor, or null; a damaged file blocks every height. */
	fun floor(base: File, keyId: String): Long? {
		val f = floorFile(base, keyId)
		if (!f.exists()) return null
		return f.readText().trim().toLongOrNull() ?: Long.MAX_VALUE
	}

	fun tombstoned(base: File, pubHex: String): Boolean {
		val f = File(base, "validator-keys/tombstones.json")
		if (!f.exists()) return false
		return try {
			val a = JSONArray(f.readText())
			(0 until a.length()).any { a.optString(it).equals(pubHex, ignoreCase = true) }
		} catch (_: Exception) {
			true
		}
	}

	/** Why this key may not sign at height, or null when it may. */
	fun check(base: File, keyId: String, pubHex: String, height: Long): String? = when {
		tombstoned(base, pubHex) -> "this key was retired on this phone"
		frozen(base, keyId) -> "this key is frozen for a move; cancel the move on this phone to sign here"
		floor(base, keyId)?.let { height <= it } == true -> "height $height is at or below this key's move floor ${floor(base, keyId)}"
		else -> null
	}

	private fun write(f: File, s: String) {
		val tmp = File(f.parentFile, f.name + ".tmp")
		tmp.writeText(s)
		if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
	}
}
