# a12-rulekit — project instructions

Design and rationale live in [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md); verified kernel-integration facts in [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md); the comprehensive kernel/editor API inventory for the bidirectional-modification goal in [`docs/KERNEL-RULE-API-SURVEY.md`](docs/KERNEL-RULE-API-SURVEY.md); experiment-verified kernel behaviors missing from the canonical `KERNEL-SEMANTICS.md` in [`docs/KERNEL-FINDINGS.md`](docs/KERNEL-FINDINGS.md); the classified inventory of every kernel rule-diagnostic in [`docs/MVK-LEDGER.md`](docs/MVK-LEDGER.md) (CI-locked); how the builder structure is derived (and the ANTLR grammar's indirect role) in [`docs/DSL-DERIVATION.md`](docs/DSL-DERIVATION.md); the real-model operator-usage analysis and transform backlog in [`docs/WORKSPACE-CORPUS-ANALYSIS.md`](docs/WORKSPACE-CORPUS-ANALYSIS.md); the end-state API/LLM experience study (with design findings feeding Phase 1/2) in [`docs/API-POC-STUDY.md`](docs/API-POC-STUDY.md).

## Language

English is the project default: code, identifiers, comments, docs, and the **default render target** (`DslRenderer` defaults to `ConditionLanguage.EN`) are English. **German is a supported render target, not the default** — the operator catalog carries verified `de` keywords and `new DslRenderer(ConditionLanguage.DE)` renders to it. Translations are welcome; English stays canonical.

## Test & sample document models

- **Committed fixtures are always our own originals.** Never copy domains, fields, or rules into the repo from the a12 kernel repos (`a12-kernel`, `a12-kernel-mm`, `a12-base`, …) or the proprietary *A12 Tools* workspaces. Invent your own. The one exception is **`a12-kernel-demo`** — copying/adapting its example models is fine.
- **Reference vs. copy.** Real corpora are a **read-only oracle** for *patterns and frequencies* (see [`docs/WORKSPACE-CORPUS-ANALYSIS.md`](docs/WORKSPACE-CORPUS-ANALYSIS.md)), transformed into our own fixtures (kernel-validated by N7). `a12-kernel-mm` is OSS (**EUPL-1.2**) and may be *referenced* — via the bootstrap's sibling clone (`../a12-kernel-mm`), env-overridable, skipped if absent — for **opt-in integration tests only**. Do **not commit** its model files: EUPL copyleft conflicts with rulekit's intended permissive license (§19); referencing ≠ copying. The *A12 Tools* workspaces are proprietary → read-only analysis only, never committed in any form.
- Whatever you invent **must make sense**: coherent domain, realistic field names and types, plausible rules. No nonsense placeholders.

## Coding style — make full use of Java 21

Write idiomatic modern Java 21, not Java 8 with newer syntax:
- **Records** for immutable data (AST nodes, value objects, results). **Sealed interfaces + exhaustive pattern-matching `switch`** for closed hierarchies — model the AST this way and let the renderer/visitors dispatch with a switch that the compiler proves exhaustive (no `default`). See proposal §18.5.
- Record/`instanceof` **deconstruction patterns**, `var` for obvious locals, **text blocks** for multi-line strings (DM-JSON fixtures, expected output), enhanced `switch` expressions, `Optional` over nulls in APIs, `List.of`/`List.copyOf` for immutability.
- Prefer making illegal states unrepresentable in the type system over runtime checks — that is the whole point of the typed builder.

## Documentation discipline

- **Every non-obvious finding gets written down under `docs/`** — kernel/build/environment facts default to [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md); design decisions to [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md). If a finding doesn't fit an existing doc's scope, **propose a new doc** rather than overloading one.
- **`../a12-kernel-demo/KERNEL-SEMANTICS.md` is the canonical kernel-truth reference — consult-only** (owner policy, 2026-06-04). Before each increment, read the relevant section (truth values, null semantics, formal errors, decimals, dates, enums, iteration, paths, computations, messages). **Do not write to it.** Kernel-behavior findings it *lacks* go to our own [`docs/KERNEL-FINDINGS.md`](docs/KERNEL-FINDINGS.md) (section-numbered to mirror KERNEL-SEMANTICS for easy upstreaming; every entry experiment-verified and test-locked); rulekit's *treatment* of each fact stays in `KERNEL-INTEGRATION-NOTES.md`.
- **Anti-whack-a-mole: mine constraints proactively, don't trip over them** (owner direction, 2026-06-04). Structural laws live in enumerable sources — sweep them instead of discovering by accident: **(1)** slot parity vs the public `IRule`/`IComputation` accessors (every accessor = supported or explicitly deferred); **(2)** the **MVK ledger** — every `MVK_*` code classified (erased-by-types / pre-checked / kernel-delegated / pending / unknown→experiment queue), CI-checked; **(3)** the `../a12-kernel-mm` metamodels as the **source of truth for valid DM structure** (schema oracle: `MM_*`/`CT_MODEL_HEADER` define the slots; constraint oracle: their rules encode validity laws — read-only, own-words analysis only, EUPL); **(4)** deserializer-tier checks (constraints that fire before `checkConsistency`, e.g. locale format) — **scheduled as the opening step of the F6 DM-JSON-write increment** (decided 2026-06-04: that tier is the write path's output contract).
- A finding is only "done" when it's documented. When a new fact **contradicts** an existing note, update that note — don't leave stale claims.
- **After any substantive change, state which docs need updating** (proposal / notes / README status / this file) and update them in the same change.
- **Docs travel inside the feature commit, never after it.** Before committing a feature: update the affected docs (study status flips 🔮→✅, notes entries for kernel findings, README status, corpus backlog marks) and **sweep the touched docs for stale claims** — then commit code + docs together. No separate "docs: refresh" commits trailing a feature.
- **[`docs/API-POC-STUDY.md`](docs/API-POC-STUDY.md) is a living study & review surface — keep it current.** When a feature lands, flip its study from 🔮 to ✅ and make the snippet real (test-backed where possible); when an interesting usage, idiom, or combinator pattern emerges (in tests, fixtures, or discussion), **showcase it there**. New ergonomic observations go into its numbered findings list — that list feeds the next increments. It is also the seed material for the LLM skill (N1) and the self-sufficiency eval (N6).
- **Doc evolution: the proposal stays whole; specs split out on implementation boundaries** (decided 2026-06-03). Don't split `PROJECT-PROPOSAL.md` into per-section files — it's one argument, densely cross-referenced by stable anchors (`§N`, `Fn`/`Nn`/`Dn`, forks Q1–Q12). Instead, when a component's design detail outgrows its proposal section during implementation, extract a **component spec** (e.g. `docs/CATALOG-SPEC.md`, `docs/ADAPTER-SPEC.md`, `docs/AST-SPEC.md`): the spec supersedes the section's *detail*, and the proposal section shrinks to intent + a pointer. Revisit a wholesale split only if the proposal exceeds ~800 lines despite spec extraction.

## Testing strategy

- **Example-based TDD is the primary spec** (red/green) — readable, intent-revealing tests drive each feature.
- **The real kernel is the oracle.** `RuleValidator` + `checkConsistency` confirm that rendered output is actually accepted (F4); N7 runs every catalog example through it.

## Build

Build with an isolated, public-repos-only Gradle home so the machine's global config (private registry) is bypassed, on JDK 21 — see the README's "Build & test" section. The kernel must first be published to `mavenLocal` via the sibling `a12-kernel-bootstrap` kit.
