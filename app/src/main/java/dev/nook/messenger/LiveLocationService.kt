package dev.nook.messenger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper

class LiveLocationService : Service() {
    private var locationManager: LocationManager? = null
    private val listener = LocationListener { location -> publish(location) }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val friendName = intent?.getStringExtra(EXTRA_FRIEND_NAME) ?: "your friend"
        createChannel()
        startForeground(NOTIFICATION_ID, notification(friendName))
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            locationManager = manager
            val fineGranted = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val providers = buildList {
                if (fineGranted) add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }.filter { manager.isProviderEnabled(it) }
            providers.forEach { provider ->
                manager.getLastKnownLocation(provider)?.let(::publish)
                manager.requestLocationUpdates(provider, 30_000L, 15f, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            stopSharing()
            stopSelf()
        } catch (_: IllegalArgumentException) {
            stopSharing()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun publish(location: Location) {
        sendBroadcast(Intent(ACTION_LOCATION).setPackage(packageName)
            .putExtra(EXTRA_LATITUDE, location.latitude)
            .putExtra(EXTRA_LONGITUDE, location.longitude)
            .putExtra(EXTRA_ACCURACY, location.accuracy))
    }

    private fun stopSharing() {
        runCatching { locationManager?.removeUpdates(listener) }
        sendBroadcast(Intent(ACTION_STOPPED).setPackage(packageName))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Live location", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while you share your live location"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun notification(friendName: String): Notification {
        val stopIntent = Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 41, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Live location is on")
            .setContentText("Sharing with $friendName · tap Stop to end")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop sharing", stopPending).build())
            .build()
    }

    override fun onDestroy() {
        runCatching { locationManager?.removeUpdates(listener) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_LOCATION = "dev.nook.messenger.LIVE_LOCATION"
        const val ACTION_STOPPED = "dev.nook.messenger.LIVE_LOCATION_STOPPED"
        const val ACTION_STOP = "dev.nook.messenger.STOP_LIVE_LOCATION"
        const val EXTRA_FRIEND_NAME = "friend_name"
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"
        const val NOTIFICATION_ID = 2410
        private const val CHANNEL_ID = "live_location"
    }
}
