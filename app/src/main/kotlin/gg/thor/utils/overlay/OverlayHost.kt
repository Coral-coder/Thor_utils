package gg.thor.utils.overlay

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import android.view.View

/**
 * Minimal owner set for a Compose view living in a `WindowManager` window.
 *
 * A `ComposeView` refuses to compose without a lifecycle, a view-model store and
 * a saved-state registry attached to the view tree. An Activity supplies those;
 * a Service does not, so the overlay brings its own.
 */
class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
    }

    /** Wires this owner into [view] so a `ComposeView` beneath it can compose. */
    fun attachTo(view: View) {
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeViewModelStoreOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }
}

/** A display the user can target, with a friendly label. */
data class DisplayTarget(
    val id: Int,
    val name: String,
    val widthPx: Int,
    val heightPx: Int,
    val isPrimary: Boolean,
) {
    val label: String get() = if (isPrimary) "$name (main)" else name
    val resolution: String get() = "${widthPx}×${heightPx}"
}

/**
 * Display lookup for a dual-screen handheld.
 *
 * The AYN Thor exposes its second panel as an ordinary public display, so the
 * standard [DisplayManager] enumeration is all that is needed — no vendor SDK.
 */
object Displays {

    fun all(context: Context): List<DisplayTarget> {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.displays.map { display ->
            @Suppress("DEPRECATION")
            DisplayTarget(
                id = display.displayId,
                name = display.name ?: "Display ${display.displayId}",
                widthPx = display.width,
                heightPx = display.height,
                isPrimary = display.displayId == Display.DEFAULT_DISPLAY,
            )
        }.sortedBy { it.id }
    }

    /** The secondary panel, or null on a single-screen device. */
    fun secondary(context: Context): DisplayTarget? =
        all(context).firstOrNull { !it.isPrimary }

    fun byId(context: Context, displayId: Int): Display? {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        return manager.getDisplay(displayId)
    }

    /**
     * A context and window manager bound to [display].
     *
     * Adding a window to a non-default display requires a context created for
     * that display — passing a `launchDisplayId` is not enough, the window itself
     * has to be born on the right screen.
     */
    fun windowManagerFor(context: Context, display: Display): Pair<Context, WindowManager> {
        val windowContext = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                context.createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                context.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
            else -> context.createDisplayContext(display)
        }
        return windowContext to windowContext.getSystemService(WindowManager::class.java)
    }
}
