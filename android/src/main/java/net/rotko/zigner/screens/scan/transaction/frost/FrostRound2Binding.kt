package net.rotko.zigner.screens.scan.transaction.frost

import org.json.JSONArray


/**
 * Ties FROST round 2 to what the user approved in round 1.
 *
 * Round 1 is where the device verifies the request: it recomputes the sighash
 * from the coordinator's PCZT and decodes the outputs, and the user approves
 * that. Round 2 arrives as a SEPARATE QR from the same coordinator and
 * carries its own `sighash` and `alphas`. Without this check, round 2 signed
 * whatever those fields said - so a hostile coordinator could show
 * transaction A in round 1 and collect a valid signature share for
 * transaction B in round 2, with no further prompt.
 *
 * So the device remembers the exact sighash and per-action randomizers
 * (alphas) it verified and the user approved, and round 2 signs only if the
 * QR repeats them exactly. The share is a signature over (sighash, alpha) per
 * action, so these two are the whole binding.
 */
object FrostRound2Binding {

	data class Approved(val sighashHex: String, val alphas: List<String>)

	fun approve(sighashHex: String, alphasJson: String): Approved =
		Approved(normalizeHex(sighashHex), parseAlphas(alphasJson))

	/**
	 * Null when round 2 may proceed; otherwise the reason it must not.
	 * Fails closed on every malformed or missing input.
	 */
	fun check(approved: Approved?, sighashHex: String, alphasJson: String): String? {
		if (approved == null || approved.sighashHex.isEmpty()) {
			return "No approved round 1 on this device. Scan the round-1 request and approve it first."
		}
		val alphas = try {
			parseAlphas(alphasJson)
		} catch (e: Exception) {
			return "Round-2 request is malformed (alphas). Do not sign."
		}
		if (normalizeHex(sighashHex) != approved.sighashHex) {
			return "Round-2 request is for a DIFFERENT transaction than the one you approved. Do not sign."
		}
		if (alphas != approved.alphas) {
			return "Round-2 randomizers do not match the approved request. Do not sign."
		}
		return null
	}

	private fun normalizeHex(s: String): String = s.trim().lowercase()

	private fun parseAlphas(json: String): List<String> {
		val arr = JSONArray(json)
		return List(arr.length()) { normalizeHex(arr.getString(it)) }
	}
}
