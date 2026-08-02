# Admin & Operations Setup

Building a city from scratch is a separate, ordered walkthrough:
**`docs/setup-guide.md`**. This file is the operations reference.

## Database (production)

MariaDB runs in Docker beside the game container (`docker-compose.dev.yml`).
Bind the port to the Docker bridge gateway so the Pterodactyl container reaches
it; set credentials in `.env` (git-ignored). First boot generates
`plugins/AfterLifeRP/config.yml` — put the real DB password there — and
`plugins/AfterLifeRP/secret.key` (HMAC key; never commit either).

## Backups (rehearsed)

```bash
tools/backup.sh                 # timestamped gzip dump into backups/, keeps 14
tools/backup.sh restore <file>  # DESTRUCTIVE restore, prompts for the DB name
```

The dump uses `--single-transaction` (consistent, non-blocking) and runs inside
the container. A restore roundtrip into a scratch database has been verified.
Schedule `tools/backup.sh` from cron/systemd-timer for daily backups; store
copies off-box.

## In-game admin commands

| Command | Purpose |
|---|---|
| `/afterlife setup` | Menu of everything setup can do (click to fill in) |
| `/afterlife setup status` | Per-module readiness: what is missing + the fix command |
| `/afterlife health` | DB + adapter status |
| `/afterlife reconcile` | Ledger integrity check |
| `/afterlife economy` | 24h source/sink report by reason |
| `/afterlife setup poi …` | Register/remove/list POIs, setup report |
| `/afterlife setup org create …` | Create an organization + treasury |
| `/afterlife setup license grant\|revoke …` | Professional/government licences |
| `/afterlife setup property create …` | Register a legal or dirty property |
| `/afterlife setup export <name>` | Snapshot POIs/properties/orgs to a blueprint |
| `/afterlife setup import <name> [apply]` | Preview (default) or replay a blueprint |
| `/afterlife debug item <type> [denom]` | Issue a test serialized item |
| `/afterlife debug dirtymoney <euro>` | Issue physical dirty money |

`setup status` reads live state only — registered POIs, WorldGuard regions,
LuckPerms group nodes, property count — so it is never out of date. It stays
available while the database is down and marks what it cannot verify as unknown
rather than missing. The same summary is logged on every boot.

## POI types to register per module

- Banking: `ATM`, `BANK_TERMINAL`
- Electrician: `ELECTRICAL_CABINET`, `TRAFFIC_LIGHT`, `REPEATER`
- Delivery: `RESTAURANT`, `DELIVERY_DESTINATION`, `SHADOW_DROP`
- EMS: `HOSPITAL_WORKSTATION`, `EMERGENCY_POINT`, `TOXIC_BARREL`
- Nightclub: `POS_TERMINAL`, `SHAKER_STATION`, `DJ_BOOTH`
- Crime: `STREET_SALE_ZONE` (ATM POIs double as hack targets)

All POIs are stored in the database and survive restarts. Bind a WorldGuard
region to a POI by passing its name as the last argument to
`/afterlife setup poi create`. `config.yml` `poi.types` is an allow-list that is
unioned with whatever the enabled modules ask for, so a module can never need a
type the command would reject.

## Reproducing a city

`/afterlife setup export <name>` writes `plugins/AfterLifeRP/setup/<name>.yml`
containing every POI, property, and organization. `import` replays it on another
server (or a wiped database) and skips anything that already exists, so re-runs
are safe. Permissions, WorldGuard regions, player data, and the ledger are not
in the blueprint — back those up with `/lp export`, WorldGuard's own storage,
and `tools/backup.sh`.

## Module toggles

Every module reads `plugins/AfterLifeRP/modules/<name>.yml` with an `enabled`
flag; an invalid config disables that module loudly (logged) without taking the
core down. Prices, cooldowns, chances, and POI types are all configurable there.

## Dependency plugins (installed, MC 26.2)

LuckPerms, WorldEdit + WorldGuard, PlaceholderAPI, VaultUnlocked, Citizens,
and **CraftEngine** (custom-item provider — ADR 0002; ItemsAdder was rejected
as incompatible with Paper 26.2). ProtocolLib was installed only for the
rejected ItemsAdder; it has been removed — the plugin sends no packets and its
player-only visuals use Paper `hideEntity` (ADR 0004). InfiniteVehicles is the
chosen vehicle plugin for the deferred Milestone 4.

Custom item models are mapped in `config.yml` `custom-items:` (afterlife type →
CraftEngine id); author the pack from `docs/craftengine/afterlife-items.yml`
(see `docs/item-catalog.md`). Unmapped types keep vanilla materials.
