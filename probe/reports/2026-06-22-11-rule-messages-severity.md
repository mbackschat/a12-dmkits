# Probe report — task 11 (rule messages, severities, token interpolation), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 6 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS — the prior native message-token CRASH stays fixed.** Authoring messages with interpolation tokens (`$#/Order/Items$` row index, `$/Order/Items/Count.value$`, the literal `$$1000`), `WARNING`/`INFO` severities, and a one-apply bilingual top-up all worked — **no exit 70**, confirming the v0.6.0/prior-probe native message-token crash is resolved on the release binary. Two skill notes, one of which refines this session's own cascade edit.

## Provenance & integrity

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1) — release candidate, gate GREEN |
| start | **fixture** — seeded from `cli/src/test/resources/models/order-ruled.dm.json` |
| audit | **CLEAN 6/6** — model mutated only by dmtool; no raw read |
| digest | **CLEAN · 0 RED FLAGS** — 2 nonzero exits, both legitimate (`MVK_NEG_CONDITION_IN_ITERATION` reject recovered with `GroupFilled`; `--add-locale --dry-run` enumerating the bilingual cascade gaps) |

## Findings

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 11-lint-direction | the `RK_UNGUARDED_NUMBER_COMPARISON` lint is **direction-aware** — it fired on `[Count] <= 0` but correctly stayed silent on `[BackorderQuantity] > 1000` (empty-as-0 can't trip the latter); the skill implies it always fires | skill | minor (clarity) | candidate — note in the skill that the lint flags only where `0` satisfies the comparison |
| 11-cascade-overstate | the bilingual cascade's enum-label / pattern-message top-ups are **conditional** (neither applied here — no enum labels, the postal-code pattern is a *rule condition* not a field `patternMessage`); the skill's checklist can read as always-required. The reliable scoping move is **`config modify --add-locale --dry-run`**, which enumerates the exact per-rule gaps (verified) | skill | minor (refines this session's cascade edit) | candidate — add the `--add-locale --dry-run` scoping tip; frame the enum/pattern items as "for each you have" |
| — | the message tokens (`$#…$` / `$….value$` / `$$`) authored without an exit-70 crash; `WARNING`/`INFO` severities + the bilingual one-apply all worked | — | **prior native-crash fix confirmed in the wild** | — |

## Replay log (verbatim, in order)
1. each line item's count must be greater than zero — and the error must show the entered count …
2. when there are several items, the message should say which line number is wrong.
3. … backorder quantity shouldn't exceed 1000 — but make it a warning …
4. add an informational note when express priority is chosen (just info) …
5. the count message should also mention that orders over $1000 need approval — with a literal dollar sign.
6. all of these messages should also read correctly in German.

## Self-audit
CLEAN: every mutation via dmtool verbs; the `*.json` were rule/apply inputs; no raw `.dm.json` read. Audit 6/6, digest 0 RED FLAGS.

## Resolution (2026-06-22)
- **CLEAN re-run** — the message-token native path (the prior crash surface) is confirmed fixed; severities + bilingual authoring work.
- **11-lint-direction / 11-cascade-overstate — confirmed skill candidates** (the lint is direction-aware; recommend `--add-locale --dry-run` for scoping and frame the cascade's enum/pattern items as conditional — refining this session's cascade edit).
