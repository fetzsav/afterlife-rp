# AfterLifeRP — Non-Negotiable Engineering Rules

Every change, in every milestone, must preserve these rules (from the master plan, §3):

1. All database access runs asynchronously.
2. All Bukkit world, entity, inventory, and GUI mutations return to the server thread.
3. Every money movement uses the shared ledger service.
4. Every transaction has a unique idempotency key.
5. Physical valuables use unique serial numbers and server-side status records.
6. GUI titles and item names are never used as authoritative identifiers.
7. Every GUI uses a custom `InventoryHolder` or session identifier.
8. Every long-running action is a persisted state machine.
9. Every privileged action writes an immutable audit record.
10. Every module can be disabled independently in configuration.
11. All prices, cooldowns, chances, coordinates, messages, and item definitions are configurable.
12. Secrets, database credentials, and signature keys never enter Git.
13. A restart must not duplicate rewards, lose accepted missions, or repeat a completed transfer.
14. Commands validate sender type, permissions, arguments, state, and cooldowns.
15. Never trust client-provided text, item lore, display names, or GUI clicks without server-side validation.

Additional standing constraints:

- Target: Paper 26.2, Java 25 (Paper 26.2 requires it). No NMS, no reflection.
- Source code, database identifiers, and config keys in English; player-facing text in Italian (`messages_it.yml`).
- Currency amounts are integer minor units (`BIGINT`); never floating point.
- MD5 is forbidden for signatures; use HMAC-SHA256 with a key stored outside Git.
- Mutable records use optimistic `version` columns; a lost race fails safely, never repeats a reward.
- Work one milestone at a time (master plan §17); stop at each exit gate and report per §18.

The full design document is `docs/master-plan.md` (copied from the server volume's `SERVER_PLAN.md`).
