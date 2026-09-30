# Client Margin Changes: Choosing a Consistency Scheme — Decision Record

Companion to [`10-messaging.md`](10-messaging.md). Spec:
[`docs/specs/10-messaging.md`](../specs/10-messaging.md).

The question the roadmap asks: why is `UPDATE balance = balance - x` not enough
to move a client's collateral, and what are at least three alternatives
worth?

Every number below is copied from `docs/evidence/10-*.txt`, measured on this
machine (JDK 21.0.12, Postgres 17 in Testcontainers, one host). Where a claim is
reasoned and not measured, it says so.

## Short answer

`balance = balance - x` is atomic and does nothing else. It does not check that
the balance can afford `x`, does not know whether this request has already been
applied, does not say why the balance moved, and cannot take part in a change
that spans another store. Each of those is a separate failure, and each was
reproduced ([`10-collateral-update.txt`](../evidence/10-collateral-update.txt),
[`10-tcc.txt`](../evidence/10-tcc.txt)).

## What the one-line update gets wrong

32 requests each want 10 from a balance of 100, all having read the balance
before any of them writes (a barrier makes the gap deterministic). Three ways
to write the update:

| Form | Requests told they took 10 | Final balance | What it means |
|---|---|---|---|
| read, check, write the computed value | 32 | 90 | 320 was promised; one withdrawal is in the balance (lost update) |
| read, check, then `balance = balance - 10` | 32 | **−220** | the decrement is atomic, the check was not: overdrawn by 220 |
| `UPDATE … WHERE balance >= 10` | **10** | 0 | the check and the write are one statement; 22 refused |

So the one-line update fails as a *check* problem, not as an arithmetic one.
The fix that costs nothing is to make the guard part of the statement. That is
the baseline every other option below is measured against, and inside one
database for one balance it is the right answer.

It is not enough once the change has three more requirements:

1. **Idempotence.** A retried request applies twice. `balance - 10` run twice is
   `balance - 20`. The guarded statement is atomic but not idempotent.
2. **Audit.** After the update the balance is 90 and nothing says which request
   made it so. For client money that is a regulatory problem before it is an
   engineering one.
3. **More than one participant.** Opening a position needs credit reserved,
   collateral frozen, and a position written. No single statement spans them.

## The options

| | Atomic on one balance | Idempotent | Auditable | Spans participants | Cost |
|---|---|---|---|---|---|
| **A. Guarded update** (`WHERE balance >= x`) | yes | no | no | no | none |
| **B. Version column** (optimistic) | yes, by retry | no | no | no | a retry loop; conflicts under contention |
| **C. Append-only entries, balance derived** | yes | yes, with a unique key on the request id | yes | no | a read is a sum, or a maintained total |
| **D. TCC: Try / Confirm / Cancel** | per step | yes, by the transaction table | the transaction table | yes | a state table, three operations per participant, every one safe in any order |
| **E. Saga (orchestrated)** | per step | yes, if each step is | the saga log | yes | a log, compensations, recovery |
| **F. Two-phase commit** | yes, across stores | yes | the coordinator's log | yes | blocks; see below |

**A. Guarded update.** Measured above: exactly 10 of 32, balance 0. Used by the
TCC participant's Try (`WHERE available >= x`). Not enough alone because it has
no memory of the request.

**B. Optimistic version.** Stage 6's tool for a position. For a balance it turns
the check-then-act gap into a retry: correct, and pointless when the guard in A
achieves the same in one statement. It pays for itself when the new value
depends on a computation that cannot be a single SQL expression.

**C. Append-only entries.** The accounting answer: never update a balance,
insert an entry `(request id, account, amount)` under a unique key on the
request id, and derive the balance. Idempotence and audit come free. It is what
I would build for the ledger's collateral if this were more than a stage
exercise, and it is **not built here**; the stage builds D and E because the
roadmap asks for them and they are what spans participants. Reasoned, not
measured: a derived balance needs either a sum per read or a maintained total,
and the maintained total is a guarded update again.

**D. TCC.** Built as `CollateralHolds`. The property it buys over A is that the
funds are *held*, visible as neither available nor spent, so a second request
cannot spend them while the first is in flight, and either outcome (Confirm or
Cancel) can still be chosen. The property it costs is the three failures the
participant has to survive when it does not remember the transaction:

- **Empty rollback.** A Cancel arrives with no Try (the Try was lost, or never
  ran). A participant that just returns the amount to *available* creates
  spendable collateral: available 1,000.00 becomes 1,100.00, frozen −100.00.
- **Suspension.** The usual patch, "cancel only if that much is frozen", stops
  the negative hold and opens the other hole: Cancel(100) arrives first and does
  nothing, the delayed Try(100) then freezes 100.00 for a transaction whose
  Cancel has already come and gone. Nothing will ever release it.
- **Duplicate Confirm.** With A's and B's holds of 100.00 each (frozen 200.00), A's
  Confirm delivered twice leaves frozen at 0: B's hold was consumed by A's
  duplicate.
- The patched Cancel also *steals*: B holds 100.00, A (never tried) cancels
  100.00, and B's hold is gone; B's Confirm then leaves frozen at −100.00.

One table repairs all four: `tcc_transaction(xid PRIMARY KEY, status, …)`. Try and
Cancel both begin with `INSERT … ON CONFLICT DO NOTHING`. Whoever writes the row
first decides: a Cancel that writes it first has recorded the empty rollback,
and the Try that follows finds `CANCELLED` and is refused; Confirm and Cancel move
money only when they change the status *from* `TRIED`, so a repeat finds another
status and moves nothing. Forty rounds of a Try racing its own Cancel ended with
available 1,000.00 and frozen 0 every time (Try landed first 17 times, Cancel
first 23).

**E. Saga.** Built as `OpenPositionSaga`. It is the answer to "several
participants, and I need a way back". What it is *not* is atomic: between its
steps the world is visible in a half-done state (credit reserved, collateral
frozen, no position yet), which is why the steps before the pivot are holds,
not final. It also needs the compensations to be safe in any order, which is
exactly what D's table provides; the saga's recovery compensates *everything*
without reading the log, and 12 crash points, each followed by recovery, end in
one of two states ([`10-saga.txt`](../evidence/10-saga.txt)).

**F. Two-phase commit.** Not built. Measured with Postgres's own
`PREPARE TRANSACTION`: a participant prepared, the coordinator gone, the
connection closed; another writer of the same row, willing to wait 2 s, waited
2,009 ms and gave up without the row. It would have waited for as long as the
prepared transaction exists, which no timer ends: only a person or a recovery
job running `ROLLBACK PREPARED`, after which the same update took 3 ms
([`10-2pc-blocking.txt`](../evidence/10-2pc-blocking.txt)). A participant that
has voted yes cannot decide alone, so the coordinator is a single point whose
failure freezes rows in every participant. Against a saga, 2PC gives isolation
and atomic visibility and takes availability; a saga takes the reverse. For
collateral, where a frozen row means a client cannot trade, I would rather have
the half-done state of a saga, held explicitly, than the blocked row.

## What I chose

- **Inside one database, for one balance:** the guarded statement (A), because
  it is exact (10 of 32, balance 0) and free.
- **Across credit, collateral and the ledger:** TCC holds (D) as the
  participants and an orchestrated saga (E) as the flow. The hold is what makes
  the intermediate state explicit and reversible.
- **Not built, and what I would add next:** append-only entries (C) under the
  hold, so the account has an audit trail and the request id is a unique key.

## Where it breaks at 100×

- **A hot account.** Every Try locks the account row, so one counterparty's
  holds serialise; the credit reservation locks the counterparty row for the
  same reason. Fine for a lending desk's per-counterparty rate; not for a
  market-maker's account. The fix is to shard the balance into buckets and
  reserve against a bucket, at the price of an available figure that is a sum.
- **`tcc_transaction` grows.** One row per attempt, kept for ever, and the
  tombstones of cancelled attempts must outlive any delayed Try. The retention
  rule is the same as the dedup table's (longer than the longest a message can
  be late) and it is not implemented: no purge exists for this table.
- **The saga's log** is read to find unfinished sagas with a `NOT EXISTS` over
  the whole table; at 100× it wants an index on the terminal marker or a state
  column on a saga row.
