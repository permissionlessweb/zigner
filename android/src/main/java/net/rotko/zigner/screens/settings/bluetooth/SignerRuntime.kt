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
	val rows = mutableStateListOf<SignedRow>()

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

	fun startKey(context: Context, id: String) {
		val app = context.applicationContext
		selectKey(app, id)
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
		app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ARMED, false).apply()
		status = "Remote signer is off. Start this signer to open the radio again. The KMS pin stays."
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
					main.post {
						rows.add(0, row)
						while (rows.size > 100) rows.removeAt(rows.lastIndex)
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
		val key = ValidatorKeys.acceptQr(context.applicationContext, payload, bindTo)
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
				status = "advertising $shown. The signer stays up when you leave this screen."
			}

			override fun onStartFailure(errorCode: Int) {
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
