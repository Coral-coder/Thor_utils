package com.shellz.a2dpvolume.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Takes the speaker's Bluetooth link away from whatever device currently holds it.
 *
 * There is no way to reach across and disconnect someone else's device, and there
 * does not need to be. A soundbar like the Vizio V20 holds exactly one Bluetooth
 * audio connection at a time, so connecting *this* device is what drops the other
 * one. Takeover is a side effect of connecting, not a separate operation.
 *
 * Two mechanisms, tried in that order:
 *
 * 1. Open an outbound RFCOMM socket to the paired speaker. This is public API and
 *    needs only BLUETOOTH_CONNECT, and the baseband (ACL) link it raises is a real
 *    connection that we initiated. Android's stack tends to bring A2DP across an
 *    ACL link to a bonded device whose profile policy allows it, which is the same
 *    path tapping the device in Bluetooth settings takes. It is not guaranteed —
 *    hence step 2 — so success is judged by watching for the A2DP state change
 *    rather than by whether the socket itself opened.
 *
 * 2. Android's media-output panel, which always works but costs a tap.
 *
 * Deliberately *not* used: `BluetoothA2dp.connect()`. It is hidden behind the
 * signature-level BLUETOOTH_PRIVILEGED permission, so reflection throws on any
 * current release — a hack that silently never works and only looks like a feature.
 */
object SpeakerTakeover {

    private const val TAG = "SpeakerTakeover"

    /**
     * `Settings.Panel.ACTION_MEDIA_OUTPUT` exists but is `@SystemApi`, so the
     * constant is absent from the public SDK and will not compile. The action
     * string itself is honoured by the system Settings app from Android 10 on,
     * so it is spelled out here.
     */
    private const val ACTION_MEDIA_OUTPUT = "android.settings.panel.action.MEDIA_OUTPUT"

    /**
     * Serial Port Profile. A soundbar almost certainly does not serve it, and that
     * is fine: the ACL link is raised during connection setup, before the RFCOMM
     * channel is negotiated and refused. The link is the point, not the channel.
     */
    private val SERIAL_PORT: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** How long to wait for the stack to carry A2DP over a link we raised. */
    private const val LINK_TIMEOUT_MS = 8_000L

    /** A paired audio sink the user could take over. */
    data class PairedSink(
        val name: String,
        val address: String,
        val isConnected: Boolean,
    )

    sealed interface Outcome {
        /** The link came up on its own. Nothing further for the user to do. */
        data class Connected(val name: String) : Outcome

        /** The picker is open; the user taps the speaker to finish the takeover. */
        object PickerOpened : Outcome

        /**
         * Nothing is paired to take over, so Bluetooth settings was opened instead.
         *
         * Connecting an already-paired speaker needs nothing from the speaker, but
         * pairing a new one needs it in pairing mode. Worth saying plainly rather
         * than showing an empty picker and letting the user wonder.
         */
        object NotPairedYet : Outcome

        /** Nothing could be done, with a reason worth showing. */
        data class Failed(val reason: String) : Outcome
    }

    /**
     * Paired devices that are audio sinks, most interesting first.
     *
     * Filtered by Bluetooth class rather than name so a renamed soundbar still
     * shows up, with the currently connected one first because that is the one
     * the user is usually reasoning about.
     */
    @SuppressLint("MissingPermission")
    fun pairedSinks(
        bondedDevices: Set<BluetoothDevice>?,
        a2dp: BluetoothA2dp?,
    ): List<PairedSink> {
        val connected = runCatching { a2dp?.connectedDevices.orEmpty() }
            .getOrDefault(emptyList())
            .map { it.address }
            .toSet()

        return bondedDevices.orEmpty()
            .filter { isAudioSink(it) }
            .map {
                PairedSink(
                    name = runCatching { it.name }.getOrNull() ?: it.address,
                    address = it.address,
                    isConnected = it.address in connected,
                )
            }
            .sortedByDescending { it.isConnected }
    }

    /** Paired audio sinks, read straight from the adapter. */
    @SuppressLint("MissingPermission")
    fun pairedSinksFor(context: Context): List<PairedSink> =
        pairedSinks(runCatching { adapterOf(context)?.bondedDevices }.getOrNull(), null)

    private fun adapterOf(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    private fun isAudioSink(device: BluetoothDevice): Boolean =
        runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ==
            BluetoothClass.Device.Major.AUDIO_VIDEO

    /**
     * Claims the speaker, quietly if the stack allows it and through the picker if not.
     *
     * Suspends for up to [LINK_TIMEOUT_MS] while a link is attempted, so it must not
     * be called on a blocking path. Never throws: failures come back as
     * [Outcome.Failed] with something worth showing the user.
     *
     * @param nameHint prefer a sink whose name contains this, when several are paired.
     */
    suspend fun takeOver(context: Context, nameHint: String = "VIZIO"): Outcome {
        if (!BluetoothPermissions.hasConnect(context)) {
            return Outcome.Failed("Bluetooth permission has not been granted.")
        }
        val adapter = adapterOf(context)
            ?: return Outcome.Failed("This device has no Bluetooth adapter.")
        if (!adapter.isEnabled) return Outcome.Failed("Bluetooth is turned off.")

        val sinks = pairedSinksFor(context)

        // With no bond there is nothing for the output panel to offer, and the
        // speaker genuinely does need putting into pairing mode first. Send the
        // user to Bluetooth settings and say so, rather than opening a picker
        // that cannot contain what they are looking for.
        if (sinks.isEmpty()) {
            return if (open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) {
                Outcome.NotPairedYet
            } else {
                Outcome.Failed("Could not open Bluetooth settings on this device.")
            }
        }

        // Only attempt a silent claim when it is unambiguous which speaker is meant.
        // Guessing wrong would connect the wrong device, which is worse than a tap.
        val target = chooseTarget(sinks, nameHint)
        if (target != null && !target.isConnected) {
            val device = runCatching { adapter.getRemoteDevice(target.address) }.getOrNull()
            if (device != null && claimLink(context, device)) {
                return Outcome.Connected(target.name)
            }
        }

        return openPicker(context)
    }

    /**
     * The one sink a silent claim should target, or null if it is a guess.
     *
     * A single paired sink is unambiguous. With several, only a name match counts:
     * picking the first of an arbitrary list would sometimes connect a headset.
     */
    private fun chooseTarget(sinks: List<PairedSink>, nameHint: String): PairedSink? =
        sinks.singleOrNull()
            ?: sinks.firstOrNull { it.name.contains(nameHint, ignoreCase = true) }

    /**
     * Raises a link to [device] and reports whether A2DP followed it.
     *
     * The socket attempt is expected to fail at the RFCOMM layer — a speaker does
     * not serve Serial Port — so its result is ignored. What matters is whether the
     * stack connected A2DP over the ACL link that the attempt brought up, which is
     * observed through the profile's own broadcast.
     */
    @SuppressLint("MissingPermission")
    private suspend fun claimLink(context: Context, device: BluetoothDevice): Boolean =
        withContext(Dispatchers.IO) {
            coroutineScope {
                val connected = async { awaitA2dpConnected(context, device.address) }
                // Give the receiver a moment to register before provoking the link.
                delay(150)
                val socket = runCatching {
                    device.createInsecureRfcommSocketToServiceRecord(SERIAL_PORT)
                }.getOrNull()
                if (socket != null) {
                    try {
                        socket.connect()
                        // Unexpected, but if it did open, holding it keeps the ACL
                        // link up while the stack decides about A2DP.
                        delay(2_000)
                    } catch (e: IOException) {
                        Log.d(TAG, "RFCOMM refused, as expected: ${e.message}")
                    } finally {
                        runCatching { socket.close() }
                    }
                }
                connected.await()
            }
        }

    /** Waits for A2DP to report [address] connected, or gives up. */
    private suspend fun awaitA2dpConnected(context: Context, address: String): Boolean =
        withTimeoutOrNull(LINK_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                lateinit var receiver: BroadcastReceiver

                fun finish(result: Boolean) {
                    if (done.compareAndSet(false, true)) {
                        runCatching { context.unregisterReceiver(receiver) }
                        if (cont.isActive) cont.resume(result)
                    }
                }

                receiver = object : BroadcastReceiver() {
                    @Suppress("DEPRECATION")
                    override fun onReceive(ctx: Context, intent: Intent) {
                        val device = intent
                            .getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (device?.address != address) return
                        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                            BluetoothProfile.STATE_CONNECTED -> finish(true)
                            BluetoothProfile.STATE_DISCONNECTED -> finish(false)
                        }
                    }
                }

                // A system broadcast, so it must be registered as exported.
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED),
                    ContextCompat.RECEIVER_EXPORTED,
                )
                cont.invokeOnCancellation {
                    if (done.compareAndSet(false, true)) {
                        runCatching { context.unregisterReceiver(receiver) }
                    }
                }
            }
        } ?: false

    /** Android's own audio-output picker, with Bluetooth settings as the fallback. */
    private fun openPicker(context: Context): Outcome {
        if (open(context, Intent(ACTION_MEDIA_OUTPUT))) return Outcome.PickerOpened
        // Before Android 10 there is no output panel, and some builds refuse it;
        // Bluetooth settings connects a paired device just as well.
        if (open(context, Intent(Settings.ACTION_BLUETOOTH_SETTINGS))) return Outcome.PickerOpened
        return Outcome.Failed("Could not open Android's audio output picker on this device.")
    }

    private fun open(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
