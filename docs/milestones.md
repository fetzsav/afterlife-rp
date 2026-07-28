# Milestone Status

Work proceeds one milestone at a time (master plan §17); each ends at its exit
gate with a report (§18).

| Milestone | Status | Notes |
|---|---|---|
| M0 — Repository & bootstrap | ✅ done (2026-07-28) | Gradle 9.6.1, Java 25 toolchain, Paper 26.2, Flyway V1, Docker MariaDB, bootable plugin |
| M1 — Shared foundation | ✅ done (2026-07-28) | Identity/public IDs, audit, GUI framework, serialized items + HMAC, POI admin, adapters |
| M2 — Economy & banking | ✅ done (2026-07-28) | Double-entry ledger, Vault bridge (ADR 0003), notes/dirty money/checks/cards, IBAN/ATM GUI, freeze, reconciliation, pending deliveries |
| M3 — Civic, contracts, property | ✅ done (2026-07-28) | Licenses, Book&Quill contracts + lawyer validation, criminal records + expungement, detention + /ricorso, audited evidence custody, property sales/keys/locks, dirty rentals + black safe, power anomalies |
| M4 — Vehicles, mechanic, used cars | ⏸ deferred | InfiniteVehicles chosen (ADR 0002); operator will schedule; plugin purchase + install needed |
| M5 — Dispatch jobs | ✅ done (2026-07-28) | Mission framework (one-winner rewards, restart/quit/AFK recovery), electrician (dispatch, wiring minigame, 1% circuit board), food delivery (temperature tips, sealed contraband) |
| M6 — EMS | ✅ done (2026-07-28) | Injury engine, tool-sequence treatments + hospital billing, traceable batches, certificates, Citizens NPC emergencies, toxic extraction → illegal Adrenaline |
| M7 — Nightclub | ✅ done (2026-07-28) | POS + receipts + stock, shaker quality minigame, VIP/bouncer/blacklist, DJ effects, criminal escrow, anonymous bounties, manager dashboard |
| M8 — Police, crime, cross-module RP | ✅ done (2026-07-28) | Warrants, authorized searches, seizures→evidence, K-9, scoped account checks, alerts; gang sales, drug trips (hideEntity, no ProtocolLib — ADR 0004), sealing, ATM hacking from M5 circuit board |
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
- Live boot verified on Paper 26.2 (2026-07-28 20:55): runtime libraries
  resolved, HMAC secret generated, all 5 integrations active, Flyway migrated
  to schema v1, database connected, POIs loaded. Confirmed by the operator.

## M2 exit-gate evidence

- `AFTERLIFE_IT=1 ./gradlew test`: 35/35 pass. M2-specific (EconomyIT):
  10 concurrent transfers of a full balance → exactly 1 completes; same
  idempotency key → 1 transaction row (replay returns DUPLICATE); a withdrawn
  note deposited by 8 concurrent requests credits once; a check redeems once
  and only for its payee; frozen accounts refuse debits but accept credits
  (director override works); pending deliveries claimed by exactly one worker
  (restart recovery); reconciliation flags a tampered balance and passes clean.
- Schema V2: organizations/members, accounts (IBAN mod-97), ledger
  transactions/entries (balanced, `balance_after` per entry), pending
  deliveries, 4 system clearing accounts.
- Vault provider registered at Highest priority via VaultUnlocked (ADR 0003).
- Commands: /iban, /atm (+GUI, POI-gated, card-gated), /bonifico, /assegno,
  /incassa, /banchiere, /sequestro, /afterlife reconcile, setup org create,
  debug dirtymoney.

## M3 exit-gate evidence

- CivicIT (Testcontainers): concurrent sale of one property → exactly one
  owner; lock bump revokes old keys and the reissued key opens; expungement
  archives rows (status EXPUNGED, never deleted) with fee + audit; open/major
  records block it; unauthorized evidence access fails AND is audited, while
  authorized reads extend the chain of custody; one active detention per
  player, appeal succeeds only on the machine-verifiable overrun rule; dirty
  rental consumes physical dirty notes exactly once into the black safe,
  replay fails, file destruction keeps the row + audit; contracts validate
  exactly once.
- Simplifications noted for later hardening: power anomalies use per-hour
  accumulation (not per-interaction weights); door/region enforcement of keys
  arrives with the door registry (§12); lawyer evidence access waits for the
  case system (M8 cross-module links).

## M5 exit-gate evidence

- MissionIT (Testcontainers): 12 concurrent completions of one mission → the
  reward gate opens exactly once; one active mission per type per player;
  startup recovery expires overdue missions (which can then never pay);
  quit-cancel frees the player; stale job sessions close on startup.
- Deliveries: package = serialized item bound to its mission serial; ended
  missions void the serial so leftover items are inert; drop/container
  stash/death drops are blocked; AFK warn/cancel via the mission tracker.
- Electrician: POI failure claims are one-winner (FAILED→REPAIRING);
  released/expired calls return to FAILED; circuit-board roll is a single
  server-side SecureRandom draw at completion, audited discreetly.
- Simplifications: delivery targets are POI proximity + command (NPC
  right-click arrives with Citizens usage in M6+); company scooter stubbed
  until M4 (InfiniteVehicles); uniform issuance deferred to the item-provider
  milestone.

## M6 exit-gate evidence

- EmsIT (Testcontainers): treatment enforces the exact tool sequence (wrong
  tool rejected with the correct expected tool); concurrent medics cannot both
  advance the same step (one-winner conditional update); healing bills the
  patient with hospital/commission split in the same transaction; batches
  trace to their producer with LEGAL/ILLEGAL flags (chemical → illegal
  Adrenaline); a cancelled extraction can never produce a chemical;
  certificates require zero open injuries.
- NPC emergencies: Citizens NPCs are bound to their mission and destroyed on
  completion, expiry, cancellation, plugin disable, and startup sweep
  ([EMS] name marker).
- Simplifications: ambulance transport waits for M4 vehicles; incapacitation
  is the UNCONSCIOUS injury (defibrillator) rather than a full no-death mode;
  medical tools are issued via /afterlife debug item until the item-provider
  milestone adds crafting recipes.

## M7 exit-gate evidence

- NightclubIT (Testcontainers): a POS order settles exactly once under
  concurrent accepts (state one-winner + stock guard + payment + receipts in
  one transaction); the last stock unit sells once and stock never goes
  negative; escrow deposits redeem once (a replayed dirty note pays nothing),
  locked deals cannot be cancelled, the bartender confirm swaps ownership
  exactly once, and returns/payouts travel as durable pending deliveries;
  bounties escrow funds at creation, pay once with the bartender fee, and hide
  the sponsor from players while keeping them in the audit trail; happy hour
  discounts proposed orders.
- GUI abuse: drinks/receipts/deposits are serialized items on the one-shot
  transition framework; the shaker and all menus run on the hardened GUI
  session framework (every click cancelled at event level).
- Simplifications: manager dashboard is command-based (GUI arrives with the
  content milestone); track selection for the DJ is lighting/smoke only until
  a music integration is chosen; escrow accepts serialized items only (plain
  vanilla stacks cannot be made duplication-safe).

## M8 exit-gate evidence

- PoliceCrimeIT (Testcontainers): searches require consent/warrant/exigent —
  a search without authority is DENIED and audited (authorizations audited
  too); a warrant authorizes until it expires by the DB clock; seizures enter
  the evidence chain and set the item CONFISCATED; a drug dose sells exactly
  once under 6 concurrent attempts; sealing voids the dose and yields an
  odor-proof bag (K-9 classification: drug_dose contraband, sealed_bag
  odor-proof); the ATM-hack device builds from an Intact Circuit Board
  (consumed once), the device is consumed at hack start (replay fails), and
  the hack pays exactly once; account checks are audited and banded (never
  exact). Plus a unit test on the band boundaries.
- Hallucinations use Paper hideEntity/showEntity (ADR 0004) — real entities
  visible only to the consumer, invulnerable, no-AI, capped, cleaned up on
  trip end/quit/disable. No ProtocolLib dependency.
- Cross-module links realized: seizure reuses the M3 evidence chain; ATM
  hacking consumes the M5 electrician circuit board; gang sales and hacks pay
  the M2 physical dirty money.
- Simplifications: gang street demand is a proximity broadcast + /gang vendi
  (Citizens buyer NPCs can replace it later); warrant/arrest tie-in to the M3
  detention flow is left to the case system; virtual-sign input still needs
  ProtocolLib if that feature is ever added.
