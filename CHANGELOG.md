# Changelog

Notable changes, newest first. Releases are on
[GitHub](https://github.com/SamurAI-Official/ai-plays-online/releases); this file says what changed between them.

## Unreleased

### Added

- **Online play**: two machines (or two windows) run the same game in lockstep or with rollback netcode over UDP — host
  on one, join on the other, with the session's state ring, prediction and desync detection underneath. A joiner with a
  different ROM revision is refused by the handshake ("content differs"); a peer that leaves (a clean goodbye) or
  vanishes (a killed process, a dropped link) stops the session with a reason instead of hanging the game.
- The panel shows the session live: role, peer, synchronization, frames, the peer's frame, rollbacks and the state it
  keeps. Online, the session owns the frame clock: save states, resets and fast forward are refused rather than
  silently wrong, and the AI's lease is refused until it can play through the session's input queue.
- `dev/SaveStateProbe` and `dev/RamProbeTool`: produce the save states and RAM snapshots the gated tests need, without
  sitting at the app pressing F1.

### Changed

- **A test that needs a ROM (or a core, or a save state) now skips visibly** — with its reason, printed by
  `./gradlew test` and uploaded with the CI run — instead of passing while testing nothing. Gated suites name their own
  variables (`POKEMON_ROM` is the app's own, so they no longer use it). See [`docs/testing.md`](docs/testing.md).
- The README's test section documents every gated suite, what it needs, and the rule that keeps the fixtures safe:
  point a suite's `*_DATA` at a **copy** of a data directory, because a loaded savestate carries the game's save chip
  and closing the console writes it back as the in-game save.

### Fixed

- `./gradlew test` says how many tests were skipped, so a green run cannot hide a path that did not run.

## 1.0.1

### Fixed

- The native macOS app stopped on its first ROM launch: its bundled Java runtime omitted `java.net.http`, which the
  DeSmuME core downloader needs.
- Included the other runtime modules identified by Compose's runtime analysis.
- Added a release-CI check for the required modules in the packaged runtime.

## 1.0.0

First release.

- The app: a game picker (nothing loads until you pick), the console thread and its drivers, the panel, the launcher.
- Decision models: **Jev** (TypeSafe), **LLMs** through Koog (OpenAI, Anthropic, OpenRouter, Ollama), **Claude** with a
  Claude Code login, and **any agent over MCP**.
- Control modes: pure (buttons only), assisted (typed actions), hybrid (a fast decider with an LLM planner).
- Typed readers for the supported Pokémon titles: RAM → typed state (map, position, dialogue, menus, party, battle),
  with the screen as text for the rest; memory between sessions; milestones and cost per run.
- macOS (Apple Silicon, Java bundled) and a cross-platform universal JAR for Java 21.
