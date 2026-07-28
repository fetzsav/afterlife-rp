plugins {
    // Auto-provisions the JDK required by the toolchain (Paper 26.2 needs Java 25).
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "afterlife-rp"
