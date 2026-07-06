# Semantics formalization — corpus · properties · facets (proposal)

> **Status: proposal — nothing below is built.** Decided with the owner 2026-07-06 (research session). Per the doc lifecycle, shipped items graduate into their owning specs ([TESTING-SPEC](TESTING-SPEC.md), a new corpus spec, a new [CATALOG-SPEC], [INTERPRETER-SPEC](INTERPRETER-SPEC.md), [CLI-SPEC](CLI-SPEC.md)) in the same commit, and this proposal shrinks until it retires (or archives). Work items carry a **model assignment** (§5) so the recommendation surfaces when the item is picked up.

## 1. Goals (owner, 2026-07-06)

1. **Correctness confidence** — kernel-exactness verified beyond today's runtime-only differential; **codegen-parity is urgent** (the per-language codegen templates are essentially unverified).
2. **Portability** — the semantics as a portable, engine-agnostic artifact a **new interpreter** (any language) could be built against; closes the JS-target oracle hole.
3. **Agent-serving** — per-operator semantics machine-readable where programs (not just LLM readers) consume them.
4. **Consolidation** (co-equal, added after the "too many places to look" review): the new surfaces must *absorb* existing scatter, not add to it — one executable home (corpus), one per-operator home (catalog facets), one navigation layer.

Out of scope: **message text** parity (own-text divergence IF3 stands; corpus signatures carry no text). Mechanized/mathematical semantics (Lean/K/paper spec) — rejected under the [PROJECT-DESIGN §21D](PROJECT-DESIGN.md) consumer-exists razor; the one defensible slice (an upstreamable human-readable semantics document) waits until the corpus + runtime layer make it near-mechanical to write, and is not part of this program.

## 2. What "formalize" means here — the settled reading

The project's formalization of kernel semantics is the **(interpreter + differential) pair**: the interpreter is the *statement* (executable, clean-room), the differential is its *validation*. Everything else (findings prose, law tests, catalog) is a projection for a specific reader. This program does three things to that pair:

| Move | Meaning | Artifact |
|---|---|---|
| make the validation **portable** | semantics-as-data, replayable against any engine — including both kernel codegen strategies and a future third-party interpreter | the **conformance corpus** (§7) |
| state the **∀-facts** neither can express | semantics-as-theorems, kernel-free, runs on JVM *and* JS | the **property pack** (§8) |
| make the per-operator facts **machine-consumable** | semantics-as-catalog-facets with self-verifying probes | the **catalog facets** (§9) |

The *statement* half needs no dedicated work: the runtime-semantics extraction is complete by decision (IG5 → IF10, 2026-06-27 — what remains in `evalFunc`/`evalPred` is operand *resolution*, which belongs to the strategy). The as-touched discipline continues; no extraction work item exists in this program.

## 3. Evidence anchors

| Hole / need | Evidence |
|---|---|
| Codegen unverified | the differential's kernel side is the *dynamic Groovy* strategy (`DocumentDynamicRtService` → `ModelGroovyCode`); static JAVA codegen is spot-checked only (`KernelJavaBaselineDiffTest`: 2 rules + perf catalog), static GROOVY / JS targets / TS runtime never |
| JS target has no oracle | kernel is JVM-only; JS correctness rests on ~15 hand-asserted multiplatform cases |
| Fuzz coverage tail | `CatalogParityTest.fuzzPending` — whole operator families with one curated case each |
| Machine consumers guess semantics | `DiagnosticEnricher` is operand-blind (shipped a wrong fix — D1, [eval/RESULTS.md](../eval/RESULTS.md) 2026-06-17); the eval judge false-positived on type-aware empty-operand equivalence despite prose prompts; `RK_UNGUARDED_NUMBER_COMPARISON` is pattern-matched and incomplete |
| Real corpora never *evaluated* | `../a12-kernel-mm` (547 rules) + A12 Tools workspaces: mined for parsing/round-trip and stats only — no differential has ever run them |
| Partial-only semantics | IF54: a relevant empty/**phantom** row is evaluated in partial validation but not full — uncapturable without partial corpus cases |

## 4. The program at a glance

| WI | What | Model | Sessions | Depends on |
|---|---|---|---|---|
| **WI-0** | Phase-0 research sprint (S1–S6, §6.0) — de-risks the estimates, resolves forks | **BEST_MODEL** | 1–2 | — |
| **WI-1** | Codegen tri-differential: `JavaKernelEngine` as third engine over the fuzz nets (+ optional static-GROOVY) | **REGULAR_MODEL** | 1–2 (+1) | — |
| **WI-T** | Divergence triage (reactive, from WI-1/WI-7 findings) | **BEST_MODEL** | unbounded | repro handoff (§5) |
| **WI-2** | Multiplatform DM-JSON → `ModelDef` loader in `:interpreter` (+ `InterpreterModelLoader` delegates) | **REGULAR_MODEL** | 2–3 | WI-0 (fork F1) |
| **WI-3a** | Corpus **contract**: case format, runner spec, corpus-spec draft | **BEST_MODEL** | 1 | WI-0 (S1) |
| **WI-3b** | Corpus capture + JVM replay implementation | **REGULAR_MODEL** | 1–3 | WI-2, WI-3a |
| **WI-4** | Property pack in `interpreter/commonTest` | **REGULAR_MODEL** | 1–2 | — |
| **WI-5a** | Facet **vertical slice**: `emptyOperand` end-to-end (schema → populate → verification probes → lint → enricher → serving) | **BEST_MODEL** | 2–3 | WI-0 (S6) |
| **WI-5b** | Facet bulk: remaining dimensions, 109-op population, judge wiring, demos, native metadata re-capture | **REGULAR_MODEL** | 2–3 | WI-5a |
| **WI-5c** | Skill migration (empty-value table, sign notes → facets) + N6 eval re-run | **BEST_MODEL** | 1 | WI-5b |
| **WI-6** | JS replay (`expect/actual` FS shim + corpus in `jsTest`) | **REGULAR_MODEL** | 1–2 | WI-2, WI-3b |
| **WI-7** | Opt-in real-corpus sweeps (`../a12-kernel-mm` + workspaces) | **REGULAR_MODEL** | 1–2 | WI-1, WI-2 |
| **WI-8a** | `-GAPS` doc rename sweep (§10) | **REGULAR_MODEL** | ≤1 | — |
| **WI-8b** | Consolidation: re-homing verify-sweep + semantics navigation map + resolve-guard | **BEST_MODEL** | 1–2 | — |

Bounded totals: **BEST_MODEL ≈ 6–9 sessions**, **REGULAR_MODEL ≈ 9–15 sessions** (≈ 15–24 overall), plus the two open-ended discovery items (§13). WI-1 is the urgent start; WI-4 and WI-8a/b are independent fillers; WI-5a's schema design can begin while WI-2/3 build.

## 5. Model policy (owner directive, 2026-07-06)

**Switch models only at work-item or session boundaries — never mid-item.** Context continuity for related work beats per-token cost; a work item runs start-to-finish on its assigned model, and a session packs only same-model items. The assignments are **role-based, not product-pinned**: **BEST_MODEL** = the most capable coding-agent model available when the item starts (deepest reasoning, at a price premium); **REGULAR_MODEL** = the standard frontier coding model. Re-resolve both to concrete models at execution time.

- **Why the split:** the repo's guard net (differential, fuzz, conformance matrix, `verify-demos.sh`) catches correctness slips in mechanical work cheaply — that insures **REGULAR_MODEL** (at `xhigh` effort) for well-specified execution. What guards *cannot* catch — a wrong published contract, a symptom-patch instead of the root-cause class fix, a workaround that hides a gap, a stale skill claim — is where **BEST_MODEL** is the cheap insurance. The price premium is paid exactly where CI can't help.
- **Divergence protocol (the WI-T handoff):** when a tri-differential or sweep finds a divergence during a REGULAR_MODEL session, do **not** triage in-session. Record a minimized repro (a committed failing/quarantined case + a ledger row), finish the item's remaining mechanical scope, and hand the repro to a **fresh BEST_MODEL session**. The repro file is the context handoff.
- **Escalation:** a genuine design fork surfacing mid-REGULAR_MODEL-item → record it, stop at a clean boundary, take the fork to a BEST_MODEL session. **De-escalation:** a BEST_MODEL item's mechanical tail (e.g. writing probes after the schema settles) finishes in-session — no switch mid-item.
- Each WI section below repeats its `Model:` so the assignment surfaces when the item is picked up, not just in the table.

## 6. Work items in detail

### 6.0 WI-0 — Phase-0 research sprint — `Model: BEST_MODEL`

Cheap, decisive checks whose outcomes swing the estimates; results are written back into this proposal (estimates + fork resolutions) before WI-3a.

- **S1 — parser-coverage sweep (decisive for WI-3).** Run every catalog `validExample`, the fuzz rule DSL, and all curated DiffTest condition strings through `parseCondition`; classify failures. Only 3 DiffTests exercise the parser today — the corpus forces *every* case through it, and `Parser.kt` self-describes as "NOT yet the full condition grammar". A small gap → fix TDD per failure; a large gap → a parser workstream gets added to this program with its own estimate.
- **S2 — SeedGenerator enum handling** (`Kind` has no ENUMERATION; `enumValues` rides on the field — verify/extend legal-member seeding) + a kind histogram of the real corpora (they're enum-heavy; WI-7 depends on this).
- **S3 — normative codegen strategy.** The standing rule says the interpreter matches *production codegen* on divergence — determine from kernel docs/BA which target that is (static Java? JS? Groovy-dynamic?), so WI-T has a decision rule and the corpus's `divergences` records name a winner.
- **S4 — [INTERPRETER-SPEC](INTERPRETER-SPEC.md) §11 in full** (the open production-parser decision + the EUPL question on generating from the kernel `.g4`) — S1's result feeds this; the proposal's fork F1/F4 resolutions land here.
- **S5 — kernel-side clock pinning** (read `ClockDiffTest`/`NowDiffTest`): confirm how capture keeps kernel and interpreter clocks aligned, and whether kernel replay of a clock-bearing corpus case is possible or excluded.
- **S6 — `Where` population feasibility (D1 fix).** `Diagnostic.Where` already carries `operator`/`span` but is empty at enrich time — establish what the kernel's problem reports expose at check-failure, deciding whether WI-5a's enricher consumer is operand-aware selection or a narrower disambiguation.

### 6.1 WI-1 — codegen tri-differential — `Model: REGULAR_MODEL`

Wire `JavaKernelEngine` (static-JAVA codegen, in-memory javac, accepts arbitrary self-contained model JSON) as a **third engine** into the `FuzzDiagnostics` comparison, over the existing fuzz nets: `interp == groovy-dynamic == java-static` at `code|type|pointer` signature granularity. Codegen+compile is once-per-model (~hundreds of ms) — acceptable at fuzz-tag cadence. Optional follow-on (+1 session): static-GROOVY via a `GroovyClassLoader` compile path (not just an enum flip — generated `.groovy` needs the Groovy compiler, which the kernel already ships). Kernel-JS codegen targets and the TS runtime are **deferred** (need a JS host harness; re-open when WI-6's JS infrastructure exists). Divergences → WI-T per §5. The first full run doubles as the feasibility probe for codegen construct coverage.

### 6.2 WI-2 — multiplatform DM-JSON loader — `Model: REGULAR_MODEL`

A `commonMain` DM-JSON → `ModelDef` loader in `:interpreter` (expanded, self-contained models only — includes are resolved by the kernel's `expand()` upstream; documented as the v1 scope). This is a **product feature first**: it unlocks the standalone JS-library goal (today JS consumers must hand-build `ModelDef`), and it is structurally required even for *JVM* corpus replay — the existing `InterpreterModelLoader` lives in `:cli`, which `:adapter` cannot depend on without a cycle. `InterpreterModelLoader` shrinks to a thin delegate. TDD against the existing fixtures; the deserializer-tier quirks (locale formats, discriminators) are documented territory in [KERNEL-FINDINGS](KERNEL-FINDINGS.md). Ships with an [API-GUIDE](API-GUIDE.md) snippet + an [API-FINDINGS](API-FINDINGS.md) finding, and INTERPRETER-SPEC's standalone-boundary update, same commit. Serialization dependency per fork F1.

### 6.3 WI-3a/b — the corpus — `Model: 3a BEST_MODEL · 3b REGULAR_MODEL`

WI-3a fixes the published **contract** (§7): the case format, the canonicalization rules, the runner spec, the corpus-spec draft doc. WI-3b implements capture (promote `Place` to a public serializable type; pin fuzz seeds; a capture harness in `:adapter`) and JVM replay for interpreter + codegen-Java, with the build-discipline guards (§7 guards). Migrating the ~105 curated DiffTest scenarios into cases is **drip work, not a gate** — per-family, alongside other touches; corpus value comes first from fuzz-pinned + facet-generated cases.

### 6.4 WI-4 — property pack — `Model: REGULAR_MODEL` (statement questions escalate per §5)

Kernel-free ∀-properties in `interpreter/commonTest` (runs JVM **and** JS), hand-rolled seeded style consistent with `SeedGenerator`; needs only `private` → `internal` seams. The pack (§8) is specified here so implementation is mechanical; any property whose *statement* proves contentious mid-item is recorded and escalated to a BEST_MODEL boundary rather than decided in-session.

### 6.5 WI-5a/b/c — facets — `Model: 5a BEST_MODEL · 5b REGULAR_MODEL · 5c BEST_MODEL`

WI-5a is a **vertical slice**: the `emptyOperand` dimension end-to-end — schema, population for its operators, the N7-style verification lane (every facet claim generates a kernel-executed probe), the lint rework (`RK_UNGUARDED_NUMBER_COMPARISON` becomes facet-driven and complete), the enricher consumer (shape per S6), and serving through `operators` (including fixing the dropped `gotcha.kind`). Only after the slice survives its consumers does WI-5b populate the remaining dimensions (§9) across all 109 operators, wire the eval judge, regenerate demos, and **re-capture native-image metadata** (an `operators.json` change is a re-capture trigger). WI-5c migrates the skill's per-operator facts to facets under the full skill-edit discipline (re-read whole file, verify every claim through the binary) and re-runs the N6 eval via the `run-dmtool-eval` skill.

### 6.6 WI-6 — JS replay — `Model: REGULAR_MODEL`

An `expect/actual` FS shim for Node `jsTest`, corpus JSON parsing in common code (rides WI-2's serialization choice), and the replay suite on the JS target — the first kernel-grounded oracle JS has. Also the prerequisite for ever adding kernel-JS-codegen comparison (deferred, WI-1).

### 6.7 WI-7 — opt-in real-corpus sweeps — `Model: REGULAR_MODEL` (findings → WI-T)

For each model in `../a12-kernel-mm` (15 metamodels, 547 kernel-team-authored rules) and the A12 Tools workspaces: kernel `expand()` → loader → `SeedGenerator` instances → tri-engine comparison. Opt-in and env-guarded (the `KernelMmIntegrationTest` pattern — skips when the sibling/volume is absent); **nothing persisted** from either corpus (EUPL / proprietary). Unsupported constructs → a skip-and-report triage list (itself valuable: interpreter coverage measured against *real* usage). Custom conditions skip by class (no SPI impls). A divergence found live is minimized and **re-expressed as an own-domain committed corpus case** — the real corpus discovers, the committed corpus records (the standing copy-the-mechanism rule, extended from parsing to evaluation).

### 6.8 WI-8a/b — consolidation — `Model: 8a REGULAR_MODEL · 8b BEST_MODEL`

**WI-8a** — the naming fix (§10): drop `-LEDGER` from the gap-tracker family so the suffix uniquely means "complete CI-locked classification". Pure markdown rename + reference grep-sweep (no code couples to the filenames; doc-to-doc links have no resolve-guard, so the sweep is deliberate).
**WI-8b** — (1) a **re-homing verify-sweep**: confirm every kernel truth discovered via the differential has its KERNEL-FINDINGS home (the routing held for IF53/IF54 — this sweep verifies the rest rather than assuming a backlog); IF entries keep only interpreter treatment. (2) The **semantics navigation map**: one page keyed by the KERNEL-SEMANTICS 14-section taxonomy — per section: the canonical prose (`../a12-kernel-demo/KERNEL-SEMANTICS.md` §n), our supplements (KERNEL-FINDINGS §n), facets, corpus families, locking tests — with a resolve-guard test (the `CommandReferencesResolveTest` pattern) so it cannot rot. The corpus/facet meta reuse the same §-taxonomy as tags, which is what makes the map derivable-and-checkable rather than hand-curated prose.

## 7. Corpus design

### Case format (settled sketch — WI-3a finalizes)

```
{ meta:       { id, kernelVersion: "30.8.1", source: fuzz-seed | curated | facet-generated,
                sections: ["§9"], tags: ["partial", ...] },
  model:      <expanded, self-contained DM-JSON; rules embedded as stored-English condition strings>,
  clock:      "2026-07-06T12:00:00",              // present iff the case uses Now/Today; runners inject via EvalClock
  placements: [ {kind: GROUP|FIELD|EMPTY, path, reps: [..], value?} ],
  op:         validateFull | validatePart{ relevant: [..] } | compute,
  expected:   [ "CODE|VALUE_ERROR|/Lines[2]/Amount", ... ],   // sorted multiset; NO message text
  computeExpected: [ "path|VALUE|v" | "path|CLEARED" | "path|ERRORED" ],
  divergences: [ {strategy, signatures} ] }                    // recorded facts; expected = production codegen (S3)
```

Keying on DM-JSON is deliberate: it is the production input any real engine must parse, and the embedded stored-English conditions make the corpus a **parser conformance suite for free** (which is why S1 gates WI-3). `validatePart` cases are included and tagged (fork F3, resolved): partial-only semantics — IF54's phantom-row firing — exist nowhere else, so the runner contract must represent pointers to non-existent rows.

### The two-lane license structure

| Lane | Content | Posture |
|---|---|---|
| **Committed portable corpus** | own-domain models + captured expectations | MIT-clean; the future third-party acceptance suite |
| **Opt-in live sweeps** (WI-7) | real corpora evaluated in place, nothing persisted | discovery net only; findings re-expressed own-domain into lane 1 |

### Replay matrix

| Engine | Via |
|---|---|
| kernel Groovy-dynamic | capture source (`RuntimeLaws`) |
| kernel static-JAVA codegen | `JavaKernelEngine` (WI-1) |
| kernel static-GROOVY codegen | optional WI-1 follow-on |
| interpreter JVM | loader (WI-2) + replay (WI-3b) |
| interpreter JS | WI-6 |
| kernel JS codegen / TS runtime | deferred — re-open when a JS host harness exists |
| a new third-party interpreter | the JSON files + the runner spec |

### Runner spec (the third-party contract; WI-3a authors it)

Placement semantics (parent-before-child, 1-based repetition indexes), the pointer format **including phantom rows**, value canonicalization (decimal scale/trailing-zero rules), sort/multiset comparison rules, clock injection, locale pin (`Locale.US`-equivalent behavior; message text excluded), and the compute-pointer form — specced cleanly, replacing the current regex index-strip in the comparator (a wart that must not leak into a published contract).

### Guards & maintenance

Corpus-reading tests run in a **dedicated Gradle task with the corpus files as declared inputs** (the `docGuardTest` pattern — prevents both false-green and full-suite re-runs). The corpus registers as a **new guardian in the [KERNEL-UPGRADE](KERNEL-UPGRADE.md) coupling map**: on a kernel bump, regenerate expectations and diff — the diff *is* the behavior-change characterization. [LICENSING](LICENSING.md) §19 provenance re-confirm before the corpus is ever published (fork F2).

## 8. Property pack (WI-4)

Kernel-free, ∀-quantified, sampled via seeded `SeedGenerator` models; each also runs on JS. Initial set:

1. **Lazy ≡ eager polarity** — `evalTypedLazy == evalTyped` on every sampled (model, doc); currently a trusted KDoc induction.
2. **Kleene monotonicity / suppression soundness** — routing any operand to UNKNOWN never flips TRUE↔FALSE; branch-scoped suppression follows.
3. **Row-permutation equivariance** — permuting repetition rows permutes pointers; the code multiset is invariant.
4. **Empty-row append laws** — stated against the settled IF53/IF54 semantics: appending an instantiated-empty row is *content* (full validation evaluates it); clamped vs full-declared-range quantifier deltas as specified.
5. **Aggregate identities** — all-empty `Sum`/`MaxValue`/`MinValue` = 0; `Min`/`Max` operand-list empty-substitution; `NumberOfDifferentValues` of the empty set.
6. **Hash-join ≡ nested-loop** — the `$`-correlation fast path equals the naive tally on the same input.
7. **Determinism** — `validateFull` twice ≡ once.

Exploratory (not committed scope): polarity *soundness* (an OMISSION verdict implies a fill exists that changes the outcome) — statement first, at a BEST_MODEL boundary.

## 9. Catalog facets (WI-5)

A `semantics` block per operator in `operators.json` (schema-safe: `OperatorEntry` ignores unknown fields; precedent for dual prose+machine fields: `gotcha.kind`, `invalidExample.error`). Dimensions, in slice order:

| Dimension | Values (sketch) | Evidence |
|---|---|---|
| `emptyOperand` (the slice) | operator-level deviation from the type default: `AS_ZERO` / `NOT_EVALUATED` / `IGNORES_EMPTY` / `EVALUATED_EMPTY` | class B — the #1 recurring semantic failure; judge false-positives; incomplete lints |
| `polarity` | `DIRECTIONAL` / `FIXED`, sign convention (`b − a`) | class A2 (`Difference*` sign inversions) |
| `iterationRange` | `DECLARED` / `INSTANTIATED` / `MIXED` | class C (quantifier ranges; IF49) |
| `boundary` | `STRICT` / `INCLUSIVE` | tolerance ops fire strictly beyond N |
| `aggregateIdentity` | `ZERO` / `IGNORES_EMPTY` | `Sum` vs `MaxValue`/`MinValue`; note `OperatorKind.AGGREGATE` exists but is unused — facet, don't re-kind |

**The four consumers** (what makes facets pass the §21D razor): the enricher (operand-aware fix selection — shape per S6; collapses the union-fix / `StructuralKernelFix` compensations for the D1 class), the lints (completeness), the eval judge, and **facet → generated corpus cases** (each facet value implies probe cases, kernel-executed N7-style — facets are self-verifying, never-rot). Type-level empty defaults (`emptyValue(kind)`) live once in a catalog-header table; facets record only operator-level deviations.

## 10. Consolidation details (WI-8)

**The `-GAPS` rename** — `-LEDGER` currently names two opposite instruments. After the rename the suffix is readable off the filename: `*-GAPS.md` = open work only, rows deleted on ship; `*-LEDGER.md` = complete CI-locked classification of an external constant, rows kept forever.

| Current | New |
|---|---|
| FEATURE-GAPS-LEDGER.md | FEATURE-GAPS.md |
| DEV-API-GAPS-LEDGER.md | DEV-API-GAPS.md |
| INTERPRETER-GAPS-LEDGER.md | INTERPRETER-GAPS.md |
| SCHEMAKIT-GAPS-LEDGER.md | SCHEMAKIT-GAPS.md |
| MODEL-REVIEW-LEDGER.md | MODEL-REVIEW-GAPS.md |
| MVK / RT-SEMANTICS / GRAMMAR ledgers | unchanged — the suffix now uniquely theirs |

CLAUDE.md's lifecycle section gains the third suffix row; the two disambiguation paragraphs collapse. The corpus program itself uses both correctly from day one: per-operator **case-coverage classification** (today half-embedded in `fuzzPending`) becomes a true `-LEDGER`; the program's open build items live in INTERPRETER-GAPS.

## 11. Open forks

| # | Fork | State |
|---|---|---|
| F1 | Loader serialization: kotlinx.serialization (compiler plugin + runtime in the publishable artifact) vs hand-rolled minimal JSON reader (the module already hand-rolls its condition parser) | **open — owner call at WI-0/S4**; the loader itself is settled (the `:cli`/`:adapter` cycle makes it structurally required) |
| F2 | Corpus publication | in-repo directory now; **publish when an external consumer materializes** (re-open trigger), after LICENSING §19 re-confirm |
| F3 | `validatePart` cases | **resolved: include, tagged `partial`** — IF54's phantom-row semantics are otherwise uncapturable |
| F4 | Parser strategy if S1 finds a large gap | decided at WI-0 with S4's EUPL constraint in view (hand-written completion keeps the license question moot) |

## 12. Doc impact

| Doc | Change |
|---|---|
| **new:** corpus spec | format, capture/replay, two lanes, runner contract (graduates from WI-3) |
| **new:** CATALOG-SPEC | extracted per the outgrows-its-section rule: catalog schema + facet vocabulary + verification lane (WI-5) |
| **new:** semantics navigation map | the §-taxonomy × surface page + resolve-guard (WI-8b) |
| [TESTING-SPEC](TESTING-SPEC.md) | tier map gains tri-differential, corpus replay, property tier, real-corpus sweeps |
| [INTERPRETER-SPEC](INTERPRETER-SPEC.md) | verification contract (corpus + both codegen strategies + properties); standalone boundary gains DM-JSON loading; §11 parser decision recorded |
| [INTERPRETER-ARCHITECTURE](INTERPRETER-ARCHITECTURE.md) | loader package, property seams |
| [SEED-GENERATOR-FUZZ-SPEC](SEED-GENERATOR-FUZZ-SPEC.md) | pointer: fuzz seeds pin into corpus cases |
| [KERNEL-UPGRADE](KERNEL-UPGRADE.md) | corpus as a new guardian in the coupling map |
| [CLI-SPEC](CLI-SPEC.md) / [CLI-CONFORMANCE-SPEC](CLI-CONFORMANCE-SPEC.md) | `operators` payload growth; enricher upgrade from union-fix to operand-aware selection |
| [PROJECT-DESIGN](PROJECT-DESIGN.md) §21D/§21G | the corpus as the missing option; updated target-per-kind row; decision-log entry |
| [ARCHITECTURE](ARCHITECTURE.md) | `:interpreter` package map (loader); corpus directory |
| [API-GUIDE](API-GUIDE.md) / [API-FINDINGS](API-FINDINGS.md) | loader snippet + finding |
| skill/SKILL.md | *shrinks* (WI-5c) under the full skill-edit discipline |
| KERNEL-FINDINGS | lock-pointer vocabulary widens (`corpus family …` / `facet probe …`); navigation anchor |
| gap docs | renamed per §10; MVK/RT/GRAMMAR ledgers unchanged |

Verb surface: **no new verbs** (the `operators` payload grows; `model seed` already exists). dmkits typed API: unchanged except the additive `OperatorEntry.semantics`. `:interpreter` API: the loader is the one genuine addition.

## 13. Risks & unbounded items

- **Divergence triage (WI-T) is discovery, not overrun** — finding interpreter≠production-codegen divergences is the point; each is #1-priority exactness work that preempts the schedule. History (the fuzz bug track record) suggests hours-to-a-day each; the count is unknowable.
- **Facet vocabulary will need one revision cycle** after the enricher and lints — the consumers with real logic — push back on the first schema. The vertical slice (WI-5a) caps that cost at one dimension.
- **S1 is the estimate swing**: a large parser gap converts WI-3 from 2–4 sessions into a program-with-a-parser-workstream. Do not start WI-3a before S1 lands.
- Native-image re-capture (WI-5b) and the corpus-task Gradle-input discipline (§7) are known traps carried as explicit checklist items, not risks.
