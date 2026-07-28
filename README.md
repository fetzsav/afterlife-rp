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

## Commands (Milestone 0+1)

| Command | Permission | Purpose |
|---|---|---|
| `/afterlife version` | `afterlife.admin` | Plugin + server version |
| `/afterlife health` | `afterlife.admin` | Database and adapter status |
| `/afterlife setup poi …` | `afterlife.admin.setup` | Create/remove/list POIs, setup report |
| `/afterlife debug item <type> [denom]` | `afterlife.admin` | Issue a test serialized item |
| `/id` (alias `/identita`) | — | Show permanent public ID |
| `/setnick <text\|reset\|off>` | `afterlife.vip.nickname` | VIP nickname |

## Milestones

Work proceeds one milestone at a time (master plan §17); current status lives in
`docs/milestones.md`.
