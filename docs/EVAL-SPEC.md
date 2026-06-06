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

- **BA/CLI path:** the **self-describing CLI** — its verbs plus their `--help`/`manifest`/`schema` output and the operator catalog reached through **`cli operators`** (since 2026-06-05; no separate `operators.json` hand-off) — and the **N1 skill** (`skill/SKILL.md`). The agent uses the `installDist` launcher (the JVM "java starter" — current code, identical behavior to native; the native binary is a stale spike artifact, never the eval vehicle). *(The bare-tool benchmark workflow withholds even the skill — it measures whether the self-describing CLI **alone** suffices.)*
- **Developer path:** rulekit-the-library's **public API + javadoc** + the catalog, with the **compiler + lint (`RuleDiagnostics`)** as the feedback (N10 — no bespoke vehicle). *Harness:* a clean agent in a **git worktree** authors a rule via the typed builder and validates with `RuleValidator`, iterating against `javac`/gradle. (First probe 2026-06-05 — see [`../eval/RESULTS.md`](../eval/RESULTS.md); harness distinct from the CLI Workflow runner.)

**Hard boundary — per persona** (no design notes, no answer keys; that's what makes the eval measure the *product*):
- **BA/CLI:** only the CLI (which self-describes its operator catalog via `cli operators`) + the N1 skill — **not** rulekit's source, `docs/`, or tests.
- **Developer:** the public API **source + javadoc** + catalog + `README` *are* the material (that's what a library user sees) — but `skill/`, `docs/` (design notes), and **`eval/` (the golds)** are off-limits.

Either way, reading the model file under test is allowed — it's the input.

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
| T4 | Authoring | "An order's Quantity must be at least the total quantity across its RUSH line items (sum of each line's `Count`)" (order) — aggregate + Having | field `/Order/Quantity`; condition `FieldFilled(Quantity) And [Quantity] < Sum(Items*/Count Having [Items/Sku] == "RUSH")`; code `QUANTITY_BELOW_RUSH_ITEMS` | gold verified. A filtered aggregate over a repeatable group vs a non-repeatable field; model-level (non-iterating); error field covered. Intent reworded to say *sum each line's Count* (the earlier "total count of line items" read as counting rows — RESULTS 2026-06-05). Now also **ingests cleanly** (field-vs-aggregate round-trips since 2026-06-05). |
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
  see ONLY: the self-describing CLI launcher (verbs · --help · cli operators) + the N1 skill + the model file under test
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

The eval is live and instrumented. **Built:** the 7-task suite (T1–T6 authoring across operator families + T7 comprehension, each with a deterministic backbone), the `check`/`add`/`batch` verbs, the N1 skill, and the Layer-2 pass@k runner. The **build-measure-learn loop closed end-to-end** on the #1 gap: the eval *found* the silent inverted-polarity trap → the skill *taught* it → the CLI now *catches* it automatically (the `RK_INVERTED_REQUIREDNESS` lint backstop on both `add` and `check`). Measured lifts and pass@k are in **[`../eval/RESULTS.md`](../eval/RESULTS.md)**.

**Next:** run the Layer-2 runner on demand to populate pass@k per (model, task); grow the suite as new operator families or gaps surface. (The F10 aggregate-ingest gap this eval surfaced is now lifted — KERNEL-INTEGRATION-NOTES — so an aggregate rule like T4 round-trips and can serve as a comprehension fixture.)

---

*Term definitions and a plain-language intro: [`EVAL-PRIMER.md`](EVAL-PRIMER.md).*
