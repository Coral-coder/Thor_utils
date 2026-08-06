# N64 static recompilation on the AYN Thor

Goal: play a Pilotwings 64 cartridge dump as a native recompiled build on the
Thor, rather than under emulation.

This page is the working record of that port. It corrects an earlier assessment
in this repository that said RT64 has no Android support — at the level that
matters most, the Vulkan backend, **it already does**.

## What N64Recomp actually is

It is easy to assume from the name that you feed it a `.z64` and get a native
game. That is not what it does.

[N64Recomp](https://github.com/N64Recomp/N64Recomp) is a **static recompiler**.
It reads an N64 binary and translates each MIPS instruction into a line of C —
`ctx->r4 = ADD32(ctx->r4, 0x20);` and so on. Its output is C source code.

Two consequences are permanent and shape everything below:

**It needs an ELF with symbols, not a ROM.** The recompiler has to know where
every function starts and ends, and a raw cartridge dump does not carry that.
The project is explicit: "the only way to provide the required metadata is by
passing an elf file to this tool." That ELF comes from a decompilation project
for the specific game.

**Recompilation is a build step, not a runtime action.** The generated C must be
compiled and linked against a runtime
([N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime)). This
happens on a build machine. No app on the handheld can do it to a file you hand
it, which is why this work lives in CI and produces an APK, rather than being a
feature inside Thor Utils.

## Why Pilotwings 64 is the right target

- **[Pilotwings64Decomp](https://github.com/gcsmith/Pilotwings64Decomp) is 100%
  decompiled** as of April 2026, so the required ELF exists.
- **[Pilotwings64Recomp](https://github.com/gcsmith/Pilotwings64Recomp)** already
  exists, is playable, and **already builds for ARM64** — the Thor's
  architecture.
- It ships no game assets and requires you to supply your own dump.

So the game is done and the CPU target matches. What is missing is Android.

## Where Android support actually stands

Established by reading the source rather than the release notes:

| Layer | Android state |
| --- | --- |
| **plume** (render hardware interface) | **Implemented.** `VK_USE_PLATFORM_ANDROID_KHR`, `VK_KHR_android_surface` in the instance extensions, `RenderWindow` typedef'd to `ANativeWindow*`, and a real `vkCreateAndroidSurfaceKHR` path. |
| **RT64** (N64 renderer) | **Stubbed.** `CMakeLists.txt` sets `ANDROID_PLATFORM`/`arm64-v8a` and guards `if (NOT ANDROID)`, but `rt64_application_window.cpp` has two `static_assert(false && "Android unimplemented")` branches for window bounds and native handle retrieval. |
| **N64ModernRuntime** | Not yet assessed. |
| **Pilotwings64Recomp** | Desktop shell only; needs an Android application layer. |

This is a much better starting position than "no Android support". The hardest
and least portable part of a graphics port — the Vulkan backend and its
swapchain and surface handling — is already written and maintained upstream by
people who know that code. The stub in RT64 is a window-handle problem, not a
renderer problem.

## Plan

Staged so each step either compiles or does not, and progress is measurable
instead of one all-or-nothing attempt. `n64/build-android.sh` runs these, and
the `N64 Android port` workflow runs it in CI on every change under `n64/`.

1. **plume for arm64-v8a.** Expected to pass unpatched. Establishes the NDK
   toolchain and proves the Vulkan backend really is Android-clean.
2. **RT64 for arm64-v8a.** Requires implementing the two stubbed branches:
   window bounds from the Android display metrics, and returning the
   `ANativeWindow*` that plume wants. This is where our first real code goes.
3. **N64ModernRuntime for arm64-v8a.** Assess and port its platform layer.
4. **Application shell.** An Activity with a `SurfaceView`, handing its
   `Surface` to `ANativeWindow_fromSurface`, plus gamepad and touch input.
5. **Pilotwings64Recomp itself.** Run the recompiler on the decomp ELF on the
   build host, compile the generated C for arm64, link the above, package an
   APK that asks the user for their own ROM.

Steps 1–2 are the ones that decide whether this is viable at all. Steps 4–5 are
substantial but conventional Android work.

## Honest risks

- **Mobile GPU drivers.** RT64 leans on desktop Vulkan behaviour, and
  Qualcomm's Android blob is the weakest link — it lags on extensions and has
  its own quirks. **Largely mitigated:** see Turnip below. What remains is that
  the mitigation itself is unproven on this device.
- **Shader pipeline.** RT64 compiles HLSL with DXC to SPIR-V. That is a host
  build-time step, so it should be fine, but the ray-tracing and
  descriptor-indexing extensions it requests may not all be available on
  Adreno.
- **Upstream churn.** These repositories move quickly. The build script pins
  refs so a failure can be attributed to our changes rather than a drifting
  dependency.
- **This may not finish.** Steps 1–3 are tractable. Step 5 depends on how much
  desktop assumption is baked into the recomp project's shell.

## Turnip: replacing Qualcomm's driver

The biggest risk in this port was always the Adreno driver. It is also the one
with the best available answer.

[Mesa's Turnip](https://docs.mesa3d.org/drivers/freedreno.html) is an
open-source Vulkan driver for Adreno that is considerably more conformant than
Qualcomm's, and [libadrenotools](https://github.com/bylaws/libadrenotools) can
load it for a single application **without root**. This is the same mechanism
emulators like NetherSX2 and Citron use, and it is well proven on handhelds.

The integration point turned out to be clean. plume loads Vulkan through
[volk](https://github.com/zeux/volk), which offers `volkInitializeCustom` —
give it a `vkGetInstanceProcAddr` and every entry point it subsequently
resolves comes from that driver instead of the system one.

So the wiring is:

1. `n64/patches/plume-01-custom-vulkan-loader.patch` adds
   `plume::setCustomVulkanLoader()`. plume gains no dependency on adrenotools;
   it only learns to accept a loader from whoever has one.
2. `n64/android/turnip_loader.cpp` does the Android-specific part —
   `adrenotools_open_libvulkan()`, `dlsym("vkGetInstanceProcAddr")`, hand it to
   plume.
3. Failure is never fatal. A missing, unreadable or broken driver logs why and
   leaves plume on the system driver, so the game still starts.

**Recommended driver:** [MrPurple666/purple-turnip](https://github.com/MrPurple666/purple-turnip)
**T26**, which carries a Snapdragon 8 Gen 2 specific fix
(`enable_tp_ubwc_flag_hint`) — the Thor's exact SoC. Drop the extracted
`libvulkan_freedreno.so` into the driver directory and it is picked up at
startup.

Other sources worth knowing: [K11MCH1/AdrenoToolsDrivers](https://github.com/K11MCH1/AdrenoToolsDrivers/releases)
and the [Turnip Tracker](https://skossan.github.io/turniptracker/).

## Meanwhile

Pilotwings 64 runs fine under emulation on an 8 Gen 2 today — **Mupen64Plus FZ**
(`org.mupen64plusae.v3.fzurita`) with the GLideN64 plugin. Sort the dump into
`ROMs/n64/`; `.z64`/`.n64`/`.v64` are identified with certainty from byte order.

## Sources

- [N64Recomp](https://github.com/N64Recomp/N64Recomp) ·
  [README](https://github.com/N64Recomp/N64Recomp/blob/main/README.md) ·
  [symbol-file issue](https://github.com/N64Recomp/N64Recomp/issues/13)
- [N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime)
- [RT64](https://github.com/rt64/rt64) · [plume](https://github.com/renderbag/plume)
- [Pilotwings64Decomp](https://github.com/gcsmith/Pilotwings64Decomp) ·
  [progress](https://decomp.dev/gcsmith/Pilotwings64Decomp)
- [Pilotwings64Recomp](https://github.com/gcsmith/Pilotwings64Recomp)
