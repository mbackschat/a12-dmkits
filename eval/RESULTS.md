# a12-dmkits — eval & benchmark results log

The dated record of self-sufficiency eval runs. **The method, scoring, task suite, and glossary live in [`../docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md)** — this file holds only *measured results*, because results churn every run while the spec should stay stable. Append new entries at the top of the probe log, each stamped with **date + time-of-day** (we run several per day). *The early 2026-06-05 single-probes predate per-run time-stamping — they are in session order, exact clock times unrecorded.*

## Lift summary

| Change measured | Task | Before | After | Model |
|---|---|---|---|---|
| Add the `check` verb | T1 | could not validate at all | authors a kernel-accepted rule (reject→fix→accept loop) | Opus 4.8 |
| Add the skill | T1 | multi-attempt / medium-confidence | first-attempt / high-confidence; polarity correct by construction | Opus 4.8 |
| Genuine transfer (skill) | T2 | — | first-attempt on a structurally-novel shape (no template to copy) | Opus 4.8 |
| Model tier | T1, T2 | Opus passes | **Sonnet matches Opus** — the skill lift is not tier-dependent | Sonnet |

All entries below are **single-probe (n=1 per cell)** unless noted; the Layer-2 Workflow runner provides **pass@k** across tiers (latest run at the top of the log).

## Probe log

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
