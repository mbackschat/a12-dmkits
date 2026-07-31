# dmtool result contract — proposal

> **What this is.** The design for **what a `dmtool` result asserts, who vouches for it, and what it is about** — the four coupled contract changes that close the 2026-07-31 field feedback: the result taxonomy (`valid`), the evidence vocabulary (`engine` / `verification`), canonical A12 pointer identity in runtime output, and one uniform aggregate envelope. All four are **breaking** and ship together as one downstream migration.
>
> **Why a proposal and not a spec edit.** The contract lands in [`CLI-SPEC.md`](CLI-SPEC.md) and [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md); this doc carries the *design plus its measured provenance* until then, and is **archived** under `docs/archived/` once the contract graduates. Durable facts do **not** live here — the kernel matrix behind §4 is [KERNEL-FINDINGS kf167](KERNEL-FINDINGS.md#kf167), and the open work it does not itself close is tracked as rows in [`CLI-CONFORMANCE-GAPS.md`](CLI-CONFORMANCE-GAPS.md).
>
> **Status.** Owner-approved 2026-07-31 as the direction for the next release, with the rulings recorded inline. Measured against revision `a955143d`, dmtool `0.10.1`, kernel build/runtime `30.8.1`.

---

## 1. Provenance — what was measured, and what the external analysis got wrong <a id="provenance"></a>

External field feedback (2026-07-31) reported seven user-visible defects; an accompanying third-party analysis proposed root causes. **All seven reproduce.** The analysis's central mechanisms are sound, and four of its claims are not. Both halves are recorded because the corrections changed the plan.

### 1a. Confirmed by measurement

| Claim | Oracle | Result |
|---|---|---|
| Repeatable `model compute` loses row identity | live CLI on a purpose-built 4-row repeatable computed field | 4 items, identical `field`, distinct `value`/`cleared`/`error`; row association only by list position |
| The loss is a CLI **projection**, not an evaluator gap | source + existing differential | `ComputationEngine` populates `Computed.indices`; `InterpreterEngine.computeData` emits `c.getPath()` only; `ComputePerRowIdentityDiffTest` locks the interpreter against the kernel |
| Summary counts instances but says "field(s)" | live CLI | `computed 5 field(s)` for **2 declarations × 5 instances** — wrong on both axes, and the list *mixes* declarations, so position is ambiguous, not merely fragile |
| Runtime envelopes claim `valid: true` | live CLI | `true` even on a model the kernel **rejects** |
| Runtime schemas carry a `"fields"` wrapper and false kernel provenance | `schema model compute` / `schema model eval` | both, in the same document that names the interpreter |
| `Abs`/Round refuse an immediate literal | real kernel via `rule check` **and** `computation add --dry-run` | `MVK_NO_CONST`; see [kf167](KERNEL-FINDINGS.md#kf167) |
| `apply --text` / `batch --text` emit JSON | live CLI, both placements, success and failure | `TextRenderer` requires `isObject() && outcome.isTextual()`; `apply` emits a custom object without `outcome`, `batch` an array |
| Unknown subcommand + `--help` exits 0 with parent help | live CLI | generalizes to `rule`/`model`/`field`/`workspace`; without `--help` it correctly exits 2 |
| Stale `JAVA_HOME` fails before dmtool; JDK 26 warns | generated launcher under three JDKs | confirmed — **but the warning is on stderr and stdout stays clean JSON**, which neither source distinguished |

### 1b. Corrections — analysis claims that are wrong <a id="corrections"></a>

1. **`model read` does run the kernel consistency oracle.** `KernelAdapter.ofJson` → `checkAndOpen` → `ConsistencyGate.REQUIRED` → `requireConsistent()`. On an inconsistent model `model read` returns `outcome: rejected`, exit 1. Its `valid:true` is a hard-coded literal that is *coincidentally* correct because the command already bailed — fragile, not false.
2. **The fabricating verbs are different ones.** Measured on a kernel-invalid model: **`model info`, `field read`, `group read`, `config read`, `meta`, `model compute`, `model eval`** report `valid:true`; **`model read`, `model describe`, `computation read`, `model usage`, `computation explain`, `model report`, `where-used`** are gated (honest, but never report `verification`). Sharper still: **`model report` is gated and `model report --tree` is not** — one verb, two evidence classes, because it has two `ResultEnvelope.read` sites. This is why evidence must be derived from the executed path and never from a per-verb list.
3. **The `Abs`/Round fact was not already recorded.** The only `MVK_NO_CONST` entry in KERNEL-FINDINGS was the **temporal** extractor case (§6, locked by `TemporalExtractorComponentLawsTest`). The numeric-wrapper instance was undocumented and unlocked; only `MVK-LEDGER`'s row gestured at "rounding", in wording that was itself wrong. Now [kf167](KERNEL-FINDINGS.md#kf167) + `NumericWrapperConstantLawsTest`.
4. **The pointer correction is right, but it is a breaking change the analysis did not flag.** Canonical A12 is `Order/Items[2]/Count` — no leading slash, `[1]` omitted (source-verified against `DocumentPointerImpl`, this section's grammar). The current `/Order[1]/Items[1]/…` is **our** `interpreter/eval/Pointer.kt` rendering; `adapter … RuntimeLaws.fmtPointer` re-renders the kernel pointer's *components* through it, so the `[1]`-explicit string in that differential is ours, not the kernel's. Converging is correct **and** breaks published output.

### 1c. Why every harness missed this <a id="why-missed"></a>

Three harnesses **encode** the defective output, so they do not merely miss it — they defend it. `examples/cli-runtime.md` captures `"valid" : true` ×5 and `computed 1 field(s)` ×2, so `verify-demos.sh` fails the *fix*. `probe/` gold answers encode `/Policy[1]/Claims[1]/Amount`, so a probe would mark the canonical form wrong. `cli … KernelEval.java` applies the *same* lossy projection to the kernel side, so a CLI-level differential compares two equally-lossy projections and agrees.

The guards that should have caught the rest are **presence-shaped, not property-shaped**: `CliConformanceMatrixTest` is entirely non-blankness (`assertFalse(...isBlank())`, `hasNonNull`, exit-code-0), so a false description passes; `TextRendererDispatchTest` has **3** cases and never touches the aggregates; `CatalogExamplesAreKernelValidTest` filters `e.invalidExample() != null`, so a *missing* example is invisible (36 of 110 entries have one). And the eval cannot see any of it — **zero** tasks invoke `model compute`, `model eval`, `rule eval`, or `--text`, and it scores task success against a gold, not envelope conformance.

Timeline, against the last full eval run (**2026-07-09**): the catalog gap postdates it (`42c9d18b`, 2026-07-23); the schema prose (2026-06-09), the `boolean valid` parameter (2026-06-11), the aggregate shape (2026-06-12), and `Computed.indices` (2026-07-06) all **predate** it. Four defects were live during that run.

**One sentence:** every harness is anchored either to previously-observed output or to the presence of text; none is anchored to an independently-stated invariant — which is why adding harnesses did not help.

---

## 2. The result taxonomy <a id="taxonomy"></a>

`ResultEnvelope.read(String, String, String, boolean valid, …)` places a `boolean` in a long same-typed parameter list, and 36 call sites pass a literal. **Required:** replace it with factories that make the wrong claim unrepresentable.

| factory | emits | may NOT emit |
|---|---|---|
| `queryResult(target, op, summary, data)` | `ok`, `data` | `valid` |
| `consistencyVerdict(target, op, summary, valid, diagnostics, Verification)` | `valid` **and** its kernel evidence, structurally inseparable | — |
| `runtimeObservation(target, op, summary, data, engine, engineVersion)` | `data` + execution evidence | `valid` |
| existing `applied`/`preview`/`refused`/`rejected`/`internalError` | unchanged | — |

**Required semantics.**

- Envelope-level `valid` survives only on genuine consistency verdicts (`model check`, `rule check`, `workspace check`).
- **Nested** verdicts are preserved verbatim — `workspace list --validate` keeps its per-model `valid`, and a nested verdict never feeds `exitCode()`.
- `profile compare` is **reclassified, not re-behaved**: it keeps exit 1 for "differs" and stops calling profile equality `valid`.
- `PatternsCommand` performs real kernel validation and emits **no** `ResultEnvelope` at all (it prints a raw node). It either joins the envelope or is classified in `SchemaCommand.CONTRACT_EXEMPT` with a reason.
- `exitCode()` keeps `Boolean.FALSE.equals(valid)` → 1 for verdict routes only.

**Excluded:** no deprecation alias for the removed key. The break is declared, not shimmed.

---

## 3. The evidence vocabulary <a id="evidence"></a>

**Required:** two orthogonal keys, never merged.

- `verification: KERNEL_CONFIRMED | STRUCTURAL_ONLY` — *how a consistency verdict was arbitrated*. Attached to **every invocation that actually ran the gate**, derived from the executed path (`BoundModel.ofJson` / `checkAndOpen` = gated; `ofJsonLenient` / `ModelIndex`-only = not), **never** from a per-verb table. This adds evidence to seven currently-silent read verbs.
- `engine: A12_KERNEL | DM_INTERPRETER` + `engineVersion` — *which engine produced the semantic result*. Co-required.

**`Verification` must not gain `INTERPRETER_OBSERVED`.** An interpreter run produces no verdict, so the value would read as "the interpreter verified something" — a category error.

**Required boundaries.**

- Evidence is **per-invocation, not per-verb**; the schema says so, and the conformance matrix must not assert a static verb→evidence map (`model report` vs `--tree` is the live counter-example).
- `model seed` emits canonical A12 Document JSON and therefore carries **no** provenance keys inside the document — inventing one would break the shape rule it exists to honour. Its engine is exposed through `schema`/`manifest`/help.
- A mixed-engine `batch` has no single aggregate engine; evidence stays on its children.

**Open:** `engineVersion` must be defined as the interpreter module version or the dmtool version. They are coupled today (`interpreter-v0.10.1` / dmtool `0.10.1`), which is exactly when an ambiguity ships unnoticed.

---

## 4. Pointer identity <a id="pointers"></a>

**Required:** one canonical A12 pointer, typed at the boundary and serialized once; the house `formatPointer` unreachable from CLI projection.

- `computed[]` carries **`field`** — the canonical field-instance pointer — and **`declaration`** — the model declaration path. Two different facts, unambiguously named. No coordinate array, no second pretty spelling.
- Summaries count declarations from the declaration identity and instances from result items — never by stripping indices from a display string.
- **Input stays permissive**: one shared parser accepts A12's legal non-canonical spellings (leading `/`, explicit `[1]`) and normalizes, matching A12's own reader. This covers `--relevant` and every pointer-taking input.
- The test-side `KernelEval.java` projection is fixed in the same change; leaving it lossy would let a differential hide the defect by agreeing.

**Required migration order** (identity is spelling-only, so it must be provable):
corpus expectations migrate by **mechanical re-render**, with the per-case `(outcome, value)` multiset proven unchanged in the same commit — so the spelling moves and nothing else can. Then examples, then probe gold.

---

## 5. Aggregate uniformity <a id="aggregates"></a>

**Required:** `apply` and `batch` return the standard outer envelope, so `--text` dispatches on one public shape.

- `apply.data.results[]` holds **per-operation envelopes**, plus aggregate metadata (`ops`, `committed`, `failedAt`, `written`).
- `batch.data.results[]` holds **typed batch items**, because a batch child may be an artifact (`model seed`) or a catalog/schema payload rather than an envelope. Existing child-exit semantics are preserved and documented.

**Excluded:** the transitional renderer registry. A bridge preserves two parallel top-level contracts for a release — the defect being fixed — and hides the break instead of surfacing it.

---

## 6. Phasing, with a stop line <a id="phasing"></a>

| phase | content | gate before proceeding |
|---|---|---|
| 0 | contract definitions; schemas written as failing tests; non-envelope verbs enumerated with reasons | schemas red for the right reason |
| 1 | §2 taxonomy + §4 pointers | targeted `:cli:test` + adapter differentials |
| 2 | §5 aggregates | `--text` renders for both, schemas conform |
| 3 | totality guards + description↔behaviour column | guards fail on a reverted fix |
| 4 | catalog / ledger / enricher for [kf167](KERNEL-FINDINGS.md#kf167) | `CatalogExamplesAreKernelValidTest` executes the new examples |
| 5 | corpus → examples → probe gold → eval → **skill last** | `verify-demos.sh`; skill claims re-run against the RC |
| 6 | native build, deterministic gates, probes, Codex activation | release gates green |

**Stop line.** If a phase slips, the release cuts **after Phase 3** (contract + guards) and Phases 4–5 move to the next one. Phase 5 may never precede Phases 1–3: those surfaces currently encode the defects, so regenerating them early would turn the fix into a green build that proves nothing.

**Execution.** Multi-session, so it runs under the `run-master-session` protocol — one active session per worktree, sets matching the phases, a review gate on every landing. Tracking is the §7 gate plus the [`CLI-CONFORMANCE-GAPS`](CLI-CONFORMANCE-GAPS.md) rows, which are **deleted on ship**; no separate tracker.

---

## 7. Acceptance gate <a id="gate"></a>

- [ ] No envelope emits top-level `valid` unless a consistency gate ran in *that invocation*; nested verdicts preserved; `PatternsCommand` and `profile compare` reclassified with exit codes unchanged.
- [ ] `verification` present on every kernel-gated invocation, derived from the executed path; `model report` vs `--tree` differ honestly; no static verb→evidence map is asserted anywhere.
- [ ] Every runtime envelope carries `engine` + a defined `engineVersion`; `model seed`'s Document JSON carries no provenance keys; mixed-engine `batch` keeps evidence on children.
- [ ] Every repeated compute result carries its canonical A12 `field` plus `declaration`; no second pointer spelling; non-canonical input still accepted and normalized.
- [ ] Compute summaries count declarations and instances separately, asserted on ≥2 declarations × multiple rows spanning `VALUE`/`CLEARED`/`ERRORED`.
- [ ] `apply --text` and `batch --text` render human-readable output for success and failure; both JSON shapes conform to published schemas; batch child exits unchanged.
- [ ] No runtime schema or help attributes interpreter output to the kernel; the document-input shape is stated once via `$ref`; the forbidden-token guard is green.
- [ ] All operators in the [kf167](KERNEL-FINDINGS.md#kf167) family document and execute-test their `MVK_NO_CONST` boundary for rules *and* computations, with a valid boundary example; the rejection carries a `fix` while retaining raw kernel text.
- [ ] Operator-coverage guard live on `operator + operand shape + code + consumer context` — never on the code alone.
- [ ] Unknown subcommand + `--help` is rejected consistently across all command groups; `computation check` points to `computation add --dry-run` and `schema computation add`.
- [ ] Totality guards green: `--text` renderability, pointer canonicity (including `examples/*.md` and `probe/tasks/*.md`), verdict provenance.
- [ ] Corpus migrated by mechanical re-render with per-case `(outcome, value)` multisets proven unchanged.
- [ ] `bin/dmtool` diagnoses a stale `JAVA_HOME` and selects or requires the supported dev JDK; native no-Java smoke green; no `--enable-final-field-mutation`.
- [ ] Demos, probe gold, eval docs/tasks, and plugin scope text regenerated **after** the contract; skill updated last with every claim re-run; plugin assets re-synced.
- [ ] Clean Codex install + fresh session lists and triggers the skill; Codex/plugin versions recorded in [`RELEASE-SPEC`](RELEASE-SPEC.md).
- [ ] Breaking commits and release notes marked; one downstream migration.

---

## 8. What this proposal does not own <a id="not-owned"></a>

| concern | home |
|---|---|
| the numeric-wrapper kernel matrix | [KERNEL-FINDINGS kf167](KERNEL-FINDINGS.md#kf167) + `NumericWrapperConstantLawsTest` |
| the `MVK_NO_CONST` law statement | [`MVK-LEDGER`](MVK-LEDGER.md) (corrected in place 2026-07-31) |
| `InterpreterApi.DocumentPointer`'s kept status | [KERNEL-FINDINGS](KERNEL-FINDINGS.md#kf1) (corrected in place 2026-07-31) |
| guard/self-description gaps as tracked work | [`CLI-CONFORMANCE-GAPS`](CLI-CONFORMANCE-GAPS.md) §1 |
| the Codex activation gate | [`RELEASE-SPEC`](RELEASE-SPEC.md) |
| eval/probe scope gaps | [`EVAL-SPEC`](EVAL-SPEC.md) / [`PROBE-SPEC`](PROBE-SPEC.md) |
