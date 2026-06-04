# a12-rulekit docs — index

What each document under `docs/` holds, so you can jump to the right one. This is an *index*, not policy: the project's working conventions (language, fixture sourcing, coding style, documentation discipline, testing strategy, build) live in [`../CLAUDE.md`](../CLAUDE.md).

## Design & vision

- **[PROJECT-PROPOSAL.md](PROJECT-PROPOSAL.md)** — the one argument: vision (the bidirectional *ingest → explain → modify → diff → validate* loop), requirements (`Fn`/`Nn`/`Dn`), architecture, the operator-catalog spine, the decision log (§17), and the phased roadmap (§16). Densely cross-referenced by stable anchors; specs split out of it on implementation boundaries rather than fragmenting the argument.

## Kernel truth (verified facts & their treatment)

- **[KERNEL-FINDINGS.md](KERNEL-FINDINGS.md)** — experiment-verified kernel behaviors that **complement** `a12-kernel-demo`'s canonical, consult-only `KERNEL-SEMANTICS.md` (checked out as `../../a12-kernel-demo/KERNEL-SEMANTICS.md`). Section-numbered to mirror it; every entry is test-locked in this repo.
- **[KERNEL-INTEGRATION-NOTES.md](KERNEL-INTEGRATION-NOTES.md)** — rulekit's *treatment* of each verified kernel/build/environment fact: how the kit validates (F4), writes (F6), and diagnoses (F4a) against the real engine.
- **[MVK-LEDGER.md](MVK-LEDGER.md)** — the classified inventory of every kernel `MVK_*` rule-diagnostic (erased-by-types / pre-checked / kernel-delegated / pending / unknown→experiment-queue). CI-locked, so a new code can't slip through unclassified.

## Surveys, derivations & corpus

- **[KERNEL-RULE-API-SURVEY.md](KERNEL-RULE-API-SURVEY.md)** — the comprehensive kernel/editor API inventory behind the bidirectional-modification goal (the Phase-2 adapter surface: ingest, explain, modify).
- **[DSL-DERIVATION.md](DSL-DERIVATION.md)** — how the typed builder structure is derived, and the ANTLR grammar's indirect role in it.
- **[WORKSPACE-CORPUS-ANALYSIS.md](WORKSPACE-CORPUS-ANALYSIS.md)** — read-only analysis of the real model corpora (A12 Tools workspaces + `a12-kernel-mm`): operator-usage histograms and the transform backlog that prioritizes what to build next. Usage facts only; nothing of either corpus is committed.

## Experience study

- **[API-POC-STUDY.md](API-POC-STUDY.md)** — the living study & review surface: what rulekit *feels like* to use, with numbered findings that feed the Phase-1/2 increments (and seed the LLM skill and self-sufficiency eval). Features flip 🔮→✅ here as they land.
