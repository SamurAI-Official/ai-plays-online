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

Filled in as we run them; `—` means not run yet. Measured on macOS (Apple Silicon), melonDS 0.9.3
(`47b32511…`/`99e84ea1…`) and DeSmuME from libretro `master`.

| Game | Core | Boots | Determinism (600 frames) | State size | Save / load | Local wireless |
|---|---|---|---|---|---|---|
| Pokémon HeartGold (USA) | melonDS (patched) | yes | OK (1,000 frames, per-frame save/load) | ~6.7 MiB | ~3.1 ms / 0.0 ms | no multiplayer screen reached |
| Pokémon HeartGold (USA) | DeSmuME (patched) | yes | OK (1,000 frames) | ~11.7 MiB | ~2.8 ms / 0.0 ms | n/a (core has no Wi-Fi) |
| Pokémon Platinum (USA) | melonDS (patched) | yes | — | — | — | — |
| Pokémon Platinum (USA) | DeSmuME (patched) | yes | — | — | — | — |
| Mario Kart DS (USA) | melonDS (patched) | — | — | — | — | — |
| Mario Kart DS (USA) | DeSmuME (patched) | — | — | — | — | — |
| Final Fantasy III (USA) | melonDS (patched) | — | — | — | — | n/a |
| Final Fantasy III (USA) | DeSmuME (patched) | — | — | — | — | n/a |
| Final Fantasy IV (USA) | melonDS (patched) | — | — | — | — | n/a |
| Final Fantasy IV (USA) | DeSmuME (patched) | — | — | — | — | n/a |
| Phantasy Star 0 (USA) | melonDS (patched) | — | — | — | — | — |
| Phantasy Star 0 (USA) | DeSmuME (patched) | — | — | — | — | — |
| Age of Empires: The Age of Kings (USA) | melonDS (patched) | — | — | — | — | — |
| Age of Empires: The Age of Kings (USA) | DeSmuME (patched) | — | — | — | — | — |

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

Adding a game means adding a profile (built-in or a user override in `<data>/games.json`); full RAM support for a new
game is a reader in [pokemon-client](https://github.com/kotlinds/pokemon-client).
