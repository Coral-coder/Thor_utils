package gg.thor.utils

import android.app.Application
import gg.thor.utils.battery.BatteryMonitorService
import gg.thor.utils.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ThorApp : Application() {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Bring the battery monitor back on launch if it was on when the user
        // last used the app; the boot receiver covers the reboot case.
        scope.launch {
            if (settings.settings.first().batteryMonitorEnabled) {
                BatteryMonitorService.start(this@ThorApp)
            }
        }
    }
}
