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

## Quick start

You need **Java 21** (for example [Temurin 21](https://adoptium.net/temurin/releases/?version=21)) and a **Nintendo DS
ROM that you dumped yourself** (no ROM is provided — see [Tested games](#tested-games) for the titles we verified).

1. Build the app (or take the jar from a release of this repository). The same jar runs on macOS, Linux and Windows
   (x86-64 and Apple Silicon).
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
./gradlew build          # compiles + 154 tests
./gradlew startApp       # runs the app (the picker: no game is loaded until you choose one)
./gradlew packageUberJarForCurrentOS -Puniversal   # the jar that runs on every platform
```

A ROM on the command line (`./gradlew startApp -PappArgs="/path/to/game.nds"`) opens that game directly.

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
| Phantasy Star 0 | `C2SE` | screen only | 6 chapters, front-end landmark captured | melonDS ✅ / DeSmuME ✅ | 600-frame determinism per core |
| Age of Empires: The Age of Kings | `AE2E` | screen only | 5 chapters, hints only | melonDS ✅ / DeSmuME ✅ | 600-frame determinism per core |

All seven boot and run on both patched cores; 12 of the 14 game/core combinations pass a 600-frame save/load
determinism check (the two that do not are melonDS's, and only for those two titles). The raw numbers — state size,
save/load cost, what the local-wireless probe found — are in [`docs/games.md`](docs/games.md), along with the exact
commands that produced them. Local wireless is measured but not yet reached by scripted inputs on any title: that
needs a savestate taken inside a multiplayer screen (procedure in the same document).

Other DS games run too: an unknown ROM gets the generic profile, which is playable with the human in control.

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
