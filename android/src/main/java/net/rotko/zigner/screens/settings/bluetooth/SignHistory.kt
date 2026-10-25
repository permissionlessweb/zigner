package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import java.io.File

/**
 * Persistent sign history. The foreground service runs in the app process,
 * but a kill and sticky restart wiped the in-memory rows, so the History tab
 * showed nothing. Each row is one tab-separated line in filesDir; heights,
 * steps and key ids only, never key material or signatures.
 */
internal object SignHistory {
	private const val FILE = "sign-history.tsv"
	private const val KEEP = 300

	fun encode(row: SignedRow): String =
		listOf(row.height, row.round, row.step, row.latencyMs, row.at, row.keyId, row.result, row.atMs)
			.joinToString("\t") { it.toString().replace("\t", " ").replace("\n", " ") }

	fun decode(line: String): SignedRow? {
		val p = line.split("\t")
		if (p.size < 8) return null
		return try {
			SignedRow(p[0].toLong(), p[1].toInt(), p[2].toInt(), p[3].toLong(), p[4], p[5], p[6], p[7].toLong())
		} catch (_: NumberFormatException) {
			null
		}
	}

	/** Newest first, at most [max]. */
	fun parse(lines: List<String>, max: Int = 100): List<SignedRow> =
		lines.asReversed().asSequence().mapNotNull { decode(it) }.take(max).toList()

	fun load(context: Context, max: Int = 100): List<SignedRow> {
		val f = File(context.filesDir, FILE)
		if (!f.exists()) return emptyList()
		return try { parse(f.readLines(), max) } catch (_: Exception) { emptyList() }
	}

	fun append(context: Context, row: SignedRow) {
		try {
			val f = File(context.filesDir, FILE)
			f.appendText(encode(row) + "\n")
			if (f.length() > 96_000) {
				val tail = f.readLines().takeLast(KEEP)
				f.writeText(tail.joinToString("\n", postfix = "\n"))
			}
		} catch (_: Exception) {
		}
	}

	/**
	 * Liveness, not intent: "Signing" only while signatures are flowing.
	 * [lastSignedMs] is wall-clock ms of the last signed row, 0 for none.
	 */
	fun liveLabel(started: Boolean, lastSignedMs: Long, nowMs: Long, windowMs: Long = 30_000): String {
		if (!started) return "Stopped"
		if (lastSignedMs <= 0L) return "Idle · no requests yet"
		val age = nowMs - lastSignedMs
		if (age in 0..windowMs) return "Signing"
		return "Idle · last sign ${ago(age)} ago"
	}

	fun isLive(started: Boolean, lastSignedMs: Long, nowMs: Long, windowMs: Long = 30_000) =
		started && lastSignedMs > 0 && nowMs - lastSignedMs in 0..windowMs

	fun ago(ms: Long): String {
		val s = (ms / 1000).coerceAtLeast(0)
		return when {
			s < 90 -> "${s}s"
			s < 5400 -> "${s / 60}m"
			s < 172_800 -> "${s / 3600}h ${(s % 3600) / 60}m"
			else -> "${s / 86400}d"
		}
	}
}
