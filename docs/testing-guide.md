# AfterLifeRP — Setup & Testing Guide

A practical walkthrough for exercising every current feature on a live server.
Assumes the plugin is deployed and the database is running (it is — see
`docs/admin-setup.md`).

---

## 0. Before you start

- **Join the server** (address `147.135.104.55:22005`, online-mode is on — you
  need a real account).
- **Become OP** so you hold every permission. From the panel console or RCON:
  `python3 tools/rcon.py op <yourName>`. Most module permissions default to
  `op`, so an operator can drive every job alone. `afterlife.bank.user` is
  granted to everyone.
- **Two-player features** (transfers to a player, contract signing, POS,
  escrow, arrest, certificate) need a **second account** — an alt or a friend.
  Give it the relevant permission with LuckPerms, e.g.
  `/lp user <name> permission set afterlife.bank.banker true`.
- **Verify the plugin is healthy:** `/afterlife health` — database connected,
  all adapters active (LuckPerms, WorldGuard, PlaceholderAPI, Vault, Citizens,
  ProtocolLib, CraftEngine).

### Speeding up timed features (optional but recommended)

Some jobs run on real-world timers. For testing, edit the module file under
`plugins/AfterLifeRP/modules/` and **restart** (`python3 tools/rcon.py`
can't reload configs; use the panel restart or `tools/wings.sh restart`):

| File | Key | Set to | Why |
|---|---|---|---|
| `electrician.yml` | `dispatch-interval-minutes` | `1` | faults appear every minute |
| `ems.yml` | `emergency.interval-minutes-min/max` | `1`/`2` | NPC emergencies spawn quickly |
| `nightclub.yml` | `restock.delivery-delay-minutes` | `0` | wholesale stock lands within ~1 min |
| `crime.yml` | `gang.demand-interval-seconds` | `20` | street demand appears sooner |

Do **not** delete `plugins/AfterLifeRP/config.yml` — it holds the DB password.

### Giving yourself test items

`/afterlife debug item <type> [denomination]` issues a stamped serialized item
(rendered as paper unless a CraftEngine model is mapped — identity still works).
`/afterlife debug dirtymoney <euro>` issues physical dirty banknotes. Useful
types: `banknote`, `credit_card`, `check`, `tool_kit`, `scanner`, `forceps`,
`bandage`, `splint`, `medkit`, `extraction_syringe`, `circuit_board`,
`drug_dose`.

### Verifying results

- In-game feedback messages (now English by default).
- `/afterlife reconcile` — ledger integrity (should always be clean).
- `/afterlife economy` — 24h money created/destroyed by reason.
- Server log — audit-worthy actions, mission recovery, daily reports.
- Database — `docker exec afterlife-mariadb mariadb -uafterlife -p<pw> afterlife
  -e "SELECT ..."` (password in `.env`).

---

## 1. Language selection

- `/language` — shows your current language and the available ones.
- `/language it` — switch to Italian; every message and manual now renders in
  Italian. `/language en` switches back. The choice persists across relogs
  (`players.locale`).
- `/manuale` then `/manuale banca` (etc.) — get an in-game manual book in your
  chosen language.

---

## 2. Identity & banking

1. `/id` — your permanent public ID; `/setnick Alex` needs
   `afterlife.vip.nickname`.
2. `/iban` — your account (everyone gets one on first join).
3. Fund yourself for testing: `/afterlife debug dirtymoney 100` is dirty (not
   bankable). For clean money, the simplest path is an ATM deposit of issued
   banknotes, or a staff transfer. To deposit: `/afterlife debug item banknote
   50` a few times, then use the ATM (below).
4. **ATM:** register a terminal — stand somewhere and
   `/afterlife setup poi create ATM atm_centrale`. Give yourself a card:
   `/banchiere carta <yourName>`. Stand on the POI and `/atm` → opens the GUI
   (balance, quick withdrawals, deposit-all, statement). Deposit your banknotes;
   your balance rises.
5. **Transfer:** `/bonifico <otherPlayer> 20` or `/bonifico <IBAN> 20`.
6. **Check:** `/assegno <otherPlayer> 30` → they hold it and `/incassa`.
7. **Banker:** `/banchiere apri <player>`, `/banchiere revoca <player>`.
8. **Director:** `/sequestro <player> congela indagine` then
   `/sequestro <player> scongela ok` — a frozen account can't withdraw/transfer.

Check `/afterlife reconcile` after — it must stay clean.

---

## 3. Legal & police

1. **Contract:** write a Book and Quill (vanilla), hold it,
   `/contratto proponi <player>`; the other player `/contratto firma` — a signed
   contract item is produced. A lawyer holds it and `/valida_contratto`.
2. **Record:** `/fedina aggiungi <player> MINOR schiamazzi`, then
   `/fedina <player>` to view, `/fedina sconta <id>` to mark served. A lawyer
   `/pulisci_fedina <player>` expunges eligible (served minor) records.
3. **Detention:** `/arresto <player> 15`; the target `/avvocato` calls a lawyer;
   `/ricorso <player>` only releases if the timer is over; `/rilascio <player>`
   releases manually.
4. **Evidence:** hold a serialized item, `/prova crea coltello`; view with
   `/prova vedi <id>`. A non-officer viewing is denied **and** logged.
5. **Search:** target runs `/acconsenti <you>`, then
   `/polizia perquisisci <player> CONSENT`. Without consent it's denied and
   audited. `/polizia mandato SEARCH <player>` then `... WARRANT` also works.
6. **Account check:** `/polizia controlloconto <player>` — band + frozen only.
7. **K-9:** `/k9 turno` → `/k9 schiera` (auto-sniff) or `/k9 fiuta <player>`;
   it flags configured contraband unless sealed.

---

## 4. Real estate

1. `/afterlife setup property create HOUSE villa_1 50000` at your location
   (add `dirty` at the end for an off-books property; add a region name before
   it to bind WorldGuard).
2. `/luoghidisponibili` lists it. `/agenzia vendi villa_1 <player>` sells it and
   delivers a key; the buyer's balance drops. `/chiave` validates a held key.
3. `/cambia_serratura villa_1 sfratto` — the old key now fails `/chiave`, the
   owner gets a new one.
4. **Dirty:** create with `dirty`, then `/agenzia affittasporco villa_1 <player>`
   (tenant must hold enough dirty money). A Confidential File is produced;
   `/cassaforte` shows the black safe, `/cassaforte preleva 100` withdraws (as a
   director). `/agenzia distruggi_file` (holding the file) ends the rental.

---

## 5. Dispatch jobs

**Electrician** (set `dispatch-interval-minutes: 1`, restart):
1. `/afterlife setup poi create ELECTRICAL_CABINET cab_1`.
2. `/elettricista turno`, wait ~1 min, `/elettricista chiamate` shows the fault.
3. `/elettricista accetta cab_1`, follow the compass, hold a
   `tool_kit` (`/afterlife debug item tool_kit`), `/elettricista ripara` → wiring
   minigame → pay + 1% circuit-board chance.

**Delivery:**
1. POIs: `RESTAURANT resto_1`, `DELIVERY_DESTINATION dest_1` (and
   `SHADOW_DROP drop_1` for contraband).
2. `/rider turno` → `/rider ordine` → go to the restaurant → `/rider ritira`
   (package appears) → go to the destination → `/rider consegna`. Tip scales
   with speed. After a legal delivery there's a 15% contraband offer
   (`/rider accetta_pacco`).

---

## 6. EMS

1. POIs: `HOSPITAL_WORKSTATION ws_1`, `EMERGENCY_POINT er_1`,
   `TOXIC_BARREL barrel_1`.
2. Get hurt: take fall damage until injured (you'll see a message). A second
   player with `/ems turno` holding a `scanner` runs `/ems scan <you>` to see the
   injury and required tools, then `/ems cura <you>` holding each tool in
   sequence (e.g. `splint` then `bandage` for a fracture). Wrong tool is
   rejected; the final step bills you and pays the medic 10%.
3. `/ems produci bandage` at a workstation mints a traceable batch; `/ems
   traccia` (holding the medicine) reads it back.
4. `/ems certificato <player>` — only if they have no open injuries.
5. **Emergencies** (needs Citizens; lower the interval): with a medic on duty,
   `/ems emergenza` claims the spawned NPC, `/ems cura_npc` ×3 completes it.
6. **Toxic:** hold an `extraction_syringe` at a barrel, `/ems estrai`, stand
   still ~45–60s (green smoke) → chemical; `/ems converti` at a workstation makes
   illegal adrenaline.

---

## 7. Nightclub

**Stock first** — it starts at 0. `/club rifornisci vodka_redbull 20`
(delivery after the configured delay; set it to 0 and wait ~1 min), then
`/club magazzino` to confirm stock.

1. **POS:** `POS_TERMINAL pos_1`. Near it, `/club vendi <customer>
   vodka_redbull:2`; the customer `/club conferma` (or `rifiuta`). They get
   drinks + a receipt, you get a receipt and commission.
2. **Shaker:** `SHAKER_STATION shaker_1`, `/club shaker vodka_redbull` → timing
   minigame → quality drink. Right-click a drink to consume its effect
   (cooldown applies).
3. **Security:** `/club scanner <player>` lists their weapons;
   `/club blacklist add <player> reason` then blacklisted players get pushed back
   at the club boundary (needs a WorldGuard region named `nightclub`).
4. **DJ:** `DJ_BOOTH dj_1`, `/club dj` — light/smoke show.
5. **Escrow (two gangs + bartender):** `/club escrow crea <A> <B> 100`; A holds
   a serialized item and `/club escrow deposita`; B `/club escrow paga` (holding
   dirty money); both `/club escrow blocca`; the bartender `/club escrow
   conferma` swaps atomically.
6. **Bounty:** `/club taglia crea <target> 200` (escrowed), `/club taglia lista`,
   `/club taglia paga <id> <claimant>` (bartender).
7. **Manager:** `/club staff assumi <player> BARTENDER 10`, `/club prezzi
   vodka_redbull 15`, `/club happyhour` (20% off, 30 min).

---

## 8. Crime

1. POIs: `STREET_SALE_ZONE zone_1`; an `ATM` doubles as a hack target.
2. **Dealing:** `/afterlife debug item drug_dose`, `/gang turno`, stand in the
   zone, `/gang vendi` (holding a dose) → dirty cash, with a suspicion-alert
   chance.
3. **Sealing:** `/gang sigilla` (holding a dose) → sealed bag defeats the K-9;
   `/gang apri` reverses it.
4. **Drug trip:** right-click a `drug_dose` → 70/30 good/bad hallucination
   entities **only you** can see, cleaned up after ~20s.
5. **ATM hack:** `/afterlife debug item circuit_board`, `/gang costruisci` →
   device, stand at an ATM, `/gang hackera`, hold position ~20s → dirty payout
   (raises a police alert).

---

## 9. Admin & integrity checks

- `/afterlife setup poi list` / `report` — see and export all POIs.
- `/afterlife setup org create business pizzeria "Pizzeria Roma"` — org +
  treasury.
- `/afterlife setup license grant <player> DRIVER 30`.
- `/afterlife reconcile` — run after any money test; must be clean.
- `/afterlife economy` — money created vs destroyed by reason.
- **Backup rehearsal:** `tools/backup.sh` writes a dump; `tools/backup.sh
  restore <file>` restores it (prompts first).

---

## 10. Automated tests (no server needed)

```bash
./gradlew check                 # 16+ unit tests
AFTERLIFE_IT=1 ./gradlew test   # + Testcontainers MariaDB integration tests (75 total)
```

The integration suite proves the exit-gate guarantees (no double rewards, atomic
swaps, one-shot redemption, authority-gated searches, restart recovery). The
abuse-coverage map is `docs/exploit-matrix.md`.
