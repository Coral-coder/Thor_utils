# Dual-screen emulators on the AYN Thor

The Thor pairs a 6" 120 Hz AMOLED main panel with a 3.92" AMOLED bottom panel,
running Android 13 on a Snapdragon 8 Gen 2 (the Lite model uses an SD865). Both
panels are exposed to Android as ordinary displays, which is what makes
independent per-screen launching possible at all.

## First, the honest framing

Only three systems ever shipped games designed for two screens: **Nintendo DS**,
**Nintendo 3DS** and **Wii U**. Everything else in your library is
single-screen by nature. For those, the bottom panel is better spent on a stats
HUD, a guide, chat, or a second app — not on a stretched or letterboxed copy of
the same picture.

So the recommendations split in two: emulators that genuinely drive both
panels, and the best emulator per system where the second screen is simply
free real estate.

## Genuinely dual-screen

### melonDS — Nintendo DS — *the reference choice*

**Application id:** `me.magnum.melonds` · **Dual screen: native**

The one that needs no setup. It detects the second panel and puts the DS touch
screen there by itself. If the screens come up mirrored or swapped, the layout
control is under **Settings → Video**.

Accuracy is good and DS is light enough on an 8 Gen 2 that performance is a
non-issue.

### MelonDualDS — Nintendo DS

**Dual screen: native**

A community melonDS fork built specifically for dual-screen handhelds. Worth
trying if stock melonDS puts the screens on the wrong panels on your unit. It
is the same emulator core, so save files carry across.

### DraStic — Nintendo DS

**Application id:** `com.dsemu.drastic` · **Dual screen: needs a setting**

The fastest DS option and free now. Second-screen behaviour varies by build —
if only one panel lights up, set the screen layout manually. Less accurate than
melonDS; keep it for the handful of games melonDS struggles with.

### Azahar — Nintendo 3DS — *the current 3DS pick*

**Application id:** `io.github.lime3ds.android` · **Dual screen: needs a setting**

Azahar is what Citra became: PabloMK7's Citra fork merged with Lime3DS. It is
the accuracy-first choice and the one to start with.

Two things worth knowing:

- Prefer the **vanilla build over the Play Store build**. The vanilla build
  uses a faster file-management method that Play Store policy does not permit,
  and on a large 3DS library the difference is noticeable.
- Set the **screen layout to separate windows** so the lower screen is fed to
  the bottom panel rather than being composited into one image.

### Citra MMJ — Nintendo 3DS

**Dual screen: layout workaround**

Older and less accurate, but faster on demanding titles. Useful as a fallback
for the specific games Azahar cannot hold 60 fps in. Not worth making your
default.

### Wii U

There is no practical Wii U emulator on Android — Cemu is desktop-only. The Wii
U GamePad's second screen has no Android equivalent, so treat Wii U as
unsupported on this device regardless of what the system lists claim.

## Best-in-class for single-screen systems

| System | Emulator | Application id | Notes |
| --- | --- | --- | --- |
| GameCube, Wii | Dolphin | `org.dolphinemu.dolphinemu` | Strong on the 8 Gen 2 |
| PSP | PPSSPP | `org.ppsspp.ppsspp` | Most of the library runs at 3× resolution |
| PlayStation | DuckStation | `com.github.stenzek.duckstation` | Use CHD images to save space |
| PS2 | NetherSX2 | `xyz.aethersx2.android` | AetherSX2 is discontinued; NetherSX2 is the patched build people run, sharing the same id |
| Dreamcast, NAOMI | Flycast | `com.flycast.emulator` | |
| Dreamcast | Redream | `io.recompiled.redream` | Cleaner upscaler; premium unlock for high internal resolutions |
| PS Vita | Vita3K | `org.vita3k.emulator` | Compatibility still patchy — check before committing a library |
| Everything 2D | RetroArch | `com.retroarch.aarch64` | Its melonDS core can target the second panel, but the standalone app handles the Thor's screens better |

**Frontend:** ES-DE (`org.es_de.frontend`) is what this project's ROM sorter
targets. Point it at the same ROMs root you sort into.

## A note on the application ids

`me.magnum.melonds`, `io.github.lime3ds.android` and `org.es_de.frontend` are
confirmed against store listings. The rest are the widely used ids but were not
individually verified while this was written — emulator forks change their ids
freely.

This matters for the launcher, so it does not depend on the list being
right: installed packages are matched by id *and* by visible name, which
catches repackaged builds the catalogue has never seen. If an emulator you have
installed is not being recognised, that is a catalogue gap worth reporting, not
a functional break.

## Sources

- [AYN Thor product page](https://www.ayntec.com/products/ayn-thor)
- [Retro Game Corps — dual-screen Android handheld guide](https://retrogamecorps.com/2025/10/27/dual-screen-android-handheld-guide/)
- [DROIX — AYN Thor dual screen gaming](https://droix.net/blogs/ayn-thor-dual-screen-gaming-handheld/)
- [Azahar Emulator](https://azahar-emu.org/)
- [melonDS Android](https://github.com/rafaelvcaetano/melonDS-android)
