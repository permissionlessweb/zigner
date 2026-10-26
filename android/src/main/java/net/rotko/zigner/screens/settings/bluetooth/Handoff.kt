package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * Shared move helpers. The move itself is phone-to-phone over QR codes
 * (MoveQr / MoveFlow); the old Bluetooth 0x26 move import is gone.
 */
internal object Handoff {
	/** Animated QR frames: zc-anim:2:<session>:<i>/<n>:<part>, one random session per payload. */
	fun frames(payload: String, size: Int = 220, session: String = newSession()): List<String> {
		val parts = payload.chunked(size)
		return parts.mapIndexed { i, p -> "zc-anim:2:$session:$i/${parts.size}:$p" }
	}

	fun newSession(): String = MoveQr.hex(ByteArray(6).also { java.security.SecureRandom().nextBytes(it) })

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

/**
 * Step 2 of Move out (review M3): instead of "do the tiles match?", the user
 * taps the 2 tiles (of the first 4) shown on the NEW phone, picked from a
 * shuffled set with decoys that appear nowhere in its fingerprint. A wrong tap
 * aborts and the Receive QR has to be scanned again.
 */
internal class TileChallenge(val options: List<Pair<String, Long>>, val answer: Set<Int>) {
	fun correct(i: Int) = i in answer

	companion object {
		const val PICK = 2
		const val DECOYS = 4

		fun make(newPhoneId: ByteArray, rnd: java.util.Random = java.security.SecureRandom()): TileChallenge {
			val all = FingerprintTiles.of(newPhoneId)
			val firstFour = all.take(4).distinct()
			val real = firstFour.shuffled(rnd).take(PICK)
			val pool = FingerprintTiles.GLYPHS.flatMap { g -> FingerprintTiles.COLORS.map { c -> g to c } }.filter { it !in all }
			val decoys = pool.shuffled(rnd).take(DECOYS)
			val options = (real + decoys).shuffled(rnd)
			return TileChallenge(options, options.indices.filter { options[it] in real }.toSet())
		}
	}
}
