# dmtool CLI conformance — open gaps

> **What this is.** Open and deferred gaps on the **agent-facing conformance axis** — the *self-explain* + *diagnostic* contract that [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md) owns (its sibling spec [`CLI-SPEC.md`](CLI-SPEC.md) owns the verb surface + safety gate). It is the CLI-conformance peer of [`FEATURE-GAPS.md`](FEATURE-GAPS.md): that tracker holds the **BA-authoring** ergonomics of the BA+agent loop, while this one holds what a *cold agent* needs to **select, risk-assess, and recover from** a verb using the tool's own output. Same `-GAPS` discipline as its siblings ([`DEV-API-GAPS.md`](DEV-API-GAPS.md), [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md), …): **OPEN-only** — a shipped gap is **deleted**, not kept as a "shipped" list (git is the *what/when*; the graduated `CLI-CONFORMANCE-SPEC` matrix column / `CLI-SPEC` section is the *why*).
>
> **Provenance.** A 2026-06-28 review of dmtool against a general "CLI tools for LLM agents" best-practices note found that dmtool already meets or beats ~80% of it — JSON-default output, a picocli-**derived** manifest that can't drift, the result envelope, the `refused`/referrers safety gate, the exit-code taxonomy, CI-executed examples. The rows below are the residual deltas, and — in §2/§3 — the ones we considered and are **not** taking, each with a re-open trigger so the decision carries its own expiry.

---

## 1. Open backlog

The gaps we intend to close. Each is a per-verb metadata axis that belongs in the manifest and graduates into `CLI-CONFORMANCE-SPEC` as **one new conformance-matrix column** (the matrix is designed to grow — "a newly-learned anti-pattern is a one-column change").

- **Per-verb side-effect class in the manifest (`sideEffect` / `readOnly`).** Today the manifest entry carries `verb`/`target`/`op`/`schema`/`description`/`example`/`params[]` but **no side-effect axis** — an agent cannot tell a read from a mutation (let alone a *cascading* delete) without running the verb or inferring from its name. Surface a declared side-effect class per verb (`none` for reads; `local_mutating` for the writers; `cascading` for `remove --cascade` / `group remove`). **Prerequisite:** the `Local / Cascading / Refactor` taxonomy is `CLI-SPEC` §6 prose today, with only `Refactor` represented in code (`StructureRefactor`); encode it as a **typed enum each command declares**, then the manifest *derives* `sideEffect` from it and the conformance matrix gates it. Graduates to `CLI-CONFORMANCE-SPEC` (a new self-explain row + matrix column) and `CLI-SPEC` §6 (the taxonomy becomes typed + surfaced).

- **`doNotUseWhen` on near-sibling verbs.** The `@Command` descriptions are non-blank (the matrix's `described` column) but **don't disambiguate near-siblings**: `model read` ↔ `export`, `check` / `eval` / `compute`, and `apply` ↔ `batch` carry no cross-reference, so an agent choosing between them has only prose intuition. Add a structured "do not use when — use X instead" note to the description contract for any verb with a near-sibling. Grounding: verb mis-selection is an observed eval failure class (the gap-finder's `AMBIGUOUS_MODE` / `UNCLEAR_PURPOSE`). Graduates to `CLI-CONFORMANCE-SPEC` as a new matrix column — an `AP7`-shaped anti-pattern: *ambiguous sibling selection*.

---

## 2. Deferred — considered, not now (with re-open triggers)

- **Per-verb `emits[]` (the failure space, discoverable *per verb*).** The failure space is already discoverable **reactively** — every `RK_*` / `MVK_*` code the tool emits resolves in the `diagnostics` catalog (`diagnostics` list + `diagnostics <CODE>`), each carrying a `fix`. A per-verb `emits[]` would add **proactive** planning ("what could this verb return before I run it"), but it is **not** picocli-derivable — it needs a verb→codes source that does not exist yet. This is the flip side of the `CLI-SPEC` §2 correction that removed the premature "each op advertises `emits[]` in the manifest" claim. **Re-open when** a planning or apply-the-fix consumer needs the per-verb failure space proactively, or a probe shows agents failing to anticipate a verb's errors — likely then a **proposal**, since the verb→codes derivation carries real design risk.

- **A runnable next-action command on `refused`.** The `refused` envelope already carries the referrers (in `data`) and a `fix` diagnostic; emitting the *exact* remediation invocation (e.g. the `remove --cascade` line to run) is the note's "include next actions." **Re-open when** a fix-applying harness exists to consume it, or a probe shows agents failing to recover from `refused` despite referrers + `fix`.

- **Error-classification booleans (`retryable` / `correctableByAgent` / `userActionRequired`).** For dmtool these would be near-constant — the real signal already lives in `outcome` + `source` + presence-of-`fix`, and the one distinction that matters (a cascade *decision* vs a tool bug) is already encoded in `outcome`/exit-code. **Re-open when** an apply-the-fix harness actually consumes them (the same consumer-exists discipline that withheld a machine `suggest` field).

- **An envelope `version` field.** The note warns against unversioned output-shape drift. dmtool's whole envelope contract is **derived + drift-guarded** (`SchemaVerbTest`, `result.schema`), so an embedded version buys little today. **Re-open if** the envelope must change incompatibly, or an external consumer pins the shape and needs a version to negotiate against.

- **A cross-verb stdout-purity guard ("nothing but one JSON document").** The **error path** is already broadly guarded — `DiagnosticConformanceFuzzTest` parses stdout and asserts a structured envelope across ≥10 verbs. A guard covering the *success* + `--text` path for every verb is cheap insurance, but this spec is deliberately **grounded in observed misses**, and no stdout contamination has been observed. **Re-open when** any verb is seen emitting non-JSON on stdout in a machine mode.

---

## 3. Out of scope — the remote / async operation model

A cluster of the note's recommendations presuppose a **resident or remote** tool: idempotency keys (§14), long-running operations with `operationId` / resume / poll (§15), the preview-id + confirmation-token lifecycle (§13), profiles / `--target-env` / auth status (§16), `--trace-id` + a correlated audit log (§16/§21), and cursor/pagination for large result sets (§17). dmtool is **local, synchronous, single-file, stateless-per-call** — the model file is the durable state, and git is the audit trail + undo. These recommendations **void** unless a resident mode lands, and that mode — **`dmtool serve`** — is already an examined **non-goal with its own re-open triggers** in [`FEATURE-GAPS.md`](FEATURE-GAPS.md) §4. They are pointed at, not re-litigated here.
