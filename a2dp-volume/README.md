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
