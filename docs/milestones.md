# Milestone Status

Work proceeds one milestone at a time (master plan §17); each ends at its exit
gate with a report (§18).

| Milestone | Status | Notes |
|---|---|---|
| M0 — Repository & bootstrap | ✅ done (2026-07-28) | Gradle 9.6.1, Java 25 toolchain, Paper 26.2, Flyway V1, Docker MariaDB, bootable plugin |
| M1 — Shared foundation | ✅ done (2026-07-28) | Identity/public IDs, audit, GUI framework, serialized items + HMAC, POI admin, adapters |
| M2 — Economy & banking | ⬜ next | Double-entry ledger, Vault bridge (VaultUnlocked installed), notes/checks/cards, IBAN/ATM |
| M3 — Civic, contracts, property | ⬜ | |
| M4 — Vehicles, mechanic, used cars | ⬜ | Vehicle plugin decision required first (ADR 0002) |
| M5 — Dispatch jobs | ⬜ | |
| M6 — EMS | ⬜ | |
| M7 — Nightclub | ⬜ | |
| M8 — Police, crime, cross-module RP | ⬜ | ProtocolLib install needed |
| M9 — Balance, content, launch | ⬜ | Item provider decision required first (ADR 0002) |

## M0/M1 exit-gate evidence

- `./gradlew build` clean; 16 unit tests pass.
- `AFTERLIFE_IT=1 ./gradlew test`: 6 MariaDB integration tests pass —
  migrations, unique public IDs under concurrency (40 threads), single row for
  same-UUID races, serialized-item transition wins exactly once (24 concurrent
  attempts), POIs survive pool restart, audit inserts.
- Missing integrations are reported by `/afterlife health` and boot logs.
- Installed on the server: LuckPerms 5.5.53, WorldEdit 7.4.4, WorldGuard 7.0.17,
  PlaceholderAPI 2.12.3, VaultUnlocked 2.20.2, Citizens 2.0.43-b4228.
