package com.shellz.a2dpvolume.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Takes the speaker's Bluetooth link away from whatever device currently holds it.
 *
 * There is no way to reach across and disconnect someone else's device, and there
 * does not need to be. A soundbar like the Vizio V20 holds exactly one Bluetooth
 * audio connection at a time, so connecting *this* device is what drops the other
 * one. Takeover is a side effect of connecting, not a separate operation.
 *
 * The connection itself goes through Android's media-output panel rather than
 * `BluetoothA2dp.connect()`. That method is hidden behind the signature-level
 * BLUETOOTH_PRIVILEGED permission, so calling it by reflection throws on any
 * current release — it would be a hack that silently never works. The output
 * panel is public API, needs no privilege, and connects a paired-but-idle device
 * when tapped. It costs one extra tap and is guaranteed to exist.
 */
object SpeakerTakeover {

    /** A paired audio sink the user could take over. */
    data class PairedSink(
        val name: String,
        val address: String,
        val isConnected: Boolean,
    )

    sealed interface Result {
        /** The picker is open; the user taps the speaker to finish the takeover. */
        data object PickerOpened : Result

        /** Nothing could be done, with a reason worth showing. */
        data class Failed(val reason: String) : Result
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

    @SuppressLint("MissingPermission")
    private fun isAudioSink(device: BluetoothDevice): Boolean =
        runCatching { device.bluetoothClass?.majorDeviceClass }.getOrNull() ==
            BluetoothClass.Device.Major.AUDIO_VIDEO

    /**
     * Opens the system audio-output picker so the speaker can be claimed.
     *
     * Never throws: a failure to open comes back as [Result.Failed] with
     * something worth showing the user.
     */
    fun takeOver(context: Context): Result {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_MEDIA_OUTPUT))
            }
            // Before Android 10 there is no output panel, so the Bluetooth
            // settings screen is the closest equivalent.
            add(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }

        intents.forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) {
                return Result.PickerOpened
            }
        }
        return Result.Failed("Could not open Android's audio output picker on this device.")
    }
}
