package net.rotko.zigner.screens.settings.bluetooth

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * After reboot (delivered only after the first unlock, since the app is not
 * direct-boot aware) or after an app update, resume a signer the user had
 * started. Never arms a signer that was stopped. Mirrors bitchat's
 * BootCompletedReceiver, gated on the armed flag instead of a setting.
 */
class SignerBootReceiver : BroadcastReceiver() {
	override fun onReceive(context: Context, intent: Intent) {
		if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
		val app = context.applicationContext
		val keys = SignerRuntime.armedKeys(app)
		if (!SignerRuntime.isArmed(app) || keys.isEmpty()) return
		// The at-rest key must open now (credential storage unlocked); otherwise
		// leave it for the user to Start by hand.
		// Every armed key must still be a stored key and open now; materialFor
		// alone could fall back to the bootstrap key for a deleted id.
		val ok = try {
			val stored = ValidatorKeys.list(app).map { it.id }.toSet()
			resumeKeysOk(keys, stored) && keys.all { ValidatorKeys.materialFor(app, it).first.wipe(); true }
		} catch (_: Exception) {
			false
		}
		if (!ok) {
			SignerRuntime.status = "Unlock and tap Start key: the key store is not open yet."
			return
		}
		try {
			RemoteSignerService.resume(app)
		} catch (_: Exception) {
			// Background start refused; the user taps Start key.
		}
	}
}

internal fun resumeKeysOk(armed: Set<String>, stored: Set<String>): Boolean =
	armed.isNotEmpty() && stored.containsAll(armed)

internal object BatteryExemption {
	fun isExempt(context: Context): Boolean =
		context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

	@SuppressLint("BatteryLife")
	fun request(context: Context) {
		val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
			.setData(Uri.parse("package:${context.packageName}"))
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
		try {
			context.startActivity(direct)
		} catch (_: Exception) {
			context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
		}
	}
}
