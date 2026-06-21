# Probe report — task 05 (full authoring session), re-run 2026-06-22

**Headline: the re-run on the F1/F4/F1b/F2-fixed binary is CLEAN — 0 RED FLAGS, audit CLEAN — and the prior run's two defects (F1 per-locale pattern message, F4 silent roles) did NOT recur.** The session is efficient (78 calls, only **9% nonzero** — orient/verify-heavy, the right shape). It surfaced new *material/skill* friction in the aggregate-rule surface, one item independently corroborated by the parallel run 06.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` — rebuilt this session (F1/F4/F1b/F2 fixes; metadata re-captured, no diff); native gate GREEN (168/0) |
| start mode | **clean** (from-scratch authoring) |
| anti-cheat audit | **CLEAN 5/5** — tool-only model mutations; hand-authored files were spec JSONs + the `auth/*.yaml` sidecars dmtool only lints; no leak |
| digest | **78 calls · 7 nonzero (9%) · 0 RED FLAGS** · author 27 / verify 14 / orient 37 |
| vs. the 2026-06-21 run | that run *found* F1/F4; this one runs on the binary that *fixed* them — neither recurred. Call-count is comparable (~70→78), but the friction shifted from F1/F4 to the new aggregate-surface items below; the nonzero ratio is low (efficient) |

## Findings (classified)

| # | finding | target | class | verified |
|---|---|---|---|---|
| F05-1 | **the skill's aggregate worked example is kernel-rejected** — `Sum(Lines*/Amount Having …) > 500` on `--field /Invoice/Total` fails `MVK_ERROR_FIELD_NOT_REFERENCED` (the error field never appears in the condition), and the skill *also* says "put its error field on a non-repeatable field" — steering you into exactly this trap | skill (`SKILL.md`) | **DEFECT (non-working example)** | **maintainer minimal repro ✓** (below) |
| F05-2 | **`MVK_NO_WILDCARD`'s `fix`/`explain` are canned to a `CurrentRepetition`/`Items` scenario** — emitted even when the condition has no `CurrentRepetition` and no `Items` group (real trigger: a `GroupFilled`/aggregate ref to a *repeatable* group from a model-level scope) | diagnostics | **DEFECT (canned/misleading fix)** | shim log; **= run 06's F06-2** (convergent) |
| F05-3 | **`group extract` names the new sub-model's root group after the *extracted* group** — extracting `/Person/HomeAddress --reference Address_DM` produced a sub-model whose root group was `HomeAddress`, a poor name for a DM reused under *Work* too; needed a follow-up `group rename` | CLI (`group extract`) | friction / DX | shim log + agent repro |
| — | `model new -o repro/Invoice_DM.json` with a missing parent dir prints a bare `cannot write output: <path>` (clear enough; a "parent directory does not exist" hint would help) | CLI | minor | shim log |
| — | age≥18 + plain requiredness correctly modeled as **field properties** (min / required), not rules; "at most one main" chosen over "exactly one" (correct on zero rows) | — | **expected / correct** | model re-read |

## The confirmed defect (F05-1) — maintainer repro

**Why an `/Invoice/…` appears in a *person* session:** F05-1 is about the **skill's own aggregate worked example**, which is written in an *invoice* domain (`/Invoice/Total`, `/Invoice/Lines`). The Person task has no invoice — the agent (and this maintainer repro) built a throwaway minimal `Invoice` model purely to run that skill example through the kernel. The `/Invoice/Total` is the skill example's field, not part of the Person model.

On a clean minimal invoice (`/Invoice/Total` NUMBER; repeatable `/Invoice/Lines` with `Amount`, `Type`):

| error field | condition | result |
|---|---|---|
| `/Invoice/Total` | `Sum(Lines*/Amount Having [Lines/Type] == "FEE") > 500` *(the skill's example)* | ❌ `MVK_ERROR_FIELD_NOT_REFERENCED` |
| `/Invoice/Total` | `FieldFilled(Total) And Sum(Lines*/Amount Having …) > 500` | ✅ valid |
| `/Invoice/FeeCap` | `FieldFilled(FeeCap) And [FeeCap] < Sum(Lines*/Amount Having …)` | ✅ valid (error field referenced via the comparison) |

So a model-level aggregate rule's error field, like any rule's, **must be referenced in the condition** — and the skill's example violates that while its surrounding prose steers the reader toward it. (The fix lands in `SKILL.md`: reference the error field + state the rule.)

## Replay log (verbatim, in order)

1. create a document model for a person with a home and work address, add proper conditions. And ensure the person's age is >= 18.
2. add a German locale to the person model.
3. create some users and roles and attach them to the model.
4. change the repeatability of the work address to 10, and somehow flag the main work address.
5. move the fields of the address group to a separate dm and include it for both home and work.

## Self-audit

Audit CLEAN 5/5: `Person_DM.json`/`Address_DM.dm.json` mutated only by dmtool; hand-authored files were spec JSONs + the `auth/*.yaml` sidecars (which have no authoring verb — dmtool only lints them, and the lint resolved 0 findings). Repro models built via `model new` + `apply`. No repo/gold/source read.

## Next

F05-1 (skill) and F05-2 (diagnostics, = F06-2) are confirmed; F05-3 is a DX nit. The convergence of F05-2 and run-06's F06-2 on `MVK_NO_WILDCARD`'s canned hint is the strongest signal of the two runs. None are internal errors — the engine's verdicts were all correct.

## Resolution (2026-06-22)

- **F05-1 — FIXED.** The skill's aggregate worked example now references its error field — *"FEE-line total must not exceed the invoice's FeeCap"* → `--field /Invoice/FeeCap --condition "FieldFilled(FeeCap) And [FeeCap] < Sum(Lines*/Amount Having [Lines/Type] == \"FEE\")"` — verified `valid:true` against the binary. A new skill rule states the error field must appear in the condition (CLAUDE.md "Editing `skill/SKILL.md`" now mandates re-reading the whole skill + running every claim through the tool before any edit).
- **(side effect of fixing F05-1)** a *prior* skill edit's three unverified aggregate claims (`GroupFilled(/Group)` as the model-level form; row-count via `NumberOfFilledGroups(/Group)` without the required `*`; "`*` never on a group ref") were caught and **corrected** in the same pass — each replaced with a binary-verified form.
- **F05-2 / F06-2 (the `MVK_NO_WILDCARD` canned fix hint) and F05-3 (extract sub-model root naming) remain OPEN** — tool-side (diagnostics / CLI) defects for a separate triage; not skill material.
