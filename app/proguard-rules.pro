# Commons Compress reaches for optional codecs (Brotli, Zstd, Pack200) that are
# not bundled. R8 must not fail the build over classes we deliberately do not
# ship — the archive formats we actually read are all present.
-dontwarn org.apache.commons.compress.**
-dontwarn org.brotli.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.tukaani.xz.**

# junrar logs through slf4j, which is likewise not bundled.
-dontwarn org.slf4j.**

# Kotlin coroutines' internal service loading.
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
