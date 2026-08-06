//
// Loads a user-supplied Mesa Turnip driver on Adreno hardware.
//
#pragma once

#include <string>

namespace thor {

    /** Where a custom driver was asked to come from, and what happened. */
    struct TurnipLoadResult {
        bool loaded = false;
        /** Human-readable outcome, suitable for a log line or an in-game overlay. */
        std::string message;
        /** Driver name that was loaded, when one was. */
        std::string driverName;
    };

    /**
     * Loads a Turnip driver and installs it as plume's Vulkan loader.
     *
     * Why this exists: RT64 targets desktop Vulkan, and Qualcomm's Android blob
     * is the weakest link on Adreno — it lags on extensions and has its own
     * behavioural quirks. Mesa's Turnip is a far more conformant implementation
     * of the same hardware, and libadrenotools can load it per-application
     * without root. This is the single highest-leverage thing available for
     * making a desktop-shaped Vulkan renderer behave on a handheld.
     *
     * Failure is never fatal. If the driver is missing, unreadable or refuses to
     * load, this returns a result explaining why and leaves plume on the system
     * driver, so the game still starts.
     *
     * @param nativeLibraryDir  the APK's native library directory.
     * @param privateDir        an app-private writable directory adrenotools can
     *                          stage files in.
     * @param driverDir         directory holding the extracted driver.
     * @param driverFileName    e.g. "libvulkan_freedreno.so".
     */
    TurnipLoadResult loadTurnipDriver(
        const std::string &nativeLibraryDir,
        const std::string &privateDir,
        const std::string &driverDir,
        const std::string &driverFileName);

    /** True when the running device is Adreno and could use a custom driver. */
    bool deviceSupportsCustomDriver();
}
