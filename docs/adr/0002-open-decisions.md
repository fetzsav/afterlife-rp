# ADR 0002 — Deliberately open decisions

**Status:** open (2026-07-28)

Per master plan §19, these are NOT needed for Milestones 0–3 and remain open:

1. **Custom item / resource-pack provider** — DECIDED (2026-07-28), REVISED
   (2026-08-02): **CraftEngine** (26.7.4, installed). ItemsAdder 4.0.17 was
   chosen first but crashed on Paper 26.2 and was rejected. ItemsAdder had
   pulled in **ProtocolLib** (5.5.0-SNAPSHOT); with ItemsAdder gone nothing
   used it, so ProtocolLib has been removed too. The packet-only visuals /
   virtual-sign path stays deferred — ADR 0004 (Paper `hideEntity`) still
   stands and the plugin sends no packets. The serialized-item framework stays
   PDC-based and provider-agnostic; CraftEngine models are applied through a
   soft adapter with vanilla-material fallback.
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
