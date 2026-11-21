# A2DP Volume

A small Kotlin app that finds the Bluetooth speaker your phone is streaming to (A2DP sink, e.g. a Vizio soundbar), keeps the link from going to sleep, and gives you a volume slider plus notification buttons that work while the app is in the background.

- minSdk 21, targetSdk 33, compileSdk 33
- Kotlin 1.9.24, AGP 8.5.2, Gradle 8.7, JDK 17
- Jetpack: AppCompat, Activity Result API, ViewModel, Lifecycle (`LifecycleService`, `repeatOnLifecycle`), StateFlow, ViewBinding, ConstraintLayout, Material 3

## What it actually does (and what Android won't allow)

Android does not let a normal app intercept or re-route another app's Bluetooth audio stream, and `BluetoothA2dp.connect()` is a privileged system API. So the app works with what the platform exposes:

| Requirement | How it's done |
|---|---|
| Find the connected speaker | `BluetoothAdapter.getProfileProxy(A2DP)` then `BluetoothA2dp.connectedDevices`. Prefers a device whose name contains "VIZIO" if several are connected. |
| Keep the connection alive | Foreground service holds the A2DP proxy and (optionally) plays an inaudible ±1 LSB dither stream through its own `AudioTrack`, which stops speakers from auto-standby and dropping the link. |
| Route a stream and control volume | Media (`STREAM_MUSIC`) is what Android sends to A2DP. The slider calls `AudioManager.setStreamVolume(STREAM_MUSIC)`. With AVRCP absolute volume (most phones and Vizio bars) this moves the speaker's own volume. The app's own track gain is exposed via `AudioTrack.setVolume` in `KeepAlivePlayer.gain`. |
| Disconnect / reconnect | Listens for `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED`, `ACTION_ACL_DISCONNECTED` and adapter on/off. On reconnect it waits for media routing to land on the speaker, then restores the last volume you set. |
| Background | `A2dpVolumeService` is a foreground service (`connectedDevice` type) with Vol−, Vol+ and Stop buttons in its notification. |

If you truly need to capture audio from other apps, that is `AudioPlaybackCaptureConfiguration` with MediaProjection (Android 10+ only, needs a screen-capture consent prompt, and apps can opt out). It isn't included because minSdk is 21 and it would double-play the audio.

## Taking the speaker from another device

A soundbar such as the Vizio V20 holds **one** Bluetooth audio connection at a
time. So there is no need to disconnect the device that currently has it, and
no way to: an app cannot reach across and command someone else's phone or the
bar itself. Connecting *this* device is what drops the other one — takeover is
a side effect of connecting.

**Take the speaker** tries two mechanisms, in order:

1. **Raise the link directly.** The app opens an outbound RFCOMM socket to the
   paired bar. That is public API needing only `BLUETOOTH_CONNECT`, and the
   baseband (ACL) link it brings up is a real connection we initiated. Android's
   stack generally carries A2DP across an ACL link to a bonded device whose
   profile policy allows it — the same path "tap the device in Bluetooth
   settings" takes. The socket itself is expected to be refused, because a
   speaker does not serve Serial Port; the link is the point, not the channel.
   Success is therefore judged by watching for the A2DP connection broadcast,
   never by whether the socket opened.
2. **Android's audio-output panel**, if the link did not come up within eight
   seconds. Tapping the bar there connects it here and boots whatever had it.

Step 1 is only attempted when the target is unambiguous — a single paired sink,
or one whose name matches the hint. With several paired sinks and no match it
goes straight to the panel, because connecting the wrong device is worse than a
tap.

Then **Start**, and volume control works normally, because this device is now
the active source.

### Why not scan for the bar first?

A Bluetooth scan does not help, for two independent reasons.

A speaker that is connected to another device has normally stopped inquiry-scan
responses, so discovery will not see it at all. And even when discovery does see
a device, it hands back a `BluetoothDevice` — exactly what `getRemoteDevice(mac)`
or the bonded-device list already gives for free. Finding the speaker was never
the obstacle; *initiating an A2DP connection* is, and that is gated on a
permission no third-party app can hold. Scanning changes nothing about which
methods are callable.

Scanning is useful for one thing only: pairing a bar that is not yet bonded, and
that needs it in pairing mode anyway, which is what Bluetooth settings already
does well.

### Does the speaker need to be in pairing mode?

Only once, and only if it is not already paired with *this* device.

Pairing and connecting are different operations. Pairing exchanges keys and does
need the bar in pairing mode — on a V20, hold its Bluetooth button for three
seconds. Connecting re-links two devices that already hold each other's keys and
needs nothing from the bar at all; it does not even need to be idle.

So if you previously removed the pairing here to let another device connect, you
have to pair once more. After that, taking the speaker is just the button, no
matter what is currently streaming to it. The app checks which case you are in
and sends you to Bluetooth settings with an explanation rather than opening a
picker that cannot list an unpaired speaker.

Why the panel rather than connecting directly: `BluetoothA2dp.connect()` is
hidden behind the signature-level `BLUETOOTH_PRIVILEGED` permission. Reaching
it by reflection throws on current Android, so wiring it up would be a hack
that silently never works. The output panel is public API, needs no privilege,
and costs one extra tap.

This is also why controlling the bar's volume *while another device streams to
it* is not possible over Bluetooth. AVRCP volume commands come from the active
A2DP source; a second, non-streaming device's commands are firmware-dependent
and usually ignored. You either hold the link or you don't.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/com/shellz/a2dpvolume/
    SessionRepository.kt          shared StateFlow state (service writes, UI reads)
    bluetooth/
      A2dpSpeakerTracker.kt       profile proxy + connect/disconnect broadcasts
      BluetoothPermissions.kt     runtime permission sets per API level
    audio/
      StreamVolumeController.kt   AudioManager wrapper, volume observer, A2DP route check
      KeepAlivePlayer.kt          AudioTrack keep-alive stream with gain control
    service/
      A2dpVolumeService.kt        foreground service + notification actions
    ui/
      MainActivity.kt             permissions, SeekBar, Start/Stop, speaker name
      MainViewModel.kt
  res/layout/activity_main.xml
```

## Build

### Android Studio
1. File > Open, pick this folder. Let Gradle sync (it downloads Gradle 8.7, AGP and the AndroidX libraries).
2. If prompted, set Gradle JDK to 17 or the bundled JBR (Settings > Build Tools > Gradle).
3. Build > Build App Bundle(s) / APK(s) > Build APK(s).
4. APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

### Command line
```bash
# needs ANDROID_HOME (or local.properties with sdk.dir) and JDK 17
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

For a release build, add a `signingConfigs` block (or use Build > Generate Signed Bundle / APK) and run `./gradlew assembleRelease`.

## Usage

1. Pair and connect the speaker in Android's Bluetooth settings first.
2. Open the app and tap **Start**. On Android 12+ allow **Nearby devices**; on 13+ allow notifications.
3. The card shows the speaker name and whether media audio is routed to it.
4. Drag the slider to change volume. Leave the app and use the notification's Vol− / Vol+ / Stop.
5. **Keep speaker awake** streams inaudible audio so the speaker doesn't time out. Turn it off if you only want volume control.
6. If the speaker drops, the status switches to "Waiting for it to come back". When Android reconnects it, your volume is put back.
7. Tap **Stop** (in the app or notification) to end the session.

## Notes

- If the slider moves but the speaker doesn't get louder, your phone may have absolute volume disabled (Developer options > "Disable absolute volume"). Then phone and speaker volumes are separate, and only the phone side moves.
- Some ROMs aggressively kill foreground services. Exempt the app from battery optimization if sessions stop on their own.
- On Android 14+ the manifest already declares `FOREGROUND_SERVICE_CONNECTED_DEVICE`; bump `compileSdk`/`targetSdk` and dependency versions together if you raise the target.
