# N64 static recompilation on the AYN Thor

Short version: **N64Recomp cannot be built into this app, and there is no way
to run a recompiled N64 port on Android today.** The blocker is not effort on
our side — it is that the renderer these ports depend on has no Android
backend. This page records exactly what is and is not possible, so the question
does not have to be re-researched later.

## What N64Recomp actually is

It is easy to assume from the name that you feed it a `.z64` and get a native
game. That is not what it does.

[N64Recomp](https://github.com/N64Recomp/N64Recomp) is a **static recompiler**.
It reads an N64 binary and translates each MIPS instruction into a line of C —
`ctx->r4 = ADD32(ctx->r4, 0x20);` and so on. Its output is C source code, not a
program.

Three consequences follow, and each is a hard requirement:

**1. It needs an ELF with symbols, not a ROM.** The recompiler has to know where
every function starts and ends, and a raw cartridge dump does not carry that.
The project's own documentation is explicit: "the only way to provide the
required metadata is by passing an elf file to this tool." In practice that ELF
comes from a full decompilation project for the specific game. There is an
[open issue](https://github.com/N64Recomp/N64Recomp/issues/13) to accept a
symbol file instead, which would ease this, but it is not the state today.

**2. Its output must be compiled.** The generated C has to go through GCC,
Clang or MSVC and be linked against a runtime
([N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime)) that
emulates N64 hardware behaviour on a modern machine. This is a desktop
toolchain operation — it is not something a phone app performs on a file you
hand it.

**3. It needs a renderer.** Recompiled ports use
[RT64](https://github.com/rt64/rt64), which reimplements the N64's graphics
pipeline. RT64 targets **Windows, Linux and macOS** via D3D12 and Vulkan.

## Why this cannot live inside Thor Utils

Point 2 alone rules it out. Recompiling a game means running a C++ tool, then a
C compiler, then a linker, over a codebase of hundreds of thousands of
generated lines. That is a desktop build, not an on-device action, and no
amount of app-side work changes it.

Even as a build-server feature it would not help: the recompiler still needs a
decompilation ELF per game, which does not exist for most of the library and
cannot be derived from a ROM automatically.

## Pilotwings 64 specifically

This one is further along than most:

- **[Pilotwings64Decomp](https://github.com/gcsmith/Pilotwings64Decomp) is 100%
  decompiled**, completed in April 2026.
- **[Pilotwings64Recomp](https://github.com/gcsmith/Pilotwings64Recomp)
  already exists** and is described as playable, if unpolished.
- It builds for **x86-64 and ARM64**, and requires you to supply your own ROM
  dump — which is exactly the legal and practical model you would want.

So the game is recompiled, and the CPU architecture matches the Thor. The
remaining gap is the renderer: RT64 has no Android backend, and no N64Recomp
port of any game ships an Android build.

## What would actually be required to change that

Porting RT64 to Android is a real graphics-engineering project, not a
configuration change:

- Bring up RT64's Vulkan backend under Android's loader and validation rules,
  including its shader compilation path.
- Replace the desktop windowing and input layer with Android equivalents
  (`ANativeWindow`, SDL2's Android backend or a bespoke layer).
- Handle Android's storage model for the ROM, saves and mod files.
- Port RmlUi's rendering and font stack for the in-game menus.
- Deal with mobile GPU driver differences, which is where this kind of work
  usually stalls — RT64 leans on desktop driver behaviour.

That is upstream work benefiting every recompiled game, and it belongs in RT64
rather than in this app.

## What to do today

Play it in an emulator. Pilotwings 64 is a 1996 launch title and the Thor's
Snapdragon 8 Gen 2 runs it without effort:

- **Mupen64Plus FZ** (`org.mupen64plusae.v3.fzurita`) — the standalone choice.
  Use the GLideN64 video plugin.
- **RetroArch** with the Mupen64Plus-Next or ParaLLEl core, if you would rather
  keep everything under one frontend.

Sort your dump into `ROMs/n64/` with this app's sorter — a `.z64`, `.n64` or
`.v64` is identified with certainty from its byte order, so it files correctly
without any guesswork.

Thor Utils will recognise a recompiled native port and let you launch it on
either screen the moment one exists for Android. The catalog entry is the easy
part; the renderer is the work.

## Sources

- [N64Recomp](https://github.com/N64Recomp/N64Recomp) and its
  [README](https://github.com/N64Recomp/N64Recomp/blob/main/README.md)
- [N64ModernRuntime](https://github.com/N64Recomp/N64ModernRuntime)
- [RT64](https://github.com/rt64/rt64)
- [Pilotwings64Decomp](https://github.com/gcsmith/Pilotwings64Decomp) ·
  [decomp.dev progress](https://decomp.dev/gcsmith/Pilotwings64Decomp)
- [Pilotwings64Recomp](https://github.com/gcsmith/Pilotwings64Recomp)
- [Zelda64Recomp Android support issue](https://github.com/Zelda64Recomp/Zelda64Recomp/issues/45)
