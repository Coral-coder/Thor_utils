package gg.thor.utils.battery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.LifecycleService
import gg.thor.utils.MainActivity
import gg.thor.utils.R
import gg.thor.utils.core.battery.BatteryEstimate
import gg.thor.utils.core.battery.BatteryEstimator
import gg.thor.utils.core.battery.BatterySample
import gg.thor.utils.core.battery.LoadWindow
import gg.thor.utils.core.stats.Format
import gg.thor.utils.data.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Samples the battery and publishes a projected runtime.
 *
 * Android has no third-party status bar, so the persistent notification *is* the
 * task-bar readout: its title carries the projected runtime and it updates in
 * place as the estimate moves. The same estimate is exposed through
 * [BatteryMonitorService.estimate] for the in-app dashboard and the HUD.
 */
class BatteryMonitorService : LifecycleService() {

    private lateinit var settings: SettingsRepository
    private lateinit var batteryManager: BatteryManager
    private var estimator = BatteryEstimator()

    /** Cached so a level-change broadcast can force an immediate resample. */
    @Volatile
    private var wakeUp: Boolean = false

    private val levelReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // A whole percentage point moved: worth re-projecting straight away
            // rather than waiting out the remainder of the sampling interval.
            wakeUp = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        createNotificationChannel()
        registerReceiver(levelReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        startForeground(NOTIFICATION_ID, buildNotification(null))
        lifecycleScope.launch { sampleLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Restart if the system kills us: a monitor that quietly stops monitoring
        // is worse than one that never started.
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(levelReceiver) }
        _estimate.value = null
        super.onDestroy()
    }

    private suspend fun sampleLoop() {
        var activeCapacity: Long? = null

        while (lifecycleScope.isActive) {
            val current = settings.settings.first()

            // Capacity converts current into percent-per-hour, so changing it
            // invalidates every accumulated average. Rebuild the estimator rather
            // than letting old readings scaled against the old capacity contaminate
            // the new ones.
            val capacity = resolveCapacityUah(current.batteryCapacityUah)
            if (capacity != activeCapacity) {
                activeCapacity = capacity
                estimator = BatteryEstimator(capacityUah = capacity)
            }

            val sample = readSample()
            if (sample != null) {
                val result = estimator.add(sample)
                _estimate.value = result
                updateNotification(result)
            }

            // Poll in short slices so a battery broadcast can cut the wait short.
            val intervalMs = current.batterySampleSeconds * 1000L
            var waited = 0L
            while (waited < intervalMs && lifecycleScope.isActive) {
                if (wakeUp) {
                    wakeUp = false
                    break
                }
                delay(WAKE_CHECK_MS)
                waited += WAKE_CHECK_MS
            }
        }
    }

    private fun readSample(): BatterySample? {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null

        val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null

        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val chargeStatus = status.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = plugged != 0 || chargeStatus == BatteryManager.BATTERY_STATUS_CHARGING

        return BatterySample(
            timestampMs = System.currentTimeMillis(),
            levelPercent = level * 100f / scale,
            chargeCounterUah = batteryManager
                .getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                .takeIf { it > 0 && it != Long.MIN_VALUE },
            currentMicroAmps = batteryManager
                .getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != 0L && it != Long.MIN_VALUE },
            voltageMilliVolts = status.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1).takeIf { it > 0 },
            temperatureCelsius = status.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE }?.let { it / 10f },
            isCharging = isCharging,
        )
    }

    /**
     * Works out the pack capacity in µAh.
     *
     * The user's setting wins. Otherwise the charge counter is divided by the
     * current level, which recovers the full capacity from a partial charge —
     * `BATTERY_PROPERTY_CAPACITY` only reports a percentage, and the framework
     * has no public API for design capacity.
     */
    private fun resolveCapacityUah(configured: Long): Long? {
        if (configured > 0) return configured
        val counter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (counter > 0 && percent in 1..100) {
            return counter * 100 / percent
        }
        return null
    }

    private fun updateNotification(estimate: BatteryEstimate) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(estimate))
    }

    private fun buildNotification(estimate: BatteryEstimate?): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = when {
            estimate == null -> "Measuring battery…"
            !estimate.isWarmedUp -> "Measuring battery… ${estimate.levelPercent.toInt()}%"
            estimate.isCharging -> {
                val full = Format.runtime(estimate.headlineRuntime)
                "Charging · $full to full · ${estimate.levelPercent.toInt()}%"
            }
            else -> {
                val remaining = Format.runtime(estimate.headlineRuntime)
                "$remaining left · ${estimate.levelPercent.toInt()}%"
            }
        }

        val detail = estimate?.let {
            val one = Format.drainRate(it.drainPercentPerHour[LoadWindow.ONE_MINUTE] ?: 0f)
            val five = Format.drainRate(it.drainPercentPerHour[LoadWindow.FIVE_MINUTE] ?: 0f)
            val fifteen = Format.drainRate(it.drainPercentPerHour[LoadWindow.FIFTEEN_MINUTE] ?: 0f)
            val power = it.powerWatts?.let { w -> " · ${Format.watts(w)}" } ?: ""
            "1m $one · 5m $five · 15m $fifteen$power"
        } ?: "Starting up"

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(R.drawable.ic_battery_stat)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            // A projection that reorders the shade every twenty seconds is
            // maddening; pin it to the bottom and keep it silent.
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Battery runtime",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Live projected battery runtime"
            setShowBadge(false)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "battery_runtime"
        const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "gg.thor.utils.STOP_BATTERY"

        private const val WAKE_CHECK_MS = 1000L

        private val _estimate = MutableStateFlow<BatteryEstimate?>(null)

        /** Latest estimate, shared with the dashboard and the HUD. */
        val estimate: StateFlow<BatteryEstimate?> = _estimate.asStateFlow()

        fun start(context: Context) {
            val intent = Intent(context, BatteryMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, BatteryMonitorService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
