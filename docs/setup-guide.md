# Setting up an AfterLifeRP city

From an empty server to a playable city. Every step is verifiable in game, and
the last step exports the result so the whole thing can be rebuilt on demand.

**Shortcut:** the plugin tells you all of this itself. Run
`/afterlife setup status` — it lists every module, what it still needs, and the
exact command that fixes each gap (click a suggestion to put it in your chat
box). This document is the same information in reading order.

---

## 1. Infrastructure (once)

1. **Database.** MariaDB beside the game container:
   ```bash
   cp .env.example .env          # set a real password
   docker compose -f docker-compose.dev.yml up -d
   ```
   Bind the port to the Docker bridge gateway (e.g. `172.18.0.1:3306`) so the
   game container can reach it.
2. **Plugin.** `./gradlew deployToServer` copies `AfterLifeRP.jar` into
   `plugins/`. First boot writes `plugins/AfterLifeRP/config.yml` and
   `secret.key`.
3. **Credentials.** Put the database host/password in
   `plugins/AfterLifeRP/config.yml`, then restart. Neither file belongs in Git.
4. **Dependency plugins.** LuckPerms (permissions), WorldEdit + WorldGuard
   (regions), PlaceholderAPI, VaultUnlocked (economy bridge), Citizens (NPCs),
   CraftEngine (custom item models). All optional except LuckPerms in practice —
   `/afterlife health` reports what is present and what each missing one costs.

Verify: `/afterlife health` shows `Database: connected` and the adapter list.

---

## 2. Language

`config.yml`:

```yaml
language:
  default: en          # console, and players who never choose
  available: [en, it]  # what /language offers
```

Players switch with `/language it`; the choice is stored per player and applies
to messages and manual books.

---

## 3. Permission groups

Nothing in the city is playable until players can hold jobs. Each job is one
permission node; the group names below are only a convention (`/afterlife setup
status` suggests them).

```
/lp creategroup banker      && /lp group banker      permission set afterlife.bank.banker true
/lp creategroup bankdirector&& /lp group bankdirector permission set afterlife.bank.director true
/lp creategroup lawyer      && /lp group lawyer      permission set afterlife.legal.lawyer true
/lp creategroup police      && /lp group police      permission set afterlife.police.officer true
/lp group police            permission set afterlife.police.k9 true
/lp creategroup estateagent && /lp group estateagent permission set afterlife.realestate.agent true
/lp creategroup electrician && /lp group electrician permission set afterlife.electrician.worker true
/lp creategroup rider       && /lp group rider       permission set afterlife.delivery.driver true
/lp creategroup medic       && /lp group medic       permission set afterlife.ems.medic true
/lp creategroup bartender   && /lp group bartender   permission set afterlife.nightclub.bartender true
/lp creategroup gang        && /lp group gang        permission set afterlife.crime.gang true
```

Then hand out jobs with `/lp user <player> parent add <group>`.

`afterlife.bank.user` is granted to everyone by default (ATM, IBAN, transfers,
cheques). Directors, managers, and bosses (`.director`, `.manager`, `.boss`) are
deliberately scarce roles — give them to a handful of trusted players.

---

## 4. Points of interest

A POI is a named location the plugin dispatches jobs to. **Stand where it
belongs and register it** — the coordinates come from where you are standing:

```
/afterlife setup poi create <TYPE> <name> [worldguard-region]
```

| Module | Types to register | Sensible count |
|---|---|---|
| Banking | `ATM`, `BANK_TERMINAL` | 3–6 spread across districts |
| Electrician | `ELECTRICAL_CABINET`, `TRAFFIC_LIGHT`, `REPEATER` | 6+ |
| Delivery | `RESTAURANT`, `DELIVERY_DESTINATION` | 2+ restaurants, 6+ addresses |
| Delivery (illegal) | `SHADOW_DROP` | 2–3, out of sight |
| EMS | `HOSPITAL_WORKSTATION`, `EMERGENCY_POINT` | 2 workstations, 4+ points |
| EMS (optional) | `TOXIC_BARREL` | 2–3 |
| Nightclub | `POS_TERMINAL`, `SHAKER_STATION`, `DJ_BOOTH` | 1–2 each |
| Crime | `STREET_SALE_ZONE` | 3+ (ATMs double as hack targets) |

Useful details:

- `/afterlife setup poi list` shows everything registered;
  `/afterlife setup poi report` writes `setup-report.txt` in the plugin folder.
- Passing a WorldGuard region name binds the POI to that region.
- The allowed type list lives in `config.yml` `poi.types`, and **any type an
  enabled module asks for is accepted automatically** — a module can never
  require a type the command rejects.
- POIs survive restarts; they live in the database, not in a config file.

---

## 5. Regions

The nightclub uses two WorldGuard regions, named in `modules/nightclub.yml`
(`security.club-region`, `security.vip-region`, default `nightclub` and
`nightclub_vip`). Select the area with WorldEdit and:

```
//wand → select the club → /rg define nightclub
//wand → select the VIP area → /rg define nightclub_vip
```

The weapon scanner and VIP door checks key off these.

---

## 6. Properties, organizations, licences

```
/afterlife setup property create <HOUSE|APARTMENT> <name> <price-euro> [region|-] [dirty]
/afterlife setup org create <TYPE> <name> [display name...]
/afterlife setup license grant <player> <TYPE> [days]
```

- Properties are registered where you stand. `dirty` marks a property for the
  illegal rental market (`/luoghisporchi`) instead of the legal one
  (`/luoghidisponibili`).
- An organization gets its own treasury account and IBAN.
- Licences are RP credentials shown by `/licenza`; conventional types are
  `MEDICAL`, `FIREARM`, `DRIVER`, `LAW_DEGREE`, `BUSINESS`.

---

## 7. Verify

```
/afterlife setup status     # per module: ready, or what is missing + the fix
/afterlife health           # database + integrations
/afterlife reconcile        # ledger integrity (should report clean)
/afterlife economy          # 24h money created/destroyed by reason
```

`setup status` is the gate: when every active module reads green, players can
work every job. Modules that are off show why — disabled in their config, a
config error, or waiting on a module they depend on.

The console prints the same summary on every boot:
`Setup: 7/9 active modules playable, 3 step(s) left`.

---

## 8. Make it reproducible

Once the city is right, snapshot it:

```
/afterlife setup export city
```

That writes `plugins/AfterLifeRP/setup/city.yml` with every POI, property, and
organization (names, types, coordinates, regions, prices). Keep it in version
control alongside the world.

To rebuild — a fresh database, a second server, a test instance:

```
/afterlife setup import city          # preview: what would be created
/afterlife setup import city apply    # actually create it
```

Import is idempotent: anything whose name already exists is skipped, so re-runs
are safe and partial applies can simply be repeated. Entries whose world is not
loaded are reported instead of silently dropped. Coordinates are world-bound —
the target server needs the same build.

What the blueprint does **not** carry: permissions (LuckPerms has its own
`/lp export`), WorldGuard regions (`/rg` definitions live in WorldGuard's own
storage), player-owned data, and the ledger. Back those up separately;
`tools/backup.sh` dumps the whole database.

---

## 9. Tuning

Every price, cooldown, chance, and POI type is in `plugins/AfterLifeRP/modules/
<module>.yml`; `enabled: false` turns a module off. An invalid file disables
that module loudly (console `SEVERE` + `setup status` shows the error) without
taking the rest of the server down.

Balance changes should be driven by `/afterlife economy`: if a reason creates far
more money than the sinks destroy, lower it there rather than adding new sinks.
`docs/economy-model.md` explains the source/sink model.
