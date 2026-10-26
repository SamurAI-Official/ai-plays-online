package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.sha256OfRom
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two consoles in one process, one session each, over real UDP: the app-level version of the engine's
 * `host`/`join` demo. Gated like the other ROM tests: it runs only with
 *
 * ```
 * POKEMON_ROM=/path/game.nds NETPLAY_TEST_ROLLBACK_CORE=/path/melonds_libretro_rollback.dylib
 * ```
 *
 * set. The core is copied into each peer's data directory, so the two `LibretroConsole`s load two
 * different files (loading the same path twice would give the same emulator globals — the reason the
 * shadow console copies the file too). `POKEMON_MELONDS_ROLLBACK_CORE` must stay unset: it would make
 * both peers resolve the same file.
 */
class NetplaySessionTest {

    private fun dataDirectoryWithCore(core: Path): Path {
        val data = Files.createTempDirectory("ai-plays-online-netplay")
        val cores = Files.createDirectories(data.resolve("cores"))
        Files.copy(core, cores.resolve("melonds_libretro_rollback.dylib"), StandardCopyOption.REPLACE_EXISTING)
        return data
    }

    @Test
    fun twoPeersSynchronizeAndRunFramesOverUdp() {
        val rom = System.getenv("POKEMON_ROM")?.let(Path::of) ?: return
        val core = System.getenv("NETPLAY_TEST_ROLLBACK_CORE")?.let(Path::of) ?: return
        val hash = sha256OfRom(rom.readBytes())

        val hostSettings = NetplaySettings(
            role = NetplayRole.HOST,
            bindPort = 7100,
            peerHost = "127.0.0.1",
            peerPort = 7101,
            mode = Mode.LOCKSTEP,
        )
        val joinSettings = NetplaySettings(
            role = NetplayRole.JOIN,
            bindPort = 7101,
            peerHost = "127.0.0.1",
            peerPort = 7100,
            mode = Mode.LOCKSTEP,
        )

        val host = ConsoleHost(
            LibretroCoreSpec.MELONDS_ROLLBACK,
            rom,
            dataDirectoryWithCore(core),
            netplay = hostSettings,
            romSha256 = hash,
            audioDevice = { null },
        )
        val join = ConsoleHost(
            LibretroCoreSpec.MELONDS_ROLLBACK,
            rom,
            dataDirectoryWithCore(core),
            netplay = joinSettings,
            romSha256 = hash,
            audioDevice = { null },
        )
        try {
            host.start()
            join.start()

            val deadline = System.nanoTime() + 30_000_000_000L
            while (System.nanoTime() < deadline) {
                val synchronized = host.netplayStatus.value?.synchronized == true &&
                    join.netplayStatus.value?.synchronized == true
                if (synchronized) break
                Thread.sleep(50)
            }

            val hostStatus = assertNotNull(host.netplayStatus.value, "the host has no session status")
            val joinStatus = assertNotNull(join.netplayStatus.value, "the joiner has no session status")
            assertTrue(hostStatus.synchronized, "the host did not synchronize: ${hostStatus.summary}")
            assertTrue(joinStatus.synchronized, "the joiner did not synchronize: ${joinStatus.summary}")
            assertNull(hostStatus.stopped, "the host session stopped: ${hostStatus.stopped}")
            assertNull(joinStatus.stopped, "the joiner session stopped: ${joinStatus.stopped}")

            val framesBefore = host.status.value.frameCount
            Thread.sleep(3_000)
            val framesAfter = host.status.value.frameCount
            assertTrue(
                framesAfter - framesBefore > 30,
                "the session did not run frames (${framesAfter - framesBefore} in 3 s)",
            )
            // Both peers advance together; one may be a frame ahead depending on when the status was
            // published (the joiner's is read while it may be mid-frame).
            val gap = kotlin.math.abs(
                assertNotNull(host.netplayStatus.value).frame - assertNotNull(join.netplayStatus.value).frame,
            )
            assertTrue(gap <= 2, "the peers drifted apart ($gap frames)")
        } finally {
            host.close()
            join.close()
        }
    }
}
