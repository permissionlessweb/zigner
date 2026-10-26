package net.rotko.zigner.screens.settings.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.security.SecureRandom

/**
 * Process-wide remote signer. Leaving the signer screen does not stop it.
 * The foreground service owns the radio until the user stops the signer or
 * the system destroys the service.
 */
internal object SignerRuntime {
	private const val PREFS = "remote_signer"
	private const val ARMED = "armed"
	private const val CODE = "code"
	private const val ACTIVE = "active_key"
	private const val ARMED_KEYS = "armed_keys"

	var status by mutableStateOf("Remote signer is off.")
	var code by mutableStateOf("")
	var pub by mutableStateOf("")
	var running by mutableStateOf(false)
	var sessions by mutableStateOf(0)
	var granterAccount by mutableStateOf("")
	var pendingKms by mutableStateOf("")
	var blockHeight by mutableStateOf(0L)
	var blockLagMs by mutableStateOf(-1L)
	var nodeValidator by mutableStateOf<Boolean?>(null)
	var nodeChain by mutableStateOf("")
	var consensusAddr by mutableStateOf("")
	var blockSeenAt by mutableStateOf(0L)
	var keys by mutableStateOf(listOf<ValidatorKey>())
	var selected by mutableStateOf("")
	/** Key that signed the last request, blank when the last one was refused. */
	var signingKey by mutableStateOf("")
	val rows = mutableStateListOf<SignedRow>()
	/** Wall-clock ms of the last signed row (0 = none). */
	var lastSignedMs by mutableStateOf(0L)
	private var historyLoaded = false
	private var lastAdvertiseAt = 0L

	private val main = Handler(Looper.getMainLooper())
	private var gatt: KmsGattServer? = null
	private var advertiser: BluetoothLeAdvertiser? = null
	private var advertiseCallback: AdvertiseCallback? = null
	private var epoch = 0
	private var wantRadio = false
	private var granterWatch = false
	private var btWatch: BroadcastReceiver? = null
	var onSigned: ((SignedRow) -> Unit)? = null
	var onStatusText: ((String) -> Unit)? = null

	fun currentEpoch(): Int = epoch

	fun isArmed(context: Context): Boolean {
		return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ARMED, false)
	}

	/**
	 * Keys started on this phone. Each validator key has its own Start/Stop;
	 * the radio is on while at least one key is started. 0x12 frames sign only
	 * for a started key. Legacy 0x10 signs with the active key (the first one
	 * started), so starting a second key never moves the legacy signer.
	 */
	fun armedKeys(context: Context): Set<String> {
		val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		val set = p.getString(ARMED_KEYS, null)?.split(",")?.filter { it.isNotBlank() }?.toSet()
		val active = p.getString(ACTIVE, "").orEmpty()
		return armedSet(set, active, p.getBoolean(ARMED, false))
	}

	/**
	 * The active key is the legacy 0x10 signer, so while armed it is started
	 * for 0x12 too. Before this, ARMED_KEYS could lose it (a Start while the
	 * radio was briefly down overwrote the set) and keyed frames for the key
	 * the phone was visibly signing were refused "not started" (code 4).
	 */
	fun armedSet(stored: Set<String>?, active: String, armed: Boolean): Set<String> {
		if (!armed) return stored ?: emptySet()
		val base = stored ?: emptySet()
		return if (active.isNotBlank()) base + active else base
	}

	private fun saveArmed(context: Context, set: Set<String>) {
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
			.putString(ARMED_KEYS, set.joinToString(",")).apply()
	}

	fun isKeyStarted(context: Context, id: String) = running && id in armedKeys(context)

	/** Stop one key. The radio stays on for the other started keys. */
	fun stopKey(context: Context, id: String) {
		val app = context.applicationContext
		val left = armedKeys(app) - id
		saveArmed(app, left)
		if (activeKey(app) == id) {
			app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE, left.firstOrNull().orEmpty()).apply()
		}
		if (left.isEmpty()) stop(app) else {
			status = "Stopped ${keys.firstOrNull { it.id == id }?.name ?: id}. ${left.size} other key(s) keep signing."
		}
	}

	fun startKey(context: Context, id: String) {
		val app = context.applicationContext
		selectKey(app, id)
		// Persisted state, not the radio flag: an advertise failure clears
		// running, and Start must not then forget the other started keys.
		val before = if (isArmed(app)) armedKeys(app) else emptySet()
		saveArmed(app, before + id)
		val active = activeKey(app)
		if (active.isBlank() || active !in before) {
			app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE, id).apply()
		}
		if (running && before.isNotEmpty()) {
			status = "Started ${keys.firstOrNull { it.id == id }?.name ?: id}. ${before.size + 1} keys are signing."
			return
		}
		wantRadio = true
		bump()
		releaseRadio()
		app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ARMED, true).apply()
		status = "opening the GATT server"
		try {
			RemoteSignerService.start(app)
		} catch (err: Exception) {
			running = false
			status = err.message ?: "could not start the signer"
		}
	}

	fun ensureStarted(context: Context) {
		val app = context.applicationContext
		wantRadio = true
		val mine = epoch
		main.post { openRadio(app, mine, 0) }
	}

	fun stop(context: Context) {
		val app = context.applicationContext
		wantRadio = false
		bump()
		releaseRadio()
		app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ARMED, false).putString(ARMED_KEYS, "").apply()
		status = "Remote signer is off. Start a key to open the radio again. The KMS pin stays."
		app.stopService(android.content.Intent(app, RemoteSignerService::class.java))
	}

	fun disarm(context: Context) {
		wantRadio = false
		bump()
		releaseRadio()
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
			.edit().putBoolean(ARMED, false).apply()
		status = "Remote signer is off. Start this signer to open the radio again. The KMS pin stays."
	}

	/** onDestroy of an older service must not close the server a newer start opened. */
	fun releaseIfCurrent(seen: Int) {
		if (wantRadio) return
		if (seen != epoch) return
		releaseRadio()
	}

	fun centralLeft(context: Context) {
		if (!wantRadio) return
		val shown = code.ifBlank {
			context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CODE, "").orEmpty()
		}
		if (shown.isEmpty()) return
		val app = context.applicationContext
		main.post { advertise(app, shown) }
	}

	fun releaseRadio() {
		advertiseCallback?.let { callback -> advertiser?.stopAdvertising(callback) }
		advertiseCallback = null
		advertiser = null
		gatt?.close()
		gatt = null
		running = false
		sessions = 0
	}

	private fun watchRadio(app: Context) {
		if (btWatch != null) return
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(ctx: Context?, intent: Intent?) {
				val state = intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
					?: return
				if (state == BluetoothAdapter.STATE_ON && wantRadio && gatt == null) {
					openRadio(app, epoch, 0)
				}
			}
		}
		val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
		if (Build.VERSION.SDK_INT >= 33) {
			app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
		} else {
			app.registerReceiver(receiver, filter)
		}
		btWatch = receiver
	}

	private fun bump(): Int {
		epoch += 1
		return epoch
	}

	private fun openRadio(app: Context, mine: Int, attempt: Int) {
		if (mine != epoch || !wantRadio) return
		if (running && gatt != null) return
		val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
		if (adapter == null || !adapter.isEnabled) {
			status = "Bluetooth is off. Turn it on and this signer opens."
			onStatusText?.invoke(status)
			running = false
			watchRadio(app)
			return
		}
		loadHistory(app)
		try {
			val boot = ValidatorKeys.bootstrap(app)
			refreshKeys(app)
			pub = boot.first.pubHex
			val shown = savedCode(app)
			code = shown
			gatt?.close()
			gatt = null
			val server = KmsGattServer(
				app,
				{ ValidatorKeys.material(app) },
				onSigned = { row ->
					SignHistory.append(app, row)
					main.post {
						rows.add(0, row)
						while (rows.size > 100) rows.removeAt(rows.lastIndex)
						if (row.result == "signed") lastSignedMs = row.atMs
						onSigned?.invoke(row)
					}
				},
				onStatus = { text ->
					main.post {
						if (mine != epoch) return@post
						status = text
						onStatusText?.invoke(text)
						if (text == "gatt ready") advertise(app, shown)
					}
				},
				onPeers = { count -> main.post { if (mine == epoch) sessions = count } },
			)
			if (!server.start()) {
				server.close()
				if (attempt < 5 && mine == epoch) {
					status = "opening the GATT server"
					main.postDelayed({ openRadio(app, mine, attempt + 1) }, 400)
				} else if (mine == epoch) {
					status = "this phone could not open a GATT server"
					running = false
				}
				return
			}
			if (mine != epoch) {
				server.close()
				return
			}
			gatt = server
			app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ARMED, true).apply()
			running = true
			status = "opening the GATT server"
			onStatusText?.invoke(status)
			watchGranter(app)
		} catch (_: SecurityException) {
			if (mine == epoch) {
				status = "allow Bluetooth advertise"
				running = false
			}
		} catch (_: Exception) {
			if (mine == epoch) {
				status = "could not prepare the signing key"
				running = false
			}
		}
	}

	fun noteBlock(height: Long, lagMs: Long, validator: Boolean, chain: String, addr: String) {
		blockHeight = height
		blockLagMs = lagMs
		nodeValidator = validator
		nodeChain = chain
		consensusAddr = addr
		blockSeenAt = android.os.SystemClock.elapsedRealtime()
	}

	fun setGranter(context: Context, address: String) {
		if (!address.startsWith("terp1")) return
		GranterAccount.pin(context, address)
		if (address == granterAccount) {
			gatt?.publishGranter(address)
			return
		}
		granterAccount = address
		gatt?.publishGranter(address)
	}

	fun refreshGranter(context: Context) {
		val address = GranterAccount.lookup(context)
		if (!address.startsWith("terp1")) return
		val changed = address != granterAccount
		granterAccount = address
		if (changed) gatt?.publishGranter(address)
	}

	private fun watchGranter(app: Context) {
		if (granterWatch) return
		granterWatch = true
		main.post(object : Runnable {
			override fun run() {
				if (!running) {
					granterWatch = false
					return
				}
				refreshGranter(app)
				main.postDelayed(this, 2000)
			}
		})
	}

	fun refreshKeys(context: Context) {
		val app = context.applicationContext
		keys = ValidatorKeys.list(app)
		selected = ValidatorKeys.selectedId(app).orEmpty()
		keys.firstOrNull { it.id == selected }?.let { pub = it.pubHex }
	}

	fun acceptQr(context: Context, payload: String, bindTo: String? = null): ValidatorKey {
		val app = context.applicationContext
		// A second key on a chain would make legacy 0x10 routing ambiguous and
		// stop the key that signs today. Pin that key as started first, so it
		// keeps signing; the new key signs only through keyed 0x12 frames
		// until the user presses Start on it.
		val incumbent = if (bindTo == null && activeKey(app).isBlank()) {
			val chain = runCatching { ValidatorKeys.parseChain(payload) }.getOrNull()
			ValidatorKeys.list(app).filter { it.chainId == chain }.singleOrNull()
		} else {
			null
		}
		val key = ValidatorKeys.acceptQr(app, payload, bindTo)
		if (incumbent != null && key.id != incumbent.id) {
			app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ACTIVE, incumbent.id).apply()
		}
		refreshKeys(context)
		pub = key.pubHex
		status = if (key.chainId.isBlank()) {
			"Opened ${key.name}."
		} else {
			"Terp key set ${key.chainId} is ready. Its history stays with this set."
		}
		return key
	}

	fun selectKey(context: Context, id: String) {
		ValidatorKeys.select(context.applicationContext, id)
		refreshKeys(context)
		status = "Selected ${keys.firstOrNull { it.id == id }?.name ?: id}. New sessions use this key."
	}

	fun renameKey(context: Context, id: String, name: String) {
		ValidatorKeys.rename(context.applicationContext, id, name)
		refreshKeys(context)
		gatt?.publishKeysChanged()
		status = "Renamed to ${name.trim()}. The key itself did not change."
	}

	/** Stops signing first, then wipes this one key set. */
	fun deleteKey(context: Context, id: String) {
		val app = context.applicationContext
		val gone = keys.firstOrNull { it.id == id }
		// Only stop the radio when the key being deleted is the one signing.
		// Deleting an idle key must not take other validators offline.
		val wasSigning = id in armedKeys(app)
		if (wasSigning) stopKey(app, id)
		if (activeKey(app) == id) {
			app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ACTIVE).apply()
		}
		ValidatorKeys.delete(app, id)
		refreshKeys(app)
		val label = "${gone?.name ?: id}${gone?.chainId?.let { if (it.isBlank()) "" else " ($it)" } ?: ""}"
		status = if (armedKeys(app).isEmpty()) "Deleted $label. Signing is off." else "Deleted $label. Other started keys keep signing."
	}

	/** Key the user explicitly started. Opening a page never changes it. */
	fun activeKey(context: Context): String =
		context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ACTIVE, "").orEmpty()

	fun noteSigningKey(id: String) {
		main.post { signingKey = id }
	}

	/** Restore persisted history once per process (after a kill/restart). */
	fun loadHistory(context: Context) {
		if (historyLoaded) return
		historyLoaded = true
		val saved = SignHistory.load(context.applicationContext)
		if (rows.isEmpty()) rows.addAll(saved)
		saved.firstOrNull { it.result == "signed" }?.let { if (it.atMs > lastSignedMs) lastSignedMs = it.atMs }
	}

	/**
	 * Called by the service every 10 s. Reopens a radio that died (GATT gone,
	 * advertise failed) and re-advertises when no laptop is connected, so a
	 * long screen-off stretch cannot leave the signer silently deaf.
	 */
	fun watchdog(context: Context) {
		val app = context.applicationContext
		if (!isArmed(app) || armedKeys(app).isEmpty()) return
		wantRadio = true
		if (!running || gatt == null) {
			openRadio(app, epoch, 0)
			return
		}
		val now = android.os.SystemClock.elapsedRealtime()
		val quiet = System.currentTimeMillis() - lastSignedMs > 60_000
		if (sessions == 0 && quiet && now - lastAdvertiseAt > 60_000 && code.isNotBlank()) {
			advertise(app, code)
		}
	}

	/**
	 * Re-scan of the KMS this phone is already paired to: keep the pin and
	 * any live session, and only re-advertise when nothing is connected so the
	 * console can find the phone again.
	 */
	fun refreshLink(context: Context): String {
		val app = context.applicationContext
		if (!running) return "Already paired with this KMS. Start a key to connect."
		if (sessions > 0) return "Already paired and connected to this KMS."
		if (code.isNotBlank()) advertise(app, code)
		return "Already paired with this KMS. Advertising $code so it reconnects."
	}

	fun recordKeyCheck(row: SignedRow) {
		rows.add(0, row)
		while (rows.size > 100) rows.removeAt(rows.lastIndex)
	}

	private fun savedCode(context: Context): String {
		val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
		val existing = prefs.getString(CODE, null)
		if (!existing.isNullOrBlank()) return existing
		val bytes = ByteArray(4).also { SecureRandom().nextBytes(it) }
		val shown = bytes.joinToString("") { "%02x".format(it) }
		prefs.edit().putString(CODE, shown).apply()
		return shown
	}

	private fun advertise(context: Context, shown: String) {
		val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
			?: BluetoothAdapter.getDefaultAdapter()
		val adv = adapter?.bluetoothLeAdvertiser
		if (adv == null) {
			status = "this phone has no BLE advertiser"
			return
		}
		advertiseCallback?.let { adv.stopAdvertising(it) }
		lastAdvertiseAt = android.os.SystemClock.elapsedRealtime()
		val bytes = hexToBytes(shown)
		val settings = AdvertiseSettings.Builder()
			.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
			.setConnectable(true)
			.setTimeout(0)
			.build()
		val data = AdvertiseData.Builder()
			.setIncludeDeviceName(false)
			.setIncludeTxPowerLevel(false)
			.addManufacturerData(0xFFFF, bytes)
			.build()
		val scanResponse = AdvertiseData.Builder()
			.addServiceUuid(ParcelUuid(KmsGattServer.SERVICE))
			.build()
		val callback = object : AdvertiseCallback() {
			override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
				status = "advertising $shown"
			}

			override fun onStartFailure(errorCode: Int) {
				if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED) return
				status = "advertise failed ($errorCode)"
				running = false
			}
		}
		adv.startAdvertising(settings, data, scanResponse, callback)
		advertiser = adv
		advertiseCallback = callback
	}

	private fun hexToBytes(hex: String): ByteArray {
		val clean = hex.filter { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
		val out = ByteArray(4)
		var i = 0
		while (i < out.size && i * 2 + 1 < clean.length) {
			out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
			i++
		}
		return out
	}
}
