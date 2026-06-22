# Probe report — task 06 (evolve an existing model's rules), 2026-06-22

**Headline (the proactive win): the advanced rule surface — rules-over-repeats, cross-row uniqueness, flag→rule promotion, rename-rewrite — ran CLEAN on the freshly rebuilt + gated binary.** Five intent-level turns evolving a ruled `order-ruled` fixture, **0 RED FLAGS**, every one of the 13 non-zero exits a structured kernel verdict. The session also surfaced one genuine **[material]** defect in the *operator catalog* (a non-working `validExample`), independently corroborated by the parallel run 05's `MVK_NO_WILDCARD` finding.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` — rebuilt this session with the F1b/F2 fixes; reflection metadata re-captured (no diff); native gate GREEN (168/0) |
| start mode | **fixture** — `cli/src/test/resources/models/order-ruled.dm.json` seeded into `work/` (a maintain/extend session) |
| anti-cheat audit | **CLEAN 5/5** — material boundary respected; model files mutated only by dmtool (no hand-edits → repro trustworthy); no main-loop task leaked |
| digest | **65 calls · 13 nonzero (20%) · 0 RED FLAGS** · author 17 / verify 24 / orient 24 |
| report authorship | narrative = the probe agent's own end-of-session report; verification = the harness audit/digest + the maintainer repros below |

## Findings (classified)

| # | finding | target | class | verified |
|---|---|---|---|---|
| F06-1 | **`RepetitionNotUnique`'s catalog `validExample` (`RepetitionNotUnique(Items/Sku)`) does not validate** — the no-`*` form is rejected `MVK_NO_WILDCARD` ("missing asterisk"), the `*` form `MVK_NO_WILDCARDS_ALLOWED` ("asterisk not allowed"): a contradiction with no working path. `FieldValuesNotUnique(Items*/Sku)` is the clean alternative | catalog (`operators.json`) | **DEFECT (non-working shipped example)** | shim log (8 rejected variants) + agent minimal repro |
| F06-2 | **`MVK_NO_WILDCARD`'s `fix` hint is misattributed** — it reads "CurrentRepetition needs the rule to iterate … put the error field inside Items", naming a *different* operator and a hard-coded `Items` group irrelevant to a `RepetitionNotUnique` rejection | diagnostics | **DEFECT (canned/misleading fix)** | shim log; **corroborated by run 05** (same hint on an aggregate rejection) |
| — | empty-number-reads-as-0 kept intentionally on the per-row `Count` rule (an empty count *is* a violation of "count > 0"); flag→rule promotion (T3) handled correctly (set `required`, then reverted + added the conditional rule atomically); rename (T5) auto-rewrote both referencing rules | — | **expected / correct** | shim log + `where-used` |

## The confirmed defect (F06-1) — the bisect

On a minimal model (non-repeatable anchor `/Repro/Anchor`, repeatable `/Repro/Lines`, field `Sku`):

| error field | condition | result |
|---|---|---|
| `/Repro/Anchor` | `RepetitionNotUnique(Lines/Sku)` *(= the catalog `validExample` shape)* | ❌ `MVK_NO_WILDCARD` |
| `/Repro/Anchor` | `RepetitionNotUnique(Lines*/Sku)` | ❌ `MVK_NO_WILDCARDS_ALLOWED` |
| `/Repro/Anchor` | `RepetitionNotUnique(/Repro/Lines/Sku)` / `(/Repro/Lines*/Sku)` | ❌ `MVK_NO_WILDCARD` / `MVK_NO_WILDCARDS_ALLOWED` |
| `/Repro/Lines/Sku` (in row) | `RepetitionNotUnique(Lines/Sku)` / `(Sku)` | ❌ `MVK_INVALID_ENTITY` / `MVK_REPEATABLE_GROUP_MISSING` |
| `/Repro/Anchor` | **`FieldFilled(Anchor) And FieldValuesNotUnique(Lines*/Sku)`** | ✅ **valid** (the working uniqueness check) |

So the defect is specific to `RepetitionNotUnique`, not to repeatable-group uniqueness in general. The catalog advertises `RepetitionNotUnique` `"supported": true` with that `validExample`, yet no form validates.

**Suggested fix (the agent's, endorsed):** the project already CI-executes every *schema* `examples` block through the tool — **extend that guard to every operator-catalog `validExample`** (run each through `rule check` against a fixture), so a non-validating example cannot ship. Then either correct the `RepetitionNotUnique` example to a working form (with the exact error-field placement it needs) or, if it isn't expressible through a single-condition `rule check`, mark it and point at `FieldValuesNotUnique`. This is the root-cause/graduate lever — it would have caught F06-1 before it shipped.

## Replay log (verbatim, in order)

1. each line item must have a count greater than zero.
2. no two line items may have the same SKU.
3. the backorder quantity should be required. → actually, only require it when express shipping is on.
4. when the order priority is EXPRESS or OVERNIGHT, every line item must have a SKU.
5. rename the line-item SKU field to ProductSku everywhere.

## Self-audit

The anti-cheat audit confirms tool-only mutations — `order-ruled.dm.json` and the `Repro_DM` repro were touched only by dmtool verbs; the hand-authored files were input spec JSONs (never models). No repo/gold/source path was read. The repro is clean.

## Next

F06-1 + F06-2 are confirmed defects (not the agent's mistakes), in the **catalog/diagnostics material**, not the engine. The friction is fully recovered-from in-session via `FieldValuesNotUnique`, so impact is DX, not a blocker.

## Resolution (2026-06-22)

Root-causing F06-1 corrected the diagnosis: **`RepetitionNotUnique`'s `validExample` is NOT broken** — it's kernel-valid and already CI-executed by `CatalogExamplesAreKernelValidTest` (which builds the rule at the *parent* of the repeated group). The probe couldn't author it because `rule check` defaults the rule group to the error field's own parent (the repeated group itself) → rejected; the kernel-valid structure needs the rule at the *parent* with the error field inside, reachable via the existing **`--group`** flag (`rule check --group /Order --field /Order/Items/Sku` → VALID, verified). So F06-1 + F06-2 collapse into one root cause and one fix:

- **The `MVK_NO_WILDCARD` fix hint was misattributed** (F06-2): it's a *shared* structural code (CurrentRepetition, RepetitionNotUnique, aggregates, model-scope `GroupFilled`), but the operator catalog-join stamped CurrentRepetition's "inside Items" fix on every occurrence. Moved to `StructuralKernelFix` with a generic hint (flatten with `*` / `--group` at the parent), consulted before the catalog-join. Reaches `rule check` / `apply` / `batch` / `diagnostics <code>`.
- **Discoverability** (F06-1): `RepetitionNotUnique`'s catalog gotcha now teaches the `--group` authoring; `FieldValuesNotUnique(Items*/Sku)` remains the no-`--group` alternative for plain cross-row uniqueness.

Locked by `DiagnosticEnricherTest` + `DiagnosticsVerbTest`; kernel fact recorded in KERNEL-FINDINGS §9 supplement + the treatment in KERNEL-INTEGRATION-NOTES.

## Re-run (2026-06-22, on the rebuilt binary after the F06 fix) — new finding, fixed

Re-running 06 on the fixed binary (73 calls, 0 RED FLAGS, audit CLEAN) confirmed the F06 hint now guides the agent to `--group` — and that exposed the next layer:

- **F06-3 — FIXED.** `rule check` had `--group` but `rule add`/`apply rule add` did not, so a `RepetitionNotUnique` rule was *checkable but not persistable*. The persist verbs now take the same `group` key (default = field's parent); the catalog gotcha teaches it. Locked by `RuleVerbTest.addPersistsARepetitionNotUniqueRuleViaTheGroupKey` + `ApplyVerbTest`.
- **06-6 — FIXED.** `--suggest-error-field` now notes that `fieldIsCandidate:false` (it lists only iteration-scope refs) is not a verdict for a model-level error field the kernel accepts.
- **06-4 — FIXED (skill).** The skill now names the uniqueness operator (`FieldValuesNotUnique` vs `RepetitionNotUnique --group`).
