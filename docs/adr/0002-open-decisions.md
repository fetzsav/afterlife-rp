# ADR 0002 — Deliberately open decisions

**Status:** open (2026-07-28)

Per master plan §19, these are NOT needed for Milestones 0–3 and remain open:

1. **Custom item / resource-pack provider** — ItemsAdder vs Oraxen vs Nexo.
   Decide before the content-production milestone (M9). The serialized-item
   framework only relies on PDC and is provider-agnostic.
2. **Vehicle plugin** — must expose ownership, towing, storage, health, fuel,
   and modification hooks. Decide before M4; all business logic goes through
   the vehicle adapter interface, never a vendor API directly.
3. **Judge policy** — automated civil judgments stay disabled until explicitly
   approved (M3).
4. **Dirty money representation** — physical notes only vs an additional
   controlled dirty account (M2).
5. **Branding** — final server name, IP, sidebar glyph, Italian terminology
   (M9).
