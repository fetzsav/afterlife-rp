# ADR 0003 — Vault economy bridge semantics

**Status:** accepted (2026-07-28)

Vault's `Economy` API is synchronous and is typically called on the server main
thread by third-party plugins, while rule 1 forbids main-thread SQL. The bridge
therefore works as follows:

- **Reads** (`getBalance`, `has`): served from the in-memory balance cache,
  which is populated at join and refreshed by every committed ledger
  transaction. Off the main thread, uncached (offline) players may block up to
  2 seconds for a DB read.
- **Writes** (`depositPlayer`, `withdrawPlayer`): validated against the cache
  (sufficient funds, frozen state), answered optimistically, and applied
  asynchronously through the shared double-entry ledger with a unique
  idempotency key. The counterparty is the `government_budget` system account —
  third-party plugins conceptually mint/burn against the government, which
  keeps the ledger balanced and the flows visible in daily source/sink reports.
- Failures of the async apply are logged; the cache is corrected by the next
  committed balance broadcast. The window for divergence is a single in-flight
  operation per player and is acceptable for non-authoritative third-party
  plugins. All AfterLife modules use the ledger directly and never go through
  Vault.
- Vault "bank" APIs are NOT_IMPLEMENTED; organization treasuries are ledger
  accounts.

Provider registration targets both classic Vault and VaultUnlocked (installed:
VaultUnlocked 2.20.2, which services the legacy `Economy` interface) at
`ServicePriority.Highest` so no second authoritative economy can win (§2.1).
