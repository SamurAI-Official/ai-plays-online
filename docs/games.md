# Tested games

Every title we run ourselves, with the numbers behind the table in the [README](../README.md#tested-games). The
project ships **no game data**: you bring your own ROM, dumped from a cartridge you own, and the app identifies it
from the ROM header (`0x00` title, `0x0C` game code).

## How a row is produced

```bash
# 1. Determinism + save/load cost + state size, per core (patched builds with a fixed RTC)
./gradlew :demo:run --args="probe --core ~/.ai-plays-online/cores/melonds_libretro.dylib \
  --rom '/path/to/your game.nds' --warmup 300 --frames 600 --repeats 30 --hash-ram"
./gradlew :demo:run --args="probe --core ~/.ai-plays-online/cores/desmume_libretro.dylib \
  --rom '/path/to/your game.nds' --warmup 300 --frames 600 --repeats 30 --hash-ram"

# 2. Local wireless (only games with a multiplayer screen reachable from the title screen)
./gradlew :demo:run --args="link-probe --core ~/.ai-plays-online/cores/melonds_libretro.dylib \
  --rom '/path/to/your game.nds' --frames 1800"
```

`probe` prints `determinism: OK` or the first differing frame; a core that fails cannot use rollback (lockstep only).
`link-probe` prints how many radio packets the game transmitted: zero means the game never opened local wireless
during the run, not that the link is broken.

The cores must be the patched builds from
[libretro-rollback](https://github.com/SamurAI-Official/libretro-rollback) (`docs/patches`, plus `docs/melonds-rtc.md`
and `docs/desmume-rtc.md` for the build steps). A patched core's savestate differs from a stock one, so both peers must
use the same build.

## Results

Measured on macOS (Apple Silicon), 300 warmup frames then 600 frames with a save/load per frame, main RAM hashed
(`--hash-ram`), melonDS 0.9.3 (`99e84ea1…`) and DeSmuME from libretro `master`, both with our RTC patch applied.

| Game | Core | State size | Save | Load | Determinism (600 frames) | Rollback |
|---|---|---|---|---|---|---|
| Age of Empires: The Age of Kings (USA) | melonDS | 6.2 MiB | 2.88 ms | 3.56 ms | OK | yes |
| Age of Empires: The Age of Kings (USA) | DeSmuME | 12.0 MiB | 2.92 ms | 13.8 ms | OK | yes |
| Final Fantasy III (USA) | melonDS | 6.3 MiB | 3.32 ms | 3.99 ms | **FAILED at frame 0** | lockstep only |
| Final Fantasy III (USA) | DeSmuME | 12.0 MiB | 3.79 ms | 8.07 ms | OK | yes |
| Final Fantasy IV (USA) | melonDS | 6.3 MiB | 2.85 ms | 3.58 ms | OK | yes |
| Final Fantasy IV (USA) | DeSmuME | 12.0 MiB | 3.25 ms | 6.07 ms | OK | yes |
| Mario Kart DS (USA) | melonDS | 6.5 MiB | 4.04 ms | 4.57 ms | **FAILED at frame 0** | lockstep only |
| Mario Kart DS (USA) | DeSmuME | 12.0 MiB | 2.90 ms | 9.17 ms | OK | yes |
| Phantasy Star 0 (USA) | melonDS | 6.3 MiB | 3.45 ms | 4.46 ms | OK | yes |
| Phantasy Star 0 (USA) | DeSmuME | 12.0 MiB | 2.48 ms | 9.76 ms | OK | yes |
| Pokémon HeartGold (USA) | melonDS | 6.7 MiB | 6.26 ms | 4.85 ms | OK | yes |
| Pokémon HeartGold (USA) | DeSmuME | 12.0 MiB | 3.69 ms | 8.99 ms | OK | yes |
| Pokémon Platinum (USA) | melonDS | 6.7 MiB | 2.97 ms | 3.85 ms | OK | yes |
| Pokémon Platinum (USA) | DeSmuME | 12.0 MiB | 3.13 ms | 12.7 ms | OK | yes |

What the numbers say:

- **All seven titles boot and run on both cores** (600 frames each, video and audio produced).
- **12 of 14 core/game combinations are rollback-safe** after the fixed-RTC patch.
- **Final Fantasy III and Mario Kart DS fail at frame 0 on melonDS**: main RAM differs immediately after a
  save/load, which means melonDS's savestate does not capture something these two touch at boot (they pass on
  DeSmuME). Use DeSmuME for them, or run them in lockstep (`Mode.LOCKSTEP`, `--mode lockstep`), which never
  restores a state. Finding the missing field would be a melonDS patch of its own.
- **DeSmuME states are ~12 MiB (the full `retro_serialize_size`) and load slower** (6–14 ms vs 3–5 ms on melonDS),
  which is what a rollback pays per rewind. melonDS states are ~6.5 MiB because the core reports its real length.
- **Local wireless was not reached by the scripted inputs on any title**, including Mario Kart DS (its menus are
  touch-driven): `link-probe` reports `radio packets transmitted: 0`. It supports `--state`, so the way to close
  this is a savestate taken inside a multiplayer screen (see below).

## Local wireless: how to finish the check

```bash
# 1. Reach a local wireless screen in the app (e.g. Mario Kart DS: multiplayer, or Pokémon's Union Room),
#    save a state there (F1 in the app writes slot 1), then copy it out of the data directory.
# 2. Run the probe on that state: it should now report packets.
./gradlew :demo:run --args="link-probe --core ~/.ai-plays-online/cores/melonds_libretro.dylib \
  --rom '/path/to/your game.nds' --state /path/to/wireless.state --frames 1800"
# 3. Then the real thing: two peers, one console each.
./gradlew :demo:run --args="host --core ~/.ai-plays-online/cores/melonds_libretro.dylib \
  --rom '/path/to/your game.nds' --state /path/to/wireless.state --link --peer 127.0.0.1:7001"
./gradlew :demo:run --args="join --core ~/.ai-plays-online/cores/melonds_libretro.dylib \
  --rom '/path/to/your game.nds' --state /path/to/wireless.state --link --peer 127.0.0.1:7000"
```

The ABI itself is already proven without any game content: the patched core's `t6_link_selftest()` round-trips a
probe through its own radio routing, and `LibretroLinkIntegrationTest` asserts it.

## Game profiles

A profile tells the app how to treat a ROM, keyed by its game code:

| Field | Meaning |
|---|---|
| `id`, `title` | how the app names the game (dropdown, run logs, narration) |
| `gameCode` / `romTitle` | the 4-byte code at `0x0C` and the 12-byte title at `0x00` the ROM is matched on |
| `agent` | `TYPED` (a RAM reader exists: the AI gets game state) or `SCREEN` (the AI gets the screen) |
| `objective` | the per-game objective put in the prompt ("win the Grand Prix cups", "follow the story"...) |
| `conventions` | the per-game prompt section on how the game behaves (controls, menus, saving, what is worth doing) |
| `touch`, `preferredCore` | whether the stylus matters, and which core to start with |
| `walkthroughId` | which walkthrough file to use (defaults to `id`) |

Adding a game means adding a profile (built-in or a user override in `<data>/games.json`) and a walkthrough
(`<data>/walkthroughs/<id>.json`); full RAM support for a new game is a reader in
[pokemon-client](https://github.com/kotlinds/pokemon-client).

## Walkthroughs

| Game | Chapters | Progress comes from | Landmark |
|---|---|---|---|
| Pokémon HeartGold | 12 (generated from its story table) | the game's own flags/vars, verified | n/a |
| Pokémon Platinum | — | (needs its story table) | n/a |
| Mario Kart DS | 7 | the front-end screen, then the panel | `dhash:0110011003c50200` (captured at frame 432, stable 871 frames) |
| Final Fantasy III | 5 | the panel | — |
| Final Fantasy IV | 5 | the panel | — |
| Phantasy Star 0 | 6 | the title screen, then the panel | `dhash:2362248c00d82000` (frame 1035, 220 frames) |
| Age of Empires: The Age of Kings | 5 | the panel | — |

A landmark is a 64-bit difference hash of the screen, captured with `dev/FrameHashTool`
(`./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.FrameHashToolKt -PdevArgs="rom.nds|2600|melonds"`),
which prints the stable screens of a run with how long each lasted. The other chapters advance by hand from the panel
until someone captures their screens; the format and the authoring steps are in
[`docs/walkthroughs.md`](walkthroughs.md).
