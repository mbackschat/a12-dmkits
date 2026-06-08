# a12-dmkits — LLM eval primer & glossary

A gentle on-ramp to the self-sufficiency eval: *the idea* (below) plus a [glossary](#glossary) of terms. The detail lives elsewhere — **method, scoring, task suite, runbook** in [`EVAL-SPEC.md`](EVAL-SPEC.md); **measured results** in [`../eval/RESULTS.md`](../eval/RESULTS.md).

## The idea

An **LLM eval** is a repeatable test for a language model, the way a unit test is a repeatable test for code: you give the model a task, capture what it produces, and **score** that output against a known-good answer. Re-run it after a change and you can see whether things got better or worse.

Three things make it different from an ordinary unit test, and they shape the whole method:

- **The output is open-ended and non-deterministic.** The same prompt can yield different (but equally valid) answers, and "correct" is rarely one exact string. So we need a trusted judge of correctness (an **oracle**) and we measure over **several runs**, not a single pass/fail.
- **You score against a *gold*.** The **gold** (gold answer / gold standard) is the reference correct answer for a task, written and verified by us up front — the answer key. "Does the model's output match the gold?" is the scoring question. *(That's the "gold" you may see near the tests — it's the expected answer, nothing to do with the build.)*
- **We're really testing the material, not the model.** This eval doesn't ask "is the LLM smart?" It asks: given **only what rulekit ships** (the CLI, the operator catalog, the skill), can a fresh LLM author and understand A12 rules? When the model fails, we treat the **shipped material** as the thing to fix — not the model.

In one breath:

```
fresh "clean" LLM (has never seen this project)
  ├─ gets ONLY: the CLI + the operator catalog + the N1 skill + the model file
  └─ gets a task: a business requirement, stated neutrally (no technique hints)
        │
        ▼   produces a rule (authoring)  or  an explanation (comprehension)
        │
   scored by:   KERNEL oracle — is it a valid rule?  (deterministic)
              + JUDGE model   — does it mean the right thing?  (semantic)
        │
        ▼   repeated across   tasks × model tiers × k runs   →   pass@k
```

**Where to go next** — the full method is [`EVAL-SPEC.md`](EVAL-SPEC.md): §1 the clean-subagent instrument · §2 the material boundary · §3 scoring · §4 the improvement loop · §5 running a probe · §6 the task suite · §7 results · §8 the corpus, runner, cost model, and execution architecture. Any bold term above is defined in the glossary below.

## Glossary

Plain-language definitions. The first group is general LLM-eval vocabulary; the second is shorthand specific to this project.

### General LLM-eval terms

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
| **Lift** | The measured improvement from one change — e.g. "the skill lifted T1 from multi-attempt to first-attempt". The build-measure-learn loop chases lifts. |
| **Model pinning** | Fixing the subagent's model tier (`opus`/`sonnet`/`haiku`) explicitly, instead of inheriting the session model, so results are reproducible and tiers are comparable. |

### Project-specific shorthand

| Term | What it means here |
|---|---|
| **Task family** | The kind of task. **Authoring** = intent → write a rule (scored by kernel-accepts + judge). **Comprehension** = existing rule → explain it (scored by read-cleanly + judge vs the rubric). |
| **Layer 1 / Layer 2** | *Layer 1* = the deterministic backbone (`EvalGoldOracleTest`: golds validate, comprehension rules read) — runs in CI. *Layer 2* = the soft agent runner (`benchmark.workflow.js`: clean agents → pass@k) — opt-in, on demand. |
| **GAPS section** | The required closing section of a probe report: what the agent found missing, confusing, or had to guess. This — not the pass/fail — is the real deliverable, because it names the material to improve. |
| **N6 / N1 / N7** | Proposal requirement codes. **N6** = semantic self-sufficiency (what this eval measures). **N1** = the LLM skill (what it drives). **N7** = the catalog-examples-validate test (the deterministic guard this composes with). |
| **`RK_INVERTED_REQUIREDNESS`** | The lint code for the recurring trap this eval surfaced: a rule written in the wrong *polarity* (fires on the requirement being *met* instead of *violated*). Kernel-valid, so only a judge or the lint catches it. |
