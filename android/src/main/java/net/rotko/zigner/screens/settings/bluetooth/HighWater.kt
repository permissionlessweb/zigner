package net.rotko.zigner.screens.settings.bluetooth

import java.io.File

/**
 * Last consensus message this key signed.
 * A repeated height, round, and step returns the same signature only when
 * the bytes match. Any other bytes at that step are a double sign and are
 * refused. A lower height is refused.
 */
internal class HighWater {
	var set: Boolean = false
	var corrupt: Boolean = false
	var height: Long = 0
	var round: Int = 0
	var step: Int = 0
	var message: ByteArray = ByteArray(0)
	var signature: ByteArray = ByteArray(0)

	fun decide(height: Long, round: Int, step: Int, message: ByteArray): Decision {
		if (corrupt || message.isEmpty()) return Decision.Refuse
		val same = set && height == this.height && round == this.round && step == this.step
		if (!same && !allows(height, round, step)) return Decision.Refuse
		// A non-nil precommit signs the extension first, then the vote, at the
		// same height, round, and step. The extension may be signed again.
		// The vote bytes are the ones that must not change.
		if (isVoteExtension(message)) {
			val hr = extensionHeightRound(message) ?: return Decision.Refuse
			if (hr.first != height || hr.second != round.toLong()) return Decision.Refuse
			return Decision.Sign
		}
		if (same && message.isNotEmpty() && message.contentEquals(this.message)) {
			return Decision.Replay(signature.copyOf())
		}
		if (same && this.message.isNotEmpty()) return Decision.Refuse
		return Decision.Sign
	}

	fun note(height: Long, round: Int, step: Int) {
		val same = set && height == this.height && round == this.round && step == this.step
		set = true
		this.height = height
		this.round = round
		this.step = step
		if (!same) {
			message = ByteArray(0)
			signature = ByteArray(0)
		}
	}

	fun commit(height: Long, round: Int, step: Int, message: ByteArray, signature: ByteArray) {
		note(height, round, step)
		this.message = message.copyOf()
		this.signature = signature.copyOf()
	}

	private fun allows(height: Long, round: Int, step: Int): Boolean {
		if (!set) return true
		if (height != this.height) return height > this.height
		if (round != this.round) return round > this.round
		return step > this.step
	}

	fun write(file: File) {
		val parent = file.parentFile ?: return
		parent.mkdirs()
		val body = buildString {
			append(if (set) "1" else "0")
			append('\n')
			append(height)
			append('\n')
			append(round)
			append('\n')
			append(step)
			append('\n')
			append(message.toHex())
			append('\n')
			append(signature.toHex())
			append('\n')
		}
		val tmp = File(parent, file.name + ".tmp")
		java.io.FileOutputStream(tmp).use { out ->
			out.write(body.toByteArray(Charsets.UTF_8))
			out.fd.sync()
		}
		if (!tmp.renameTo(file)) {
			tmp.copyTo(file, overwrite = true)
			tmp.delete()
		}
		File(parent, file.name + ".ok").writeText("1")
	}

	companion object {
		fun read(file: File): HighWater {
			val mark = HighWater()
			val provisioned = File(file.parentFile, file.name + ".ok").exists()
			if (!file.exists()) {
				mark.corrupt = provisioned
				return mark
			}
			val lines = file.readText().split('\n')
			if (lines.size < 6 || lines[0] != "1") {
				mark.corrupt = provisioned
				return mark
			}
			val height = lines[1].toLongOrNull()
			val round = lines[2].toIntOrNull()
			val step = lines[3].toIntOrNull()
			val message = lines[4].hex()
			val signature = lines[5].hex()
			if (height == null || round == null || step == null || message == null || signature == null) {
				mark.corrupt = provisioned
				return mark
			}
			mark.set = true
			mark.height = height
			mark.round = round
			mark.step = step
			mark.message = message
			mark.signature = signature
			if (isVoteExtension(mark.message)) {
				mark.message = ByteArray(0)
				mark.signature = ByteArray(0)
			}
			return mark
		}
	}
}

/** Height and round inside a canonical vote extension. A missing round is 0. */
internal fun extensionHeightRound(message: ByteArray): Pair<Long, Long>? {
	var i = 0
	while (i < message.size && message[i].toInt() and 0x80 != 0) i++
	if (i >= message.size) return null
	i++
	if (i >= message.size) return null
	if ((message[i].toInt() and 0xff) == 0x0a) {
		i++
		if (i >= message.size) return null
		val n = message[i].toInt() and 0xff
		i += 1 + n
	}
	if (i >= message.size || (message[i].toInt() and 0xff) != 0x11 || i + 9 > message.size) return null
	i++
	var height = 0L
	for (b in 0 until 8) height = height or ((message[i + b].toLong() and 0xff) shl (8 * b))
	i += 8
	var round = 0L
	if (i < message.size && (message[i].toInt() and 0xff) == 0x19 && i + 9 <= message.size) {
		i++
		for (b in 0 until 8) round = round or ((message[i + b].toLong() and 0xff) shl (8 * b))
	}
	return height to round
}

/** Canonical vote extensions do not start with the vote type field. */
internal fun isVoteExtension(message: ByteArray): Boolean {
	var i = 0
	while (i < message.size && message[i].toInt() and 0x80 != 0) i++
	if (i >= message.size) return false
	i++
	if (i >= message.size) return false
	val tag = message[i].toInt() and 0xff
	return tag == 0x0a || tag == 0x11
}

internal sealed class Decision {
	data object Sign : Decision()
	data object Refuse : Decision()
	data class Replay(val signature: ByteArray) : Decision()
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.hex(): ByteArray? {
	if (length % 2 != 0) return null
	return try {
		chunked(2).map { it.toInt(16).toByte() }.toByteArray()
	} catch (_: NumberFormatException) {
		null
	}
}
