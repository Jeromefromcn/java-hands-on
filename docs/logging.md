# Logging

What a log line may carry, and how that is held.

## The rule

A log line carries **identifiers, counts, currencies, dates and outcomes**. It never carries:

- a **counterparty name**. The counterparty's id (`CP-001`) identifies it to anyone who has the
  ledger, and means nothing to anyone who has only the logs.
- a **monetary amount**: a price, collateral, a shortfall, a fee, a credit limit. A currency code is
  allowed; the number is not.
- a **request or message payload**, whole or in part.
- a secret of any kind.

Everything else in a line is for the person debugging at 3 a.m. and may be as specific as helps: the
position id, the ISIN, the idempotency key, the message id, the trace id, the job and its business
date, how many rows were read, written and skipped.

## Why

A log is a copy of the ledger in a system with a different retention, a different audience and no
access control of the ledger's kind. An amount in a log is an amount in every place logs are
shipped; a counterparty's name is the commercial relationship. Neither is needed to find what went
wrong, because the id leads back to the row.

## How it is held

- **At the source.** Log statements pass ids, never domain objects whose `toString` prints an amount
  (`Money` does). `LoggingNoticeChannel` is the example: it logs the position, the counterparty id,
  the ISIN and the currency of the shortfall.
- **By a test.** `LogHygieneIT` drives open, return, a price that raises a margin call and the
  notice that follows, the daily accrual, and a live and a stale quote, with a counterparty name and
  amounts that appear nowhere else in the system, with `com.jerome` at `DEBUG`. It fails if any of
  them is in the captured output. Putting the amount back into the notice's log line makes it fail.
- **Not by scrubbing.** A filter that masks amounts by pattern is a safety net with holes: it cannot
  recognise a name, and it gives the false comfort of a rule that is only partly kept. It was
  considered and left out; see `docs/decisions/12-resilience-observability.md`.

## Shape

In `prod` every event is one JSON object (Boot's `logstash` format) with the MDC, so `traceId` and
`spanId` are fields and not text to be parsed. Elsewhere it is the text pattern with the trace id in
brackets. A task handed to a pooled thread carries the submitter's MDC (`MdcPropagation`); without it
a pooled thread logs with no trace id, which `MdcPropagationTest` reproduces.
