# a12-rulekit — self-sufficiency eval & benchmark (N6/N1)

How we measure — and improve — whether rulekit's bundled material lets an LLM author and understand A12 rules **without the kernel's BA docs**. This operationalizes the existing requirement **N6** (semantic self-sufficiency, proposal §14) and drives **N1** (the LLM skill). The proposal states the intent; this spec is the method, the scoring, and the living task suite.

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

Grows as coverage grows; each task carries an objective success criterion. Baselines:

| # | Family | Task | Gold (kernel-verified) | Status |
|---|---|---|---|---|
| T1 | Authoring | "When Tier is PRO, each add-on's MonthlyFee must be provided" (subscription model) | field `/Subscription/Addons/MonthlyFee`; condition `GroupFilled(/Subscription/Addons) And [/Subscription/Tier] == "PRO" And FieldNotFilled(MonthlyFee)`; code `PRO_ADDON_FEE_REQUIRED` | ✅ gold verified; agent pass — no-skill: multi-attempt/medium-confidence; **+skill: first-attempt/high-confidence** (Opus 4.8, 2026-06-05). *Caveat: T1 ≈ the skill's worked example → measures transfer, not novel composition.* |
| T2 | Authoring | "A subscription's effective fee must never be lower than its base fee" (subscription model) | field `/Subscription/Billing/EffectiveFee`; condition `[EffectiveFee] < [BaseFee]`; code `EFFECTIVE_FEE_BELOW_BASE` | ✅ gold verified; **+skill: first-attempt/high-confidence (genuine transfer** — structurally unlike the skill example: non-iterating, field-vs-field comparison, no guard; Opus 4.8, 2026-06-05) |

## 7. Baseline result — first probe (2026-06-05, task T1)

A clean subagent given only the CLI (`dump`/`describe`/`validate`) + the catalog, forbidden source/docs.

**Strengths (material works):** the **catalog is a usable operator reference** — the agent picked operators and composed a *correct* condition with the right **polarity** (error-scenario inversion) and the **enum-stored-value** rule (`"PRO"` not the label). It even flagged the `MVK_NEG_CONDITION_IN_ITERATION` trap as an untested risk.

**Gaps (the worklist):**
1. **DECISIVE — no author-and-validate affordance.** The CLI was read-only; `validate` only re-checks rules *already in* the model, so the agent could not confirm its rule (the task's success criterion was unreachable). It independently designed the fix (`check <model> --field --code --condition`). → built as the `check` verb (wraps core `RuleValidator.check`).
2. **Skill-content gaps:** the catalog covers the *condition expression* but not the **rule envelope** (error field/code), the **bracket-vs-bare path convention**, the **iteration-via-error-field-placement** semantics (most business-critical, absent), and there's **no worked end-to-end example** (model ships with 0 rules).
3. **CLI defect:** `validate` **silently ignored extra args** → hardened to error on unknown args.

**Caveat on the strengths:** the prompt primed the inversion (§5 protocol lesson), so the polarity success is partly attributable to the prompt, not the material — re-measured below.

### T1 re-run — the lift after the `check` verb (2026-06-05, neutral prompt, Opus 4.8 clean)

**Decisive improvement.** With `check` present, the agent went from *"could not validate at all"* to **authoring a rule the kernel accepts**, via a real **reject→fix→accept loop**: attempt 1 → `MVK_NEG_CONDITION_IN_ITERATION` → used the *catalog gotcha* (which names the guard idiom) → attempt 2 → `MVK_INVALID_ENTITY` → probed the path rules → **accepted** (`{valid:true}`). The diagnostics-driven self-correction loop works as the F4a/catalog thesis predicted, and the kernel error messages were rated genuinely helpful. Gold answer captured (T1, §6).

**The sharpened #1 gap — silent inverted-polarity.** With a *neutral* prompt the agent still inferred the right polarity, but flagged the real trap: nothing states "the condition is true *on a violation*." A BA would naturally write `FieldFilled(MonthlyFee)` (the requirement as a positive assertion); the CLI returns `valid:true` — but that encodes the **inverted** rule (fires when the fee *is* present). **The tool reports success for a semantically wrong rule.** Kernel investigation (2026-06-05, KERNEL-FINDINGS) settled that **polarity is fixed — not configurable**: `errorCondition` always fires on TRUE, there's no `Not` operator (only paired positive/negative predicates), so the trap can't be designed away at the kernel. Mitigation is rulekit's: **(a)** the skill must teach "state the requirement as its violation, via the negative-form predicate"; **(b)** the `RK_INVERTED_REQUIREDNESS` lint flags likely inversion. It's **AST-based**, so it needs a structured rule — but rather than restrict that to `add`, the shared `LintBackstop` gives the raw-text `check` a route too: it temporarily injects the candidate (persist with placeholder messages → load → ingest → AST) and merges the lint into `check`'s diagnostics (resolved 2026-06-05, ✅ on both `add` and `check`). So the trap is now caught at *both* BA entry points, not only the structured one. Remaining gaps: path-reference syntax (relative-to-iteration-group vs absolute `[/...]`, bracket rules), iteration-is-implicit (error-field-placement drives it), and no "would this fire on sample data?" affordance (that's the Groovy *execution* path — the native-image wall — so a heavier future verb).

## 8. The curated corpus & runner (where it lives)

- **`eval/tasks/*.json`** — the curated task set (schema below); each authoring task carries a **kernel-verified gold**. Our own original fixtures/rules (license-safe per `CLAUDE.md`).
- **`eval/README.md`** — the schema, scoring/pass policy, and how to add/change a task.
- **Layer 1 — the oracle (deterministic, CI):** `cli/.../eval/EvalGoldOracleTest` reads the corpus and asserts every gold *validates against the kernel* (via `RuleValidator.check`). Guards that tasks + golds are real; runs on every build.
- **Layer 2 — the agent runner (soft, opt-in):** `eval/benchmark.workflow.js` (draft) — a Workflow that fans out `model × task × k` clean agents, scores each via the CLI, aggregates pass@k. **Depends on the `batch` CLI verb** (build it with this runner — amortizes JVM startup + kernel warm-up across the many checks; CLI-SPEC §4).

Task schema (per file):
```json
{ "id": "T1", "family": "authoring", "model": "subscription",
  "operators": ["GroupFilled","Equal","FieldNotFilled","And"],
  "intent": "When a subscription's Tier is PRO, each add-on's MonthlyFee must be provided.",
  "gold": { "field": "/Subscription/Addons/MonthlyFee",
            "condition": "GroupFilled(/Subscription/Addons) And [/Subscription/Tier] == \"PRO\" And FieldNotFilled(MonthlyFee)",
            "code": "PRO_ADDON_FEE_REQUIRED" } }
```

### T1 with the skill — the skill lift (2026-06-05, neutral prompt, Opus 4.8 clean)

Adding `skill/SKILL.md` to the material moved T1 from *multi-attempt / medium-confidence* (check-only) to **first-attempt / high-confidence**: the agent got the polarity right by construction (citing the skill's polarity table — *"without it I would almost certainly have written the opposite rule `FieldFilled(...)`"*), pre-empted `MVK_NEG_CONDITION_IN_ITERATION` with the guard, and ran a negative-control to confirm the guard is load-bearing. The #1 silent-inversion gap is closed by the skill. **Caveat (above, §5):** T1 ≈ the skill's worked example, so this is *transfer*, not novel composition — needs structurally-distinct T2 to be a strong claim. Minor new findings: the catalog is large JSON keyed by `id`/`keywords` (hard to skim raw — a future `cli operators <search>` or the skill naming operators mitigates); shell-quoting embedded `"…"` in `--condition` is friction (a `--condition-file`/stdin or the JSON rule-spec avoids it).

### T2 — genuine transfer (2026-06-05, +skill, Opus 4.8 clean)

T2 is *structurally unlike* the skill's worked example (no enum, no repeatable group, no guard; a field-vs-field numeric comparison). The skill-equipped agent passed **first-attempt, high confidence**, transferring the *principles*: the polarity flip (requirement `>=` → violation `<`), the bracket form, field-vs-field comparison, and — correctly — **skipping** the guard after `describe` showed the group is non-repeatable. So the skill teaches principles, not a template (answers the T1 caveat). Two cheap gaps found and **closed in the skill the same session** (build-measure-learn): field-vs-field comparison was never demonstrated (only field-vs-literal), and strict-vs-inclusive wording (`<` vs `<=`) had no guidance. Re-confirmed limitation: `check` accepts *both* polarities, so polarity correctness rests on the skill's reasoning, not the kernel — the standing argument for the inverted-requiredness lint via a structured `add`.

### Model comparison — Sonnet matches Opus (2026-06-05, +skill)

Re-ran both T1 and T2 skill-equipped on **Sonnet** (pinned). Result: **first-attempt / high-confidence on both — identical to Opus.** So the skill's value is **not tier-dependent**: a cheaper model (the realistic BA case) reaches the same correct, kernel-accepted rule. The build-measure-learn loop also showed through — Sonnet-T2 explicitly used the field-vs-field + strict/inclusive guidance added to the skill earlier the same session ("exactly covers this case — no inference required"). Re-confirmed gap (Sonnet-T2): the BA path stops at `check` (validate a candidate); there's no way to *persist* the rule into the model — the `add`/JSON-rule-spec gap. *(Caveat: n=1 per cell; the Workflow runner will give pass@k across more tasks/tiers.)*

## 9. Status & next

The loop is live; the measured results (§7): `check` (couldn't-validate → author-and-validate), the skill (multi-attempt → first-attempt, polarity fixed), T2 (genuine transfer to a novel shape), and **Sonnet == Opus with the skill** (the skill lifts the cheaper tier too). **Done since:** the **`add` verb + JSON rule-spec** — the BA loop now closes end-to-end (check → add → saved model), with message/locale-coverage enforced by re-validating the persisted model. Next: **(1)** more **structurally-distinct** tasks spanning operator families + a rules-bearing comprehension fixture; **(2)** the **`batch` verb + Layer-2 Workflow runner** for `model × task × k` pass@k. **Lint backstop — ✅ on `add` and `check` (2026-06-05):** the shared `LintBackstop` ingests a rule's condition (F10) → AST → `Linter.lint(model, errorField, condition)` and warns on **`RK_INVERTED_REQUIREDNESS`** — `add` on the persisted rule (stderr), `check` via a temporary in-memory injection (merged into its diagnostics). So the #1 silent-polarity gap is now caught *automatically* at **both** BA entry points, not only taught by the skill. The full loop closed: eval **found** the trap → skill **taught** it → the CLI now **catches** it.
