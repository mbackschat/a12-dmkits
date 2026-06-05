# a12-rulekit — eval & benchmark results log

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

### 2026-06-05 05:45 CEST — pass@k run `w35lyr1fd` FAILED (runner bug; recoverable)

First live Layer-2 run (opus + sonnet, k=2, full suite). It **ran** — 29 agents, ~804k tokens, ~515s — but **aborted**: the rescore agent finished without calling `StructuredOutput`, and that `agent()` call wasn't wrapped in a catch, so it failed the whole workflow. Root cause: a runner bug (uncaught rescore), not a material/skill finding. The produce agents' work is cached, so the fix is to harden the runner (catch the rescore; fall back to the agents' self-reported `kernelAccepted`) and **resume from the run id** — the cached produce agents replay, only rescore + judge re-run. pass@k numbers will replace this entry once it completes.

*(An earlier attempt the same session no-opped on empty `args.tasks` — the script now parses string-or-object args and fails loud on an empty task list.)*

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
