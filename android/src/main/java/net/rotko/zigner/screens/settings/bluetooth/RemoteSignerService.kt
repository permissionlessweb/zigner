package net.rotko.zigner.screens.settings.bluetooth

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import net.rotko.zigner.R

/**
 * Owns the Bluetooth signer for the life of the process, the same way
 * bitchat's MeshForegroundService owns its mesh: a connectedDevice
 * foreground service with one ongoing notification, START_STICKY so the
 * system restarts it after a kill, and a partial wake lock while armed so
 * the CPU can answer a vote with the screen off. Closing or swiping the
 * app away does not stop signing; only Stop (screen or notification) does.
 *
 * SignerRuntime is a process singleton. The screen reads it and calls
 * startKey/stopKey, which map to start/stop of this service.
 */
class RemoteSignerService : Service() {
	private var lastNotify = 0L
	private var seenEpoch = 0
	private var lastHeight = 0L
	private var wake: PowerManager.WakeLock? = null
	private val tick = android.os.Handler(android.os.Looper.getMainLooper())
	private val tickRun = object : Runnable {
		override fun run() {
			SignerRuntime.watchdog(this@RemoteSignerService)
			post(liveText())
			tick.postDelayed(this, 10_000)
		}
	}

	private fun liveText(): String {
		val label = SignHistory.liveLabel(true, SignerRuntime.lastSignedMs, System.currentTimeMillis())
		return if (lastHeight > 0) "$label · #$lastHeight" else label
	}

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			SignerRuntime.stop(this)
			releaseWake()
			stopForegroundCompat()
			stopSelf()
			return START_NOT_STICKY
		}
		// A sticky restart (null intent), boot or update resume must only
		// reopen the radio for a signer the user left started.
		if (!shouldRun(intent?.action, SignerRuntime.isArmed(this), SignerRuntime.armedKeys(this))) {
			stopSelf()
			return START_NOT_STICKY
		}
		SignerRuntime.refreshKeys(this)
		try {
			val n = buildNotification(SignerRuntime.status)
			if (Build.VERSION.SDK_INT >= 29) {
				startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
			} else {
				startForeground(NOTIFICATION_ID, n)
			}
		} catch (_: Exception) {
			SignerRuntime.status = "allow the remote signer notification"
			stopSelf()
			return START_NOT_STICKY
		}
		holdWake()
		seenEpoch = SignerRuntime.currentEpoch()
		SignerRuntime.onSigned = { row -> maybeUpdate(row) }
		SignerRuntime.onStatusText = { text -> post(text) }
		SignerRuntime.loadHistory(this)
		SignerRuntime.ensureStarted(this)
		tick.removeCallbacks(tickRun)
		tick.postDelayed(tickRun, 10_000)
		return START_STICKY
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		// Swiping the app away is not a stop. The foreground service keeps the
		// process; nothing to do but keep the notification current.
		post(SignerRuntime.status)
	}

	override fun onDestroy() {
		tick.removeCallbacks(tickRun)
		SignerRuntime.onSigned = null
		SignerRuntime.onStatusText = null
		SignerRuntime.releaseIfCurrent(seenEpoch)
		releaseWake()
		super.onDestroy()
	}

	private fun holdWake() {
		if (wake?.isHeld == true) return
		val pm = getSystemService(PowerManager::class.java) ?: return
		// Partial only: the CPU stays up to answer BLE writes, the screen does not.
		wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zigner:signer").apply {
			setReferenceCounted(false)
			@Suppress("WakelockTimeout")
			acquire()
		}
	}

	private fun releaseWake() {
		wake?.let { if (it.isHeld) it.release() }
		wake = null
	}

	private fun stopForegroundCompat() {
		if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE)
		else @Suppress("DEPRECATION") stopForeground(true)
	}

	private fun maybeUpdate(row: SignedRow) {
		if (row.height > 0 && row.result == "signed") lastHeight = row.height
		val now = android.os.SystemClock.elapsedRealtime()
		if (now - lastNotify < 2000L) return
		lastNotify = now
		post(if (row.result == "signed") "Signing · #${row.height} · ${row.latencyMs} ms" else "Refused at ${row.height}")
	}

	private fun post(text: String) {
		getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
	}

	private fun title(): String {
		val keys = SignerRuntime.keys
		val armed = SignerRuntime.armedKeys(this)
		val active = keys.firstOrNull { it.id == SignerRuntime.activeKey(this) } ?: keys.firstOrNull { it.id in armed }
		val name = active?.name?.ifBlank { null } ?: active?.slot ?: "Signer"
		return if (armed.size > 1) "$name +${armed.size - 1}" else name
	}

	private fun buildNotification(text: String): Notification {
		ensureChannel()
		val open = PendingIntent.getActivity(
			this, 0, packageManager.getLaunchIntentForPackage(packageName),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val stop = PendingIntent.getService(
			this, 1, Intent(this, RemoteSignerService::class.java).setAction(ACTION_STOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val line = if (lastHeight > 0) "#$lastHeight · $text" else text
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
			.setContentTitle(title())
			.setContentText(line)
			.setContentIntent(open)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setShowWhen(false)
			.setPriority(NotificationCompat.PRIORITY_LOW)
			.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
			.setCategory(Notification.CATEGORY_SERVICE)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
			.build()
	}

	private fun ensureChannel() {
		if (Build.VERSION.SDK_INT < 26) return
		val manager = getSystemService(NotificationManager::class.java) ?: return
		val channel = NotificationChannel(CHANNEL_ID, "Remote signer", NotificationManager.IMPORTANCE_LOW)
		channel.description = "Keeps Bluetooth block signing running."
		channel.setShowBadge(false)
		manager.createNotificationChannel(channel)
	}

	companion object {
		private const val CHANNEL_ID = "remote_signer"
		private const val NOTIFICATION_ID = 70
		internal const val ACTION_STOP = "net.rotko.zigner.REMOTE_SIGNER_STOP"
		internal const val ACTION_ARM = "net.rotko.zigner.REMOTE_SIGNER_ARM"
		internal const val ACTION_RESUME = "net.rotko.zigner.REMOTE_SIGNER_RESUME"

		/** Arm is an explicit Start. Anything else (sticky null, resume) needs a signer left started. */
		internal fun shouldRun(action: String?, armed: Boolean, keys: Set<String>): Boolean =
			action == ACTION_ARM || (armed && keys.isNotEmpty())

		fun start(context: Context) {
			val intent = Intent(context, RemoteSignerService::class.java).setAction(ACTION_ARM)
			androidx.core.content.ContextCompat.startForegroundService(context, intent)
		}

		fun resume(context: Context) {
			val intent = Intent(context, RemoteSignerService::class.java).setAction(ACTION_RESUME)
			androidx.core.content.ContextCompat.startForegroundService(context, intent)
		}
	}
}
