package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.security.Signature
import javax.crypto.Cipher

/**
 * Moving one validator key between phones through the KMS console.
 *
 *   console shows   zcinti-handoff:1:<chain>:<slot>:<nonceHex>:<floor>:<kmsPubB64>
 *   old phone       Move out -> zcinti-recover:2 (animated), later Retire -> zcinti-retired:1
 *   new phone       Receive  -> pins the KMS, expects that slot and nonce, refuses
 *                   any import without a height floor.
 *
 * The owner (operator) seed never moves here; only its address.
 */
internal object Handoff {
	data class Ticket(val chain: String, val slot: String, val nonce: ByteArray, val floor: Long, val kmsPub: ByteArray, val sent: Boolean = false) {
		val label get() = "${chain}_$slot"
	}

	private fun ok(s: String) = s.isNotEmpty() && s.length <= 64 && s.all { it.isLetterOrDigit() || it == '-' || it == '_' }

	fun parse(payload: String): Ticket {
		val p = payload.trim().split(":")
		if ((p.size != 7 && !(p.size == 8 && p[7] == "sent")) || p[0] != "zcinti-handoff" || p[1] != "1") throw IllegalArgumentException("scan the Move QR on the console")
		if (!ok(p[2]) || !ok(p[3])) throw IllegalArgumentException("chain or slot is not usable")
		val nonce = p[4].hexOrNull() ?: throw IllegalArgumentException("bad nonce")
		if (nonce.size != 16) throw IllegalArgumentException("bad nonce")
		val floor = p[5].toLongOrNull() ?: throw IllegalArgumentException("bad floor")
		if (floor <= 0) throw IllegalArgumentException("a move needs a height floor")
		val kms = Base64.decode(p[6], Base64.DEFAULT)
		if (kms.size != 32) throw IllegalArgumentException("KMS identity is not a public key")
		return Ticket(p[2], p[3], nonce, floor, kms, p.size == 8)
	}

	fun keyFor(context: Context, t: Ticket): ValidatorKey =
		ValidatorKeys.list(context).firstOrNull { it.slot == t.label && it.chainId == t.chain }
			?: throw IllegalArgumentException("no key on this phone serves ${t.label}")

	private fun hwmFile(context: Context, id: String) = File(context.filesDir, "validator-keys/$id/hwm.txt")

	/** Old phone: seal this slot's key, its mark and the owner address to the KMS. */
	fun exportV2(context: Context, t: Ticket): String {
		if (t.sent) throw IllegalStateException("this move already sent the key: use Retire")
		val key = keyFor(context, t)
		val folder = File(context.filesDir, "validator-keys/${key.id}")
		val pkcs8 = DeviceKey.readPkcs8(folder)
		val pub = File(folder, "raw").readBytes()
		if (pkcs8.size < 32 || pub.size != 32) throw IllegalStateException("key set")
		val secret = pkcs8.copyOfRange(pkcs8.size - 32, pkcs8.size) + pub
		pkcs8.fill(0)
		val mark = HighWater.read(hwmFile(context, key.id))
		if (mark.corrupt) { secret.fill(0); throw IllegalStateException("this key's double-sign mark is damaged") }
		val owner = GranterAccount.lookup(context)
		// Freeze before the key leaves: from here this phone refuses votes for
		// it until the move is cancelled (PhoneGate.unfreeze).
		PhoneGate.freeze(context.filesDir, key.id, if (mark.set) mark.height else t.floor)
		val plain = exportPlain(t, if (mark.set) mark.height else 0, if (mark.set) mark.round else 0, if (mark.set) mark.step else 0, owner, secret)
		secret.fill(0)
		val eph = ByteArray(32).also { SecureRandom().nextBytes(it) }
		val ephPub = X25519.base(eph)
		val shared = X25519.shared(eph, t.kmsPub)
		eph.fill(0)
		val k = sha256(shared)
		shared.fill(0)
		val sealed = chaCha(Cipher.ENCRYPT_MODE, k, nonce(0), "zcinti-export-v2".toByteArray(), plain)
		k.fill(0)
		plain.fill(0)
		return "zcinti-recover:2:" + Base64.encodeToString(ephPub + sealed, Base64.NO_WRAP)
	}

	internal fun exportPlain(t: Ticket, h: Long, r: Int, s: Int, owner: String, secret: ByteArray): ByteArray {
		val chain = t.chain.toByteArray()
		val slot = t.slot.toByteArray()
		val own = owner.toByteArray().let { if (it.size > 90) ByteArray(0) else it }
		val b = ByteBuffer.allocate(1 + chain.size + 1 + slot.size + 16 + 8 + 4 + 1 + 1 + own.size + 1 + 64)
		b.put(chain.size.toByte()).put(chain).put(slot.size.toByte()).put(slot).put(t.nonce)
		b.putLong(h).putInt(r).put(s.coerceIn(0, 255).toByte())
		b.put(own.size.toByte()).put(own).put(0).put(secret)
		return b.array()
	}

	/** Animated QR frames: zc-anim:1:<i>/<n>:<part>. */
	fun frames(payload: String, size: Int = 220): List<String> {
		val parts = payload.chunked(size)
		return parts.mapIndexed { i, p -> "zc-anim:1:$i/${parts.size}:$p" }
	}

	fun retireMessage(t: Ticket, pub: ByteArray): ByteArray {
		val id = sha256(pub).copyOfRange(0, 8).joinToString("") { "%02x".format(it) }
		return "zcinti-retire:1|${t.chain}|${t.slot}|$id|${t.floor}|${t.nonce.joinToString("") { "%02x".format(it) }}".toByteArray()
	}

	/**
	 * Old phone: sign the retire proof, record the tombstone, wipe the key.
	 * Returns zcinti-retired:1:<sig>.
	 */
	fun retire(context: Context, t: Ticket): String {
		if (!t.sent) throw IllegalStateException("the console has not sent the key to the new phone yet: keep this key")
		val key = keyFor(context, t)
		val (priv, pub) = ValidatorKeys.materialFor(context, key.id)
		val signer = Signature.getInstance("Ed25519")
		signer.initSign(priv)
		signer.update(retireMessage(t, pub))
		val sig = signer.sign()
		addTombstone(context, key.pubHex)
		ValidatorKeys.delete(context, key.id)
		return "zcinti-retired:1:" + Base64.encodeToString(sig, Base64.NO_WRAP)
	}

	private fun tombFile(context: Context) = File(context.filesDir, "validator-keys/tombstones.json")

	fun tombstoned(context: Context, pubHex: String): Boolean {
		val f = tombFile(context)
		if (!f.exists()) return false
		return try {
			val a = JSONArray(f.readText())
			(0 until a.length()).any { a.optString(it) == pubHex }
		} catch (_: Exception) {
			true // damaged list: refuse
		}
	}

	private fun addTombstone(context: Context, pubHex: String) {
		val f = tombFile(context)
		val a = try { if (f.exists()) JSONArray(f.readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
		a.put(pubHex)
		f.parentFile?.mkdirs()
		val tmp = File(f.parentFile, f.name + ".tmp")
		tmp.writeText(a.toString())
		if (!tmp.renameTo(f)) { tmp.copyTo(f, true); tmp.delete() }
	}

	private fun expectFile(context: Context) = File(context.filesDir, "zcinti-handoff.expect")

	/** New phone: pin this KMS and arm one import for the ticket's slot and nonce. */
	fun arm(context: Context, t: Ticket) {
		File(context.filesDir, "zcinti-kms.peer").writeBytes(t.kmsPub)
		val o = JSONObject()
			.put("chain", t.chain).put("slot", t.slot)
			.put("nonce", t.nonce.joinToString("") { "%02x".format(it) })
			.put("floor", t.floor)
			.put("at", System.currentTimeMillis())
		expectFile(context).writeText(o.toString())
	}

	fun armed(context: Context): JSONObject? = try {
		expectFile(context).takeIf { it.exists() }?.readText()?.let { JSONObject(it) }
	} catch (_: Exception) { null }

	fun disarm(context: Context) { expectFile(context).delete() }

	/**
	 * New phone: a 0x26 import. Refused unless armed for this slot and nonce,
	 * with a floor, and the key is not tombstoned. Returns the public key.
	 */
	fun accept(context: Context, opened: ByteArray): ByteArray {
		val exp = armed(context) ?: throw IllegalStateException("not expecting a key: tap Receive first")
		if (System.currentTimeMillis() - exp.optLong("at") > 30 * 60 * 1000L) { disarm(context); throw IllegalStateException("Receive expired") }
		val b = ByteBuffer.wrap(opened)
		fun str(): String { val n = b.get().toInt() and 0xff; val a = ByteArray(n); b.get(a); return a.decodeToString() }
		val chain = str()
		val slot = str()
		val n = ByteArray(16).also { b.get(it) }
		val floor = b.long
		b.int; b.get()
		val owner = str()
		val secret = ByteArray(64).also { b.get(it) }
		try {
			if (b.hasRemaining()) throw IllegalArgumentException("trailing bytes")
			if (chain != exp.optString("chain") || slot != exp.optString("slot")) throw IllegalArgumentException("key is for $chain/$slot, not the slot this phone expects")
			if (n.joinToString("") { "%02x".format(it) } != exp.optString("nonce")) throw IllegalArgumentException("key is for another move")
			if (floor <= 0 || floor < exp.optLong("floor")) throw IllegalArgumentException("key arrived without its height floor")
			val pub = secret.copyOfRange(32, 64)
			val pubHex = pub.joinToString("") { "%02x".format(it) }
			if (tombstoned(context, pubHex)) throw IllegalArgumentException("that key was retired on this phone")
			ValidatorKeys.importSecret(context, chain, secret, "${chain}_$slot")
			val key = ValidatorKeys.list(context).first { it.pubHex == pubHex }
			// The floor goes down before the key can sign anything.
			HighWater().also { it.note(floor, Int.MAX_VALUE, Int.MAX_VALUE) }.write(hwmFile(context, key.id))
			PhoneGate.setFloor(context.filesDir, key.id, floor)
			PhoneGate.unfreeze(context.filesDir, key.id)
			if (owner.startsWith("terp1")) GranterAccount.pin(context, owner)
			disarm(context)
			return pub
		} finally {
			secret.fill(0)
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

private fun String.hexOrNull(): ByteArray? {
	if (length % 2 != 0) return null
	return try { chunked(2).map { it.toInt(16).toByte() }.toByteArray() } catch (_: NumberFormatException) { null }
}
