//
// Loads a user-supplied Mesa Turnip driver on Adreno hardware.
//
#include "turnip_loader.h"

#include <dlfcn.h>
#include <sys/stat.h>

#include <android/log.h>

#include "adrenotools/driver.h"
#include "plume_vulkan.h"

#define LOG_TAG "ThorTurnip"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace thor {
    namespace {
        bool fileExists(const std::string &path) {
            struct stat info = {};
            return stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
        }

        std::string withTrailingSlash(const std::string &path) {
            if (path.empty() || path.back() == '/') {
                return path;
            }

            return path + "/";
        }
    }

    bool deviceSupportsCustomDriver() {
        // adrenotools works by hooking the loader used for Adreno's driver, so
        // it is only meaningful where that loader is present. Rather than
        // sniffing the SoC, just ask whether the hook library is usable — that
        // is the condition that actually matters.
        void *handle = dlopen("libadrenotools.so", RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            return false;
        }

        dlclose(handle);
        return true;
    }

    TurnipLoadResult loadTurnipDriver(
        const std::string &nativeLibraryDir,
        const std::string &privateDir,
        const std::string &driverDir,
        const std::string &driverFileName)
    {
        TurnipLoadResult result;

        const std::string driverPath = withTrailingSlash(driverDir) + driverFileName;
        if (!fileExists(driverPath)) {
            result.message = "No custom driver at " + driverPath + "; using the system driver.";
            LOGI("%s", result.message.c_str());
            return result;
        }

        // adrenotools replaces the Vulkan library the process would otherwise
        // load. ADRENOTOOLS_DRIVER_CUSTOM is the only feature needed here — the
        // file-redirect and GPU-mapping features are for other use cases and
        // asking for them would only add ways to fail.
        void *libVulkan = adrenotools_open_libvulkan(
            RTLD_NOW,
            ADRENOTOOLS_DRIVER_CUSTOM,
            nullptr,                                    // tmpLibDir, unused for custom drivers
            withTrailingSlash(nativeLibraryDir).c_str(),
            withTrailingSlash(driverDir).c_str(),
            driverFileName.c_str(),
            nullptr,                                    // fileRedirectDir, unused
            nullptr);                                   // hookParam, unused

        if (libVulkan == nullptr) {
            const char *error = dlerror();
            result.message = std::string("Failed to load ") + driverFileName + ": " +
                (error != nullptr ? error : "unknown error") + ". Falling back to the system driver.";
            LOGW("%s", result.message.c_str());
            return result;
        }

        auto getInstanceProcAddr =
            reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(libVulkan, "vkGetInstanceProcAddr"));
        if (getInstanceProcAddr == nullptr) {
            // A library that loads but has no entry point is worse than none at
            // all: leaving it installed would fail later and further from the
            // cause. Drop it and carry on with the system driver.
            result.message = driverFileName + " has no vkGetInstanceProcAddr; using the system driver.";
            LOGW("%s", result.message.c_str());
            dlclose(libVulkan);
            return result;
        }

        plume::setCustomVulkanLoader(getInstanceProcAddr);

        result.loaded = true;
        result.driverName = driverFileName;
        result.message = "Loaded custom Vulkan driver " + driverFileName + ".";
        LOGI("%s", result.message.c_str());
        return result;
    }
}
