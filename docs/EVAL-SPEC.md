# a12-dmkits — self-sufficiency eval & benchmark (N6/N1)

How we measure — and improve — whether rulekit's bundled material lets an LLM author and understand A12 rules **without the kernel's BA docs**. This operationalizes the existing requirement **N6** (semantic self-sufficiency, proposal §14) and drives **N1** (the LLM skill). The proposal states the intent; this spec is the method, the scoring, and the living task suite.

> **New to LLM evals?** The [primer & glossary](EVAL-PRIMER.md) explains the idea in plain terms and defines the vocabulary (eval, gold, oracle, judge, pass@k, …). This document is the method, scoring, task suite, and runbook.

## 1. The core method — a clean subagent is the instrument

**Self-evaluation is invalid.** The main-loop assistant that built rulekit has internalized the design; if *it* authors a rule, that proves nothing about the *material*. So the LLM-under-test is a **freshly spawned subagent with clean context**, given **only the material under test** + a task. It is a faithful stand-in for "an arbitrary LLM consumer meeting rulekit for the first time."

- **Honest caveat:** the subagent is *Claude-family*, so this measures Claude-family self-sufficiency, not "any LLM." It is still vastly better than self-eval and is what we can run in-loop.
- **Model dimension (pin it).** The subagent's model is a first-class benchmark axis: the `Agent`/`Workflow` `model` param (`opus`/`sonnet`/`haiku`) runs the *same task across tiers* → `model × task → pass@k`, so we can compare e.g. Sonnet vs Opus self-sufficiency. **Always pin the model explicitly** for reproducibility (don't inherit the session model — it drifts). Results are labeled by model; an unpinned baseline run measures the *strongest* tier (an upper bound: if Opus struggles, weaker tiers struggle more).
- **The N6 stance (decisive):** when the agent fails, **the material is incomplete, not the LLM.** Every failure localizes a gap — a missing operator example, an unclear gotcha, an absent verb — which becomes the prioritized worklist for N1.

## 2. The material under test — and the boundary that makes the eval valid

The agent gets exactly the **shipped** material for the persona under test, and **nothing else**:

- **BA/CLI path:** the **self-describing CLI** — its verbs plus their `--help`/`manifest`/`schema` output and the operator catalog reached through **`cli operators`** (since 2026-06-05; no separate `operators.json` hand-off) — and, *in production*, the **N1 skill** (`skill/SKILL.md`). The agent uses the `installDist` launcher (the JVM "java starter" — current code, identical behavior to native; the native binary is a stale spike artifact, never the eval vehicle). Whether the skill is included is the **run-mode** distinction in §2a.
- **Developer path:** rulekit-the-library's **public API + javadoc** + the catalog, with the **compiler + lint (`RuleDiagnostics`)** as the feedback (N10 — no bespoke vehicle). *Harness:* a clean agent in a **git worktree** authors a rule via the typed builder and validates with `RuleValidator`, iterating against `javac`/gradle. (First probe 2026-06-05 — see [`../eval/RESULTS.md`](../eval/RESULTS.md); harness distinct from the CLI Workflow runner.)

**Hard boundary — per persona** (no design notes, no answer keys; that's what makes the eval measure the *product*):
- **BA/CLI:** only the CLI (which self-describes its operator catalog via `cli operators`) + (in production mode) the N1 skill — **not** rulekit's source, `docs/`, or tests.
- **Developer:** the public API **source + javadoc** + catalog + `README` *are* the material (that's what a library user sees) — but `skill/`, `docs/` (design notes), and **`eval/` (the golds)** are off-limits.

Either way, reading the model file under test is allowed — it's the input.

### 2a. Two run modes of the BA/CLI path — bare-tool vs production

The BA/CLI persona has **two distinct material boundaries** that measure different things. They are easy to conflate (the RESULTS log did, once — see the 2026-06-05 19:06 correction); keep them separate.

| Mode | The agent sees | The question it answers | Runnable today? |
|---|---|---|---|
| **Bare-tool** | self-describing CLI + the model file — **no skill, no catalog file, no docs** | Does the CLI's *self-explaining surface alone* let a cold agent discover the verbs/operators and author/read rules? (the discoverability bar in CLAUDE.md "Self-explaining surface") | ✅ **`eval/benchmark.workflow.js`** — the only wired Workflow; **every pass@k run in RESULTS has been this mode** |
| **Production** | self-describing CLI + **`skill/SKILL.md`** + the model file | Does the *full shipped material* (the real BA/LLM experience) get polarity / iteration / the traps right? — this is the mode whose number moves when a **skill note** is added | ⚠️ **not a Workflow yet** — only ever measured by early **manual, n=1** probes (RESULTS "Add the skill" lift rows) |

Why bare-tool is the default instrument, not an afterthought: CLAUDE.md's standing requirement is to **favor a self-explaining CLI over relying on the skill** — the skill must stay minimal and teach only *judgment* (polarity, the traps), never the verb/parameter catalog the tool should describe about itself. Bare-tool is the forcing function for that: it isolates **"does the tool describe itself?"** from **"does the judgment layer land?"**. So the two modes also split the worklist cleanly:

- a bare-tool failure the **skill would fix** (a polarity flip, a missed iteration guard) → a **skill-worklist** item (N1), *not* a CLI defect;
- a bare-tool failure about an **undiscoverable verb/param/operator** → a **CLI/catalog** defect (fix the self-description).

`benchmark.workflow.js` has **always** been bare-tool by default: from its first commit the produce/explain prompts say *"Do NOT read any … `*.md` files"*, and the script never referenced `SKILL.md`. **Production mode is now wired as opt-in (2026-06-08):** pass the skill's CONTENTS via `args.skill` (the script has no filesystem access, so the main loop reads `skill/SKILL.md` and passes the text); the producer prompts then hand that text to the agent as trusted background while still forbidding docs/source/tests/golds/other `*.md` (the agent reads no files — the skill is inline). With no `args.skill`, the run is bare-tool exactly as before. **Bare-tool stays the default and the only mode CI / the historical trend line use** — a production number is labelled separately and is not comparable to the bare-tool series.

**Decision (2026-06-07, revisited 2026-06-08): bare-tool is the default instrument; production mode is now wired but stays opt-in.** Bare-tool is not a compromise — it is the instrument we *want*, for four reasons:

1. **It enforces the self-explaining requirement.** CLAUDE.md's standing rule is to favor a self-explaining CLI over relying on the skill. Bare-tool is the forcing function: a miss a cold agent makes from the CLI alone is a **CLI/catalog defect to fix** (a missing `--help` detail, an operator without a usable example), not something to paper over by handing it the skill. A skill-equipped run would *mask* exactly the gaps we most want surfaced.
2. **It measures the floor, honestly.** A real consumer may or may not have the skill loaded; bare-tool is the worst-case "just the binary" baseline. If that passes, the skill is pure upside.
3. **It splits the worklist cleanly** (the two bullets above): a miss the skill would fix → a *skill* (N1) item; a miss about an undiscoverable verb/param/operator → a *CLI/catalog* item. A skill-equipped run can't make that distinction.
4. **Comparability + simplicity:** every historical run is bare-tool, so the trend line stays intact, and there's no skill-injection plumbing to maintain.

The accepted tradeoff: the *default* (bare-tool) benchmark **does not measure the skill notes** (e.g. the T1/T8 ones stay backed by reasoning + the early manual probes unless a production run is requested). That's deliberate — we treat the minimal skill as judgment-layer *insurance* (polarity, the traps), not something needing continuous pass@k. Production mode (now wired) exists for the **one-off question** "does the skill close a specific gap?" — run it on demand, label it as production, and keep it out of the bare-tool trend line; it is not part of CI or the per-commit story.

## 3. Task families & scoring

Five families (the four authoring/modify variants share one scoring shape; comprehension is its mirror), each with an **objective, deterministic backbone** (the kernel is the oracle):

- **Authoring** — intent (NL) → a rule. Scored: (a) **kernel accepts it** (run the CLI's validate/check — hard pass/fail), and (b) **matches intent** (the rendered condition equals an expected canonical form, or read→re-render→compare for semantic equivalence).
- **Authoring-computation** — intent (NL) → a *computation* (computed field + alternatives). Scored the same way, but the kernel gate is `cli add` (which validates before persisting) and the judge compares **computation** equivalence (same value per document).
- **Modify-computation** — an *existing* computation + a change intent → a re-expressed computation, keeping the same computed field. The kernel gate is `cli modify --computation … --dry-run` (replace-then-bind, read-only), which also reports `changed`; a modify passes only when the kernel **accepts** it **and** it is an actual **change** (changed:true), then the judge scores equivalence to the gold modification.
- **Modify-rule** — the rule-side counterpart: an *existing* rule + a change intent → a re-expressed **condition**, keeping the same rule (same error field). The kernel gate is `cli modify --rule … --condition … --dry-run` (replace-then-bind, read-only; the runner batches these so the DSL condition needs no shell-quoting), same accepted-AND-changed pass rule, and the judge scores condition equivalence (polarity included) to the gold.
- **Comprehension** — a rule → an explanation. Scored: it names the right operators / referenced fields / iteration scope / relevant gotchas (checkable against the model + catalog).

A task may also be **multi-file**: it carries a `modelPath` (a nested layout) and an `includeDir`, and the runner threads `--include-dir` through every verb (check/describe/modify) so the model's `include`d/typedef models resolve (F-RES). Such a task forces the agent to discover `--include-dir` from the self-describing CLI — the multi-file model cannot expand without it.

**Scoring policy — a *soft* regression, not a hard CI gate.** The kernel-acceptance sub-check is deterministic, but the *agent's behavior* is non-deterministic and costs tokens. So:

- Run **pass@k** (a few runs per task); track **pass-rate over time** against a **threshold** (e.g. "≥90% of covered operators author-and-validate").
- Run **periodically / on-demand**, not per-commit; alert on drops rather than blocking merges.
- It **composes with N7** (which already runs every catalog example through the kernel, fully deterministic): N7 guards the catalog's truth; this benchmark guards the LLM-in-the-loop usability on top.

## 4. The build-measure-learn loop (run it in-session)

```
draft material (skill + self-describing CLI: verbs, --help, cli operators)
      │
      ▼
spawn a CLEAN subagent  ──►  give it ONLY the material + one task
      │
      ▼
score (kernel-accepts? intent-match?)  +  read the GAPS section of its report
      │
      ▼
the gap names the material deficiency  ──►  improve the material
      │
      └────────────────────────────► re-run the same task → measure the lift
```

The benchmark is the **forcing function**: it tells us, objectively, which verbs/skill-content to build next — we stop guessing.

## 5. Probe protocol (how to run one)

> **Operationally, run via the `run-rulekit-eval` skill** (`.claude/skills/`) — it loads these guardrails at point-of-use so they aren't run from memory. This section is the rationale behind them.

- Spawn a **fresh agent** (clean context; the `Agent` tool does this). For the suite at scale, a **Workflow** fans out tasks × k runs through clean agents, each scored via the CLI, aggregating pass@k (opt-in — it spawns many agents).
- The prompt **must not leak the answer.** *(Protocol lesson from the 2026-06-05 baseline: the first probe prompt hinted at the error-scenario inversion — "think about when the condition is true" — which biases the result. Keep prompts neutral; state the business requirement and the constraints, not the technique.)*
- **No teaching to the test.** The skill's worked examples must be *structurally distinct* from the benchmark tasks, or a pass measures copying, not self-sufficiency. *(Lesson: the skill's worked example (Order/EXPRESS/DeliveryDate) is near-identical in shape to T1 (Subscription/PRO/MonthlyFee), so the T1-with-skill pass measures pattern-substitution transfer, not novel composition — add T2 tasks whose shape no taught example covers.)*
- State the hard boundary (§2: no source/docs).
- Require a structured report ending in an explicit **GAPS** section (what was missing/confusing/guessed) — that section is the actual deliverable.

## 6. The task suite (living)

Grows as coverage grows. Each task defines an intent, a kernel-verified gold, and what it exercises. **Measured agent results (lifts, pass@k) live in [`../eval/RESULTS.md`](../eval/RESULTS.md)**, not here.

| # | Family | Task | Gold (kernel-verified) | Backbone & what it exercises |
|---|---|---|---|---|
| T1 | Authoring | "When Tier is PRO, each add-on's MonthlyFee must be provided" (subscription) | field `/Subscription/Addons/MonthlyFee`; condition `GroupFilled(/Subscription/Addons) And [/Subscription/Tier] == "PRO" And FieldNotFilled(MonthlyFee)`; code `PRO_ADDON_FEE_REQUIRED` | gold verified. The iteration/negative-guard idiom (a bare `FieldNotFilled` in the repeatable Addons scope trips `MVK_NEG_CONDITION_IN_ITERATION` unless guarded) + an absolute cross-group ref. |
| T2 | Authoring | "A subscription's effective fee must never be lower than its base fee" (subscription) | field `/Subscription/Billing/EffectiveFee`; condition `[EffectiveFee] < [BaseFee]`; code `EFFECTIVE_FEE_BELOW_BASE` | gold verified. Non-iterating, field-vs-field comparison (both operands bracketed); the polarity flip `>=` → `<`; no guard needed. |
| T3 | Authoring | "An order's product code, when present, must be `XX-9999` (2 uppercase letters, hyphen, 4 digits)" (order) — pattern | field `/Order/ProductCode`; condition `FieldFilled(ProductCode) And [ProductCode] PatternViolated "[A-Z]{2}-[0-9]{4}"`; code `PRODUCT_CODE_MALFORMED` | gold verified. Pattern polarity (`PatternViolated`, not `PatternMatched` — matching is the *good* case) + presence guard. **Unambiguous** format (the intent fully specifies it) — replaced an email-regex version whose gold over-specified (RESULTS 2026-06-05). |
| T4 | Authoring | "An order's Quantity must be at least the total quantity across its RUSH line items (sum of each line's `Count`)" (order) — aggregate + Having | field `/Order/Quantity`; condition `FieldFilled(Quantity) And [Quantity] < Sum(Items*/Count Having [Items/Sku] == "RUSH")`; code `QUANTITY_BELOW_RUSH_ITEMS` | gold verified. A filtered aggregate over a repeatable group vs a non-repeatable field; model-level (non-iterating); error field covered. Intent reworded to say *sum each line's Count* (the earlier "total count of line items" read as counting rows — RESULTS 2026-06-05). Now also **reads cleanly** (field-vs-aggregate round-trips since 2026-06-05). |
| T5 | Authoring | "An order's delivery date must not fall before its order date" (order) — date | field `/Order/DeliveryDate`; condition `AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0`; code `DELIVERY_BEFORE_ORDER` | gold verified. Date-diff argument order/sign (`DifferenceInDays(A,B)` = B−A) + a multi-field presence guard. |
| T6 | Authoring | "An auto-renewing subscription must be on a paid tier (Pro/Enterprise), never Basic" (subscription) — value-list + boolean | field `/Subscription/Tier`; condition `[AutoRenew] == True And FieldValueNotIncludedInValueList(Tier, "PRO", "ENTERPRISE")`; code `AUTORENEW_REQUIRES_PAID_TIER` | gold verified. The *negative* value-list predicate, the boolean literal `True` (not the string), and enum-by-stored-code. |
| T7 | Comprehension | "Read the two rules on this order model and explain each violation + the field it guards" (`order-ruled` — a date rule + a pattern rule) | rules `/Order/DeliveryNotBeforeOrder`, `/Order/BillingAddress/PostalCodeFormat`; rubric in the task's `expected` | backbone: both rules read to `Converted` (readable by `explain`). Read-back of foreign rules: polarity + violated-not-matched pattern semantics + presence guard. |
| T8 | Comprehension | "Read the EligibilityCheck rule and explain the violation, the guarded field, and what it delegates" (`order-ruled` — a custom condition) | rule `/Order/EligibilityCheck` (`FieldFilled(CustomerName) And CustomCondition ExternalEligibility`); rubric in the task's `expected` | backbone: reads to `Converted` (custom conditions are typed since 2026-06-06). Tests recognizing a **host-delegated** condition and *not hallucinating* its meaning from the name. |
| T9 | Authoring | "An order placed before the year 2020 is invalid" (order) — date extraction | field `/Order/OrderDate`; condition `FieldFilled(OrderDate) And YearFromDate(OrderDate) < 2020`; code `ORDER_BEFORE_2020` | gold verified. The **date→number extraction** family (new 2026-06-06): `YearFromDate` returns a number, so it compares like one; reach for it over an ISO-format date literal (lexed as a string → rejected for ordering) + presence guard. |
| T10 | Authoring | "When express shipping is selected, the shipping address must be complete (all its fields filled)" (order) — group-scope quantifier | field `/Order/ExpressShipping`; condition `[ExpressShipping] == True And NotAllFieldsFilled(ShippingAddress)`; code `EXPRESS_NEEDS_FULL_ADDRESS` | gold verified. The **group-scope** field-fill quantifier (new 2026-06-06): `NotAllFieldsFilled(<group>)` checks the fields inside a group (vs the field-list form); polarity (`NotAllFieldsFilled`, not `All…`) + boolean `True`. |
| T11 | Authoring | "A person's full name must read as first + space + last" (`person`) — string concatenation | field `/Person/FullName`; condition `AllFieldsFilled(FirstName, LastName) And [FullName] != [FirstName] + " " + [LastName]`; code `FULLNAME_MISMATCH` | gold verified. **String concatenation** (new 2026-06-06): a string expression on the right of a comparison; the `+` is the intrinsic concat operator; `!=` polarity for "must equal". Uses the dedicated `person` fixture. |
| T12 | Authoring | "A person's tax number, when provided, must be a valid German tax number (Steuernummer)" (`person`) — custom-field-type validity | field `/Person/TaxId`; condition `FieldFilled(TaxId) And Invalid(TaxId, "Steuernummer")`; code `TAX_ID_INVALID` | gold verified. **Custom-field-type validity** (`ValidType`/`InvalidType`, new 2026-06-08): `Invalid(field, "Type")` delegates to a registered type verifier. Deliberately unambiguous — a Steuernummer checksum has no regex, so a `PatternViolated` candidate is genuinely non-equivalent (the email pitfall T3 avoids). The type name isn't model-checked (accepted at check-time; see the catalog gotcha). The `person` fixture gained a `TaxId` string field. |
| T13 | Authoring-computation | "An order's coverage window is the date range from its order date to its delivery date" (order) — the `DateRange` constructor | computed field `/Order/CoverageWindow`; operation `DateRange(OrderDate, DeliveryDate)` | gold verified. The **`DateRange` constructor** (its sole legal home is a computation assigning a DATE_RANGE field). **Gold is UNGUARDED, intentionally** — an empty operand makes the computation compute nothing (KERNEL-FINDINGS §2 supplement, locked by `adapter.laws.DateRangeComputationLawsTest`), so an `AllFieldsFilled` guard is redundant/equivalent; the judge prompt carries this fact (settles the 2026-06-08 judge split). |
| T14 | Authoring-computation | "A subscription's effective fee is twice its base fee" (subscription) — numeric computation | computed field `/Subscription/Billing/EffectiveFee`; operation `[BaseFee] * 2` | gold verified. The baseline computation **write path** — discovering the computation-spec shape (`schema add` → computedField/alternatives/messages) and an unconditional value expression. |
| T15 | Authoring (multi-file) | "Every storefront must have a store name" (`storefront`, multi-file) | field `/Storefront/StoreName`; condition `FieldNotFilled(StoreName)`; code `STORE_NAME_REQUIRED` | gold verified (with the resolver). **CLI-over-includes (F-RES):** `storefront` lives in `multifile/app/` and `include`s a separate `catalog` model in a SIBLING `multifile/lib/` tree — so the model can't expand/validate without `--include-dir <lib>`; the agent must discover it from the self-describing CLI (the expand error names the missing `catalog` + the dir searched). Gold on a MAIN-model field (rule-injection can't attach to a mounted-include field — a known boundary). |
| T16 | Modify-computation | "Change the effective-fee computation so the effective fee is the base fee PLUS the total of all add-on monthly fees" (`subscription-computed`) — the `modify --dry-run` loop | computed field `/Subscription/Billing/EffectiveFee`; operation `[BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)` | gold verified via `cli modify --dry-run` (accepted, changed:true). The fixture already carries `EffectiveFeeComp = [BaseFee]`; the agent must discover it, KEEP the computed field, and re-express. Recheck = `cli modify --computation … --spec --dry-run` (replace-then-bind, must be ACCEPTED and an actual CHANGE). Backbone: replacing the computation with the gold re-binds. |
| T17 | Modify-rule | "Tighten the delivery rule: the delivery date must be at least 2 days after the order date" (`order-ruled`) — the `modify --rule` loop | error field `/Order/DeliveryDate`; condition `AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 2`; code `DELIVERY_TOO_SOON` | gold verified via `cli modify --rule --dry-run` (accepted, changed:true). The fixture already carries `/Order/DeliveryNotBeforeOrder` (`… DifferenceInDays(…) < 0`); the agent discovers it, KEEPS the rule + error field, and re-expresses the **condition** — minding the polarity (requirement "≥ 2 days after" → violation `< 2`). Recheck = `cli modify --rule … --condition … --dry-run` (batched; replace-then-bind, ACCEPTED and an actual CHANGE). Backbone: replacing the condition re-binds. |

> **New-operator / surface coverage:** T9–T12 (authoring) exercise four families the build-out added (date extraction, group-scope quantifiers, string concatenation, custom-field-type validity); **T13–T14 (`authoring-computation`)** cover the **computation write surface** (the `DateRange` constructor + a numeric computation) via the Layer-2 runner's computation path (author a computation-spec → authoritative `cli add` recheck → computation-equivalence judge); **T15 (multi-file authoring)** covers **CLI-over-includes** (`--include-dir`, F-RES — the runner stages a nested `app/`+`lib/` model and threads `--include-dir` through recheck/judge); **T16 (`modify-computation`)** + **T17 (`modify-rule`)** cover both halves of the **`modify --dry-run` loop** (computation re-express and rule-condition re-express). (Added 2026-06-08.) The remaining operand-list operators (**rounding**, **`FirstFilledValue`**, date **`MaxValue`/`MinValue`**, **root-group count**) read awkwardly as authoring intents — better as **comprehension** tasks over a fixture carrying such rules (still open).

## 7. Results

The spec stays stable; measured results churn each run — so **all eval/benchmark results live in the dated log [`../eval/RESULTS.md`](../eval/RESULTS.md)**: the baseline, the per-change lifts (the `check` verb, the skill, the T2 transfer), the Sonnet-vs-Opus comparison, and pass@k runs. That log is the moving record; this spec is the fixed method it's measured against.

## 8. The curated corpus & runner (where it lives)

- **`eval/tasks/*.json`** — the curated task set (schema below); each authoring task carries a **kernel-verified gold**. Our own original fixtures/rules (license-safe per `CLAUDE.md`).
- **`eval/README.md`** — the schema, scoring/pass policy, and how to add/change a task.
- **Layer 1 — the oracle (deterministic, CI):** `cli/.../eval/EvalGoldOracleTest` reads the corpus and asserts every gold *validates against the kernel* — authoring/computation golds via `RuleValidator.check` (multi-file tasks with a `ReferenceResolvers.directory` resolver), modify-computation and modify-rule golds via replace-then-bind. Guards that tasks + golds are real; runs on every build.
- **Layer 2 — the agent runner (soft, opt-in):** `eval/benchmark.workflow.js` (✅ runnable, 2026-06-05) — a Workflow that fans out `model × task × k` clean agents over **all five families** (authoring / authoring-computation / modify-computation / modify-rule → a candidate; comprehension → an explanation), then **Recheck** (authoritatively re-validates every candidate — `cli batch` of `check` for rules, `cli add` for computations, `cli modify … --dry-run` for both modify families [rule re-expressions batched, so the DSL needs no shell-quoting] — not the agents' self-report) → **Judge** (a judge agent scores semantic correctness vs the gold/rubric, which catches a kernel-valid-but-inverted rule; every judge carries the type-aware empty-operand kernel fact so a *redundant* presence guard isn't mis-flagged — the cheap patch for the false-positive class. The principled fix, **judge-verify-by-execution**, is now possible: the `eval-doc` verb runs a rule against test documents on the real kernel (the rule judges) and `compute-doc` does the same for computed values (the computation judges), so equivalence can be decided by EXECUTION rather than by the judge recalling kernel semantics — the verbs exist (CLI-SPEC §4); wiring the judges to call them is the next increment) → aggregate pass@k. `pass = kernel-valid AND semantically-right`. Uses the **`batch` verb** (✅ CLI-SPEC §4) for the re-check. Token-heavy → run on demand, never per-commit; the caller passes the `eval/tasks/*.json` contents in via `args` (the script has no filesystem access). Prereq: `./gradlew :cli:installDist`.

### 8a. Cost model — how many agents a run spawns

A run's agent count is dominated by the three phases. With `A` authoring tasks, `C` comprehension tasks, `M` model tiers, and `k` runs per cell:

| Phase | Agents | Why |
|---|---|---|
| **Produce** | `(A + C) × M × k` | One clean agent per (task, model, run) — the fan-out. |
| **Recheck** | `1` (or `0` if no authoring candidates) | A **single** agent batches *all* authored candidates through one `cli batch` (the whole point of the `batch` verb — one warm JVM, not one process per candidate). |
| **Judge** | `Vauth + (C × M × k)` | One judge per **kernel-valid** authoring candidate (`Vauth` ≤ `A × M × k`; an invalid candidate skips its judge — it's already a fail) + one per comprehension run. |
| **Total** | `Produce + Recheck + Judge` | |

The recheck is **flat at 1** by design — that's why scaling `k` or tasks grows produce + judge linearly but never multiplies the re-check cost.

**Worked example — the 2026-06-05 run** (`A=6`, `C=1`, `M=2`, `k=2`):

| Phase | Count | Computation |
|---|--:|---|
| Produce | 28 | `(6 + 1) × 2 × 2` |
| Recheck | 1 | one batch agent |
| Judge | 27 | `23` valid authoring candidates (one `sonnet/T1` run was kernel-invalid → no judge) `+ 4` comprehension `(1 × 2 × 2)` |
| **Total** | **56** | |

The **earlier failed run showed 29** because it aborted in the Recheck phase (the uncaught rescore agent): `28` produce `+ 1` rescore, with **no judges reached**. So `29` = produce + the one failed recheck agent; `56` = the full pipeline once recheck was fixed. (On a *resumed* run the produce agents replay from cache but still count toward the total.)

### 8b. Targeted re-runs — confirm a fix cheaply

Agents cost tokens, so **after fixing a gap a run surfaced, re-run only the affected cells, not the whole suite.** The runner fans out over exactly what you pass, so subsetting is free: pass just the relevant task(s) and model(s) in `args` at a low `k`. Re-running T4 on both tiers, for example, is `(1)×2×2 + 1 + ≤4 ≈ 9` agents vs 56 for the full suite (§8a).

Fresh run or resume? — it depends on what changed:

| Situation | Run type | Why |
|---|---|---|
| **Material changed** (skill / catalog / CLI) | **Fresh** run with the subset `args` | produce must re-run on the *new* material |
| **Recovering an aborted run**, inputs unchanged | **Resume** (`resumeFromRunId`) | cached produce agents replay — no re-spend |
| **Want more samples**, same material | **Fresh**, higher `k` | new draws, not cached ones |

The trap to avoid: resuming after a material change replays the **old** material from cache — silently measuring the thing you just changed away. And run the **full suite only periodically** (a targeted re-run proves *this* fix; the full sweep catches regressions elsewhere). Both land in `eval/RESULTS.md`, labelled targeted vs full.

### 8c. Execution architecture — layers, isolation, and parameterization

The run has **three layers**, each with a deliberate role. Getting the layering right is what keeps the eval *valid* (the right thing is isolated) and the results *usable* (the right thing shares context).

```
MAIN LOOP  (you, via the run-rulekit-eval skill)      — orchestration; shares your context
  · invoke the skill   · ./gradlew :cli:installDist   · bundle tasks → args
  · estimate cost (§8a)   · fold results → eval/RESULTS.md   · resume / cost-track (session-scoped)
  │
  │   Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks } })
  ▼
WORKFLOW  (eval/benchmark.workflow.js)                — fan-out + scoring; ONE allowed level of nesting
  Produce ──► Recheck (1 batch agent) ──► Judge ──► aggregate pass@k
  │
  │   spawns N clean agents — model pinned, neutral prompt, material boundary enforced
  ▼
CLEAN SUBAGENTS  (the instrument)                     — ISOLATED BY DESIGN (a first-time consumer)
  see ONLY: the self-describing CLI launcher (verbs · --help · cli operators) + the model file under test
            (bare-tool mode, §2a — the wired default; production mode would also inject skill/SKILL.md)
  return:  a candidate rule / an explanation, via StructuredOutput
```

**Isolation belongs at exactly one layer — the clean subagents.** A walled-off, first-time consumer is the honest test (§1). **Orchestration must stay in the main loop** because it needs *shared* context: it writes `eval/RESULTS.md`, tracks token cost, and can `resumeFromRunId` (session-scoped). So **do not wrap orchestration in its own sub-agent** — it would (a) hit the nesting limit (Workflow can't nest; a sub-agent spawning sub-agents is unsupported), likely unable to launch the run at all; and (b) re-wall orchestration from your context, so pass@k detail + judge rationales return only as a lossy summary and write-back/resume/cost-tracking break. The skill is a **main-loop** playbook; the Workflow — not a sub-agent — provides the isolation.

**How the run is assembled — who builds what, when.** Two artifacts, different actors and lifetimes:
- The **script** `eval/benchmark.workflow.js` is a *committed, persistent file* — authored ahead of time, edited only when the orchestration logic changes. It never holds tasks or tiers; it reads them from the `args` global.
- The **`Workflow(...)` call is built by the main loop** (you, following the skill) at run time. `scriptPath` just *references* that committed file; `args = {models, k, tasks}` is assembled fresh each run — read `eval/tasks/*.json`, trim each task, choose the tiers and `k` (a selective run includes only the affected task objects). You emit the call; the **harness launches it in the background** and the Workflow runtime executes the script, **injecting `args` as a global** — *that* is what spawns the clean subagents (you don't run the JS). On completion the return value (`{agg, …}`) comes back to the main loop to fold into `RESULTS.md`.

This script-vs-`args` split is deliberate: the script is fixed, `args` varies per run — which is exactly what makes **selective runs** (vary `args`, never the script) and **resume** (same script + same `args` ⇒ cache hit) work.

**What crosses each boundary (data flow):**

| Boundary | Payload |
|---|---|
| Main → Workflow | `args = { models, k, tasks }` (plain JSON) |
| Workflow → clean subagent | a neutral prompt + the path to the shipped material (never source/docs/this spec) |
| Clean subagent → Workflow | `StructuredOutput` — authoring: `{field, condition, code, kernelAccepted}`; comprehension: `{explanations}` |
| Recheck (inside Workflow) | all candidates → **one** agent runs `cli batch` (one warm JVM) → `{verdicts:[{id, valid}]}`; falls back to self-report on failure |
| Judge → Workflow | `{equivalent\|correct, why}` per item |
| Workflow → Main | `{agg, authorJudged, explainJudged}` — you fold `agg` + rationales into `RESULTS.md` |

**Parameterization — the `args` object is the entire control surface:**

| param | meaning | selective-run use |
|---|---|---|
| `models` | tiers to pin, e.g. `["opus","sonnet"]` | pass one tier to re-check just it |
| `k` | runs per (model, task) cell | low `k` for a quick trial; higher for stable pass@k |
| `tasks` | the trimmed task objects (`id/family/model/intent/gold/rules/expected`) | **pass only the affected tasks** → targeted re-run (§8b) |

`tasks` and `models` are the only selectivity knobs — **the runner fans out over exactly what you pass, no filter flag.** An empty authoring *or* comprehension subset just skips that phase; a totally empty `tasks` throws (a guard against silent no-ops). Material changed ⇒ **fresh** run, not resume (§8b). Agent count for any `args` follows the §8a formula.

Task schema (per file):
```json
{ "id": "T1", "family": "authoring", "model": "subscription",
  "operators": ["GroupFilled","Equal","FieldNotFilled","And"],
  "intent": "When a subscription's Tier is PRO, each add-on's MonthlyFee must be provided.",
  "gold": { "field": "/Subscription/Addons/MonthlyFee",
            "condition": "GroupFilled(/Subscription/Addons) And [/Subscription/Tier] == \"PRO\" And FieldNotFilled(MonthlyFee)",
            "code": "PRO_ADDON_FEE_REQUIRED" } }
```

## 9. Status & next

The eval is live and instrumented. **Built:** the 17-task suite (T1–T6 + T9–T12 authoring across operator families, T13–T14 computation authoring, T15 multi-file authoring, T16 computation modify, T17 rule modify, T7–T8 comprehension — each with a deterministic backbone), the `check`/`add`/`batch`/`modify` verbs, the N1 skill, and the Layer-2 pass@k runner (all five families). The **build-measure-learn loop closed end-to-end** on the #1 gap: the eval *found* the silent inverted-polarity trap → the skill *taught* it → the CLI now *catches* it automatically (the `RK_INVERTED_REQUIREDNESS` lint backstop on both `add` and `check`). The **2026-06-06 full run** then drove three more material fixes: the CLI `--field`/`--group`/`--rule` metavar+guard (model-entity-path vs file-path confusion), and two skill notes — the per-row-vs-document-level scope choice (T1) and reading host-delegated custom conditions without guessing (T8). Measured lifts and pass@k are in **[`../eval/RESULTS.md`](../eval/RESULTS.md)**.

**Next:** run the Layer-2 runner on demand to populate pass@k per (model, task); grow the suite as new operator families or gaps surface. (The F10 aggregate-read gap this eval surfaced is now lifted — KERNEL-INTEGRATION-NOTES — so an aggregate rule like T4 round-trips and can serve as a comprehension fixture.)

---

*Term definitions and a plain-language intro: [`EVAL-PRIMER.md`](EVAL-PRIMER.md).*
