# ADR 0004 — Player-only hallucinations without ProtocolLib

**Status:** accepted (2026-07-28)

The master plan (§9.4, §14) requires drug-trip hallucination entities visible
ONLY to the consuming player, and lists ProtocolLib as the packet adapter.

**Decision:** implement hallucinations with Paper's `Player#hideEntity` /
`showEntity` on real server-side entities spawned near the consumer, hidden
from everyone else, and removed on a timer. This meets the "affects only the
consumer" exit-gate requirement with no new dependency and no packet code.

Trade-offs vs ProtocolLib packet-only entities:
- The entity briefly exists server-side (AI disabled, invulnerable, silent,
  persistence off). It is hidden from all other players, so no one else sees
  it; the only observable difference is a marginally higher server-entity
  count during a trip, bounded by a per-player cap.
- Virtual sign input and packet-only visuals (master plan §2.1) still need
  ProtocolLib if/when those features arrive; this ADR does not preclude
  installing it later. It is simply not required for M8.

Hallucination safety (§9.4): entities never deal real damage (invulnerable,
no AI targeting, cancelled damage), are capped per player, and are always
cleaned up on trip end, quit, death, and plugin disable.
