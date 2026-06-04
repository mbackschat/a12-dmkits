# a12-rulekit

A **model-aware**, typed, combinator-style **rule kit** (Java first, TypeScript later) for A12 Kernel validation- and computation-rule conditions: **author** them through a typed builder, **read** existing rule text into the same typed representation, **modify** with reviewable diffs, and **render + validate** against the real kernel — replacing the fragile hand-written `errorCondition` strings. Think *"jOOQ for the A12 validation DSL"* — including the reverse direction, since the kernel's own parser supplies it.

> **Not an official A12 artifact.** A personal exploration; not affiliated with or endorsed by mgm. Shipped code binds only to the kernel's **public** OSS surface (no internal systems); an opt-in Java-only adapter module may read the kernel's internal AST tier under strict guardrails (proposal D15).

## Status

**Phase 1 — the Java vertical slice — is complete** (2026-06-04): model index, the self-verifying operator catalog (full corpus operator coverage), the typed AST + EN/DE renderer with fork-9 group-relative paths, the real-kernel validator with the **complete F4a diagnostic surface** (KERNEL · LINT · PRECHECK in one `Diagnostic`, catalog-joined fixes, span-pointing, "did you mean", the gotcha/INFO channel, and the unifying `RuleDiagnostics`), the DM-JSON write path (incl. includes round-trip), and the F9a `describe`/`find` context surface. Fork-9 renders group-relative paths and single-quotes keyword-named segments. Carried forward (small): the N3 construct/keyword-parity test anchor and F2a message-interpolation sugar. Next is **Phase 2** — the kernel-adapter module and the bidirectional edit loop (*ingest → explain → modify → diff → validate*).

The design is captured in **[`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md)** (**v2**, reworked 2026-06-03) — vision, requirements, architecture, the operator-catalog spine, the decision log (§17), and the phased roadmap (§16); the per-feature status board is [`docs/API-POC-STUDY.md` §9](docs/API-POC-STUDY.md). The kernel/editor API inventory is **[`docs/KERNEL-RULE-API-SURVEY.md`](docs/KERNEL-RULE-API-SURVEY.md)**; verified kernel facts in **[`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md)**.

Working end-to-end today (capability snapshot — the per-feature status board is [`docs/API-POC-STUDY.md` §9](docs/API-POC-STUDY.md), and operator coverage / backlog is [`docs/WORKSPACE-CORPUS-ANALYSIS.md`](docs/WORKSPACE-CORPUS-ANALYSIS.md)):

- **Model index** (F0) — load DM-JSON → `expand` → `checkConsistency` → indexed group tree, field kinds, number scale, repeatability.
- **Operator catalog** (§9) — bundled, bilingual, **self-verifying** JSON spine (every `SUPPORTED` example checked against the real kernel, N7); an `OperatorStatus` maps the whole operator space (`SUPPORTED` vs `PLANNED` stubs).
- **Typed condition AST + renderer** — model-bound handles compose immutable `Condition`s carrying the **D14 invariant** (catalog operator-id + model-resolved refs + provenance); the renderer emits canonical EN/DE with no keyword knowledge outside the catalog, paths rendered relative to the rule's group (fork #9, `../` to siblings, keyword-named segments single-quoted). Coverage spans the corpus backlog's core — presence/group/enum predicates, patterns, value lists, count/list aggregates, `*`-flattened lists, uniqueness, `Having` filters, `CurrentRepetition`, semantic index, `CustomCondition`, dates, and field-vs-field comparison.
- **Authoring objects** (F2/F2a) — `m.rule(group)…build()` / `m.computation(field)…build()` with computed rel-paths and build-time slot pre-checks that name the `MVK_*` they prevent, so illegal shapes are unrepresentable or fail fast.
- **Ground-truth validation + rich diagnostics** (F4/F4a) — a rendered rule is injected as DM-JSON and run through `checkConsistency`; problems surface as LSP-shaped `Diagnostic`s (`severity·source·code·summary·where·fix·explain`, JSON-serializable) with a **catalog-joined** fix, an AST **lint** layer (valid-but-suspicious WARNINGs, incl. the negative-in-iteration pre-warning), and a **gotcha/INFO** channel surfacing the catalog's semantic traps. `lint.RuleDiagnostics.diagnose(model, rule)` returns all origins (KERNEL span-pointed + LINT + INFO) in one result.
- **Model context** (F9a) — `context.ModelContext`: `describe()` gives an agent a compact model card (fields + kinds + enum domains + group repeatability, JSON) and `find(name)` resolves a name to fields (exact / substring / fuzzy) — the inverse of authoring, so an agent orients before it builds.
- **DM-JSON write path** (F6) — `ofJson` → `add(Rule)` → re-validated new binding → `toJson`/`writeTo`, editing the authored JSON so includes/`modelReferences` are preserved (round-trip fixture-proven).

Next up: **Phase 2** — the kernel-adapter module and the bidirectional edit loop (text→AST ingest F10, explain F9 + F9a `whereUsed`, modify-by-re-express + diff F8 #2/#3), then the TypeScript twin. The small Phase-1 remainder rides alongside: the N3 parity anchor and F2a message-interpolation sugar.

## Prerequisites

The A12 Kernel is resolved from your **local Maven repository** (`~/.m2`) — no private registry. Publish it from public sources once with the sibling [`a12-kernel-bootstrap`](https://github.com/mbackschat/a12-kernel-bootstrap) kit:

```sh
# in the a12-kernel-bootstrap checkout (a sibling of this repo)
./bootstrap.sh
```

This publishes kernel `30.8.1` (Java only) to `mavenLocal`. You also need **JDK 21**.

## Build & test

Like the sibling repos, build with a **clean/isolated Gradle home** so the machine's global config (which may point at a private registry) is bypassed — `build.gradle` declares only `mavenLocal()` + `mavenCentral()`:

```sh
ROOT="$(cd .. && pwd)"                               # the directory holding the A12 repos
export GRADLE_USER_HOME="$ROOT/.gradle-isolated"     # public-repos-only Gradle home
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS; or point at any JDK 21
./gradlew test
```

The suite covers kernel wiring, the model index, the renderer (EN/DE), the validator, the self-verifying catalog (each example checked against the real kernel), and the constraint-mining instruments (slot parity, MVK ledger) — so a green `test` means rulekit's output is confirmed by the engine, not just plausible. With the sibling `a12-kernel-mm` clone present, an opt-in harness additionally loads all 15 of the kernel's own metamodels through the index (skipped when absent).

## License

Deliberately left open for now; a permissive OSS license (Apache-2.0 or MIT) is the working intent — to be settled before first publish. See proposal §19.
