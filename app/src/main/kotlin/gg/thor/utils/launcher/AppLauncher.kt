package gg.thor.utils.launcher

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.view.Display
import gg.thor.utils.core.emulators.Emulator
import gg.thor.utils.core.emulators.EmulatorCatalog

/** An installed, launchable application. */
data class LaunchableApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    /** Set when this app is a known emulator. */
    val emulator: Emulator?,
) {
    val isEmulator: Boolean get() = emulator != null
}

/** Why a launch did not do what was asked. */
sealed interface LaunchResult {
    data object Success : LaunchResult
    data class MovedToDefaultDisplay(val reason: String) : LaunchResult
    data class Failed(val reason: String) : LaunchResult
}

/**
 * Finds installed apps and launches them on a chosen screen.
 *
 * Dual-screen launching rests on [ActivityOptions.setLaunchDisplayId]. It is not
 * a guarantee: an activity declaring `resizeableActivity="false"`, or a vendor
 * policy that pins certain apps to the main panel, will land on the default
 * display regardless. The launch is verified rather than assumed, so the UI can
 * tell the user what actually happened instead of silently doing the wrong thing.
 */
class AppLauncher(private val context: Context) {

    private val packageManager: PackageManager get() = context.packageManager

    /** All launchable apps, emulators first, then alphabetical. */
    fun installedApps(includeIcons: Boolean = true): List<LaunchableApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved: List<ResolveInfo> = packageManager.queryIntentActivities(intent, 0)

        return resolved
            .asSequence()
            .mapNotNull { info ->
                val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                LaunchableApp(
                    packageName = packageName,
                    label = info.loadLabel(packageManager).toString(),
                    icon = if (includeIcons) runCatching { info.loadIcon(packageManager) }.getOrNull() else null,
                    emulator = matchEmulator(packageName, info.loadLabel(packageManager).toString()),
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<LaunchableApp> { it.isEmulator }.thenBy { it.label.lowercase() })
            .toList()
    }

    /**
     * Matches an installed package to the emulator catalog.
     *
     * Application ids are checked first, then the visible label. The label pass
     * matters because emulator forks change their id freely — matching on name
     * catches a repackaged build the catalog has never seen.
     */
    private fun matchEmulator(packageName: String, label: String): Emulator? {
        EmulatorCatalog.byPackageId(packageName)?.let { return it }
        val normalisedLabel = label.lowercase().filter { it.isLetterOrDigit() }
        if (normalisedLabel.isEmpty()) return null
        return EmulatorCatalog.emulators.firstOrNull { emulator ->
            val normalisedName = emulator.name.lowercase().filter { it.isLetterOrDigit() }
            normalisedName.isNotEmpty() &&
                (normalisedLabel.contains(normalisedName) || normalisedName.contains(normalisedLabel))
        }
    }

    /**
     * Launches [packageName] on [displayId].
     *
     * `FLAG_ACTIVITY_MULTIPLE_TASK` is deliberately included so the same emulator
     * can be started independently on both panels — without it, a second launch
     * just re-fronts the existing task on whichever display already has it.
     */
    fun launch(packageName: String, displayId: Int, allowSeparateInstance: Boolean = true): LaunchResult {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: return LaunchResult.Failed("$packageName has no launcher activity")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (allowSeparateInstance && displayId != Display.DEFAULT_DISPLAY) {
            intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)

        return try {
            context.startActivity(intent, options.toBundle())
            if (canLaunchOnDisplay(packageName, displayId)) {
                LaunchResult.Success
            } else {
                LaunchResult.MovedToDefaultDisplay(
                    "This app does not declare support for multiple displays, so Android " +
                        "may have opened it on the main screen instead.",
                )
            }
        } catch (e: SecurityException) {
            LaunchResult.Failed("Android refused the launch: ${e.message}")
        } catch (e: Exception) {
            LaunchResult.Failed(e.message ?: "Launch failed")
        }
    }

    /**
     * Whether the app declares itself resizeable, which is what Android checks
     * before honouring a secondary-display launch.
     */
    fun canLaunchOnDisplay(packageName: String, displayId: Int): Boolean {
        if (displayId == Display.DEFAULT_DISPLAY) return true
        return try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES)
            val activities = info.activities ?: return false
            val launcherActivity = packageManager.getLaunchIntentForPackage(packageName)
                ?.component?.className
            val target = activities.firstOrNull { it.name == launcherActivity } ?: activities.first()
            resizeModeOf(target) != RESIZE_MODE_UNRESIZEABLE
        } catch (_: Exception) {
            // Unknown is treated as "probably fine": refusing to try would be
            // worse than attempting a launch that Android may still honour.
            true
        }
    }

    /**
     * Reads `ActivityInfo.resizeMode` reflectively.
     *
     * The field is the one Android itself consults when deciding whether an
     * activity may open on a secondary display, but it is hidden from the public
     * SDK, so it cannot be referenced directly. When it is unreadable — a future
     * release tightening reflection, say — the answer is "resizeable", which
     * keeps the launch attempt happening rather than blocking it on a check that
     * is only advisory anyway.
     */
    private fun resizeModeOf(activity: android.content.pm.ActivityInfo): Int = try {
        val field = android.content.pm.ActivityInfo::class.java.getField("resizeMode")
        field.getInt(activity)
    } catch (_: Throwable) {
        RESIZE_MODE_RESIZEABLE
    }

    private companion object {
        /** `ActivityInfo.RESIZE_MODE_UNRESIZEABLE`. */
        const val RESIZE_MODE_UNRESIZEABLE = 0
        /** `ActivityInfo.RESIZE_MODE_RESIZEABLE`. */
        const val RESIZE_MODE_RESIZEABLE = 2
    }
}
