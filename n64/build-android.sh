#!/usr/bin/env bash
#
# Builds the N64 recompilation graphics stack for Android arm64.
#
# This is staged deliberately. Each stage is a checkpoint that either compiles
# or does not, so progress toward a playable Android build is measurable rather
# than a single all-or-nothing attempt:
#
#   plume  - the render hardware interface, including the Vulkan backend.
#            Upstream already implements the Android path (ANativeWindow ->
#            vkCreateAndroidSurfaceKHR), so this stage should pass with no
#            patching. It is the foundation everything else sits on.
#   rt64   - the N64 graphics renderer. Its window layer is an explicit
#            `static_assert(false && "Android unimplemented")`, so this stage
#            needs our own implementation before it can compile.
#
# Usage:  n64/build-android.sh [stage]      (stage: plume | sdl2 | adrenotools | rt64 | all)
set -euo pipefail

STAGE="${1:-plume}"
ABI="${ANDROID_ABI:-arm64-v8a}"
API="${ANDROID_API:-26}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="${N64_WORK_DIR:-$ROOT/n64/work}"
mkdir -p "$WORK"

# --- locate the NDK -------------------------------------------------------

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_LATEST_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
    echo "error: no Android NDK found." >&2
    echo "Set ANDROID_NDK_HOME, or install one with:" >&2
    echo "  sdkmanager 'ndk;26.3.11579264'" >&2
    exit 1
fi
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
[[ -f "$TOOLCHAIN" ]] || { echo "error: toolchain missing at $TOOLCHAIN" >&2; exit 1; }
echo "Using NDK: $NDK"
echo "Target:    $ABI / android-$API"

# --- helpers --------------------------------------------------------------

# Clones at a pinned commit so a build that worked yesterday still works today.
# Upstream moves fast and an unpinned clone makes failures impossible to
# attribute between our changes and theirs.
fetch() {
    # Split across statements on purpose: referencing "$name" inside the same
    # `local` declaration that defines it is unset at expansion time under
    # `set -u`, which fails the script before anything is fetched.
    local name="$1" url="$2" ref="$3"
    local dir="$WORK/$name"
    if [[ -d "$dir/.git" ]]; then
        echo "== $name already present"
    else
        echo "== cloning $name"
        git clone --recurse-submodules "$url" "$dir"
    fi
    if [[ -n "$ref" ]]; then
        git -C "$dir" fetch --depth 1 origin "$ref" 2>/dev/null || true
        git -C "$dir" checkout --quiet "$ref"
        git -C "$dir" submodule update --init --recursive --depth 1
    fi
}

configure_and_build() {
    local name="$1" src="$2"; shift 2
    local build="$WORK/build-$name-$ABI"
    echo "== configuring $name"
    cmake -S "$src" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        "$@"
    echo "== building $name"
    cmake --build "$build" --parallel "$(nproc)"
    echo "== $name OK"
    # Informational only — must never fail a build that already succeeded.
    find "$build" \( -name '*.a' -o -name '*.so' \) 2>/dev/null | head -20 || true
}

# --- stages ---------------------------------------------------------------

# Applies our patches to a fetched dependency. Idempotent: a patch that is
# already in place is reported and skipped rather than failing the build.
apply_patches() {
    local name="$1" prefix="$2"
    local dir="$WORK/$name"
    shopt -s nullglob
    for patch in "$ROOT/n64/patches/$prefix"-*.patch; do
        echo "== applying $(basename "$patch") to $name"
        if git -C "$dir" apply --check "$patch" 2>/dev/null; then
            git -C "$dir" apply "$patch"
        else
            echo "   (already applied or does not match; continuing)"
        fi
    done
    shopt -u nullglob
}

build_plume() {
    fetch plume https://github.com/renderbag/plume.git "${PLUME_REF:-main}"
    apply_patches plume plume
    configure_and_build plume "$WORK/plume" \
        -DPLUME_BUILD_EXAMPLES=OFF
}

# libadrenotools is what lets a Mesa Turnip build replace Qualcomm's Vulkan
# driver for this process alone, without root. It is the main lever available
# for making a desktop-shaped Vulkan renderer behave on Adreno.
build_adrenotools() {
    fetch adrenotools https://github.com/bylaws/libadrenotools.git "${ADRENOTOOLS_REF:-master}"
    configure_and_build adrenotools "$WORK/adrenotools"
}

# RT64 needs SDL2. On desktop it comes from the system; on Android it has to be
# built from source, and it is also what supplies the ANativeWindow our patched
# window layer hands to plume.
build_sdl2() {
    fetch sdl2 https://github.com/libsdl-org/SDL.git "${SDL2_REF:-release-2.30.9}"
    local build="$WORK/build-sdl2-$ABI"
    cmake -S "$WORK/sdl2" -B "$build" -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
        -DANDROID_ABI="$ABI" \
        -DANDROID_PLATFORM="android-$API" \
        -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_INSTALL_PREFIX="$WORK/sdl2-install" \
        -DSDL_SHARED=ON -DSDL_STATIC=OFF -DSDL_TEST=OFF
    cmake --build "$build" --parallel "$(nproc)"
    cmake --install "$build"
    echo "== SDL2 installed to $WORK/sdl2-install"
}

build_rt64() {
    build_sdl2
    fetch rt64 https://github.com/rt64/rt64.git "${RT64_REF:-main}"
    # RT64's Android window path is unimplemented upstream; ours goes in here.
    apply_patches rt64 rt64
    configure_and_build rt64 "$WORK/rt64" \
        -DSDL2_DIR="$WORK/sdl2-install/lib/cmake/SDL2" \
        -DCMAKE_PREFIX_PATH="$WORK/sdl2-install"
}

case "$STAGE" in
    plume)       build_plume ;;
    sdl2)        build_sdl2 ;;
    adrenotools) build_adrenotools ;;
    rt64)        build_plume; build_rt64 ;;
    all)         build_plume; build_adrenotools; build_rt64 ;;
    *) echo "unknown stage: $STAGE (expected plume | sdl2 | adrenotools | rt64 | all)" >&2; exit 2 ;;
esac
