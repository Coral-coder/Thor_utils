package gg.thor.utils.overlay

import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * Optional helper that hands input focus back to a chosen display.
 *
 * This is a second line of defence, not the main mechanism. The HUD window is
 * created non-focusable, so touching it does not move focus in the first place;
 * see [StatsOverlayService]. What this service adds is a way to recover when
 * something *else* moves focus to the bottom screen — a notification, a system
 * dialog, or an emulator that grabs it on launch.
 *
 * What it can do is bounded by the platform. There is no public API to say "make
 * display N focused"; only the system UI can do that. What an app can do is
 * relaunch the activity that was last on that display with a launch-display hint,
 * which brings it forward and takes focus with it. That works for ordinary
 * activities and is a no-op for anything it cannot see, which is why this is
 * off unless the user turns it on.
 */
class FocusReturnService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = e.packageName?.toString() ?: return
        if (packageName == applicationContext.packageName) return

        // Record which app was last in front on each display, so it can be
        // brought back later. Class name is captured when present because a
        // package alone does not identify which activity to relaunch.
        val displayId = e.displayId
        val className = e.className?.toString()
        lastForeground[displayId] = ForegroundApp(packageName, className)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private data class ForegroundApp(val packageName: String, val className: String?)

    companion object {
        @Volatile
        private var instance: FocusReturnService? = null

        private val lastForeground = ConcurrentHashMap<Int, ForegroundApp>()

        /** True when the user has granted and enabled the accessibility service. */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = ComponentName(context, FocusReturnService::class.java).flattenToString()
            return enabled.split(':').any { TextUtils.equals(it, expected) }
        }

        /**
         * Brings the last known app on [displayId] back to the front.
         *
         * Returns false when there is nothing recorded for that display or the
         * relaunch was refused, so callers can fall back quietly rather than
         * pretending it worked.
         */
        fun returnFocusTo(context: Context, displayId: Int): Boolean {
            val target = lastForeground[displayId] ?: return false
            val intent = context.packageManager
                .getLaunchIntentForPackage(target.packageName)
                ?.apply {
                    // SINGLE_TOP plus NEW_TASK re-fronts the existing task instead
                    // of restarting the app and losing the player's game state.
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                } ?: return false

            val options = ActivityOptions.makeBasic().let {
                if (displayId != Display.INVALID_DISPLAY) it.setLaunchDisplayId(displayId) else it
            }
            return runCatching { context.startActivity(intent, options.toBundle()) }.isSuccess
        }

        /** Opens the system screen where the service is turned on. */
        fun openSettings(context: Context) {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
