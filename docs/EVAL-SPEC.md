# a12-rulekit — self-sufficiency eval & benchmark (N6/N1)

How we measure — and improve — whether rulekit's bundled material lets an LLM author and understand A12 rules **without the kernel's BA docs**. This operationalizes the existing requirement **N6** (semantic self-sufficiency, proposal §14) and drives **N1** (the LLM skill). The proposal states the intent; this spec is the method, the scoring, and the living task suite.

## Primer — start here if LLM evals are new to you

An **LLM eval** is a repeatable test for a language model, the way a unit test is a repeatable test for code: you give the model a task, capture what it produces, and **score** that output against a known-good answer. Re-run it after a change and you can see whether things got better or worse.

Three things make it different from an ordinary unit test, and they shape everything below:

- **The output is open-ended and non-deterministic.** The same prompt can yield different (but equally valid) answers, and "correct" is rarely one exact string. So we need a trusted judge of correctness (an **oracle**) and we measure over **several runs**, not a single pass/fail.
- **You score against a *gold*.** The **gold** (gold answer / gold standard) is the reference correct answer for a task, written and verified by us up front — the answer key. "Does the model's output match the gold?" is the scoring question. *(That's the "gold" you saw near the tests — it's the expected answer, nothing to do with the build.)*
- **We're really testing the material, not the model.** This eval doesn't ask "is the LLM smart?" It asks: given **only what rulekit ships** (the CLI, the operator catalog, the skill), can a fresh LLM author and understand A12 rules? When the model fails, we treat the **shipped material** as the thing to fix — not the model (§1).

In one breath: spawn a **fresh ("clean") LLM** that has never seen this project, hand it **only the shipped material** plus one task, let it work, then **score** its output — the real kernel is the **oracle** for "is this a valid rule?", and a **judge** model decides "does it mean the right thing?". Repeat across tasks and model tiers and report **pass@k** (how often it succeeds within k tries). Bold terms are defined in the [Glossary](#glossary) at the end.

The rest is the full method: §1 the clean-subagent instrument · §2 the material boundary · §3 scoring · §4 the improvement loop · §5 running a probe · §6 the task suite · §7 results so far · §8 where the corpus and runner live.

## 1. The core method — a clean subagent is the instrument

**Self-evaluation is invalid.** The main-loop assistant that built rulekit has internalized the design; if *it* authors a rule, that proves nothing about the *material*. So the LLM-under-test is a **freshly spawned subagent with clean context**, given **only the material under test** + a task. It is a faithful stand-in for "an arbitrary LLM consumer meeting rulekit for the first time."

- **Honest caveat:** the subagent is *Claude-family*, so this measures Claude-family self-sufficiency, not "any LLM." It is still vastly better than self-eval and is what we can run in-loop.
- **Model dimension (pin it).** The subagent's model is a first-class benchmark axis: the `Agent`/`Workflow` `model` param (`opus`/`sonnet`/`haiku`) runs the *same task across tiers* → `model × task → pass@k`, so we can compare e.g. Sonnet vs Opus self-sufficiency. **Always pin the model explicitly** for reproducibility (don't inherit the session model — it drifts). Results are labeled by model; an unpinned baseline run measures the *strongest* tier (an upper bound: if Opus struggles, weaker tiers struggle more).
- **The N6 stance (decisive):** when the agent fails, **the material is incomplete, not the LLM.** Every failure localizes a gap — a missing operator example, an unclear gotcha, an absent verb — which becomes the prioritized worklist for N1.

## 2. The material under test — and the boundary that makes the eval valid

The agent gets exactly the **shipped** material for the persona under test, and **nothing else**:

- **BA/CLI path:** the CLI (verbs + their own usage output) + the bundled operator catalog (`src/main/resources/catalog/operators.json`) + the **N1 skill** (`skill/SKILL.md`, first draft 2026-06-05). The agent uses the `installDist` launcher (the JVM "java starter" — current code, identical behavior to native; the native binary is a stale spike artifact, never the eval vehicle).
- **Developer path:** rulekit-the-library + its javadoc + the catalog, used in a scratch project (the dev's own compiler is the feedback).

**Hard boundary:** the agent must **not** read rulekit's source, `docs/`, READMEs, or tests — only the shipped material. This is what makes the eval measure the *product*, not the design notes. (Reading the model file under test is allowed — it's the input.)

## 3. Task families & scoring

Two families, both with an **objective, deterministic backbone** (the kernel is the oracle):

- **Authoring** — intent (NL) → a rule. Scored: (a) **kernel accepts it** (run the CLI's validate/check — hard pass/fail), and (b) **matches intent** (the rendered condition equals an expected canonical form, or ingest→re-render→compare for semantic equivalence).
- **Comprehension** — a rule → an explanation. Scored: it names the right operators / referenced fields / iteration scope / relevant gotchas (checkable against the model + catalog).

**Scoring policy — a *soft* regression, not a hard CI gate.** The kernel-acceptance sub-check is deterministic, but the *agent's behavior* is non-deterministic and costs tokens. So:

- Run **pass@k** (a few runs per task); track **pass-rate over time** against a **threshold** (e.g. "≥90% of covered operators author-and-validate").
- Run **periodically / on-demand**, not per-commit; alert on drops rather than blocking merges.
- It **composes with N7** (which already runs every catalog example through the kernel, fully deterministic): N7 guards the catalog's truth; this benchmark guards the LLM-in-the-loop usability on top.

## 4. The build-measure-learn loop (run it in-session)

```
draft material (skill + catalog + CLI verbs)
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
| T3 | Authoring | "A subscription's invoice email, when given, must be well-formed" (subscription) — pattern | field `/Subscription/Billing/InvoiceEmail`; condition `FieldFilled(InvoiceEmail) And [InvoiceEmail] PatternViolated "[A-Za-z0-9._-]+@[A-Za-z0-9.-]+"`; code `INVOICE_EMAIL_MALFORMED` | gold verified. Pattern polarity (`PatternViolated`, not `PatternMatched` — matching is the *good* case) + presence guard. |
| T4 | Authoring | "An order's Quantity may not be less than the total count of its RUSH line items" (order) — aggregate + Having | field `/Order/Quantity`; condition `FieldFilled(Quantity) And [Quantity] < Sum(Items*/Count Having [Items/Sku] == "RUSH")`; code `QUANTITY_BELOW_RUSH_ITEMS` | gold verified. A filtered aggregate over a repeatable group vs a non-repeatable field; model-level (non-iterating); error field covered. (Kernel-valid but ingests `Opaque` — F10 gap, KERNEL-INTEGRATION-NOTES.) |
| T5 | Authoring | "An order's delivery date must not fall before its order date" (order) — date | field `/Order/DeliveryDate`; condition `AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0`; code `DELIVERY_BEFORE_ORDER` | gold verified. Date-diff argument order/sign (`DifferenceInDays(A,B)` = B−A) + a multi-field presence guard. |
| T6 | Authoring | "An auto-renewing subscription must be on a paid tier (Pro/Enterprise), never Basic" (subscription) — value-list + boolean | field `/Subscription/Tier`; condition `[AutoRenew] == True And FieldValueNotIncludedInValueList(Tier, "PRO", "ENTERPRISE")`; code `AUTORENEW_REQUIRES_PAID_TIER` | gold verified. The *negative* value-list predicate, the boolean literal `True` (not the string), and enum-by-stored-code. |
| T7 | Comprehension | "Read the two rules on this order model and explain each violation + the field it guards" (`order-ruled` — a date rule + a pattern rule) | rules `/Order/DeliveryNotBeforeOrder`, `/Order/BillingAddress/PostalCodeFormat`; rubric in the task's `expected` | backbone: both rules ingest to `Converted` (readable by `explain`). Read-back of foreign rules: polarity + violated-not-matched pattern semantics + presence guard. |

## 7. Results

The spec stays stable; measured results churn each run — so **all eval/benchmark results live in the dated log [`../eval/RESULTS.md`](../eval/RESULTS.md)**: the baseline, the per-change lifts (the `check` verb, the skill, the T2 transfer), the Sonnet-vs-Opus comparison, and pass@k runs. That log is the moving record; this spec is the fixed method it's measured against.

## 8. The curated corpus & runner (where it lives)

- **`eval/tasks/*.json`** — the curated task set (schema below); each authoring task carries a **kernel-verified gold**. Our own original fixtures/rules (license-safe per `CLAUDE.md`).
- **`eval/README.md`** — the schema, scoring/pass policy, and how to add/change a task.
- **Layer 1 — the oracle (deterministic, CI):** `cli/.../eval/EvalGoldOracleTest` reads the corpus and asserts every gold *validates against the kernel* (via `RuleValidator.check`). Guards that tasks + golds are real; runs on every build.
- **Layer 2 — the agent runner (soft, opt-in):** `eval/benchmark.workflow.js` (✅ runnable, 2026-06-05) — a Workflow that fans out `model × task × k` clean agents over **both** families (authoring → candidate; comprehension → explanation), then **Recheck** (one warm-JVM `cli batch` re-validates every authored candidate authoritatively — not the agents' self-report) → **Judge** (a judge agent scores semantic correctness vs the gold/rubric, which catches a kernel-valid-but-inverted rule) → aggregate pass@k. `pass = kernel-valid AND semantically-right`. Uses the **`batch` verb** (✅ CLI-SPEC §4) for the re-check. Token-heavy → run on demand, never per-commit; the caller passes the `eval/tasks/*.json` contents in via `args` (the script has no filesystem access). Prereq: `./gradlew :cli:installDist`.

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

Two rules keep the cheap re-run honest:
- **Material change ⇒ fresh run, not a resume.** A *resume* replays produce agents from cache — correct only when inputs are unchanged (e.g. recovering an aborted run). When the **material** changed (skill/catalog/CLI), the produce agents must re-run on the *new* material, so start a **fresh** run with the subset `args`.
- **Full suite only periodically.** The targeted re-run proves *this* fix; run the whole suite occasionally to catch regressions elsewhere. Both land in `eval/RESULTS.md`, labelled (targeted vs full).

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

The eval is live and instrumented. **Built:** the 7-task suite (T1–T6 authoring across operator families + T7 comprehension, each with a deterministic backbone), the `check`/`add`/`batch` verbs, the N1 skill, and the Layer-2 pass@k runner. The **build-measure-learn loop closed end-to-end** on the #1 gap: the eval *found* the silent inverted-polarity trap → the skill *taught* it → the CLI now *catches* it automatically (the `RK_INVERTED_REQUIREDNESS` lint backstop on both `add` and `check`). Measured lifts and pass@k are in **[`../eval/RESULTS.md`](../eval/RESULTS.md)**.

**Next:** run the Layer-2 runner on demand to populate pass@k per (model, task); grow the suite as new operator families or gaps surface; lift the F10 aggregate-ingest gap (KERNEL-INTEGRATION-NOTES) so aggregate rules round-trip for comprehension.

## Glossary

Terms used throughout this spec, in plain language. The first group is general LLM-eval vocabulary; the second is shorthand specific to this project.

**General LLM-eval terms**

| Term | What it means here |
|---|---|
| **Eval** | A repeatable test of a model's behaviour on a task — give it a prompt, score its output. Our eval tests the *shipped material*, with the model as a stand-in user. |
| **Benchmark** | The eval run as a *suite* (many tasks) to produce comparable numbers — here, pass-rates per model tier. |
| **Gold** (gold answer, gold standard) | The reference correct answer for a task, authored and kernel-verified by us in advance. Scoring asks "does the model's output match the gold?". Lives in each `eval/tasks/*.json`. |
| **Oracle** | The trusted automatic judge of a *factual* property. Our oracle is the **real A12 kernel**: it says yes/no to "is this rule valid?" deterministically — no model judgement involved. |
| **Judge** | A model used to score a *semantic* property the oracle can't — e.g. "does this rule mean the same as the gold?" (catches a rule that is kernel-valid but says the opposite thing). Softer and non-deterministic, so used in addition to the oracle. |
| **Pass@k** | Run a task **k** times; report how often the model succeeds. Used because a single run is noisy. "pass@k = 2/3" means it passed on 2 of 3 attempts. |
| **Clean context / clean subagent / LLM-under-test** | A freshly spawned model with no memory of this project — the faithful stand-in for "a first-time consumer". The main assistant can't grade itself (it already knows the design), so the eval always uses a clean one. |
| **Probe** | One run of one task by one clean subagent — the atomic unit of the eval. |
| **Deterministic vs. soft scoring** | *Deterministic*: the oracle gives the same verdict every time (kernel accepts / rejects) — safe to gate CI. *Soft*: depends on model judgement or luck across runs (pass@k, judge scores) — tracked as a trend, not a hard gate. |
| **Material boundary (hard boundary)** | The rule that the subagent may read **only** the shipped material (CLI, catalog, skill), never the source/docs/tests. This is what makes a pass measure the *product* rather than the design notes. |
| **Teaching to the test** | The anti-pattern of making the shipped material (e.g. a skill example) too close in shape to a benchmark task — then a "pass" measures copying, not genuine ability. Tasks are kept structurally distinct from skill examples to avoid it. |
| **Lift** | The measured improvement from one change — e.g. "the skill lifted T1 from multi-attempt to first-attempt". The build-measure-learn loop (§4) chases lifts. |
| **Model pinning** | Fixing the subagent's model tier (`opus`/`sonnet`/`haiku`) explicitly, instead of inheriting the session model, so results are reproducible and tiers are comparable. |

**Project-specific shorthand**

| Term | What it means here |
|---|---|
| **Task family** | The kind of task. **Authoring** = intent → write a rule (scored by kernel-accepts + judge). **Comprehension** = existing rule → explain it (scored by ingest-cleanly + judge vs the rubric). |
| **Layer 1 / Layer 2** | *Layer 1* = the deterministic backbone (`EvalGoldOracleTest`: golds validate, comprehension rules ingest) — runs in CI. *Layer 2* = the soft agent runner (`benchmark.workflow.js`: clean agents → pass@k) — opt-in, on demand. |
| **GAPS section** | The required closing section of a probe report: what the agent found missing, confusing, or had to guess. This — not the pass/fail — is the real deliverable, because it names the material to improve. |
| **N6 / N1 / N7** | Proposal requirement codes. **N6** = semantic self-sufficiency (what this eval measures). **N1** = the LLM skill (what it drives). **N7** = the catalog-examples-validate test (the deterministic guard this composes with). |
| **`RK_INVERTED_REQUIREDNESS`** | The lint code for the recurring trap this eval surfaced: a rule written in the wrong *polarity* (fires on the requirement being *met* instead of *violated*). Kernel-valid, so only a judge or the lint catches it. |
