rootProject.name = "ai-plays-online"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // A -SNAPSHOT published locally while developing wins over everything (`./gradlew publishToMavenLocal` in a
        // pokemon-client checkout) — the escape hatch for trying a change in one of the submodules without committing.
        mavenLocal()
        google()
        mavenCentral()
    }
}

// The stack this app is built on, from source: the rollback engine, and the game client with its libretro and rollback
// adapters. They are **submodules**, and Gradle builds them as part of this build (an `includeBuild` substitutes them
// for the dependencies declared in gradle/libs.versions.toml, whatever version that file names). That is what makes a
// clean clone testable by anyone: no token, no published snapshot, no `publishToMavenLocal` step — and the tests you
// run are the ones for the exact commits this repository pins.
//
// The engine and the rollback adapter are ours (they are not on Maven Central); `libretro-kmp`, which the client
// depends on, does come from Maven Central, so it is not vendored here.
includeBuild("engine/libretro-rollback")
includeBuild("client/pokemon-client")

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
