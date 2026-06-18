# a12-dmkits — eval & benchmark results log

The dated record of self-sufficiency eval runs. **The method, scoring, task suite, and glossary live in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md)** — this file holds only *measured results*, because results churn every run while the spec should stay stable. Append new entries at the top of the probe log, each stamped with **date + time-of-day** (we run several per day). *The early 2026-06-05 single-probes predate per-run time-stamping — they are in session order, exact clock times unrecorded.*

## Lift summary

| Change measured | Task | Before | After | Model |
|---|---|---|---|---|
| Add the `check` verb | T1 | could not validate at all | authors a kernel-accepted rule (reject→fix→accept loop) | Opus 4.8 |
| Add the skill | T1 | multi-attempt / medium-confidence | first-attempt / high-confidence; polarity correct by construction | Opus 4.8 |
| Genuine transfer (skill) | T2 | — | first-attempt on a structurally-novel shape (no template to copy) | Opus 4.8 |
| Model tier | T1, T2 | Opus passes | **Sonnet matches Opus** — the skill lift is not tier-dependent | Sonnet |
| Close the DateRange/locale gaps (tool→catalog→skill) | T13, T14 | 0/3 (the bracket + per-locale-message traps, masked by a fixture artifact) | **3/3 bare-tool *and* production** | Sonnet |

All entries below are **single-probe (n=1 per cell)** unless noted; the Layer-2 Workflow runner provides **pass@k** across tiers (latest run at the top of the log).

## Probe log

### 2026-06-17 13:06 CEST — targeted regression: session family after the FEATURE-GAPS §3 verb changes (sonnet, k=2, bare-tool)

A **targeted** re-run (§8b) confirming the day's CLI-surface changes didn't regress a cold agent's `apply` session authoring. The changes under test: **G8** (`apply`'s `field add` now takes the rich per-kind spec — the field-add dispatch was refactored to `FieldSpec.parse`), **G11** (`where-used` clean break to `-m` + positional entity), and the **`where-used` envelope-ification** (its output moved from a bare payload to the result envelope, so the session scorer's `data.rules` path is now literally correct rather than LLM-bridged). The session family is the only agentic task set that exercises these (apply op-list authoring + the `where-used` scorer); **fresh** run (material changed), bare-tool (no skill).

| task | axis | sonnet pass@2 | facts (every run) |
|---|---|---|---|
| `mixed/01-apply-add-field-and-rule` | apply: add field → add rule referencing it, one gated session | **2/2** | `committed · fieldExists · ruleOnField` all true |
| `mixed/02-fix-rejected-batch` | apply: atomic rollback + per-op repair (date-diff slip) | **2/2** | `committed · fieldExists · ruleOnField` all true |

**4/4 — no regression.** `ruleOnField:true` on every run confirms the enveloped `where-used`'s `data.rules` is read correctly by the deterministic scorer (the latent mismatch the schema review surfaced is closed end-to-end), and a cold agent still composes the `apply` op-list with the rule referencing the just-added field. **Boundary audit clean** (bare-tool): all 9 agents read only their own `/tmp/rk-sess-ops-*.json` op-lists; zero reads of `docs/`/`src/`/`*Test`/skill/`eval/tasks`/committed models — the model was touched only through the CLI's `-m`. Cost: **9 agents, 261,870 subagent tokens, 131 s**. Scope note: **G9** (`model usage`) and **G10** (`rule read` messages) are reads with no authoring task, so the eval doesn't exercise them — they're covered by the unit tests + the cli-tour demo, not here.

### 2026-06-17 03:03 CEST — OQ1: code-mode (`dmcode`) vs bare-tool CLI, head-to-head (opus, k=2, 7 tasks)

The [DMCODE-PROPOSAL](../docs/DMCODE-PROPOSAL.md) OQ1 probe: is **code-against-the-API** (a clean agent authoring Java via the typed kits, compiler + `RuleValidator` as feedback) **more powerful** than the **bare-tool CLI** for BA authoring? Two arms, **neither given the skill**. CLI arm = self-describing `dmtool` + the model (bare-tool boundary). Code arm = a `/tmp` **`api.txt` proxy** (scoped signatures + javadoc) + `operators.json` + the model, source-launched against the kits classpath (no `src/` — boundary-audited clean, both arms). Tasks chosen from a verb-surface study to hit the CLI's *theoretical* difficulty axes.

**Result — code-mode ties the CLI on correctness on every axis, never beats it, costs ~2.3×, and is worse on refactors.** Deterministic scores (k=2):

| task | axis | CLI | code | note |
|---|---|---|---|---|
| C1 | bulk-5 | 2/2 | 2/2 | tie |
| C2 | conditional (small set) | 2/2 | 1/2 | code-k1 miss is an **eval-design artifact** — the `RK_UNGUARDED` lint doesn't flag the field-vs-field case, so trusting it under-corrected; not a code deficiency |
| C3 | built-in rename | 2/2 | 2/2 | code fragile (manual ref surgery) |
| A | aggregate query / audit | 2/2 | 2/2 | CLI ground via `where-used`×N + read messages; code walked once |
| C | data-driven ×25 | 2/2 | 2/2 | **CLI scripted `jq`→`batch`**; code looped |
| D | state-parameterized edit | 2/2 | 2/2 | CLI `where-used`+`meta`; code walk + `setInternalDescription` |
| E | multi-model rename ×3 | 2/2 | 2/2 | **CLI `field rename` cascades in one verb; code = 4–5-iteration manual `removeRule`→rename→`addRule` surgery** |

Cost: CLI ~42k tok/task avg, code ~97k (~2.3×) — even with `api.txt` (the compile-iterate loop + the 38k api.txt read are inherent).

**Why the thesis didn't hold:** a *coding agent* clears the "verb ceiling" the dmcode pitch assumed by **shell-scripting around it** — `jq`-generating the op-list → `batch` (bulk/data-driven), `where-used`×N + shell (queries), shell loops (multi-model). The CLI's fixed vocabulary isn't the agent's ceiling; the agent's shell is the loop/data-pipe. So code's expressiveness edge doesn't materialize for realistic BA authoring.

**Verdict (OQ1): do not graduate `dmcode` as a general authoring tier — PARK.** The graduate criterion (beat the CLI on composition **and** tie on simple) is not met: code *ties* (doesn't beat) on composition, loses on cost, and is worse on refactors. Re-open only if (a) a real consumer hits a task the CLI+shell genuinely can't express, or (b) the **runtime-eval** capability (OQ2 — `model eval`/`rule test`, which native can't ship but the JVM bundle can) becomes a real need — **untested here** and dmcode's one un-superseded structural advantage.

**Corroborated side-findings:** **OQ7** — `EditSession.renameField` does **not** cascade rule references (C3+E forced fragile manual surgery); the kit needs the refactor cascade promoted from `cli/` regardless of dmcode. **OQ5** — the scoped `api.txt` proxy is ~38k tokens, too big to always-load; needs trimming to the ~40 entry types before any ship.

**Caveats:** a probe (k=2, 7 tasks) — directional, not definitive; the `api.txt` was an unrefined proxy; the task space isn't exhausted (very complex data-flow / custom analysis beyond shell+`jq`+CLI wasn't reached). Harness: clean Agent-tool subagents driven from the main loop (not the bare-tool Workflow runner); boundaries instructed + transcript-audited, not workflow-enforced.

### 2026-06-17 00:46 CEST — self-correction family wired + a catalog-quality fix it surfaced (sonnet, k=3, T19)

Wired the **`self-correction`** family into `benchmark.workflow.js` (it had no runner stage, so the 28-task baseline above excluded `rulekit/19`). Self-correction is authoring with a recovery trap — the recovery loop happens *inside* the produce agent (it has the read-only `check` verb), so the pass criterion is identical to authoring (final rule kernel-valid AND gold-equivalent); it rides the authoring produce → `cli batch` recheck → judge-by-execution path verbatim. EVAL-SPEC §3.

| task | mode | k=3 |
|---|---|---|
| `19-self-correct-date-rule` (`wf_da1f8f67`) | bare-tool | **3/3** |

All three agents **recovered**, but via `YearFromDate(DeliveryDate) > 2024` — *routing around* the date-literal comparison rather than using the German `dd.MM.yyyy` literal (gold). Judge confirmed equivalence by kernel execution (both first fire on 01.01.2025). The recovery-via-detour is itself the signal the task is for: the diagnostic's joined `fix` did **not** point at the date-literal remedy.

**Catalog-quality fix (build-measure-learn closure).** The `MVK_INVALID_TYPE_FOR_COMPARISON` diagnostic is enriched by a catalog-join (`DiagnosticEnricher`, **by MVK code only — operand-blind**), so the static `invalidExample.fix` for the comparison operators (whose example is string-vs-string) was reused on the *date-vs-ISO-literal* trigger, where "compare strings with == / != instead" is wrong. Broadened the fix text (all four ordering ops in `operators.json`) to name **both** remedies — strings → `==`/`!=`; a DATE → write the literal in German `dd.MM.yyyy` (e.g. `"31.12.2024"`), not ISO. Verified the live diagnostic now carries it for both triggers. (The skill already taught this; the gap was the **bare-tool** self-describing surface — CLAUDE.md's self-explaining-CLI requirement.) Also added a **fail-fast** guard: authoring/self-correction tasks missing `gold.field`/`gold.condition` now abort loudly.

**Recovery-route confirmation (k=6, post-fix, `wf_c8433191`): T19 6/6 — and the route shifted to the canonical remedy.** All six produce agents hit the trap (tried the ISO literal → `MVK_INVALID_TYPE_FOR_COMPARISON`) and recovered to the **German `dd.MM.yyyy` literal** `[DeliveryDate] > "31.12.2024"` (one reproducing the gold verbatim with the redundant `FieldFilled` guard) — the exact remedy the broadened `fix` now names; 3 of 6 weighed `YearFromDate` in-flight but chose the literal. Contrast the **k=3 pre-fix** run (`wf_da1f8f67`): all recovered via `YearFromDate > 2024`, routing *around* the literal. **Caveat:** observational (different runs + k, small n), not a fixed-k A/B — strong corroboration the fix steers recovery to the canonical route, not proof of causation. The 6/6 trap-hits confirm recovery genuinely happened (not first-try success).

### 2026-06-17 00:34 CEST — full pass@3 baseline on the post-rename surface (sonnet, k=3, 28 tasks, bare-tool)

First full N6 baseline since the **`doc`→`model`** target rename, the **`--include-dir`→`-w/--workspace`** flag collapse, and the ~26 CLI commits of 2026-06-13/14 (new verbs `workspace list/graph`, `model info/new`, `meta`, `rule deps`, `diagnostics`; the `RkCode` registry; coded refusals). Bare-tool (no skill). `wf_bee704d3`, 166 agents, **4.61M tokens**, ~28 min — plus a targeted task-17 re-run (`wf_88a4f3de`, 8 agents, 0.22M).

**pass@3, sonnet, bare-tool: 23/28 clean** (76/84 runs) — *after* correcting one harness artifact (below).

| result | tasks |
|---|---|
| **3/3** | 02-effective-fee-not-below-base · 03-product-code-pattern · 05-delivery-not-before-order · 06-autorenew-paid-tier · 07-read-two-rules · 08-read-custom-condition · 09-order-before-2020 · 11-fullname-equals-first-last · 12-tax-id-validity · 13-coverage-window-daterange · 14-effective-fee-double-base · 15-storefront-name-multifile · 16-modify-effective-fee-sum-addons · **17-tighten-delivery-rule** · 01-add-field-and-group · 02-safe-delete-referenced-field · 03-rename-field · 04-describe-field · 05-annotate-rule · 06-model-comment · 07-multiselect-template · 08-attachment-template · 01-apply-add-field-and-rule · 02-fix-rejected-batch |
| **2/3** | 04-quantity-vs-rush-sum · 18-read-value-operator-rules |
| **1/3** | 01-pro-addon-fee-required |
| **0/3** | 10-express-needs-full-address |

Headline: **the post-rename self-describing CLI is broadly self-sufficient for a cold Sonnet agent.** The two breaking renames cost nothing — the multi-file task (15, which exercises `-w/--workspace`) is 3/3, and all ten modelkit/mixed families (structure · delete-safety · metadata×3 · template×2 · refactor · session×2, deterministically scored) are 3/3. Every miss was triaged against the judge-by-execution rationales:

- **17-tighten-delivery-rule: 0/3 → 3/3 — harness artifact (operator bundling), FIXED.** The full-run bundle **dropped the `rule` routing key** when trimming task objects, so the produce prompt *and* the `rule modify … --dry-run` recheck both resolved `${task.rule}` to `undefined` → every candidate rejected. The candidate matched the gold and is kernel-valid; the targeted re-run with the full task object scored **3/3** (judge: identical `rule.fired` across edge docs, differing only by bare-vs-absolute path notation). Fixes so it can't recur: the `run-dmtool-eval` skill now lists `rule` as a required routing key (and recommends passing **whole** task objects), and `benchmark.workflow.js` **fail-fasts** if a modify-rule/modify-computation task is missing its `rule`/`computation` key instead of silently scoring 0/k. *Lesson reaffirmed: a 0/k cell whose candidate matches the gold is an artifact — reproduce the recheck before recording.*
- **10-express-needs-full-address: 0/3 — GENUINE bare-tool difficulty.** Agents anchored the rule's error field on `/Order/ShippingAddress/Street` and hand-enumerated sub-fields (`FieldNotFilled(ShippingAddress/Street) Or …`); that combination is `MVK_INVALID_ENTITY` (verified — only the gold's `/Order/ExpressShipping` anchor with the group-level `NotAllFieldsFilled(ShippingAddress)` validates). The agents' own `kernelAccepted=true` self-report didn't match the field they reported — the authoritative recheck correctly caught it. Worklist: the group-level `NotAllFieldsFilled` operator + error-field-anchor judgment (skill/catalog).
- **01-pro-addon 1/3 · 02-effective-fee 2/3 · 04-quantity 2/3 — real authoring difficulty (skill-lift targets), judge-verified by kernel execution.** Pro-addon: candidates used a cross-row aggregate (`NumberOfFilledGroups > NumberOfFilledFields`) instead of the gold's per-row check → different error count/attachment. Effective-fee: a `FieldFilled(BaseFee)` guard over a NUMBER comparison changes the fire set (empty number = 0 — the empty-value trap). Quantity: `PatternMatched "RUSH.*"` vs exact `== "RUSH"` includes an extra `RUSH-01` row.
- **18-read-value-operator-rules 2/3 — one comprehension miss.** Two readings correct; one misread `FirstFilledValue` (claimed earlier rows suppress the rule via formal errors, vs. it simply skips empty rows and takes the first non-empty Count).

**Scope note:** `rulekit/19-self-correct-date-rule` (family `self-correction`) was **excluded** — the runner has no stage for that family, so including it would have silently dropped it. 28 of 29 tasks ran; the `self-correction` family has no runner support (a follow-up).

### 2026-06-12 05:14 CEST — #45/B landed: the modelkit/mixed agentic path (structure · session · delete-safety), sonnet k=1 smoke-verified

Wired the modelkit/mixed families into `benchmark.workflow.js` (the Layer-2 agentic runner), all **deterministically scored — no LLM judge** (the "structural diff" choice): a clean agent produces the edit from the bare CLI; a score agent applies it via `-o` to a fresh `/tmp` file and reports raw facts; the workflow decides pass.

| family | task | produce → score | k=1 |
|---|---|---|---|
| **structure** | `modelkit/01` | `field add` → `applied && expectField exists with gold.kind` (kind via `model describe`) | **1/1** |
| **session** | `mixed/01` | `apply` op-list → `committed && expect.field exists && a rule references it` (`where-used`) | **1/1** |
| **delete-safety** | `modelkit/02` | `field remove [--cascade]` → `applied && every expectRemoved gone` | **1/1** |

Two scoring bugs caught + fixed by the k=1 runs (the same "read the candidate before recording" lesson): structure first read `field read`'s `data.type` (`NumberType`) instead of `model describe`'s `kind` (`NUMBER`); session first matched the gold's exact rule name (`/Order/DISCOUNT_NEGATIVE`) but the agent legitimately named its rule `DISCOUNT_NOT_NEGATIVE` (the code is the author's choice) → switched to a name-agnostic `where-used` check. Both then green.

These are **structural** scores by design (D7-style deterministic diff): they confirm the right elements exist / are gone and the model re-binds; they do **not** judge the added rule's polarity (a future rulekit-style verify-by-execution judge could add that). `EvalGoldOracleTest` already validates all these golds (Layer 1). Only **`refactor`/rename** stays deferred (Phase 4). EVAL-SPEC §8d.

### 2026-06-12 02:58 CEST — T13/T14 fix confirmed + the fixture-corruption artifact closed (sonnet, k=3, targeted)

Targeted re-run after fixing the two T13 traps the 00:27 baseline surfaced, **tool→catalog→skill** (API-POC finding 21): (1) declared `locales` now ride `config read` + the `model describe` card so an agent provisions a message per locale; (2) `AUTHORING` bare-ref gotchas on the date-range functions in the catalog; (3) the skill's bracket rule generalized to function args + the `MVK_UNEXPECTED_TOKEN` hint corrected to name *too-many* brackets.

| run | mode | T13 | T14 |
|---|---|---|---|
| `wf_d0e55df7` | bare-tool | **3/3** | **3/3** |
| `wf_69c925f6` | production (`skillPath`) | **3/3** | **3/3** |
| `wf_86aa9147` | bare-tool, k=1 (new-prep confirm) | — | **1/1** |

**Lift: T13 0/3 → 3/3, T14 0/3 → 3/3** (bare-tool *and* production). Candidates are now bare `DateRange(OrderDate, DeliveryDate)` and computations carrying a message for every declared locale.

**The 00:27 "T13/T14 0/3" was a fixture-corruption artifact, not a gap — root-caused here.** Both tasks' candidates were correct, yet the recheck crashed with `Cannot have two child elements with the same name [CoverageWindowComp]`/`[EffectiveFeeComp]`. Cause: produce agents author against the committed fixture, and the authoring verbs **write in place by default** — so an agent's `computation add <model> <spec>` (no `--dry-run`) injected a computation, leaving a duplicate the recheck then tripped over. Worse, a *prior* run's mutation of `order.dm.json` (a stray `CoverageWindowComp` + a whole-file reformat) had **slipped into a commit** (de7e593, a "docs" commit), making T13 permanently un-recheckable — so a real, *fixed* capability scored 0/k. The earlier `7e7e768` fix only stopped the *recheck* from writing; the *produce* agents still did.

**Harness fix (so it cannot recur):** restored both fixtures (`order.dm.json` to de7e593~1), then made the runner sandbox-safe — [`eval/prep-sandbox.sh`](prep-sandbox.sh) mirrors the models tree into `/tmp/rk-eval-sandbox` (the **operator runs the script**, because a sandboxed subagent can't be granted permission for a mutating `rm`/`cp` — an agent *prompted* to do it was non-deterministically denied), the workflow's Prep phase only **verifies** (read-only) and halts loudly if absent, and the author prompts now steer to read-only confirmation (`check` / `--dry-run`) like the modify prompts. The `wf_86aa9147` k=1 run confirms the script-built sandbox + verify-prep path end to end. EVAL-SPEC §8c, *Committed fixtures are never mutated*.

**Boundary audit (production run `wf_69c925f6`): producers CLEAN** — read only `skill/SKILL.md`, the sandbox model, and their own `/tmp` scratch; no `docs/`/`src/`/`*Test`/other-`*.md`/`eval/tasks` reads. (The `cp` from `adapter/`/`build/` paths the audit shows was the *old* prep agent improvising after its `rm` was denied — eliminated by the script.)

Lesson, recorded: **a 0/k cell whose candidates look correct is an artifact, not a gap — read the produced candidates before recording.** This was its sharpest case.

### 2026-06-12 00:27 CEST — first baseline on the dmtool target-first surface (sonnet, k=3, full 18, bare-tool)

The first full N6 baseline after the `rulekit`→`dmtool` **target-first cutover** (`-m <model> <target> <op>`, the result envelope, hidden flat aliases). Bare-tool mode (no skill — measures the self-describing CLI alone). `wf_28f73d65`, 105 agents, **2.83M tokens**, ~38 min.

**pass@3, sonnet, bare-tool: 16/18 clean** (45/54 runs).

| result | tasks |
|---|---|
| **3/3** | T1, T3, T5, T6, T7, T8, T9, T10, T11, T12, T15, T16, T17, T18 |
| **2/3** | T4 |
| **1/3** | T2 |
| **0/3** | T13, T14 |

Headline: **the target-first self-describing CLI is broadly self-sufficient for a cold Sonnet agent** — every family (authoring, computation-authoring, modify-rule, modify-computation, comprehension) is reachable from `manifest`/`--help` alone, and the verb rename/re-parenting cost nothing. The four misses split cleanly into one harness bug (mine, fixed) and three real bare-tool difficulties:

- **T14 0/3 — harness artifact, FIXED.** Zero judge entries → candidates marked kernel-invalid *at recheck*, never judged. Cause: the eval-runner migration pointed the comp-authoring recheck at `computation add` but kept the old prompt (old flat `add` printed the model / `{persisted:false}`); the new verb writes in place + emits the envelope, so the recheck misread *and* (no `--dry-run`) wrote into the committed `order`/`subscription` fixtures. Fixed (`7e7e768`): `computation add --dry-run` reading `outcome:"preview"`. Targeted re-run (`wf_baddb5f6`, sonnet k=3): **T14 0/3 → 3/3** (the only difference judged is bare-vs-absolute path notation). Fixtures restored.
- **T13 0/3 — GENUINE bare-tool gap** (recheck now sound, T14 proves it; T13 re-run still 0/3, now *judged* kernel-invalid). The DateRange computation-authoring trap: agents bracket the constructor's operands (`DateRange([/Order/OrderDate], …)` → `MVK_UNEXPECTED_TOKEN`; it takes *bare* refs) and/or omit a model-locale message (`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` — a computation needs a message per declared locale). Skill/catalog candidates: the DateRange bare-operand gotcha + the all-locales-message rule.
- **T2 1/3 + T4 2/3 — real bare-tool difficulty (the skill-lift targets), judge verified by kernel execution.** T2: 2/3 candidates added a `FieldFilled(EffectiveFee)` guard that *changes the fire set* (empty number = 0, so the gold fires on empty `< BaseFee` but the guarded candidate doesn't) — the empty-number trap the skill's "empty values in a comparison" section closes. T4 (aggregate + `Having`): one candidate kernel-invalid.

Method note: this run also caught (and the log records) that a CLI redesign silently breaks an eval recheck whose prompt describes the old verb behavior — a reminder to re-verify the recheck commands, not just the agent-facing hints, after a grammar change.

### 2026-06-09 01:25 CEST — judge verify-by-execution wired + first production-mode probe (sonnet, k=1, T1+T11)

Two firsts in one cheap probe (`wf_4ed74368`, 5 agents, 0.12M tokens): the rule judges now **verify equivalence by execution** (run `eval-doc` on discriminating documents for gold vs candidate, compare `rule.fired`), and this is the **first production-mode run** (the N1 skill injected via `args.skill` — confirmed present in the produce transcripts).

**Result: sonnet 2/2** (T1 1/1, T11 1/1). The judge rationales show the execution actually happened, not reasoning:
- **T1** — "the kernel fires identically on every test document (PRO+empty-fee fires; BASIC / filled-fee / absent-Tier do not)" — the judge built those docs and ran the kernel.
- **T11** — the candidate's redundant `AllFieldsFilled(…, FullName)` guard ruled `equivalent:true` "verified by kernel execution across filled/equal, filled/differing, empty-FullName, empty-FirstName — all matched." **This is the exact shape that false-positived in the 22:50/23:19 bare-tool runs** (judge reasoned `equivalent:false`); verify-by-execution eliminates it at the source, not by a per-semantic prompt patch.

Caveats (it's a probe): **k=1** (one sample/task — noisy), and the injected skill was a **faithfully-condensed** rendering of `skill/SKILL.md` (all judgment preserved — polarity, the iteration `GroupFilled` guard, empty-values, dates — but not byte-identical). **Resolved for the next run (2026-06-09):** `args.skillPath` now lets the producer read the verbatim `skill/SKILL.md` itself (no transcription, no drift) — so a definitive production run is `args: { skillPath: "skill/SKILL.md", … }` followed by the mandatory boundary audit (EVAL-SPEC §2a). Also: in this probe the skill reached only the authoring producers; production mode now injects it into **all** producer families. Computation judges still use the type-aware empty-operand *reasoning* — first-class comp verify-by-execution awaits a `compute-doc --spec` candidate path. Also new this session: **T18** (comprehension over the awkward-as-authoring operators — operand-list `Max`, `RoundDownValue`, `FirstFilledValue` — on the `order-aggregates` fixture).

### 2026-06-08 23:19 CEST — the "`--field` file-path slip" is a HARNESS artifact, not a CLI misuse (sonnet, k=3, T11+T15)

Chased the recurring sonnet authoring miss (blamed across earlier runs on a `--field` file-path slip) by adding a tool-side nudge, then **reading the produce transcripts**. Finding: the producers **author correctly at the CLI** — every `cli check` used the right entity path (`--field=/Person/FullName`, `FieldNotFilled(/Storefront/StoreName)`), all `valid:true`. The slip lives in the benchmark's **answer JSON**: the prompt asked for `{"field":"<absolute path>"}`, and to a model the model FILE path *is* an absolute path — so it reported `/Users/.../person.dm.json` there. Recheck then trusted `r.out.field`, re-ran with the file path, and failed. (That batch-recheck re-run is the *only* place the new nudge fired — never in produce.)

Two changes came out of it:
- **`check` nudge (tool):** on a `--field` that doesn't resolve, the error now suggests the field the `--condition` references — `Did you mean --field /Storefront/StoreName?`. Sound defensive hygiene + unit-tested (`CheckVerbTest.filePathFieldSuggestsTheFieldTheConditionReferences`), but it does **not** move the eval — the agents never actually misuse `--field`.
- **Answer-schema fix (harness, the real lever):** the producer prompts now state that `field`/`computedField` is "the field's path INSIDE the model, NOT the model FILE path."

| | sonnet/T11 | sonnet/T15 | candidates kernel-valid |
|---|---|---|---|
| nudge only (artifact still present) | 0/3 | 1/3 | 4/6 |
| **+ answer-schema fix** | **2/3** | **3/3** | **6/6** |

Fresh runs `wf_f243abb2` (nudge-only) + `wf_d1e09030` (+fix), ~0.5M tokens total. After the fix **every** candidate is kernel-valid. The single remaining T11 miss turned out to be a **judge FALSE POSITIVE**, not a real miss: the candidate adds `AllFieldsFilled(FirstName, LastName, **FullName**)` — a *redundant* presence guard, since an empty STRING operand makes `[FullName] != …` not-evaluated (the gold's `AllFieldsFilled(FirstName, LastName)` already never fires on empty FullName). The other two T11 judges ruled it equivalent (one verified by running the kernel on sample docs). Root cause: the **authoring judge prompt lacked the empty-operand kernel fact** the computation/modify judges already carried — fixed by adding the type-aware empty-operand block to it (so a redundant presence guard over a string/date/enum/boolean comparison reads as equivalent, while a guard over a NUMBER comparison or a date-FUNCTION-on-absent-date stays a real difference). A T11-only confirm re-run after the judge fix (`wf_93dfdc43`, sonnet, k=3) went **3/3** — no FP recurrence; caveat: that run's three candidates were byte-identical to the gold, so it confirms no-regression more than it re-stresses the *guarded* shape (the fix's correctness rests on the empty-operand semantic, which the computation judge already encodes and which the earlier T11/2 judge verified by running the kernel on sample docs). This run also **corrects** the 22:16/22:50 entries' attribution of the sonnet/T15 misses to a "`--field` file-path slip" — it was the answer-schema artifact all along. Production mode (skill injection) is now wired (opt-in, EVAL-SPEC §2a); both runs here were bare-tool.

### 2026-06-08 22:50 CEST — diff-retirement spot-check: no dent (T7/T13/T15, opus + sonnet, k=2)

Confirming the retired `diff` verb (manifest 14 → 13) didn't dent self-sufficiency. Fresh run `wf_46603311-672` (**24 agents, 0.56M tokens, 4m45s**) over a comprehension (T7), an author-computation (T13), and a multi-file authoring (T15) task — the surfaces nearest the verb-list change. **Results identical to the pre-retirement 22:16 baseline: opus 6/6, sonnet 4/6** — T7 2/2 and T13 2/2 on **both** tiers, T15 opus 2/2 / sonnet 0/2. The two sonnet/T15 misses were read at the time as a `--field` file-path slip; the 23:19 investigation showed it was actually a **harness answer-schema artifact** (the producer reported the model FILE path in the answer JSON's `field`), since fixed. Removing `diff` from the self-describing manifest left cold-agent orientation and the read/computation/multi-file surfaces unchanged.

### 2026-06-08 22:30 CEST — T16 recheck switched to `modify --dry-run`; then `diff` retired (targeted, opus + sonnet, k=2)

The 22:16 run validated the `modify-computation` family with `diff --computation` — so the `modify` verb itself wasn't eval-exercised. The recheck + prompt now use `cli modify --computation … --spec --dry-run` (read-only — writes nothing, but drives the verb under test). Fresh T16-only run `wf_2d440657-493` (**9 agents, 0.22M tokens, 2m09s**): **opus 2/2, sonnet 2/2** — unchanged from the `diff` gate, confirming `modify --dry-run` is equivalent (same shared core) and the `modify` verb is now under eval coverage. Following this, the read-only **`diff` verb was retired entirely** — it was redundant with `modify --dry-run` (identical code path + output); `modify` is now the sole re-express verb (default writes, `--dry-run` previews). Verb surface 14 → 13.

### 2026-06-08 22:16 CEST — post-rename confirmation (ingest→read, dump→export) — no regression (opus + sonnet, k=2, 6-task subset)

Targeted run after the verb/API rename (`ingest`→`read`, `dump`→`export`, `export --out-dir`) and the `diff`(preview)/`modify`(write) split. The rename's only self-sufficiency risk is **discovery**: a cold agent now sees `read`/`export` in `manifest`/`--help`/`schema` instead of `ingest`/`dump`. A representative subset spanning every family was run **bare-tool** (no skill). Fresh run `wf_50b7b5d6-632` (**46 agents, 1.14M tokens, 9m23s**).

| Task | opus | sonnet | family — rename relevance |
|---|---|---|---|
| T1 | 1/2 | 1/2 | authoring — iteration row-guard idiom (unchanged verbs) |
| T3 | 2/2 | **0/2** | authoring — pattern (unchanged verbs) |
| T7 | 2/2 | 2/2 | **comprehension — read-back via `explain`** |
| T13 | 2/2 | 2/2 | **authoring-computation — `schema add` + `DateRange`** |
| T15 | 2/2 | **0/2** | authoring (multi-file) — `-w/--workspace` |
| T16 | 2/2 | 2/2 | **modify-computation — discovery via `read --all`/`explain --computation` + `diff --computation`** |

**Totals: opus 11/12, sonnet 7/12. No regression from the rename.** The rename-sensitive surface is **2/2 on both tiers**: T7 (read foreign rules back via `explain`), T13 (`schema add` + the computation write path), and T16 (the agent discovers the *existing* computation through the renamed `read --all`/`explain --computation`, re-expresses it, and `diff --computation` validates). The self-describing CLI absorbed the new verb names — agents found `read`/`export` from the manifest with no help. **opus T15 2/2** further confirms the multi-file `-w/--workspace` path works after the rename.

Every miss is pre-existing and **naming-independent**:
- **sonnet T3 0/2 and T15 0/2 — both kernel-*invalid*: the documented `--field` file-path slip** (GAP 1, persists). The condition is right; sonnet puts the model *file* path in the `--field` slot. opus got both 2/2 (incl. the multi-file T15). Neither task touches a renamed verb in its authoring path (they use `describe`/`operators`/`check` + `-w/--workspace`). A sonnet-tier self-correction limit, not the rename.
- **T1 1/2 both tiers — the `GroupFilled` vs `FieldFilled(sibling)` row-guard idiom** (opus's miss is kernel-valid-but-non-equivalent on the blank-row case; sonnet's is kernel-invalid). The known iteration wobble; flaky at k=2.

**Bonus: the T13 judge split from the 18:07 run is settled.** That run had sonnet/T13 1/2 because two judges disagreed on whether an `AllFieldsFilled` guard is equivalent to the unguarded gold. This run is **sonnet/T13 2/2**, both judges ruling the guard redundant and citing the kernel empty-operand rule — i.e. the empty-operand semantic fed into the judge prompt (+ the `DateRangeComputationLawsTest` finding) resolved the ambiguity.

### 2026-06-08 18:07 CEST — first computation-authoring run (T13–T14) — the Layer-2 runner extension works (opus + sonnet, k=2)

First run of the new **`authoring-computation`** family — the Layer-2 runner gained a computation path (author a computation-spec → authoritative `cli add` recheck → computation-equivalence judge). Fresh run `wf_791e1c12-b15` (**17 agents, 0.48M tokens, 4m02s**). Two tasks: **T13** the `DateRange` constructor (`CoverageWindow = DateRange(OrderDate, DeliveryDate)`), **T14** a numeric computation (`EffectiveFee = [BaseFee] * 2`).

| Task | opus | sonnet | exercises |
|---|---|---|---|
| T13 | 2/2 | 1/2 | computation authoring — the `DateRange` constructor |
| T14 | 2/2 | 2/2 | computation authoring — numeric value expression |

**The extension works end-to-end** (the deliverable): clean agents discovered the computation-spec shape from the self-describing CLI (`schema add`), authored a computation, the `cli add` recheck validated it against the kernel, and the judge scored equivalence. **opus 4/4, sonnet 3/4** — the computation write surface (incl. the `DateRange` constructor) is authorable by a cold agent from the CLI alone.

**The one miss is an eval-design observation, not a model/tool gap.** sonnet/T13 split 1/2 because one run added `commonPrecondition FieldFilled(OrderDate) And FieldFilled(DeliveryDate)` and the **two judges contradicted each other** on whether that guard is equivalent to the unguarded gold: judge A said non-equivalent (a non-repeatable computed field "is always calculated even with empty inputs", so the guard changes behavior on missing dates), judge B said equivalent (a `DateRange` over an empty bound "computes nothing", so the guard is redundant). They are *guessing* the empty-operand-in-computation semantic — which neither the gold nor the judge prompt pins. **Follow-up:** verify the kernel behavior (does an unguarded `DateRange` computation with an empty date operand compute a value or nothing?) and either pin the gold with the guard or feed the semantic to the judge — the same gold-ambiguity class as T5/T11. (The runner change itself is sound; only the T13 equivalence bar is fuzzy.)

**Still uncovered:** multi-file (`-w/--workspace`) authoring — needs multi-file model staging in the runner (the next Layer-2 extension).

### 2026-06-08 17:46 CEST — targeted re-run confirming the two gap fixes (T3, T11; opus + sonnet, k=2)

Fresh targeted run `wf_de96c275-459` (**14 agents, 0.37M tokens, 3m07s**) after committing the two fixes from the 17:25 sweep: the `RK_UNGUARDED_STRING_CONCAT` lint (GAP 2) and the `ModelPaths` file-path guard (GAP 1). Re-ran only the affected cells (§8b): **T3** (the GAP-1 `--field` case) and **T11** (the GAP-2 concat case).

| Task | opus before → after | sonnet before → after |
|---|---|---|
| T3 (pattern / `--field`) | 2/2 → **2/2** | 0/2 → **0/2** |
| T11 (string concat) | **0/2 → 2/2** | **0/2 → 1/2** |

**GAP 2 (concat guard) — fixed on both tiers.** opus flipped **0/2 → 2/2**; sonnet now adds the presence guard in *every* T11 run (all carry `AllFieldsFilled`/`FieldFilled` on the operands). The lint steers the author to guard the concatenation — exactly the build-measure-learn close (eval found the trap → KERNEL-FINDINGS §2 recorded it → the `RK_UNGUARDED_STRING_CONCAT` lint now pre-warns it on `add`/`check`).

**GAP 1 (`--field` file-path) — the tooling fix is correct but does not rescue sonnet.** sonnet/T3 stayed **0/2**: the condition was right both times, but sonnet **still reported the model file path in the `field` slot** despite the new laser-specific error (`'…order.dm.json' looks like a FILE path, but --field must be a path INSIDE the model…`). The one remaining sonnet/T11 failure is the *same* slip (file-path field + a correctly-guarded condition). So the failure is upstream of the tool — sonnet commits the file path as its final answer rather than acting on the boundary error. It is **tier-correlated**: opus slipped on `--field` only **once** all day (the full-sweep T10/1) and not on T3 (byte-identical to gold both runs), whereas sonnet slips persistently (T3 ×2, one T11 run). The guard is strictly better UX (kept), but a tool-boundary message can't force a weaker agent's self-correction. The durable mitigation is the **production-mode skill** teaching the field-vs-file distinction — which bare-tool mode (the only wired one) can't measure. Net: **mostly a sonnet-tier self-correction limit, not a tooling gap.**

**Outcome:** GAP 2 closed on both tiers; GAP 1 mitigated as far as the tool boundary allows (clear error), the residual being model-tier self-correction (sonnet-heavy). After the fixes, opus's only remaining miss today is the single T10/1 `--field` slip; sonnet's persistent miss is the same slip.

### 2026-06-08 17:25 CEST — full pass@k after the computation/CLI/operator build-out — no regression; two real gaps (opus + sonnet, k=2)

First full sweep after this session's large surface growth: computations first-class read+write (`ingestModel`/`where-used`/`explain --computation`/`ingest --all`, `add` a computation, `diff --computation`), CLI-over-includes (`-w/--workspace` on every verb, F-RES), `Valid/Invalid(field,"Type")`, and the `DateRange` constructor (catalog now zero PLANNED stubs). Material changed → **fresh** full run `wf_478a0cb7-3c0` (**80 agents, 2.07M subagent tokens, 11m36s**), now **T1–T11** (T9–T11 = the date-extraction / group-scope-quantifier / string-concat authoring tasks). Bare-tool mode (no skill). Question: did the enlarged CLI/catalog regress self-sufficiency, and where are the gaps?

| Task | opus | sonnet | family |
|---|---|---|---|
| T1 | 2/2 | 2/2 | authoring — iteration guard + cross-group enum |
| T2 | 2/2 | 2/2 | authoring — field-vs-field |
| T3 | 2/2 | **0/2** | authoring — pattern |
| T4 | 2/2 | 1/2 | authoring — aggregate + Having |
| T5 | 2/2 | 1/2 | authoring — date |
| T6 | 2/2 | 2/2 | authoring — value-list + boolean |
| T7 | 2/2 | 2/2 | comprehension — date + pattern read-back |
| T8 | 2/2 | 2/2 | comprehension — custom condition |
| T9 | 2/2 | 1/2 | authoring — date extraction (`YearFromDate`) |
| T10 | 1/2 | 1/2 | authoring — group-scope quantifier (`NotAllFieldsFilled(group)`) |
| T11 | **0/2** | **0/2** | authoring — string concatenation |

**Totals: opus 19/22, sonnet 14/22.** **No regression** — on the T1–T8 overlap with 2026-06-06, opus *improved* to **16/16** (was 13/16) and sonnet held at **12/16** (was 11/16); comprehension T7/T8 are 2/2 on both tiers. The enlarged CLI/catalog (new verbs + ~2 new operator families this session) did not degrade the existing families. Two gaps, one recurring and one new:

- **GAP 1 (recurring, #1 cause of sonnet's misses) — the `--field` file-path confusion persists.** Across **T3 (sonnet 0/2), T11, T10/1, and the sonnet T4/T5/T9 misses**, the produced *condition was correct* but the agent put the **model file path** (`/Users/…/models/order.dm.json`) into the `field` slot instead of a model entity path (`/Order/ProductCode`). This is the exact gap the 2026-06-06 run surfaced; the `ModelPaths` pre-flight guard (added since) reduced but did **not eliminate** it — sonnet still lands a file path there and doesn't recover within the run. It's a pure tool-boundary confusion, not a rule-language gap. **Actionable (tooling):** make it impossible to ignore — reject a `--field`/`--group`/`--rule` value that **is an existing file** or **equals the positional `<model.json>` arg** with a laser-specific message ("you passed the model FILE; --field is a path INSIDE it, e.g. /Order/ProductCode — see `describe`"), upstream of the generic resolution error. opus did not hit this at all.
- **GAP 2 (new, genuine semantic — both tiers on T11) — string concatenation does NOT inherit "empty ⇒ not evaluated".** opus authored `[FullName] != [FirstName] + " " + [LastName]` **without** the gold's `AllFieldsFilled(FirstName, LastName)` guard → kernel-valid but **not equivalent**: empty number/date operands make a comparison "not evaluated" (KERNEL-FINDINGS §2), but an empty *string* still concatenates to `" "`, so the comparison **is** evaluated and mis-fires on an all-empty person. (sonnet's T11 misses were the file-path confusion on top.) One agent even guarded the wrong field — `FieldFilled(FullName)` instead of the concatenated *operands*. **Actionable (skill/catalog):** a `Concatenation`/`+` gotcha — *a string field that participates in a concatenation needs an explicit presence guard on the operands; it does not get the empty-as-not-evaluated free pass numbers/dates do.* This is the most valuable new finding — the string-concat operator's specific trap.

**Not gaps:** **T10** — agents reached for the field-list enumeration `NotAllFieldsFilled(ShippingAddress/Street, ShippingAddress/City)` rather than the new group-scope `NotAllFieldsFilled(ShippingAddress)`; the judge correctly ruled them equivalent (same field set), so the new group-scope operator is discoverable and the enumeration is a valid alternative. **T9** (`YearFromDate(OrderDate) < 2020`) — opus byte-identical to gold both runs; the date-extraction family authors cleanly. **T5** — agents again prefer the direct `[DeliveryDate] < [OrderDate]` over the gold's `DifferenceInDays`; judge rules equivalent (the gold's guard is redundant for dates).

**On this session's new surface:** T1–T11 exercise the *older* + the three 2026-06-06 families; this run therefore measures **regression + the existing suite**, not the brand-new verbs (computation authoring/diff over the CLI, `-w/--workspace`, `Valid(field,"Type")`, `DateRange`) — there are no tasks for those. Measuring *their* authorability needs **new tasks** (a computation-authoring task; a multi-file/`-w/--workspace` task; a `Valid(field,"Type")` task; a `DateRange` computed-field task) — the recommended next suite addition.

### 2026-06-06 22:54 CEST — full pass@k after the F10 ingest build-out (+11 catalog operators) — no regression (opus + sonnet, k=2)

First full sweep (now **T1–T8**, T8 added) after this session's large converter/catalog growth: the comparison-RHS family, `FirstFilledValue` (number/date/string), group-scope `Not*`/`MoreThanOne` quantifiers, string coercions `RangeAsString`/`FieldValueAsString`, date shifts + date→number extraction, and root-group counting — **~11 new operators in the catalog** (Corpus-1 ingest 65.1% → 87.0% this session). Material changed → fresh run `wf_deff5a53-caa` (**63 agents, 1.32M subagent tokens, 8m19s**). Question: did the enlarged `cli operators` catalog regress self-sufficiency on the existing families?

| Task | opus | sonnet | family |
|---|---|---|---|
| T1 | 1/2 | 0/2 | authoring — iteration guard + cross-group enum |
| T2 | 2/2 | 2/2 | authoring — field-vs-field |
| T3 | 2/2 | 1/2 | authoring — pattern |
| T4 | 2/2 | 2/2 | authoring — aggregate + Having |
| T5 | 2/2 | 1/2 | authoring — date |
| T6 | 2/2 | 2/2 | authoring — value-list + boolean |
| T7 | 2/2 | 2/2 | comprehension — date + pattern read-back |
| T8 | 1/2 | 1/2 | comprehension — custom condition |

**Verdict: no regression from the +11 operators.** opus **13/16**, sonnet **11/16**; the self-describing CLI absorbed the bigger catalog (smoke-checked: `cli operators` surfaces `RoundDown`/`FirstFilledValue`/`RangeAsString`/`YearFromDate`/`MoreThanOneFieldFilled`/`FieldValueAsString`). Notably **T4 opus 1/2 → 2/2** vs the 2026-06-05 run (both opus runs now byte-match / bare-equivalent to the gold; the empty-number-guard subtlety didn't bite this draw). Every non-perfect cell is explained and **none is caused by a new operator**:

- **The 2 sonnet kernel-invalid runs (T3/1, T5/1) share ONE root cause — a CLI argument confusion, not a rule-language gap.** Both put the **model file path in the `--field` slot** (`field=/Users/…/cli/src/test/resources/models/order.dm.json`) instead of a model entity path (`/Order/ProductCode` / `/Order/DeliveryDate`). The condition text was correct in both (T3 byte-identical to gold; T5 a valid `[DeliveryDate] < [OrderDate]`); the kernel only rejected because the error entity was a filesystem path. **GAP (actionable):** `check`/`add` accept any `--field` string and defer to the kernel — a cheap pre-flight lint ("`--field` must be a model entity path: starts with `/`, resolves in the model; you passed a file path") would catch this at the tool boundary and is the clearest material/tooling fix this run surfaced.
- **T1 (opus 1/2, sonnet 0/2) — the iteration-scope idiom, a known wobble.** opus's miss used `FieldFilled(Name)` as the row guard instead of `GroupFilled(Addons)` (diverge on a row filled via another field with empty Name — judge correctly non-equivalent). **Both sonnet runs** authored a *document-level aggregate* instead of the per-Addon-instance rule — and one reached for the **new `NotAllFieldsFilled`** operator (`NotAllFieldsFilled(Addons*/MonthlyFee)`) at whole-document scope. So the new vocabulary is *discoverable and used* — just misapplied on granularity. **GAP:** the skill could sharpen "a per-row error field ⇒ a per-row rule (instance scope), not a document-level count."
- **T8 (1/2 each tier) — custom-condition comprehension slips, distinct per tier.** opus hallucinated what `ExternalEligibility` decides ("signals the order is ineligible / rejects the order") — the exact guessing the rubric forbids. sonnet inverted polarity (called the doc invalid when the condition is *false*). Both otherwise correctly flagged the host-delegation + the `FieldFilled` guard. **GAP:** reinforce "name the delegation, don't guess its logic" + the fires-when-true polarity for custom conditions.
- **T5 note (not a gap):** agents on both tiers author a **direct `[DeliveryDate] < [OrderDate]`** rather than the gold's `DifferenceInDays(OrderDate, DeliveryDate) < 0`; the judge correctly rules them equivalent (an empty-date comparison doesn't fire, matching the gold's `AllFieldsFilled` guard). The simpler form is what agents naturally reach for — the gold over-specifies. Consider relaxing the T5 gold or accepting both.

**On the new operators:** T1–T8 exercise the *older* families, so this run measures **regression only** — it does not directly author a rounding/concat/date-extraction/`FirstFilledValue` rule. The catalog-level surface kept pace (all six new ops listed; one used spontaneously). To measure the new operators' *authorability* we need **T9+ tasks** (e.g. a `RoundAccounting` computed-field rule, a `RangeAsString`/concat consistency rule, a `YearFromDate`/`FirstFilledValue` rule) — the recommended next addition to the suite.

### 2026-06-05 19:12 CEST — targeted T1 re-run confirms the judge path-resolution fix (opus, k=2)

After fixing the judge prompt (it now knows bare field names resolve **up** ancestor groups — GAP 1 from the 19:06 run), a fresh **T1-only** re-run (`w8700u6m0`, 5 agents) flips **opus T1 → 2/2**. Both judges now resolve bare `[Tier]` to `/Subscription/Tier` and rule the candidates equivalent to the gold (*"the only difference is path notation … both denote the same field"*). Confirms the 19:06 T1 0/2 was a **judge false-negative**, not an authoring gap — opus authors T1 correctly from the self-describing CLI alone. (Did not re-judge sonnet's separate `FieldFilled(Name)` vs `GroupFilled(Addons)` guard divergence — that one is a real, subtle slip, left as-is.)

### 2026-06-05 19:06 CEST — full pass@k under the self-explaining CLI, bare-tool (opus + sonnet, k=2)

First run under the **new material boundary**: produce agents get ONLY the self-describing CLI (`--help` / `manifest` / `cli operators`) and the model file — **no `operators.json` hand-off, and no skill** (`benchmark.workflow.js` forbids `*.md`; it always has). Question: did dropping the raw catalog hand-off in favor of the self-describing CLI regress self-sufficiency? Run `wxltzni75` (57 agents, 1.17M subagent tokens, 7m43s).

> **Correction (2026-06-07):** this entry originally read "*plus the judgment-only (trimmed) skill*." That was wrong — the benchmark workflow has never handed the skill to the produce agents (git-verified across all 7 commits; the prompt forbids `*.md`). The phrasing conflated a dual-purpose commit (`5ffc660` trimmed `skill/SKILL.md` *and* dropped the catalog file from the workflow) — only the catalog change touched what agents see. This run was **bare-tool**: CLI-self-describing, no raw catalog, no skill. See EVAL-SPEC §2 "Two run modes."

| Task | opus | sonnet | family |
|---|---|---|---|
| T1 | 0/2 | 0/2 | authoring — iteration guard + cross-group enum |
| T2 | 2/2 | 2/2 | authoring — field-vs-field |
| T3 | 2/2 | 2/2 | authoring — pattern |
| T4 | 1/2 | 1/2 | authoring — aggregate + empty-number guard |
| T5 | 2/2 | 2/2 | authoring — date |
| T6 | 2/2 | 2/2 | authoring — value-list + boolean |
| T7 | 2/2 | 2/2 | comprehension |

**Verdict: no regression from the self-explaining changes.** With the raw catalog withheld, agents discovered the vocabulary via `cli operators` and authored T2/T3/T5/T6 perfectly and read T7 perfectly — so the catalog-via-CLI swap holds (this bare-tool run does not exercise the skill, so it says nothing about the skill trim). The two non-perfect cells are *not* caused by the change:

- **T1 0/2 — mostly a judge false-negative (eval-design), kernel-verified.** Every opus judge marked the candidate non-equivalent *solely* because it used bare `[Tier]` instead of absolute `[/Subscription/Tier]`, claiming bare resolves to a non-existent `/Subscription/Addons/Tier`. **Not so:** `cli check` proves bare `[Tier]` from the `/Subscription/Addons` rule context is **valid** and binds to the ancestor `/Subscription/Tier` (a bogus bare name → `MVK_INVALID_ENTITY`) — bare names **walk up ancestor groups**. So opus's candidates are correct; the judge's path model is wrong (GAP 1). Sonnet additionally used `FieldFilled(Name)` as the row guard instead of `GroupFilled(Addons)` — a genuine, subtle divergence (they differ when an add-on row has an empty Name), so sonnet's T1 misses are partly real.
- **T4 1/2 — the known empty-number-guard subtlety** (consistent with prior runs, not new). The passing run guards `FieldFilled(Quantity)`; the failing run omits it, so per KERNEL-SEMANTICS §2 the empty number reads as 0 and the rule fires on an empty Quantity — genuinely ≠ the gold. The `RK_UNGUARDED_NUMBER_COMPARISON` lint does **not** fire here (it skips field-vs-aggregate), so the agent gets no nudge.

**GAPS / actions:**
1. **Judge path-resolution bug (eval-design):** the judge doesn't know bare field names walk up ancestors → false-negatives (T1). Fix the judge prompt; a cheap T1 re-run should then flip toward ~2/2 (opus).
2. **Kernel finding (new):** bare field names resolve against the rule's group **or an ancestor** (`[Tier]` from `/Subscription/Addons` → `/Subscription/Tier`, no `../` needed) — refines KERNEL-FINDINGS §paths. → documented + the skill's "bare = that scope" line corrected.
3. **Lint gap (minor):** `RK_UNGUARDED_NUMBER_COMPARISON` skips field-vs-aggregate, so T4's missing guard isn't nudged — a candidate lint extension.

### 2026-06-05 07:15 CEST — developer-path baseline probe (n=1, opus, worktree)

The first eval of the **developer path** (N10). A clean opus agent, given only rulekit's public DSL API + javadoc + catalog (**no CLI, no BA skill**), in a git worktree, authoring a rule via the typed builder with the **compiler + `RuleValidator`** as the feedback loop. Task = T1's intent (PRO ⇒ add-on MonthlyFee required — the polarity + iteration-guard traps). Boundary: `skill/`, `docs/`, `eval/` forbidden.

**Result — substantive pass.** Authored `[Tier] == "PRO" And FieldFilled(Addons/Name) And FieldNotFilled(Addons/MonthlyFee)` (error field MonthlyFee), kernel-accepted, **polarity correct**, guarded. ≈ the gold (`GroupFilled(Addons) And [Tier]=="PRO" And FieldNotFilled(MonthlyFee)`); differs only in the guard (`FieldFilled(Name)` vs `GroupFilled(Addons)` — diverges on a no-Name add-on). Compiled first try; 2 validate iterations.

**Type system erased the syntactic class (as predicted):** types/paths/shape; cross-group hosting caught by `build()` (`RK_ERROR_FIELD_OUTSIDE_GROUP`); locale-message coverage pre-checked by `build()`.

**Two semantic traps survived — the same ones the BA path has:**
1. **Polarity.** Got right, but **only via one javadoc sentence** on `Rule.Builder.when()` ("true ⇒ the data is invalid"); the agent flagged it as the #1 risk — *"the compiler happily accepts the wrong-polarity condition."* Navigable but fragile (n=1; a coin-flip without explicit framing).
2. **Iteration guard.** `build()` does **not** pre-warn — the agent's first attempt (no guard) was rejected by the live kernel (`MVK_NEG_CONDITION_IN_ITERATION`). The `Linter` *has* the check (`RK_NEG_CONDITION_IN_ITERATION`) but `build()` doesn't run it, and a developer calling `build()` + `RuleValidator` isn't pointed at `RuleDiagnostics`. The guard remedy came from the catalog gotcha text.

**Answer to "can you write wrong rules via the API?": yes** — polarity, the iteration guard, and empty-as-0 all survive the type system, which erases only the syntactic class. **But a full dev "skill" looks unnecessary** — the gaps are narrow and fixable at the API surface:
- enrich `Rule.Builder.when()` javadoc with the error-scenario polarity framing (✅/❌);
- make the lint **discoverable** on the dev path (javadoc pointer `build()`/`RuleValidator` → `RuleDiagnostics`, the developer's lint backstop) so `RK_NEG_CONDITION_IN_ITERATION` / `RK_INVERTED_REQUIREDNESS` surface at author time, not only via the kernel;
- the candidate unguarded-number-comparison lint (empty-as-0) serves both personas.

n=1 — a pass@k dev-path run would firm up the polarity coin-flip. (Harness: clean agent + worktree + compiler/lint loop; distinct from the CLI Workflow runner.)

### 2026-06-05 06:57 CEST — T4 intent reword, opus + sonnet, k=2 (run `w5qap37n5`)

Confirming the T4 intent fix (reworded to "the sum of each RUSH line item's Count"; gold unchanged). Fresh, T4 only — 9 agents.

| model | T4 |
|---|---|
| opus | 0/2 |
| sonnet | 1/2 |

**The fix worked — even though pass@k is still low.** The point of this re-run was the count-vs-sum confusion, and it is **gone**: all four candidates now use `Sum(Items*/Count Having …)`, summing the per-line Count exactly as intended. The remaining misses are entirely different and much subtler:
- **opus ×2 — dropped the `FieldFilled(Quantity)` guard.** Right aggregate, but the judge flagged that without the guard the rule diverges from the gold on an *empty* Quantity (the judge reasoned a number-null is compared as 0, so `0 < Sum` fires where the guarded gold stays silent). A real-but-subtle edge — and the judge's null-semantics claim is itself unverified (worth a quick KERNEL check if we lean on it).
- **sonnet ×1 — `PatternMatched "RUSH.*"` instead of `== "RUSH"`** for the Sku filter (matches `RUSHED` too) — a defensible-but-different interpretation.
- **sonnet ×1 — byte-identical to the gold** → equivalent. ✅

So T4 moved from *"wrong aggregate (a clear spec defect)"* to *"right aggregate, with subtle guard/match variations the strict judge separates."* That's the intended outcome: the spec bug is fixed; the residual is k=2 noise + legitimate judge strictness, not a material gap. (Possible tiny future skill note: guard a number comparison with `FieldFilled` when the field's presence is implied — held off to avoid over-fitting to one cell.)

**Eval worklist status:** the three eval-design artifacts are resolved (T3 gold, T6 judge context, T4 intent); T5 is a residual polarity slip within k=2 noise. No material gap remains proven — the suite now measures real subtleties.

### 2026-06-05 06:49 CEST — targeted re-run T3/T4/T5/T6, opus + sonnet, k=2 (run `w90famp3n`)

Confirming the four fixes from the 05:51 run. Fresh run (material + harness changed), subset to the four authoring tasks. 33 agents = 16 produce + 1 recheck + 16 judge (§8a; `A=4, C=0, M=2, k=2`), ~729k tokens.

| model | T3 | T4 | T5 | T6 |
|---|---|---|---|---|
| opus | **2/2** (↑ 0/2) | 0/2 | 1/2 | **2/2** (↑ 0/2) |
| sonnet | **2/2** (↑ 0/2) | 0/2 | 2/2 | **2/2** (↑ 0/2) |

**Two fixes confirmed:**
- **T3 ✅ 0/4 → 4/4.** The unambiguous product-code gold worked — both tiers wrote byte-identical conditions. The earlier email-regex false negative is gone.
- **T6 ✅ 0/4 → 4/4.** The judge now reasons within the enum domain: *"Tier is exactly {BASIC, PRO, ENTERPRISE}, so 'not in {PRO, ENTERPRISE}' equals '== BASIC'"* — so `Tier == "BASIC"` is correctly scored equivalent. The judge-context false negative is gone.

**T4 re-diagnosed — 0/4, but NOT the gap I fixed.** Both tiers wrote a *count of RUSH items*; the gold *sums their per-line `Count`*. But the intent reads **"the total count of line items whose Sku is RUSH"** — which most naturally means *count the items*, and the field is literally named `Count`. So the agents' reading is defensible; this is an **intent⇄gold mismatch + a misleading field name**, not a skill gap. (The skill's Sum-vs-count section is still correct pedagogy — it just wasn't T4's problem.) **Fix:** reword T4's intent to unambiguously call for *summing each RUSH line's `Count` quantity* (or rename the confusing field). This is a third eval-design artifact, found only because re-measuring contradicted my assumed cause.

**T5 — 3/4, unchanged.** One opus run still inverted the polarity (fired on `DeliveryDate >= OrderDate`, the opposite). The skill's `DifferenceInDays` sign note didn't move the rate — the remaining miss is a *polarity* slip, not a sign error, and at k=2 is within noise. Watch; raise `k` if it matters.

**Takeaway:** the targeted re-run cost ~⅓ of a full run and confirmed the two harness fixes *and* corrected my misdiagnosis of T4 — the build-measure-learn loop catching a wrong fix is exactly the point. Net material gaps remaining: essentially none proven (T4 = task-spec bug, T5 = residual polarity noise).

### 2026-06-05 05:51 CEST — first pass@k, full suite, opus + sonnet, k=2 (run `wx2sb6fin`)

The first complete Layer-2 run. `pass = kernel-valid AND judge-equivalent to the gold` (comprehension: judge-correct vs the rubric). **56 agents** = 28 produce + 1 recheck + 27 judge (the agent-count formula is in EVAL-SPEC §8a; `A=6, C=1, M=2, k=2`), ~386k tokens, ~92s — resumed over the cached produce agents from the failed `w35lyr1fd`.

| model | T1 | T2 | T3 | T4 | T5 | T6 | T7 |
|---|---|---|---|---|---|---|---|
| opus | 2/2 | 2/2 | 0/2 | 0/2 | 1/2 | 0/2 | 2/2 |
| sonnet | 1/2 | 2/2 | 0/2 | 0/2 | 2/2 | 0/2 | 2/2 |

Strong: **T1, T2, T7** (comprehension 4/4 — reading foreign rules back works well on both tiers). The failures split into two kinds — and telling them apart is the main result:

**Real material gaps (the skill/catalog worklist):**
- **T4 aggregate+Having — 0/4.** Both tiers mis-modelled the aggregate: used a *count of filled `Sku` fields* (`NumberOfFilledFields`) or reversed polarity, instead of `Sum(Items*/Count Having …)`. The material under-teaches "sum a field over a filtered repeatable group" vs "count items". **Next:** a worked aggregate+Having example in the skill.
- **T5 date — 3/4.** One opus run inverted the `DifferenceInDays(A, B) = B − A` sign (fired on delivery *after* order). **Next:** state the sign convention explicitly in the skill.

**Eval-design artifacts (NOT material gaps — fix the harness, not the skill):**
- **T3 pattern — 0/4 is a false negative.** Both models wrote *valid, sensible* email regexes (`.+@.+`, `[^@]+@[^@]+`) that simply differ from the gold's specific pattern. There is no canonical email regex, so exact-equivalence is the wrong bar. **Fix:** T3's gold should accept any reasonable email pattern (judge on intent, not regex identity), or pick a task whose pattern is unambiguous (e.g. a 5-digit postal code).
- **T6 value-list — 0/4 is a judge false negative.** Both used `Tier == "BASIC"`. The judge marked it non-equivalent, reasoning about "FREE/TRIAL" tiers — but the `Tier` enum is *exactly* `{BASIC, PRO, ENTERPRISE}`, so `== "BASIC"` **is** equivalent to "not in {PRO, ENTERPRISE}". The judge wasn't given the model, so it assumed an open enum domain. **Fix:** give the judge the field's enum domain (or the model card) so it can reason within the actual value set.

**Meta-findings for the harness:** (1) the judge needs **model context** (at least enum domains) to avoid open-world false negatives; (2) golds for **open-ended** outputs (regex, free text) need intent-equivalence, not string identity. One flake: `sonnet/T1/1` produced no kernel-valid rule (n=2 is noisy — raise k for the cells that matter).

*(Earlier same session: `w35lyr1fd` aborted on an uncaught rescore agent — runner bug, since fixed (best-effort rescore → self-report fallback); a first attempt no-opped on empty `args.tasks` — now parsed defensively. Neither was a material finding.)*

### 2026-06-05 — Sonnet matches Opus (+skill, pinned)

Re-ran both T1 and T2 skill-equipped on **Sonnet** (pinned). Result: **first-attempt / high-confidence on both — identical to Opus.** So the skill's value is **not tier-dependent**: a cheaper model (the realistic BA case) reaches the same correct, kernel-accepted rule. The build-measure-learn loop also showed through — Sonnet-T2 explicitly used the field-vs-field + strict/inclusive guidance added to the skill earlier the same session ("exactly covers this case — no inference required"). Re-confirmed gap (Sonnet-T2): the BA path stopped at `check` (validate a candidate) with no way to *persist* the rule — since closed by the `add` verb. *(Caveat: n=1 per cell; the Workflow runner gives pass@k across tasks/tiers.)*

### 2026-06-05 — T2 genuine transfer (+skill, Opus 4.8 clean)

T2 is *structurally unlike* the skill's worked example (no enum, no repeatable group, no guard; a field-vs-field numeric comparison). The skill-equipped agent passed **first-attempt, high confidence**, transferring the *principles*: the polarity flip (requirement `>=` → violation `<`), the bracket form, field-vs-field comparison, and — correctly — **skipping** the guard after `describe` showed the group is non-repeatable. So the skill teaches principles, not a template (answers the T1 caveat). Two cheap gaps found and **closed in the skill the same session** (build-measure-learn): field-vs-field comparison was never demonstrated (only field-vs-literal), and strict-vs-inclusive wording (`<` vs `<=`) had no guidance. Re-confirmed limitation: `check` accepts *both* polarities, so polarity correctness rests on the skill's reasoning, not the kernel — the standing argument for the inverted-requiredness lint, since built into `add`/`check`.

### 2026-06-05 — T1 with the skill, the skill lift (neutral prompt, Opus 4.8 clean)

Adding `skill/SKILL.md` to the material moved T1 from *multi-attempt / medium-confidence* (check-only) to **first-attempt / high-confidence**: the agent got the polarity right by construction (citing the skill's polarity table — *"without it I would almost certainly have written the opposite rule `FieldFilled(...)`"*), pre-empted `MVK_NEG_CONDITION_IN_ITERATION` with the guard, and ran a negative-control to confirm the guard is load-bearing. The #1 silent-inversion gap is closed by the skill. **Caveat (EVAL-SPEC §5):** T1 ≈ the skill's worked example, so this is *transfer*, not novel composition — needed the structurally-distinct T2 (above) to be a strong claim. Minor new findings: the catalog is large JSON keyed by `id`/`keywords` (hard to skim raw — a future `cli operators <search>` or the skill naming operators mitigates); shell-quoting embedded `"…"` in `--condition` is friction (a `--condition-file`/stdin or the JSON rule-spec avoids it).

### 2026-06-05 — T1 re-run after the `check` verb (neutral prompt, Opus 4.8 clean)

**Decisive improvement.** With `check` present, the agent went from *"could not validate at all"* to **authoring a rule the kernel accepts**, via a real **reject→fix→accept loop**: attempt 1 → `MVK_NEG_CONDITION_IN_ITERATION` → used the *catalog gotcha* (which names the guard idiom) → attempt 2 → `MVK_INVALID_ENTITY` → probed the path rules → **accepted** (`{valid:true}`). The diagnostics-driven self-correction loop works as the F4a/catalog thesis predicted, and the kernel error messages were rated genuinely helpful.

**The sharpened #1 gap — silent inverted-polarity.** With a *neutral* prompt the agent still inferred the right polarity, but flagged the real trap: nothing states "the condition is true *on a violation*." A BA would naturally write `FieldFilled(MonthlyFee)` (the requirement as a positive assertion); the CLI returns `valid:true` — but that encodes the **inverted** rule (fires when the fee *is* present). **The tool reports success for a semantically wrong rule.** Kernel investigation (2026-06-05, KERNEL-FINDINGS) settled that **polarity is fixed — not configurable**: `errorCondition` always fires on TRUE, there's no `Not` operator (only paired positive/negative predicates), so the trap can't be designed away at the kernel. Mitigation is rulekit's: **(a)** the skill teaches "state the requirement as its violation, via the negative-form predicate"; **(b)** the `RK_INVERTED_REQUIREDNESS` lint flags likely inversion — AST-based, so it needs a structured rule, and the shared `LintBackstop` now runs it on **both** `add` (persisted) and `check` (via a temporary in-memory injection). So the trap is caught at both BA entry points. Remaining gaps from this probe: path-reference syntax (relative-to-iteration-group vs absolute `[/...]`, bracket rules) and iteration-is-implicit (error-field-placement drives it).

### 2026-06-05 — baseline, T1, read-only CLI (no `check`, no skill)

A clean subagent given only the CLI (`dump`/`describe`/`validate`) + the catalog, forbidden source/docs.

**Strengths (material works):** the **catalog is a usable operator reference** — the agent picked operators and composed a *correct* condition with the right **polarity** (error-scenario inversion) and the **enum-stored-value** rule (`"PRO"` not the label). It even flagged the `MVK_NEG_CONDITION_IN_ITERATION` trap as an untested risk.

**Gaps (the worklist this produced):**
1. **DECISIVE — no author-and-validate affordance.** The CLI was read-only; `validate` only re-checks rules *already in* the model, so the agent could not confirm its rule (the task's success criterion was unreachable). It independently designed the fix (`check <model> --field --code --condition`). → built as the `check` verb.
2. **Skill-content gaps:** the catalog covers the *condition expression* but not the **rule envelope** (error field/code), the **bracket-vs-bare path convention**, the **iteration-via-error-field-placement** semantics (most business-critical, absent), and there was **no worked end-to-end example**. → addressed by the skill.
3. **CLI defect:** `validate` **silently ignored extra args** → hardened to error on unknown args.

**Caveat on the strengths:** the prompt primed the inversion (EVAL-SPEC §5 protocol lesson), so the polarity success was partly attributable to the prompt, not the material — re-measured neutrally above.
