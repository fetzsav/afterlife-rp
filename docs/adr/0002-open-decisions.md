# ADR 0002 — Deliberately open decisions

**Status:** open (2026-07-28)

Per master plan §19, these are NOT needed for Milestones 0–3 and remain open:

1. **Custom item / resource-pack provider** — DECIDED (2026-07-28):
   **ItemsAdder** (4.0.17, installed). It requires **ProtocolLib** — installed
   the 5.5.0-SNAPSHOT dev build, which lists 26.2 support (the 5.4.0 release
   does not). This also re-enables the packet-only visuals / virtual-sign path
   that ADR 0004 deferred. The serialized-item framework stays PDC-based and
   provider-agnostic; ItemsAdder custom models are applied through a soft
   adapter with vanilla-material fallback.
2. **Vehicle plugin** — DECIDED (2026-07-28): **InfiniteVehicles** (premium,
   has a plugin API, in-game builder, .bbmodel import, builds tracking MC
   26.2). M4 itself is deferred by the operator; when it starts: purchase and
   install the plugin, verify its API covers ownership/towing/storage/health/
   fuel/modification hooks on 26.2, and wrap it behind the vehicle adapter
   interface — business logic never touches the vendor API directly.
3. **Judge policy** — automated civil judgments stay disabled until explicitly
   approved (M3).
4. **Dirty money representation** — physical notes only vs an additional
   controlled dirty account (M2).
5. **Branding** — final server name, IP, sidebar glyph, Italian terminology
   (M9).
