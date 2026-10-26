# ai-plays-online

Put an AI in front of a Nintendo DS game and watch it play, live (and take back the controls whenever you want). The
same game, the same "eyes" and the same controller for every AI, so they can be compared:

- **[Jev](https://docs.typesafe.ai)**, TypeSafe's "System One" decision model: it doesn't generate text, it picks one
  option from a closed list and returns a calibrated **probability for every option**, in ~150 ms;
- **LLMs** through [Koog](https://github.com/JetBrains/koog): OpenAI, Anthropic, OpenRouter (Gemini, Mistral,
  DeepSeek, Llama...) or a local model with Ollama. They reason before each choice and can keep notes;
- **Claude with your Claude Code login** (e.g. a Claude subscription, no API key), through `claude -p`;
- **any external agent through MCP**: the app runs an MCP server and the agent (e.g. Claude Code) plays with tools.

It started as [ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon) and is being opened up to other DS
games: each game gets its own prompt (objective and conventions) and its own agent, and the app picks the game from
the ROM itself. See [Tested games](#tested-games) for where each title stands.

**Bring your own ROM.** No game, BIOS or firmware is included, downloaded or linked to: you must dump your own
cartridges. This project is an orchestration and optimization layer over the projects we compiled, patched and tested
(see [Provenance](#provenance-and-licenses)).

What every AI gets:

- **eyes**: for the games we have a typed reader for (Pokémon today) we read the game's RAM (map around the player as
  text, position, dialogue, menus, party, battle...) and describe it as JSON: no AI sees pixels. Other DS games are
  playable in the app, and get the screen instead (see [Tested games](#tested-games));
- **memory**: what each of its actions changed, where it has been, what it read, the map it explored, its own notes;
- **hands**, depending on the mode you pick:
    - **Pure**: the controller, nothing more: "which button do I press?" among the 12 DS buttons (+ wait);
    - **Assisted**: the buttons plus actions carried out by code ("walk to the stairs and take them", "talk to Mom",
      "choose YES", "go to Route 30", "surf across"): the AI still decides what to do, code does the walking (across
      floors and maps, with Surf, Cut, Strength, Waterfall... used on the way);
    - **Hybrid**: assisted actions picked by a fast decision model (Jev), with an LLM planner called when it hesitates,
      loops, or periodically, whose goal the fast model then follows.

Every variant is an option in the app (mode, knowledge level, timing, sequences, reasoning, notes, explored map...),
and each run records milestones (new places, story progress) with decisions, presses, time, tokens and cost in
`~/.ai-plays-online/runs/`, so configurations can be compared.

## Download the macOS app

The native app requires **macOS 11 or later on Apple Silicon** (M1 or later) and includes its own Java runtime:

1. Download `ai-plays-online-<version>-macos-arm64.dmg` from
   [GitHub Releases](https://github.com/SamurAI-Official/ai-plays-online/releases/latest).
2. Open the DMG and drag **AI Plays Online** into **Applications**, then launch the app.
3. Choose a Nintendo DS ROM that you dumped yourself. No game, BIOS or firmware is included.

The macOS build is **not Developer ID signed or notarized**, so macOS may warn that the developer cannot be verified.
If you trust the release, Control-click the app in Finder and choose **Open**. Intel Macs can use the cross-platform
JAR below instead.

## Quick start (JAR or source)

You need **Java 21** (for example [Temurin 21](https://adoptium.net/temurin/releases/?version=21)) and a **Nintendo DS
ROM that you dumped yourself** (no ROM is provided — see [Tested games](#tested-games) for the titles we verified).

1. Build the app (or take `ai-plays-online-<version>-universal.jar` from a release). The same jar runs on macOS, Linux
   and Windows (x86-64 and Apple Silicon).
2. Run it. **The app opens on the game picker**: it lists the ROMs in your ROMs folder
   (`~/.ai-plays-online/roms` by default), each with what we know about it, and **Browse…** opens one from anywhere.
   Nothing is loaded until you pick — the app never starts a game by itself. To skip the picker, name a ROM on the
   command line (or in `AI_PLAYS_ONLINE_ROM`):

   ```bash
   java -jar ai-plays-online-<version>-universal.jar "/path/to/your game.nds"
   ```

   On first launch it downloads the DeSmuME emulator core for your platform (see below).
3. Let an AI play:
    - **an agent through MCP** (e.g. Claude Code or Codex): keep the *MCP* tab selected (the server starts on port
      `3333`), then connect the agent, e.g. for Claude Code
      `claude mcp add --transport http game http://localhost:3333/mcp`, and ask it to play, for example: *"Play
      Pokémon HeartGold through the `pokemon` MCP tools. Start a new game and beat the Pokémon League. Keep notes of
      your progress in NOTES.md."* For any other MCP client, the server's URL is `http://localhost:3333/mcp`
      (streamable HTTP);
    - **an LLM or Jev from the app**: choose it in the panel on the right, paste its API key if needed, and click
      **▶ Let … play**.

## Building and running

```bash
./gradlew test         # unit tests
./gradlew startApp      # runs the app (the picker: no game is loaded until you choose one)
./gradlew packageUberJarForCurrentOS -Puniversal   # the jar that runs on every platform
./gradlew packageDmg    # on macOS: a self-contained .app and DMG
```

A ROM on the command line (`./gradlew startApp -PappArgs="/path/to/game.nds"`) opens that game directly.

## The launcher (macOS)

`./make_launcher.sh` builds the app and installs **AI Plays Online.app** into `~/Applications` and
`~/StreamBoyDS`:

```bash
./make_launcher.sh              # build the uber jar, then (re)install the app
./make_launcher.sh --no-build   # reuse the jar already in build/compose/jars
./make_launcher.sh ~/Desktop    # install somewhere else
```

The app is a **launcher over this repository's build**: its script starts the newest jar in
`build/compose/jars` (and builds one when there is none), so it always runs the current code — no jar copied
into the app that goes stale. Double-click it, or:

```bash
open -a "AI Plays Online"                          # the game picker
open -a "AI Plays Online" --args "/path/to/game.nds"   # straight into a game
```

The app's output goes to `~/Library/Logs/AI plays Online.log`, and `AI_PLAYS_ONLINE_HOME` points the launcher
at another checkout. The pieces live in [`launcher/`](launcher) (the script, the `Info.plist`, the icon) and
`make_launcher.sh` fills in the paths; the LLM settings the launcher exports (the OpenAI-compatible endpoint)
are read from the environment, so override them there instead of editing the app.

## Tested games

We run every title ourselves and record what works. "Typed agent" means the AI gets the game's state read from RAM
(pokemon-client readers, the fullest support); "screen only" means the game runs, the human can play, and the emulator
is measured, but the AI would only have the screen. A ROM is identified by its header, so another region or revision
of the same game still works (and may be untested).

| Game | ROM code | Agent | Walkthrough | Rollback-safe | Verified |
|---|---|---|---|---|---|
| Pokémon HeartGold | `IPKE` | typed | 12 chapters, generated from its story table (verified progress) | melonDS ✅ / DeSmuME ✅ | 1,000-frame determinism, 300-frame two-process rollback with 1/3 packet loss, delta snapshots |
| Pokémon Platinum | `CPUE` | typed | — (needs its story table) | melonDS ✅ / DeSmuME ✅ | 600-frame determinism per core |
| Mario Kart DS | `AMCE` | screen only | 7 chapters, front-end landmark captured | melonDS ❌ (fails at frame 0) / DeSmuME ✅ | boots on both; use DeSmuME or lockstep |
| Final Fantasy III | `AFFE` | screen only | 5 chapters, hints only | melonDS ❌ (fails at frame 0) / DeSmuME ✅ | boots on both; use DeSmuME or lockstep |
| Final Fantasy IV | `YF4E` | screen only | 5 chapters, hints only | melonDS ✅ / DeSmuME ✅ | 600-frame determinism per core |
| Phantasy Star 0 | `C24E` | screen only | 6 chapters, front-end landmark captured | melonDS ✅ / DeSmuME ✅ | 600-frame determinism per core |
| Age of Empires: The Age of Kings | `AEKE` | typed (calibrated reader) | 6 chapters, front-end + briefing landmarks, in-mission signal | melonDS ✅ / DeSmuME ✅ | 600-frame determinism per core |

All seven boot and run on both patched cores; 12 of the 14 game/core combinations pass a 600-frame save/load
determinism check (the two that do not are melonDS's, and only for those two titles). The raw numbers — state size,
save/load cost, what the local-wireless probe found — are in [`docs/games.md`](docs/games.md), along with the exact
commands that produced them. Local wireless is measured but not yet reached by scripted inputs on any title: that
needs a savestate taken inside a multiplayer screen (procedure in the same document).

Other DS games run too: an unknown ROM gets the generic profile, which is playable with the human in control.

## Typed readers: how a game nobody wrote code for becomes typed

The AI plays best when the app can read the game's own state. For Pokémon that reader is code (pokemon-client knows
the games' structures). For any other game it is **data**: `readers/<id>.json` says which addresses hold what, and the
app then treats that game as typed — its values are shown in the panel, and a walkthrough chapter can be verified from
it (`kind: memory`) instead of from a picture of the screen.

That data is not written by hand from a debugger: `dev/RamProbeTool` **calibrates** it by driving the game and watching
its memory, looking for the address that says which screen is up, one that counts up on its own, one that reacts to a
press. Age of Empires is the worked example — `--calibrate=mode` found the address, `--watch` confirmed it, and its
`in-mission` signal now verifies a chapter:

```text
chapter aoe:boot (Boot to the front end) reached at frame 460      # screen landmark
chapter aoe:start (Start the campaign) reached at frame 1650       # screen landmark
chapter aoe:cursor (Learn the controls) reached at frame 3480      # the reader's in-mission signal
```

The format, the recipes, what it can and cannot infer, and the mistakes it learned from are in
[`docs/readers.md`](docs/readers.md).

**A ROM with no reader teaches the app one by itself.** You do not have to run anything: while a game with no reader is
played, `emulator/ReaderCalibrator` watches the picture and the memory together, one 128 KiB part of main RAM at a time,
and writes `readers/<id>.json` when it has a signal it can stand behind — one that separates at least three screens,
survives being re-checked on every later look, and was seen again on a screen that came back. The panel shows it
learning; the moment the reader is written the game is typed. On Mario Kart DS (nobody calibrated it, the route was
blind button presses) it wrote a 4-value reader and the picker then offered it as
`TYPED · reader: 4 values (learned while playing)`. The same run *rejected* the addresses that looked perfect at first —
clocks that moved once per screen — because a screen came back with a different value. What it finds is a draft to
review: the values are the game's own, the names are placeholders.

**The reader also plays the game.** `ReaderPlayer` is the same observe → decide → act loop as the Pokémon player, with
the reader's values as its state and the console's own actions (press, hold, tap, wait) — so a game nobody wrote code
for gets an AI that can see *and* act. Verified headlessly on AoE with a scripted policy that reads nothing but the
reader's `screen` value:

```text
#11  screen select campaign (64) · menu no menu (0)   →  press_a  →  screen: select campaign → select mission
#12  screen select mission (231) · menu briefing (16) →  press_a  →  screen: select mission → mission briefing (83)
#19  screen loading the mission (29)                  →  wait     →  screen: loading the mission → in mission (84)
decisions: 24, error: none
progress signal 'in-mission': true
```

Run it yourself with `dev/ReaderPlayProbe` (`./gradlew devRun -PdevMain=…ReaderPlayProbeKt -PdevArgs="game.nds|24"`);
in the app, pick the game, choose a model and press play as usual.

**A real model did it too**: `--model=llm` with a 9B model on the LM Studio box (`ornith-1.0-9b`), which only ever saw
the reader's values:

```text
#6   screen publisher logos (129) · menu no menu (0)  →  wait     →  screen: publisher logos → main menu (19)
#7   screen main menu (19) · menu main menu (70)     →  press_a  →  nothing changed
#8   screen main menu (19) · menu main menu (70)     →  press_down+wait  →  nothing changed
#10  screen main menu (19) · menu main menu (70)     →  press_left+wait  →  nothing changed
#12  screen main menu (19) · menu main menu (70)     →  press_a  →  screen: main menu → single player menu (18)
```

It explored when A did nothing (it was told it was stuck), then got the menu to move. That is the whole point: a game
nobody wrote code for, played by a model that can only see the numbers a calibration found.

**And it is told what the picture is doing — both screens of it**, because a reader alone cannot say "a dialogue box is
open and it wants A". A DS game uses both screens, so the request carries a `screen` block with a `top` and a `bottom`:
what each one shows (the model's own reading of that screen, when it has vision), how long each has been still, and
which one changed while the action ran (a still screen is a game waiting for input; the screen that answered says so).
Both screens are sent to the model as **two labelled pictures**, cropped, instead of one combined frame — one image
would give each screen half the detail, which is what small dialogue text suffers from. The panel shows the same line
under **Screen**, so it is visible at a glance whether the chosen model reads the screens at all. Details, and the
`dev/VisionCheck` tool that proves both screens are seen and told apart, in [`docs/readers.md`](docs/readers.md).

## Running from source

Requirements: JDK 21 and the ROM as above. Then an API key for the AI you want to try (or
[Ollama](https://ollama.com) for a local model, e.g. `ollama pull gemma4:26b`: a mixture-of-experts model, 4B
parameters active, so fast enough on a laptop; small local models play poorly, cloud models play much better).

```bash
./gradlew run --args="/path/to/your game.nds"
```

Gradle downloads the libraries from Maven Central (libretro-kmp and
[pokemon-client](https://github.com/kotlinds/pokemon-client) included). While the app depends on a `-SNAPSHOT` of
pokemon-client (see `gradle/libs.versions.toml`), clone that repository too and run `./gradlew publishToMavenLocal`
in it first. To build the jar that runs on every platform yourself: `./gradlew packageUberJarForCurrentOS
-Puniversal`, written to `build/compose/jars/` (the name carries the platform it was built on, but `-Puniversal` adds
the other platforms' natives).

## Files and the emulator core

**Bring the ROM yourself**: a `.nds` file you dumped from a cartridge you own. Nothing here provides, downloads or
links to a ROM, a BIOS or DS firmware, and the app identifies the game from the ROM's own header. The file name names
the save: `heartgold-us.nds` → `heartgold-us.sav`.

The emulator core is the only other file. It is downloaded on first launch from the libretro buildbot. Or copy it
yourself, built for the target platform, into the data directory's `cores/` folder. The data directory is
`~/.ai-plays-online`, i.e. `%USERPROFILE%\.ai-plays-online` on Windows (the old `~/.ai-plays-pokemon` is still read
when the new one does not exist).

| Platform                      | Core file (DeSmuME)      | Buildbot folder                        |
|-------------------------------|--------------------------|----------------------------------------|
| macOS (Apple Silicon / Intel) | `desmume_libretro.dylib` | `apple/osx/arm64` / `apple/osx/x86_64` |
| Linux x86-64                  | `desmume_libretro.so`    | `linux/x86_64`                         |
| Windows x86-64                | `desmume_libretro.dll`   | `windows/x86_64`                       |

The URL is `https://buildbot.libretro.com/nightly/<folder>/latest/desmume_libretro.<ext>.zip`. No BIOS or firmware
is needed.

The core is checked against a pinned SHA-256 where one is pinned. Today that is only macOS Apple Silicon, the build
we tested; other platforms take whatever build is "latest". If the buildbot has moved on, the app stops with
"Unexpected … build". Then either:

- copy the core from a computer where it works;
- or test the new build and update the hash in `LibretroCoreSpec`.

To carry a game over, copy `saves/<rom name>.sav` from the data directory. It is converted for the core
automatically.

## Using the app

In the window: the game on the left; on the right, choose the AI (Jev, or an LLM provider + model id), paste its API key
if needed, pick the mode and options, and **▶ Let … play** starts the autonomous loop. You can see exactly what the AI
receives ("What the AI sees" → Show JSON), and for each decision Jev's probability for every option, or the LLM's
reasoning and note.

**The game picker** (top of the panel) lists the ROMs in the ROMs folder (`~/.ai-plays-online/roms` by default,
`AI_PLAYS_ONLINE_ROMS_DIR` to point elsewhere) and shows, for each one, whether the AI gets the game's state
(*typed agent*) or only the screen, plus what we verified for that title. Picking another game saves the current one,
closes its console and opens the chosen ROM; **Browse…** takes any `.nds` file, and **Choose another game…** goes back
to the startup picker. Each game's prompt (objective and conventions) lives in its profile, and you can override any
profile — or add your own game — in `~/.ai-plays-online/games.json`; see [`docs/games.md`](docs/games.md).

**The walkthrough** (below it) is what the run is working on: the current chapter's goal and hints, a dropdown to pin
another chapter, and **Follow progress** — on, the app advances the chapter by itself (from the game's own verified
progress for Pokémon, or from a screen landmark for the other titles), off, only you move it. The text is data:
shipped with the app and overridable in `~/.ai-plays-online/walkthroughs/`; **Copy for editing** writes the current one
out. See [`docs/walkthroughs.md`](docs/walkthroughs.md).

**Claude without an API key**: choose LLM → *Claude (Claude Code login)* with a model like `sonnet` or `opus`. Each
decision runs `claude -p` (a few seconds of overhead) and counts towards your Claude plan's usage.

**An external agent through MCP**: select the *MCP* tab (it starts the server; the port can be changed there), then
connect the agent (see the quick start) and ask it to play. It gets four tools, always the same:

- `get_state`: the screen (with the ids of its entries), messages and events since the last call, the team, the
  battle (with the estimated effectiveness of each move at the Pokédex knowledge level), a text map of the
  surroundings with the ids of exits, people and signs, and the actions possible now with their valid values;
- `act`: one typed action (`{"type": "buy", "items": [{"item": "Poke Ball", "quantity": 5}]}`, `{"type": "go_to",
  "map": "Route 30"}`, `{"type": "attack", "move": "move:85"}`, `pc`, `step`, `set_options`, `soft_reset`...),
  optionally followed by up to 8 more (`then`), a note to itself and a short reasoning; a refused action always says
  why (typed error). The answer is compact by default (only what changed: messages, screen, position, team or "team
  unchanged"); `detail: "full"` returns the whole state like `get_state`;
- `lookup`: game knowledge from the ROM (species, moves, items, types, learnsets, TMs), within the knowledge level;
- `screenshot`: both screens, for anything the state doesn't describe.

The agent keeps its own context between calls, and the game is frozen while it thinks.

| Keys      | Action         | Keys  | Action                         |
|-----------|----------------|-------|--------------------------------|
| Arrows    | D-pad          | Space | AI play / pause                |
| X / Z     | A / B          | P     | Pause / resume the emulator    |
| S / A     | X / Y          | F     | Fast forward                   |
| Q / W     | L / R          | M     | Mute                           |
| Enter / ⌫ | Start / Select | F1–F4 | Load state (Shift+F1–F4: save) |
| Mouse     | Touch screen   | F12   | Save a RAM snapshot (debug)    |

You can play at the same time as the AI: the emulator merges both inputs.

### Playing online (netplay)

The app can drive the console through a **rollback session** instead of the free run, so two peers play
the same console over UDP (the engine's `host` / `join` demo, put behind the app's UI). This is the
**two-controller topology**: both players' inputs go into one emulated console. The DS local-wireless
topology (two consoles, one per peer) needs the patched melonDS link ABI and the
`libretro-rollback-libretro` adapter, which is not published for the app yet — see
`engine/libretro-rollback/docs/two-console.md`.

```bash
# Host (player 1), then join (player 2) — on the same machine or across a LAN
AI_PLAYS_ONLINE_NETPLAY=host AI_PLAYS_ONLINE_NETPLAY_PEER=127.0.0.1:7001 \
  ./gradlew startApp -PappArgs="/path/to/game.nds"
AI_PLAYS_ONLINE_NETPLAY=join AI_PLAYS_ONLINE_NETPLAY_PEER=127.0.0.1:7000 \
  ./gradlew startApp -PappArgs="/path/to/game.nds"
```

Both peers must run the **same ROM** (the handshake compares its SHA-256), the same core build and the
same engine settings (`mode`, input delay, prediction, snapshots): a mismatch is refused instead of
silently desyncing. Online, the app picks the fixed-RTC core build when there is one, and refuses
`rollback` mode on a core that is not rollback-safe (use `lockstep` for those, see
[Tested games](#tested-games)). Save states, fast-forward and music during pauses are off online (each
would desync the session), and the panel shows the session's frame, rollbacks and last event.

**Fair play.** The session only ever carries inputs: there is no command channel, and the client
library has no RAM-write API, so an agent or a user can only press buttons and touch the screen. What a
peer-to-peer session can and cannot prove is written down in the engine's README ("Integrity"): a
modified local client can still cheat, and that is what the relay/attestation tier is for.

**The AI cannot take an online controller yet.** Online, the session owns the frame clock, and an
action that steps the console itself would bypass it; `ConsoleHost.lease` refuses instead of
desyncing. The agent plays through the session's input queue next (see the plan in the panel).

### Configuration

Everything can be set in the app; settings are saved in `~/.ai-plays-online/config.properties` (next to the downloaded
core, in-game saves and save states). Environment variables take precedence:

| Environment variable                                                          | Config key              | Default                                |
|-------------------------------------------------------------------------------|-------------------------|----------------------------------------|
| `AI_PLAYS_ONLINE_ROM` (or `POKEMON_ROM`, or first argument)                    | `rom`                   | none: the game picker at startup        |
| `DECISION_BACKEND` (`mcp` / `llm` / `jev`)                                    | `backend`               | `mcp`                                  |
| `TYPESAFE_API_KEY`                                                            | `typesafe.apiKey`       | entered in the app                     |
| `JEV_MODEL`                                                                   | `typesafe.model`        | `jev-latest`                           |
| `JEV_ENDPOINT`                                                                | `typesafe.endpoint`     | `https://api.typesafe.ai/v1/systemone` |
| `LLM_PROVIDER` (`openai`, `anthropic`, `openrouter`, `ollama`, `claude_code`) | `llm.provider`          | `ollama`                               |
| `LLM_MODEL`                                                                   | `llm.<provider>.model`  | a default per provider                 |
| `LLM_THINKING` (Ollama reasoning models think first)                          | `llm.thinking`          | `false`                                |
| `MCP_PORT`                                                                    | `mcp.port`              | `3333`                                 |
| `EMULATOR_CORE` (`desmume` / `melonds`)                                       | `emulator.core`         | `desmume`                              |
| `START_MUTED`                                                                 | `start.muted`           | `false`                                |
| `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `OPENROUTER_API_KEY`                   | `llm.<provider>.apiKey` | entered in the app                     |
| `AI_PLAYS_ONLINE_DATA_DIR`                                                    | —                       | `~/.ai-plays-online`                   |
| `AI_PLAYS_ONLINE_ROMS_DIR`                                                    | `roms.dir`              | `<data>/roms` (the game picker's list) |
| `AI_PLAYS_ONLINE_NETPLAY` (`host` / `join`)                                   | `netplay.role`          | off (single player)                    |
| `AI_PLAYS_ONLINE_NETPLAY_PEER` (`host:port`)                                  | `netplay.peer`          | required when netplay is on            |
| `AI_PLAYS_ONLINE_NETPLAY_BIND`                                                | `netplay.bindPort`      | `7000` (host) / `7001` (join)          |
| `AI_PLAYS_ONLINE_NETPLAY_MODE` (`rollback` / `lockstep`)                      | `netplay.mode`          | `lockstep`                             |
| `AI_PLAYS_ONLINE_NETPLAY_INPUT_DELAY`                                         | `netplay.inputDelay`    | `2`                                    |
| `AI_PLAYS_ONLINE_NETPLAY_PREDICTION`                                          | `netplay.prediction`    | `8`                                    |
| `AI_PLAYS_ONLINE_NETPLAY_SNAPSHOTS` (`full` / `delta`)                        | `netplay.snapshots`     | `delta`                                |
| `AI_PLAYS_ONLINE_NETPLAY_KEYFRAME`                                            | `netplay.keyframe`      | `30`                                   |

**Jev without the cloud.** Jev itself is only served by TypeSafe (no public weights). `JEV_ENDPOINT` can point to any
server implementing the same `POST /v1/systemone` protocol, e.g. an open-weight reproduction of Jev served locally.

## Provenance and licenses

This repository is a fork of [kotlinds/ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon) and an
orchestration and optimization layer over the projects we compiled, patched and tested — we do not reimplement an
emulator, a libretro frontend or a game client:

| Project | What we use it for | License |
|---|---|---|
| [ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon) | the app this fork starts from | **none declared upstream** (all rights with its author; our changes are listed in the git history) |
| [pokemon-client](https://github.com/kotlinds/pokemon-client) | reads a game from RAM/ROM into a typed model, typed actions | Apache-2.0 |
| [libretro-kmp](https://github.com/kotlinds/libretro-kmp) | libretro frontend (Kotlin Multiplatform) | Apache-2.0 |
| [melonDS](https://github.com/libretro/melonDS) / [DeSmuME](https://github.com/libretro/desmume) | the DS cores the app downloads | GPL-3.0 / GPL-2.0 (not redistributed here) |
| [libretro-rollback](https://github.com/SamurAI-Official/libretro-rollback) | rollback netcode engine, delta snapshots, DS local wireless; vendored at `engine/libretro-rollback` | Apache-2.0 (GGPO's MIT license reproduced inside) |
| [Koog](https://github.com/JetBrains/koog), [Compose Multiplatform](https://github.com/JetBrains/compose-multiplatform), [Ktor](https://ktor.io), [MCP Kotlin SDK](https://github.com/modelcontextprotocol/kotlin-sdk) | AI clients, UI, MCP server | Apache-2.0 / MIT |

Our patches to the DS cores (fixed RTC for determinism, dirty pages for delta snapshots, frontend-routed local
wireless for two-console play) live in the engine repository under `docs/patches`, with build instructions and the
pinned hashes of the builds we tested. See [`NOTICE`](NOTICE) for the full attribution text, and our own `LICENSE`
(Apache-2.0) for the code we added. **No game data is distributed by this project.**

## How it works

```
 ┌──────────────── app (me.nathanfallet.aiplayspokemon) ─────────────────┐
 │ ui: EmulatorScreen · ControlPanel        mcp: GameMcpServer (4 tools)  │
 │ agent: GameSession · PokemonPlayer       decision: Jev · LLMs · claude │
 │ emulator: ConsoleHost (one console thread, drivers: idle/human/agent)  │
 │   └ LibretroConsole ── libretro-kmp ── DeSmuME / melonDS core          │
 └───────────────┬───────────────────────────────────────────────────────┘
                 │ ConsolePort (step, read RAM, frames, save states)
 ┌───────────────▼──── pokemon-client (dev.kotlinds.pokemonclient) ──────┐
 │ state: GameState, sealed Screen (entries, cursor, topology), events   │
 │ runtime: ActionScope (self-checking taps), Recorder                    │
 │ actions: Navigator (verified cursor moves), typed GameActions, recipes│
 │          ActionRegistry (schema, availability, typed errors)           │
 │ world: Area, Pathfinder (levels, ledges, surf, triggers)  view: MapView│
 │ data: GameData, Lookup, KnowledgeLevel                                 │
 │ games: gen4 (shared Gen 4 engine) · hgss · platinum — RAM decoders,   │
 │        ROM maps and data (kotlinds), story table                       │
 └────────────────────────────────────────────────────────────────────────┘
```

The app, and the [pokemon-client](https://github.com/kotlinds/pokemon-client) library (its own repository, on Maven
Central; a `-SNAPSHOT` from `mavenLocal()` while developing):

- **`pokemon-client`** (Kotlin Multiplatform, as much as possible in `commonMain`) is the library: it knows Pokémon,
  not emulators. A game reads the RAM into one common, typed model: `GameState` with a sealed `Screen` (every menu
  has its entries with **stable, language-independent ids** like `option:yes`, `mon:8dd175d1.76f3a6fb`, `move:85`,
  its cursor and the exact D-pad topology). Actions are typed (`GameAction`) and carried out by **recipes** (one class
  hierarchy, a game overrides
  only the steps that differ) that never press blindly: the `Navigator` reads the cursor, moves it one verified tap at a
  time and confirms only on the
  target (3 corrections at most, then an explicit error). Movement uses the maps read from the **ROM** (tiles,
  heights, warps, events) with the live people and the game's script variables on top. Nothing is ever written to
  the game's RAM: everything goes through buttons and the touch screen, like a player.
- **`pokemon-client-libretro`** (JVM, same repository) plugs the library into a libretro core: `LibretroConsole` (the
  `ConsolePort` over libretro-kmp), the cores, save formats, and a headless **bench** that runs commands and actions
  on a ROM without the app. Bringing up a new game happens there.
- **the app** runs the emulator (through `pokemon-client-libretro`
  and [libretro-kmp](https://github.com/kotlinds/libretro-kmp), a libretro core loaded in process) on
  a single console thread that drives time (agents get a lease; the human can always take over), and connects the
  deciders: our loop (`PokemonPlayer` with Jev, LLMs through Koog, or `claude -p`) or an external agent through MCP.

Tests: `./gradlew test`; the library's own tests (and the ROM tests) live in its repository. Coverage with
`./gradlew koverHtmlReport`.

### Pure, assisted, hybrid: why several modes?

The point of the project is to see what an AI does when it faces the game. Pure mode gives it exactly what a human
has: the screen (as text) and a controller, no pathfinding, no macros. But every project that finished a Pokémon
game with an LLM (Claude/Gemini/GPT Plays Pokémon, the PokéAgent challenge) gave it navigation tools, and a decision
model like Jev needs options that already carry meaning. Assisted mode is that: code does the walking and the cursor
moves, the AI still decides where to go and what to do. Hybrid mode adds a slower planner for when the fast model is
unsure. Having the three side by side, with milestones recorded for each run, is what lets us measure the difference.

## Extending

- **Another game**: the app identifies a ROM by its header and looks up a *game profile* (name, per-game objective and
  conventions prompt, whether the AI gets RAM state or the screen, preferred core) and a *walkthrough* (the chapters of
  its progression, with hints and, for games we only see the screen of, the screen each chapter happens on). Profiles
  and walkthroughs ship for the titles in [Tested games](#tested-games) and yours can be added without touching the
  code — see [`docs/games.md`](docs/games.md) and [`docs/walkthroughs.md`](docs/walkthroughs.md). Full RAM-level
  support for a new game is a reader in [pokemon-client](https://github.com/kotlinds/pokemon-client), see its
  [adding-a-game guide](https://github.com/kotlinds/pokemon-client/blob/main/docs/adding-a-game.md): implement
  `PokemonGame` (RAM → `GameState`, screen decoders, optionally `world` and `data` from the ROM), register its ROM code
  in `PokemonGames`, and give it its recipes; the navigator, the registry, the MCP server and every decider work
  unchanged.
- **Another emulator**: implement `ConsolePort` (or add a `LibretroCoreSpec` entry for another libretro core). The
  patched builds with rollback-safe save states are described in
  [libretro-rollback](https://github.com/SamurAI-Official/libretro-rollback).
- **Another AI**: any model Koog supports is a provider + model id away (`LlmProvider`); anything else implements
  `DecisionModel`.
- **Better eyes**: the more faithfully the game reader describes the screen (menu options, text in apps...), the better
  the model can play, without us deciding for it.
