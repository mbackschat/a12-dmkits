# a12-dmkits — Project Proposal (v2)

> **Status:** design **v2**, reworked from a sound base on **2026-06-03** — a full rewrite of the v1 proposal (2026-06-02) after the kernel/editor API survey ([`KERNEL-RULE-API-SURVEY.md`](./KERNEL-RULE-API-SURVEY.md)) showed the kernel ships far more reusable rule machinery than v1 assumed, and after the owner declared **bidirectional rule modification a vision/goal**. v1's decisions are preserved where they survived re-examination and **revised where they didn't** — without regard to code already written (sunk cost explicitly ignored; existing code conforms to this design or gets reworked).
>
> **What it is (one line):** a **model-aware**, typed, combinator-style **rule kit** (Java first, TypeScript later) for A12 Kernel validation- and computation-rule conditions: **author** them through a typed builder, **read** existing rule text into the same typed representation, **modify** on that representation, and **render + validate** back — replacing the fragile hand-written `errorCondition` strings the kernel otherwise expects.
>
> **Not an official A12 artifact.** A personal exploration; not affiliated with or endorsed by mgm. Builds only against public OSS sources (no internal systems).
>
> **Resume here:** decisions in [§17](#17-decision-log) (all current, no amendment layers); decided design forks in [§18](#18-decided-forks). The **§19 license is now settled** (2026-06-04): rulekit source is **MIT**, the bundled CLI distributable is **EUPL-1.2** — see [`LICENSING.md`](./LICENSING.md). The two non-negotiables: **model-awareness is foundational** ([§5a](#5a-model-awareness)) and **rulekit's own AST is the only working representation** ([§6](#6-the-key-reframe), [§12](#12-modify)). Roadmap in [§16](#16-roadmap); Phase 1 (the Java vertical slice) is **complete**, Phase 2 (the bidirectional edit loop) is next.

---

## Table of contents

1. [Motivation — why this exists](#1-motivation)
2. [Vision & goals](#2-vision--goals)
3. [Requirements](#3-requirements)
4. [Non-goals / scope boundaries](#4-non-goals)
5. [Background — the A12 Kernel DSL and what the kernel already provides](#5-background)
    - [The DSL surface — the slots](#dsl-slots)
    - [**5a. Model-awareness is foundational**](#5a-model-awareness)
    - [**5b. The type system — operand types are first-class**](#5b-type-system)
    - [**5c. What the kernel already ships (and what it doesn't)**](#5c-kernel-inventory)
6. [The key reframe — "jOOQ for the A12 DSL", with the parser already written](#6-the-key-reframe)
7. [Prior art](#7-prior-art)
8. [Architecture](#8-architecture)
9. [The operator catalog (the spine)](#9-operator-catalog)
10. [API design sketch](#10-api-design-sketch)
11. [Validation strategy](#11-validation-strategy)
12. [Modify — the bidirectional edit loop](#12-modify)
13. [Polyglot strategy (Java now, TypeScript later)](#13-polyglot)
14. [LLM coding-agent friendliness](#14-llm-friendliness)
15. [Feasibility, risks & mitigations](#15-feasibility-risks)
16. [Phased roadmap](#16-roadmap)
17. [Decision log](#17-decision-log)
18. [Decided forks (design record) & open items](#18-decided-forks)
19. [Relationship to sibling repos, build, licensing](#19-relationship)
20. [Glossary & references](#20-glossary)

---

## 1. Motivation <a name="1-motivation"></a>

The A12 Kernel is a model-driven validation engine: **validation rules** (the *error* scenario) and **computations** (derived fields) are declared as data and compiled to Java/TypeScript/Groovy validators. A rule's logic is carried as a **plain text string** — the `errorCondition` — written in the kernel's bilingual DSL and **only checked at model-conversion time**, where mistakes surface as cryptic `MVK_*` errors.

Building the sibling demo suite (`a12-kernel-demo`) and studying the grammar (`a12-kernel/KERNEL-GRAMMAR.md`) surfaced a whole taxonomy of these the hard way: keyword-named fields (`Date`) breaking the parser; the `$` correlation operator's placement rules; `Date(...)` operand format constraints; the error-field-must-be-referenced rule; rules being illegal at model root; decimal-scale mismatches; short names not resolving across sibling groups. **Every one is a class of bug that a typed, model-bound builder can make unrepresentable or catch at Java-compile time.**

And the problem is symmetric: rules don't just need to be *written* — they need to be **read, understood, and changed**. Real models carry hundreds of hand-written conditions (the corpus analysis found 150 in one application workspace and 355 in the kernel's own metamodels — [`WORKSPACE-CORPUS-ANALYSIS.md`](./WORKSPACE-CORPUS-ANALYSIS.md)). Editing them today means editing raw strings with no structural safety at all.

**Three compounding payoffs:**

1. **Correctness up front** — illegal rules don't compile, or fail fast with actionable messages mirroring the `MVK_*` fixes.
2. **Safe evolution** — existing rules become structured, explainable, and modifiable with reviewable diffs instead of string surgery.
3. **LLM enablement** — a small, consistent, composable API plus an explicit semantic catalog is exactly what lets a coding agent author, understand, and modify kernel rules reliably.

---

## 2. Vision & goals <a name="2-vision--goals"></a>

The owner's goals, consolidated (original vision 2026-06-02; bidirectionality declared 2026-06-03):

- **A typed, builder-based way to produce valid conditional and computation rules**, faithful to the kernel DSL.
- **Bidirectional modification — a core vision, not a future maybe:** *"provide ways to modify A12 rules bidirectionally: from rule text and from a Java builder — represent the AST and work on it via Java APIs (existing or self-developed)."* The answer this design gives: the kernel's **existing** APIs supply the read direction (text → its model-resolved internal AST) and the oracles; rulekit's **self-developed** AST is the single representation you work on; render + validate close the loop. (→ §12.)
- **Combinators:** expression parts are reusable, parameterizable values (`mandatory`, `withinTolerance`, `chronological`, `uniqueBy`, …).
- **Model-bound, on a fully-expanded model** — paths, types, repetition/index structure, enum domains all come from a specific `DocumentModel` (→ §5a). **Field/operand types are first-class** (→ §5b).
- **Semantics matter as much as grammar** — expose and explain the operators, their constraints and gotchas, not just keywords.
- **Authoring requires no code generation** — build, render, and pre-check offline; ground-truth validation is a lightweight parse/check pass (→ §11).
- **LLM-agent friendly for two personas as a first-class requirement** — the **developer** (Claude Code editing rulekit-the-library inside their own project) and the **modeller/BA** (a standalone CLI, no developer toolchain), sharing one engine; including **full context & explainability** for any specific rule — understand it, explain it, rewrite it (→ F9, §14, N10).
- **Polyglot** — portable to TypeScript later; plan for it structurally from day one (→ §13).
- **Learn from established validators** — Zod, Pydantic, JSON Schema, Bean Validation, FluentValidation, and above all **jOOQ** (→ §7).

---

## 3. Requirements <a name="3-requirements"></a>

### Functional

- **F0 (foundational) — Bind to a DocumentModel** (→ §5a)
  - Consumes a **fully-expanded** `IDocumentModel`, or model + caller-supplied `IDocumentModelReferenceResolver` (rulekit expands; fails fast on unresolvable references).
  - Indexes the group tree, field types, repetition/index fields, and enum domains; rules are authored *into a specific group*.
  - **Snapshot semantics:** a binding is an **immutable snapshot** — no in-place mutation exists in the public tier (verified), so no staleness and no event mechanism; every write (DM-JSON surgery, Phase-2 `replaceCondition`) produces a **new model → new binding**.
  - **Memoized lookups:** derived indexes (kind/name/label maps, short-name uniqueness, keyword collisions) are lazily memoized inside the binding — clients never build their own.
  - **Model scale (owner facts):** ~1,000 fields / 6-level nesting is a *normal* DM; 10,000-field models exist. Trivial for Java-side indexing; decisive for anything LLM-facing (→ F9a).
- **F0a (foundational) — Own the operand type system** (→ §5b)
  - A **type lattice** of the kernel's value kinds + legality/comparability rules, encoded as catalog data (the kernel exposes no readable type tag), CI-verified against the engine.
- **F1 — Build boolean `Condition`s** via the typed API — the kind that fills a rule's `errorCondition` *and* computation pre-/common-preconditions (one AST, many slots — §5).
- **F2 — Build `Calculation`s and `Computation`s** — computed field + optional `commonPrecondition` + `(precondition, operation)` alternatives; the operation's value **kind** is enforced locally (`Calculation.kind()`), decimal **scale** stays the kernel's call (D13). Alternatives are either one unconditional or all preconditioned — mixing is kernel-illegal (`MVK_ERROR_EMPTY_PRECONDITION`, verified) and unrepresentable in the builder.
- **F2a — Author the adjacent slots** (fork #11)
  - Path slots (`errorEntityRelPath`, `computedFieldRelPath`) set from typed field handles.
  - A light typed **error-message interpolation helper** (`$Field.value$`, no-asterisk rule enforced); full templating stays optional sugar.
- **F3 — Render** to canonical DSL text: EN default, DE available, same AST.
  - **F3a — model- and rule-group-aware rendering:** computes the correct/safest **path form** (relative-first, per the kernel's own preference — fork #9) and **auto-quotes keyword-named segments**, dissolving the keyword-collision and common path-error classes by construction.
- **F4 — Validate via the real kernel**, no compile/execute (→ §11)
  - Shipped path: DM-JSON injection + `checkConsistency` on the expanded model.
  - Adapter fast lane: per-rule `hasValidConditionText` for inner loops and CI.
  - `MVK_*` failures map to actionable fixes.
- **F4a — Rich diagnostics** *(added 2026-06-04; the LLM-facing half of validation — feeds F9/N1)* *(build status: study §9)*
  - One structured `Diagnostic` shape (modelled on LSP's: `severity` ERROR/WARNING/INFO · `code` `MVK_*` or rulekit `RK_*` · `summary` *what* · `where` rule path + AST operator-id / span · `fix` *how* — an **applicable** DSL replacement where known, not just prose · `explain` *why* — catalog `meaning`/`gotchas` · `source` PRECHECK/KERNEL/LINT), unifying the three diagnostic origins.
  - **Catalog-join enrichment:** a kernel `MVK_*` is joined to the catalog entry whose `invalidExample.error` matches, reusing its `fix`/`gotchas` — so kernel diagnostics inherit the curated, **N7-locked** fix database without drift.
  - **Lint layer (the distinguishing half — valid-but-suspicious WARNINGs over the AST):** inverted-requiredness gloss, unguarded negative under a repeatable group (pre-warns the kernel-delegated `MVK_NEG_CONDITION_IN_ITERATION`), `FieldFilled`/`FieldNotFilled` used as strict complements, dead/always-true conjuncts — signals an LLM can't get from `checkConsistency` (which only sees errors). Subsumes F9's "flag suspicions" (study finding 7).
  - **"Did you mean"** off the model index: enum value outside domain → nearest by edit distance; wrong path → nearest path; category-vs-enum-value confusion.
  - **Gotcha/INFO channel (semantic surprises — the third diagnostic kind):** the structural-error/semantic-surprise split is fundamental — `checkConsistency` catches the former (F4) but is **silent on the latter** because the rule is *valid*; surfacing them is rulekit's unique value for an LLM. So F4a emits an `INFO` diagnostic for the operators a rule *uses*, sourced from the catalog `gotchas` keyed by operator-id and **classified by relevance** (a `GotchaKind` gates out trivia the renderer already handles) — turning the passive catalog field into proactive author-facing notes, drift-free (N7-locked). Examples: `AddYears`/`AddMonths` disagree across a leap year (`AddYears(28.02.2010, 2) = 29.02.2012`); `DifferenceInMonths` is a *floor*; `Sum` substitutes 0 for unspecified fields while `Max/Min` ignore them; `GroupFilled` = "row exists"; `FieldFilled`/`FieldNotFilled` are not strict complements. **Origin/kind model (decision):** origins are PRECHECK/KERNEL/LINT (+ENVIRONMENT for toolchain notes, added with F11) and kinds are ERROR/WARNING/INFO — the gotcha channel is the INFO kind of the LINT origin (severity is the kind axis). F11 also splits PRECHECK: completeness/structural pre-checks stay hard at `build()`, while kernel-semantic ones are *deferred* (advisory) so the live kernel stays the sole authority on validity.
  - Agent-parseable JSON output (files-as-API, §14).
- **F5 — Combinators:** expressions are first-class, immutable, composable values; reusable parameterized fragments are ordinary functions returning expression nodes.
- **F6 — Integrate:** emit condition strings for `RuleBuilder.errorCondition(...)`-style call sites, and whole Rule/Computation **DM-JSON nodes** (the public, polyglot write path — D9).
- **F7 — Expose semantics:** the machine- and human-readable **operator catalog** (→ §9).
- **F8 — Modify: the edit loop is core scope** (→ §12)
  - **#1** immutable, transformable AST from day one; **#2** modify-by-re-express + rendered diff; **#3 (core)** the text→AST read path, so the loop covers foreign/BA-authored rules.
  - Every modify yields a **reviewable rendered diff**.
  - Minimal-edit (concrete-syntax-preserving) re-render stays research-gated (Phase 5).
- **F9 — Explain a specific rule** (structured projection; → §14) *(✅ condition-level 2026-06-04)*
  - `ModelContext.explain(condition) → RuleExplanation` assembles: operators used (catalog `meaning`/kind/gotchas), referenced fields/groups (path + model kind), whether & which repeatable group it iterates, the canonical text, and an NL gloss; JSON-serializable (files-as-API).
  - One mechanism, two consumers: *understand/rewrite existing rules* and *ground new authoring* — verified **identically on a rulekit-built condition and a converted-foreign one** (F10 ingest feeds it; demonstrated end-to-end).
  - *Carried forward:* rule-level context (error-field **role** labels, severity, computation table) and the kernel cross-check oracles (`getDirectlyReferencedEntities`/`referencesIteration`) — the condition-level projection is in; the rule-envelope enrichment is the next slice.
- **F9a — Model-context surface: the inverse projections** *(added 2026-06-04; own `context` package — a projection layer over model index + catalog, nothing else depends on it)*
  - **Motivation:** an LLM asked *"explain the rule on birthdate"* must first resolve the name and find the rules — F9's other half: `find → whereUsed → explain(each)`.
  - **`describe()`** — the model card, **progressive disclosure, never a full catalogue** (at F0's scale a full dump is 20–400k tokens): default = the **skeleton** (root groups, depth-limited tree, counts + kind histograms — low-thousands of tokens even at 10k fields); `describe(group)` drills down; reduced-model extraction (parked) is the right-sized "context for one rule" on large models.
  - **`find(nameish)`** — candidate fields by name/id-segment/label match; deliberately dumb string ranking — fuzzy/NL interpretation stays the LLM's job.
  - **`whereUsed(field)`** — the inverse index: every rule/computation referencing the field, **slot-labeled per hit** (error field / errorCondition operand / precondition / operation / message parameter — the §5 vocabulary). Exact via the adapter's `getDirectlyReferencedEntities` (one kernel call per rule, **no converter coverage needed** — Phase-2 adapter skeleton); the API stays in the public-tier core with a **pluggable reference-resolver seam** (core ships a labeled heuristic; the adapter contributes the exact resolver — N8-clean).
  - **Delivery — files/stdout are the agent API** (owner direction): agents dump-then-grep, so the primary agent form is emitted artifacts: `model-card.md` (the only read-whole file), `fields.jsonl`/`groups.jsonl`/`rules.jsonl` (one line per fact; `rules.jsonl` carries raw condition text + `referencedFields`, marked `heuristic` until the adapter fills it exactly). `grep -i birthdate fields.jsonl` *is* the agent's `find`. Artifacts stamp the snapshot's model id/version.
  - **Stream-first emitters** (owner refinement): the dump primitive targets `Appendable`/stdout per artifact, git-style (stdout default, `--output` optional, artifact as subcommand) — pipes work; `dump(dir)` is convenience wiring. Honest caveat: JVM startup costs seconds per invocation (unlike git), so the recommended agent loop is **dump-once-then-grep** (a redirect).
  - **Scope guard:** structured projections + plain files only — no embeddings, no semantic search, no MCP/CLI product, no doc generation; grep is the query language.
- **F10 — Ingest: text → rulekit AST** *(new in v2)*
  - Parse existing rule/computation text via the kernel adapter (`IRuleParser` → `ICondition`), convert with a read-only visitor.
  - Incremental construct coverage, **fail-fast** on unconverted constructs — never silent loss.
  - Converted nodes carry **provenance** (origin: text) and the author's **path-notation style** (`PathNotationConfig`) as a rendering hint.
- **F11 — Kernel-version tolerance: bias to acceptance, never a self-inflicted false positive** *(added 2026-06-04)*. rulekit binds only the kernel's public surface and runs against whatever kernel version is on the classpath — which may be **newer** than the one its catalog was verified against (the verification floor, currently `30.8.1`; the floor only ever rises, so older-than-floor is a future-release concern, not a runtime one). The governing rule: **the live kernel is the sole authority on rule validity; rulekit's own layers must never be the only thing that declares a rule corrupt.**
  - **Pre-checks split by nature.** Construction-completeness checks (a rule has a name, condition, error field, code, severity, message) may hard-fail — they are about rulekit's own API contract, version-independent. Checks that *mirror a kernel judgment* (e.g. `MVK_ERROR_FIELD_NOT_REFERENCED` via the `covers()` heuristic) **degrade to deferred diagnostics surfaced beside the live kernel verdict**, never a hard `build()` throw — so a heuristic that drifts from a newer kernel cannot block a rule the kernel itself accepts.
  - **Version skew is detected and surfaced, not fatal.** rulekit reads the running kernel version at runtime (`Package.getImplementationVersion()` off any kernel class — spike-confirmed `30.8.1`, spec `30.8`) and compares it to the catalog's stamped `verifiedAgainst`. A mismatch yields **one INFO/WARNING** ("authoring is safe; unmodeled operators or changed semantics may exist"), never an error.
  - **Ingest preserves the unknown** (F10) *(✅ 2026-06-04)*. A not-yet-modeled construct is kept as **`Ingest.Opaque(canonicalText, reason)`** that re-renders verbatim — never reported as corrupt; `KernelAdapter.ingest`/`ingestAll` return the sealed `Ingest` (`Converted`/`Opaque`), so a user's whole DM ingests safely.
  - **Drift is caught at rulekit's build time, not the user's runtime.** N7 (every catalog example through the real kernel) and the keyword-parity anchor (N3) run against the kernel on the classpath, so rebuilding rulekit against a newer kernel pinpoints exactly what drifted; a CI matrix (floor + latest) is the standing detector.

### Non-functional

- **N1 LLM-friendliness** — small consistent surface, static-import factory, rich examples, fail-fast messages, a validate entry point, a Claude Code skill, the catalog as readable data.
- **N2 Polyglot-ready** — language-neutral contract (catalog + DM-JSON + render rules); Java now, TS later as a parallel implementation. (→ §13.)
- **N3 Faithful to the kernel** — never reimplement the grammar's or type system's truth; the kernel is the oracle. Coverage tests assert catalog ↔ kernel parity from **three independent anchors**: the bilingual lexer terminals (per shared token constant — forward `KeywordParityTest`), the parsetree-api **construct-name enums** (✅ reverse `adapter.KernelConstructParityTest` 2026-06-04: every enum member cataloged or explicitly excluded, so an operator the kernel *adds* fails loudly — the kernel-evolution early-warning), and the `MVK_*` error-code enum (each member mapped or explicitly excluded — the **MVK ledger**: classified as erased-by-types / pre-checked / kernel-delegated / pending / unknown→experiment queue).
  **Constraint mining (2026-06-04, owner direction):** structural laws are swept proactively from their enumerable sources rather than discovered by accident — slot parity vs the public `IRule`/`IComputation` accessors, the MVK ledger above, the **`a12-kernel-mm` metamodels** (the source of truth for valid DM structure: schema + constraint-rule oracles; read-only, own-words analysis per the EUPL boundary), and deserializer-tier checks. The canonical `a12-kernel-demo/KERNEL-SEMANTICS.md` is consulted (read-only) before each increment; kernel behaviors it lacks are recorded, experiment-verified and test-locked, in our own `KERNEL-FINDINGS.md`; rulekit's treatment stays in `KERNEL-INTEGRATION-NOTES.md`.
- **N4 No internal systems / no machine-specific data** — public OSS sources via the `a12-kernel-bootstrap` `mavenLocal` flow; publishable docs/source.
- **N5 Immutability** — all expression nodes immutable (safe composition; cheap transform-modify).
- **N6 Semantic self-sufficiency** — rulekit's API + bundled material (catalog, language-semantics layer, skill, examples, validation messages) is a **complete, self-contained source for authoring A12 rules**; an LLM shouldn't need the kernel BA docs. Verified by a **self-sufficiency eval** (§14). Compatible with N3: we *describe* semantics as data; the kernel *executes* and CI keeps the description honest.
- **N7 Self-verifying catalog** — every catalog `validExample`/`invalidExample` runs against the real kernel in CI. v2 adds two **round-trip oracles** at test tier: the **differential render check** (`kernelFormat(kernelParse(rulekitRender(ast)))` equals rulekit's canonical text) and the **DE-translation check** (rulekit's DE rendering vs the kernel's `formatAndTranslateConditionText`).
- **N8 Tiered kernel-API policy.** Three tiers:
  - **Core (shipped, polyglot):** public kernel surface only (`IDocumentModel`, DM-JSON, `kernel-md-facade`). No kernel-internal type in any rulekit public signature, ever.
  - **Tests:** internal-tier use allowed (fixtures, oracles, parity sources), provided nothing internal leaks into `src/main` or public signatures.
  - **Kernel-adapter (opt-in, Java-only module):** may consume the kernel's `a12internal` **read** surface — the `@OnlyForUsage` AST interfaces, `ASTVisitor`, `IRuleParser`, `IConditionFormatter`, the `IMVK_Service` facade via `getMvkServiceForModel` — under four guardrails: **boundary conversion** (kernel types never escape: text in → rulekit AST out), **read-only** (never construct/mutate kernel `…Impl` classes), **pin + canary** (pinned kernel version + adapter CI job failing loudly on upgrade breakage), **fail-fast coverage** (`visitGeneral` rejects unconverted constructs).
- **N9 Offline core** — build, render, and model pre-checks run with no kernel call; the kernel (from `mavenLocal`) is needed only for the opt-in validate step and the adapter.
- **N10 Two-persona LLM support** *(2026-06-04)* — LLM assistance serves both the **developer** and the **modeller/BA**, with *different interactions over one engine* (core + adapter). The developer needs **no bespoke vehicle**: Claude Code edits rulekit-the-library inside the developer's own project, where the project's own compiler/IDE supplies the compile-time type-steering — rulekit adds nothing for it beyond being well-shaped (N1). The modeller/BA — who must **not** be handed a JDK, `.m2`, Gradle, or JBang — gets a **standalone CLI** (verbs + JSON in/out; the JVM bundled and invisible), with the same safety delivered at runtime via the builder's pre-checks plus the kernel `Diagnostic`s (F4a). Since the kernel is the oracle and is Java, "no Java toolchain" means the JVM is *bundled, not absent* — packaging + code-signing + **kernel-redistribution licensing** are the real costs (gated on §19). The earlier compile-first JBang / `java --source 21` idea is **not** a shipped developer feature (an in-repo developer doesn't need it; a BA can't use it); it survives only as an internal mechanism for the N6 eval harness. (→ §14, §16.)

---

## 4. Non-goals / scope boundaries <a name="4-non-goals"></a>

- **Not a validation engine.** The kernel is the engine; rulekit is a typed front-end and edit layer over the language the engine consumes.
- **Not a workbench over the kernel's AST.** The kernel's `ICondition` tree is effectively immutable, internal-tier, and ergonomically alien (survey §1) — it is the adapter's *transport format*, never the representation users or LLMs touch. All work happens on rulekit's own AST.
- **Not a hand-written parser.** rulekit never parses DSL text itself; the read direction is delegated to the kernel's parser through the adapter (F10). (This retires v1's "not initially a parser" hedge — there will be *no* rulekit parser, by design.)
- **v1 scope does not build model *structure*.** rulekit binds to an existing `DocumentModel`; if model construction is ever added, it emits public DM-JSON (fork #8, D9). *(The fork-#8 case — modelkit as the rulekit peer and the formalize-the-knowhow thesis behind it — is developed in the addendum [`MODELKIT-PROPOSAL.md`](MODELKIT-PROPOSAL.md); pre-spike, no build commitment.)*
- **Not a new validation language.** 1:1 to the existing A12 DSL; no invented semantics.
- **Not an editor.** No Monaco/UI work; if rulekit output ever feeds the official editor, the integration contract is the editor's existing string + async-callbacks API (survey §15).

---

## 5. Background — the DSL, and what the kernel provides <a name="5-background"></a>

Authoritative detail lives in the sibling repos: `a12-kernel/KERNEL-GRAMMAR.md` (grammar + failure→fix table), `KERNEL-USE.md`, the merged BA guide, and `a12-kernel-demo/KERNEL-SEMANTICS.md` (evaluation/truth model). What the builder must model:

- **"Rules as data."** The portable artifact is DocumentModel JSON; each rule carries `errorCondition` text. A rule states the **error scenario** — `true` ⇒ invalid.
- **Construct families** (catalog-driven; corpus-prioritized — see the backlog in [`WORKSPACE-CORPUS-ANALYSIS.md`](./WORKSPACE-CORPUS-ANALYSIS.md)):
  - **Predicates:** `FieldFilled`/`FieldNotFilled`; field-list quantifiers (`AllFieldsFilled`, `NoFieldFilled`, `AtLeastOneFieldFilled`, `NotExactlyOneFieldFilled`, `NotAllFieldsFilled`, `FieldsNotCollectivelyFilled`); group variants; value-list membership.
  - **Operators:** comparison (`== != > < >= <=`, the fixed tolerance variants `DiffersWithToleranceRange{1,2,5,10}`), logic (`And`/`Or` — **no `Not`**), arithmetic (`+ - * / ^`, `{ }` grouping), field value `[Field]`.
  - **Functions:** `Length`, `RangeAsString`/`RangeAsNumber`, `Abs`, rounding, aggregates (`Sum`, `SumOfProducts`, `Max/Min`, `NumberOfFilledFields`, `NumberOfDifferentValues`, `FirstFilledValue`), the date family (`Today`, `Date(...)`, `DifferenceIn*`, `Add*`, `Valid`/`Invalid`, ranges), `PatternMatched`/`PatternViolated`, conversions (`FieldValueAsString/AsNumber`, `ValueAsDate`).
  - **Higher-level / structural:** paths (absolute `/a/b`, relative `..`, short names), `*`/`**` flatten, `Having` filters, `$` correlation, `CurrentRepetition`, parallel iteration, semantic index `For`, `RepetitionNotUnique`, enum categories `->`, `CustomCondition`, error-text parameters.
  - **Computations:** `commonPrecondition` + mutually-exclusive `(precondition, operation)` alternatives, with self-validation and scale discipline.
- **Cross-cutting gotchas** rulekit encodes or guards: keyword-name collisions, error-field-referenced, rule-must-live-in-a-group, three-valued null semantics, decimal-scale discipline, `Date()` operand formats, bilingual keywords via `conditionLanguage`, `;;` comments and case-variant keywords in real-world text (corpus finding — the *ingest* path must tolerate them; the kernel parser does).

### The DSL surface — the slots <a name="dsl-slots"></a>

DSL text lives in several model slots, of two kinds:

| Slot | Kind | Returns | Context needed | Slot-specific rules |
|---|---|---|---|---|
| `Rule.errorCondition` | **condition** | boolean | rule's group; error field | error field referenced; rule in a group; no negation |
| `Computation.commonPrecondition` | **condition** | boolean | computed field's group | gates the whole table |
| `ComputationAlternative.precondition` | **condition** | boolean | computed field's group | alternatives mutually exclusive; computed field not referenced; no `CustomCondition` |
| `ComputationAlternative.operation` | **calculation** | computed field's type | computed field type **+ scale** | type & scale match; empty-input defaults; `Now` self-fires; no `RepetitionNotUnique` |

One expression language, two kinds — **`Condition`** (boolean) and **`Calculation`** (typed value) — placed into slots through two authoring objects, **`Rule`** and **`Computation`**. Consequences: combinators reuse across slots for free (F5); model-awareness supplies each slot's context (§5a); slot-specific constraints are layered per-slot checks (§11). The kernel itself round-trips these slots snippet-wise (`parseConditionSnippet`/`parseOperationSnippet`, `formatConditionSnippet`/`formatOperationSnippet` — survey §4/§7), confirming the slot model.

### 5a. Model-awareness is foundational <a name="5a-model-awareness"></a>

**rulekit is model-bound at its core — not a model-agnostic string builder.** A rule condition is only meaningful relative to a position in a specific DocumentModel. The model is load-bearing for:

1. **Rule location** — relative paths, short names, and the error-field relationship resolve relative to the rule's group; the same logical condition renders to different text depending on where the rule sits.
2. **Path resolution & form** — validity and unambiguity of absolute/relative/short paths depend on the group tree.
3. **Operand types** — `[Quantity] <= 0` needs a NumberType; `-> Category` an existing enum category. Type-safe combinators require field types from the model.
4. **Repetition / iteration** — `*`, `Having`, `$`, parallel iteration, `For`, `RepetitionNotUnique` depend on which groups repeat and their index fields. A rule iterates iff its **error field is repeatably referenced** — decided by the error field's position in the model, unknowable model-agnostically.
5. **Enum domains** — allowed values and categories come from the model.
6. **Cross-checks** — error-field-referenced, keyword collisions, rule-in-group.

**The model index is cheap and fully public-tier** (verified against `kernel-md-model-api` 30.8.1): group tree (paths, repeatability via `IGroup.getRepeatability()`, index fields), field kinds via `instanceof` on `IFieldType` subtypes (the interface itself is a bare marker — the §5b "no readable type tag" fact), `IField.getEffectiveType()` for TypeDef resolution, number scale via `INumberType.getMaxFractionalDigits()`, enum values/categories, date formats/precision, plus model config (`isFieldRefByShortNameAllowed`, `getConditionLanguage`, base year). The index is defined over **DM-JSON content**, so Java and a future TS implementation build the same index from the same data.

**Bind to an expanded model.** Models may `include` others and import type-definitions; these are not flattened on deserialize. rulekit accepts an already-expanded model **or** model + caller-supplied `IDocumentModelReferenceResolver` (single method, caller owns artifact location) and runs `IDocumentModelService.expand(...)` itself; it fails fast on unresolvable references rather than indexing a half-resolved model.

**Binding levels.** **L2a — runtime typed refs** is the v1 core (fork #7): load a model, get typed handles via runtime lookups (`m.num("/Order/Quantity")`, `m.group(...)`, `m.enumOf(...)`, `m.date(...)`); no codegen, works on any model an LLM loads on the fly. **L2b — codegen typed refs** (jOOQ/QueryDSL-style per-model classes; the kernel's typed-accessor generator is precedent) is the Phase-3 enhancement. **L1 — model-validated strings** is subsumed by L2a's lookup surface.

### 5b. The type system — operand types are first-class <a name="5b-type-system"></a>

The A12 DSL is **strongly, statically typed at model-conversion time**, and the bulk of the `MVK_*` family is *type* rejection. The kernel exposes **no readable value-type tag** — legality lives in the parser's check layer and the BA guide's tables — so **rulekit owns the type lattice** (in the catalog), reconstructed from those sources and kept honest by CI-validated examples whose *invalid* cases are largely type violations.

**The twelve authoring-relevant field types** (model-api; `Unspecified` is `@Experimental`, excluded):

| Field type | Value kind | Config that changes operator legality |
|---|---|---|
| `IStringType` | string | pattern, min/max length |
| `INumberType` | number **+ scale** | max-fractional-digits (gates `==`/`!=`), min/max, trait (amount/percent/permille) |
| `IEnumerationType` | enum value, ±texts | values, **categories** (enable `->`), texts (gate comparability) |
| `IBooleanType` | boolean (True/False/unset) | unset ⇒ comparison not evaluated |
| `IConfirmType` | confirm (True/unset) | unset ⇒ False; **distinct kind** from Boolean |
| `IDateType` | date | format, datePrecision |
| `IDateTimeType`, `ITimeType` | date-time, time | sole operands of the `…Hours/Minutes/Seconds` family |
| `IDateFragmentType` | partial date | fragment format gates comparability + `Add*` |
| `IDateRangeType` | date-range | only `==`/`!=`; not nestable |
| `ICustomFieldType` | string-equivalent | name (used by `Valid(F,"Name")`) |
| `ITypeDefType` | delegate | resolved type governs (needs expansion — §5a) |

**Comparability rules the builder enforces:** ordering only for numbers/amounts/dates (fragments per year-presence matrix; base year lifts it); string ↔ *textless* enum equality only — text-bearing enums compare by **stored value**, never display text; number `==`/`!=` requires equal declared scale; Boolean and Confirm are not cross-comparable; date-ranges only same-format. **Conversions are explicit** (`FieldValueAsString/AsNumber`, `RangeAs*`, `ValueAsDate`); literals are typed, with the documented date-constant ambush (`"30.11." + [Year]` parses the string as a date constant) avoided by construction in the renderer.

**Scale pre-check boundary (D13):** rulekit pre-checks decimal scale only for the arithmetic-free case (two declared-scale fields); a calculation's *result* scale is parser-computed (`*` sums, `+/-` max, `/` unpredictable, `Round(…,z)`→`z`) — predicting it would reimplement engine truth (N3), so the kernel arbitrates and rulekit steers toward explicit `Round*`.

**v1 depth (D12):** common kinds first-class (number-with-scale, string, date, boolean, confirm, enumeration with categories); Custom string-like; TypeDef a delegate; DateTime/Time/DateFragment/DateRange phased in with their operators.

### 5c. What the kernel already ships (and what it doesn't) <a name="5c-kernel-inventory"></a>

*New in v2 — the finding that reshaped this proposal. Full inventory with verification status: [`KERNEL-RULE-API-SURVEY.md`](./KERNEL-RULE-API-SURVEY.md).*

**The kernel already ships the entire read half of the bidirectional goal**, behind interface-only `a12internal` API modules reachable in one call from the md-tier model rulekit already holds (`DocumentModelService.getMvkServiceForModel(model, variant)` → `IMVK_Service`):

| Capability | Kernel API | rulekit use |
|---|---|---|
| text → model-resolved AST | `IRuleParser.parseConditionNoExpand(rule, text, pr)` → `ICondition` (+ snippet variants for computation slots) | **F10 ingest** — the adapter's entry |
| the AST itself | `kernel-core-parsetree-api`: ~40 `@OnlyForUsage` node interfaces; `IEntityId` carries resolved paths, iteration/wildcard semantics, enum categories, `Having` filters, **path-notation style** (`PathNotationConfig`; kernel default = `RELATIVE`) | converter input; provenance hints |
| traversal | `ASTVisitor` (~86 methods, default-`true`) | the converter visitor; coverage checklist |
| AST → text | `IConditionFormatter.formatConditionText` / `formatConditionPart` / snippets | differential render oracle (N7) |
| en↔de translation | `formatAndTranslateConditionText` | DE-dialect oracle (N7) |
| per-rule validation | `hasValidConditionText(rule, pr)` | F4 fast path (adapter/CI) |
| entity-rename refactoring | `changeConditionText(rule, old, new, pr)` (+ bulk variant) | delegated bulk rename over foreign text (note: interface `@Deprecated` since 30.6.0 — pin + canary) |
| rule analysis | `getDirectlyReferencedEntities`, `referencesIteration`, `getEntityInRule`, mandatory-info/model-changes services | F9 cross-check oracles |
| computations | `ICalculationService.checkCalculationRule`, **`checkForCycle`** (dependency cycles with path), `extractCalculationInfo` | F2 validation; computation ingest |
| construct inventory | parsetree-api name enums (with runtime names); `ParserErrorTypesEnum` (~100+ `MVK_*`) | N3 parity anchors |
| bilingual semantics prose | `kernel-core-parser-web` content catalogs (~120 entries, name+description in EN/DE) | catalog **drift-detection oracle** — ⚠️ EUPL content: compare in opt-in tests, never copy verbatim |

**What the kernel does *not* ship — rulekit's actual work:** a public/stable construction or mutation API for that AST (it is effectively immutable — 14 setters total, constructor-assembled), any readable type lattice, any catalog of operator semantics as data, model-aware typed authoring, and anything TypeScript-side (the TS stack has **no AST at all** — verified). The division of labor is exact: **the kernel reads and arbitrates; rulekit represents, types, teaches, and writes.**

---

## 6. The key reframe — "jOOQ for the A12 DSL", with the parser already written <a name="6-the-key-reframe"></a>

**This project is to the A12 DSL what jOOQ is to SQL**: a typed DSL that composes expressions and renders them to a textual target language, validated by a separate engine, with dialects (EN/DE), optional codegen of typed refs from a schema (the model), and a parser for the reverse direction. The analogy now extends further than v1 knew: jOOQ shipped its SQL parser years after the core; **rulekit's "parser" exists on day one** — it is the kernel's own, reached through the adapter, so the reverse direction costs a converter visitor instead of a parsing project.

The contrast with Zod/Pydantic stands: they *are* the engine; we are not. Our validation responsibility is to prevent the representable mistakes and ask the real engine for the rest. And one principle the survey made non-negotiable: **one tree to work on.** Text comes *in* through the kernel's AST and is converted out of it at the boundary; builders construct the same rulekit AST directly; everything downstream (explain, transform, render, diff, validate) sees a single representation. Working on the kernel's tree, or on raw text, is ruled out (§4).

---

## 7. Prior art <a name="7-prior-art"></a>

| Framework | Teaches us | Concrete take-away |
|---|---|---|
| **jOOQ** (Java) | the reference architecture | typed nodes (`Field<T>`/`Condition`), `DSL.*` static factory, dialects (→ EN/DE seam), schema codegen (→ L2b), parser-for-the-reverse-direction (→ ours is the kernel's, via the adapter) |
| **Zod** (TS) | functional composition + LLM ergonomics + the TS surface to target | combinators-as-values, `.refine` (→ `CustomCondition`), schemas-are-code (→ rebuild over text surgery) |
| **JSON Schema** | the portable data contract | rules-as-data + per-language implementations = our polyglot model; its hand-authoring pain justifies a typed front-end |
| **Pydantic** (Python) | declarative core-schema → multi-output | a serializable IR drives validator + docs + LLM context (→ our catalog) |
| **FluentValidation** (.NET) | OO fluent surface, rule grouping | `.When()/.Unless()` (→ guards/preconditions), reusable validators (→ combinators) |
| **Bean Validation / Hibernate** (Java) | Java-native mechanics + i18n | programmatic fluent API precedent; `MessageInterpolator` (→ bilingual messages) |
| (QueryDSL) | schema→typed-refs codegen | same L2b lesson; the kernel's typed-accessor generator is the in-house precedent |

**Synthesis:** Zod/FluentValidation ergonomics, Pydantic/JSON-Schema IR discipline, jOOQ architecture. Same catalog/AST contract, two idiomatic surfaces (Java: jOOQ-flavored; TS: Zod-flavored), identical canonical output.

---

## 8. Architecture <a name="8-architecture"></a>

Six components; the **catalog** is the language-neutral spine, the **kernel bridge** is the two-tier seam to the engine.

```
                 ┌────────────────────────────────────────────────────┐
                 │ (1) OPERATOR CATALOG — language-neutral JSON          │
                 │ ids · EN/DE keywords · kinds (type lattice) · arity   │
                 │ meaning · constraints · gotchas · valid/invalid ex.   │
                 └──────┬──────────────────┬──────────────────┬────────┘
              drives    │           drives │           drives │
        ┌───────────────▼──┐   ┌───────────▼────────┐   ┌─────▼──────────────┐
        │ (2) MODEL INDEX   │   │ (3) TYPED AST       │   │ docs · LLM skill ·  │
        │ public IDocument- │──▶│ sealed interfaces + │   │ self-sufficiency    │
        │ Model / DM-JSON:  │   │ records; immutable; │   │ eval (§14)          │
        │ paths·kinds·scale │   │ catalog-ids + model │   └────────────────────┘
        │ repetition·enums  │   │ refs + provenance   │
        └───────────────────┘   └──┬───────────▲──────┘
                                   │ render    │ convert (F10)
                            ┌──────▼─────┐  ┌──┴─────────────────────────────┐
                            │ (4) RENDERER│  │ (5) KERNEL BRIDGE               │
                            │ catalog-    │  │  core tier (public, shipped):   │
                            │ driven;     │─▶│   DM-JSON inject + expand +     │
                            │ EN/DE; path │  │   checkConsistency (F4)         │
                            │ form + auto-│  │  adapter tier (Java-only, N8):  │
                            │ quoting (F3a)│ │   parse-in (F10) · per-rule     │
                            └─────────────┘  │   validate · format/translate   │
                                             │   oracles · delegated rename    │
                                             └──────────────┬─────────────────┘
                                                            ▼
                                             (6) INTEGRATION — strings for rule
                                             builders · whole Rule/Computation
                                             DM-JSON nodes · rendered diffs
```

- **(1) Catalog** — single source of truth for everything operator-specific; nothing hard-coded in Java (→ §9, §13).
- **(2) Model index** — the §5a facts, built from public surface only; shared semantics with the future TS twin via DM-JSON.
- **(3) Typed AST** — immutable nodes of two kinds (`Condition`, `Calculation`), **sealed interfaces + records, exhaustive pattern-matching switches** (Java 21 idiom). Every node carries its **catalog operator-id** and **model-resolved field refs** (D14 — what makes explain/modify cheap projections), plus **provenance** (built vs converted-from-text, with optional path-notation hints). Node taxonomy is designed *with the kernel's parsetree taxonomy in view* (conditions: composite/comparison/entity-list/value-list/validity/application/root-group; operations: composite/field-ref/constant/aggregate/group/date-family) so the F10 converter is a structural mapping, not an impedance fight — expressed in rulekit's own clean, typed, English vocabulary.
- **(4) Renderer** — catalog-driven AST→text; EN default, DE free; model- and rule-group-aware path computation (fork #9) and keyword auto-quoting.
- **(5) Kernel bridge** — *core tier:* the polyglot, public validate path. *Adapter tier:* the N8 kernel-adapter module — parse-in, fast per-rule validate, format/translate oracles, delegated bulk rename. Kernel types never escape it.
- **(6) Integration & LLM artifacts** — strings and DM-JSON nodes out; rendered diffs for every modify; the skill + catalog + javadoc.

---

## 9. The operator catalog (the spine) <a name="9-operator-catalog"></a>

A serializable registry — one entry per kernel construct — that drives the builder, the renderer, the docs, and the LLM context. Illustrative entry shape:

```jsonc
{
  "id": "DiffersWithToleranceRange",
  "kind": "operator",                       // predicate | operator | function | aggregate | path-op | structural
  "keywords": { "en": "DiffersWithToleranceRange{N}", "de": "UngleichMitToleranz{N}" },
  "variants": [1, 2, 5, 10],
  "operands": [ { "type": "number" }, { "type": "number" } ],
  "returns": "boolean",
  "meaning": "true (the rule fires) iff |left − right| is greater than N",
  "edgeCases": [ "an unset number operand defaults to 0 (see the null-semantics layer)" ],
  "constraints": [ "both operands must be number fields/expressions" ],
  "gotchas": [ "no general tolerance N — only the fixed 1/2/5/10 variants exist" ],
  "validExample":   { "dsl": "[Quoted] DiffersWithToleranceRange5 [Paid]", "note": "fires when they differ by > 5" },
  "invalidExample": { "dsl": "[Name] DiffersWithToleranceRange5 [Paid]", "error": "operand type", "fix": "both sides must be numbers" },
  "relatedTo": [ "Abs", "comparison operators" ],
  "docRef": "VL guide → Operators → Comparison operations",
  "sinceKernel": "30.8.x"
}
```

Two parts: **per-operator entries** (above — `meaning` + edge cases + valid *and* invalid examples teach correctness, not just spelling) and the **language-semantics layer** (cross-cutting, bundled): error-scenario inversion; per-type null handling (number→0 except min/max, confirm→False, others not-evaluated); the formal-error "unknown" state (`FieldFilled(F) Or FieldNotFilled(F)` is *not* a tautology); no-negation and why predicate pairs aren't complements; decimal discipline + rounding; iteration & relevance model; computation self-validation; error-text parameters; path-resolution + quoting rules. The prose basis exists (`KERNEL-SEMANTICS.md`, `KERNEL-GRAMMAR.md`); rulekit structures and CI-verifies it.

**Kept honest from four directions** (v2 widens v1's two):

1. **N7 example execution** — every example through the real kernel in CI.
2. **Keyword parity** — catalog ↔ `LexerTerminals_{en,de}` per shared token constant (the bundles are not 1:1; reconcile per token, including case variants like `and`/`AND`). **✅ forward direction built 2026-06-04** (`catalog.KeywordParityTest`, bundles loaded from the classpath): every catalog/stub keyword is a real lexer terminal, or a language-independent symbol (`<=`/`->`). The reverse direction (every terminal mapped or excluded) and per-token-constant case-variant reconciliation remain.
3. **Construct parity** *(new)* — catalog ↔ the parsetree-api **name enums** (`EntityListConditionName` et al.): every enum member mapped or explicitly excluded; same for `ParserErrorTypesEnum` against the failure→fix table.
4. **Semantics drift detection** *(new, opt-in)* — diff the catalog's EN/DE keywords and `meaning` texts against the kernel's web **content catalogs** (~120 bilingual descriptions). ⚠️ EUPL boundary: compare only, never copy verbatim — rulekit's texts stay its own.

**Stub-entries policy** *(adopted 2026-06-04).* The catalog is a complete **map of the kernel operator space**, not only the subset rulekit builds — so an LLM (and `describe`/docs) can see that an operator *exists and is deferred* rather than hallucinate a handle. Each entry carries an `OperatorStatus`: **`SUPPORTED`** (rulekit has a typed handle; an N7-validated `validExample` is **required** — enforced by `OperatorCatalogTest.everySupportedEntryHasAValidExample`, so a supported operator can't silently escape N7) or **`PLANNED`** (a *stub*: keywords grounded in the lexer bundles + `meaning`/`returns`/`kind`, but **no example** — exempt from N7, which already filters example-less entries). An absent `status` defaults to `SUPPORTED`, so existing entries are unchanged. Seed stubs (2026-06-04): `RangeAsString`, `Date`, `Now`, `Valid`/`Invalid`, `SumOfProducts`, `FirstFilledValue`, `DateRange`/`DateRangesOverlap` — the known-but-unbuilt operators from the corpus histogram and the date/aggregate findings. Building a stub = flip it to `SUPPORTED` and add the handle + example.

---

## 10. API design sketch <a name="10-api-design-sketch"></a>

Illustrative (final names from the catalog); jOOQ-functional `Dsl.*` static-import core (fork #5), real package `io.github.mbackschat.a12.dm`. Bare strings denote model-resolved handles; the renderer derives actual path forms (fork #9).

```java
import static io.github.mbackschat.a12.dm.Dsl.*;

// --- author (builder direction) ---
var m = BoundModel.load(dmJson);                       // expanded + indexed (F0)
Condition qtyPositive = fieldFilled(m.num("Quantity")).and(m.num("Quantity").le(0));
// renders: "FieldFilled(Quantity) And [Quantity] <= 0"

Aggregate rushUnits = sum(m.nums("Lines*/Seconds")).having(m.bool("Lines/Rush").isTrue());
Computation bonus = compute(m.num("Bonus"))
    .when(m.num("WeekPay").ge(1000)).calc(roundDown(m.num("WeekPay").times(0.10), 2))
    .otherwise(literal("0.00"));

// --- combinators: parameterized fragments are ordinary methods ---
static Condition mandatoryWhen(FieldRef f, Condition when) { return when.and(fieldNotFilled(f)); }

// --- ingest (text direction, F10 — kernel adapter) ---
Rule existing = m.rule("/Order/QuantityPositiveRule");          // a foreign, BA-authored rule
Condition parsed = KernelAdapter.ingest(existing, m);            // kernel parses → converter → rulekit AST
Explanation ex = Explain.explain(parsed, m);                     // F9: fields+types+roles, operators+semantics, iteration, gloss

// --- modify (one tree, reviewable diff) ---
Condition changed = parsed.transform(swapThreshold(0, 1));       // #1 in-AST transform
RenderedDiff diff = Diff.render(parsed, changed, m);             // always reviewable
ValidationResult vr = Kernel.validate(changed, existing, m);     // F4

// --- integrate ---
String dsl = changed.render();                                    // canonical EN; .render(DE) for German
m.replaceRuleCondition(existing, changed);                        // emits via public DM-JSON surgery
```

The deliberate absence of `.not()` stands — the kernel has no negation, so the API shape encodes the constraint (positive/negative predicate families instead).

---

## 11. Validation strategy <a name="11-validation-strategy"></a>

**Three lines of defense, plus a fast lane:**

1. **Unrepresentable in the typed API** — operand typing, error-field auto-referenced, typed path refs, no-negation. Caught at Java-compile time.
2. **Model pre-checks** — because rulekit knows the model: number-default-0 trap, Boolean-vs-Confirm, enum value/text/category validity, decimal-scale for the arithmetic-free case (D13), path resolution + keyword auto-quoting. Reported locally with actionable messages.
3. **Real-kernel confirmation (shipped path, public tier):** inject the rendered rule as a `{"type":"Rule", …}` element into the target group's DM-JSON, re-deserialize, `expand`, **`checkConsistency`** — `MVK_*` arrive as notifications, no code emitted, no compile (verified). Map failures to the `KERNEL-GRAMMAR.md` fixes. Polyglot: the TS twin does the same JSON surgery. (Auto-inject placeholder error messages per locale so `MVK_ERROR_MESSAGE_MISSING` doesn't mask condition diagnostics; not every code is `MVK_*`-prefixed — extraction is best-effort. See `KERNEL-INTEGRATION-NOTES.md`.)
4. **Fast lane (adapter tier, opt-in):** **`hasValidConditionText(rule, pr)`** validates one rule in-memory — no serialize/re-deserialize cycle per candidate. Used by the N7 harness and interactive loops; the public path remains the final gate. Computations: `isValidComputation` / `checkCalculationRule`, incl. **`checkForCycle`** for computation dependency cycles (a class invisible to model-local pre-checks).

Rejected alternatives stand: reimplementing semantic checks (drift-prone duplication, violates N3) and render-only (weak guarantees).

---

## 12. Modify — the bidirectional edit loop <a name="12-modify"></a>

v1 treated "modify" as three speculative capabilities and built only the cheapest. v2, with bidirectionality a declared goal and the read path delegated to the kernel, makes the **edit loop core scope**:

```
ingest (F10: kernel parse → convert)  ──▶  rulekit AST  ──▶  explain (F9)
        ▲                                      │
        │ (or: built directly via Dsl.*)       ▼ transform (#1) / re-express (#2)
        │                                  modified AST
        └── foreign rule text              ──▶ render ──▶ rendered diff ──▶ validate (F4) ──▶ DM-JSON
```

| Capability | What | Status in v2 |
|---|---|---|
| **#1 Transform** | edit an AST (add a conjunct, swap a threshold) via immutable `with…`/transform combinators | **Phase 1** (free with N5) |
| **#2 Re-express + diff** | LLM reads `explain(...)`, rebuilds via the builder, diff old-vs-new rendered text | **Phase 2** |
| **#3 core: ingest** | foreign text → kernel `ICondition` → converter → rulekit AST (F10) | **Phase 2** (was Phase 5 in v1 — re-priced by the survey: a converter visitor, not a parsing project; re-gated when bidirectionality became a goal) |
| **#3 residual: minimal-edit re-render** | untouched subtrees keep the author's exact layout/comments | **Phase 5, research-gated.** Re-render canonicalizes: the kernel AST stores no whitespace/comments. Two mitigations exist: per-reference **path-notation style** *is* recorded (`PathNotationConfig`) and converted nodes keep it as a rendering hint; and `formatAndTranslateConditionText` lets a team normalize a whole model once, after which canonical re-render produces clean diffs. |

**Source-of-truth posture (amends v1's Q1/D4):** foreign rule text is a supported **input**; **rulekit's AST is the canonical working representation** (you never mutate text or the kernel tree); rendered text + DM-JSON are the **output**. "Builder code as truth" survives as: the working form is ours, and rebuild-over-string-surgery remains the default mental model. Every modify — whatever kind — produces a **reviewable rendered diff**.

**Delegated refactoring:** for bulk entity renames over foreign text, the kernel's `changeConditionText` (notation-style-preserving) is available through the adapter — used where rulekit ASTs aren't in play; where they are, re-render from the AST wins. (The interface is `@Deprecated` since 30.6.0 — exactly the instability N8's pin+canary prices in; prefer the AST route.)

---

## 13. Polyglot strategy (Java now, TypeScript later) <a name="13-polyglot"></a>

**The one rule:** all kernel-operator knowledge lives in the language-neutral **catalog**; nothing operator-specific is hard-coded in Java. Language-neutral: catalog + render rules + DM-JSON model index semantics. Per-language: the fluent surface and AST encoding — Java (jOOQ/FluentValidation-flavored, sealed interfaces + records) now; TS (Zod-flavored) later, emitting identical canonical DSL.

**TS twin facts (survey-verified):** the TS kernel stack has **no AST** — so the twin is **build + render (+ model pre-checks) only**; there is no TS ingest path and no TS ground-truth validation until the kernel's TS runtime is buildable in the OSS setup (it currently isn't — bootstrap is Java-only). The adapter (F10) is **Java-only by nature and by design** — an optional module outside the polyglot core, so it doesn't constrain the twin. If the twin ever needed parsing, the editor stack's build tool + vendored ANTLR fork exist OSS-side; by design it won't (no rulekit parser, §4).

---

## 14. LLM coding-agent friendliness <a name="14-llm-friendliness"></a>

First-class, not an afterthought.

### Two personas, one engine (N10, 2026-06-04)

LLM support serves **two personas with different interactions over the same core + adapter**:

- **Developer — no bespoke vehicle.** A developer uses rulekit as a library *inside their own project*; when Claude Code helps, it edits the project's source and runs the project's build and tests. The compile-first type-steering (a wrong handle type fails `javac` before the kernel is touched) is simply *the developer's own compiler in their own repo* — rulekit needs to add nothing for it beyond being well-shaped (the qualities below). There is deliberately **no separate developer "execution vehicle"**; an LLM editing a project that already depends on rulekit has everything it needs. *(This corrects the earlier assumption that the LLM needs a special compile-first vehicle — that premise only ever held for the BA, where the answer is the CLI, not JBang.)*
- **Modeller / BA — the standalone CLI.** Rule work is usually a modeller's or business analyst's job, and they must never be handed a JDK, `.m2`, Gradle, or JBang. They get a **standalone CLI**: a single bundled artifact (JVM embedded and invisible), driven by an agent through verbs with JSON in/out (`describe`/`dump`/`ingest`/`explain`/`add`/`validate`/`diff`/write-back). The type-safety the developer gets at compile time, the BA gets at runtime from the same engine — the typed builder's pre-checks plus the kernel `Diagnostic`s (F4a). The CLI does **not** shell out to `javac` (that needs a JDK and reintroduces the toolchain); the LLM authors a rule as JSON, which the CLI deserializes *through the typed builder*. Real costs are distribution, not code: self-contained packaging (jlink/jpackage, ~JRE-sized), code-signing/notarization, and **kernel-redistribution licensing** (§19) — the last is the hard gate.

Both personas share these qualities:

- **Small, consistent, composable surface** (`Dsl.*` factory, names mirroring kernel operators), example-rich javadoc, polyglot naming parity.
- **The catalog as readable data** — the semantic ground truth.
- **A Claude Code skill teaching both interactions**: the developer loop (compose rulekit in your own repo, let the project compiler + `RuleDiagnostics` steer) and the BA loop (drive the CLI verbs), over the catalog + the `MVK_*` failure→fix table.
- **Fail-fast, actionable errors** mirroring the known fixes; a **validate entry point** for self-checking.
- **The full edit loop** (§12) as the agent workflow: *ingest → explain → re-express/transform → diff → validate* — over any rule in the model, not just rulekit-built ones.

### The rulekit is the teacher (N6)

Semantics captured at three tiers: **(A)** per-operator (catalog), **(B)** language-level (the bundled semantics layer), **(C)** by construction (the API shape itself — typed operands, no `.not()`, model-bound handles). **Describe vs. execute:** a complete *description* as data; the kernel stays the executor; coverage tests + CI examples + the drift oracles (§9) prevent divergence. **Measured** by the self-sufficiency eval: given only rulekit's material, an LLM must author *and correctly explain* a rule for each covered operator without the BA docs.

### Explainability (F9) — the introspection surface

`explain(rule, model)` assembles: referenced fields (path + model type + **role**: error field / operand / filter / index / `$`-correlated), operators used (catalog semantics travel with the rule), structural context (group; whether & why the rule iterates — the §5a error-field rule; severity; computation table where applicable), and an NL gloss. **Structured data first, gloss optional** (fork #12). It is a pure projection of catalog + model index onto the AST — near-free because of the D14 invariant (nodes carry catalog-ids + model refs), and it works identically for ingested foreign rules (F10). The kernel's own analysis services cross-check it (survey §9).

**The inverse direction (F9a, 2026-06-04):** an agent's entry point is usually a *name*, not a rule — "explain the rule on birthdate". The **`context` package** composes the answer from three projections: `find("birthdate")` → field + metadata, `whereUsed(field)` → the rules/computations touching it (slot-labeled), `explain(each)`. rulekit supplies the structure; the natural-language resolution and answer prose stay the LLM's work. `describe()` (the model card) gives the agent its map of the model without burning context on raw DM-JSON.

*Exploratory, parked:* a "what can legally come next" affordance via the kernel's ATN machinery, and reduced-model extraction (`createReducedDocumentModel`) for focused LLM context.

---

## 15. Feasibility, risks & mitigations <a name="15-feasibility-risks"></a>

| Risk | Likelihood | Mitigation |
|---|---|---|
| Catalog/semantics drift vs kernel | med | pin kernel version; the four honesty anchors (§9): CI examples, keyword parity, construct-enum parity, content-catalog drift oracle |
| **Adapter breaks on kernel upgrade** (`a12internal` has no stability guarantee; `IChangeRuleSupport` already deprecated) | med | confined to the opt-in Java-only module; boundary conversion keeps kernel types out of public signatures; pin + CI canary fails loudly at bump time (N8) |
| Converter coverage gaps (F10) | low | `ASTVisitor`'s ~86 methods are the checklist; fail-fast on unconverted constructs; coverage grows by corpus priority |
| Re-render blast radius on foreign rules | med | AST-as-working-form + rendered diff always; path-notation hints preserved; one-time model normalization via the kernel formatter; minimal-edit stays research (Phase 5) |
| **EUPL boundary** (web catalogs, `a12-kernel-mm`) | low | compare/reference only, never copy; opt-in tests read sibling clones; policy recorded in `CLAUDE.md` + survey §13 |
| Type lattice must be hand-encoded (no kernel type tag) | med | encode as catalog data; invalid examples (largely type violations) CI-verified against the engine |
| Over-abstracting for polyglot too early | med | Java first with a clean contract seam; TS is a parallel impl, not a refactor |
| TS validation depends on unbuilt TS kernel runtime | med | TS twin scoped to build+render; validation is a later milestone |
| Scope creep | high | strict phasing (§16); corpus-driven coverage order; exploratory items explicitly parked |

**Overall:** the core (build + render + validate, Java) was already clearly feasible; the survey upgraded the ambitious part — **bidirectional modify is feasible mostly by delegation**, with rulekit's own work concentrated where it adds unique value (the typed AST, the catalog, the model index, the converter).

---

## 16. Phased roadmap <a name="16-roadmap"></a>

- **Phase 0 — Design & catalog skeleton.** *Done:* this proposal (v1→v2), catalog schema, kernel pinned (`30.8.1`), build against `mavenLocal`, the API survey.
- **Phase 1 — Java vertical slice** *(✅ complete 2026-06-04)*: model index (L2a runtime typed refs) → catalog → typed AST (immutable + transformable #1, carrying the **D14 invariant**: catalog-ids + model refs + provenance) → model/rule-group-aware renderer (EN/DE, fork-9 group-relative paths) → validate via the real kernel → emit DM-JSON. Operator coverage landed in **corpus-backlog order** (`WORKSPACE-CORPUS-ANALYSIS.md`) — all of it. The **`context` package's Phase-1 half** (F9a `describe()`/`find()`) shipped. **F4a outran its plan**: not just the first increment but the *whole* diagnostic surface — KERNEL (catalog-join + span-pointing) · LINT (pure-AST + model-context) · PRECHECK · the gotcha/INFO channel · "did you mean" · the unifying `RuleDiagnostics`. Plus the F6 DM-JSON write path (incl. includes round-trip) and the four constraint-mining instruments. **Carried forward (small):** N3's remaining directions (reverse keyword parity + parsetree construct-name / error-type parity — the forward keyword-parity anchor shipped) and F2a message-interpolation sugar (F2 "optional sugar"). Per-feature status: study §9.
- **Phase 2 — The bidirectional edit loop.** The **kernel-adapter module** (N8 tier): `getMvkServiceForModel` bootstrap *(✅ shipped 2026-06-04 — opt-in `adapter` subproject, `KernelAdapter` over the verified a12internal read chain, boundary-converting; canary doubles as the N8 pin+canary; see KERNEL-INTEGRATION-NOTES)*, **F10 ingest** (parse → converter visitor, corpus-priority coverage, fail-fast) *(✅ 2026-06-04 — `adapter.ConditionConverter`: corpus-core coverage (logic, comparisons incl. dates, patterns, field/group presence, quantifiers, value-lists, aggregates + `Having`, iteration/uniqueness), re-authored through the handle API so converted nodes carry D14; **tolerant** sealed `Ingest` (`Converted`/`Opaque`) preserves unmodeled constructs verbatim; round-trip oracle. See KERNEL-INTEGRATION-NOTES)*, fast per-rule validation, delegated rename. **F9 explain** for built *and* ingested ASTs *(✅ condition-level 2026-06-04 — `ModelContext.explain` → `RuleExplanation`)*, plus **F9a's exact `whereUsed`** (the adapter's `getDirectlyReferencedEntities` — one call per rule, needs no converter coverage, so it lands with the adapter's walking skeleton). **#2 re-express + rendered diff** *(✅ 2026-06-04 — `dsl.RenderedDiff` + `KernelAdapter.diff`: re-express a built/ingested condition, diff before/after with lossiness disclosed, and `BoundModel.replaceCondition` writes the reviewed change back (new binding, re-validated, `writeTo` persists); full loop ingest→re-express→diff→validate→write-back tested)*. Together: *find → whereUsed → ingest → explain → modify → diff → validate* over any rule in a model. **The standalone CLI (N10)** lands here — the modeller/BA's zero-toolchain front-end over the same engine (verbs + JSON I/O, JVM bundled); a JVM-resident fat jar proves the verb surface first, then self-contained packaging + signing. Licensing is **settled** (§19 / [`LICENSING.md`](./LICENSING.md)): redistribution is permitted, so the bundled distributable ships under **EUPL-1.2** while rulekit source stays MIT — the remaining work is packaging + signing + EUPL notices, not an open question. The **LLM skill** ships here too, teaching **both** persona interactions (developer in-repo via the library; BA via the CLI). *(There is no separate developer execution vehicle — see §14/N10; the old JBang vehicle is at most an internal N6-eval mechanism.)*
- **Phase 3 — Codegen typed refs (L2b) + optional model construction.** Per-model typed handle classes (kernel typed-accessor generator as precedent) for compile-time safety; optional typed model *construction* emitting public DM-JSON (fork #8). *Spike candidate:* on-the-fly rule testing against sample data (survey §12 — deprecation status and cost need a dedicated look).
- **Phase 4 — TypeScript twin** *(deferred — very-future; design-informing only, not on the near agenda, owner direction 2026-06-04)*. Zod-flavored surface over the same catalog/render rules; build + render + pre-checks (no TS ingest/validation — §13). Kept in mind as a polyglot constraint on every Java design decision (the catalog/DM-JSON/render contract stays language-neutral); no implementation proposed yet.
- **Phase 5 — research-gated residuals.** Minimal-edit / concrete-syntax-preserving re-render; full-grammar converter coverage beyond corpus-driven growth; anything from the parked exploratory list (§14) that earns a concrete need.

Each phase is independently shippable; later phases are earned by need — with Phase 2 itself now *being* the earned need that v1 had gated.

---

## 17. Decision log <a name="17-decision-log"></a>

All decisions current as of 2026-06-03 (v2 — amendment history folded in; v1 originals in git history).

| # | Decision | Resolution | Rationale |
|---|---|---|---|
| D1 | Validation | typed builder + model pre-checks + **real kernel** (public `checkConsistency` shipped path; adapter `hasValidConditionText` fast lane) | no grammar/type reimplementation or drift; the engine is the oracle (N3) |
| D2 | Model-awareness | **model-bound from the core** (runtime L2a); codegen refs (L2b) Phase 3 | a rule is meaningless without its model; model-awareness is the *source* of the safety (§5a) |
| D3 | v1 scope | conditions + computations, core operators, immutable/transformable AST; coverage in corpus-backlog order | the near-term win; catalog-driven growth with real-world priority |
| D4 | Source of truth | **rulekit's AST is the canonical working representation**; foreign text is a supported *input* (F10), rendered text + DM-JSON the *output*; never mutate text or the kernel tree | cleanest blast radius; one tree for explain/modify/render; bidirectionality (§2) without string surgery |
| D5 | Modify posture | **the edit loop is core**: #1 Phase 1; #2 + #3-ingest Phase 2; minimal-edit re-render research-gated (Phase 5) | the read path is a converter over the kernel's own AST (D15), not a parsing project; the declared goal (§2) is the concrete need v1's gate awaited |
| D6 | Polyglot | catalog = language-neutral contract; data-driven renderer; dialect seam day one; TS twin = parallel impl, build+render only | makes TS a parallel reading, not a rewrite; respects the missing TS kernel runtime/AST |
| D7 | Framing | **"jOOQ for the A12 DSL"** — now including "the parser arrived with the core" (§6) | instantly conveys the mental model |
| D8 | LLM artifacts | catalog-as-data + skill + javadoc + validate entry point + `MVK_*`-aware messages + the full edit loop as agent workflow | first-class N1 |
| D16 | Two-persona LLM support (N10, 2026-06-04) | **one engine, two interactions**: developer = Claude Code editing rulekit-the-library *in their own project* (project compiler supplies the type-steering — **no bespoke vehicle**); modeller/BA = a **standalone CLI** (verbs + JSON, JVM bundled, no JDK/`.m2`/Gradle/JBang). The compile-first JBang/`java --source` vehicle is **demoted** from a shipped developer feature to at most an internal N6-eval mechanism | the developer already gets compile-time safety from their own repo, so a special vehicle added no value; the only persona that justifies a standalone vehicle is the BA, who can't use Java — collapsing two imagined vehicles to **one** (the CLI) shrinks blast radius. The kernel-is-Java oracle means the CLI bundles the JVM; redistribution licensing (§19) gates it |
| D9 | Relationship to kernel model builders | bind to public `IDocumentModel`; emit strings + public **DM-JSON** (the only shipped write path); `betterbuilders` = reference, never dependency; in-memory mutation of the internal model (`Rule.setErrorCondition`) allowed **only** in adapter/test tiers, never core | public surface is stable + polyglot; the internal tier's mutability is a tooling convenience, not a contract |
| D10 | Name | **Umbrella `a12-dmkits`** (`dm` = document model) — `rulekit` + `modelkit` over a shared core. Maven group `io.github.mbackschat.a12.dm`; Java package root `io.github.mbackschat.a12.dm.*`. *(Settled 2026-06-06; the 2026-06-05 working name `a12-kernel-kits` was dropped — `dm` is terser as a package prefix and matches the project's own DM-JSON vocabulary. Full rationale: ARCHITECTURE "the umbrella".)* | The trigger (modelkit a real peer) fired with M1–M6. Renaming is cheapest pre-publish, so it was done now: **package root + Maven coords + `rootProject.name` + CI + in-repo docs are renamed**; the **GitHub repo + on-disk dir rename remain as owner action** (external, can lag — the in-repo config is independent of the repo's external name). |
| D11 | Semantic self-sufficiency (N6) | bundled material is the complete authoring source; "the rulekit is the teacher"; measured by the self-sufficiency eval | maximizes LLM autonomy; describe-vs-execute keeps N3 intact |
| D12 | Type-model ownership & depth | rulekit owns the type lattice as catalog data; v1 kinds: number-with-scale, string, date, boolean, confirm, enum+categories; Custom string-like; TypeDef delegate; date specials phased with their operators; `Unspecified` excluded | the largest `MVK_*` class is type rejection; the kernel exposes no type tag (§5b) |
| D13 | Decimal-scale pre-check boundary | pre-check only the arithmetic-free case; result scales are the kernel's to compute; steer to explicit `Round*` | predicting parser-computed scales would reimplement engine truth (N3) |
| D14 | AST invariant for explainability | every node carries **catalog operator-id + model-resolved field refs + provenance** from Phase 1 | makes F9/explain and #2/re-express cheap projections instead of retrofits; provenance (v2 addition) distinguishes built vs ingested and carries notation hints |
| D15 | Tiered kernel-API policy (N8) | three tiers (core public-only / tests / **Java-only kernel-adapter** with boundary-conversion, read-only, pin+canary, fail-fast guardrails); kernel-AST *construction* permanently rejected | the kernel ships the read half (`@OnlyForUsage` traversal is its intended consumer use; `a12internal` withholds only stability); guardrails preserve what blanket "public-only" protected while confining upgrade risk to one optional module. Full basis: [`KERNEL-RULE-API-SURVEY.md`](./KERNEL-RULE-API-SURVEY.md) |

---

## 18. Decided forks (design record) & open items <a name="18-decided-forks"></a>

The twelve v1 design forks, all decided (owner review 2026-06-02; #1 amended 2026-06-03). Kept as the record of *why* and what was rejected; numbering preserved for cross-references (other docs cite these as **Q1–Q12** or **§18.N**, e.g. "§18.9" = fork 9, the path policy).

1. **Source of truth** — decided, then **amended**: bidirectional modification became a goal, so foreign text is a supported input (F10, Phase 2); the rulekit AST stays the canonical working form; rebuild-over-text-surgery remains the default. *(Rejected: text/JSON as working representation — blast radius; committing to minimal-edit now — research-grade.)*
2. **Validation dependency** — kernel-on-classpath for validate; build+render fully standalone (N9). *(Rejected: bundling a kernel jar; never calling the kernel.)*
3. **Catalog authorship** — hand-curated entries + generated coverage checks (now four anchors, §9). *(Rejected: full generation from the grammar — poor ergonomics; uncovered hand-authoring — silent drift.)*
4. **Computation depth** — v1: simple + tiered/conditional + common precondition; parallel-iteration computations deferred. The adapter adds `checkForCycle` to computation validation when it lands.
5. **Ergonomic style** — jOOQ-functional `Dsl.*` static-import core; modern-Java internals (sealed + records + pattern matching); Zod-flavored TS twin; no language-specific type tricks in the shared contract. *(Rejected: FluentValidation-OO primary — less TS-portable; both surfaces first-class — parity burden.)*
6. **Distribution** — standalone library; upstreaming is a later governance question. *(Rejected: propose-into-kernel now; private-only.)*
7. **Binding level** — L2a runtime typed refs v1; L2b codegen Phase 3. *(Rejected: L2b-first — per-model build step; L1 strings-only — loses typed ergonomics.)*
8. **Consume vs build the model** — consume/bind only in v1; construction (if ever) emits public DM-JSON per D9. *(Rejected: construction in v1 — adjacent scope; wrapping `betterbuilders` — internal-tier + Java-only.)*
9. **Path-rendering policy** — default **relative from the rule group** (the kernel guide's own preference — independently confirmed by `PathNotationConfig.DEFAULT_MAYBELOSSY = RELATIVE`, survey §5); fall back to **absolute** when relative can't be proven unambiguous or when combining `..` with `*` (`MVK_INVALID_WILDCARD_REL`); **short names** only when `fieldRefByShortNameAllowed` and unique; **auto-quote keyword segments** always. Ingested nodes additionally carry the author's recorded notation style as a rendering hint (F10). *(Rejected: always-absolute — noisy, ignores the guide; preserve-author-intent as default — needs minimal-edit research.)*
10. **Incomplete models** — v1 requires a complete, resolvable, expanded model. *(Noted: the kernel's `ParserStrictness.LENIENT` exists for incomplete models — a concrete mechanism if this is ever revisited; survey §3.)*
11. **Adjacent slots** — paths yes (free with the renderer); message templates as a light typed interpolation helper; full templating optional sugar.
12. **Explainability output** — structured data first + optional NL gloss; built **and ingested** ASTs (v2 widening — both available Phase 2).

**Currently open:**

- **Converter coverage order** (F10) — default: same corpus-backlog order as the builder; revisit if an ingest-heavy use case (e.g. bulk explain over a legacy model) demands different priorities.
- **On-the-fly rule testing** (survey §12) — Phase-3 spike: deprecation status, cost (generates+executes code), and whether behavioral catalog examples are worth it.

---

## 19. Relationship to sibling repos, build, licensing <a name="19-relationship"></a>

- **`a12-kernel`** — the engine, grammar, BA guide; the validate + adapter dependency; the parity-test sources. Pinned at `30.8.1` (2025.06-ext5); the catalog records `sinceKernel` per operator.
- **`a12-kernel-demo`** — runnable feature showcases (the Phase-1 coverage floor), `KERNEL-SEMANTICS.md` (prose basis of the semantics layer), the typed-accessor codegen precedent for L2b, and the only kernel repo whose models may be copied/adapted.
- **`a12-kernel-bootstrap`** — publishes the kernel + deps to `mavenLocal` from public sources (Java only — the concrete reason TS validation is later). Run `./bootstrap.sh` once.
- **`a12-antlr4-code-editor` + `kernel-core-parser-web`** — surveyed; no dependency. The editor is a grammar-agnostic string editor (no AST); the web package contributes the content-catalog drift oracle and, hypothetically, TS parsing machinery rulekit doesn't plan to use.
- **Corpora** ([`WORKSPACE-CORPUS-ANALYSIS.md`](./WORKSPACE-CORPUS-ANALYSIS.md)): *A12 Tools* workspaces (proprietary) — read-only pattern/frequency oracle, transformed into original fixtures; **`a12-kernel-mm`** (EUPL) — referenced via sibling clone for opt-in integration tests, never committed. Committed fixtures are always our own originals (project `CLAUDE.md`).
- **Build:** Java 21 toolchain, Gradle 9, `mavenLocal` + Maven Central only, isolated `GRADLE_USER_HOME` (machine-global config may inject a private registry — see README and `KERNEL-INTEGRATION-NOTES.md`). Core depends on `kernel-md-facade` + public model API + serializer; **must not** depend on `kernel-md-model`/`kernel-md-a12internal` (those are adapter/test-tier only per N8/D15).
- **Licensing & IP** *(decided 2026-06-04 — full analysis in [`LICENSING.md`](./LICENSING.md))*. rulekit ships no kernel source — it emits the kernel's DSL text and binds to public artifacts; the catalog is rulekit's own description of the public DSL surface (CI-validated, drift-checked but never copied — §9 anchor 4). Quoted `MVK_*` codes and keywords are interface identifiers. **Decision: rulekit source is MIT** (its own original work, ships no kernel binary, consistent with `a12-kernel-demo`/`a12-kernel-bootstrap`); **the bundled CLI distributable (N10) ships under EUPL-1.2**, since the kernel (dual `EUPL-1.2 OR commercial`, redistribution granted) is embedded in that artifact. Governing rule: *MIT for our source that resolves the kernel at build time; EUPL-1.2 for any artifact that bundles the kernel.* MIT-source + EUPL-bundle do **not** conflict (permissive→copyleft is the allowed direction); EUPL applies only to the bundle, not the source repo.

---

## 20. Glossary & references <a name="20-glossary"></a>

- **Catalog** — the language-neutral JSON registry of operators + the language-semantics layer; the spine.
- **Model index** — rulekit's queryable projection of an expanded DocumentModel (paths, kinds, scale, repetition, enums).
- **AST** — rulekit's immutable typed expression tree; the **only working representation** (D4). Nodes carry catalog-ids, model refs, provenance (D14).
- **Condition / Calculation** — the boolean / typed-value expression kinds; **Slot** — where DSL text lives in the model (§5).
- **Value kind / type lattice** — the operand-type system rulekit encodes as catalog data (§5b, D12).
- **Render / dialect** — AST → canonical DSL text (EN/DE).
- **Ingest (F10)** — foreign DSL text → kernel parse → converter visitor → rulekit AST, with provenance.
- **Kernel bridge / adapter** — the two-tier seam to the engine (§8): public validate path (core) + the Java-only `a12internal`-read module (N8/D15).
- **Converter visitor** — the `ASTVisitor` subclass mapping kernel `ICondition` nodes to rulekit nodes; fail-fast on uncovered constructs.
- **Explain (F9)** — the projection of catalog + model index onto an AST: fields/types/roles, operator semantics, iteration context, gloss.
- **The edit loop** — ingest → explain → transform/re-express → rendered diff → validate (§12).
- **Combinator** — a reusable, parameterized expression fragment (an ordinary function returning nodes).
- **Provenance** — whether an AST was built or ingested, incl. recorded path-notation style hints.
- **MVK_*** — the kernel's parser/conversion error codes (failure→fix table in `KERNEL-GRAMMAR.md`).
- **Round-trip oracles** — the N7 differential render and DE-translation checks against the kernel's formatter.

**References:** [`KERNEL-RULE-API-SURVEY.md`](./KERNEL-RULE-API-SURVEY.md) (the kernel/editor API inventory behind v2), [`KERNEL-INTEGRATION-NOTES.md`](./KERNEL-INTEGRATION-NOTES.md) (verified integration facts), [`WORKSPACE-CORPUS-ANALYSIS.md`](./WORKSPACE-CORPUS-ANALYSIS.md) (operator priorities), `a12-kernel/KERNEL-GRAMMAR.md` · `KERNEL-USE.md` · merged BA guide, `a12-kernel-demo/KERNEL-SEMANTICS.md` · `SHOWCASE.md` · `KERNEL-GLOSSARY.md`, `a12-kernel-bootstrap/README.md`, `a12-antlr4-code-editor/ANALYSIS-RULES-EDITOR-AST.md`. External: jOOQ, Zod, Pydantic, JSON Schema, Jakarta Bean Validation, FluentValidation, QueryDSL.
