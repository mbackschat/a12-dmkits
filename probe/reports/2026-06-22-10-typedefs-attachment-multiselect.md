# Probe report — task 10 (typedefs + attachment/multiselect), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 6 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS, 0 nonzero exits.** The structure surface — a shared `MoneyAmount` typedef used by two fields, `typedef modify` widening it (both fields inherited via `resolvedType`), `group attachment`, `group multiselect`, a cross-field rule, and a confirm read — built kernel-valid throughout. **This session's typedef/`kind` work confirmed in the wild:** `typedef modify` propagated to both cost fields, visible via `field read`'s `resolvedType`. Two minor DX findings.

## Provenance & integrity

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1) — release candidate, gate GREEN |
| start | **clean** (from-scratch support-case model) |
| audit | **CLEAN 6/6** — `SupportCase_DM.json` mutated only by dmtool; no raw read |
| digest | **CLEAN · 0 RED FLAGS · 0 nonzero exits** — every edit accepted first try |

## Findings

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 10-groupread | `group read <path>` returns only the group's repeatability, not its child fields — so it can't on its own confirm a just-built composite group; the agent fell back to `model describe` (in-surface) | CLI/material | minor (read-gap) | candidate — `group read` could echo child fields (symmetry with `field read`'s config echo), or a help pointer to `model describe` |
| 10-euro | "euro" has no representation; the kernel's only money marker is the generic `unit:AMOUNT` (no currency) | material | minor (doc) | candidate — a one-line schema note that `AMOUNT` doesn't encode a currency |
| — | `typedef modify` to 3 decimals propagated to **both** typed fields (`resolvedType…maxFractionalDigits:3`) — the single-source-of-truth payoff; `typedef read`/`model describe` spoke the clean `kind` vocabulary | — | **this session's work confirmed in the wild** | — |

## Replay log (verbatim, in order)
1. create a support-case model … one shared "Money amount" type … for both an estimated cost and an approved cost.
2. the money amounts should allow 3 decimals now — change it in one place.
3. let the customer attach supporting documents …
4. add a place to pick one or more affected products (a multi-select).
5. the approved cost must not exceed the estimated cost.
6. show me the money type and the two new groups …

## Self-audit
CLEAN: every mutation via dmtool verbs; the `*.json` were tool inputs; no raw `.dm.json` read. Audit 6/6, digest 0 RED FLAGS.

## Resolution (2026-06-22)
- **CLEAN re-run** — typedef define/reuse/propagate, attachment, and multiselect all behaved as specified; the `typedef modify` + `resolvedType` propagation (this session's kind/typedef work) is confirmed end-to-end.
- **10-groupread / 10-euro — recorded, minor candidates** (a `group read` child-field echo; an `AMOUNT`-has-no-currency schema note).
