package io.github.deevroman.gpsfilter

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground mock-location service.
 *
 * It obtains non-mock network locations and replaces the GPS provider with the latest point that
 * is outside every private bbox. A device owner must select GPS Filter as its mock-location app.
 */
class GpsFilterService : Service(), LocationListener {
    private lateinit var locationManager: LocationManager
    private var testProviderInstalled = false
    private var lastSafePoint: FilterStorage.SafePoint? = null
    private var stopped = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopFiltering()
            ACTION_START -> startFiltering()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopFiltering(removeNotification = true)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun startFiltering() {
        stopped = false
        if (!hasLocationPermission()) {
            fail("Нет доступа к геопозиции. Разрешите точную геолокацию в приложении.")
            return
        }
        startAsForegroundService()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lastSafePoint = FilterStorage.lastSafePoint(this)
        try {
            locationManager.addTestProvider(
                LocationManager.GPS_PROVIDER,
                false,
                true,
                false,
                false,
                true,
                true,
                true,
                Criteria.POWER_HIGH,
                Criteria.ACCURACY_FINE,
            )
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            testProviderInstalled = true
        } catch (exception: SecurityException) {
            fail("Выберите GPS Filter в «Параметры разработчика → Приложение для фиктивных геоданных».")
            return
        } catch (exception: IllegalArgumentException) {
            fail("Не удалось создать mock GPS provider: ${exception.message}")
            return
        }

        try {
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                UPDATE_INTERVAL_MILLIS,
                UPDATE_DISTANCE_METERS,
                this,
                Looper.getMainLooper(),
            )
            FilterStorage.setFilteringEnabled(this, true)
            FilterStorage.appendEvent(
                this,
                "${eventTime()}  Mock GPS включён; источник реальных данных: сеть",
            )
            locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)?.let(::processSourceLocation)
        } catch (exception: SecurityException) {
            fail("Не удалось получать координаты: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            fail("Источник сетевой геолокации недоступен на устройстве.")
        }
    }

    override fun onLocationChanged(location: Location) {
        if (!locationIsMock(location)) processSourceLocation(location)
    }

    private fun processSourceLocation(source: Location) {
        val incomingPoint = FilterStorage.IncomingPoint(
            latitude = source.latitude,
            longitude = source.longitude,
            timestampMillis = source.time,
        )
        FilterStorage.saveIncomingPoint(this, incomingPoint)
        GpxTrackRecorder.append(this, incomingPoint)
        val zones = FilterStorage.zones(this)
        val blockedBy = zones.firstOrNull { it.contains(source.latitude, source.longitude) }
        if (blockedBy == null) {
            FilterStorage.saveFilterResult(this, isFiltered = false)
            val safePoint = FilterStorage.SafePoint(
                latitude = source.latitude,
                longitude = source.longitude,
                accuracyMeters = source.accuracy,
                timestampMillis = source.time,
            )
            lastSafePoint = safePoint
            FilterStorage.saveLastSafePoint(this, safePoint)
            inject(safePoint)
            FilterStorage.appendEvent(this, "${eventTime()}  Безопасная точка передана mock GPS")
        } else {
            FilterStorage.saveFilterResult(this, isFiltered = true, zoneName = blockedBy.name)
            val safePoint = lastSafePoint
            if (safePoint == null) {
                FilterStorage.appendEvent(
                    this,
                    "${eventTime()}  Точка внутри «${blockedBy.name}»; безопасной точки ещё нет",
                )
            } else {
                inject(safePoint)
                FilterStorage.appendEvent(
                    this,
                    "${eventTime()}  Точка внутри «${blockedBy.name}»; удерживается последняя безопасная",
                )
            }
        }
    }

    private fun inject(point: FilterStorage.SafePoint) {
        if (!testProviderInstalled) return
        val mockLocation = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = point.latitude
            longitude = point.longitude
            accuracy = point.accuracyMeters.coerceAtLeast(1f)
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        try {
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, mockLocation)
        } catch (exception: SecurityException) {
            fail("Потерян доступ к mock GPS: ${exception.message}")
        } catch (exception: IllegalArgumentException) {
            fail("Mock GPS provider недоступен: ${exception.message}")
        }
    }

    private fun stopFiltering(removeNotification: Boolean = false) {
        if (stopped) return
        stopped = true
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(this)
            if (testProviderInstalled) {
                runCatching { locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false) }
                runCatching { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) }
            }
        }
        testProviderInstalled = false
        FilterStorage.setFilteringEnabled(this, false)
        FilterStorage.appendEvent(this, "${eventTime()}  Mock GPS выключен")
        if (removeNotification) stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(message: String) {
        FilterStorage.setFilteringEnabled(this, false)
        FilterStorage.appendEvent(this, "${eventTime()}  Ошибка: $message")
        stopFiltering(removeNotification = true)
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun locationIsMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    private fun startAsForegroundService() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GPS Filter", NotificationManager.IMPORTANCE_LOW),
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GPS Filter активен")
            .setContentText("Удерживает последнюю безопасную геопозицию внутри приватных зон")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val ACTION_START = "io.github.deevroman.gpsfilter.action.START"
        const val ACTION_STOP = "io.github.deevroman.gpsfilter.action.STOP"
        const val ACTION_STATE_CHANGED = "io.github.deevroman.gpsfilter.action.STATE_CHANGED"
        private const val CHANNEL_ID = "filtering"
        private const val NOTIFICATION_ID = 1001
        private const val UPDATE_INTERVAL_MILLIS = 2_000L
        private const val UPDATE_DISTANCE_METERS = 1f

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GpsFilterService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, GpsFilterService::class.java).setAction(ACTION_STOP))
        }
    }
}
