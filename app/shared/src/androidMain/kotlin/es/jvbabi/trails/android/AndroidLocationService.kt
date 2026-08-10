@file:OptIn(ExperimentalCoroutinesApi::class)

package es.jvbabi.trails.android

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import co.touchlab.kermit.Logger
import es.jvbabi.trails.domain.repository.*
import es.jvbabi.trails.shared.compose.R
import es.jvbabi.trails.utils.backgroundExceptionHandler
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AndroidLocationService: Service(), LocationListener, KoinComponent {

    private val serviceScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob() + backgroundExceptionHandler("AndroidLocationService")
    )

    private val locationRepository by inject<LocationRepository>()
    private val snapshotRepository by inject<SnapshotRepository>()
    private val devicesRepository by inject<DevicesRepository>()
    private val keyValueRepository by inject<KeyValueRepository>()
    private val trailsServerRepository by inject<TrailsServerRepository>()

    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    }

    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        val notification = createNotification()
        foregroundStarted = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(1, notification)
            }
            true
        } catch (e: Exception) {
            // From Android 12 on, a location foreground service must not be started from the
            // background -> ForegroundServiceStartNotAllowedException. Shut down cleanly instead
            // of letting the app crash.
            Log.w("LocationService", "startForeground not allowed, stopping the service: $e")
            false
        }
        if (!foregroundStarted) stopSelf()
    }

    private var isStarted = false

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foregroundStarted) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Trails:LocationWakeLock")
            wakeLock?.acquire()
        }

        if (!isStarted) {
            isStarted = true
            serviceScope.launch {
                keyValueRepository
                    .get(Key.ThisDeviceId)
                    .filterNotNull()
                    .flatMapLatest { id -> devicesRepository.getDeviceById(id) }
                    .filterNotNull()
                    .distinctUntilChangedBy {}
                    .collect {
                        trailsServerRepository.connectWithHomeserver().await()
                    }
            }
            serviceScope.launch {
                withContext(Dispatchers.Main) {
                    startTracking()
                }
            }
        }
        return START_STICKY
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun startTracking() {
        _isRunning.value = true
        snapshotRepository.startSnapshotCollection(serviceScope)
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                10000L,
                0f,
                this
            )

        } catch (unlikely: SecurityException) {
            Log.e("LocationService", "Keine Berechtigung: $unlikely")
        }
    }

    override fun onLocationChanged(location: Location) {
        serviceScope.launch {
            locationRepository.storeLocation(
                latitude = location.latitude,
                longitude = location.longitude,
                bearing = location.bearing,
                bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) location.bearingAccuracyDegrees else null,
                locationAccuracy = location.accuracy,
            )
        }
        Logger.d { "Native Location: ${location.latitude}, ${location.longitude} (Provider: ${location.provider})" }
    }

    override fun onProviderEnabled(provider: String) { Log.d("LocationService", "$provider enabled") }
    override fun onProviderDisabled(provider: String) { Log.d("LocationService", "$provider disabled") }

    private fun createNotification(): Notification {
        val channelId = "pure_location_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_tracking_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_tracking_title))
            .setContentText(getString(R.string.notification_tracking_body))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    @RequiresPermission(anyOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
        serviceScope.cancel()
        locationManager.removeUpdates(this)
        _isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
