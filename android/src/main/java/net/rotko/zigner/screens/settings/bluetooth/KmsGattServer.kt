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
import java.security.Signature
import java.util.UUID

internal data class SignedRow(
	val height: Long,
	val round: Int,
	val step: Int,
	val latencyMs: Long,
	val at: String,
	val keyId: String = "",
)

/**
 * GATT server the laptop connects to after the pairing code is accepted.
 * A write is one block sign request. The signature is notified back.
 */
internal class KmsGattServer(
	context: Context,
	private val material: () -> Pair<java.security.PrivateKey, ByteArray>,
	private val onSigned: (SignedRow) -> Unit,
	private val onStatus: (String) -> Unit,
	private val onPeers: (Int) -> Unit = {},
) {
	private val app = context.applicationContext
	private val main = Handler(Looper.getMainLooper())
	private var server: BluetoothGattServer? = null
	private var incoming = ByteArray(0)
	private var statusIncoming = ByteArray(0)
	private var pendingStatus = false
	private var sigChar: BluetoothGattCharacteristic? = null
	private var granterChar: BluetoothGattCharacteristic? = null
	private val bound = mutableMapOf<String, String>()
	private val sessions = mutableMapOf<String, NoiseResponder>()
	private val marks = mutableMapOf<String, HighWater>()
	private val clients = linkedSetOf<BluetoothDevice>()
	private var importer: NoiseResponder? = null

	fun start(): Boolean {
		val mgr = app.getSystemService(BluetoothManager::class.java) ?: return false
		val callback = object : BluetoothGattServerCallback() {
			private val peers = mutableSetOf<String>()

			override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
				val id = try {
					device.address
				} catch (_: SecurityException) {
					return
				}
				if (newState == BluetoothProfile.STATE_CONNECTED) {
					peers.add(id)
					clients.add(device)
					bound[id] = ValidatorKeys.selectedId(app).orEmpty()
					SignerRuntime.refreshGranter(app)
				} else {
					peers.remove(id)
					clients.remove(device)
					bound.remove(id)
					sessions.remove(id)
					// Android stops the advertisement while a laptop is connected.
					// Without a new one, the scanner cannot find this phone again.
					SignerRuntime.centralLeft(app)
				}
				onPeers(peers.size)
			}

			override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
				onStatus(if (status == BluetoothGatt.GATT_SUCCESS) "gatt ready" else "gatt service rejected")
			}

			override fun onCharacteristicReadRequest(
				device: BluetoothDevice,
				requestId: Int,
				offset: Int,
				characteristic: BluetoothGattCharacteristic,
			) {
				val body = when {
					characteristic.uuid == PUB && offset == 0 -> forDevice(device).second
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
			) {
				if (characteristic.uuid == STATUS) {
					if (offset == 0) statusIncoming = ByteArray(0)
					statusIncoming = assemble(statusIncoming, offset, value)
					pendingStatus = true
					if (!preparedWrite) {
						handleStatus(device, statusIncoming)
						statusIncoming = ByteArray(0)
						pendingStatus = false
					}
				} else if (characteristic.uuid == SIGN) {
					pendingStatus = false
					if (offset == 0) incoming = ByteArray(0)
					incoming = assemble(incoming, offset, value)
					if (!preparedWrite) {
						reply(device, incoming)
						incoming = ByteArray(0)
					}
				}
				if (responseNeeded) {
					server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
				}
			}

			override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
				if (execute && pendingStatus && statusIncoming.isNotEmpty()) {
					handleStatus(device, statusIncoming)
				} else if (execute && incoming.isNotEmpty()) {
					reply(device, incoming)
				}
				incoming = ByteArray(0)
				statusIncoming = ByteArray(0)
				pendingStatus = false
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
					val phone = importer ?: return null
					val opened = phone.open(frame.copyOfRange(1, frame.size))
					val chainLen = opened[0].toInt() and 0xff
					val chain = opened.copyOfRange(1, 1 + chainLen).decodeToString()
					val secret = opened.copyOfRange(1 + chainLen, 1 + chainLen + 64)
					val pub = secret.copyOfRange(32, 64)
					ValidatorKeys.importSecret(app, chain, secret)
					secret.fill(0)
					opened.fill(0)
					onStatus("Imported the KMS key for $chain")
					byteArrayOf(0x25) + pub
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

	private fun forDevice(device: BluetoothDevice): Pair<java.security.PrivateKey, ByteArray> {
		val id = try {
			bound[device.address]
		} catch (_: SecurityException) {
			null
		}
		return if (id.isNullOrBlank()) material() else ValidatorKeys.materialFor(app, id)
	}

	private fun assemble(current: ByteArray, offset: Int, value: ByteArray): ByteArray {
		val next = ByteArray(offset + value.size)
		current.copyInto(next, 0, 0, minOf(current.size, offset))
		value.copyInto(next, offset)
		return next
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
				"import" -> {
					val packet = importFrame(frame) ?: return
					notify(device, packet)
				}
				else -> onStatus("rejected an unauthenticated frame")
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
		notify(device, byteArrayOf(0x53))
	}

	private fun signSealed(device: BluetoothDevice, id: String, frame: ByteArray) {
		val phone = sessions[id] ?: return
		val opened = phone.open(frame.copyOfRange(1, frame.size))
		val started = android.os.SystemClock.elapsedRealtime()
		val parsed = parse(opened) ?: run {
			onStatus("sign request was not a block frame")
			return
		}
		val keyId = bound[id].orEmpty().ifBlank { "selected" }
		val mark = marks.getOrPut(keyId) {
			HighWater.read(java.io.File(app.filesDir, "validator-keys/$keyId/hwm.txt"))
		}
		when (val decision = mark.decide(parsed.height, parsed.round, parsed.step, parsed.msg)) {
			is Decision.Refuse -> {
				notify(device, byteArrayOf(0x5f))
				onStatus("refused a repeated or lower height")
				return
			}
			is Decision.Replay -> {
				val box = phone.seal(byteArrayOf(0x11) + decision.signature)
				notify(device, byteArrayOf(0x55) + box)
				return
			}
			Decision.Sign -> Unit
		}
		val signer = Signature.getInstance("Ed25519")
		signer.initSign(forDevice(device).first)
		signer.update(parsed.msg)
		val sig = signer.sign()
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

	private data class Parsed(val height: Long, val round: Int, val step: Int, val msg: ByteArray)

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

	private fun parse(frame: ByteArray): Parsed? {
		if (frame.size < 16 || frame[0] != 0x10.toByte()) return null
		val height = readLong(frame, 1)
		val round = readInt(frame, 9)
		val step = frame[13].toInt() and 0xff
		val n = ((frame[14].toInt() and 0xff) shl 8) or (frame[15].toInt() and 0xff)
		if (n <= 0 || 16 + n > frame.size) return null
		return Parsed(height, round, step, frame.copyOfRange(16, 16 + n))
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
		val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
	}
}
