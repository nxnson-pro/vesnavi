package com.nxnson.vesnavi

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that keeps the process (GPS + scooter link) alive with the screen off —
 * the Android counterpart of the iOS background modes. It does no work itself: AppModel and
 * VespaBLE keep running in the app process while this service is up.
 */
class ScooterService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as VesNaviApp).model.stopFromNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        val types = foregroundTypes()
        if (types == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        val ok = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(intent?.getStringExtra(EXTRA_TEXT)), types) }
        if (ok.isFailure) stopSelf()
        return START_NOT_STICKY
    }

    /** Only the types whose permissions are granted, or startForeground throws. */
    private fun foregroundTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 1
        fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        var types = 0
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        val bluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) granted(Manifest.permission.BLUETOOTH_CONNECT) else true
        if (bluetooth) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        return types
    }

    private fun notification(text: String?): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, L("Dẫn đường & kết nối xe", "Navigation & scooter link"), NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, ScooterService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VesNavi")
            .setContentText(text ?: L("Đang chạy nền cho xe", "Running for the scooter"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, L("Dừng", "Stop"), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "vesnavi.background"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.nxnson.vesnavi.STOP"
        private const val EXTRA_TEXT = "text"

        /** Starting a foreground service from the background is refused on Android 12+; that's fine — it's already running then. */
        fun start(context: Context, text: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ScooterService::class.java).putExtra(EXTRA_TEXT, text))
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScooterService::class.java))
        }
    }
}
