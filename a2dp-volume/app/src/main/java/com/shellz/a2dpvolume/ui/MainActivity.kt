package com.shellz.a2dpvolume.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.shellz.a2dpvolume.LinkState
import com.shellz.a2dpvolume.R
import com.shellz.a2dpvolume.SessionState
import com.shellz.a2dpvolume.bluetooth.BluetoothPermissions
import com.shellz.a2dpvolume.bluetooth.SpeakerTakeover
import com.shellz.a2dpvolume.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var userDragging = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { !it }.keys
            if (denied.isEmpty() || BluetoothPermissions.hasConnect(this)) {
                viewModel.start()
            } else {
                showPermissionRationale()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.startButton.setOnClickListener { requestPermissionsThenStart() }
        binding.stopButton.setOnClickListener { viewModel.stop() }
        binding.takeoverButton.setOnClickListener { takeOverSpeaker() }
        binding.keepAliveSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) viewModel.setKeepAlive(checked)
        }

        binding.volumeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val value = progress + viewModel.state.value.minVolume
                    binding.volumeValue.text = getString(R.string.volume_fmt, value, viewModel.state.value.maxVolume)
                    viewModel.setVolume(value)
                }
            }
            override fun onStartTrackingTouch(bar: SeekBar) { userDragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) { userDragging = false }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Volume may have changed while we were in the background.
        if (!viewModel.state.value.serviceRunning) viewModel.refreshVolume()
    }

    private fun render(s: SessionState) {
        binding.statusText.text = when (s.linkState) {
            LinkState.IDLE -> getString(R.string.status_idle)
            LinkState.SEARCHING -> getString(R.string.status_searching)
            LinkState.CONNECTED -> getString(R.string.status_connected)
            LinkState.DISCONNECTED -> getString(R.string.status_disconnected)
            LinkState.BLUETOOTH_OFF -> getString(R.string.status_bt_off)
            LinkState.NO_PERMISSION -> getString(R.string.status_no_permission)
        }
        binding.speakerName.text = s.speakerName ?: getString(R.string.no_speaker)
        binding.routeText.isVisible = s.linkState == LinkState.CONNECTED
        binding.routeText.text = getString(
            if (s.routedToBluetooth) R.string.route_bt else R.string.route_pending
        )

        binding.startButton.isEnabled = !s.serviceRunning
        binding.stopButton.isEnabled = s.serviceRunning
        binding.keepAliveSwitch.isChecked = s.keepAliveEnabled

        val range = (s.maxVolume - s.minVolume).coerceAtLeast(1)
        binding.volumeSeek.max = range
        if (!userDragging) binding.volumeSeek.progress = s.volume - s.minVolume
        binding.volumeValue.text = getString(R.string.volume_fmt, s.volume, s.maxVolume)
    }

    private fun requestPermissionsThenStart() {
        val missing = BluetoothPermissions.missing(this)
        if (missing.isEmpty()) viewModel.start() else permissionLauncher.launch(missing.toTypedArray())
    }

    /**
     * Claims the soundbar from whatever device currently holds it.
     *
     * Nothing here reaches across to the other device — it cannot. The soundbar
     * accepts one Bluetooth audio connection at a time, so connecting this one
     * is what drops the other. Once it lands, Start gives us volume control the
     * normal way, because we are then the active source.
     *
     * The attempt can take several seconds while a link is raised, so the button
     * reports that it is working rather than appearing to have done nothing.
     */
    private fun takeOverSpeaker() {
        val missing = BluetoothPermissions.missing(this)
        if (!BluetoothPermissions.hasConnect(this)) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        lifecycleScope.launch {
            binding.takeoverButton.isEnabled = false
            binding.takeoverButton.setText(R.string.takeover_working)
            val result = SpeakerTakeover.takeOver(this@MainActivity)
            binding.takeoverButton.setText(R.string.takeover)
            binding.takeoverButton.isEnabled = true

            val message = when (result) {
                is SpeakerTakeover.Outcome.Connected ->
                    getString(R.string.takeover_connected, result.name)
                is SpeakerTakeover.Outcome.PickerOpened -> getString(R.string.takeover_opened)
                is SpeakerTakeover.Outcome.NotPairedYet -> getString(R.string.takeover_not_paired)
                is SpeakerTakeover.Outcome.Failed ->
                    getString(R.string.takeover_failed, result.reason)
            }
            Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            // We may now be the active source, which changes the volume stream.
            viewModel.refreshVolume()
        }
    }

    private fun showPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.perm_title)
            .setMessage(R.string.perm_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                Toast.makeText(this, R.string.status_no_permission, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
