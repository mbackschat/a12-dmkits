# a12-dmkits docs — index

What each document under `docs/` holds, so you can jump to the right one. This is an *index*, not policy: the project's working conventions (language, fixture sourcing, coding style, documentation discipline, testing strategy, build) live in [`../CLAUDE.md`](../CLAUDE.md).

## Design & vision

- **[PROJECT-PROPOSAL.md](PROJECT-PROPOSAL.md)** — the one argument: vision (the bidirectional *read → explain → modify → diff → validate* loop), requirements (`Fn`/`Nn`/`Dn`), architecture, the operator-catalog spine, the decision log (§17), and the phased roadmap (§16). Densely cross-referenced by stable anchors; specs split out of it on implementation boundaries rather than fragmenting the argument.
- **[ARCHITECTURE.md](ARCHITECTURE.md)** — the *physical* view: the Gradle subprojects (core / opt-in `adapter` / `cli`) and the skill, their artifacts, the core's acyclic package layering, the N8/D15 kernel-coupling boundary, what crosses each boundary as plain data, and the **proposed parts laid out as TBD** with each one's intended home. Complements the proposal's conceptual component view (§15).
- **[CLI-SPEC.md](CLI-SPEC.md)** — the standalone CLI (the modeller/BA front-end, N10): the verb surface, the JSON I/O contract, the **dev/test workflow that keeps the inner loop fast** (JUnit verb tests; packaging out of the loop), the `export` verb detail, sizing, and the dev-vs-distribution packaging phases.
- **[CLI-CONFORMANCE-SPEC.md](CLI-CONFORMANCE-SPEC.md)** — the **self-explain + diagnosis harness**: the diagnostic contract (every domain error is a structured, coded, fixable envelope), the self-explain criteria, the anti-pattern catalog, the **gates** that enforce each (the conformance fuzzer, registry parity, single-source derivation), the never-stale guarantee, and the verb-authoring checklist.
- **[MODELKIT-PROPOSAL.md](MODELKIT-PROPOSAL.md)** — addendum to the proposal for fork #8 (model-*structure* authoring, the rulekit peer): why modelkit is worth it (and why it *is* the formalization vehicle), why not `betterbuilders`, the **formalize-the-knowhow** outcome (the per-kind gradient + five options + the consumer-exists discipline), a **material-source evaluation** of every document-model knowledge source, and the **go-forward read→edit→write-back plan** (§H "Direction"). **Spike complete; modelkit M1–M6 landed** (dogfooded) — it authors structure incl. includes + typedefs and emits kernel-valid DM-JSON. **Folded into the root project as the `dm.modelkit` sibling package 2026-06-09** (D10 superseded — see ARCHITECTURE "the umbrella"); read→edit→write-back is the M7+ direction.
- **[FEATURE-GAPS-PROPOSAL.md](FEATURE-GAPS-PROPOSAL.md)** — the **authoring-coverage reference**: a map of the whole document-model surface dmkits can author over the A12 kernel, each capability pointing at where it's implemented, contracted, and tested. (It began as the feature-gaps backlog, now closed — the `§3.x` numbers remain as stable anchors referenced from the code.) Kernel facts live in KERNEL-FINDINGS; verb/gate contracts in CLI-SPEC; status on the API-POC-STUDY §9 board.

## Kernel truth (verified facts & their treatment)

- **[KERNEL-FINDINGS.md](KERNEL-FINDINGS.md)** — experiment-verified kernel behaviors that **complement** `a12-kernel-demo`'s canonical, consult-only `KERNEL-SEMANTICS.md` (checked out as `../../a12-kernel-demo/KERNEL-SEMANTICS.md`). Section-numbered to mirror it; every entry is test-locked in this repo.
- **[KERNEL-INTEGRATION-NOTES.md](KERNEL-INTEGRATION-NOTES.md)** — rulekit's *treatment* of each verified kernel/build/environment fact: how the kit validates (F4), writes (F6), and diagnoses (F4a) against the real engine.
- **[MVK-LEDGER.md](MVK-LEDGER.md)** — the classified inventory of every kernel `MVK_*` rule-diagnostic (erased-by-types / pre-checked / kernel-delegated / n/a-structural). **Fully classified — 300/300 as of 2026-06-10**; the kernel-delegated rows double as an own-words law inventory. CI-locked, so a code a kernel bump adds can't slip through unclassified; its "How the queue resolves" section maps the per-source resolution path for newcomers.
- **[RT-SEMANTICS-LEDGER.md](RT-SEMANTICS-LEDGER.md)** — the MVK ledger's *runtime* sibling (instrument 6): every class of the kernel's runtime evaluation core classified (mined / already-locked / canonical / infrastructure / out-of-scope / queued), so behavioral semantics get drained from the source proactively — each drained fact law-locked in `adapter/…/laws/` and recorded in KERNEL-FINDINGS. CI-locked by `adapter.RtSemanticsLedgerTest`.
- **[GRAMMAR-LEDGER.md](GRAMMAR-LEDGER.md)** — the *syntax* sibling (instrument 7): all 119 parser productions of the kernel's condition grammar classified (rendered / plumbing / entry / legacy / queued), closing the blind spot keywords and construct enums can't see — a new syntax *shape*. Guardian: `GrammarProductionLedgerTest` (opt-in, reads the sibling `../a12-kernel` checkout, skips when absent).
- **[KERNEL-UPGRADE.md](KERNEL-UPGRADE.md)** — the runbook for *when a12-kernel(-mm) changes*: the coupling map (each guardian test ↔ what its failure means ↔ the realignment), the bump procedure, and the dry-run negative-controls that rehearse a change. Embodies F11's "quiet runtime tolerance, loud build-time detection".

## Surveys, derivations & corpus

- **[KERNEL-RULE-API-SURVEY.md](KERNEL-RULE-API-SURVEY.md)** — the comprehensive kernel/editor API inventory behind the bidirectional-modification goal (the Phase-2 adapter surface: read, explain, modify).
- **[DSL-DERIVATION.md](DSL-DERIVATION.md)** — how the typed builder structure is derived, and the ANTLR grammar's indirect role in it.
- **[WORKSPACE-CORPUS-ANALYSIS.md](WORKSPACE-CORPUS-ANALYSIS.md)** — read-only analysis of the real model corpora (A12 Tools workspaces + `a12-kernel-mm`): operator-usage histograms and the transform backlog that prioritizes what to build next. Usage facts only; nothing of either corpus is committed.

## Testing

- **[TESTING.md](TESTING.md)** — the testing-approach map: the one principle (the real kernel is the oracle), the kinds of test and where each lives, the **two law tiers** (accept/reject vs runtime fires/does-not-fire) that are easy to conflate, the structural-law guardians (anti-whack-a-mole), the opt-in corpus harnesses, layout conventions, and how to run. The navigation layer; enforced conventions stay in [`../CLAUDE.md`](../CLAUDE.md), per-area detail in its own doc.

## Experience study

- **[API-POC-STUDY.md](API-POC-STUDY.md)** — the living study & review surface: what rulekit *feels like* to use, with numbered findings that feed the Phase-1/2 increments (and seed the LLM skill and self-sufficiency eval). Features flip 🔮→✅ here as they land.
- **[EVAL-PRIMER.md](EVAL-PRIMER.md)** — **start here if LLM evals are new to you**: a plain-language intro to what an eval is (oracle, gold, judge, pass@k) plus the **glossary** of all the vocabulary. The onboarding/reference companion to the spec.
- **[EVAL-SPEC.md](EVAL-SPEC.md)** — the self-sufficiency eval & benchmark (N6/N1): the method (a **clean subagent** as the LLM-under-test, since self-eval is invalid), the material boundary, objective+soft-regression scoring, the build-measure-learn loop, the probe protocol, the living task suite, and the runner cost model + execution architecture (§8). The *method*, not the numbers.
- **[../eval/RESULTS.md](../eval/RESULTS.md)** — the dated **results log** (sibling of the runner, outside `docs/`): baselines, per-change lifts, model comparisons, and pass@k runs, newest first. EVAL-SPEC is the fixed method; this is the moving record.

## Demos

- **[CLI-TOUR-SPEC.md](CLI-TOUR-SPEC.md)** — the **method** for the verifiable CLI demos: how to author/verify/regenerate them, the `bin/dmtool` launcher shim, the determinism contract (showboat `verify` is exact-match), and the expansion backlog (more demos + a CI verify gate). The demos themselves are in **[../examples/](../examples/)** (outside `docs/`) — `EVAL-SPEC` ↔ `eval/` split.

## Licensing

- **[LICENSING.md](LICENSING.md)** — the settled license decision (rulekit source **MIT**; the kernel-bundling CLI distributable **EUPL-1.2**) and the full reasoning: the kernel's dual `EUPL-1.2 OR commercial`, why redistribution is permitted, and why MIT-source + EUPL-bundle don't conflict. The proposal's §19 states the decision in one line and points here.
