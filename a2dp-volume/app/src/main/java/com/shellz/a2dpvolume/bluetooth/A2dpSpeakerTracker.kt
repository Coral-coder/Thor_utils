package com.shellz.a2dpvolume.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Finds the A2DP sink (speaker / soundbar) that is currently connected and reports
 * connect, disconnect and reconnect events.
 *
 * Android does not let normal apps open or hold an A2DP link themselves (BluetoothA2dp.connect
 * is a privileged system API). What we can do is: hold the A2DP profile proxy, react instantly
 * when the system reconnects the speaker, and keep audio flowing so the speaker does not
 * auto-standby (see KeepAlivePlayer).
 */
class A2dpSpeakerTracker(
    private val context: Context,
    private val listener: Listener,
    /** Name fragment to prefer when several sinks are connected, e.g. "VIZIO". */
    private val preferredNameHint: String = "VIZIO",
) {
    interface Listener {
        fun onSpeakerConnected(name: String, address: String)
        fun onSpeakerDisconnected(lastName: String?)
        fun onBluetoothOff()
        fun onSearching()
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var a2dp: BluetoothA2dp? = null
    private var current: BluetoothDevice? = null
    private var registered = false

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = proxy as BluetoothA2dp
                refresh()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.A2DP) a2dp = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                    Log.d(TAG, "A2DP connection state -> $state")
                    if (state == BluetoothProfile.STATE_CONNECTED ||
                        state == BluetoothProfile.STATE_DISCONNECTED
                    ) refresh()
                }
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                        BluetoothAdapter.STATE_OFF -> {
                            current = null
                            listener.onBluetoothOff()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            // Proxy is lost when the radio goes off; get a fresh one.
                            requestProxy()
                            listener.onSearching()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> refresh()
            }
        }
    }

    fun start() {
        if (adapter == null || !adapter.isEnabled) {
            listener.onBluetoothOff()
        } else {
            listener.onSearching()
            requestProxy()
        }
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            // System broadcasts; exported so the platform can always deliver them.
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            registered = true
        }
    }

    fun stop() {
        if (registered) {
            runCatching { context.unregisterReceiver(receiver) }
            registered = false
        }
        a2dp?.let { adapter?.closeProfileProxy(BluetoothProfile.A2DP, it) }
        a2dp = null
        current = null
    }

    private fun requestProxy() {
        if (!BluetoothPermissions.hasConnect(context)) return
        adapter?.getProfileProxy(context, profileListener, BluetoothProfile.A2DP)
    }

    /** Re-evaluate which sink is connected and notify on any change. */
    @SuppressLint("MissingPermission") // checked via BluetoothPermissions.hasConnect
    fun refresh() {
        if (!BluetoothPermissions.hasConnect(context)) return
        val proxy = a2dp ?: return
        val connected = proxy.connectedDevices
        val chosen = connected.firstOrNull { safeName(it).contains(preferredNameHint, ignoreCase = true) }
            ?: connected.firstOrNull()

        val previous = current
        current = chosen
        when {
            chosen != null && chosen.address != previous?.address ->
                listener.onSpeakerConnected(safeName(chosen), chosen.address)
            chosen == null && previous != null ->
                listener.onSpeakerDisconnected(safeName(previous))
            chosen == null ->
                listener.onSearching()
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeName(device: BluetoothDevice): String {
        if (!BluetoothPermissions.hasConnect(context)) return device.address
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias else null
        return alias ?: device.name ?: device.address
    }

    companion object {
        private const val TAG = "A2dpSpeakerTracker"
    }
}
