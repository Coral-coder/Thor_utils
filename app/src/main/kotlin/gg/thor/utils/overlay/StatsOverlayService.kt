package gg.thor.utils.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import gg.thor.utils.R
import gg.thor.utils.data.HudPosition
import gg.thor.utils.data.SettingsRepository
import gg.thor.utils.data.ThorSettings
import gg.thor.utils.stats.DeviceSnapshot
import gg.thor.utils.stats.DeviceStatsReader
import gg.thor.utils.ui.hud.StatsHud
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Draws the always-on statistics HUD, by default on the bottom screen.
 *
 * The central design decision is [WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE].
 * A non-focusable window still receives touch events but never takes input
 * focus, so tapping the HUD leaves the game on the top screen focused — which is
 * exactly the "I have to tap the top screen again every time" problem this is
 * here to solve. Focus is never taken, so it never has to be given back.
 */
class StatsOverlayService : LifecycleService() {

    private lateinit var settings: SettingsRepository
    private lateinit var statsReader: DeviceStatsReader

    private var windowManager: WindowManager? = null
    private var hostView: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var attachedDisplayId: Int = Display.DEFAULT_DISPLAY

    private var snapshot by mutableStateOf<DeviceSnapshot?>(null)
    private var config by mutableStateOf(ThorSettings())

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(applicationContext)
        statsReader = DeviceStatsReader(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        lifecycleScope.launch {
            config = settings.settings.first()
            if (!canDrawOverlay()) {
                // Without the overlay permission there is nothing to show. Stop
                // rather than sit in the foreground doing nothing.
                stopSelf()
                return@launch
            }
            attach(config)
            pollLoop()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private suspend fun pollLoop() {
        while (lifecycleScope.isActive) {
            val latest = settings.settings.first()
            if (latest.hudDisplayId != config.hudDisplayId || latest.hudPosition != config.hudPosition) {
                config = latest
                detach()
                attach(latest)
            } else {
                config = latest
            }
            snapshot = statsReader.snapshot()
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun attach(current: ThorSettings) {
        val targetId = resolveDisplayId(current.hudDisplayId)
        val display = Displays.byId(this, targetId) ?: Displays.byId(this, Display.DEFAULT_DISPLAY) ?: return
        attachedDisplayId = display.displayId

        val (windowContext, manager) = Displays.windowManagerFor(this, display)
        windowManager = manager

        val lifecycleOwner = OverlayLifecycleOwner().apply { onCreate() }
        owner = lifecycleOwner

        val view = ComposeView(windowContext).apply {
            lifecycleOwner.attachTo(this)
            setContent {
                StatsHud(
                    snapshot = snapshot,
                    settings = config,
                    onRequestFocusReturn = { returnFocusIfConfigured() },
                )
            }
        }
        hostView = view
        lifecycleOwner.onStart()

        runCatching { manager.addView(view, layoutParams(current)) }
            .onFailure {
                // A vendor build that refuses overlays on the secondary display
                // should not leave a zombie service behind.
                detach()
                stopSelf()
            }
    }

    private fun detach() {
        hostView?.let { view -> runCatching { windowManager?.removeView(view) } }
        owner?.onDestroy()
        hostView = null
        owner = null
        windowManager = null
    }

    /** -1 means "the second screen if there is one, otherwise the main one". */
    private fun resolveDisplayId(configured: Int): Int =
        if (configured >= 0) configured
        else Displays.secondary(this)?.id ?: Display.DEFAULT_DISPLAY

    private fun layoutParams(current: ThorSettings): WindowManager.LayoutParams {
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = when (current.hudPosition) {
                HudPosition.TOP -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                HudPosition.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                HudPosition.LEFT -> Gravity.START or Gravity.CENTER_VERTICAL
                HudPosition.RIGHT -> Gravity.END or Gravity.CENTER_VERTICAL
                HudPosition.FLOATING -> Gravity.CENTER
            }
            // Let the HUD sit over the navigation bar and cutouts rather than
            // being pushed around by system insets on a small secondary panel.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    /**
     * Belt-and-braces focus return.
     *
     * The non-focusable window already prevents focus from moving. This exists
     * for the case where something else on the system moves it anyway, and only
     * runs when the user has opted in and named a priority display.
     */
    private fun returnFocusIfConfigured() {
        val current = config
        if (!current.focusReturnEnabled) return
        if (current.focusPriorityDisplayId == attachedDisplayId) return
        FocusReturnService.returnFocusTo(this, current.focusPriorityDisplayId)
    }

    private fun canDrawOverlay(): Boolean = Settings.canDrawOverlays(this)

    private fun buildNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Stats HUD active")
            .setContentText("Showing hardware statistics on the secondary display")
            .setSmallIcon(R.drawable.ic_stats_stat)
            .setOngoing(true)
            .setShowWhen(false)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Stats HUD",
            NotificationManager.IMPORTANCE_MIN,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "stats_hud"
        const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "gg.thor.utils.STOP_HUD"
        private const val POLL_INTERVAL_MS = 1000L

        fun start(context: Context) {
            val intent = Intent(context, StatsOverlayService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, StatsOverlayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
