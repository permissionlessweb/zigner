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
import androidx.core.app.NotificationCompat
import net.rotko.zigner.R

/**
 * Keeps the Bluetooth signer alive after the user leaves the screen, and
 * after the activity is closed. Android shows one ongoing notification for
 * this. The signer stops when that service is destroyed.
 */
class RemoteSignerService : Service() {
	private var lastNotify = 0L
	private var seenEpoch = 0

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			SignerRuntime.disarm(this)
			if (Build.VERSION.SDK_INT >= 24) {
				stopForeground(STOP_FOREGROUND_REMOVE)
			} else {
				@Suppress("DEPRECATION")
				stopForeground(true)
			}
			stopSelf()
			return START_NOT_STICKY
		}
		// A process restart must not advertise again. Only Start this signer,
		// or a signer that was still armed, opens the radio.
		if (intent?.action != ACTION_ARM && !SignerRuntime.isArmed(this)) {
			stopSelf()
			return START_NOT_STICKY
		}
		val notification = buildNotification(SignerRuntime.status)
		try {
			if (Build.VERSION.SDK_INT >= 29) {
				startForeground(
					NOTIFICATION_ID,
					notification,
					ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
				)
			} else {
				startForeground(NOTIFICATION_ID, notification)
			}
		} catch (_: Exception) {
			SignerRuntime.status = "allow the remote signer notification"
			stopSelf()
			return START_NOT_STICKY
		}
		seenEpoch = SignerRuntime.currentEpoch()
		SignerRuntime.onSigned = { row -> maybeUpdate(row) }
		SignerRuntime.onStatusText = { text ->
			getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
		}
		SignerRuntime.ensureStarted(this)
		return START_NOT_STICKY
	}

	override fun onTaskRemoved(rootIntent: Intent?) {
		// Leave the service running. Closing the activity is not a stop.
	}

	override fun onDestroy() {
		SignerRuntime.onSigned = null
		SignerRuntime.onStatusText = null
		SignerRuntime.releaseIfCurrent(seenEpoch)
		super.onDestroy()
	}

	private fun maybeUpdate(row: SignedRow) {
		val now = android.os.SystemClock.elapsedRealtime()
		if (now - lastNotify < 2000L) return
		lastNotify = now
		val text = if (row.height == 0L) {
			"Key check · ${row.latencyMs} ms"
		} else {
			"Height ${row.height} · ${row.latencyMs} ms"
		}
		val manager = getSystemService(NotificationManager::class.java)
		manager?.notify(NOTIFICATION_ID, buildNotification(text))
	}

	private fun buildNotification(text: String): Notification {
		ensureChannel()
		val open = PendingIntent.getActivity(
			this,
			0,
			packageManager.getLaunchIntentForPackage(packageName),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		val stop = PendingIntent.getService(
			this,
			1,
			Intent(this, RemoteSignerService::class.java).setAction(ACTION_STOP),
			PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
		)
		return NotificationCompat.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.mipmap.ic_launcher)
			.setContentTitle("Remote signer")
			.setContentText(text)
			.setContentIntent(open)
			.setOngoing(true)
			.setOnlyAlertOnce(true)
			.setCategory(Notification.CATEGORY_SERVICE)
			.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
			.build()
	}

	private fun ensureChannel() {
		if (Build.VERSION.SDK_INT < 26) return
		val manager = getSystemService(NotificationManager::class.java) ?: return
		val channel = NotificationChannel(
			CHANNEL_ID,
			"Remote signer",
			NotificationManager.IMPORTANCE_LOW,
		)
		channel.description = "Keeps Bluetooth block signing running."
		manager.createNotificationChannel(channel)
	}

	companion object {
		private const val CHANNEL_ID = "remote_signer"
		private const val NOTIFICATION_ID = 70
		private const val ACTION_STOP = "net.rotko.zigner.REMOTE_SIGNER_STOP"
		private const val ACTION_ARM = "net.rotko.zigner.REMOTE_SIGNER_ARM"

		fun start(context: Context) {
			val intent = Intent(context, RemoteSignerService::class.java).setAction(ACTION_ARM)
			androidx.core.content.ContextCompat.startForegroundService(context, intent)
		}
	}
}
