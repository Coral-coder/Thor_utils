import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.junrar)

    testImplementation(kotlin("test"))
}

// Target Java 17 bytecode (what the Android module consumes) without demanding a
// JDK 17 *toolchain* — that lets the module build on whatever recent JDK is
// present, which matters in sandboxes that ship only one.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}
