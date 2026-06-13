# CLI conformance — the self-explain + diagnosis harness

This is the **contract** every `dmtool` verb must satisfy and the **gates** that enforce it. Its purpose is the project's #1 priority — a cold LLM agent (and a library dev) must be able to *discover* the surface and *recover* from every error using the tool's own output, never an out-of-band skill. The harness exists because that promise was silently unmet: a hand probe of 13 mutating verbs (2026-06-13) found **11 returned a bare stderr string on a not-found input** — no envelope, no code, no fix — and one accepted invalid input at exit 0. No gate caught any of it. The lesson driving this spec:

> **Gates over checklists.** Anything an LLM (or a human) can skip, will eventually be skipped. A criterion that matters becomes an executable gate that fails the build — a checklist item is only the human-readable shadow of a gate.

The home of the *safety-gate* contract is [`CLI-SPEC.md`](CLI-SPEC.md) §6; the *operator vocabulary* is the [`CATALOG-SPEC.md`](CATALOG-SPEC.md) / `operators` verb. This doc is the *conformance* contract — what makes a verb self-explaining and well-diagnosed, and the guards that prove it.

## The anti-patterns it forbids (grounded in observed misses)

| # | anti-pattern | observed |
|---|---|---|
| **AP1** | **bare-fail domain error** — `fail(msg)` (stderr text, exit 2) for a not-found/invalid condition instead of a structured envelope | 11 of 13 mutating verbs |
| **AP2** | **asymmetric gating** — one wrong-input position richly diagnosed, its peer not | refactor source vs destination |
| **AP3** | **silent accept** — invalid input → exit 0, no diagnostic (the agent thinks it succeeded) | `config modify --decimal-separator XYZ` |
| **AP4** | **happy-path-only tests** — success + the refusals the author *thought of*, never the full error matrix | the original move verbs |
| **AP5** | **surface rot** — stale `@Command`/param descriptions feeding `manifest`/help; un-described params | the pre-flip rename descriptions |
| **AP6** | **under-surfaced verb** — a verb ships listed + described but its **I/O shape is undiscoverable** (no resolvable `schema` pointer) or it is never **demonstrated in action** | `meta` shipped with no `schema` pointer; the templates/cross-cutting verbs had no worked example |

## The two contracts

### 1. The diagnostic contract (diagnosis)

> A **domain error** of any mutating verb is a **structured envelope** — `outcome ∈ {refused, rejected}`, exit `2`/`1`, `written:false` — carrying **≥1 diagnostic with `code` + `where` + `fix`**, and the `code` **resolves in the diagnostic registry**.

- **Domain error** = the CLI syntax parsed, the model loaded, the op was attempted, and a model-level precondition failed (not-found, collision, type mismatch, kernel reject, …). These MUST be structured.
- **Usage / IO errors** (bad CLI syntax, missing/unreadable `-m`) MAY stay plain text — they are picocli/IO concerns, not domain diagnoses.
- **`refused`** = a pre-mutation safety-gate precondition failed (exit 2, nothing written). **`rejected`** = the kernel re-validate failed (exit 1). Never a bare `fail()` for a domain condition; never `outcome:applied`/exit 0 on invalid input (AP3).
- Every **error-severity** diagnostic carries `code` + `where` + `fix`. `explain` (the *why*) is added where it helps.

### 2. The self-explain contract (discoverability)

A cold agent learns the whole surface from the tool:

| an agent can discover… | via | gate |
|---|---|---|
| every verb + its params (with op-record key) | `manifest` (derived from the live picocli tree) | `ManifestVerbTest` / `ManifestTreeTest` |
| what each verb does | the `@Command` description (non-blank, capability-only) | `ManifestVerbTest` |
| what each param means **and what to pass** | the param description **+ its `@ParamKind`** (e.g. "a FIELD_PATH that must exist" vs "a NEW_NAME you choose") | the conformance matrix |
| a verb's I/O shape | `schema <target> <op>` → the `result` schema (and `schema <verb>` for the single-token **envelope** verbs like `meta`); input-bearing verbs add an input schema with CI-executed `examples` | `SchemaVerbTest` / `SchemaExamplesGuardTest` / the conformance matrix |
| **a verb in action** | the verb's `example {intent, command}` in `manifest` — a worked, read-only invocation the agent can run | the conformance matrix / `VerbDemonstrationTest` |
| **what a `code` means** | `dmtool diagnostics` (list) / `dmtool diagnostics <CODE>` (explain) — the `operators` twin, derived from the registry | registry parity guard |
| what `severity`/`source` mean | `schema diagnostic` (per-value enum descriptions) | schema guard |

## The gates (what enforces each rule)

| gate | proves |
|---|---|
| **diagnostic-conformance fuzzer** | for every mutating verb (derived from the live tree), every synthesized **domain error** is a conformant structured envelope — code+where+fix, code∈registry, exit/outcome consistent, `written:false`. A new verb **cannot escape it.** Kills AP1/AP2/AP4. |
| **registry parity** (no-rot) | every `RK_*` code the tool can emit resolves to a registry entry — **compile-time** via the typed `RkCode` enum + an exhaustiveness test, so an un-catalogued code can't ship. `MVK_*` ride `MVK-LEDGER`/`MvkLedgerTest`. |
| **single-source derivation** (no-drift) | the `diagnostics` verb, the `manifest` diagnostic info, the `diagnostic.schema` enum/examples, and the API Javadoc all **derive from the one registry** — a guard forbids a second hand-authored copy of a code's meaning. |
| **conformance matrix** (`CliConformanceMatrixTest`) | the single **derived** per-verb gate (folds the old completeness check): every verb is described, every param is described, every **envelope** verb advertises a `schema` pointer that *resolves to real JSON*, and every verb is **demonstrated** (a worked example in `manifest`). One row per verb, one column per obligation — so a new verb can't ship under-surfaced, and a newly-learned anti-pattern is a one-column change. Kills AP5/AP6. |
| **demonstration gate** (`VerbDemonstrationTest`) | every `verb-examples.json` invocation (the one `manifest` surfaces) is **executed through the tool** against a fixture and must produce its documented outcome — so the "in action" example an agent reads cannot rot. All examples are read-only (read or `--dry-run`). |
| **silent-accept guard** | scalar/enum setters reject invalid input with a structured diagnostic (or are documented freeform + kernel-confirmed). Kills AP3. |
| **negative-control rehearsals** | each gate above is proven to **fire** (a fabricated bare-fail verb → fuzzer red; an un-catalogued code → red; a param missing a kind → red) so a guard can't rot into a no-op. |

## The conformance matrix — "is this new verb actually surfaced?"

The recurring failure mode is subtle: you add a verb, it *works*, it's *listed* in the manifest — but its I/O shape is undiscoverable or it's never shown in action, and no gate complains (AP6). The matrix (`CliConformanceMatrixTest`) makes "fully surfaced?" **one green/red signal**. It is **derived** — it walks the live `manifest` and asserts a per-verb obligation row, so a new verb is in scope automatically; you cannot opt out by forgetting to register a check.

| column | obligation | exemptions |
|---|---|---|
| **described** | non-blank capability description | none — every verb |
| **params-described** | every param has a non-blank description | none |
| **schema-resolves** | advertises a `schema` pointer that resolves to real JSON | the non-envelope verbs (below) |
| **demonstrated** | carries a CI-executed worked invocation an agent can see *in action* | the self-describing verbs |

**One row per verb, one column per obligation.** A newly-learned anti-pattern becomes one more column, re-checked against every existing verb at once — the same growth loop that produced AP1–AP6. The matrix *folds and strengthens* the old `ManifestCompletenessTest`: that check **skipped** a verb that advertised no schema, which is exactly how `meta` slipped through; the matrix has no skip.

**The contract exemption is a reasoned ledger, not a silent skip.** `schema <verb>` serves a contract only for verbs that return the **result envelope**. The exceptions live in `SchemaCommand.CONTRACT_EXEMPT` as a `verb → why` map — the single source the manifest reads to decide the pointer *and* the matrix reads as the ledger:

- the four **self-describing** verbs (`manifest`/`schema`/`operators`/`diagnostics`) emit their own JSON — you run them to see it;
- `export` **renders an artifact** (model card / dot / …), and `where-used` returns a **custom referrers payload** — neither is the `{outcome,…}` envelope (a dedicated `where-used` output schema is a follow-up).

The matrix proves the ledger is **honest**: every exempt verb exists in the manifest (no stale row) and carries a non-blank reason. Anything not exempt **must** be contract-discoverable — which is how `meta` (an envelope verb that was wrongly missing its pointer) is now locked in.

**The demonstration — every verb in action, surfaced by the binary.** A description tells an agent a verb exists; it does not show it *working*. Each verb carries a worked invocation in `verb-examples.json` (read-only by construction — a read, or a mutation under `--dry-run`), and the `manifest` surfaces it as the verb's `example {intent, command}` — so the demonstration ships *in the binary*, on the cold agent's material boundary, not in `examples/` (which the agent never sees). `VerbDemonstrationTest` **executes** every example against a fixture and asserts its documented outcome, so a drifted demo (renamed flag, changed behavior) fails the build. The matrix's `demonstrated` column accepts three forms: a `verb-examples` entry, a schema **input** example (the rich-input verbs, executed by `SchemaExamplesGuardTest`), or a reasoned row in `CliConformanceMatrixTest.DEMONSTRATION_PENDING` — a small, CI-locked, shrinking debt ledger (currently `typedef inline`/`import`, whose happy path needs a typedef-import fixture pair). A **new** verb is in none of the three, so the gate fires.

## Never stale — the structural guarantee

The diagnostic info is **derived, never authored twice**, and **CI-verified both directions**:

- **One registry**, keyed by a typed `RkCode` enum. Emitting a code references the enum → an un-catalogued code does not compile.
- Every **surface** (CLI `diagnostics` verb, `diagnostic.schema`, the API Javadoc, the dev doc) reads/points to that registry — no parallel prose to drift.
- **Forward** parity: emitted ⊆ registry (compile-time). **Reverse** parity: every registry entry is emitted/reachable or explicitly classified (no dead rows).
- The `diagnostic.schema` `examples` and the conformance fuzzer **execute** against the live registry every build.

The `RK_*` registry is **rulekit-owned** — it has no kernel coupling, so a kernel upgrade doesn't touch it. A kernel upgrade that adds/renames `MVK_*` codes rides the existing **MVK ledger** (`MvkLedgerTest` / [`MVK-LEDGER.md`](MVK-LEDGER.md)), not this registry; the broader kernel guardians and their realignments are in [`KERNEL-UPGRADE.md`](KERNEL-UPGRADE.md).

## Library consumers — the same registry, no second source

Diagnostics aren't a CLI-only concern: the diagnostic model is **public library surface**. A dependency that drives rulekit/modelkit directly receives `Diagnostic` objects (record: `code`/`severity`/`source`/`where`/`fix`/`explain`) from `ValidationResult.diagnostics()` and the edit/validate seams. So the meaning of a code must be **dev-discoverable**, not only CLI-discoverable — and it resolves through the *same* registry the `diagnostics` verb serves:

- **`RkCode` is public.** `RkCode.byCode("RK_NO_SUCH_FIELD")` returns the catalog entry — `meaning()`, `severity()`, `source()`, `fix()`. The CLI `diagnostics` verb is a thin shell over exactly this; a library consumer calls it directly (see `Diagnostic`'s Javadoc for the idiom). `Severity` (the kernel's enum) and `DiagnosticSource` carry their own Javadoc.
- **One source, both faces.** Tool and library read the one `RkCode` enum, so they never diverge — and the parity guard above keeps it honest. `MVK_*` codes are the kernel's; their meaning is the operator catalog's (`operators`/`explain`), not this registry.

## Authoring checklist — adding or changing a verb

The checklist is the human shadow of the gates above; the gates fail the build if you skip a row.

- [ ] **Discoverable:** `@Command` description is capability-only (no status/roadmap); every param has a description **and** a `@ParamKind`; the verb appears in `manifest` (flip `ManifestVerbTest.EXPECTED_VERBS`); `schema <target> <op>` (or `schema <verb>` for an envelope cross-cutting verb) resolves, or the verb is classified in `SchemaCommand.CONTRACT_EXEMPT` with a reason; an input-bearing verb adds an input schema + a CI-executed `examples` block.
- [ ] **Demonstrated:** add a read-only worked invocation to `verb-examples.json` (a read or a `--dry-run` mutation) so `manifest` surfaces the verb *in action* and `VerbDemonstrationTest` executes it — or classify it in `DEMONSTRATION_PENDING` with a reason. **The conformance matrix enforces both this and the Discoverable row.**
- [ ] **Diagnosed:** every domain-error path returns a structured envelope (`refused`/`rejected`), not `fail()`; every error diagnostic has `code` + `where` + `fix`; each `code` is an `RkCode` registry entry (so it's catalogued + explorable via `diagnostics`).
- [ ] **Symmetric:** every input position that can be wrong has a diagnostic (no AP2); no input is silently accepted (no AP3).
- [ ] **Proven:** the conformance fuzzer is green for the verb; each new refusal has a does-fire test (the §6 refactor-gate rule); a new gate has a negative-control rehearsal.
- [ ] **Documented as capability** in the living surfaces (the §9 board + the `examples/` demo for a CLI change), per the user-facing-no-status rule.
