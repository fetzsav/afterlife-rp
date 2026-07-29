# AfterLifeRP

Modular city-roleplay core plugin for Paper 26.2 / Java 25. The design source of
truth is `docs/master-plan.md`; the standing engineering rules are in `AGENTS.md`.

## Development

```bash
# 1. Start the dev database (Docker)
cp .env.example .env   # edit passwords first
docker compose -f docker-compose.dev.yml up -d

# 2. Build and test
./gradlew check                      # unit tests
AFTERLIFE_IT=1 ./gradlew check       # + MariaDB integration tests (needs Docker)

# 3. Deploy to the local server
./gradlew deployToServer             # copies AfterLifeRP.jar into ../plugins/
```

The server loads runtime libraries (HikariCP, Flyway, MariaDB driver) through
`plugin.yml` `libraries:` — keep those versions in sync with `build.gradle.kts`.

On first boot the plugin generates `plugins/AfterLifeRP/config.yml` (set the
database credentials there) and `plugins/AfterLifeRP/secret.key` (HMAC key —
never commit either; rule 12).

## Setting up a city

`docs/setup-guide.md` walks an empty server to a playable city. In game the
plugin guides itself: `/afterlife setup status` lists every module, what it
still needs, and the command that fixes each gap; `/afterlife setup export city`
snapshots the result so it can be rebuilt with `import`.

## Commands (by module)

| Area | Commands |
|---|---|
| Core | `/afterlife <version\|health\|reconcile\|economy\|debug>`, `/id`, `/setnick`, `/language`, `/manuale` |
| Setup | `/afterlife setup` (menu), `setup status` (readiness checklist), `setup poi/property/org/license`, `setup export\|import` |
| Banking | `/iban`, `/atm` (`/banca`), `/bonifico`, `/assegno`, `/incassa`, `/banchiere`, `/sequestro` |
| Legal | `/contratto`, `/valida_contratto`, `/fedina`, `/pulisci_fedina`, `/arresto`, `/rilascio`, `/avvocato`, `/ricorso`, `/prova`, `/licenza` |
| Real estate | `/luoghidisponibili`, `/luoghisporchi`, `/agenzia`, `/cambia_serratura`, `/cassaforte`, `/chiave` |
| Jobs | `/elettricista`, `/rider` (`/job`), `/ems` (`/medico`) |

Permissions follow `afterlife.<module>.<action>` (see `plugin.yml`).

## Milestones

Work proceeds one milestone at a time (master plan §17); current status lives in
`docs/milestones.md`.
