package gg.thor.utils.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import gg.thor.utils.data.SettingsRepository
import gg.thor.utils.overlay.StatsOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Restores the monitors after a reboot.
 *
 * A battery projection that silently stops existing every time the handheld
 * restarts is not a monitor, so the enabled state is treated as persistent
 * intent rather than a one-off action.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository(appContext).settings.first()
                if (settings.batteryMonitorEnabled) BatteryMonitorService.start(appContext)
                if (settings.hudEnabled) StatsOverlayService.start(appContext)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
