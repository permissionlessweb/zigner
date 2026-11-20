package net.rotko.zigner.screens.settings.bluetooth

import java.security.MessageDigest

/** Short display helpers for the SEZS usher tab. No secrets. */
internal object SezsDisplay {
	fun shortHex(hex: String, head: Int = 8, tail: Int = 6): String {
		val s = hex.trim()
		if (s.isEmpty()) return "—"
		if (s.length <= head + tail + 1) return s
		return "${s.take(head)}…${s.takeLast(tail)}"
	}

	fun eventTag(eventId: String): String {
		if (eventId.isBlank()) return "—"
		val digest = MessageDigest.getInstance("SHA-256")
		val hash = digest.digest(eventId.toByteArray(Charsets.UTF_8))
		return hash.take(8).joinToString("") { b -> "%02x".format(b) }
	}
}
