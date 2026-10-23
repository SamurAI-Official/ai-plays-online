# Walkthroughs

A walkthrough tells the AI (and you) what "making progress" means in a game: an ordered list of chapters, each with a
goal and a few concrete hints. It is **data**, not code, so a corrected route, a translation or a game we don't ship
needs no rebuild.

## Where the files live

| Source | Path | Wins over |
|---|---|---|
| Your own | `~/.ai-plays-online/walkthroughs/<id>.json` | the shipped one |
| Shipped with the app | `src/main/resources/walkthroughs/<id>.json` | — |

`<id>` is the game profile's id (`mario-kart-ds`, `pokemon-heartgold`…). A game with no file simply plays with its
profile prompt: the panel says so instead of failing. The panel's **Copy for editing** button writes the shipped
walkthrough into your data directory, ready to change; `provenance` in the file says where the text came from
(`hand-written`, `community`, `imported`).

## Format

```json
{
  "id": "mario-kart-ds",
  "provenance": "hand-written",
  "chapters": [
    {
      "id": "mkds:boot",
      "title": "Boot to the front end",
      "goal": "Get past the boot and intro to the game's front end.",
      "hints": ["The DS boots into an intro; START or A skips it."],
      "milestone": {
        "kind": "frameHash",
        "hashes": ["dhash:0110011003c50200"],
        "threshold": 6,
        "minStableChecks": 3
      }
    },
    {
      "id": "hgss:badge_zephyr",
      "title": "Falkner at the Violet City Gym",
      "goal": "Challenge Falkner at the Violet City Gym",
      "hints": ["Follow Route 29 west to Cherrygrove City…"],
      "steps": ["johto:talk_to_mom", "johto:leave_house"]
    }
  ]
}
```

- **`goal`** goes into the prompt as `walkthrough.goal`, **`hints`** as `walkthrough.hints` (the first six).
- **`steps`** (typed games only) are the story-table step ids the chapter covers, so the app knows the chapter is
  reached from the game's own verified progress — it never guesses.
- **`milestone`** (games we only see the screen of) is how the app can tell the chapter's screen is on screen:
  - `frameHash` / `template`: the frame (or `region`, `[x, y, width, height]`) is reduced to a 64-bit difference hash
    and compared with `hashes` (`dhash:…`), tolerating up to `threshold` bits of difference for `minStableChecks`
    consecutive checks. Cheap, offline, no model call.
  - `vision`: a question for the multimodal provider (`question`, at most once every `everySeconds`). Declared in the
    data, not used yet: it belongs to the screen agent.
- A chapter without a milestone is advanced by hand from the panel (chapter dropdown) or by the model.

## Authoring a chapter

1. Add the chapter to the JSON (goal + hints; our own words, see below).
2. If the game has a typed reader, add its story step ids — that is the most reliable way, and the app verifies them.
3. Otherwise capture the screen's hash:

   ```bash
   ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.FrameHashToolKt \
     -PdevArgs="/path/to/game.nds|2600|desmume"
   ```

   It runs the game headless and prints the stable screens with their `dhash:…` reference and how long they lasted.
   Copy the hash of the screen you want (a title screen, a menu, a save prompt) into `hashes`. Use several references
   when the screen animates, and raise `threshold` or `minStableChecks` if it fires on the wrong screen.
4. For anything deeper than the front end, drive the game there while it records (`;` separates the steps, frame 0 is
   the first frame of the run):

   ```bash
   ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.FrameHashToolKt \
     -PdevArgs="game.nds|3600|desmume|--png=/tmp/screens|--script=A@700-702;A@1000-1002;A@1300-1302"
   ```

   `--png=<dir>` writes one PNG per stable screen (`<frame>-<hash>.png`), so you can see what each hash is;
   `--pngAt=1200` adds a specific frame. `--state=<file>` starts from a savestate instead of a cold boot. A touch is
   `TOUCH:128,96@300-302` (pixels of the touch screen), buttons are `A@10-12`, `A+DOWN@20`, and `NONE@0-2000` holds
   nothing for a while.

   When the screen you want is not stable (an in-game view with a moving map, a HUD counter), do not landmark it:
   hash a `region` that is stable instead (Age of Empires' menu is the bottom screen, `[0, 192, 256, 192]`), and check
   how far the frames actually drift before choosing. Two screens 10+ bits apart will not match a `threshold` of 6.
5. Verify before committing: `--check=<walkthrough id>` follows the walkthrough with the app's own watcher and prints
   the frame each chapter's milestone fires on. It catches a landmark that fires too early (on an intro screen whose
   coarse hash happens to match) as well as one that never fires:

   ```bash
   ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.FrameHashToolKt \
     -PdevArgs="game.nds|2200|desmume|--check=age-of-empires-aok|--script=A@700-702;A@1000-1002"
   ```

   ```text
   chapter aoe:boot (Boot to the front end) reached at frame 436
   chapter aoe:start (Start the campaign) reached at frame 1629
   walkthrough age-of-empires-aok: no milestone fired for aoe:cursor, aoe:economy, aoe:army, aoe:mission
   ```
6. Run the app: the panel shows the chapter, and **Follow progress** decides whether the app may advance it by itself.

## What "verified" means

| | Typed games (Pokémon) | Screen-only games |
|---|---|---|
| Progress | read from the game's flags/vars: exact | a screen landmark, or your hand |
| Chapter text | generated from the story table (`dev/HgssWalkthroughGenerator`) | written by us, in the JSON |
| Trust | a wrong chapter means a wrong reader | a wrong chapter means a wrong guide |

## Provenance and rights

Walkthrough text here is **ours**: what the controls do, what the game asks for, where to go. Do not paste text from a
published guide, a wiki or a scanned manual into these files — a hint must be our own sentence, and `provenance` must
say where a file came from. Users may keep their own file (including one they imported) in their data directory; that
file is theirs and never part of this repository.
