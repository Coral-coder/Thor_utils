// Plugins are declared per-module rather than listed here with `apply false`.
//
// That is deliberate: an `apply false` block in the root script forces Gradle to
// resolve the Android Gradle Plugin whenever *any* task runs, including the
// pure-JVM :core tests. Declaring them in the modules that use them keeps :core
// buildable and testable without an Android SDK — see the `thorCoreOnly`
// property in settings.gradle.kts.

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
