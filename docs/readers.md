# Typed readers: what the app reads from a game

A **reader** is what makes a game *typed*: instead of the AI seeing only a picture of the screen, the app reads the
game's own state — which screen it is on, what the cursor is on, how much wood the player has — and gives that to the
model, and to the walkthrough (a chapter can be verified from it, exactly like a Pokémon game's story flags).

A reader is **data**, not code: `readers/<id>.json`. That is what lets a game nobody wrote a reader for become typed,
and what this document is about: how the file is shaped, and how `dev/RamProbeTool` **calibrates** it by driving the
game and watching its memory.

## Where a reader lives

| Where | What |
|---|---|
| `<data>/readers/<id>.json` | the user's own (where a calibration writes its draft: `--write=auto`) |
| `readers/<id>.json` on the classpath | the one shipped with the app (`src/main/resources`) |

The first that exists wins, so a user can correct or extend a shipped reader without touching the app. The file's name
is the game's profile id (`games/GameCatalog`: `age-of-empires-aok`, `mario-kart-ds`, ...), which is how a ROM is
matched to its reader. A game with a reader is shown as **typed** in the game picker, its values are shown live in the
panel, and a walkthrough chapter whose milestone is `kind: memory` is verified from it.

## The format

```json
{
  "id": "age-of-empires-aok",
  "provenance": "calibrated",
  "observations": [
    {
      "name": "screen",
      "address": 37251014,
      "type": "u8",
      "values": { "19": "main menu", "83": "mission briefing", "84": "in mission" },
      "confidence": 1.0,
      "note": "0x023867c6: watched over a 6000-frame run; changed exactly at each screen"
    }
  ],
  "progress": [
    { "name": "in-mission", "address": 37251014, "type": "u8", "equals": 84, "note": "the mission is running" }
  ],
  "notes": "how it was calibrated, and what is still missing"
}
```

- **`address`** is an ARM9 bus address of main RAM (`0x02000000`..`0x023FFFFF`), written in decimal because it is JSON;
  every tool prints it as `0x…` (`RamProbe.address`).
- **`type`** is `u8`, `u16`, `u32`, `s8`, `s16`, `s32`, `bool`, `bits` (with `mask`/`shift`) or `ascii` (with `length`).
  `scale` multiplies the value before it is shown (a fixed-point position, for instance).
- **`values`** names the values the model should understand. Unlisted values are shown as numbers.
- **`confidence`** is what the calibration thought of it, and **`note`** is the evidence. Neither is decoration: a
  reader is a claim about someone else's program, and these are how a later reader checks the claim.
- **`progress`** entries are named signals a walkthrough's `kind: memory` milestone refers to by `signal`.

## Calibrating one

```bash
# 1. What moves with the screen? Drive the game through its menus and let the tool look.
./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.RamProbeToolKt \
  -PdevArgs="game.nds|4600|desmume|--script=A@700-702;A@1000-1002;A@1300-1302|--calibrate=mode|--write=auto"

# 2. Confirm a candidate by hand: it should read one value per screen, and change exactly when the screen does.
./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.RamProbeToolKt \
  -PdevArgs="game.nds|6000|desmume|--script=…|--every=150|--watch=0x023867c6:u8"
```

`--calibrate=mode` finds the addresses whose value is a function of the screen; `counter` finds ones that only ever go
one way (clocks, scores); `input` finds ones that moved on every press of the script and never otherwise. `--every`
and `--samples` decide how often memory is snapshotted (each snapshot is 4 MB).

Two things the tool learned the hard way, both built in now:

- **Screens must be clustered.** Most games animate their menus, so almost every sampled frame is a *different* hash;
  comparing raw hashes made any moving address look like it followed the screen. Screens within 6 bits of each other
  are now one screen (`RamSignals.cluster`), which is what makes `consistency 1.00` mean something.
- **A stable screen is what you want.** An in-game view (a map, a HUD counting up) drifts 10+ bits from frame to frame
  and cannot be a signal; the useful ones are menus, briefings, mode ids — things that hold still.

Then name what you found (`--write` writes placeholders like `signal_1`) and drop what is wrong. The AoE reader is the
worked example: `0x023867c6` was found by `--calibrate=mode`, confirmed by `--watch` (boot 0, logos 129, main menu 19,
single player 18, select campaign 64, select mission 231, briefing 83, **in mission 84** — held for 2,250 frames), and
its `in-mission` progress signal now verifies a walkthrough chapter:

```text
chapter aoe:boot (Boot to the front end) reached at frame 460      # screen landmark
chapter aoe:start (Start the campaign) reached at frame 1650       # screen landmark
chapter aoe:cursor (Learn the controls) reached at frame 3480      # the reader's in-mission signal
```

## Playing through a reader

`ReaderPlayer` (`agent/ReaderPlayer.kt`) is the second half of a typed agent: the same observe → decide → act loop as
the Pokémon player, but its state is the reader's values and its actions are the console's own (`ReaderActions`:
press a button, hold a direction, tap the touch screen, wait). The model is told the game, its objective, the chapter,
the reader's values, and — after each action — **what changed** (`screen: 19 → 18`), plus a `stuck` note once actions
stop changing anything. Nothing in it is game-specific: another calibrated reader, another game.

What it is not: it cannot see the picture (the `vision` milestone kind and a multimodal provider would be that), it
cannot act on anything the reader does not describe (a calibrated reader usually describes a handful of numbers), and
its action vocabulary is coarse — a 3x3 touch grid, presses and holds. A game needing precise stylus work needs a
better reader (a `cursor.x`/`cursor.y` observation) before the model can aim.

Verifying it without a model: `dev/ReaderPlayProbe` plays a ROM headlessly with a **scripted** policy, so the loop
itself (observe → decide → lease → execute → see what changed) can be tested on a real game:

```bash
./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.ReaderPlayProbeKt \
  -PdevArgs="game.nds|24|desmume"
```

Against AoE it boots the game and walks it into the mission, driven only by the reader's `screen` value:

```text
#11  screen select campaign (64) · menu no menu (0)   →  press_a  →  screen: select campaign → select mission
#12  screen select mission (231) · menu briefing (16) →  press_a  →  screen: select mission → mission briefing (83)
#18  screen mission briefing (83) · menu no menu (0)  →  press_a  →  screen: mission briefing → loading the mission (29)
#19  screen loading the mission (29)                  →  wait     →  screen: loading the mission → in mission (84)
decisions: 24, error: none
progress signal 'in-mission': true
```

With `--model=llm` the same probe asks the app's configured model instead, which is the real thing: a 9B model on an
LM Studio box played AoE from the reader's values alone, exploring by itself when a button did nothing (the `stuck`
note), and getting the main menu to move on its own:

```text
#7   screen main menu (19) · menu main menu (70)  →  press_a  →  nothing changed
#8   screen main menu (19) · menu main menu (70)  →  press_down+wait  →  nothing changed
#12  screen main menu (19) · menu main menu (70)  →  press_a  →  screen: main menu → single player menu (18)
```

A reasoning model spends its tokens thinking before answering, so give it time (and a `max_tokens` big enough for both:
the client asks for 2048 and says so when the model answers nothing).

## What a reader is not

- **It cannot invent meaning.** The calibration finds addresses and their behaviour; calling one "wood" is a judgment
  you make by watching the game, and `confidence`/`note` are where you say how sure you are.
- **It does not write memory.** Readers are read-only, on purpose: the AI acts through buttons and the touch screen.
- **It is not a replacement for a hand-written reader.** A Pokémon game's reader (pokemon-client) knows the game's
  structures, maps and menus; a calibrated reader knows the few numbers someone confirmed. For a game nobody has
  written code for, that is the difference between "the AI is blind" and "the AI can see which screen the game is on".
- **The AI acts through it, not on it.** `ReaderPlayer` plays with the console's own actions; a reader never writes
  memory, so a game that needs a cheat or a memory patch is out of scope by design.
