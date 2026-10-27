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
        // A -SNAPSHOT published locally while developing wins over the published ones (`./gradlew publishToMavenLocal`
        // in a pokemon-client checkout).
        mavenLocal()
        // Our own artifacts, published by their own repositories' workflows: dev.kotlinds:libretro-rollback (the
        // rollback engine) and dev.kotlinds:pokemon-client{,-libretro,-rollback} (the fork the netplay work needs).
        // They are not on Maven Central (a -SNAPSHOT never is, and the engine and the rollback adapter are ours), so a
        // clean clone resolves them here: GitHub Packages needs a token with `read:packages` (`GITHUB_TOKEN` in CI,
        // `gpr.user`/`gpr.key` in ~/.gradle/gradle.properties locally). See README → Building from a clean clone.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/SamurAI-Official/ai-plays-online")
            content { includeGroup("dev.kotlinds") }
            credentials {
                // A settings script has no `findProperty`: the providers API is how the same names are read here.
                username = System.getenv("GITHUB_ACTOR") ?: providers.gradleProperty("gpr.user").orNull
                password = System.getenv("GITHUB_TOKEN") ?: providers.gradleProperty("gpr.key").orNull
            }
        }
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
