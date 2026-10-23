package net.rotko.zigner.screens.settings.bluetooth

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

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
)

internal object ValidatorKeys {
	const val CHAIN = "terp"

	fun list(context: Context): List<ValidatorKey> {
		migrateLegacy(context)
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: JSONArray()
		return (0 until keys.length()).mapNotNull { index ->
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
			)
		}
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
		val id = "terp-${have.size + 1}"
		val folder = dir(context, id)
		folder.mkdirs()
		val gen = KeyPairGenerator.getInstance("Ed25519")
		val pair = gen.generateKeyPair()
		val raw = DeviceKey.rawPublic(pair)
		DeviceKey.writePkcs8(folder, pair.private.encoded)
		File(folder, "spki").writeBytes(pair.public.encoded)
		File(folder, "raw").writeBytes(raw)
		val root = readIndex(context)
		val keys = root.optJSONArray("keys") ?: JSONArray()
		keys.put(JSONObject().put("id", id).put("network", CHAIN).put("chain", "").put("name", "Terp ${have.size + 1}"))
		root.put("keys", keys)
		root.put("selected", id)
		writeIndex(context, root)
		return ValidatorKey(id, CHAIN, "", "Terp ${have.size + 1}", raw.joinToString("") { "%02x".format(it) })
	}

	/**
	 * Map a laptop chain QR onto a signer key set.
	 * The same chain opens the set that already has it.
	 * A new chain creates its own key, history, and control plane.
	 * Only the Terp network is accepted.
	 */
	fun acceptQr(context: Context, payload: String, bindTo: String? = null): ValidatorKey {
		val chainId = parseChain(payload)
		list(context).firstOrNull { it.chainId == chainId }?.let { existing ->
			select(context, existing.id)
			return existing
		}
		if (bindTo != null) {
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
					item.put("chain", chainId)
					item.put("name", chainId)
				}
			}
			root.put("keys", keys)
			root.put("selected", bindTo)
			writeIndex(context, root)
			return list(context).first { it.id == bindTo }
		}
		val created = add(context)
		return acceptQr(context, payload, created.id)
	}

	fun parseChain(payload: String): String {
		val parts = payload.trim().split(":")
		if (parts.size != 4 || parts[0] != "zcinti" || parts[1] != "1") {
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

	/** Replace or create the key set for chain with a secret that arrived over Noise. */
	fun importSecret(context: Context, chainId: String, secret: ByteArray) {
		if (secret.size != 64) throw IllegalArgumentException("consensus secret")
		val existing = list(context).firstOrNull { it.chainId == chainId }
		val id = existing?.id ?: add(context).id
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
				item.put("name", chainId)
			}
		}
		root.put("keys", keys)
		root.put("selected", id)
		writeIndex(context, root)
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

	fun material(context: Context): Pair<PrivateKey, ByteArray> {
		return materialFor(context, selectedId(context))
	}

	fun materialFor(context: Context, id: String?): Pair<PrivateKey, ByteArray> {
		val key = list(context).firstOrNull { it.id == id } ?: bootstrap(context).first
		val folder = dir(context, key.id)
		val factory = KeyFactory.getInstance("Ed25519")
		val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(DeviceKey.readPkcs8(folder)))
		val raw = File(folder, "raw").readBytes()
		return privateKey to raw
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
