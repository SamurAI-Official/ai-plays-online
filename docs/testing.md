# Testing

What is covered, what a run prints, and what a machine with a ROM can do that CI cannot. The short version:

```bash
./gradlew test     # 300+ tests, no ROM needed
./gradlew build    # the same, plus the build checks
```

## What the suite covers

| area | examples |
| --- | --- |
| the game model | RAM → typed state for every supported title, map/party/battle/menu decoders, ROM-derived world |
| the players | the agent loop (observe → decide → act → verify), reader and typed players, memory between sessions, the MCP tools |
| the emulator host | the console thread and its drivers, leases (the agent asks, the human always wins), save states, resets, the ROM picker's config |
| online play | `ConsoleHost` in a session and `NetplayDriver` itself: handshake, frames, idling while paused, a peer with another ROM, a peer that leaves, a peer that vanishes |
| the window's wiring | keys → console, pause, fast forward, mute, state slots, settings that reach the console *and* the config file, the panel's description of the game |
| music during pauses | the shadow console, resync refusals, rapid pauses, a pause during a song change (gated, see below) |
| narration | a real decision turned into speech (gated) |

## What CI runs

`.github/workflows/build.yml` runs `./gradlew testAll` on every push and pull request, on Ubuntu and macOS: the app's
tests **and** the ones for the libraries it builds from the submodules (the engine, and the client with its adapters).
It has no ROM, so the **gated** suites below are *skipped* there — and the run says so, twice over: Gradle's log names
each skip,

```
NetplayDriverTest > twoDriversSynchronizeAndRunTheSameFrames() SKIPPED
AppControllerTest > thePauseKeyStopsTheGameAndPutsItBack() SKIPPED
```

and the run's summary lists all of them with the reason (read from the JUnit XML, which is also uploaded):

```
190 test(s) did not run:

- `NetplayDriverTest.twoDriversSynchronizeAndRunTheSameFrames()` — NETPLAY_TEST_ROM is not set (this test needs a ROM to drive two peers on)
- `RomNarrationCheckTest.aDecisionOnTheRealGameIsSaidByTheVoice()` — NARRATION_ROM is not set (this test needs a HeartGold ROM to build a real decision on)
```

The JUnit XML and that list are uploaded as a build artifact, so you can read what a run did not cover.

## The gated suites

They need a ROM, sometimes a save state, sometimes an emulator core: things we cannot ship. **Run them one at a time**
(a libretro core can only be started once per process — two of them in one JVM interfere, and the second game "never
gets past its intro"):

| suite | needs | what it proves |
| --- | --- | --- |
| `NetplaySessionTest` | `NETPLAY_TEST_ROM`, `NETPLAY_TEST_ROLLBACK_CORE` | two consoles, one session each, over real UDP; the online guards (no save/load/reset/fast-forward online, the agent's lease refused) |
| `NetplayDriverTest` | the same two | `NetplayDriver` itself: synchronizing and running frames together, idling while paused, a peer with another ROM refused, a peer that leaves (BYE), a peer that vanishes (the 5 s timeout) |
| `AppControllerTest` | `APP_TEST_ROM`, `APP_TEST_CORE` | the window's wiring on a real console: keys, focus loss, pause, state slots writing a real file, the panel, settings persisted |
| `ShadowAudioCheckTest` | `PAUSE_MUSIC_DATA`, `PAUSE_MUSIC_ROM`, `PAUSE_MUSIC_STATE` | a pause with music playing is resynced: the sound card really played, and the WAV proves it (`PAUSE_MUSIC_OUT`) |
| `RapidPausesCheckTest` | `PAUSE_MUSIC_DATA`, `PAUSE_MUSIC_ROM`, `PAUSE_MUSIC_RAPID_STATE` | pausing and resuming again and again stays in tune |
| `SongChangePauseCheckTest` | `PAUSE_MUSIC_DATA`, `PAUSE_MUSIC_ROM`, `PAUSE_MUSIC_SONG_CHANGE_STATE` (+ `PAUSE_MUSIC_SONG_CHANGE_WAIT=off`) | a pause asked *while the game changes its song*: with the setting on it starts on the new song; off, the resume is refused and the music jumps back |
| `RomNarrationCheckTest` | `NARRATION_ROM`, `NARRATION_DATA` (+ `NARRATION_OUT`) | a decision built on the real game is really said by the voice, and the audio is written to a file |

```bash
# two peers over UDP, through ConsoleHost and through NetplayDriver
NETPLAY_TEST_ROM="/path/game.nds" NETPLAY_TEST_ROLLBACK_CORE="/path/melonds_libretro_rollback.dylib" \
  ./gradlew test --tests '*Netplay*Test*'

# the window's wiring on a real console
APP_TEST_ROM="/path/game.nds" APP_TEST_CORE="/path/melonds_libretro_rollback.dylib" \
  ./gradlew test --tests '*AppControllerTest*'

# music during pauses: the data directory holds cores/, and the states come from dev/SaveStateProbe
PAUSE_MUSIC_DATA="$HOME/.ai-plays-online-copy" PAUSE_MUSIC_ROM="/path/HeartGold.nds" \
  PAUSE_MUSIC_STATE="$HOME/.ai-plays-online-copy/states/HeartGold.nds.state7" \
  PAUSE_MUSIC_OUT=/tmp/wav ./gradlew test --tests '*ShadowAudioCheckTest*'

# a decision narrated for real
NARRATION_ROM="/path/HeartGold.nds" NARRATION_DATA="$HOME/.ai-plays-online-copy" \
  NARRATION_OUT=/tmp/wav ./gradlew test --tests '*RomNarrationCheckTest*'
```

### Point `*_DATA` at a copy, never at the app's own directory

The tests write `states/` there, and the core writes the in-game save (`saves/<rom>.sav`) when a console closes. A
savestate carries the game's save chip, so **loading a fixture state and closing the console replaces the real in-game
save with the one that state was taken from**. A state taken where there was no save — what `SaveStateProbe` produces,
since it boots in a temp directory — carries a *new game*, which then boots into the name entry instead of the player's
field.

```bash
cp -R ~/.ai-plays-online /tmp/ai-plays-data-copy    # cores/, saves/, states/: one copy per test session
```

### Fixtures

`dev/SaveStateProbe` writes the states the audio suites want, without sitting at the app pressing F1:

```bash
./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.SaveStateProbeKt \
  -PdevArgs="/path/game.nds|1200|desmume|1|--out=/tmp/state1"
```

To see what a **screen-only** game tells the model — the path a ROM with no calibrated reader takes, where the picture
names the screens — replay a state taken where it misbehaves with `dev/ReaderPlayProbe`, which prints the `memory` and
`affordances` blocks the model is given, and the map it has built:

```bash
# take a state where the agent was stuck (SaveStateProbe, or F1 in the app), then:
./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.ReaderPlayProbeKt \
  -PdevArgs="/path/game.nds|12|desmume|--state=/tmp/stuck.state7|--memory"
```

`affordances: N known transitions over M screens` is the line that says whether the player is learning: it stays at
`0 known transitions` when the screens cannot be told apart, which is the shape of a loop.

`RomNarrationCheckTest` is different: it boots the ROM and presses A until the game is on the **field**, so its data
directory needs a HeartGold with a **real in-game save**. A fresh game never gets there — it stops at the name entry.
`SongChangePauseCheckTest` needs a state taken *while the song changes* (walking from Viridian City onto Route 22 is the
one we use), which has to be made by playing to that point.

### Running the gated suites on CI

`.github/workflows/gated-tests.yml` runs all of them on a runner **you host** — the only place they can run, since a
hosted runner has no ROM. Label the runner `self-hosted,macos,roms` and set three repository variables (Settings →
Secrets and variables → Actions → Variables):

| variable | what it points at |
| --- | --- |
| `GATED_ROMS` | a directory holding the ROMs (the workflow expects the file names used in the table above) |
| `GATED_DATA` | a data directory with `cores/` and the save states; the workflow **copies** it, so this one is never written |
| `GATED_ROLLBACK_CORE` | the melonDS rollback build the netplay suites drive |

It is deliberately not wired to `pull_request`: that would run a fork's code on your machine. Run it on demand, or let
the weekly schedule catch drift in the fixtures and the cores. Each suite runs in its own JVM
(`.github/scripts/run-gated-suite.sh`), and the run's summary says which tests skipped and why.

## What a person has to test

Automation covers the loop, not the window. If you have half an hour, this is the list that finds real bugs — and a
result from any one line is worth an issue (a **Test report** if it worked, a **Bug report** if it did not):

**The window.** Open the app with no ROM: the picker lists your ROMs folder, *Browse…* opens one from anywhere, nothing
loads until you pick. Then: play/pause with the button **and** with Space; the arrow keys, X/Z (A/B), Enter, Backspace,
S/A/Q/W; **P** pauses, **F** fast-forwards, **M** mutes, **F1–F4** load a state and **Shift+F1–F4** save one. Switch
tabs, change the mode and the model, paste an API key (the key form must appear and then disappear). **Click away
mid-press, or Cmd-Tab away**: the game must stop walking by itself (releases made while unfocused never arrive). Resize
the window and go full screen: the two screens stay readable. Close the window while the AI is playing: it must exit
cleanly.

**The launcher (macOS DMG).** First launch with no `~/.ai-plays-online`: the picker appears, no Java install needed,
the core downloads on the first ROM. Gatekeeper's warning is expected (the build is not notarized): Control-click →
Open. The dock name and the app's own menu should say **AI Plays Online**.

**Audio.** The default device, wired headphones, Bluetooth headphones — and switching device *while the game runs*.
**M** and **F** must not stutter or double the sound. Turn music during pauses on, pause for a few seconds, resume: the
music continues where it should, without a jump or a silence.

**Online play on a LAN.** Two machines, same ROM and revision: host on one
(`AI_PLAYS_ONLINE_NETPLAY_ROLE=host`), join on the other. Both players' inputs must land on both screens; the panel's
netplay status should show frames, the peer's frame and rollbacks. Pull the network cable or sleep one machine: the
other must stop with a reason, never hang. Try a joiner with a different ROM revision: it must be refused ("content
differs").

**Agents.** An external agent over MCP (e.g. Claude Code): the tool list is complete, a refused action comes back with
its reason, and the agent can play a whole chapter. An LLM provider (OpenAI, Anthropic, OpenRouter, Ollama): the model
list, the key form, a run with reasoning on and off. Jev with a key, and `claude -p` with a Claude Code login.

**A fresh machine.** Delete `~/.ai-plays-online` and start again: the app must download the core it needs and play.
Start it with no network: it must say so clearly instead of crashing.

## Known gaps

- **The Compose UI has no automated test** (`ControlPanel`): it would need a UI-test dependency and a display. The
  window list above is the substitute, and it is why a hand-tested release matters.
- **Gated suites cannot run on hosted CI** — they need a ROM, and shipping one is not ours to do. They run locally, or
  on a self-hosted runner that has the ROMs.
- **Nothing here judges how well an AI plays**, only that the loop, the wiring and the timing are sound. That is what
  test reports are for.
- **A clean clone needs no credentials**: the libraries are submodules built from source by `./gradlew test`, and
  `./gradlew testAll` runs their own tests too. The publish workflows that used to feed GitHub Packages are unused by
  this build now; they stay until we are sure nothing else consumes those snapshots.
