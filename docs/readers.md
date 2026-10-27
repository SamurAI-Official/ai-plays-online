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
  reader is a claim about someone else's program, and these are how a later reader checks the claim — the app also
  rewrites them itself as it plays, from what it sees of the values (see [Checking a reader still holds up](#checking-a-reader-still-holds-up)).
- **`progress`** entries are named signals a walkthrough's `kind: memory` milestone refers to by `signal`.

### Values made of more than one address

A game rarely keeps what matters in one byte. These types read the shapes that showed up in practice, without letting
a reader compute anything it likes (every one is bounded, and a wrong address is visible as a wrong value):

| `type` | What it reads |
|---|---|
| `u8_array`, `u16_array` | `length` entries starting at `address + index * step`: a party, a menu, a bag. Shown as a list. |
| `pair` | `address` and `address2`, both as `type2`: a position, a cursor, `(x, y)`. |
| `expr` | `address` (as `type1`) combined with `address2` (as `type2`) by `op` (`add`, `sub`, `mul`, `div`, `shl`, `shr`, `and`, `or`), the second multiplied by `factor` first: a 16-bit value split over two bytes, an offset from a base. |
| `pointer` | the `u32` at `address`, then `type2` at `target + offset` — **only** when the target lands in main RAM, otherwise `out of range`. |
| `bits` + `bitNames` | a byte of flags shown by name (`{"0": "paused", "3": "in battle"}`) instead of as a number. |
| any type + `guardAddress`/`guardEquals` | the value is only read while another address holds a value; otherwise it reads `not now` (a menu cursor on the field is not a value). |

```json
{
  "name": "position",
  "address": 34670141,
  "type": "pair",
  "address2": 34670143,
  "type2": "s16",
  "note": "x and y of the player, in tiles: checked against the screen over a 3000-frame run"
}
```

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

## The app learns one while you play

Nobody has to run a calibration by hand for a ROM: **the app learns a reader while the game is played** and writes it
itself. `emulator/ReaderCalibrator` is fed by the app every time it looks at the game (a few times a second) — the
picture (`FrameHash` of the frame) and the memory — and it is the same learner `dev/ReaderLearnProbe` drives headlessly.

How it decides, and why it is not guesswork:

- **One part of memory at a time.** Main RAM is far too big to judge every address on every look, and the tens of
  thousands of addresses that move while a game boots would swamp anything worth keeping. The learner examines 128 KiB,
  with a baseline taken when it started that part, then moves on: a session covers all of a DS's 4 MiB in 32 parts. Each
  part is judged on what changed *within* it, which is where a game keeps "which screen am I on". A part is only left
  behind once the game has shown it at least two screens, or after 96 looks.
- **Evidence, not guesswork.** An address is a signal only if it mostly agrees with the screen: one value per screen,
  held on 90%+ of the looks at it. A frame counter or an animation step changes constantly, so it never agrees.
- **Verification for the rest of the run.** A candidate is then read on **every** later look: if a screen that comes
  back gives it a different value, it is refuted and never considered again. That is what tells a screen id from a
  clock — a counter that happens to move once per screen looks perfect until a screen comes back.
- **It refuses to write junk.** A reader is only written when a signal separates at least 3 screens, has survived 8+
  looks of checking, and was seen again on a screen that came back. Until then the panel says what it is still missing.
- **It also proposes what counts and what the game says.** A reader is not only "which screen is up":
  - a **counter** (`u16`/`u32`, confidence 0.7) is a value that, within one part of memory, only ever went one way,
    moved at least twice, and did *not* move on most looks — money, a score, a level. A frame counter rises on every
    look, so it is excluded; a menu cursor or a health bar goes both ways, so it is excluded too; two (or four)
    printable characters are text, not a number.
  - a **text** run (`ascii`, confidence 0.6) is a run of at least 6 printable characters ended by a zero byte whose
    content *changed* while the game was played — a dialogue box, a label. A string nobody rewrites is not proposed
    (and binary data that merely looks printable is not: the run has to end at a zero byte).
  A draft keeps 4 screen signals, 2 counters and 1 text run; a counter and a text run are only written **with** a
  screen signal, because a reader without `screen` would make the game typed while telling the agent nothing about
  where it is.
- **What it learned is not lost when the game closes.** The evidence — the screens seen, the signals found, how often
  each was checked, and the part of memory the sweep had reached — is written to
  `<data>/readers/<id>.learning.json` as it goes (every few seconds, and once more on close). The next session resumes
  there instead of starting at the first byte of memory: the part that was being examined is re-baselined (its tallies
  belonged to a RAM baseline that is gone), everything before it is kept. `romSha256` in the file pins the evidence to
  one ROM, so another revision's is discarded rather than mixed. `refuted` is *not* resumed on purpose: a refutation
  comes from one session's screen clustering, and carrying it over could kill a good address for good.
- **A reader it learned itself keeps growing.** The learner used to stop as soon as a reader existed, so a first draft
  was frozen — one value, for ever. Now a later session's draft is **merged** into a `learned while playing` reader
  (`emulator/ReaderMerge`): one entry per address, the names already given win, the values of screens the earlier
  sessions never saw are added (up to 8 values), duplicate names are made unique (`screen_2`), and a chapter whose
  landmark was seen while the reader's `screen` read one value becomes a `chapter:<id>` progress signal after three
  agreeing sightings. A reader somebody wrote (shipped, calibrated by hand, imported) is never touched.
- **The file stays small.** A 4 MiB sweep folds hundreds of thousands of addresses (a real 71-screen run folded
  705,824 and wrote a 222 MB file before this was bounded). The learner keeps the best 8 signals of *each* part it
  examined, up to 256 in all, so the evidence is tens of KB and the screen id of a part is never pushed out by another
  part's noisier addresses.

The proof, on two games nobody calibrated for the learner (both with a route of blind button presses):

```
# Mario Kart DS, 30,000 frames
looks: 2000, screens: 12, addresses kept: 32
ready: true, best signal separates 3 screens and agrees 100% of the time
   0x02175580: 3 values over 4 screens, checked 1769 times since (1762 on a screen that came back)
wrote /tmp/learn-demo/readers/mario-kart-ds.json
```

and the app then treats that game as typed — from `./gradlew probe`:

```
- Mario Kart DS [AMCE] · TYPED · walkthrough: 7 chapters · reader: 4 values, 0 signals (learned while playing)
```

What the same run *rejected* is the interesting part: the first candidates it found were addresses that took one value
per screen and looked perfect (`0x0232e8b5: 9 values over 9 screens, agrees 1.00`) — they were clocks, moving once per
screen, and the moment a screen came back with a different value they were thrown away. A reader that had been written
from them would have been worse than useless: it would have told the AI it was on a screen it had left.

### Limits, stated plainly

- **It finds *a* consistent signal, not necessarily *the* one a human would pick.** On Age of Empires, a 24,000-frame
  run completed all 1,600 looks; the learner settles on a per-screen table at `0x0237b290` (7 screens, 1,569
  verification looks, 1,489 on revisited screens) rather than the `0x023867c6` a hand calibration chose. Both work as
  "which screen is up"; the human one is better because a human also knows what the values mean.
- **The names are placeholders.** `values` maps a value to the screen's picture, or to a chapter title when a
  walkthrough landmark already knows that picture ("Boot to the front end" below is that). Rename them when you know.
- **A game whose screens never repeat cannot be verified.** If every screen is visited once, a screen id and a clock are
  indistinguishable, so nothing is written. Going back to a menu is what makes it learnable.
- **It only learns for a game that has no reader, or for one it learned itself.** A shipped or hand-written reader (and
  the user's own file) wins, and is never extended; a `learned while playing` reader is the app's own and keeps growing.
- **Review it before trusting it.** `provenance: "learned while playing"` and the note on each value say how it was
  found: what it saw, how often it agreed, and how many looks of checking it survived. The app also checks the reader it
  is using as it plays and writes the verdicts back (see [Checking a reader still holds up](#checking-a-reader-still-holds-up)).

### Driving it yourself

```bash
# Learn for a ROM headlessly and print what it found (add --write to save it):
./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.ReaderLearnProbeKt \
  -PdevArgs="/path/to/game.nds|30000|desmume"

# Options: |frames|core, --write, --route=A@900-908;DOWN@1400-1408 (drive a different route),
#          --chunk=<bytes> and --looks=<n> (how much of memory is examined at a time, and for how long)
#          --resume (pick up <data>/readers/<id>.learning.json and write it back: what a second session does)
#          --merge  (with --write: extend the reader the app already has, instead of replacing it)

# A game that already has a reader is the learner's ground truth: the probe says whether it found those addresses again:
#   -- the reader this game already has (a hand calibration, for comparison):
#      screen at 0x023867c6: not found in this run
```

A run that resumes is visibly different from one that starts over — the numbers continue instead of restarting:

```
resumed: 160 looks, 24 screens, part 2, 10 signals found before
looks: 320, screens: 24, addresses kept: 15
this run added 160 looks to the 160 resumed
ready: true, best signal separates 6 screens and agrees 100% of the time
wrote .../readers/final-fantasy-iii.json (8 values, 0 progress signals)
```

In the app it happens by itself: open a game with no reader, play it (or let the AI play), and watch the **Reader**
section of the panel — it shows how many looks, how many screens, which part of memory is being examined, and how many
signals it has. When it is confident it writes `<data>/readers/<id>.json` and the panel starts showing the values live.

## Checking a reader still holds up

A reader is a claim about someone else's program, and claims go stale: a game that gets patched, a value a hand
calibration picked that never really held, a learned signal that was a clock in disguise. So the app **checks the
reader it is using while the game is played** (`emulator/ReaderVerifier.kt`) and writes what it saw back into it —
`confidence` and one `notes` line.

The check applies the same rule the learner writes readers under: for every value that claims to name screens (it has a
`values` map), it asks whether it read **one value per screen**, and whether it tells at least two screens apart. Each
value comes back with one of four verdicts:

| verdict | what it means |
| --- | --- |
| `ok` | it held one value on 90%+ of the looks at every screen, and told screens apart: the claim holds |
| `inconsistent` | it took more than one value on a screen it had already seen — it moves on its own, so the agent would be told it is on a screen it has left |
| `constant` | it never changed: it cannot say which screen is up (it may still be a constant the reader wanted, but as a screen value it is not verified) |
| `unreadable` | it was never read: a guard that never held in this game, a pointer outside main RAM, an address out of range |

A value that claims nothing about screens is **reported, not judged**: a counter is expected to move and text is
expected to be rewritten, so the panel and the tools say what it did (`moved 412 times, from 0 to 412`) without holding
it to a rule it never claimed.

In the app it runs on the same look at the game that feeds the panel's live values, so the **Reader** section gains a
line like:

```text
Check — checked over 1,240 looks and 41 screens: 3 of 4 screen values hold · 1 to look at
game_mode: inconsistent — now: 4, changed 137 times
```

The check only writes what it can stand behind:

- A screen value **caught moving within a screen it had seen** is downgraded to how often it really held (`confidence`
  becomes the observed agreement, below 90% by construction). A value that holds keeps the confidence its calibration
  gave it — the check does not inflate a claim, and it does not deflate one it merely could not exercise.
- A value that never changed, or was never readable, is **reported, not refuted**: one screen makes every value look
  constant, so the panel and the notes say what happened (`never read (guard off 412 times)`, `out of range 41 times`)
  and `confidence` is left alone.
- The reader's notes gain **one rewritten** `Checked while playing:` line — rewritten, not piled up, so a reader checked
  a hundred times has one line about it.
- Nothing is written at all until the run has shown enough to mean something (60 looks over 3 screens): below that the
  panel says `checking… N looks and S screens so far`, because a verdict then would be about the route, not the reader.

**A reader that ships with the app is never written**: that would shadow the shipped file with a user copy, and a later
release could not fix it. It is checked and reported in the panel, and the file is only written when the reader is the
app's own (`learned while playing`) or one already in your own `<data>/readers`. Writes are 30 s apart at most, plus
once when the game closes. A reader the app is still learning is followed as it grows: the looks already taken stay, and
only a value whose reading changed starts over.

### Driving it yourself

```bash
# Check the reader of a ROM headlessly: one line per value, with the evidence (add --write to write it back):
./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.ReaderVerifyProbeKt \
  -PdevArgs="/path/to/game.nds|3000|desmume"

#   ok           screen at 0x23867c6: 41/41 screens held, 6 values, agreement 1.00 — now: 84, changed 6 times
#   INCONSISTENT menu at 0x237b290: 3/7 screens held, 9 values, agreement 0.71 — now: 4, changed 137 times

# Options: |frames|core, --write (write the verdicts into the reader), --force (allow it for a shipped reader),
#          --route=A@900-908;DOWN@1400-1408 (drive a different route)
```

It says nothing about a screen the run never showed: a value that is fine on the three screens you walked through may
still break on the screen you did not, which is why the app keeps checking every session instead of once. A run that
shows fewer than 3 screens or 60 looks prints `too little to judge the reader` and writes nothing.

## Playing through a reader

`ReaderPlayer` (`agent/ReaderPlayer.kt`) is the second half of a typed agent: the same observe → decide → act loop as
the Pokémon player, but its state is the reader's values and its actions are the console's own (`ReaderActions`:
press a button, hold a direction, tap the touch screen, wait). The model is told the game, its objective, the chapter,
the reader's values, and — after each action — **what changed** (`screen: 19 → 18`), plus a `stuck` note once actions
stop changing anything. Nothing in it is game-specific: another calibrated reader, another game.

What it is not: it cannot read the picture by itself, so `ScreenState` gives it what the app *can* say — whether the
picture changed while it acted, how long it has been still (a still picture is a game waiting for input), which
walkthrough landmark it matches, and, when the model has vision, its own one-line reading of the screenshot
(`dialogue`, `menu`, `field`, `loading`, `intro`). A model without vision answers `unknown` and the request says so.
Beyond that it cannot act on anything the reader does not describe, and its action vocabulary is coarse — a 3x3 touch
grid, presses and holds. A game needing precise stylus work needs a better reader (a `cursor.x`/`cursor.y` observation)
before the model can aim.

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

## What the model is told about the picture

A reader reads memory; it cannot say "a dialogue box is open and it wants A". `ScreenState` closes that gap — and it
does so **per screen**, because a DS game uses both (a map above a touch menu, an illustration above a dialogue box).
Everything it knows goes into the request's `screen` block:

```json
"screen": {
  "top":    {"looks_like": "dialogue", "stable_frames": 92, "changed_by_your_action": false, "landmark": "Start the campaign"},
  "bottom": {"looks_like": "menu",     "stable_frames": 4,  "changed_by_your_action": true},
  "what_that_means": "the bottom screen changed while you acted: the game answered you there · the top screen has not changed for 92 frames: that is where the game is waiting for input",
  "note": "A Nintendo DS has two screens and games use both: ... the bottom one is the touch screen, so `touch_x_y` taps there."
}
```

- **`stable_frames` and `changed_by_your_action`** are measured *per screen*, frame by frame, while the action runs —
  not guessed between decisions. An animated HUD on one screen no longer hides the other being still, and
  `changed_by_your_action` says *which* screen the game answered on. This is what stops an agent believing it is stuck
  on a boot screen: a still screen is a game *waiting for input*, a moving one is loading.
- **`looks_like`** is the model's own reading of that screen, asked per screen ("what is the bottom screen of this
  Nintendo DS showing right now?"), with constrained options (`dialogue`, `menu`, `field`, `loading`, `intro`,
  `unknown`) and cached per screen and picture. It is only asked once *that* screen has settled (20 frames), because
  that is when a reading is useful and reliable. A model that cannot see images answers `unknown`, and the request then
  says so outright. The panel shows the same line under **Screen**, so a user can see at a glance whether their model
  reads the screens.
- **`landmark`** names a screen outright when it matches a walkthrough chapter's captured landmark — a landmark that
  names a region belongs to that screen (Age of Empires' menu landmark is the bottom screen), and one that covers the
  whole frame is reported on its own.

**Both screens are sent as two pictures**, cropped and labelled ("top screen", "bottom screen"), rather than one
combined picture: a vision encoder resizes what it is given, so one 256x384 image gives each screen half the detail,
which is exactly what small dialogue text suffers from. The text of the request names each picture, so the model knows
which is which. The **Read the screen** switch turns the readings off; the two pictures, the stability, the change and
the landmark matching stay.

## Checking that the model can see at all

A vision model only helps if the pictures reach it, so there is a tool for exactly that:

```bash
LLM_PROVIDER=openai LLM_MODEL=<a vision model> OPENAI_BASE_URL=http://host:1234 OPENAI_API_KEY=lm-studio \
  ./gradlew devRun -PdevMain=me.nathanfallet.aiplayspokemon.dev.VisionCheckKt
```

It sends a solid red square, then a DS-shaped frame whose top screen is red and bottom screen is blue — the way the app
sends them (two labelled crops) — and asks about each:

```text
== vision check: OPENAI · ornith-1.0-9b (base URL http://100.102.229.67:1234) ==
1. one picture: Red
   → the model sees images: the screenshot reaches it
2. both screens: top=red bottom=blue
   → both screens are seen, and told apart
```

If the first check fails, no prompt work will make the agent see (check that the loaded model is the multimodal one and
that the endpoint accepts image parts — LM Studio reports a load failure as HTTP 400). If the second fails, the agent
sees one screen and guesses the other.

## What a reader is not

- **It cannot invent meaning.** The calibration finds addresses and their behaviour; calling one "wood" is a judgment
  you make by watching the game, and `confidence`/`note` are where you say how sure you are.
- **It does not write memory.** Readers are read-only, on purpose: the AI acts through buttons and the touch screen.
- **It is not a replacement for a hand-written reader.** A Pokémon game's reader (pokemon-client) knows the game's
  structures, maps and menus; a calibrated reader knows the few numbers someone confirmed. For a game nobody has
  written code for, that is the difference between "the AI is blind" and "the AI can see which screen the game is on".
- **The AI acts through it, not on it.** `ReaderPlayer` plays with the console's own actions; a reader never writes
  memory, so a game that needs a cheat or a memory patch is out of scope by design.
