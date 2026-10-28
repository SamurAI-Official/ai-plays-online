package me.nathanfallet.aiplayspokemon.emulator

import dev.kotlinds.libretrorollback.Mode
import dev.kotlinds.pokemonclient.console.InputFrame
import dev.kotlinds.pokemonclient.libretro.LibretroConsole
import dev.kotlinds.pokemonclient.libretro.LibretroCoreSpec
import me.nathanfallet.aiplayspokemon.gatedPath
import me.nathanfallet.aiplayspokemon.sha256OfRom
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.readBytes
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [NetplayDriver] itself: the app's online loop (one frame in, the peer's input out, the console runs once), with two
 * consoles in one process over real UDP. `NetplaySessionTest` drives `ConsoleHost`, which is what the app runs — but
 * through its own loop; this drives the driver, where the input encoding, the status the panel shows, the idle path and
 * the events that stop a session live.
 *
 * Gated like the other ROM tests, and **run it alone** (a libretro core can only be started once per process):
 *
 * ```
 * NETPLAY_TEST_ROM=/path/game.nds NETPLAY_TEST_ROLLBACK_CORE=/path/melonds_libretro_rollback.dylib
 * ```
 */
class NetplayDriverTest {

    /** One console, with its own copy of the core: loading the same path twice would share the emulator's globals. */
    private fun console(rom: Path, core: Path, name: String): LibretroConsole {
        val data = Files.createTempDirectory("ai-plays-online-driver-$name")
        val cores = Files.createDirectories(data.resolve("cores"))
        Files.copy(core, cores.resolve("melonds_libretro_rollback.dylib"), StandardCopyOption.REPLACE_EXISTING)
        return LibretroConsole(
            spec = LibretroCoreSpec.MELONDS_ROLLBACK,
            rom = rom.toKotlinxPath(),
            dataDirectory = data.toKotlinxPath(),
            onVideo = { },
            onAudio = { _, _ -> },
        )
    }

    private fun settings(role: NetplayRole, bindPort: Int, peerPort: Int) = NetplaySettings(
        role = role,
        bindPort = bindPort,
        peerHost = "127.0.0.1",
        peerPort = peerPort,
        mode = Mode.LOCKSTEP,
    )

    /** The two sides, each ticking on its own thread: in lockstep [NetplayDriver.tick] blocks until the peer's input
     * arrives, so two drivers on one thread would wait for each other forever (the app gives every peer its own console
     * thread for exactly this reason).
     *
     * Closing a driver while its loop is inside a frame crashes the process in native code (libretro cores are not
     * thread-safe), so [close] stops both loops and waits for them first — what `ConsoleHost` does by closing its
     * driver from the console thread's own `finally`.
     */
    private class Peers(
        val host: NetplayDriver,
        val join: NetplayDriver,
        private val hostConsole: LibretroConsole,
        private val joinConsole: LibretroConsole,
    ) : AutoCloseable {

        private val threads = java.util.concurrent.Executors.newFixedThreadPool(2)
        private val stopHost = java.util.concurrent.atomic.AtomicBoolean(false)
        private val stopJoin = java.util.concurrent.atomic.AtomicBoolean(false)
        private val hostDone = java.util.concurrent.CountDownLatch(1)
        private val joinDone = java.util.concurrent.CountDownLatch(1)

        @Volatile
        private var started = false

        /** Starts both loops; they run until [close] (or until their session stops, which [NetplayDriver.tick] reports). */
        fun start() {
            started = true
            threads.submit { run(host, stopHost); hostDone.countDown() }
            threads.submit { run(join, stopJoin); joinDone.countDown() }
        }

        /** The app's pacing: a frame that ran is one frame; one that did not waits a millisecond (never a busy loop). */
        private fun run(driver: NetplayDriver, stop: java.util.concurrent.atomic.AtomicBoolean) {
            while (!stop.get() && !driver.isClosed) {
                if (!driver.tick(InputFrame())) Thread.sleep(1)
            }
        }

        /** Waits until [condition] holds, pumping [pump] between checks, or fails with [what] after [seconds]. */
        fun waitUntil(what: String, seconds: Int = 30, pump: () -> Unit = {}, condition: () -> Boolean) {
            val deadline = System.nanoTime() + seconds * 1_000_000_000L
            while (System.nanoTime() < deadline) {
                if (condition()) return
                pump()
                Thread.sleep(20)
            }
            throw AssertionError("$what (host: ${host.status.value.summary} · join: ${join.status.value.summary})")
        }

        fun synchronize() = waitUntil("the peers did not synchronize") { host.isSynchronized && join.isSynchronized }

        /** The joiner leaves: its loop stops and finishes its frame before the driver goes (a peer that quits). */
        fun joinerLeaves() {
            stopJoin.set(true)
            if (started) joinDone.await(15, java.util.concurrent.TimeUnit.SECONDS)
            join.close()
        }

        /** The joiner vanishes: its loop stops and nothing is sent — a frozen peer, a killed process, a dropped link. */
        fun joinerVanishes() {
            stopJoin.set(true)
            if (started) joinDone.await(15, java.util.concurrent.TimeUnit.SECONDS)
        }

        override fun close() {
            stopHost.set(true)
            stopJoin.set(true)
            if (started) {
                hostDone.await(15, java.util.concurrent.TimeUnit.SECONDS)
                joinDone.await(15, java.util.concurrent.TimeUnit.SECONDS)
            }
            host.close()
            join.close()
            threads.shutdownNow()
            hostConsole.close()
            joinConsole.close()
        }
    }

    /** Both sides of a session, with the given ROM hashes (the same, unless a test wants a mismatch). */
    private fun peers(
        rom: Path,
        core: Path,
        hostHash: String,
        joinHash: String,
        hostPort: Int,
        joinPort: Int,
        log: (String) -> Unit,
    ): Peers {
        val hostConsole = console(rom, core, "host-$hostPort")
        val joinConsole = console(rom, core, "join-$joinPort")
        return Peers(
            host = NetplayDriver(hostConsole, settings(NetplayRole.HOST, hostPort, joinPort), hostHash, log),
            join = NetplayDriver(joinConsole, settings(NetplayRole.JOIN, joinPort, hostPort), joinHash, log),
            hostConsole = hostConsole,
            joinConsole = joinConsole,
        )
    }

    private fun romAndCore(): Pair<Path, Path> = gatedPath("NETPLAY_TEST_ROM", "a ROM to drive two peers on") to
        gatedPath("NETPLAY_TEST_ROLLBACK_CORE", "a rollback core to drive two peers with")

    @Test
    fun twoDriversSynchronizeAndRunTheSameFrames() {
        val (rom, core) = romAndCore()
        val hash = sha256OfRom(rom.readBytes())
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        peers(rom, core, hash, hash, 7200, 7201) { lines += it }.use { session ->
            session.start()
            session.synchronize()
            val before = session.host.status.value.frame
            session.waitUntil("the session did not run frames") { session.host.status.value.frame >= before + 60 }

            assertNull(session.host.status.value.stopped, "the host session stopped")
            assertNull(session.join.status.value.stopped, "the joiner session stopped")
            // Both peers advance together (one may be a frame ahead: the status is read while it may be mid-frame).
            val drift = abs(session.host.status.value.frame - session.join.status.value.frame)
            assertTrue(drift <= 2, "the peers drifted apart ($drift frames)")
            // The status the panel shows comes from the session: the peer's frame, and the state it keeps.
            assertTrue(session.host.status.value.remoteFrame > 0, "the host never saw the peer's frame")
            assertTrue(session.host.status.value.stateMiB > 0.0, "the session keeps no state")
            assertTrue(lines.any { it.contains("synchronized") }, "nothing said the session synchronized: $lines")
        }
    }

    @Test
    fun idlingKeepsTheSessionAliveWithoutRunningFrames() {
        val (rom, core) = romAndCore()
        val hash = sha256OfRom(rom.readBytes())
        peers(rom, core, hash, hash, 7220, 7221) { }.use { session ->
            // What the app does while the game is paused online: pump the session and run nothing. That has to be
            // enough to finish the handshake — the pause must not stop the session from ever connecting.
            session.waitUntil("the peers did not synchronize while idling", seconds = 20, pump = {
                session.host.idle()
                session.join.idle()
            }) { session.host.isSynchronized && session.join.isSynchronized }
            assertEquals(0, session.host.status.value.frame, "idling ran frames")

            // …and the session goes on afterwards: the frames start as soon as the game does.
            session.start()
            session.waitUntil("the session did not resume after idling") { session.host.status.value.frame > 0 }
            assertNull(session.host.status.value.stopped, "an idle stretch stopped the session")
            assertNull(session.join.status.value.stopped, "an idle stretch stopped the joiner")
        }
    }

    @Test
    fun aPeerWithAnotherRomIsRefused() {
        val (rom, core) = romAndCore()
        val hash = sha256OfRom(rom.readBytes())
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        // The same game, a different revision: the handshake compares the content hash, so neither side plays on.
        peers(rom, core, hash, "0".repeat(64), 7240, 7241) { lines += it }.use { session ->
            session.start()
            session.waitUntil("the handshake mismatch was not noticed", seconds = 20) {
                session.host.status.value.stopped != null
            }
            assertTrue(
                session.host.status.value.stopped!!.contains("content differs"),
                "the host stopped for another reason: ${session.host.status.value.stopped}",
            )
            assertTrue(lines.any { it.contains("handshake failed") }, "nothing was logged: $lines")

            // The joiner is not told: the protocol has no refusal packet, and its timeout only applies once the
            // session runs, so it goes on offering the handshake (a host that is simply not up yet looks the same).
            // What matters is that it never plays: no frames, and the game does not start on one side only.
            Thread.sleep(1_000)
            assertNull(session.join.status.value.stopped, "the joiner stopped by itself")
            assertFalse(session.join.isSynchronized, "the joiner synchronized with a refused session")
            assertEquals(0, session.join.status.value.frame, "the joiner ran frames without a session")
        }
    }

    @Test
    fun aPeerThatLeavesStopsTheSession() {
        val (rom, core) = romAndCore()
        val hash = sha256OfRom(rom.readBytes())
        peers(rom, core, hash, hash, 7260, 7261) { }.use { session ->
            session.start()
            session.synchronize()
            // The peer goes away properly: it says goodbye (`BYE`), so the survivor stops at once, not on a timeout.
            session.joinerLeaves()
            session.waitUntil("the host did not notice the peer leaving", seconds = 20) {
                session.host.status.value.stopped != null
            }
            assertTrue(
                session.host.status.value.stopped!!.contains("disconnected (closed)"),
                "the host stopped for another reason: ${session.host.status.value.stopped}",
            )
        }
    }

    @Test
    fun aPeerThatVanishesIsNoticedAfterTheTimeout() {
        val (rom, core) = romAndCore()
        val hash = sha256OfRom(rom.readBytes())
        peers(rom, core, hash, hash, 7280, 7281) { }.use { session ->
            session.start()
            session.synchronize()
            // The peer freezes (a killed process, a dropped link: nothing is sent at all): the host must give up on
            // its own rather than wait for a packet that will never come — otherwise the game stays stuck, not stopped.
            session.joinerVanishes()
            session.waitUntil("the host waited for a silent peer", seconds = 20) {
                session.host.status.value.stopped != null
            }
            assertTrue(
                session.host.status.value.stopped!!.contains("no packet for"),
                "the host stopped for another reason: ${session.host.status.value.stopped}",
            )
        }
    }
}
