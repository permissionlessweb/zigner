package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import java.io.File

/**
 * Local record of tickets this usher phone scanned. Heights of the
 * result, a short ticket tag, sequence and time only. The payload,
 * signatures and key material are never stored.
 */
internal object SezsTicketHistory {
	private const val FILE = "sezs-ticket-history.tsv"
	private const val KEEP = 300

	data class Row(
		val atMs: Long,
		val seq: Long,
		val ticketTag: String,
		val result: String,
		val reason: String,
	)

	fun ticketTag(payload: String): String {
		val digest = sha256(payload.toByteArray(Charsets.UTF_8))
		return SezsDeviceDerivation.hex(digest.copyOfRange(0, 8))
	}

	fun encode(row: Row): String =
		listOf(row.atMs, row.seq, row.ticketTag, row.result, row.reason)
			.joinToString("\t") { it.toString().replace("\t", " ").replace("\n", " ") }

	fun decode(line: String): Row? {
		val p = line.split("\t")
		if (p.size < 5) return null
		return try {
			Row(p[0].toLong(), p[1].toLong(), p[2], p[3], p[4])
		} catch (_: NumberFormatException) {
			null
		}
	}

	fun parse(lines: List<String>, max: Int = 100): List<Row> =
		lines.asReversed().asSequence().mapNotNull { decode(it) }.take(max).toList()

	fun load(context: Context, max: Int = 100): List<Row> {
		val f = File(context.filesDir, FILE)
		if (!f.exists()) return emptyList()
		return try {
			parse(f.readLines(), max)
		} catch (_: Exception) {
			emptyList()
		}
	}

	fun append(context: Context, row: Row) {
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
}
