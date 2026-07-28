# Economy Model

The AfterLife ledger is the single source of truth for clean money and registers
as the Vault economy provider (ADR 0003). All amounts are integer minor units
(cents) stored as `BIGINT`; never floating point.

## Money types

1. **Clean money** — digital balances in `accounts`, moved only by balanced
   double-entry `ledger_transactions` / `ledger_entries`.
2. **Dirty money** — serialized physical notes (`serialized_items`, type
   `dirty_money`). Not depositable into ordinary accounts; earned from crime
   loops and spent on illegal rentals/escrow. Laundering is a future mechanic.
3. **Organization treasuries** — system clearing accounts per business
   (hospital, nightclub) plus the government budget.
4. **Checks** — serialized, single-redemption, payee-specific instruments.

## System clearing accounts (`accounts.code`)

| Code | Role |
|---|---|
| `cash_issuance` | Counterparty for ATM deposits/withdrawals (physical cash ↔ digital) |
| `government_budget` | Source of job wages/rewards, sink for fees/fines |
| `seizure` | Destination for legal asset seizures |
| `check_clearing` | Holds funds behind outstanding checks |
| `hospital_treasury` | EMS billing revenue, pays medic commission |
| `nightclub_treasury` | POS revenue, pays wholesale supply |
| `bounty_escrow` | Holds escrowed bounty funds until payout |

These have `allow_negative = 1` where they represent an external source/sink
(cash issuance, government, treasuries); balanced entries keep the whole system
summing to zero, which `/afterlife reconcile` verifies.

## Source / sink discipline (§7.5)

Legal jobs move money **from** government/business budgets rather than minting
it. The daily report (`/afterlife economy`, and a log line every 24h) sums the
net flow to non-system accounts per transaction reason, so staff can see
creation vs destruction by category before adjusting rewards:

```
/afterlife economy
  ELECTRICIAN_PAY: +12.400,00 € (83 tx)
  POS_SALE:        -4.210,00 €  (140 tx)
  ...
  Created: 40.100,00 €  Destroyed: 9.300,00 €
```

Money sinks include property/licence purchases, impound and repair fees,
vehicle upgrades, hospital reagents, wholesale supply, expungement and
certificate fees, and the percentage lost when dirty money is laundered.

## Reconciliation

`ReconciliationService` (daily + `/afterlife reconcile`) asserts two invariants:
every transaction's entries sum to zero, and every account's cached balance
equals the sum of its ledger entries. Any drift is logged as a defect.
