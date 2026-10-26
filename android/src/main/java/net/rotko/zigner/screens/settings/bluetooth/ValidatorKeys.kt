package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Validator keys for zcinti. One phone holds many. Each key is for Terp.
 *
 * If a key already exists, bootstrap uses that key. A new key is created
 * only when the set is empty, or when the user adds one.
 *
 * The radio follows the bitchat pattern: one foreground service, one
 * advertiser, many laptop centrals. Each central is one session bound to
 * the Terp key that was selected when it connected. Noise XX per session
 * is how this scales. This set does not put a private key in a container.
 */
internal data class ValidatorKey(
	val id: String,
	val network: String,
	val chainId: String,
	val name: String,
	val pubHex: String,
	/** KMS slot this key serves, e.g. morocco-1_zcinti2 (kms engine label: chain id with / as _). */
	val slot: String = "",
)

internal object ValidatorKeys {
	const val CHAIN = "terp"

	fun list(context: Context): List<ValidatorKey> {
		migrateLegacy(context)
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: JSONArray()
		return assignSlots((0 until keys.length()).mapNotNull { index ->
			val item = keys.optJSONObject(index) ?: return@mapNotNull null
			val id = item.optString("id")
			if (id.isBlank() || item.optString("network", CHAIN) != CHAIN) return@mapNotNull null
			val raw = File(dir(context, id), "raw")
			if (!raw.exists()) return@mapNotNull null
			ValidatorKey(
				id = id,
				network = CHAIN,
				chainId = item.optString("chain"),
				name = item.optString("name").ifBlank { "Terp" },
				pubHex = raw.readBytes().joinToString("") { "%02x".format(it) },
				slot = item.optString("slot"),
			)
		})
	}

	private fun setField(context: Context, id: String, field: String, value: String) {
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: return
		for (i in 0 until keys.length()) {
			val item = keys.optJSONObject(i) ?: continue
			if (item.optString("id") == id) item.put(field, value)
		}
		writeIndex(context, root)
	}

	/** Pin the derived slot so it never shifts when other keys come and go. */
	private fun pinSlot(context: Context, id: String, wanted: String?) {
		val others = list(context).filter { it.id != id }.map { it.slot }.toSet()
		val slot = if (!wanted.isNullOrBlank() && wanted !in others) wanted
			else list(context).first { it.id == id }.slot
		setField(context, id, "slot", slot)
	}

	fun selectedId(context: Context): String? {
		migrateLegacy(context)
		val selected = readIndex(context).optString("selected")
		return selected.ifBlank { list(context).firstOrNull()?.id }
	}

	/** Reuse the selected Terp key. Create the first key only when none exist. */
	fun bootstrap(context: Context): Pair<ValidatorKey, Boolean> {
		val have = list(context)
		if (have.isNotEmpty()) {
			val id = selectedId(context)
			val key = have.firstOrNull { it.id == id } ?: have.first()
			return key to false
		}
		return add(context) to true
	}

	fun add(context: Context): ValidatorKey {
		migrateLegacy(context)
		val have = list(context)
		val taken = listWithoutMigrate(context).toSet()
		var n = have.size + 1
		while ("terp-$n" in taken || dir(context, "terp-$n").exists()) n++
		val id = "terp-$n"
		val folder = dir(context, id)
		folder.mkdirs()
		// Same files the JCA generator wrote (48-byte RFC 8410 PKCS#8, SPKI, raw).
		val seed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
		val raw = Ed25519.pub(seed)
		DeviceKey.writeSeed(folder, seed, raw)
		seed.fill(0)
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: JSONArray()
		keys.put(JSONObject().put("id", id).put("network", CHAIN).put("chain", "").put("name", "Terp $n"))
		root.put("keys", keys)
		root.put("selected", id)
		writeIndex(context, root)
		return ValidatorKey(id, CHAIN, "", "Terp $n", raw.joinToString("") { "%02x".format(it) })
	}

	/**
	 * Map a laptop chain QR onto a signer key set.
	 * The same chain opens the set that already has it.
	 * A new chain creates its own key, history, and control plane.
	 * Only the Terp network is accepted.
	 * bindTo null is "New validator": it always creates a key, even when the
	 * chain already has one (two validators on one chain, e.g. zcinti and
	 * zcinti2). Rescanning an open key set's own chain just opens it.
	 */
	fun acceptQr(context: Context, payload: String, bindTo: String? = null): ValidatorKey {
		val chainId = parseChain(payload)
		if (bindTo != null) {
			list(context).firstOrNull { it.id == bindTo && it.chainId == chainId }?.let { return it }
			val current = list(context).firstOrNull { it.id == bindTo }
				?: throw IllegalArgumentException("that signer key set is gone")
			if (current.chainId.isNotBlank() && current.chainId != chainId) {
				throw IllegalArgumentException("this key set is already ${current.chainId}")
			}
			val root = readIndex(context)
			val keys = root.optJSONArray("keys") ?: JSONArray()
			for (i in 0 until keys.length()) {
				val item = keys.optJSONObject(i) ?: continue
				if (item.optString("id") == bindTo) {
					val same = list(context).count { it.chainId == chainId && it.id != bindTo }
					item.put("chain", chainId)
					item.put("name", if (same == 0) chainId else "$chainId #${same + 1}")
				}
			}
			root.put("keys", keys)
			root.put("selected", bindTo)
			writeIndex(context, root)
			pinSlot(context, bindTo, parseSlot(payload))
			return list(context).first { it.id == bindTo }
		}
		val created = add(context)
		return acceptQr(context, payload, created.id)
	}

	fun parseChain(payload: String): String {
		val parts = payload.trim().split(":")
		if ((parts.size != 4 && parts.size != 5) || parts[0] != "zcinti" || parts[1] != "1") {
			throw IllegalArgumentException("scan the chain QR on the zcinti page")
		}
		if (parts[2] != CHAIN) {
			throw IllegalArgumentException("only Terp is available")
		}
		val chainId = parts[3].trim()
		if (chainId.isEmpty() || chainId.length > 64 || !chainId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
			throw IllegalArgumentException("that chain id is not usable")
		}
		return chainId
	}

	/** Optional 5th QR field names the node: zcinti:1:terp:morocco-1:zcinti2 -> slot morocco-1_zcinti2. */
	fun parseSlot(payload: String): String? {
		val parts = payload.trim().split(":")
		if (parts.size != 5) return null
		val node = parts[4].trim()
		if (node.isEmpty() || node.length > 32 || !node.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
		return "${parts[3].trim()}_$node"
	}

	/**
	 * Import a secret that arrived over Noise. It ADDS a key set unless this
	 * exact key (same public key) is already on the phone, in which case that
	 * set is refreshed. Another validator's key on the same chain is never replaced.
	 */
	fun importSecret(context: Context, chainId: String, secret: ByteArray, slot: String? = null) {
		if (secret.size != 64) throw IllegalArgumentException("consensus secret")
		val pubHex = secret.copyOfRange(32, 64).joinToString("") { "%02x".format(it) }
		if (Handoff.tombstoned(context, pubHex)) throw IllegalArgumentException("that key was retired on this phone")
		val existing = list(context).firstOrNull { it.pubHex == pubHex }
		val id = existing?.id ?: add(context).id
		val same = list(context).count { it.chainId == chainId && it.id != id }
		val seed = secret.copyOf(32)
		val pub = secret.copyOfRange(32, 64)
		DeviceKey.writeSeed(dir(context, id), seed, pub)
		seed.fill(0)
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: JSONArray()
		for (i in 0 until keys.length()) {
			val item = keys.optJSONObject(i) ?: continue
			if (item.optString("id") == id) {
				item.put("network", CHAIN)
				item.put("chain", chainId)
				if (existing == null) item.put("name", if (same == 0) chainId else "$chainId #${same + 1}")
			}
		}
		root.put("keys", keys)
		root.put("selected", id)
		writeIndex(context, root)
		if (existing == null || slot != null) pinSlot(context, id, slot)
	}

	fun noiseSeed(context: Context): ByteArray {
		val file = File(context.filesDir, "zcinti-noise.static")
		if (!file.exists()) {
			val fresh = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
			file.writeBytes(AtRest.seal(VaultKey.get(), fresh))
			return fresh
		}
		val raw = file.readBytes()
		if (!AtRest.isSealed(raw) && raw.size == 32) {
			file.writeBytes(AtRest.seal(VaultKey.get(), raw))
			return raw
		}
		return AtRest.open(VaultKey.get(), raw)
	}

	fun select(context: Context, id: String) {
		val root = readIndex(context)
		root.put("selected", id)
		writeIndex(context, root)
	}

	fun material(context: Context): Pair<Ed25519Key, ByteArray> {
		return materialFor(context, selectedId(context))
	}

	fun materialFor(context: Context, id: String?): Pair<Ed25519Key, ByteArray> {
		val key = list(context).firstOrNull { it.id == id } ?: bootstrap(context).first
		return DeviceKey.open(dir(context, key.id))
	}

	private fun migrateLegacy(context: Context) {
		val legacy = File(context.filesDir, "kms-ed25519.pkcs8")
		if (legacy.exists() && listWithoutMigrate(context).isEmpty()) {
			val id = "terp-1"
			val folder = dir(context, id)
			val pkcs8 = legacy.readBytes()
			DeviceKey.writePkcs8(folder, pkcs8)
			pkcs8.fill(0)
			File(context.filesDir, "kms-ed25519.spki").let { if (it.exists()) it.copyTo(File(folder, "spki"), overwrite = true) }
			File(context.filesDir, "kms-ed25519.raw").let { if (it.exists()) it.copyTo(File(folder, "raw"), overwrite = true) }
			val root = JSONObject()
			root.put("selected", id)
			root.put("keys", JSONArray().put(JSONObject().put("id", id).put("network", CHAIN).put("chain", "").put("name", "Terp 1")))
			writeIndex(context, root)
		}
		for (name in listOf("kms-ed25519.pkcs8", "kms-ed25519.spki", "kms-ed25519.raw")) {
			val file = File(context.filesDir, name)
			if (!file.exists()) continue
			file.writeBytes(ByteArray(file.length().toInt().coerceAtLeast(0)))
			file.delete()
		}
	}

	/** Renames the key whose keyid (sha256(pub)[:8]) is [keyIdHex]. False when no key matches. */
	fun renameByKeyId(context: Context, keyIdHex: String, name: String): Boolean {
		val key = list(context).firstOrNull { keyIdHex(it.pubHex) == keyIdHex } ?: return false
		rename(context, key.id, name)
		return true
	}

	/** Changes the display name only. Key files are not touched. */
	fun rename(context: Context, id: String, name: String) {
		val clean = String(utf8Cut(name.trim(), NAME_MAX), Charsets.UTF_8)
		require(clean.isNotEmpty()) { "the name is empty" }
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: return
		for (i in 0 until keys.length()) {
			val item = keys.optJSONObject(i) ?: continue
			if (item.optString("id") == id) item.put("name", clean)
		}
		writeIndex(context, root)
	}

	/**
	 * Wipes one key set: its sealed pkcs8, public files and high-water mark,
	 * then drops it from the index. The shared AndroidKeyStore wrapping key
	 * stays, so other key sets keep working.
	 */
	fun delete(context: Context, id: String) {
		require(id.isNotBlank() && !id.contains('/') && !id.contains("..")) { "bad key id" }
		val folder = dir(context, id)
		folder.listFiles()?.forEach { file ->
			if (file.isFile) {
				file.writeBytes(ByteArray(file.length().toInt().coerceAtLeast(0)))
				file.delete()
			}
		}
		folder.delete()
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: JSONArray()
		val kept = JSONArray()
		for (i in 0 until keys.length()) {
			val item = keys.optJSONObject(i) ?: continue
			if (item.optString("id") != id) kept.put(item)
		}
		root.put("keys", kept)
		if (root.optString("selected") == id) {
			root.put("selected", kept.optJSONObject(0)?.optString("id").orEmpty())
		}
		writeIndex(context, root)
	}

	private fun listWithoutMigrate(context: Context): List<String> {
		val keys = readIndex(context).optJSONArray("keys") ?: return emptyList()
		return (0 until keys.length()).mapNotNull { keys.optJSONObject(it)?.optString("id")?.ifBlank { null } }
	}

	private fun dir(context: Context, id: String) = File(context.filesDir, "validator-keys/$id")

	private fun index(context: Context) = File(context.filesDir, "validator-keys/index.json")

	private fun readIndex(context: Context): JSONObject {
		val file = index(context)
		if (!file.exists()) return JSONObject()
		return JSONObject(file.readText())
	}

	private fun writeIndex(context: Context, root: JSONObject) {
		val file = index(context)
		file.parentFile?.mkdirs()
		file.writeText(root.toString())
	}
}
