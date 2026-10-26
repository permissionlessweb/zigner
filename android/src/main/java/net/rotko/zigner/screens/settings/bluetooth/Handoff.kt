package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Shared move helpers. The move itself is phone-to-phone over QR codes
 * (MoveQr / MoveFlow); the old Bluetooth 0x26 move import is gone.
 */
internal object Handoff {
	/** Animated QR frames: zc-anim:1:<i>/<n>:<part>. */
	fun frames(payload: String, size: Int = 220): List<String> {
		val parts = payload.chunked(size)
		return parts.mapIndexed { i, p -> "zc-anim:1:$i/${parts.size}:$p" }
	}

	private fun tombFile(context: Context) = File(context.filesDir, "validator-keys/tombstones.json")

	fun tombstoned(context: Context, pubHex: String): Boolean {
		val f = tombFile(context)
		if (!f.exists()) return false
		return try {
			val a = JSONArray(f.readText())
			(0 until a.length()).any { a.optString(it).equals(pubHex, ignoreCase = true) }
		} catch (_: Exception) {
			true // damaged list: refuse
		}
	}
}

/** Fingerprint tiles shared with the console: glyph = b&15, colour = (b>>4)&7. */
internal object FingerprintTiles {
	val GLYPHS = listOf("●", "▲", "■", "◆", "★", "♥", "♠", "♣", "☀", "☾", "⚡", "✿", "☂", "♪", "✚", "⬢")
	val COLORS = longArrayOf(0xFF2F7D6D, 0xFFC2603B, 0xFF3D63B8, 0xFFA5487F, 0xFFB8962E, 0xFF5B8A2F, 0xFF7A4FC4, 0xFF3A3A3A)
	fun of(pub: ByteArray): List<Pair<String, Long>> =
		sha256(pub).copyOfRange(0, 8).map { val b = it.toInt() and 0xff; GLYPHS[b and 15] to COLORS[(b shr 4) and 7] }
}
