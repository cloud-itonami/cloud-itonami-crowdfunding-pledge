# cloud-itonami-crowdfunding-pledge

**PledgeAdvisor ⊣ PledgeGovernor** — the actor that takes commitments and
cannot take money.

A `cloud-itonami` blueprint actor in the workspace's standard shape:
advisor sealed into one node of a [langgraph-clj](https://github.com/kotoba-lang/langgraph)
StateGraph, an independent governor, a 0→3 phase gate, an append-only
audit ledger. Domain rules from
[`kotoba-lang/crowdfunding`](https://github.com/kotoba-lang/crowdfunding);
design record: ADR-2607268500.

## A pledge is an authorization

Not a payment. The backer commits; the charge happens weeks later, after
the deadline, if the goal was met — in
[`cloud-itonami-crowdfunding-collection`](https://github.com/cloud-itonami/cloud-itonami-crowdfunding-collection),
a different repo behind a different governor.

This actor **structurally cannot charge anyone**: no rail, no charge op,
no store field for one. That is not a policy that could be relaxed in a
later phase; there is nothing to relax.

## Backer rights are rights, not favours

`:cancel-pledge` is **auto-committable** at phase 3, deliberately. A
backer withdrawing inside the funding window is exercising a right, and
routing a right through an approval queue is how a right quietly becomes
a favour.

The platform cancelling someone's pledge on its own initiative is a
different act wearing the same op. `governor/platform-initiated?`
separates them and escalates that one to a human, independently of the
phase table — so the op can pass the phase gate while the case that
matters still stops at a person.

## The invariant

> The governor rejects; the actor never writes what it refuses.

Six HARD checks, un-overridable by any human approval:

| Check | Why |
|---|---|
| Pledge invalid | `pledge-errors` re-run against the **store's** campaign and reward records — this is where "that tier is sold out" becomes ground truth rather than a claim |
| Uncomputable total | a total that is nil today is a charge invented later |
| Duplicate pledge id | idempotency is a refusal, not an overwrite of someone's commitment |
| Outside the backer window | after the deadline the question is a *refund*, which is not this actor's decision |
| `:effect` ≠ `:propose` | a claim to actuate outside governance |
| Scope exclusion | claiming to have charged, captured or refunded |

## Scarcity is conserved

Accepting a pledge claims a tier unit through `reward/claim`; cancelling
or changing releases it through `reward/release`. Neither goes near a
counter directly, which is what keeps `:oversold` an invariant to assert
on rather than a state the system reaches. A campaign that sold out to
backers who never paid is selling phantoms.

## Run it

```bash
clojure -M:dev:run     # offline demo: intake, a sold-out refusal, both cancellation paths
clojure -M:dev:test    # 25 tests
clojure -M:lint
```

## Licence

AGPL-3.0-or-later.
