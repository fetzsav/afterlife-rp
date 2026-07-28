# ADR 0001 — Target platform: Paper 26.2, Java 25

**Status:** accepted (2026-07-28)

The master plan pinned Paper 1.20.6, written before Mojang's calendar
versioning. The production server already runs Paper 26.2 (API
`26.2.build.84-stable`) on Java 21, and the required ecosystem plugins publish
26.2-compatible builds (Citizens 2.0.43, WorldGuard 26.2 builds, ProtocolLib,
LuckPerms, PlaceholderAPI). Downgrading to 1.20.6 would mean losing current
plugin support for no benefit.

**Decision:** target `io.papermc.paper:paper-api:26.2.build.84-stable`, Java 25
toolchain (auto-provisioned by Gradle via the foojay resolver; the game
container runs `yolks:java_25`). Classic `plugin.yml` (not `paper-plugin.yml`)
is used so we get
declarative command registration and the `libraries:` runtime dependency
resolver without depending on the newer Brigadier/PluginLoader APIs; this can
be revisited once the codebase stabilizes.
