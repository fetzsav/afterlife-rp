# Item Catalog

AfterLifeRP identifies items by a stable **item type** stored in PDC plus a
server-side `serialized_items` record and an HMAC signature (rule 5–6). The
type is authoritative; the visual model is presentation only.

When CraftEngine is installed, each type can be given a custom 3D model by
mapping it to a CraftEngine namespaced id in `config.yml` under `custom-items:`.
Unmapped types (or a server without CraftEngine) fall back to the vanilla
material listed below — nothing breaks either way (ADR 0002).

| Item type (PDC) | CraftEngine id (default) | Vanilla fallback | Module |
|---|---|---|---|
| `banknote` | `afterlife:banknote` | PAPER | Banking |
| `dirty_money` | `afterlife:dirty_money` | PAPER | Banking / crime |
| `credit_card` | `afterlife:credit_card` | NAME_TAG | Banking |
| `check` | `afterlife:check` | PAPER | Banking |
| `receipt` | `afterlife:receipt` | PAPER | Nightclub |
| `contract` | `afterlife:contract` | PAPER | Legal |
| `property_key` | `afterlife:property_key` | TRIPWIRE_HOOK | Real estate |
| `black_file` | — | PAPER | Real estate |
| `medical_certificate` | `afterlife:medical_certificate` | PAPER | EMS |
| `forceps`/`bandage`/`splint`/`medkit`/`defibrillator`/`scanner`/`extraction_syringe`/`adrenaline` | (add per tool) | see EmsItems | EMS |
| `circuit_board` | `afterlife:circuit_board` | REPEATER (model 2004) | Electrician |
| `food_package` / `contraband_package` | — | CHEST / BARREL | Delivery |
| `drug_dose` | `afterlife:drug_dose` | SUGAR | Crime |
| `sealed_bag` | `afterlife:sealed_bag` | PAPER | Crime |
| `atm_hacking_device` | `afterlife:atm_hacking_device` | COMPARATOR | Crime |
| nightclub drinks (`vodka_redbull`, …) | (add per drink) | POTION | Nightclub |

## Authoring the CraftEngine pack

1. In `plugins/CraftEngine/resources/<pack>/configuration/`, define each item
   under the `afterlife` namespace (id = the value in `custom-items:`), pointing
   at your model + texture assets.
2. Run `/craftengine reload` (or restart) to load the pack and regenerate the
   resource pack.
3. AfterLifeRP applies the model automatically the next time an item of that
   type is issued — the PDC/HMAC is stamped on top of the CraftEngine base
   stack, so validation, K-9, and one-shot redemption are unaffected.

Only the visual base changes; never rely on the model or display name for
identity — always the PDC type + serial + signature.
