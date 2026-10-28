# Security

## Reporting a vulnerability

Please report privately, through **Security → Report a vulnerability** on this repository
(<https://github.com/SamurAI-Official/ai-plays-online/security/advisories/new>). Do not open a public issue.

We will acknowledge within a few days, keep you informed while we work on it, and — if you want — name you in the
advisory and the changelog.

## What this project is

The app runs an emulator in-process, plays a game through an AI, and can serve an MCP endpoint to an external agent. Its
data lives in `~/.ai-plays-online` (config, cores, saves, states, runs). It sends nothing anywhere except to the model
provider you configure. **No game, BIOS or firmware is included.**

## In scope

- **The MCP server** (`http://127.0.0.1:3333/mcp` by default). It binds the loopback interface, has no authentication
  and no `Origin` checks: anything that can reach that port can drive the game. So a way to reach it from outside the
  machine, to make it listen on another interface, or to drive it from a web page is in scope.
- **The core downloader.** Cores come from the libretro buildbot over HTTPS and are checked against a pinned SHA-256. A
  path that skips, weakens or confuses that check — a redirect, a proxy, an archive entry that escapes the target
  directory — is in scope.
- **ROM, save and state handling.** A crafted ROM, save file or savestate that reads or writes outside
  `~/.ai-plays-online`, or that gets code to run outside the emulator, is in scope.
- **Secrets.** API keys are stored in plain text in `~/.ai-plays-online/config.properties` — that is documented, not a
  bug. A path that leaks them (into a log, a run directory, an issue, or a request to the wrong host) is in scope.
- **The packaged app.** The macOS bundle, the universal JAR, the runtime bundled inside them.
- **Online play.** A session accepts inputs from a peer: a third party joining without the session token, or making a
  peer's console do something its own player did not, is in scope. If the engine is at fault, report it against
  [libretro-rollback](https://github.com/SamurAI-Official/libretro-rollback) too.

## Out of scope

- **ROMs, BIOS and firmware.** We cannot help you obtain or use them; such issues are closed.
- **The emulator cores themselves** (DeSmuME, melonDS) and their upstream projects — report those upstream, and tell us
  if the app should pin a different build.
- **The upstream forks** ([kotlinds/ai-plays-pokemon](https://github.com/kotlinds/ai-plays-pokemon) and friends):
  report there if the bug is in their code, here if it is in ours.
- **Your model provider** and its API behaviour.
- Anything that needs an attacker to already control your user account or your machine.

## What you can do today

- Leave the MCP port on `127.0.0.1`, and close the app when an agent is done with it.
- Treat `~/.ai-plays-online/config.properties` as a secret: it holds your API keys.
- Keep using the cores the app downloaded (they are hash-pinned) rather than pointing it at a core from elsewhere.
- The macOS build is ad-hoc signed, not notarized: if you would rather not see Gatekeeper's warning, build it yourself
  (`./gradlew packageDmg`) instead of taking the release.
