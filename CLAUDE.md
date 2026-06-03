# a12-rulekit — project instructions

Design and rationale live in [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md); verified kernel-integration facts in [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md); how the builder structure is derived (and the ANTLR grammar's indirect role) in [`docs/DSL-DERIVATION.md`](docs/DSL-DERIVATION.md); the real-model operator-usage analysis and transform backlog in [`docs/WORKSPACE-CORPUS-ANALYSIS.md`](docs/WORKSPACE-CORPUS-ANALYSIS.md).

## Language

English is the project default: code, identifiers, comments, docs, and the **default render target** (`DslRenderer` defaults to `ConditionLanguage.EN`) are English. **German is a supported render target, not the default** — the operator catalog carries verified `de` keywords and `new DslRenderer(ConditionLanguage.DE)` renders to it. Translations are welcome; English stays canonical.

## Test & sample document models

- **Never copy domains, fields, or rules directly from the a12 kernel repos** (`a12-kernel`, `a12-kernel-mm`, `a12-base`, etc.). Invent your own domain and naming instead. The one exception is **`a12-kernel-demo`** — copying or adapting its example models is fine.
- Whatever you invent **must make sense**: coherent domain, realistic field names and types, plausible rules. No nonsense placeholders.

## Coding style — make full use of Java 21

Write idiomatic modern Java 21, not Java 8 with newer syntax:
- **Records** for immutable data (AST nodes, value objects, results). **Sealed interfaces + exhaustive pattern-matching `switch`** for closed hierarchies — model the AST this way and let the renderer/visitors dispatch with a switch that the compiler proves exhaustive (no `default`). See proposal §18.5.
- Record/`instanceof` **deconstruction patterns**, `var` for obvious locals, **text blocks** for multi-line strings (DM-JSON fixtures, expected output), enhanced `switch` expressions, `Optional` over nulls in APIs, `List.of`/`List.copyOf` for immutability.
- Prefer making illegal states unrepresentable in the type system over runtime checks — that is the whole point of the typed builder.

## Documentation discipline

- **Every non-obvious finding gets written down under `docs/`** — kernel/build/environment facts default to [`docs/KERNEL-INTEGRATION-NOTES.md`](docs/KERNEL-INTEGRATION-NOTES.md); design decisions to [`docs/PROJECT-PROPOSAL.md`](docs/PROJECT-PROPOSAL.md). If a finding doesn't fit an existing doc's scope, **propose a new doc** rather than overloading one.
- A finding is only "done" when it's documented. When a new fact **contradicts** an existing note, update that note — don't leave stale claims.
- **After any substantive change, state which docs need updating** (proposal / notes / README status / this file) and update them in the same change.

## Testing strategy

- **Example-based TDD is the primary spec** (red/green) — readable, intent-revealing tests drive each feature.
- **The real kernel is the oracle.** `RuleValidator` + `checkConsistency` confirm that rendered output is actually accepted (F4); N7 runs every catalog example through it.

## Build

Build with an isolated, public-repos-only Gradle home so the machine's global config (private registry) is bypassed, on JDK 21 — see the README's "Build & test" section. The kernel must first be published to `mavenLocal` via the sibling `a12-kernel-bootstrap` kit.
