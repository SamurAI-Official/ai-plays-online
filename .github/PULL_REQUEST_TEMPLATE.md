## What this changes

<!-- One or two sentences. If it closes an issue, link it. -->

## How it was verified

<!--
Paste the line `./gradlew test` prints (it says how many tests ran and how many were skipped). Gated suites need a ROM
and are *skipped*, not passed, without one — if this touches netplay, the panel wiring, audio during pauses or
narration, name the gated suite you ran (see docs/testing.md).
-->

- [ ] `./gradlew test` — and `./gradlew build` if this touches the build, the packaging or a dependency
- [ ] A gated suite if this touches netplay / the app's wiring / music during pauses / narration (name it in the PR)
- [ ] By hand if this touches the UI or the launcher (`ControlPanel`, the DMG, the ROM picker): what I clicked, what happened
- [ ] A CHANGELOG entry if this is user-visible

## Notes for the reviewer

<!-- Anything surprising, any trade-off, anything you deliberately did not do. -->
