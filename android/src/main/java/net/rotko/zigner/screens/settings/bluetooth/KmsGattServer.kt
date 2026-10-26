package net.rotko.zigner.screens.settings.bluetooth

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.security.PrivateKey
import java.util.UUID

internal data class SignedRow(
	val height: Long,
	val round: Int,
	val step: Int,
	val latencyMs: Long,
	val at: String,
	val keyId: String = "",
	/** "signed" or "refused". */
	val result: String = "signed",
	/** Wall-clock ms; drives liveness. */
	val atMs: Long = System.currentTimeMillis(),
)

/**
 * GATT server the laptop connects to after the pairing code is accepted.
 * A write is one block sign request. The signature is notified back.
 */
internal class KmsGattServer(
	context: Context,
	private val material: () -> Pair<Ed25519Key, ByteArray>,
	private val onSigned: (SignedRow) -> Unit,
	private val onStatus: (String) -> Unit,
	private val onPeers: (Int) -> Unit = {},
) {
	private val app = context.applicationContext
	private val main = Handler(Looper.getMainLooper())
	private var server: BluetoothGattServer? = null
	/**
	 * Write assembly is per central: several KMS hosts (or a KMS and a
	 * scanner restart) may be connected at once, and a long write from one
	 * must never be spliced into another's frame. Binder callbacks can run
	 * on different threads, so every callback that touches this state, the
	 * sessions or the marks holds [lock].
	 */
	private class Assembly {
		var incoming = ByteArray(0)
		var statusIncoming = ByteArray(0)
		var pendingStatus = false
	}
	private val lock = Any()
	private val assembly = mutableMapOf<String, Assembly>()
	private var sigChar: BluetoothGattCharacteristic? = null
	private var keysChar: BluetoothGattCharacteristic? = null
	private var granterChar: BluetoothGattCharacteristic? = null
	private val sessions = mutableMapOf<String, NoiseResponder>()
	private val marks = mutableMapOf<String, HighWater>()
	private val clients = linkedSetOf<BluetoothDevice>()
	private var importer: NoiseResponder? = null

	fun start(): Boolean {
		val mgr = app.getSystemService(BluetoothManager::class.java) ?: return false
		val callback = object : BluetoothGattServerCallback() {
			private val peers = mutableSetOf<String>()

			override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
				var joined = false
				var count = 0
				synchronized(lock) {
				val id = try {
					device.address
				} catch (_: SecurityException) {
					return
				}
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					peers.add(id)
					clients.add(device)
					joined = true
				} else {
					peers.remove(id)
					clients.remove(device)
					sessions.remove(id)
					assembly.remove(id)
					// Android stops the advertisement while a laptop is connected.
					// Without a new one, the scanner cannot find this phone again.
				}
				count = peers.size
				}
				// Outside the lock: the granter lookup blocks on storage, and holding the
				// GATT lock through it stalls every read/notify-subscribe from the KMS.
				if (joined) Thread { SignerRuntime.refreshGranter(app) }.start()
				else SignerRuntime.centralLeft(app)
				onPeers(count)
			}

			override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
				onStatus(if (status == BluetoothGatt.GATT_SUCCESS) "gatt ready" else "gatt service rejected")
			}

			override fun onCharacteristicReadRequest(
				device: BluetoothDevice,
				requestId: Int,
				offset: Int,
				characteristic: BluetoothGattCharacteristic,
			): Unit = synchronized(lock) {
				val body = when {
					characteristic.uuid == PUB && offset == 0 -> legacyPub()
					characteristic.uuid == KEYS -> encodeKeyList(ValidatorKeys.list(app)).let {
						if (offset >= it.size) ByteArray(0) else it.copyOfRange(offset, it.size)
					}
					characteristic.uuid == GRANTER && offset == 0 && sessionReady(device) -> granterBytes()
					else -> ByteArray(0)
				}
				server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, body)
			}

			override fun onCharacteristicWriteRequest(
				device: BluetoothDevice,
				requestId: Int,
				characteristic: BluetoothGattCharacteristic,
				preparedWrite: Boolean,
				responseNeeded: Boolean,
				offset: Int,
				value: ByteArray,
			): Unit = synchronized(lock) {
				val a = assemblyFor(device)
				if (a == null) {
					// unknown address: nothing to key the assembly on
				} else if (characteristic.uuid == STATUS) {
					if (offset == 0) a.statusIncoming = ByteArray(0)
					a.statusIncoming = assemble(a.statusIncoming, offset, value)
					a.pendingStatus = true
					if (!preparedWrite) {
						handleStatus(device, a.statusIncoming)
						a.statusIncoming = ByteArray(0)
						a.pendingStatus = false
					}
				} else if (characteristic.uuid == SIGN) {
					a.pendingStatus = false
					if (offset == 0) a.incoming = ByteArray(0)
					a.incoming = assemble(a.incoming, offset, value)
					if (!preparedWrite) {
						reply(device, a.incoming)
						a.incoming = ByteArray(0)
					}
				}
				if (responseNeeded) {
					server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
				}
			}

			override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean): Unit = synchronized(lock) {
				val a = assemblyFor(device)
				if (a != null) {
					if (execute && a.pendingStatus && a.statusIncoming.isNotEmpty()) {
						handleStatus(device, a.statusIncoming)
					} else if (execute && a.incoming.isNotEmpty()) {
						reply(device, a.incoming)
					}
					a.incoming = ByteArray(0)
					a.statusIncoming = ByteArray(0)
					a.pendingStatus = false
				}
				server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
			}

			override fun onDescriptorWriteRequest(
				device: BluetoothDevice,
				requestId: Int,
				descriptor: BluetoothGattDescriptor,
				preparedWrite: Boolean,
				responseNeeded: Boolean,
				offset: Int,
				value: ByteArray,
			) {
				if (responseNeeded) {
					server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
				}
			}
		}
		val opened = mgr.openGattServer(app, callback) ?: return false
		server = opened
		val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
		val pub = BluetoothGattCharacteristic(
			PUB,
			BluetoothGattCharacteristic.PROPERTY_READ,
			BluetoothGattCharacteristic.PERMISSION_READ,
		)
		val sign = BluetoothGattCharacteristic(
			SIGN,
			BluetoothGattCharacteristic.PROPERTY_WRITE,
			BluetoothGattCharacteristic.PERMISSION_WRITE,
		)
		val sig = BluetoothGattCharacteristic(
			SIG,
			BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_READ,
			BluetoothGattCharacteristic.PERMISSION_READ,
		)
		sig.addDescriptor(
			BluetoothGattDescriptor(
				CCCD,
				BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
			),
		)
		val granter = BluetoothGattCharacteristic(
			GRANTER,
			BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
			BluetoothGattCharacteristic.PERMISSION_READ,
		)
		granter.addDescriptor(
			BluetoothGattDescriptor(
				CCCD,
				BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
			),
		)
		val status = BluetoothGattCharacteristic(
			STATUS,
			BluetoothGattCharacteristic.PROPERTY_WRITE,
			BluetoothGattCharacteristic.PERMISSION_WRITE,
		)
		service.addCharacteristic(pub)
		service.addCharacteristic(sign)
		service.addCharacteristic(sig)
		service.addCharacteristic(granter)
		service.addCharacteristic(status)
		// Notify carries only a one-byte 'N' change hint; the scanner re-reads
		// the listing, which can be longer than one notification.
		val keysCh = BluetoothGattCharacteristic(
			KEYS,
			BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
			BluetoothGattCharacteristic.PERMISSION_READ,
		)
		keysCh.addDescriptor(
			BluetoothGattDescriptor(
				CCCD,
				BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
			),
		)
		service.addCharacteristic(keysCh)
		keysChar = keysCh
		sigChar = sig
		granterChar = granter
		if (!opened.addService(service)) {
			main.post { retryAdd(opened, service, 1) }
		}
		return true
	}

	private fun retryAdd(opened: BluetoothGattServer, service: BluetoothGattService, attempt: Int) {
		if (server == null) return
		if (opened.addService(service)) return
		if (attempt >= 12) {
			onStatus("this phone could not open a GATT server")
			close()
			return
		}
		main.postDelayed({ retryAdd(opened, service, attempt + 1) }, 400)
	}

	fun publishGranter(address: String) {
		if (!address.startsWith("terp1")) return
		val value = address.toByteArray(Charsets.UTF_8)
		val current = server ?: return
		val characteristic = granterChar ?: return
		for (device in clients.toList()) {
			notifyValue(current, device, characteristic, value)
		}
	}

	/** Tells connected scanners the key listing changed (e.g. a rename) so they re-read it. */
	fun publishKeysChanged() {
		val current = server ?: return
		val characteristic = keysChar ?: return
		for (device in clients.toList()) {
			notifyValue(current, device, characteristic, byteArrayOf(NAME_TRAILER))
		}
	}

	/** Sealed 0x56 rename from the pinned KMS. Replies 0x57 keyId8, or a coded refusal. */
	private fun renameSealed(device: BluetoothDevice, id: String, frame: ByteArray) {
		val phone = sessions[id] ?: run {
			notify(device, refusal(REFUSE_NO_SESSION))
			return
		}
		val opened = try { phone.open(frame.copyOfRange(1, frame.size)) } catch (_: Exception) {
			notify(device, refusal(REFUSE_BAD_FRAME, "rename did not open"))
			return
		}
		val r = parseRenameFrame(opened) ?: run {
			notify(device, refusal(REFUSE_BAD_FRAME, "bad rename frame"))
			return
		}
		if (!ValidatorKeys.renameByKeyId(app, r.keyIdHex, r.name)) {
			notify(device, refusal(REFUSE_UNKNOWN_KEY, r.keyIdHex))
			return
		}
		val raw = ByteArray(8) { r.keyIdHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
		notify(device, byteArrayOf(WIRE_RENAME_ACK) + raw)
		main.post { SignerRuntime.refreshKeys(app) }
		onStatus("KMS renamed a key to ${r.name}")
		publishKeysChanged()
	}

	private fun granterBytes(): ByteArray {
		val address = SignerRuntime.granterAccount.ifBlank { GranterAccount.lookup(app) }
		if (!address.startsWith("terp1")) return ByteArray(0)
		SignerRuntime.granterAccount = address
		return address.toByteArray(Charsets.UTF_8)
	}

	private fun notifyValue(
		gatt: BluetoothGattServer,
		device: BluetoothDevice,
		characteristic: BluetoothGattCharacteristic,
		value: ByteArray,
	) {
		if (Build.VERSION.SDK_INT >= 33) {
			gatt.notifyCharacteristicChanged(device, characteristic, false, value)
		} else {
			@Suppress("DEPRECATION")
			characteristic.value = value
			@Suppress("DEPRECATION")
			gatt.notifyCharacteristicChanged(device, characteristic, false)
		}
	}

	fun close() {
		val current = server ?: return
		server = null
		for (device in clients.toList()) {
			try {
				current.cancelConnection(device)
			} catch (_: SecurityException) {
			}
		}
		clients.clear()
		assembly.clear()
		try {
			current.clearServices()
		} catch (_: RuntimeException) {
		}
		current.close()
	}

	private fun importFrame(frame: ByteArray): ByteArray? {
		return try {
			when (frame[0]) {
				0x20.toByte() -> {
					val phone = NoiseResponder(ValidatorKeys.noiseSeed(app))
					phone.readMessage1(frame.copyOfRange(1, frame.size))
					val eph = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
					val msg2 = phone.message2(eph)
					eph.fill(0)
					importer = phone
					onStatus("Noise import started")
					byteArrayOf(0x21) + msg2
				}
				0x22.toByte() -> {
					val phone = importer ?: return null
					phone.readMessage3(frame.copyOfRange(1, frame.size))
					val pinFile = java.io.File(app.filesDir, "zcinti-kms.peer")
					val remote = phone.remoteStatic()
					if (!pinFile.exists() || !pinFile.readBytes().contentEquals(remote)) {
						onStatus("import refused: this KMS is not pinned")
						return null
					}
					byteArrayOf(0x23)
				}
				0x24.toByte() -> {
					// A key without a height floor could double-sign: refused.
					onStatus("import refused: use Move validator (key needs a height floor)")
					null
				}
				0x26.toByte() -> {
					// Key moves are phone-to-phone over QR only; key bytes never cross the radio.
					importer = null
					onStatus("move refused: keys move phone-to-phone by QR (chain tab > Move out)")
					null
				}
				else -> null
			}
		} catch (_: Exception) {
			onStatus("Noise import failed")
			null
		}
	}

	private fun notify(device: BluetoothDevice, packet: ByteArray) {
		val characteristic = sigChar ?: return
		val gatt = server ?: return
		if (Build.VERSION.SDK_INT >= 33) {
			gatt.notifyCharacteristicChanged(device, characteristic, false, packet)
		} else {
			@Suppress("DEPRECATION")
			characteristic.value = packet
			@Suppress("DEPRECATION")
			gatt.notifyCharacteristicChanged(device, characteristic, false)
		}
	}

	/**
	 * Single-key mode public key: the started key, else the selected one.
	 * No connection is bound to a key; each request names or routes its own.
	 */
	private fun legacyPub(): ByteArray {
		val active = SignerRuntime.activeKey(app)
		val key = ValidatorKeys.list(app).firstOrNull { it.id == active }
		val (k, pub) = if (key != null) ValidatorKeys.materialFor(app, key.id) else material()
		k.wipe()
		return pub
	}

	private fun assemble(current: ByteArray, offset: Int, value: ByteArray): ByteArray {
		val next = ByteArray(offset + value.size)
		current.copyInto(next, 0, 0, minOf(current.size, offset))
		value.copyInto(next, offset)
		return next
	}

	private fun assemblyFor(device: BluetoothDevice): Assembly? {
		val id = deviceId(device) ?: return null
		return assembly.getOrPut(id) { Assembly() }
	}

	private fun deviceId(device: BluetoothDevice): String? =
		try {
			device.address
		} catch (_: SecurityException) {
			null
		}

	private fun sessionReady(device: BluetoothDevice): Boolean {
		val id = deviceId(device) ?: return false
		return sessions[id]?.ready == true
	}

	private fun handleStatus(device: BluetoothDevice, frame: ByteArray) {
		if (admit(sessionReady(device), true, frame) != "status") return
		noteStatus(frame)
	}

	private fun reply(device: BluetoothDevice, frame: ByteArray) {
		val id = deviceId(device) ?: return
		try {
			when (admit(sessionReady(device), false, frame)) {
				"hello" -> {
					val phone = NoiseResponder(ValidatorKeys.noiseSeed(app))
					phone.readMessage1(frame.copyOfRange(1, frame.size))
					val eph = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
					val msg2 = phone.message2(eph)
					eph.fill(0)
					sessions[id] = phone
					notify(device, byteArrayOf(0x51) + msg2)
				}
				"finish" -> finishSession(device, id, frame)
				"vote" -> signSealed(device, id, frame)
				"rename" -> renameSealed(device, id, frame)
				"import" -> {
					val packet = importFrame(frame) ?: return
					notify(device, packet)
				}
				else -> {
					if (frame[0] == 0x54.toByte() || frame[0] == 0x56.toByte()) notify(device, refusal(REFUSE_NO_SESSION))
					onStatus("rejected an unauthenticated frame")
				}
			}
		} catch (_: Exception) {
			sessions.remove(id)
			onStatus("session failed")
		}
	}

	private fun finishSession(device: BluetoothDevice, id: String, frame: ByteArray) {
		val phone = sessions[id] ?: return
		phone.readMessage3(frame.copyOfRange(1, frame.size))
		val remote = phone.remoteStatic()
		val pinFile = java.io.File(app.filesDir, "zcinti-kms.peer")
		if (!pinFile.exists() || pinFile.length() != 32L || !pinFile.readBytes().contentEquals(remote)) {
			sessions.remove(id)
			SignerRuntime.pendingKms = KeyExport.fingerprint(remote)
			onStatus("confirm this KMS before it can sign: ${SignerRuntime.pendingKms}")
			notify(device, byteArrayOf(0x5e))
			return
		}
		// The KMS only completes a session with the phone the console pinned.
		PhoneGate.releaseImported(app.filesDir)
		notify(device, byteArrayOf(0x53))
	}

	private fun signSealed(device: BluetoothDevice, id: String, frame: ByteArray) {
		val phone = sessions[id] ?: run {
			notify(device, refusal(REFUSE_NO_SESSION))
			return
		}
		val opened = phone.open(frame.copyOfRange(1, frame.size))
		val started = android.os.SystemClock.elapsedRealtime()
		val parsed = parseSignFrame(opened) ?: run {
			notify(device, refusal(REFUSE_BAD_FRAME, "not a 0x10/0x12 sign frame"))
			onStatus("sign request was not a block frame")
			return
		}
		val sb = parseSignBytes(parsed.msg)
		val problem = when {
			sb == null -> "sign bytes are not a vote, proposal or vote extension"
			else -> signBytesProblem(sb, parsed.height, parsed.round, parsed.step)
		}
		if (sb == null || problem != null) {
			notify(device, refusal(REFUSE_BAD_FRAME, problem ?: "bad sign bytes"))
			onStatus("refused: $problem")
			return
		}
		val keys = ValidatorKeys.list(app)
		val route = parsed.keyId?.let { routeKeyed(keys, SignerRuntime.armedKeys(app), sb.chainId, it) }
			?: routeKey(keys, SignerRuntime.activeKey(app), sb.chainId)
		if (route is Route.Refuse) {
			notify(device, refusal(route.code, route.why))
			onStatus("refused ${kindName(sb.kind)} at ${sb.height}: ${route.why}")
			SignerRuntime.noteSigningKey("")
			return
		}
		val key = (route as Route.Use).key
		val keyId = key.id
		PhoneGate.check(app.filesDir, keyId, key.pubHex, parsed.height)?.let { why ->
			notify(device, refusal(REFUSE_LOCKED, why))
			onStatus("refused ${kindName(sb.kind)} at ${parsed.height}: $why")
			return
		}
		val mark = marks.getOrPut(keyId) {
			HighWater.read(java.io.File(app.filesDir, "validator-keys/$keyId/hwm.txt"))
		}
		when (val decision = mark.decide(parsed.height, parsed.round, parsed.step, parsed.msg)) {
			is Decision.Refuse -> {
				notify(device, staleRefusal(mark.height, mark.round, mark.step))
				onStatus("refused a repeated or lower height for ${key.name}")
				onSigned(SignedRow(parsed.height, parsed.round, parsed.step, 0,
					java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()), keyId, "refused"))
				return
			}
			is Decision.Replay -> {
				val box = phone.seal(byteArrayOf(0x11) + decision.signature)
				notify(device, byteArrayOf(0x55) + box)
				return
			}
			Decision.Sign -> Unit
		}
		val signer = ValidatorKeys.materialFor(app, keyId).first
		val sig = try { signer.sign(parsed.msg) } finally { signer.wipe() }
		if (sig.size != 64) {
			onStatus("signature length ${sig.size}")
			return
		}
		if (isVoteExtension(parsed.msg)) {
			mark.note(parsed.height, parsed.round, parsed.step)
		} else {
			mark.commit(parsed.height, parsed.round, parsed.step, parsed.msg, sig)
		}
		mark.write(java.io.File(app.filesDir, "validator-keys/$keyId/hwm.txt"))
		val box = phone.seal(byteArrayOf(0x11) + sig)
		notify(device, byteArrayOf(0x55) + box)
		SignerRuntime.noteSigningKey(keyId)
		val latency = android.os.SystemClock.elapsedRealtime() - started
		onSigned(
			SignedRow(
				height = parsed.height,
				round = parsed.round,
				step = parsed.step,
				latencyMs = latency,
				at = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
				keyId = keyId,
			),
		)
	}


	private fun noteStatus(frame: ByteArray) {
		if (frame.size < 19) return
		var at = 1
		val height = readLong(frame, at)
		at += 8
		val lag = readLong(frame, at)
		at += 8
		val validator = frame[at] == 1.toByte()
		at += 1
		if (at >= frame.size) return
		val chainLen = frame[at].toInt() and 0xff
		at += 1
		if (at + chainLen > frame.size) return
		val chain = frame.copyOfRange(at, at + chainLen).decodeToString()
		at += chainLen
		if (at >= frame.size) return
		val addrLen = frame[at].toInt() and 0xff
		at += 1
		if (at + addrLen > frame.size) return
		val addr = frame.copyOfRange(at, at + addrLen).decodeToString()
		SignerRuntime.noteBlock(height, lag, validator, chain, addr)
	}

	private fun readLong(b: ByteArray, at: Int): Long {
		var v = 0L
		for (i in 0 until 8) v = (v shl 8) or (b[at + i].toLong() and 0xff)
		return v
	}

	private fun readInt(b: ByteArray, at: Int): Int {
		var v = 0
		for (i in 0 until 4) v = (v shl 8) or (b[at + i].toInt() and 0xff)
		return v
	}

	companion object {
		val SERVICE: UUID = UUID.fromString("e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000001")
		val PUB: UUID = UUID.fromString("e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000002")
		val SIGN: UUID = UUID.fromString("e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000003")
		val SIG: UUID = UUID.fromString("e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000004")
		val GRANTER: UUID = UUID.fromString("e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000005")
		val STATUS: UUID = UUID.fromString("e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000006")
		val KEYS: UUID = UUID.fromString("e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000007")
		val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
	}
}
