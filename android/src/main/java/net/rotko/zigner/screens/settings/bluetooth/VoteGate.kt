package net.rotko.zigner.screens.settings.bluetooth

/**
 * What a connected laptop may do. The advertisement is not authentication.
 * Keep these results aligned with Admit in crates/kms/signing/bluetooth/gate.go.
 */
internal fun admit(sessionReady: Boolean, statusLane: Boolean, frame: ByteArray): String {
	if (frame.isEmpty()) return "reject"
	val op = frame[0]
	if (statusLane) {
		return if (sessionReady && op == 0x30.toByte()) "status" else "reject"
	}
	return when (op) {
		0x50.toByte() -> "hello"
		0x52.toByte() -> "finish"
		0x54.toByte() -> if (sessionReady) "vote" else "reject"
		0x20.toByte(), 0x22.toByte(), 0x24.toByte() -> if (sessionReady) "import" else "reject"
		0x10.toByte(), 0x30.toByte() -> "reject"
		else -> "reject"
	}
}
