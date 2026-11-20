package net.rotko.zigner.screens.settings.bluetooth

/**
 * Canonical SEZS usher-device texts, matching apps/sezs/src/ble/messages.mjs.
 * The phone signs SEZS-TICKET-FRAME-V1 with the device ed25519 key.
 */
internal object TicketCanon {
	const val FRAME_DOMAIN = "SEZS-TICKET-FRAME-V1"
	const val GRANT_DOMAIN = "SEZS-DEVICE-GRANT-V1"

	fun eventTagHex(eventId: String): String = hex(sha256(eventId.toByteArray(Charsets.UTF_8))).substring(0, 16)

	fun encodeFrame(
		grantHash: String,
		deviceSignPubkey: String,
		eventId: String,
		seq: Long,
		ts: Long,
		payload: String,
		eventTag: String = eventTagHex(eventId),
	): String {
		require(grantHash.matches(HEX32)) { "grant_hash" }
		require(deviceSignPubkey.matches(HEX32)) { "device_sign_pubkey" }
		require(eventId.isNotBlank()) { "event" }
		require(eventTag.matches(HEX8)) { "event_tag" }
		require(seq >= 1L) { "seq" }
		require(ts > 0L) { "ts" }
		require(payload.startsWith("sezs3")) { "payload" }
		return listOf(
			FRAME_DOMAIN,
			"grant_hash: ${grantHash.lowercase()}",
			"device_sign_pubkey: ${deviceSignPubkey.lowercase()}",
			"event: $eventId",
			"event_tag: ${eventTag.lowercase()}",
			"seq: $seq",
			"ts: $ts",
			"payload: $payload.trim()",
		).joinToString("\n").let {
			// keep payload un-trimmed of the sezs3 body only
			it.replace("payload: $payload.trim()", "payload: ${payload.trim()}")
		}
	}

	fun signFrame(text: String, key: Ed25519Key): String {
		val sig = key.sign(text.toByteArray(Charsets.UTF_8))
		require(sig.size == 64) { "ed25519 sig" }
		return hex(sig)
	}

	fun envelope(text: String, sigHex: String): ByteArray =
		(text + "\n---\n" + sigHex.lowercase()).toByteArray(Charsets.UTF_8)

	fun parseSezs3(raw: String): String? {
		val s = raw.trim()
		if (!s.startsWith("sezs3")) return null
		if (!s.substring(5).all { it in "qpzry9x8gf2tvdw0s3jn54khce6mua7l" }) return null
		return s
	}

	private val HEX32 = Regex("^[0-9a-fA-F]{64}$")
	private val HEX8 = Regex("^[0-9a-fA-F]{16}$")

	fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
}
