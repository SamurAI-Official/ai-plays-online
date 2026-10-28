import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kover)
}

group = "me.nathanfallet.aiplayspokemon"
// The release workflow checks the packaged app's version against the tag it was built from, so a release must not
// depend on someone remembering to bump this line: on a tag (`v1.0.2`, or `v1.0.2-rc1` for a pre-release) the tag is
// the version. Everything else — local builds, pull requests — keeps the version above.
version = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" && it.startsWith("v") }
    ?.removePrefix("v")
    ?: "1.0.1"

kotlin {
    jvmToolchain(21)
}

dependencies {
    // UI: Compose for Desktop (window, emulator screen, control panel)
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.runtime)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutines.swing)
    // `-Puniversal`: also ship the UI natives of every desktop platform, so the uber jar
    // (`./gradlew packageUberJarForCurrentOS -Puniversal`) runs on macOS, Linux and Windows alike.
    if (providers.gradleProperty("universal").isPresent) {
        runtimeOnly(compose.desktop.macos_arm64)
        runtimeOnly(compose.desktop.macos_x64)
        runtimeOnly(compose.desktop.linux_x64)
        runtimeOnly(compose.desktop.windows_x64)
    }

    // Decision models: Jev (JSON over the JDK HTTP client) and LLMs through Koog
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koog.agents) // OpenAI, Anthropic, Ollama clients
    implementation(libs.koog.openrouter) // OpenRouter: Gemini, Mistral, DeepSeek, Llama... behind one key
    runtimeOnly(libs.slf4j.nop) // Koog logs through SLF4J: silence it

    // The Pokémon game client library (github.com/kotlinds/pokemon-client: reads the game, typed actions), and its
    // bridge to a libretro core (console adapter, DeSmuME / melonDS cores, saves). While developing, a -SNAPSHOT
    // published with `./gradlew publishToMavenLocal` in that repository.
    implementation(libs.pokemon.client)
    implementation(libs.pokemon.client.libretro)
    // Rollback netcode over LibretroConsole: the session, prediction and state ring the online mode drives.
    implementation(libs.pokemon.client.rollback)

    // MCP server: lets an external agent (e.g. Claude Code) play through tools
    implementation(libs.mcp.server)
    implementation(libs.ktor.server.cio)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

compose.desktop {
    application {
        mainClass = "me.nathanfallet.aiplayspokemon.MainKt"
        if (System.getProperty("os.name").startsWith("Mac")) jvmArgs += listOf("-Xdock:name=AI plays Online")
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // jlink otherwise omits these modules used by the core downloader and decision-model dependencies.
            // Keep in sync with `./gradlew suggestRuntimeModules`; java.net.http is required for first ROM launch.
            modules("java.instrument", "java.management", "java.net.http", "jdk.unsupported")
            packageName = "AI Plays Online"
            packageVersion = "1.0.1"
            macOS {
                bundleID = "me.nathanfallet.aiplaysonline"
                dockName = "AI Plays Online"
                appCategory = "public.app-category.games"
                minimumSystemVersion = "11.0"
                iconFile.set(project.file("launcher/icon.icns"))
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    // What did *not* run matters as much as what did: the ROM-gated tests skip (with what they wanted) when their
    // environment is missing, and that has to be visible in a CI log, not only in the report — a green run that
    // silently covered less than it looks like is how a real regression hides.
    testLogging {
        events("skipped", "failed")
        // Failures come out in full (Gradle's log only *names* a skip: the reason — "PAUSE_MUSIC_ROM is not set…" —
        // is in the report and in build/test-results/*.xml, where the sweep above reads it).
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// One command for the whole stack: this app's tests, and the tests of the libraries it pins as submodules (the rollback
// engine, and the game client with its libretro and rollback adapters). A change in a submodule is only trustworthy
// once its own tests pass too — hence the mixed task names: the multiplatform modules run `allTests`, and the JVM-only
// rollback adapter runs `test`.
tasks.register("testAll") {
    description = "Runs the app's tests and the tests of the included builds (engine and client)."
    group = "verification"
    dependsOn(tasks.named("test"))
    dependsOn(gradle.includedBuild("libretro-rollback").task(":libretro-rollback:allTests"))
    dependsOn(gradle.includedBuild("pokemon-client").task(":pokemon-client:allTests"))
    dependsOn(gradle.includedBuild("pokemon-client").task(":pokemon-client-libretro:allTests"))
    dependsOn(gradle.includedBuild("pokemon-client").task(":pokemon-client-rollback:test"))
}

// Development helper: runs any main class of the project, e.g.
// ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.SomeToolKt -PdevArgs="a|b|c"
tasks.register<JavaExec>("devRun") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = providers.gradleProperty("devMain")
    args = providers.gradleProperty("devArgs").map { it.split("|") }.getOrElse(emptyList())
}

// Headless probe: verifies config/ROM/env routing + core resolution WITHOUT starting the UI.
// Usage:
//   ./gradlew probe --args="--rom /path/to/rom.nds"
tasks.register<JavaExec>("probe") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "me.nathanfallet.aiplayspokemon.dev.AppConfigProbeKt"
    args = providers.gradleProperty("probeArgs").map { it.split("\\|") }.getOrElse(emptyList())
}


// Launcher helper: run the app directly as a Java daemon with the full runtime classpath.
// Use: ./gradlew startApp -PappArgs="/path/to/rom.nds" [-Dkey=val ...]
tasks.register<JavaExec>("startApp") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "me.nathanfallet.aiplayspokemon.MainKt"
    args = providers.gradleProperty("appArgs").map { it.split("\\|") }.getOrElse(listOf())
    jvmArgs = providers.gradleProperty("appJvmArgs").map { it.split("\\|") }.getOrElse(listOf())
}


