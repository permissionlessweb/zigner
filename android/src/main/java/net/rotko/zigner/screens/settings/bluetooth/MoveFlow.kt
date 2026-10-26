package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.security.SecureRandom

/**
 * Android side of the phone-to-phone QR move (see MoveQr). UI state lives
 * here so the one shared Scan button can route every move QR.
 */
internal object MoveFlow {
	/** Old phone: key id -> console start ticket scanned for it (memory only). */
	var outStart by mutableStateOf<Map<String, MoveQr.Start>>(emptyMap())
	/** Old phone: key id -> receiver scanned for it (memory only). */
	var outTarget by mutableStateOf<Map<String, MoveQr.Receiver>>(emptyMap())
	/** New phone: the pending Receive (ephemeral seed in memory only, single use). */
	private var recvSeed: ByteArray? = null
	private var recvAt = 0L
	var receiveQr by mutableStateOf<String?>(null)
	/** New phone: last import result shown as the Imported QR. */
	var imported by mutableStateOf<Imported?>(null)
	/** Old phone: the retire proof to show the console (not secret). */
	var retireProof by mutableStateOf<String?>(null)
	/** Shared scan: KMS identity waiting for the user to confirm tiles. */
	var kmsCandidate by mutableStateOf<ByteArray?>(null)
	/** Where the scan router wants the UI to go: key id to open on its Chain tab. */
	var focusKey by mutableStateOf<String?>(null)

	data class Imported(val keyName: String, val ack: String, val oldFp: String, val oldId: ByteArray, val floor: Long)

	private fun base(context: Context) = context.applicationContext.filesDir
	private fun hwm(context: Context, id: String) = File(base(context), "validator-keys/$id/hwm.txt")

	fun phoneId(context: Context): ByteArray = X25519.base(ValidatorKeys.noiseSeed(context))

	// ---------- shared scan ----------

	/** Route one scanned (and fully assembled) QR. Returns a status line. */
	fun route(context: Context, text: String, openKey: String?): String {
		val t = text.trim()
		return when {
			t.startsWith("zcinti-kms:1:") -> {
				val pub = KeyExport.kmsPublic(t)
				if (pairedKms(context)?.contentEquals(pub) == true) {
					kmsCandidate = null
					SignerRuntime.refreshLink(context)
				} else {
					kmsCandidate = pub
					"Compare the tiles with the console, then Pair this KMS."
				}
			}
			t.startsWith(MoveQr.START) -> {
				val s = MoveQr.parseStart(t)
				val key = beginOut(context, s)
				focusKey = key.id
				"Move ${key.name}: now show Receive on the new phone and scan it."
			}
			t.startsWith(MoveQr.RECV) -> {
				val r = MoveQr.parseReceive(t)
				val id = (openKey?.takeIf { outStart.containsKey(it) } ?: outStart.keys.singleOrNull())
					?: throw IllegalStateException("scan the console's Move QR on the old phone first")
				setTarget(context, id, r)
				focusKey = id
				"New phone scanned. Compare both fingerprints, then Show move QR."
			}
			t.startsWith(MoveQr.SEALED) -> {
				val got = importIn(context, t)
				"Imported ${got.keyName}. Show the Imported QR to the old phone."
			}
			t.startsWith(MoveQr.IMPORTED) -> {
				val id = openKey?.takeIf { MoveStore.outGet(base(context), it) != null }
					?: ValidatorKeys.list(context).map { it.id }.singleOrNull { MoveStore.outGet(base(context), it) != null }
					?: throw IllegalStateException("no move out is waiting for this Imported QR")
				retire(context, id, t)
				focusKey = null
				"Retired on this phone. Show the retire proof to the console."
			}
			else -> throw IllegalArgumentException("not a zcinti QR")
		}
	}

	// ---------- pairing ----------

	fun pairedKms(context: Context): ByteArray? =
		File(base(context), "zcinti-kms.peer").takeIf { it.exists() && it.length() == 32L }?.readBytes()

	fun pairKms(context: Context, pub: ByteArray) {
		File(base(context), "zcinti-kms.peer").writeBytes(pub)
		kmsCandidate = null
		SignerRuntime.pendingKms = ""
	}

	// ---------- old phone ----------

	fun keyFor(context: Context, s: MoveQr.Start): ValidatorKey =
		ValidatorKeys.list(context).firstOrNull { it.slot == s.label && it.chainId == s.chain }
			?: throw IllegalArgumentException("no key on this phone serves ${s.label}")

	fun beginOut(context: Context, s: MoveQr.Start): ValidatorKey {
		val key = keyFor(context, s)
		val kms = pairedKms(context)
		if (kms != null && !kms.contentEquals(s.kmsPub)) throw IllegalArgumentException("this Move QR is from another KMS than the one this phone is paired to")
		val mark = HighWater.read(hwm(context, key.id))
		if (mark.corrupt) throw IllegalStateException("this key's double-sign mark is damaged")
		// A stale ticket (floor at or below what this phone already signed) is refused.
		if (mark.set && mark.height >= s.floor) throw IllegalArgumentException("this phone signed height ${mark.height}, above the move floor ${s.floor}: start the move again on the console")
		outStart = outStart + (key.id to s)
		outTarget = outTarget - key.id
		return key
	}

	fun setTarget(context: Context, keyId: String, r: MoveQr.Receiver) {
		val s = outStart[keyId] ?: throw IllegalStateException("scan the console's Move QR first")
		if (r.id.contentEquals(phoneId(context))) throw IllegalArgumentException("that Receive QR is from this phone")
		val prior = MoveStore.outGet(base(context), keyId)
		if (prior != null && prior.first.nonceHex == s.nonceHex && !prior.second.contentEquals(r.id)) {
			throw IllegalStateException("this move was already shown to another phone; cancel it on the console and start again")
		}
		outTarget = outTarget + (keyId to r)
	}

	/** After BiometricPrompt: freeze, record the target, seal. Returns the payload (caller animates it). */
	fun sealOut(context: Context, keyId: String): String {
		val s = outStart[keyId] ?: throw IllegalStateException("scan the console's Move QR first")
		val r = outTarget[keyId] ?: throw IllegalStateException("scan the new phone's Receive QR first")
		val key = ValidatorKeys.list(context).firstOrNull { it.id == keyId } ?: throw IllegalStateException("key is gone")
		val mark = HighWater.read(hwm(context, key.id))
		if (mark.corrupt) throw IllegalStateException("this key's double-sign mark is damaged")
		if (mark.set && mark.height >= s.floor) throw IllegalStateException("this phone signed above the move floor; start again on the console")
		MoveStore.outSet(base(context), key.id, s, r.id)
		// Freeze before any key byte is shown: this phone refuses votes for it from here.
		PhoneGate.freeze(base(context), key.id, s.floor)
		val folder = File(base(context), "validator-keys/${key.id}")
		val pkcs8 = DeviceKey.readPkcs8(folder)
		val pub = File(folder, "raw").readBytes()
		if (pkcs8.size < 32 || pub.size != 32) { pkcs8.fill(0); throw IllegalStateException("key set") }
		val secret = Ed25519.seedFromPkcs8(pkcs8) + pub
		pkcs8.fill(0)
		val (priv, _) = ValidatorKeys.materialFor(context, key.id)
		val idSeed = ValidatorKeys.noiseSeed(context)
		try {
			return MoveQr.seal(
				s, r, idSeed, GranterAccount.lookup(context),
				if (mark.set) MoveQr.Mark(mark.height, mark.round, mark.step) else MoveQr.Mark(0, 0, 0),
				secret, { MoveQr.sign(priv, it) },
			)
		} finally {
			secret.fill(0); idSeed.fill(0); priv.wipe()
		}
	}

	fun awaitingAck(context: Context, keyId: String) = MoveStore.outGet(base(context), keyId) != null

	/** Cancel on the old phone. Allowed until the Imported QR was scanned. */
	fun cancelOut(context: Context, keyId: String) {
		outStart = outStart - keyId
		outTarget = outTarget - keyId
		MoveStore.outClear(base(context), keyId)
		PhoneGate.unfreeze(base(context), keyId)
	}

	/** Verify the new phone's Imported QR, then tombstone + wipe + sign the retire proof. */
	fun retire(context: Context, keyId: String, ackText: String): String {
		val (s, target) = MoveStore.outGet(base(context), keyId) ?: throw IllegalStateException("no move out for this key")
		val key = ValidatorKeys.list(context).firstOrNull { it.id == keyId } ?: throw IllegalStateException("key is gone")
		val pub = key.pubHex.hexOrNull() ?: throw IllegalStateException("key set")
		val (sig, mac) = MoveQr.ackParse(ackText)
		val msg = MoveQr.ackMessage(s, pub, target)
		if (!MoveQr.verify(pub, msg, sig)) {
			throw IllegalArgumentException("Imported QR is not from the phone this key was moved to")
		}
		val idSeed = ValidatorKeys.noiseSeed(context)
		val idOk = try { MoveQr.ackMacOk(idSeed, target, X25519.base(idSeed), target, msg, mac) } finally { idSeed.fill(0) }
		if (!idOk) throw IllegalArgumentException("Imported QR was not made by the new phone's identity")
		val (priv, _) = ValidatorKeys.materialFor(context, key.id)
		val proof = try { MoveQr.retiredText(target, MoveQr.sign(priv, MoveQr.retireMessage(s, pub, target))) } finally { priv.wipe() }
		MoveStore.write(MoveStore.proofFile(base(context)), proof)
		MoveStore.addTombstone(base(context), key.pubHex)
		ValidatorKeys.delete(context, key.id)
		outStart = outStart - keyId
		outTarget = outTarget - keyId
		retireProof = proof
		SignerRuntime.refreshKeys(context)
		return proof
	}

	fun storedProof(context: Context): String? =
		MoveStore.proofFile(base(context)).takeIf { it.exists() }?.readText()

	fun clearProof(context: Context) { MoveStore.proofFile(base(context)).delete(); retireProof = null }

	// ---------- new phone ----------

	fun newReceive(context: Context): String {
		recvSeed?.fill(0)
		val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
		recvSeed = seed
		recvAt = System.currentTimeMillis()
		return MoveQr.receiveText(X25519.base(seed), phoneId(context)).also { receiveQr = it }
	}

	fun cancelReceive() { recvSeed?.fill(0); recvSeed = null; receiveQr = null }

	fun importIn(context: Context, payload: String): Imported {
		val seed = recvSeed ?: throw IllegalStateException("tap Receive a key first")
		if (System.currentTimeMillis() - recvAt > MoveQr.RECV_TTL_MS) { cancelReceive(); throw IllegalStateException("Receive expired: tap Receive a key again") }
		val idSeed = ValidatorKeys.noiseSeed(context)
		val o = try { MoveQr.open(payload, seed, idSeed) } finally { idSeed.fill(0) }
		try {
			val s = o.start
			val pubHex = MoveQr.hex(o.pub)
			val b = base(context)
			if (MoveStore.nonceUsed(b, s.nonceHex)) throw IllegalArgumentException("this move was already imported (replay)")
			if (Handoff.tombstoned(context, pubHex)) throw IllegalArgumentException("that key was retired on this phone")
			if (ValidatorKeys.list(context).any { it.pubHex == pubHex }) throw IllegalArgumentException("this key is already on this phone")
			val kms = pairedKms(context)
			if (kms != null && !kms.contentEquals(s.kmsPub)) throw IllegalArgumentException("this phone is paired to another KMS")
			ValidatorKeys.importSecret(context, s.chain, o.secret, s.label)
			val key = ValidatorKeys.list(context).first { it.pubHex == pubHex }
			// Frozen first: it signs only after the console pinned this phone (PhoneGate.releaseImported).
			PhoneGate.freezeImported(b, key.id, s.floor)
			// The floor goes down before the key can sign anything.
			HighWater().also { it.note(s.floor, Int.MAX_VALUE, Int.MAX_VALUE) }.write(hwm(context, key.id))
			PhoneGate.setFloor(b, key.id, s.floor)
			// Only a completed import uses up the nonce.
			MoveStore.markNonce(b, s.nonceHex)
			if (kms == null) File(b, "zcinti-kms.peer").writeBytes(s.kmsPub)
			if (o.owner.startsWith("terp1")) GranterAccount.pin(context, o.owner)
			val (priv, _) = ValidatorKeys.materialFor(context, key.id)
			val msg = MoveQr.ackMessage(s, o.pub, o.target)
			val sig = try { MoveQr.sign(priv, msg) } finally { priv.wipe() }
			val myId = ValidatorKeys.noiseSeed(context)
			val mac = try { MoveQr.ackMac(myId, o.oldId, o.oldId, o.target, msg) } finally { myId.fill(0) }
			val ack = MoveQr.ackText(sig, mac)
			MoveStore.write(MoveStore.ackFile(b), ack)
			cancelReceive()
			SignerRuntime.refreshKeys(context)
			return Imported(key.name, ack, KeyExport.fingerprint(o.oldId), o.oldId, s.floor).also { imported = it }
		} finally {
			o.wipe()
		}
	}
}
