# Thor Utils

A utility suite for the [AYN Thor](https://www.ayntec.com/products/ayn-thor) dual-screen
Android handheld: battery runtime projection, a second-screen stats HUD, a
per-display app launcher, and an automatic ROM sorter for ES-DE.

> **Status:** running on a physical AYN Thor. The core engine is complete and
> tested; see [What has and has not been verified](#what-has-and-has-not-been-verified)
> for what is covered by tests versus what has only been compiled.

## Install

Grab the latest APK from the [releases page](../../releases) — the rolling
`latest` release is rebuilt on every push to `main`.

| File | Use |
| --- | --- |
| `ThorUtils-<version>-<sha>.apk` | Normal install |
| `ThorUtils-<version>-<sha>-debug.apk` | Installs alongside the release build for testing |

## What it does

### 1. Battery runtime projection

A foreground service samples the battery and projects how much longer the
device will last, shown live in the notification shade — Android has no
third-party status bar, so an ongoing notification is the closest thing to a
task-bar readout.

The projection is a **time-weighted moving average over 1, 5 and 15 minute
windows**, deliberately mirroring the familiar `uptime` load averages. The
five-minute figure is the headline: fast enough to notice you launched a game,
stable enough not to flicker on every background task.

Two measurements of drain are available and they fail in opposite ways. The
fuel gauge's `CURRENT_NOW` reacts within seconds but is noisy, and its sign
convention is not standardised — Android documents positive-when-charging and a
good number of devices do the opposite. The reported level is unambiguous but
only moves in whole percentage points, which on a 6000 mAh pack can be twenty
minutes apart. Thor Utils prefers current, **learns the device's actual sign
convention** by watching what the hardware reports while charging, and falls
back to level deltas when current is unavailable.

When the measured rate is too small to mean anything, no projection is shown
rather than a fabricated one — a 0.05 %/h reading would otherwise claim eighty
days of runtime.

### 2. Dual-screen launcher

Lists installed apps and gives each one a button per physical display, so an
emulator can go straight to the bottom panel — or two instances can run, one on
each screen.

This uses `ActivityOptions.setLaunchDisplayId`. It is not a guarantee: an app
declaring `resizeableActivity="false"`, or a vendor policy pinning apps to the
main panel, will land on the default display anyway. Thor Utils checks whether
the target actually supports it and **tells you when Android is likely to have
overridden the request** instead of silently doing the wrong thing.

Known emulators are recognised and annotated with their dual-screen capability.

### 3. Second-screen stats HUD

An always-on overlay showing CPU, GPU, memory, battery, temperature and
storage, sized for the 3.92" bottom panel.

**On the focus problem:** the HUD window is created with
`FLAG_NOT_FOCUSABLE`. A non-focusable window still receives touch events but
never takes input focus — so tapping the HUD leaves the game on the top screen
focused. Focus is never taken, which means it never has to be given back. That
is the actual fix for "every time I touch the bottom screen I have to touch the
top screen again".

A *focus priority display* setting exists as a second line of defence, for when
something else — a notification, a system dialog, an emulator grabbing focus on
launch — moves focus to the bottom screen. It relies on an optional
accessibility service and is off until you enable it. Its limits are real:
there is no public API to say "make display N focused", so what it does is
re-front the last app seen on your priority display, which works for ordinary
activities and does nothing for anything it cannot see.

Statistics come from `/proc` and `/sys`. Vendors move, rename and lock these
files down freely; anything unreadable is simply omitted from the HUD rather
than shown as a zero.

### 4. ROM sorter

Point it at a folder of backed-up games and it files them into the ES-DE
directory structure.

Identification weighs four independent kinds of evidence and the strongest
wins:

1. **The bytes of the ROM** — iNES magic, the three N64 byte orders, the Game
   Boy boot logo and CGB flag, GBA versus NDS by logo offset, SNES header
   checksum pairs, GameCube and Wii disc magics, Sega console name strings,
   ISO 9660 volume descriptors.
2. **An extension owned by exactly one system.**
3. **An enclosing folder name** — `Sega Genesis`, `PS1`, `nds`.
4. **A console tag in the filename** — `[SNES]`, `(PSX)`.

Content beats names, because names in a real backup are wrong often enough to
matter. Archives are opened and judged on what is inside them, not on the
archive's name.

**A file is only ever moved if something proved it is a console game.** Only
the bytes and console-exclusive extensions count as proof. A folder called
`PS1` can say *which* console a game belongs to, but it can never establish
that a file is a game — otherwise an APK sitting in that folder gets treated as
a PlayStation title. Anything unproven is left exactly where it is: not moved,
not copied, not renamed.

Formats that cannot name their console are handled the same way. A CHD header
records "compressed disc" and nothing else, so folder context names the system
— but only because a `.chd` is always a console dump. A bare `.iso` or `.bin`
gets no such treatment, since it is as likely to be an OS image or a disk
backup.

App packages, installers, media and documents are refused before identification
even runs. An APK is a ZIP, so without that it would be opened as an archive
and could be identified from some asset buried inside it.

Safety, because a library takes years to assemble and seconds to shred:

- **Nothing unproven is touched.** Files that are not demonstrably console
  games produce no work at all.
- **Every move is journalled and reversible.** There is an *Undo last sort*
  button, and the record is written as each file lands, so even an interrupted
  run can be put back.
- **Plan first, apply second.** Every decision is listed with its reason before
  a byte moves.
- **Dry run by default.**
- **Nothing is ever overwritten** — a name collision gets a ` (2)` suffix,
  since two ROMs sharing a filename are usually different regional dumps.
- **Multi-file games move together.** A `.cue` without its `.bin` tracks
  produces a library that looks sorted and does not boot.
- **Archive entries cannot escape the destination** (Zip Slip).
- **Cross-filesystem moves are verified** before the original is removed.
- **Re-running is idempotent** — a file already filed at the same size is
  recognised rather than duplicated with a ` (2)` suffix.

### 5. Dual-screen emulator guide

Built into the app and written up in
**[docs/dual-screen-emulators.md](docs/dual-screen-emulators.md)**. Installed
emulators are marked, so the list works as a checklist rather than an article.

See also **[docs/n64-recompilation.md](docs/n64-recompilation.md)** for why
N64Recomp-based native ports cannot run on Android yet, and what would have to
change.

## Appearance

A glass theme in the spirit of Windows XP and Vista: translucent panels, a
specular sheen across the top of each surface, and a saturated gradient
underneath. The whole palette is generated from **one hue slider**, so changing
the base colour retints the entire UI rather than just an accent. Saturation
and opacity are adjustable, and backdrop blur is used where the platform
supports it (Android 12+).

## Building

```bash
./gradlew :core:test -PthorCoreOnly   # core logic, no Android SDK needed
./gradlew :app:assembleRelease        # APK
```

`-PthorCoreOnly` drops the Android module from the build entirely. That is not
a convenience flag — the core module is deliberately free of Android types so
that the parts with real logic in them can be built and tested on any JVM,
including in environments with no Android SDK.

CI runs the core tests first, without an SDK, then assembles both APKs.

## What has and has not been verified

Being straight about this, because it affects how much you should trust it:

**Verified** — 71 unit tests covering signature detection against synthetic ROM
headers built to the real hardware offsets, sort planning and execution against
a temporary filesystem, collision and path-traversal handling, idempotent
re-runs, the battery estimator's projection maths and sign-convention learning,
and the `/proc` parsers.

**Built but not run on hardware** — every Android-side behaviour: the overlay's
focus properties, second-display launching, the notification readout, and
whether the Thor's kernel exposes the GPU counters the HUD looks for. These
compile and are written against documented platform behaviour, but no physical
device has been in the loop.

The most likely things to need adjustment on a real Thor are the sysfs paths
for GPU load and the exact display ids, both of which are surfaced in the UI to
make diagnosing them easy.

## Permissions

| Permission | Why |
| --- | --- |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Continuous battery measurement that must survive while a game runs |
| `SYSTEM_ALERT_WINDOW` | Drawing the HUD over other apps |
| `MANAGE_EXTERNAL_STORAGE` | Bulk-moving thousands of ROMs; the per-file picker cannot do it |
| `QUERY_ALL_PACKAGES` | Listing installed emulators in the launcher |
| `POST_NOTIFICATIONS` | The battery readout |

The accessibility service is optional and used only for focus return.

## Licence

MIT — see [LICENSE](LICENSE).
