package com.shellz.a2dpvolume.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.shellz.a2dpvolume.LinkState
import com.shellz.a2dpvolume.R
import com.shellz.a2dpvolume.SessionRepository
import com.shellz.a2dpvolume.audio.KeepAlivePlayer
import com.shellz.a2dpvolume.audio.StreamVolumeController
import com.shellz.a2dpvolume.bluetooth.A2dpSpeakerTracker
import com.shellz.a2dpvolume.bluetooth.BluetoothPermissions
import com.shellz.a2dpvolume.ui.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the Bluetooth speaker session:
 *  - tracks the connected A2DP sink and reacts to disconnect / reconnect
 *  - keeps the link warm with an inaudible stream (optional)
 *  - applies volume changes from the UI and the notification buttons
 *  - restores the user's chosen volume when the speaker comes back
 */
class A2dpVolumeService : LifecycleService(), A2dpSpeakerTracker.Listener {

    private lateinit var tracker: A2dpSpeakerTracker
    private lateinit var volume: StreamVolumeController
    private val keepAlive = KeepAlivePlayer()

    /** Volume the user last picked, re-applied after a reconnect. */
    private var desiredVolume: Int? = null
    private var restoreJob: Job? = null
    private var started = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        volume = StreamVolumeController(this) { cur, min, max ->
            SessionRepository.update { it.copy(volume = cur, minVolume = min, maxVolume = max) }
            if (started) updateNotification()
        }
        tracker = A2dpSpeakerTracker(this, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_START, null -> startSession()
            ACTION_STOP -> stopSession()
            ACTION_VOLUME_UP -> { volume.step(up = true); desiredVolume = volume.current }
            ACTION_VOLUME_DOWN -> { volume.step(up = false); desiredVolume = volume.current }
            ACTION_SET_VOLUME -> {
                val v = intent.getIntExtra(EXTRA_VOLUME, volume.current)
                desiredVolume = v
                volume.set(v)
            }
            ACTION_SET_KEEPALIVE -> setKeepAlive(intent.getBooleanExtra(EXTRA_ENABLED, true))
        }
        // If killed, Android restarts us with a null intent, which maps to ACTION_START.
        return START_STICKY
    }

    private fun startSession() {
        if (started) return
        started = true
        startInForeground()

        if (!BluetoothPermissions.hasConnect(this)) {
            SessionRepository.update { it.copy(serviceRunning = true, linkState = LinkState.NO_PERMISSION) }
            updateNotification()
            return
        }
        SessionRepository.update { it.copy(serviceRunning = true) }
        volume.start()
        tracker.start()
    }

    private fun stopSession() {
        started = false
        restoreJob?.cancel()
        keepAlive.stop()
        tracker.stop()
        volume.stop()
        SessionRepository.update {
            it.copy(
                serviceRunning = false, linkState = LinkState.IDLE,
                speakerName = null, speakerAddress = null, routedToBluetooth = false
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (started) {
            keepAlive.stop()
            tracker.stop()
            volume.stop()
            SessionRepository.update { it.copy(serviceRunning = false, linkState = LinkState.IDLE) }
        }
        super.onDestroy()
    }

    // ---- A2dpSpeakerTracker.Listener ----

    override fun onSpeakerConnected(name: String, address: String) {
        Log.i(TAG, "Speaker connected: $name ($address)")
        SessionRepository.update {
            it.copy(linkState = LinkState.CONNECTED, speakerName = name, speakerAddress = address)
        }
        if (SessionRepository.state.value.keepAliveEnabled) keepAlive.start()

        // Audio routing to the new sink takes a moment; restore volume once it settles.
        restoreJob?.cancel()
        restoreJob = lifecycleScope.launch {
            for (attempt in 0 until 10) {
                if (volume.isRoutedToA2dp()) break
                delay(300)
            }
            SessionRepository.update { it.copy(routedToBluetooth = volume.isRoutedToA2dp()) }
            desiredVolume?.let { volume.set(it) }
            updateNotification()
        }
        updateNotification()
    }

    override fun onSpeakerDisconnected(lastName: String?) {
        Log.i(TAG, "Speaker disconnected: $lastName")
        // Stop our stream so the phone speaker doesn't start hissing dither.
        keepAlive.stop()
        restoreJob?.cancel()
        SessionRepository.update {
            it.copy(linkState = LinkState.DISCONNECTED, speakerName = lastName, routedToBluetooth = false)
        }
        updateNotification()
    }

    override fun onBluetoothOff() {
        keepAlive.stop()
        SessionRepository.update {
            it.copy(linkState = LinkState.BLUETOOTH_OFF, routedToBluetooth = false)
        }
        updateNotification()
    }

    override fun onSearching() {
        if (SessionRepository.state.value.linkState != LinkState.DISCONNECTED) {
            SessionRepository.update { it.copy(linkState = LinkState.SEARCHING) }
        }
        updateNotification()
    }

    private fun setKeepAlive(enabled: Boolean) {
        SessionRepository.update { it.copy(keepAliveEnabled = enabled) }
        val connected = SessionRepository.state.value.linkState == LinkState.CONNECTED
        if (enabled && connected && started) keepAlive.start() else keepAlive.stop()
    }

    // ---- Notification ----

    private fun startInForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!started) return
        val canPost = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (canPost) {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val s = SessionRepository.state.value
        val title = when (s.linkState) {
            LinkState.CONNECTED -> getString(R.string.notif_connected, s.speakerName ?: "speaker")
            LinkState.DISCONNECTED -> getString(R.string.notif_waiting, s.speakerName ?: "speaker")
            LinkState.BLUETOOTH_OFF -> getString(R.string.status_bt_off)
            LinkState.NO_PERMISSION -> getString(R.string.status_no_permission)
            else -> getString(R.string.status_searching)
        }
        val text = getString(R.string.notif_volume, s.volume, s.maxVolume)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_vol_down, getString(R.string.vol_down), serviceIntent(ACTION_VOLUME_DOWN, 1))
            .addAction(R.drawable.ic_vol_up, getString(R.string.vol_up), serviceIntent(ACTION_VOLUME_UP, 2))
            .addAction(R.drawable.ic_stop, getString(R.string.stop), serviceIntent(ACTION_STOP, 3))
            .build()
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            this, requestCode,
            Intent(this, A2dpVolumeService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val TAG = "A2dpVolumeService"
        private const val CHANNEL_ID = "a2dp_session"
        private const val NOTIFICATION_ID = 42

        const val ACTION_START = "com.shellz.a2dpvolume.START"
        const val ACTION_STOP = "com.shellz.a2dpvolume.STOP"
        const val ACTION_VOLUME_UP = "com.shellz.a2dpvolume.VOL_UP"
        const val ACTION_VOLUME_DOWN = "com.shellz.a2dpvolume.VOL_DOWN"
        const val ACTION_SET_VOLUME = "com.shellz.a2dpvolume.SET_VOLUME"
        const val ACTION_SET_KEEPALIVE = "com.shellz.a2dpvolume.SET_KEEPALIVE"
        const val EXTRA_VOLUME = "volume"
        const val EXTRA_ENABLED = "enabled"

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))

        fun stop(context: Context) = context.startService(intent(context, ACTION_STOP))

        fun setVolume(context: Context, value: Int) =
            context.startService(intent(context, ACTION_SET_VOLUME).putExtra(EXTRA_VOLUME, value))

        fun setKeepAlive(context: Context, enabled: Boolean) =
            context.startService(intent(context, ACTION_SET_KEEPALIVE).putExtra(EXTRA_ENABLED, enabled))

        private fun intent(context: Context, action: String) =
            Intent(context, A2dpVolumeService::class.java).setAction(action)
    }
}
