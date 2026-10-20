# Contributing to this fork

`origin` is https://github.com/KonradReinhardt/diorama and `upstream` is https://github.com/SunkenInTime/diorama.

## Branches

- `master`: an unmodified copy of the original project's history. Update it only by a fast-forward from `upstream/master`.
- `development`: the integration branch containing this fork's validated changes.
- `improvements/cardinal-map`: the initial improvements, persistence fixes, regression tests and cardinal camera directions.
- `fix/preserve-map-on-settings`: preserves tile textures and exploration during appearance updates and coalesces settings changes.
- `feat/modmenu-config`: optional Fabric Mod Menu entrypoint and its client test.
- `docs/day-night-lighting`: the original day/night rendering design.
- `feat/day-night-lighting`: paired surface lighting, draw-time environment blending, incremental saved maps and regression fixtures.
- `sync/upstream-0.2.1`: integration of upstream release `v0.2.1` (`d9f4a28`), including all NeoForge targets and the updated client-test shim.

The fork's 0.3.0 builds include upstream 0.2.1. Dynamic surface lighting remains
limited to Fabric 26.3; the newly added NeoForge targets use the daytime backend.

New work starts on a topic branch based on `development`. Make focused commits and merge a topic only after its relevant checks pass. Do not force-push shared history or put generated JARs, caches or local instance configuration in commits.

When preparing contributions to the original project, open focused PRs against `upstream/master`. Topic branches in this fork can have dependencies on earlier fork improvements; review the entire diff against upstream and extract/rebase the necessary commits into a clean submission branch first. `development` is convenient for playing with the combined fork, but should not be submitted as one large upstream PR.

## Validation

Use JDK 25, the Gradle wrapper and Stonecutter's active version 26.3.

```sh
./gradlew --configure-on-demand :26.3:build :26.3:gametestClasses
DIORAMA_TESTS=settings-refresh ./gradlew --configure-on-demand :26.3:runClientGameTest
DIORAMA_TESTS=modmenu ./gradlew --configure-on-demand -PdioramaModMenuTest=true :26.3:runClientGameTest
```

The local Windows wrapper `D:\MinecraftModding\diorama-dev.cmd` selects the workspace Java/cache setup. It is intentionally not part of the repository.

The Mod Menu dependency is compile-only by default. The `dioramaModMenuTest` property adds Mod Menu and Placeholder API only to the 26.3 development runtime; they are not bundled in the release JAR.

GitHub Actions declares a build matrix for all 21 supported version/loader targets and three client-test jobs (map regressions, lighting and optional Mod Menu). Fork workflows may need to be enabled in GitHub before they run. A declared matrix is not evidence that every version has passed; inspect each run before preparing a release.

Automatic push builds run on `development`; pull requests and manual workflow runs
can validate topic branches. Publishing the same integration on multiple branch
names therefore does not start duplicate full matrices.

See [the day/night design](docs/design/day-night-lighting.md) for the feature's scope, compatibility policy and performance gates, and [its validation report](docs/testing/day-night-lighting.md) for measured results.
