# AfterLifeRP — Plugin Documentation

A modular city-roleplay core plugin for **Paper 26.2 / Java 25**. Legal
professions, government services, organized crime, and player businesses all run
on one authoritative double-entry economy. This document is the complete
reference for operators and developers; the original design spec is
`docs/master-plan.md` and the standing engineering rules are `AGENTS.md`.

- **Status:** Milestones 0–3 and 5–8 complete and live; M9 (launch hardening)
  in progress; M4 (vehicles) deferred. Schema at V8, 74 automated tests.
- **Root package:** `com.afterlife.rp` · **Build:** Gradle (wrapper) ·
  **DB:** MariaDB via HikariCP + Flyway.

---

## 1. Architecture

Five layers, top to bottom: **Presentation** (commands, GUIs, listeners) →
**Application** (per-module services orchestrating use cases) → **Domain**
(rules, state machines) → **Infrastructure** (async SQL repositories, config,
adapters) → **Audit** (append-only records). Cross-cutting shared services live
under `shared/`.

### Non-negotiable engineering rules (enforced everywhere)

1. All database access is asynchronous — never on the server thread.
2. Bukkit world/entity/inventory/GUI mutations return to the server thread.
3. Every money movement goes through the shared ledger.
4. Every transaction carries a unique idempotency key.
5. Physical valuables use unique serials + server-side status records.
6. GUI titles and item names are never authoritative identifiers.
7. Every GUI uses a custom `InventoryHolder`/session.
8. Every long-running action is a persisted state machine.
9. Every privileged action writes an immutable audit record.
10. Every module can be disabled independently in config.
11. All prices/cooldowns/chances/coords/messages/items are configurable.
12. Secrets, DB credentials, and signature keys never enter Git.
13. A restart never duplicates rewards, loses missions, or repeats a transfer.
14. Commands validate sender, permission, arguments, state, and cooldowns.
15. Never trust client text, lore, display names, or GUI clicks without
    server-side validation.

### Shared foundation (`shared/`)

| Service | Responsibility |
|---|---|
| `database.Db` / `DatabaseManager` | Async pool, Flyway migrations, health, `inTransaction`/`supply`, main-thread executor |
| `audit.AuditService` | Append-only `audit_events`; returns a future so callers can wait for durability |
| `identity.IdentityService` | Permanent sequential public IDs, nametags, VIP nicknames |
| `economy.*` | Accounts, double-entry ledger, reconciliation, economy report, pending deliveries |
| `items.*` | PDC serialized items, HMAC-SHA256 signing, one-shot status transitions |
| `missions.*` | Persisted mission state machines, job sessions, navigation/AFK tracker |
| `gui.*` | Hardened session-bound inventory framework |
| `regions.*` | POIs with WorldGuard binding |
| `ManualService` | Data-driven in-game manual books |

### Key invariants

- **Money** is integer minor units (cents) as `BIGINT` — never floating point.
- **One-winner transitions**: every reward/redeem/swap uses a conditional SQL
  UPDATE (`WHERE status = <from>` / `WHERE version = ?`), so exactly one caller
  wins under concurrency or replay.
- **DB-clock time rules**: expiries and durations (warrants, detention,
  missions, checks) compare against `CURRENT_TIMESTAMP(3)` in SQL, immune to
  host/DB timezone skew. JDBC uses `timezone=auto`.
- **Item identity** = PDC type + serial + HMAC signature + DB record. Custom
  models (CraftEngine) are cosmetic only.

---

## 2. Dependencies & environment

Installed plugins (all MC 26.2 compatible): LuckPerms, WorldEdit + WorldGuard,
PlaceholderAPI, VaultUnlocked, Citizens, ProtocolLib, CraftEngine. Every one is
a **soft dependency** surfaced by `/afterlife health`; the plugin boots and
degrades gracefully when any is absent.

Runtime libraries (HikariCP, Flyway, MariaDB driver) load via `plugin.yml`
`libraries:` on first boot. The database runs in Docker beside the game
container; credentials live in `.env` (git-ignored) and the generated
`plugins/AfterLifeRP/config.yml`. The HMAC key is generated at
`plugins/AfterLifeRP/secret.key` on first boot.

### Decisions of record (ADRs)

- **0001** — Target Paper 26.2 / Java 25 (not the spec's outdated 1.20.6 pin).
- **0002** — CraftEngine as the custom-item provider (ItemsAdder rejected:
  incompatible with 26.2). InfiniteVehicles chosen for M4.
- **0003** — Vault bridge semantics (cache reads, async ledger writes vs the
  government budget; VaultUnlocked at highest priority).
- **0004** — Player-only hallucinations via Paper `hideEntity` (ProtocolLib is
  installed but not required for this).

---

## 3. Economy

The ledger is the single source of truth and registers as the Vault economy
provider. See `docs/economy-model.md` for the full model.

- **Clean money** — digital balances in `accounts`, moved by balanced
  `ledger_transactions` + `ledger_entries`.
- **Dirty money** — serialized physical notes; not bankable until laundered.
- **Checks** — serialized, payee-specific, single-redemption.
- **System clearing accounts** — `cash_issuance`, `government_budget`,
  `seizure`, `check_clearing`, `hospital_treasury`, `nightclub_treasury`,
  `bounty_escrow`.

**Reconciliation** (`/afterlife reconcile`, daily): asserts every transaction
sums to zero and every balance equals its entry sum. **Economy report**
(`/afterlife economy`, daily log): net flow to the player economy per reason,
with created-vs-destroyed totals for inflation control.

---

## 4. Modules & player commands

Every module reads `plugins/AfterLifeRP/modules/<name>.yml` with an `enabled`
flag; an invalid config disables that module loudly without taking the core
down. All player-facing text is Italian (`messages_it.yml`, MiniMessage).

### Core & identity
- `/id` (`/identita`) — show permanent public ID.
- `/setnick <text|reset|off>` — VIP nickname (`afterlife.vip.nickname`).
- `/language` (`/lingua`, `/lang`) `[code]` — show or set your viewing language.
- `/manuale [topic]` — in-game manual books (benvenuto, banca, legge,
  immobili, lavori, crimine), shown in your chosen language.

**Localization.** Player-facing text is fully localized: each language is a
`messages_<code>.yml` (and optional `manuals_<code>.yml`); English (`en`) and
Italian (`it`) ship in the box. `config.yml` `language:` sets the default (used
for the console, unset players, and missing-key fallback) and the list players
may pick from. A player's choice is stored in `players.locale` and applied to
every message and manual. Command names/tokens stay fixed; only rendered text
is translated. Add a language by dropping in a new `messages_<code>.yml` and
listing it under `language.available`.

### Banking (§9.1)
- `/iban` — IBAN, balance, frozen state.
- `/atm` (`/banca`) — ATM GUI (POI + card gated): withdraw, deposit notes,
  statement; `/atm preleva <euro>` for a direct withdrawal.
- `/bonifico <IBAN|player> <euro>` — transfer.
- `/assegno <player> <euro>` / `/incassa` — issue / redeem a check.
- `/banchiere <apri|carta|revoca> <player>` — employee (`afterlife.bank.banker`).
- `/sequestro <player> <congela|scongela> <reason>` — director freeze.

### Legal (§9.2, `afterlife.legal.lawyer` / `afterlife.police.officer`)
- `/contratto proponi <player>` / `firma`, `/valida_contratto`.
- `/fedina [player] | aggiungi <player> <MINOR|MAJOR> <charge> | sconta <id>`.
- `/pulisci_fedina <player>` — lawyer expungement (archive, never delete).
- `/arresto <player> [min]`, `/rilascio <player>`, `/avvocato`,
  `/ricorso <player>` (release only on machine-verifiable overrun).
- `/prova crea <desc> | vedi <id>` — evidence with audited access.
- `/licenza` — active licenses.

### Real estate (§9.7, `afterlife.realestate.agent` / `.director`)
- `/luoghidisponibili`, `/luoghisporchi`.
- `/agenzia vendi <property> <player> | affittasporco <property> <player> |
  distruggi_file`.
- `/cambia_serratura <property> <reason>` — bumps the lock, revoking old keys.
- `/cassaforte [preleva <euro>]` — director-only black safe.
- `/chiave` — validate the held key.

### Dispatch jobs (§9.5–9.6)
- `/elettricista <turno|fine|chiamate|accetta <poi>|ripara>` — repair minigame,
  government pay, 1% circuit-board roll (`afterlife.electrician.worker`).
- `/rider <turno|fine|ordine|ritira|consegna|accetta_pacco>` — temperature tips,
  sealed contraband offer (`afterlife.delivery.driver`).

### EMS (§9.8, `afterlife.ems.medic`)
- `/ems <turno|fine|scan|cura|produci|traccia|certificato|manuale|emergenza|
  cura_npc|estrai|converti>` — injuries, tool-sequence treatment + billing,
  traceable batches, certificates, Citizens NPC emergencies, toxic extraction.

### Nightclub (§9.11)
- `/club <vendi|conferma|rifiuta|shaker|scanner|blacklist|dj|escrow|taglia|
  staff|prezzi|rifornisci|happyhour|magazzino>` — POS, mixology, security,
  criminal escrow, bounties, manager dashboard
  (`afterlife.nightclub.bartender|security|manager`, `afterlife.crime.boss`).

### Police & crime (§9.3–9.4)
- `/polizia <mandato|perquisisci|sequestra|controlloconto|allerte|rispondi>`,
  `/k9 <turno|fine|schiera|fiuta>`, `/acconsenti <officer>`
  (`afterlife.police.officer|k9`).
- `/gang <turno|fine|vendi|sigilla|apri|costruisci|hackera>` — street sales,
  sealing, drug trips, ATM hacking from the M5 circuit board
  (`afterlife.crime.gang`).

### Admin (`afterlife.admin` / `afterlife.admin.setup`)
- `/afterlife <version|health|reconcile|economy>`.
- `/afterlife setup` — menu; `/afterlife setup status` — live readiness
  checklist per module, each gap carrying the command that closes it.
- `/afterlife setup <poi|org|license|property> …`.
- `/afterlife setup export <name>` / `import <name> [apply]` — snapshot and
  replay POIs, properties, and organizations (see `docs/setup-guide.md`).
- `/afterlife debug <item <type> [denom]|dirtymoney <euro>>`.

---

## 5. Permissions

Namespace `afterlife.<module>.<action>`. Key nodes: `afterlife.admin[.setup]`,
`vip.nickname`, `bank.user|banker|director`, `legal.lawyer`,
`police.officer|k9`, `realestate.agent|director`, `electrician.worker`,
`delivery.driver`, `ems.medic`, `nightclub.bartender|security|manager`,
`crime.boss|gang`. Full list with defaults is in `plugin.yml`.

Privileged actions (freezes, seizures, record changes, licenses, large
transfers) require explicit permission, a reason, an audit entry, and — for the
riskiest — confirmation.

---

## 6. Data model

UUIDs are internal identifiers; sequential public IDs are display-only. Mutable
rows carry an optimistic `version` column. Schema is applied by Flyway
migrations `V1`–`V8`:

| Migration | Tables |
|---|---|
| V1 | players, audit_events, points_of_interest, serialized_items |
| V2 | organizations(+members), accounts, ledger_transactions/entries, pending_deliveries |
| V3 | licenses, contracts(+parties), criminal_records, detentions, evidence(+custody), properties(+ownership), black files/safe, power_anomalies |
| V4 | missions, job_sessions |
| V5 | injuries, treatments, medicine_batches, medical_certificates, hospital_treasury |
| V6 | business_orders, receipts, business_stock, inventory_orders, escrow_deals, bounties, blacklist, employees, nightclub/bounty accounts |
| V7 | widen pending_deliveries.item_type |
| V8 | warrants, police_alerts |

---

## 7. Custom items (CraftEngine)

Each serialized item type can be mapped to a CraftEngine id in `config.yml`
`custom-items:`. `SerializedItemService.toItemStack` renders the mapped model as
the base stack and stamps the PDC/HMAC on top; unmapped types (or no
CraftEngine) fall back to a vanilla material. Author the pack from
`docs/craftengine/afterlife-items.yml`; full mapping in `docs/item-catalog.md`.

---

## 8. Operations

- **Health/diagnostics**: `/afterlife health`, structured logs with
  transaction/mission IDs.
- **Backups**: `tools/backup.sh` (single-transaction dump, rotation) and
  `tools/backup.sh restore <file>` — roundtrip rehearsed. Schedule daily.
- **Dev tooling**: `tools/rcon.py <command>` (RCON), `tools/wings.sh restart`
  (Pterodactyl Wings API).
- **Setup**: `docs/admin-setup.md` (DB, POI types per module, dependency list).
- **Graceful degradation**: if the database is unavailable, joins are blocked
  with a maintenance message rather than corrupting state.

---

## 9. Testing

74 automated tests: JUnit 5 unit tests (HMAC, IBAN mod-97, reward/temperature
formulas, treatment sequences, GUI click policy, config validation, account
bands) and Testcontainers MariaDB integration tests per milestone
(`FoundationIT`, `EconomyIT`, `CivicIT`, `MissionIT`, `EmsIT`, `NightclubIT`,
`PoliceCrimeIT`, `EconomyReportIT`). Run: `./gradlew check` (unit) and
`AFTERLIFE_IT=1 ./gradlew test` (with MariaDB). The exploit/abuse coverage map
is `docs/exploit-matrix.md`.

---

## 10. Building & contributing

```bash
cp .env.example .env            # set DB passwords
docker compose -f docker-compose.dev.yml up -d
./gradlew check                 # unit tests
AFTERLIFE_IT=1 ./gradlew check  # + MariaDB integration tests
./gradlew deployToServer        # copy jar to ../plugins/AfterLifeRP.jar
```

Work proceeds one milestone at a time (master plan §17); each ends at an exit
gate with tests. Status lives in `docs/milestones.md`. Preserve the 15
engineering rules in every change.
