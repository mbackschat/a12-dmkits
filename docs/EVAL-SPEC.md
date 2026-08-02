# a12-dmkits — self-sufficiency eval & benchmark (N6/N1)

How we measure — and improve — whether **dmtool's** bundled material (its self-describing CLI surface) lets an LLM author and understand A12 models — rules, computations, and structure — **without the kernel's BA docs**. This operationalizes the existing requirement **N6** (semantic self-sufficiency, PROJECT-DESIGN §14) and drives **N1** (the LLM skill). The proposal states the intent; this spec is the method, the scoring, and the living task suite.

> **New to LLM evals?** The [primer & glossary](EVAL-PRIMER.md) explains the idea in plain terms and defines the vocabulary (eval, gold, oracle, judge, pass@k, …). This document is the method, scoring, task suite, and runbook.

## 1. The core method — a clean subagent is the instrument

**Self-evaluation is invalid.** The main-loop assistant that built dmkits has internalized the design; if *it* authors a rule, that proves nothing about the *material*. So the LLM-under-test is a **freshly spawned subagent with clean context**, given **only the material under test** + a task. It is a faithful stand-in for "an arbitrary LLM consumer meeting dmtool for the first time."

- **Honest caveat:** the subagent is *Claude-family*, so this measures Claude-family self-sufficiency, not "any LLM." It is still vastly better than self-eval and is what we can run in-loop.
- **Model dimension (pin it).** The subagent's model is a first-class benchmark axis: the `Agent`/`Workflow` `model` param (`opus`/`sonnet`/`haiku`) runs the *same task across tiers* → `model × task → pass@k`, so we can compare e.g. Sonnet vs Opus self-sufficiency. **Always pin the model explicitly** for reproducibility (don't inherit the session model — it drifts). Results are labeled by model; an unpinned baseline run measures the *strongest* tier (an upper bound: if Opus struggles, weaker tiers struggle more).
- **The N6 stance (decisive):** when the agent fails, **the material is incomplete, not the LLM.** Every failure localizes a gap — a missing operator example, an unclear gotcha, an absent verb — which becomes the prioritized worklist for N1.

## 2. The material under test — and the boundary that makes the eval valid

The agent gets exactly the **shipped** material for the persona under test, and **nothing else**:

- **BA/CLI path:** the **self-describing CLI** — its verbs plus their `--help`/`manifest`/`schema` output and the operator catalog reached through **`cli operators`** (since 2026-06-05; no separate `operators.json` hand-off) — and, *in production*, the **N1 skill** (`skill/SKILL.md`). The agent uses the `installDist` launcher (the JVM "java starter" — current code, identical behavior to native; the native binary is a stale spike artifact, never the eval vehicle — the probe rebuilds it fresh, see [`PROBE-SPEC.md`](PROBE-SPEC.md)). Whether the skill is included is the **run-mode** distinction in §2a.
- **Developer path:** dmkits-the-library's **public API + javadoc** + the catalog, with the **compiler + lint (`RuleDiagnostics`)** as the feedback (N10 — no bespoke vehicle). *Harness:* a clean agent in a **git worktree** authors a rule via the typed builder and validates with `RuleValidator`, iterating against `javac`/gradle. (First probe 2026-06-05 — see [`../eval/RESULTS.md`](../eval/RESULTS.md); harness distinct from the CLI Workflow runner.)

**Hard boundary — per persona** (no design notes, no answer keys; that's what makes the eval measure the *product*):
- **BA/CLI:** only the CLI (which self-describes its operator catalog via `cli operators`) + the task's model and, where applicable, its inline Document instance or proposed computation + (in production mode) the N1 skill — **not** dmkits' source, `docs/`, tests, or task/gold files.
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

`benchmark.workflow.js` has **always** been bare-tool by default: from its first commit the produce prompts say *"Do NOT read any … `*.md` files"*, and the script never referenced `SKILL.md`. **Production mode is opt-in (wired 2026-06-08; `skillPath` added 2026-06-09),** and gives **every** producer family (authoring, computation authoring/preflight, both modify families, comprehension, computation-comprehension, runtime, and the modelkit/mixed families) the skill, two ways:

- **`args.skillPath`** (PREFERRED, 2026-06-09) — a repo-relative path; the producer prompt tells the agent to **read that file itself** (it already runs in the repo with shell access). The **verbatim** skill thus needs no inline transcription by the main loop (the script has no filesystem access) and can't drift from the committed file. The prompt names that file as the **only** extra material the agent may read.
- **`args.skill`** — the skill's CONTENTS inlined (the main loop pastes them). Kept for inlining callers; for a *definitive* run prefer `skillPath` so "verbatim" is trustworthy.

With neither, the run is bare-tool exactly as before. The skill goes to **producers only** — judges stay skill-free (they score against the gold).

**Mandatory boundary audit (production runs).** `skillPath` lets a producer agent read a file, so the run is only valid if the agents read **nothing but the skill**. After a production run, grep the producer transcripts (`…/subagents/workflows/wf_…/agent-*.jsonl`) for Read-tool `file_path` + bash read commands; the only files read may be `skill/SKILL.md`, the model `.dm.json`, and the agents' own `/tmp` docs — any `docs/` · `src/` · `*Test` · other `*.md` · `eval/tasks` read is a boundary breach that **invalidates the run**. (The grep + interpretation live in the `run-dmtool-eval` skill, checklist step 7.)

**Bare-tool stays the default and the only mode CI / the historical trend line use** — a production number is labelled separately and is not comparable to the bare-tool series.

**Decision (2026-06-07, revisited 2026-06-08): bare-tool is the default instrument; production mode is now wired but stays opt-in.** Bare-tool is not a compromise — it is the instrument we *want*, for four reasons:

1. **It enforces the self-explaining requirement.** CLAUDE.md's standing rule is to favor a self-explaining CLI over relying on the skill. Bare-tool is the forcing function: a miss a cold agent makes from the CLI alone is a **CLI/catalog defect to fix** (a missing `--help` detail, an operator without a usable example), not something to paper over by handing it the skill. A skill-equipped run would *mask* exactly the gaps we most want surfaced.
2. **It measures the floor, honestly.** A real consumer may or may not have the skill loaded; bare-tool is the worst-case "just the binary" baseline. If that passes, the skill is pure upside.
3. **It splits the worklist cleanly** (the two bullets above): a miss the skill would fix → a *skill* (N1) item; a miss about an undiscoverable verb/param/operator → a *CLI/catalog* item. A skill-equipped run can't make that distinction.
4. **Comparability + simplicity:** every historical run is bare-tool, so the trend line stays intact, and there's no skill-injection plumbing to maintain.

The accepted tradeoff: the *default* (bare-tool) benchmark **does not measure the skill notes** (e.g. the T1/T8 ones stay backed by reasoning + the early manual probes unless a production run is requested). That's deliberate — we treat the minimal skill as judgment-layer *insurance* (polarity, the traps), not something needing continuous pass@k. Production mode (now wired) exists for the **one-off question** "does the skill close a specific gap?" — run it on demand, label it as production, and keep it out of the bare-tool trend line; it is not part of CI or the per-commit story.

## 3. Task families & scoring

The families, grouped by scoring shape (the authoring/modify variants — including self-correction, which rides the authoring path — share one shape; comprehension and its computation twin are its mirror; the modelkit/mixed families are deterministically scored, §8d B), each with an **objective, deterministic backbone** (the kernel is the oracle):

- **Authoring** — intent (NL) → a rule. Scored: (a) **kernel accepts it** (run the CLI's validate/check — hard pass/fail), and (b) **matches intent** (the rendered condition equals an expected canonical form, or read→re-render→compare for semantic equivalence).
- **Authoring-computation** — intent (NL) → a *computation* (computed field + alternatives). Scored the same way, but the kernel gate is `cli add` (which validates before persisting) and the judge compares **computation** equivalence (same value per document).
- **Modify-computation** — an *existing* computation + a change intent → a re-expressed computation, keeping the same computed field. The kernel gate is `cli modify --computation … --dry-run` (replace-then-bind, read-only), which also reports `changed`; a modify passes only when the kernel **accepts** it **and** it is an actual **change** (changed:true), then the judge scores equivalence to the gold modification.
- **Modify-rule** — the rule-side counterpart: an *existing* rule + a change intent → a re-expressed **condition**, keeping the same rule (same error field). The kernel gate is `cli modify --rule … --condition … --dry-run` (replace-then-bind, read-only; the runner batches these so the DSL condition needs no shell-quoting), same accepted-AND-changed pass rule, and the judge scores condition equivalence (polarity included) to the gold.
- **Comprehension** — a rule → an explanation. Scored: it names the right operators / referenced fields / iteration scope / relevant gotchas (checkable against the model + catalog).
- **Computation-comprehension** — the comprehension mirror for *computations*: a banded computation → a plain-language explanation. Scored (LLM judge, rubric): the NL gets (a) the **computed value/field**, (b) the **when** — the `commonPrecondition` guard, and (c) the **bands** — each alternative's `precondition → operation` — right, with no inversion of direction. Deterministic backbone: the named computation reads to `Converted` and `computation explain` surfaces its `commonPrecondition` + non-empty `computationAlternatives`.
- **Structure** — intent (NL) → a structure edit (a field/group/typedef/include add, or a safe-delete). Scored: the kernel re-binds (the structure is valid) **and** the edit matches intent; the delete-safety variant is scored on handling the `refused` diagnostic correctly (cascade vs surface-and-stop, by the intent).
- **Metadata** — intent (NL) → a description / annotation / model-comment edit on a named element (the cross-cutting `meta <ref>` verb, or `config modify --comment`). Scored (both layers): the gold metadata edit re-binds kernel-valid **and** the value reads back; covers descriptions/annotations on field/group/rule/computation + type-def by id, and the model comment. **Layer-2 agentic + deterministically scored** (§8d B).
- **Template** — intent (NL) → a `usageType` group template (§3.8): `group multiselect` or `group attachment`. Scored (both layers): expanding the template re-binds kernel-valid (the kernel's `MultiSelect`/`AttachmentGroupRule` accept the canonical composition — the strongest proof for attachment, whose 8 fields + 4 mandated rules must be exact) **and** the group exists. **Layer-2 agentic + deterministically scored** (§8d B).
- **Session** — a multi-step intent → an `apply` op-list applied in one session. Scored: the model re-binds after all ops **and** every intended element is present; the batch-repair variant seeds one wrong op and scores recovery from the rolled-back, per-op-attributed envelope.
- **Runtime** — an inline canonical Document instance + business intent → discovery and execution of the appropriate live instance verb (`model compute`, `model eval`, or `rule eval`) and its human-readable projection. `EvalSuiteLedgerTest` derives this denominator from every manifest verb carrying `--instance`; `EvalGoldOracleTest` executes both machine and text modes and locks the exact facts. The clean agent's structured observation is scored directly, with no second judge.
- **Computation-preflight** — an inline proposal + the neutral intent “test whether this proposed computation would be accepted without modifying the model” → discovery of the read-only computation-add preview. The task and producer prompt are both guarded against leaking the option name; the deterministic oracle proves acceptance and byte-identical input, and the clean agent's structured observation is scored directly.

**Self-correction (a cross-cutting dimension — #1 priority).** A task may be **seeded to fail recoverably** on the obvious first attempt (a near-miss field name, an invertible polarity, an ISO date literal the kernel lexes as a string). It is scored not on first-try success but on whether the agent **recovers using the diagnostic's `fix`** within k turns (error → applied fix → green) — the direct measure of "good diagnostics let the agent correct its mistakes."

**Wired into the runner (2026-06-17): self-correction rides the authoring path.** The recovery loop happens *inside* the produce agent — it has the read-only `check` verb, hits the seeded error, reads the diagnostic's `fix`, and retries until kernel-valid — so the runner needs no separate stage: the family's pass criterion is **identical to authoring** (final rule kernel-valid AND gold-equivalent). `benchmark.workflow.js` folds the `self-correction` tasks into `authorRuns` (same `authorPrompt`, same warm-JVM `cli batch` recheck, same judge-by-execution against the gold). A self-correction failure therefore means the agent could *not* recover from the bare CLI alone — a CLI/catalog **diagnostic-quality** signal (does the `fix` text name the real remedy?), which is exactly what the family is for. The recovery *narrative* (did it error first, then fix?) isn't in the pass/fail number; read the produce transcript for it. The seed is `rulekit/19-self-correct-date-rule` (the ISO-vs-German-date trap).

A task may also be **multi-file**: it carries a `modelPath` (a nested layout) and an `includeDir`, and the runner threads `-w/--workspace` through every verb (check/describe/modify) so the model's `include`d/typedef models resolve (F-RES). Such a task forces the agent to discover `-w/--workspace` from the self-describing CLI — the multi-file model cannot expand without it.

**Scoring policy — a *soft* regression, not a hard CI gate.** The kernel-acceptance sub-check is deterministic, but the *agent's behavior* is non-deterministic and costs tokens. So:

- Run **pass@k** (a few runs per task); track **pass-rate over time** against a **threshold** (e.g. "≥90% of covered operators author-and-validate").
- Run **periodically / on-demand**, not per-commit; alert on drops rather than blocking merges.
- It **composes with N7** (which already runs every catalog example through the kernel, fully deterministic): N7 guards the catalog's truth; this benchmark guards the LLM-in-the-loop usability on top.

**Notation — our "pass@k" deviates from the literature (defined once, in the [primer](EVAL-PRIMER.md#glossary), referenced everywhere).** The literature's **pass@k** (Chen et al. 2021, *HumanEval*) is *P(at least one of k samples correct)* — a task is solved if **any** of *k* attempts pass. **Ours is a stricter pass-rate:** per `(model, task)` cell we run *k* probes and report **how many pass** (`2/3` = 2 of 3 runs passed); a cell is **"clean" only when all *k* pass** (`k/k`), and a run aggregates as **"*X/N* clean (*R*/total runs)"**. We measure **reliability** (does the material *repeatably* yield a correct rule), not best-of-*k* — so "right once in three" is **not** a solve for us, where the literature's metric would count it. `pass` (per probe) `= kernel-valid AND semantically-right` (the judge). Full rationale + the side-by-side: the primer's ⚠️ pass@k note.

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

> **Term:** here a *probe* means **a single eval attempt** (one clean agent, one task, one run — the pass@k unit). This is **not** the **probe harness** ([`PROBE-SPEC.md`](PROBE-SPEC.md), `probe/`), which is the separate DX instrument over the shipped product. Same word, different things.

> **Operationally, run via the `run-dmtool-eval` skill** (`.claude/skills/`) — it loads these guardrails at point-of-use so they aren't run from memory. This section is the rationale behind them.

- Spawn a **fresh agent** (clean context; the `Agent` tool does this). For the suite at scale, a **Workflow** fans out tasks × k runs through clean agents, each scored via the CLI, aggregating pass@k (opt-in — it spawns many agents).
- The prompt **must not leak the answer.** *(Protocol lesson from the 2026-06-05 baseline: the first probe prompt hinted at the error-scenario inversion — "think about when the condition is true" — which biases the result. Keep prompts neutral; state the business requirement and the constraints, not the technique.)*
- **No teaching to the test.** The skill's worked examples must be *structurally distinct* from the benchmark tasks, or a pass measures copying, not self-sufficiency. *(Lesson: the skill's worked example (Order/EXPRESS/DeliveryDate) is near-identical in shape to T1 (Subscription/PRO/MonthlyFee), so the T1-with-skill pass measures pattern-substitution transfer, not novel composition — add T2 tasks whose shape no taught example covers.)*
- State the hard boundary (§2: no source/docs).
- Require a structured report ending in an explicit **GAPS** section (what was missing/confusing/guessed) — that section is the actual deliverable.

## 6. The task suite (living)

The suite grows as coverage grows — **but the inventory lives next to the corpus, not in this spec.** It rotted here once (frozen at "18 tasks" while the corpus kept growing), which is the lesson: a stable method spec must not carry a churny per-task table (the live count lives in [`../eval/TASKS.md`](../eval/TASKS.md)). This spec keeps only the *stable method* — the families' scoring shapes (§2/§3) and their deterministic backbones (§8d). For everything task-specific:

- **the per-task catalog** — every task's intent + what it exercises (the rulekit operator-coverage map + the modelkit/mixed families) → **[`../eval/TASKS.md`](../eval/TASKS.md)**
- **the task schema + per-family gold shapes + how to add a task** → **[`../eval/README.md`](../eval/README.md)**
- **the authoritative, always-current list** → the corpus files themselves, **`eval/tasks/<kit>/*.json`**
- **measured pass@k / lifts per (model, task)** → **[`../eval/RESULTS.md`](../eval/RESULTS.md)**

## 7. Results

The spec stays stable; measured results churn each run — so **all eval/benchmark results live in the dated log [`../eval/RESULTS.md`](../eval/RESULTS.md)**: the baseline, the per-change lifts (the `check` verb, the skill, the T2 transfer), the Sonnet-vs-Opus comparison, and pass@k runs. That log is the moving record; this spec is the fixed method it's measured against.

## 8. The curated corpus & runner (where it lives)

- **`eval/tasks/<kit>/*.json`** (`<kit>` = `rulekit`/`modelkit`/`mixed`; the folder is the kit — §8d) — the curated task set (schema below); each authoring task carries a **kernel-verified gold**. Our own original fixtures/rules (license-safe per `CLAUDE.md`).
- **`eval/README.md`** — the schema, scoring/pass policy, and how to add/change a task.
- **Layer 1 — the oracle (deterministic, CI):** `cli/.../eval/EvalGoldOracleTest` reads the corpus and asserts every gold *validates against the kernel* — authoring/computation golds via `RuleValidator.check` (multi-file tasks with a `ReferenceResolvers.directory` resolver), modify-computation and modify-rule golds via replace-then-bind. Guards that tasks + golds are real; runs on every build.
- **Layer 2 — the agent runner (soft, opt-in):** `eval/benchmark.workflow.js` (✅ runnable, 2026-06-05) — a Workflow that fans out `model × task × k` clean agents over the **seven LLM-judged rulekit families** (authoring / self-correction [rides the authoring path — §3] / authoring-computation / modify-computation / modify-rule → a candidate; comprehension / computation-comprehension → an explanation) **plus the six deterministically-scored modelkit/mixed families** (structure · session · delete-safety · metadata · template · refactor — no LLM judge; §8d B) **and the LLM-judged `workspace-roles` family** (R4 — the agent audits a workspace's access control vs the task rubric; its deterministic backbone is the `EvalGoldOracleTest` lint-to-`expectedCodes` check), then **Recheck** (authoritatively re-validates every candidate — `cli batch` of `check` for rules, `cli add` for computations, `cli modify … --dry-run` for both modify families [rule re-expressions batched, so the DSL needs no shell-quoting] — not the agents' self-report) → **Judge** (a judge agent scores semantic correctness vs the gold/rubric, which catches a kernel-valid-but-inverted rule; every judge carries the type-aware empty-operand kernel fact so a *redundant* presence guard isn't mis-flagged — the cheap patch for the false-positive class. As of WI-5b the judge also **consults the machine-readable facet block** the tool serves — `cli operators <id>`'s `semantics` (`emptyOperand`, `boundary` STRICT/INCLUSIVE so `< k` ≢ `<= k`, `aggregateIdentity` the all-empty fold, `polarity`) is the judge's **ground-truth oracle** for a per-operator semantic (CATALOG-SPEC §8; the served surface is guarded deterministically by `OperatorsVerbTest`). The principled fix, **judge-verify-by-execution**, is now wired for the **rule judges** (authoring + modify-rule): they construct discriminating documents and run `model eval --condition … --field … --instance …` for the gold *and* the candidate, comparing `rule.fired` — deciding equivalence by EXECUTION on the real kernel rather than by recalling empty-operand/polarity semantics (preferred whenever equivalence hinges on such a semantic). The **computation judges** keep the type-aware empty-operand reasoning for now; first-class comp verify-by-execution awaits a `model compute --spec` candidate path (follow-up — today `model compute` runs a model's existing computations, not an injected candidate)) → aggregate pass@k. `pass = kernel-valid AND semantically-right`. Uses the **`batch` verb** (✅ CLI-SPEC §4) for the re-check. Token-heavy → run on demand, never per-commit; the caller passes the `eval/tasks/*/*.json` contents in via `args` (the script has no filesystem access). Prereq: `./gradlew :cli:installDist`.
- **Layer 2 — the agent runner (soft, opt-in):** `eval/benchmark.workflow.js` (✅ runnable, 2026-06-05) — a Workflow that fans out `model × task × k` clean agents over the **eight LLM-judged families** (authoring · self-correction · authoring-computation · modify-computation · modify-rule · comprehension · computation-comprehension · workspace-roles), the **six executor-scored structural families** (structure · session · delete-safety · metadata · template · refactor), and the **two single-agent discovery families** (runtime · computation-preflight). Recheck authoritatively validates authored candidates; a separate judge handles semantic equivalence where judgment is required; structural scorers execute and report facts; discovery observations compare directly with deterministic expected facts. Token-heavy → run on demand, never per-commit; the caller passes task contents via `args` because the workflow has no filesystem access. Prerequisite: `./build.sh :cli:installDist`.

### 8a. Cost model — how many agents a run spawns

A run's agent count is dominated by the phases. Let `T₂` be tasks that require a second judge/executor agent, `T₁` the directly scored discovery tasks (`runtime` and `computation-preflight`), `M` model tiers, and `k` runs per cell:

| Phase | Agents | Why |
|---|---|---|
| **Prep** | `1` | One agent VERIFIES (read-only) the sandbox the operator built with `eval/prep-sandbox.sh`, halting if absent — so no produce/score agent ever names a committed fixture (§8c). |
| **Produce** | `(T₂ + T₁) × M × k` | One clean agent per (task, model, run) — every family. |
| **Recheck** | `~1` (`+1` if any computation tasks; `0` if neither) | Rule candidates re-validate through **one** warm-JVM `cli batch` of `check`; computations through `cli add`/`cli modify --dry-run`. **Flat** — `k`/tasks grow produce + score linearly but never multiply the batch. The modelkit/mixed structural families need no recheck (their scorer applies the edit directly). |
| **Score** | `≈ T₂ × M × k` | One second agent per judged or executor-scored candidate. Runtime and preflight observations are compared directly in-script and add no score agent. A kernel-invalid authored candidate may skip its judge because it is already a fail. |
| **Total** | `Produce + Recheck + Score` ≈ **`(2·T₂ + T₁) × M × k + recheck + prep`** | |

The recheck is **flat** by design. Two-agent families scale with the `2·T₂` term; directly scored discovery families scale with `T₁` and therefore cost half as many agents per cell.

**Worked examples (real recent runs):**
- **Targeted single comprehension task, `k=3`, one model** (07-read-two-rules, 2026-06-18): prep `1` + produce `3` + score `3` (comprehension → judged) = **7 agents** (no recheck — comprehension produces no rule candidate). *(Matches the run's reported `agent_count: 7`.)*
- **Single workspace-roles task, `k=1`, one model** (09-workspace-roles-audit, 2026-06-18): prep `1` + produce `1` + score `1` (LLM judge) = **3 agents** — the cheap floor for a judged task. *(Matches `agent_count: 3`.)*
- **Single runtime/preflight discovery task, `k=1`, one model:** prep `1` + produce `1` + direct in-workflow scoring `0` = **2 agents** — no second agent is spent copying an already-structured oracle.
- **Rulekit-era full run** (2026-06-05, `T=7` rulekit, `M=2`, `k=2`): produce `28` + recheck `1` + judge `27` (`23` kernel-valid authoring candidates `+ 4` comprehension) = **56**. *(Predates the modelkit/mixed families; a same-size run today adds one executor agent per structural candidate.)*

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
MAIN LOOP  (you, via the run-dmtool-eval skill)      — orchestration; shares your context
  · invoke the skill   · ./gradlew :cli:installDist   · bash eval/prep-sandbox.sh   · bundle tasks → args
  · estimate cost (§8a)   · fold results → eval/RESULTS.md   · resume / cost-track (session-scoped)
  │
  │   Workflow({ scriptPath: "eval/benchmark.workflow.js", args: { models, k, tasks } })
  ▼
WORKFLOW  (eval/benchmark.workflow.js)                — fan-out + scoring; ONE allowed level of nesting
  Prep (verify sandbox, read-only) ──► Produce ──► Recheck (where needed) ──► Judge/executor (where needed) ──► aggregate pass@k
  │
  │   spawns N clean agents — model pinned, neutral prompt, material boundary enforced
  ▼
CLEAN SUBAGENTS  (the instrument)                     — ISOLATED BY DESIGN (a first-time consumer)
  see ONLY: the self-describing CLI launcher (verbs · --help · cli operators) + the model file under test
            + the task's inline Document instance or computation proposal where applicable
            (bare-tool mode, §2a — the wired default; production mode would also inject skill/SKILL.md)
  return:  a candidate rule / an explanation, via StructuredOutput
```

**Isolation belongs at exactly one layer — the clean subagents.** A walled-off, first-time consumer is the honest test (§1). **Orchestration must stay in the main loop** because it needs *shared* context: it writes `eval/RESULTS.md`, tracks token cost, and can `resumeFromRunId` (session-scoped). So **do not wrap orchestration in its own sub-agent** — it would (a) hit the nesting limit (Workflow can't nest; a sub-agent spawning sub-agents is unsupported), likely unable to launch the run at all; and (b) re-wall orchestration from your context, so pass@k detail + judge rationales return only as a lossy summary and write-back/resume/cost-tracking break. The skill is a **main-loop** playbook; the Workflow — not a sub-agent — provides the isolation.

**How the run is assembled — who builds what, when.** Two artifacts, different actors and lifetimes:
- The **script** `eval/benchmark.workflow.js` is a *committed, persistent file* — authored ahead of time, edited only when the orchestration logic changes. It never holds tasks or tiers; it reads them from the `args` global.
- The **`Workflow(...)` call is built by the main loop** (you, following the skill) at run time. `scriptPath` just *references* that committed file; `args = {models, k, tasks}` is assembled fresh each run — read `eval/tasks/*/*.json` (recurse the kit folders), trim each task, choose the tiers and `k` (a selective run includes only the affected task objects). You emit the call; the **harness launches it in the background** and the Workflow runtime executes the script, **injecting `args` as a global** — *that* is what spawns the clean subagents (you don't run the JS). On completion the return value (`{agg, …}`) comes back to the main loop to fold into `RESULTS.md`.

This script-vs-`args` split is deliberate: the script is fixed, `args` varies per run — which is exactly what makes **selective runs** (vary `args`, never the script) and **resume** (same script + same `args` ⇒ cache hit) work.

**Committed fixtures are never mutated (the sandbox) — a hard-won guarantee.** The authoring verbs `add`/`computation add` **write in place by default**. A produce agent told to "author X and confirm the kernel accepts it" would reach for `computation add <model> <spec>` — which *persists* into the committed fixture it was handed. This bit the eval **three times**, the worst being a mutated `order.dm.json` (a stray `CoverageWindowComp` + a whole-file reformat) that **slipped into a commit** (e845b86) and then made the `13-coverage-window-daterange` task un-authorable — every recheck of it crashed with a duplicate-child error, scoring a real, fixed capability as 0/k. Two layers now prevent it, structural first:

1. **Sandbox (structural — no compliance needed).** A committed script, **`eval/prep-sandbox.sh`**, mirrors the whole `cli/src/test/resources/models` tree into `/tmp/rk-eval-sandbox` (preserving structure, so a multi-file model's relative `../lib` includes still resolve); the **operator runs it from the main loop alongside `./gradlew :cli:installDist`**. It is a *script*, not an agent step, on purpose: a workflow subagent runs under a restricted permission mode and **cannot be granted approval for a mutating `rm`/`cp`**, so an agent prompted to build the sandbox is non-deterministically denied. Every model path the runner hands an agent then resolves *under the sandbox*, so no agent ever names a committed file — a corrupted, committable fixture is impossible regardless of what an agent runs. The workflow's **Prep phase only verifies** the sandbox is present (read-only `ls`) and **halts the run loudly** if it isn't, so a forgotten prep can never silently score correct candidates as failures.
2. **Read-only author prompts (defense in depth).** The author prompts steer to read-only confirmation — `check` for a rule, `computation add … --dry-run` for a computation (both validate without writing) — and forbid any persisting verb, exactly as the modify prompts already do. This keeps even the sandbox copy pristine within a run, so the recheck never trips over a sibling run's write.

The lesson generalizes: **a 0/k cell whose candidates look correct is an artifact, not a gap** — read the produced candidates before recording (the bracket-fix re-run's candidates were perfect `DateRange(OrderDate, DeliveryDate)` with the right messages; the 0/k was entirely the fixture corruption). §3 names this discipline; this is its sharpest worked case.

**What crosses each boundary (data flow):**

| Boundary | Payload |
|---|---|
| Main → Workflow | `args = { models, k, tasks }` (plain JSON) |
| Workflow → clean subagent | a neutral prompt + the path to the shipped material (never source/docs/this spec) |
| Clean subagent → Workflow | `StructuredOutput` — a candidate artifact, an explanation, or directly scored runtime/preflight observations |
| Recheck (inside Workflow) | all candidates → **one** agent runs `cli batch` (one warm JVM) → `{verdicts:[{id, valid}]}`; falls back to self-report on failure |
| Judge/executor → Workflow | `{equivalent\|correct, why}` or raw execution facts for families requiring a second agent; no second-agent boundary for runtime/preflight |
| Workflow → Main | `{agg, …family details}` — you fold `agg` + rationales/facts into `RESULTS.md` |

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

### 8d. Run tiers — a cost/coverage ladder (smoke · simple · full)

The suite is large, so a full pass@k is a milestone tool, not a routine check. Three tiers give a cost/coverage gradient; tasks are categorized on two axes — **difficulty** (`simple|medium|complex`, an intrinsic per-task tag) and **kit** (`rulekit|modelkit|mixed`, **structural** — the task's subfolder) — so a tier can be **derived by filter**, not hand-picked each time. The ledger guard `EvalSuiteLedgerTest` locks it: every task lives in a kit folder + declares a difficulty, and the cheap tiers keep their coverage — so adding a task can't silently rot the gradient.

**Layout.** `eval/tasks/{rulekit,modelkit,mixed}/<NN>-<slug>.json` — the **folder is the kit** (the explicit axis; no derived family→kit map to drift), `family` stays the fine-grained type, `difficulty` is a tag, and the task `id` is the filename stem (e.g. `01-pro-addon-fee-required`). `rulekit/` = rules/computations + their comprehension/self-correction; `modelkit/` = structure (field/group/typedef/include/config + delete-safety + refactor); `mixed/` = one `apply` session touching both kits.

**NL-explanation coverage — rule & computation comprehension (2026-06-18).** "Is a rule/computation *well explained in NL*?" is a legitimate eval target, and **rule** explanation is already covered — the `comprehension` family (`07`/`08`/`18`) reads rules back and an LLM judge scores the prose against a rubric, with the polarity-correctness criterion front and centre. **The non-determinism concern is real but managed, not avoided:** every comprehension task pairs the soft (LLM-judged, non-deterministic) NL score with a **deterministic backbone** (`EvalGoldOracleTest`: the named element reads to `Converted`, i.e. is actually explainable), the judge prompt is rubric-anchored (and verifies-by-execution where equivalence hinges on semantics), and results are read at **k≥3 as a tracked trend, never a per-commit gate** (§3). The `rule explain` `polarity`+`messages` additions further shrink the judge's burden by making the *raw material* deterministic and complete. **The COMPUTATION side was the gap — now CLOSED (2026-06-18).** The `computation-comprehension` family (`rulekit/20-explain-computation`, fixture `order-tiered`) asks an agent to explain a **banded computation** in NL — its computed field, common precondition, and the per-alternative precondition/operation bands — scored against `computation explain` (the `ComputationExplanation`: `computedField` · `commonPrecondition` · `alternatives` · `messages` · `gloss`). It uses the **same managed-non-determinism shape** as `comprehension`: a deterministic backbone (`EvalGoldOracleTest.assertComputationsExplainable` — the named computation reads to `Converted` and `computation explain` surfaces a present `commonPrecondition` + non-empty `computationAlternatives`) plus a rubric LLM judge that the agent's NL gets the *computed value*, the *when* (the precondition guard), and the *bands* (each precondition→operation, no inversion) right. That closes "rules **and** computations (incl. preconditions) are well-explained in NL" without adding a new scoring mechanism.

| tier | what runs | model · k | budget | catches |
|---|---|---|---|---|
| **smoke** | **deterministic** core — every gold through `EvalGoldOracleTest` (rules/computations re-validate, structure/session golds re-bind) + the demos verify — **plus a rulekit AND a modelkit agent run** (the `smoke` suite; `EvalSuiteLedgerTest` requires both) | sonnet · k=1 for the agent runs | ~2 min (soft) | "a verb / kernel interaction broke" + "the agent can't do even one task, either kit" |
| **simple** | **agentic** subset — the `simple` suite (~6 tasks spanning all difficulties × the wired kit) | **sonnet** (+ at most one opus spot-check) · k=1 | <7 min | "a cold agent lost self-sufficiency" |
| **full** | the pass@k benchmark, all tasks | sonnet + **few** opus · k=3 | ~40 min | precise pass@k / tier comparison |

The deterministic smoke core is difficulty-complete by construction (it runs *every* gold) and already largely exists as `EvalGoldOracleTest`. The agentic tiers default to **sonnet** (the cheaper tier matches opus on these — see RESULTS.md); opus is a spot-check, not the default. Derive a tier's agentic tasks with a one-line filter when bundling `args.tasks`:
```bash
jq -s -c '[ .[] | select(.suites and (.suites|index("simple"))) ]' eval/tasks/*/*.json   # the `simple` tier (recurse the kit folders)
```

**B: the modelkit/mixed *agentic* path — now wired for ALL six families (structure · session · delete-safety landed 2026-06-12; refactor · metadata · template landed 2026-06-14).** Both layers are wired:
- **Layer 1 (deterministic oracle, `EvalGoldOracleTest`):** the `structure`/`delete-safety`/`session`/`metadata`/`template` golds run against the real kernel via `EditSession` — apply the gold edit → re-bind → assert the post-conditions (the expected field/group exists, the cascade's `expectRemoved` set is gone, the session's field+rule are present, the metadata value reads back, the template group exists). The **`refactor`** gold is applied via `adapter.ReferenceRewriter` + `ModelDocument` (not a bare `EditSession`, since the rewriter lives in the adapter module) → must re-bind valid→valid → the new path exists while the old is gone.
- **Layer 2 (agentic runner) — all six families, all scored DETERMINISTICALLY, no LLM judge.** Each: a clean agent produces the edit from the bare CLI; a score agent applies it via `-o` to a fresh `/tmp` file (never the committed/sandbox fixture; the verb re-binds before writing, so `ok`/`committed`/`applied` ⇒ kernel-valid) and reports raw facts; the **workflow** decides pass. The producer is built from the agent's chosen op; the read-back + the expected value come from the **gold**, so a wrong op/ref/param leaves the gold's location empty and fails.
  - **`structure`** (`STRUCTURE_SCHEMA`) — `pass = applied && the gold's expectField exists with gold.kind` (kind read from `model describe`).
  - **`session`** (`SESSION_SCHEMA`, an `apply` op-list) — `pass = committed && expect.field exists && a rule references it` (via `where-used`; **not** the rule's exact name — the author chooses the rule's code, so name-matching would wrongly fail a correct edit).
  - **`delete-safety`** (`DELETE_SAFETY_SCHEMA`, the cascade-refusal corrective) — `pass = applied && every expectRemoved path is gone`.
  - **`metadata`** (`METADATA_SCHEMA`) — dispatched on the gold's op: `describe` → `meta <ref> --internal/--external <loc>=<text>` then the description reads back at the gold's `ref`/`locale`; `annotate` → `meta <ref> --annotate <n>=<v>` then `annotations[n]` reads back; `comment` → `config modify --comment` then `config read`'s `data.comment` reads back. `pass = applied && readBack === gold value`.
  - **`template`** (`TEMPLATE_SCHEMA`, the §3.8 `usageType` group templates) — `group multiselect`/`group attachment` via `-o`; `pass = applied && the gold's expectGroup exists && the generated skeleton carries the right usageType marker` (the kernel's `MultiSelect`/`AttachmentGroupRule` already accepted the composition via `applied`; the marker proves the agent used the *template* verb, not a hand-rolled plain `group add`).
  - **`refactor`** (`REFACTOR_SCHEMA`, rename) — `field rename <path> --to <name>` via `-o`; `pass = applied && the new path resolves && the old path is gone` (`applied`==ok already proves every reference followed to the new name — a dangling ref would reject, not apply).
  - `EvalSuiteLedgerTest` requires a modelkit smoke run; `modelkit/01` carries `suites:["smoke"]`. Verified k=1 (sonnet) for the first three: structure 1/1, session 1/1, delete-safety 1/1.

The session/delete-safety/template/refactor scores are **structural** by design (the deterministic-diff choice, D7-style): they confirm the right elements exist / are gone / read back and the model re-binds — they do **not** judge an added rule's *polarity* (that would need the rulekit verify-by-execution judge; a future add). No new CLI work was needed — B was runner-wiring over the already-built `meta`/`config modify`/`group multiselect`/`group attachment`/`field rename` surface.

## 9. Status & next

The eval is live and instrumented. **Built:** the **35-task corpus across 16 families** (`rulekit/` 01–24, `modelkit/` 01–09, `mixed/` 01–02 — the per-task catalog is [`../eval/TASKS.md`](../eval/TASKS.md)), each with a deterministic backbone (`EvalGoldOracleTest`); the Layer-2 pass@k runner over eight LLM-judged families, six executor-scored structural families, and the two directly scored discovery families; plus the live-manifest denominator that requires runtime tasks for every document-instance verb and the neutral computation-preflight guard. Measured lifts and pass@k are in **[`../eval/RESULTS.md`](../eval/RESULTS.md)**.

**Next:** run the Layer-2 runner on demand to populate pass@k per (model, task); grow the suite as new operator families or gaps surface. (The F10 aggregate-read gap this eval surfaced is now lifted — KERNEL-INTEGRATION-NOTES — so an aggregate rule like T4 round-trips and can serve as a comprehension fixture.)

---

*Term definitions and a plain-language intro: [`EVAL-PRIMER.md`](EVAL-PRIMER.md).*
