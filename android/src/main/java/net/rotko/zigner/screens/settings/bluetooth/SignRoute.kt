package net.rotko.zigner.screens.settings.bluetooth

/**
 * What a CometBFT sign request actually says, read from its canonical
 * length-prefixed protobuf sign bytes.
 * kind: 1 proposal, 2 prevote, 3 precommit, 4 vote extension.
 */
internal data class SignBytes(val kind: Int, val height: Long, val round: Long, val chainId: String)

internal fun parseSignBytes(m: ByteArray): SignBytes? {
	var i = 0
	fun varint(): Long? {
		var v = 0L
		var shift = 0
		while (i < m.size && shift < 64) {
			val b = m[i++].toInt() and 0xff
			v = v or ((b and 0x7f).toLong() shl shift)
			if (b and 0x80 == 0) return v
			shift += 7
		}
		return null
	}
	val len = varint() ?: return null
	if (len <= 0 || i + len != m.size.toLong()) return null
	val varints = mutableMapOf<Int, Long>()
	val fixed = mutableMapOf<Int, Long>()
	val bytes = mutableMapOf<Int, ByteArray>()
	while (i < m.size) {
		val tag = varint() ?: return null
		val field = (tag ushr 3).toInt()
		when ((tag and 7).toInt()) {
			0 -> varints[field] = varint() ?: return null
			1 -> {
				if (i + 8 > m.size) return null
				var v = 0L
				for (b in 0 until 8) v = v or ((m[i + b].toLong() and 0xff) shl (8 * b))
				fixed[field] = v
				i += 8
			}
			2 -> {
				val n = varint() ?: return null
				if (n < 0 || i + n > m.size) return null
				bytes[field] = m.copyOfRange(i, i + n.toInt())
				i += n.toInt()
			}
			5 -> { if (i + 4 > m.size) return null; i += 4 }
			else -> return null
		}
	}
	val height = fixed[2] ?: return null
	val round = fixed[3] ?: 0L
	if (isVoteExtension(m)) {
		val chain = bytes[4]?.decodeToString() ?: return null
		return SignBytes(4, height, round, chain)
	}
	return when (varints[1]) {
		1L -> SignBytes(2, height, round, bytes[6]?.decodeToString() ?: return null)
		2L -> SignBytes(3, height, round, bytes[6]?.decodeToString() ?: return null)
		32L -> SignBytes(1, height, round, bytes[7]?.decodeToString() ?: return null)
		else -> null
	}
}

/** Checks the sign bytes against the frame header. Null means fine. */
internal fun signBytesProblem(sb: SignBytes, height: Long, round: Int, step: Int): String? {
	if (sb.height <= 0) return "height ${sb.height} is not valid"
	if (sb.height != height) return "frame says height $height, sign bytes say ${sb.height}"
	if (sb.round != round.toLong()) return "frame says round $round, sign bytes say ${sb.round}"
	val want = when (sb.kind) { 1 -> 1; 2 -> 2; else -> 3 }
	if (step != want) return "frame step $step does not match a ${kindName(sb.kind)}"
	return null
}

internal fun kindName(kind: Int) = when (kind) {
	1 -> "proposal"; 2 -> "prevote"; 3 -> "precommit"; else -> "vote extension"
}

internal sealed class Route {
	data class Use(val key: ValidatorKey) : Route()
	data class Refuse(val why: String, val code: Byte = REFUSE_CHAIN) : Route()
}

/**
 * Picks the key for one sign request by chain id. [active] is the key the
 * user explicitly started (blank if none). [wantKey] is reserved for a key id
 * the KMS may send in the frame later. Never falls back silently.
 */
internal fun routeKey(keys: List<ValidatorKey>, active: String, chain: String, wantKey: String? = null): Route {
	if (chain.isBlank()) return Route.Refuse("the request has no chain id")
	if (!wantKey.isNullOrBlank()) {
		val k = keys.firstOrNull { it.id == wantKey } ?: return Route.Refuse("the KMS asked for key $wantKey, which is not on this phone", REFUSE_UNKNOWN_KEY)
		return if (k.chainId == chain) Route.Use(k) else Route.Refuse("key ${k.name} is for ${k.chainId.ifBlank { "no chain" }}, the request is for $chain")
	}
	if (active.isNotBlank()) {
		val k = keys.firstOrNull { it.id == active } ?: return Route.Refuse("the started key is gone; start a key for $chain", REFUSE_LOCKED)
		return if (k.chainId == chain) Route.Use(k) else Route.Refuse("${k.name} is started for ${k.chainId.ifBlank { "no chain" }}, the request is for $chain; start a $chain key")
	}
	val matches = keys.filter { it.chainId == chain }
	return when (matches.size) {
		1 -> Route.Use(matches[0])
		0 -> Route.Refuse("no key on this phone is for $chain")
		else -> Route.Refuse("${matches.size} keys are for $chain; press Start on the one that should sign", REFUSE_LOCKED)
	}
}

/** Wire id of a consensus key: sha256(pub)[:8] as 16 hex, as in kms keylink.go KeyID. */
internal fun keyIdHex(pubHex: String): String {
	if (pubHex.length != 64) return ""
	val pub = ByteArray(32) { pubHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
	val sum = java.security.MessageDigest.getInstance("SHA-256").digest(pub)
	return sum.copyOf(8).joinToString("") { "%02x".format(it) }
}

/**
 * One sign request after the Noise box is opened.
 * 0x10 | height u64 | round u32 | step u8 | len u16 | sign bytes          (legacy, routed by chain id)
 * 0x12 | key id (8) | height u64 | round u32 | step u8 | len u16 | sign bytes  (names the key)
 * keyId is null for 0x10.
 */
internal class SignFrame(val keyId: String?, val height: Long, val round: Int, val step: Int, val msg: ByteArray)

internal fun parseSignFrame(frame: ByteArray): SignFrame? {
	if (frame.isEmpty()) return null
	val keyed = when (frame[0]) {
		0x10.toByte() -> false
		0x12.toByte() -> true
		else -> return null
	}
	val at = if (keyed) 9 else 1
	if (frame.size < at + 15) return null
	fun u(i: Int) = frame[i].toLong() and 0xff
	var height = 0L
	for (i in 0 until 8) height = (height shl 8) or u(at + i)
	var round = 0
	for (i in 0 until 4) round = (round shl 8) or u(at + 8 + i).toInt()
	val step = u(at + 12).toInt()
	val n = ((u(at + 13) shl 8) or u(at + 14)).toInt()
	val start = at + 15
	if (n <= 0 || start + n > frame.size) return null
	val keyId = if (keyed) frame.copyOfRange(1, 9).joinToString("") { "%02x".format(it) } else null
	return SignFrame(keyId, height, round, step, frame.copyOfRange(start, start + n))
}

/**
 * The KEYS characteristic: every validator key on the phone, so one KMS can
 * drive several. Public data only.
 * 0x01 | count u8 | per key: key id (8) | chain len u8 | chain | label len u8 | label | pub (32)
 * Chain is capped at 64 bytes and label at 40 bytes (UTF-8, cut on a char boundary).
 */
internal fun encodeKeyList(keys: List<ValidatorKey>): ByteArray {
	val out = java.io.ByteArrayOutputStream()
	val usable = keys.filter { it.pubHex.length == 64 }.take(255)
	out.write(1)
	out.write(usable.size)
	fun cut(s: String, max: Int): ByteArray = utf8Cut(s, max)
	for (k in usable) {
		val id = keyIdHex(k.pubHex)
		out.write(ByteArray(8) { id.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
		val chain = cut(k.chainId, 64)
		out.write(chain.size); out.write(chain)
		val label = cut(k.slot.ifBlank { k.name }, 40)
		out.write(label.size); out.write(label)
		out.write(ByteArray(32) { k.pubHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
	}
	// Name trailer: 'N' [count] { len, UTF-8 name <= 40 bytes } in entry order.
	// Older scanners stop after the counted entries and never read this.
	out.write(NAME_TRAILER.toInt())
	out.write(usable.size)
	for (k in usable) {
		val name = utf8Cut(k.name.trim(), NAME_MAX)
		out.write(name.size); out.write(name)
	}
	return out.toByteArray()
}

internal const val NAME_TRAILER: Byte = 0x4E
internal const val NAME_MAX = 40
internal const val WIRE_RENAME: Byte = 0x56
internal const val WIRE_RENAME_ACK: Byte = 0x57
internal const val MSG_RENAME: Byte = 0x01

/** UTF-8 bytes of [s], cut to at most [max] bytes without splitting a code point. */
internal fun utf8Cut(s: String, max: Int): ByteArray {
	val b = s.toByteArray(Charsets.UTF_8)
	if (b.size <= max) return b
	var end = max
	// Back off continuation bytes (10xxxxxx) so the cut lands on a code point start.
	while (end > 0 && (b[end].toInt() and 0xC0) == 0x80) end--
	return b.copyOf(end)
}

internal data class RenameFrame(val keyIdHex: String, val name: String)

/** Opened 0x56 body: 0x01 keyId8 nameLen name. Null when malformed or the name is empty. */
internal fun parseRenameFrame(b: ByteArray): RenameFrame? {
	if (b.size < 10 || b[0] != MSG_RENAME) return null
	val len = b[9].toInt() and 0xff
	if (len == 0 || len > NAME_MAX || b.size != 10 + len) return null
	val raw = b.copyOfRange(10, 10 + len)
	val dec = Charsets.UTF_8.newDecoder()
		.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
		.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
	val name = try { dec.decode(java.nio.ByteBuffer.wrap(raw)).toString() } catch (_: Exception) { return null }
	if (name.isBlank() || name.any { it.isISOControl() }) return null
	return RenameFrame(b.copyOfRange(1, 9).joinToString("") { "%02x".format(it) }, name.trim())
}

/*
 * Refusal replies, kept aligned with kms signing/bluetooth/refusal.go:
 * [0x5f]                       bare (older zigner)
 * [0x5f][0x01][h u64][r u32][s u8]   stale: the key's high-water mark
 * [0x5f][code][UTF-8 text]     other codes, short text so it fits one notify
 */
internal const val REFUSE_STALE: Byte = 0x01
internal const val REFUSE_UNKNOWN_KEY: Byte = 0x02
internal const val REFUSE_NO_SESSION: Byte = 0x03
internal const val REFUSE_LOCKED: Byte = 0x04
internal const val REFUSE_CHAIN: Byte = 0x05
internal const val REFUSE_USER: Byte = 0x06
internal const val REFUSE_BAD_FRAME: Byte = 0x07

internal fun refusal(code: Byte, text: String = ""): ByteArray {
	var t = text.filter { it >= ' ' && it != '\u007f' }
	while (t.toByteArray().size > 120) t = t.dropLast(1)
	return byteArrayOf(0x5f, code) + t.toByteArray()
}

internal fun staleRefusal(height: Long, round: Int, step: Int): ByteArray =
	byteArrayOf(0x5f, REFUSE_STALE) +
		java.nio.ByteBuffer.allocate(13).putLong(height).putInt(round).put(step.toByte()).array()

/**
 * Every key gets a unique KMS slot. It matches the kms engine label (config
 * chain id with "/" as "_"): the first key on morocco-1 is morocco-1_zcinti,
 * the next morocco-1_zcinti2, and so on. A pinned slot is kept as is.
 */
internal fun assignSlots(keys: List<ValidatorKey>): List<ValidatorKey> {
	val taken = keys.map { it.slot }.filter { it.isNotBlank() }.toMutableSet()
	return keys.map { k ->
		if (k.slot.isNotBlank()) return@map k
		val base = "${k.chainId.ifBlank { "unbound" }}_zcinti"
		var n = 1
		var s = base
		while (s in taken) { n++; s = base + n }
		taken += s
		k.copy(slot = s)
	}
}

/**
 * 0x12 routing: the frame names the key; it signs only if that key is
 * started (armed) on this phone and is for the request's chain.
 */
internal fun routeKeyed(keys: List<ValidatorKey>, armed: Set<String>, chain: String, wireId: String): Route {
	val k = keys.firstOrNull { keyIdHex(it.pubHex) == wireId }
		?: return Route.Refuse("key $wireId is not on this phone", REFUSE_UNKNOWN_KEY)
	if (k.id !in armed) return Route.Refuse("${k.slot.ifBlank { k.name }} is not started on the phone", REFUSE_LOCKED)
	if (k.chainId != chain) return Route.Refuse("key ${k.name} is for ${k.chainId.ifBlank { "no chain" }}, the request is for $chain", REFUSE_CHAIN)
	return Route.Use(k)
}
