package com.tripshare.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.tripshare.app.MainActivity
import com.tripshare.app.R
import com.tripshare.app.TripShareApplication
import com.tripshare.app.data.CapturedPosition
import com.tripshare.app.data.LocalTripSummary
import com.tripshare.app.data.TripRepository
import com.tripshare.app.worker.TripSyncWorker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

class TripLocationService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repository: TripRepository
    private lateinit var sampler: AmapLocationSampler
    private var activeTrip: LocalTripSummary? = null
    private var loopJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastSuccessfulAt: String? = null
    private var lastUploadElapsed = 0L
    private var initialized = false

    override fun onCreate() {
        super.onCreate()
        repository = (application as TripShareApplication).trips
        sampler = AmapLocationSampler(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startAsForeground(notification("正在恢复行程…", "行程位置共享正在运行"))
        } catch (_: SecurityException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_STOP -> {
                val tripId = intent.getStringExtra(EXTRA_TRIP_ID)
                stopTrip(tripId)
                return START_NOT_STICKY
            }
            ACTION_SETTINGS_UPDATED -> {
                if (initialized) restartWithUpdatedSettings(intent.getStringExtra(EXTRA_TRIP_ID))
                else {
                    setServiceRunning(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
            ACTION_START -> begin(intent.getStringExtra(EXTRA_TRIP_ID), intent.getStringExtra(EXTRA_FIRST_POINT_AT))
            else -> begin(null, null)
        }
        return START_STICKY
    }

    private fun begin(tripId: String?, firstPointAt: String?) {
        if (initialized) return
        initialized = true
        serviceScope.launch {
            val trip = tripId?.let { repository.localTrip(it) } ?: repository.activeTrip()
            if (trip == null || !trip.isActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            activeTrip = trip
            setServiceRunning(true)
            lastSuccessfulAt = firstPointAt
            updateNotification("正在记录行程", intervalLabel(trip.sampleIntervalSec))
            registerNetworkCallback()
            runCatching { repository.uploadPending(trip.id, maxBatches = 2) }
            lastUploadElapsed = SystemClock.elapsedRealtime()
            loopJob = serviceScope.launch { samplingLoop(trip, firstPointAt) }
        }
    }

    private suspend fun samplingLoop(trip: LocalTripSummary, firstPointAt: String?) {
        var waitUntilFirst = firstPointAt?.let { parseMillis(it) }?.let { captured ->
            val remaining = trip.sampleIntervalSec * 1_000L - (System.currentTimeMillis() - captured)
            remaining.coerceAtLeast(0L)
        } ?: 0L
        while (serviceScope.coroutineContext[Job]?.isActive == true) {
            val deadline = trip.maxShareDeadline
            if (deadline != null) {
                val remainingShare = deadline.toEpochMilli() - System.currentTimeMillis()
                if (remainingShare <= 0) {
                    stopTrip(trip.id, reason = "expired")
                    return
                }
                waitUntilFirst = waitUntilFirst.coerceAtMost(remainingShare)
            }
            if (waitUntilFirst > 0) delay(waitUntilFirst)
            val cycleStarted = SystemClock.elapsedRealtime()
            if (deadline != null && Instant.now().isAfter(deadline)) {
                stopTrip(trip.id, reason = "expired")
                return
            }
            var wakeLock: PowerManager.WakeLock? = null
            try {
                if (!hasLocationPermission()) {
                    updateNotification("定位权限已关闭，记录已暂停", "打开应用并由你主动恢复")
                    setServiceRunning(false)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return
                }
                val power = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TripShare:LocationSample").apply {
                    setReferenceCounted(false)
                    acquire(50_000L)
                }
                updateNotification("正在获取位置…", intervalLabel(trip.sampleIntervalSec))
                val sample = withTimeout(45_000L) { sampler.capture(needAddress = false, newerThan = lastSuccessfulAt) }
                if (sample != null) {
                    if (AmapLocationSampler.isNewerThan(sample.capturedAt, lastSuccessfulAt)) {
                        val point = CapturedPosition(
                            UUID.randomUUID().toString(), trip.id, sample.latWgs84, sample.lonWgs84,
                            sample.capturedAt, sample.accuracyM, sample.speedMps, sample.speedAccuracyMps,
                            sample.source, "WGS84"
                        )
                        repository.saveObservedPosition(point)
                        lastSuccessfulAt = point.capturedAt
                        updateNotification("最近定位于 ${formatTime(point.capturedAt)}", intervalLabel(trip.sampleIntervalSec))
                    } else {
                        updateNotification("尚未获取到比上次更新的位置", intervalLabel(trip.sampleIntervalSec))
                    }
                } else {
                    updateNotification("暂时无法获取新位置", "下次尝试：${intervalLabel(trip.sampleIntervalSec)}")
                }
            } catch (_: TimeoutCancellationException) {
                updateNotification("定位超时，等待下个周期重试", intervalLabel(trip.sampleIntervalSec))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateNotification("位置获取或本地保存失败", intervalLabel(trip.sampleIntervalSec))
            } finally {
                runCatching { if (wakeLock?.isHeld == true) wakeLock.release() }
            }

            val uploadDue = trip.mode.wireValue == "standard" ||
                SystemClock.elapsedRealtime() - lastUploadElapsed >= trip.uploadIntervalSec * 1_000L
            if (uploadDue) {
                try {
                    repository.uploadPending(trip.id, maxBatches = 10)
                    lastUploadElapsed = SystemClock.elapsedRealtime()
                } catch (_: Exception) {
                    // Room remains the source of truth; retry on connectivity restoration or the next cycle.
                }
            }
            refreshNotificationSyncState(trip)
            val periodMillis = trip.sampleIntervalSec * 1_000L
            waitUntilFirst = (periodMillis - (SystemClock.elapsedRealtime() - cycleStarted)).coerceAtLeast(5_000L)
            if (deadline != null) {
                waitUntilFirst = waitUntilFirst.coerceAtMost((deadline.toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0L))
            }
        }
    }

    private fun stopTrip(tripId: String?, reason: String = "manual") {
        serviceScope.launch {
            val selected = tripId ?: activeTrip?.id ?: repository.activeTrip()?.id
            if (selected != null) {
                repository.endTrip(selected, reason)
                TripSyncWorker.enqueue(applicationContext, selected)
            }
            setServiceRunning(false)
            loopJob?.cancel()
            unregisterNetworkCallback()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun restartWithUpdatedSettings(tripId: String?) {
        val selectedId = tripId ?: activeTrip?.id ?: return
        serviceScope.launch {
            val updated = repository.localTrip(selectedId) ?: return@launch
            if (!updated.isActive) return@launch
            loopJob?.cancel()
            activeTrip = updated
            val firstAt = lastSuccessfulAt
            updateNotification("正在记录行程", intervalLabel(updated.sampleIntervalSec))
            loopJob = serviceScope.launch { samplingLoop(updated, firstAt) }
        }
    }

    private suspend fun refreshNotificationSyncState(trip: LocalTripSummary) {
        val pending = runCatching { repository.pendingCount(trip.id) }.getOrDefault(0)
        val time = lastSuccessfulAt?.let(::formatTime) ?: "尚无成功定位"
        val status = if (pending == 0) "已同步" else "$pending 个位置待同步"
        updateNotification("最近定位于 $time", "$status · ${intervalLabel(trip.sampleIntervalSec)}")
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val trip = activeTrip ?: return
                serviceScope.launch {
                    runCatching { repository.uploadPending(trip.id) }
                    refreshNotificationSyncState(trip)
                }
            }
        }
        networkCallback = callback
        runCatching { manager.registerNetworkCallback(NetworkRequest.Builder().build(), callback) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(callback) }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun setServiceRunning(running: Boolean) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, running).apply()
    }

    private fun startAsForeground(value: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, value, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, value)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "行程位置共享", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示行程记录状态和最近一次定位时间"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun notification(title: String, text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopService = PendingIntent.getService(
            this, 2, Intent(this, TripLocationService::class.java).apply {
                action = ACTION_STOP
                putExtra(EXTRA_TRIP_ID, activeTrip?.id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, "结束行程", stopService)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(title, text))
    }

    private fun intervalLabel(seconds: Int) = "每 ${if (seconds % 60 == 0) "${seconds / 60} 分钟" else "$seconds 秒"}采集"
    private fun parseMillis(value: String) = runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    private fun formatTime(value: String): String = runCatching {
        DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
    }.getOrDefault("未知")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        setServiceRunning(false)
        loopJob?.cancel()
        unregisterNetworkCallback()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.tripshare.app.action.START_TRIP"
        const val ACTION_STOP = "com.tripshare.app.action.STOP_TRIP"
        const val ACTION_SETTINGS_UPDATED = "com.tripshare.app.action.SETTINGS_UPDATED"
        const val EXTRA_TRIP_ID = "trip_id"
        const val EXTRA_FIRST_POINT_AT = "first_point_at"
        private const val PREFS = "trip-location-service"
        private const val KEY_RUNNING = "running"
        private const val CHANNEL_ID = "trip-sharing-location"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context, tripId: String, firstPointAt: String) {
            val intent = Intent(context, TripLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TRIP_ID, tripId)
                putExtra(EXTRA_FIRST_POINT_AT, firstPointAt)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun settingsUpdated(context: Context, tripId: String) {
            if (!context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)) return
            val intent = Intent(context, TripLocationService::class.java).apply {
                action = ACTION_SETTINGS_UPDATED
                putExtra(EXTRA_TRIP_ID, tripId)
            }
            context.startService(intent)
        }
    }
}
