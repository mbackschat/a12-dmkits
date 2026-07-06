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
| **WI-0** | Phase-0 research sprint (S1–S6, §6.0) — **done 2026-07-06**, findings in §6.0 | **BEST_MODEL** | ~~1–2~~ done | — |
| **WI-1** | Codegen tri-differential: `JavaKernelEngine` as third engine over the fuzz nets (+ optional static-GROOVY) — **done 2026-07-06** (all six fuzz nets tri-green; static `validatePart` verified & included; static-GROOVY skipped) | **REGULAR_MODEL** | ~~1–2 (+1)~~ done | — |
| **WI-T** | Divergence triage (reactive, from WI-1/WI-7 findings; anchor = groovy-dynamic per S3) | **BEST_MODEL** | unbounded | repro handoff (§5) |
| **WI-P** | Parser completion against the S1 gap list (case variants, `;;` comments, `..Name`, value-list literals, `For` shapes) — **done 2026-07-06** (both corpora parse 100%; IG35 → IF55; INTERPRETER-SPEC §11 / fork F4 resolved) | **REGULAR_MODEL** | ~~1–2~~ done | WI-0 |
| **WI-2** | Multiplatform DM-JSON → `ModelDef` loader in `:interpreter` (+ `InterpreterModelLoader` delegates) — **done 2026-07-06** (`load.ModelLoader`, JVM+JS; delegate + parity over all 37 fixture models; kotlinx tree API per F1) | **REGULAR_MODEL** | ~~2–3~~ done | WI-0 (fork F1) |
| **WI-3a** | Corpus **contract**: case format, runner spec, corpus spec — **done 2026-07-06** ([CONFORMANCE-CORPUS-SPEC](CONFORMANCE-CORPUS-SPEC.md); §7's detail superseded by it) | **BEST_MODEL** | ~~1~~ done | WI-0 (S1) |
| **WI-3b** | Corpus capture + JVM replay implementation — **done 2026-07-06** (per-row compute identity; serializable case model + tree-API JSON; tri-verified capture on the clock-pinned modern path; 3-way JVM replay guard; initial own-domain case set; schema/id guard + declared-inputs task) | **REGULAR_MODEL** | ~~1–3~~ done | WI-2, WI-3a |
| **WI-4** | Property pack in `interpreter/commonTest` — **done 2026-07-06** (all seven §8 properties, seeded/hand-rolled, green on jvm + js; 3 internal seams; property 2 scoped off counting quantifiers, property 4 off the IF49 declared-range clamp) | **REGULAR_MODEL** | ~~1–2~~ done | — |
| **WI-5a** | Facet **vertical slice**: `emptyOperand` end-to-end (schema → populate → verification probes → lint → enricher → serving) | **BEST_MODEL** | 2–3 | WI-0 (S6) |
| **WI-5b** | Facet bulk: remaining dimensions, 109-op population, judge wiring, demos, native metadata re-capture | **REGULAR_MODEL** | 2–3 | WI-5a |
| **WI-5c** | Skill migration (empty-value table, sign notes → facets) + N6 eval re-run | **BEST_MODEL** | 1 | WI-5b |
| **WI-6** | JS replay (`expect/actual` FS shim + corpus replay on jvm+js) — **done 2026-07-06** (Node FS shim via `CORPUS_DIR`; shared kernel-free `InterpreterReplay`; `CorpusReplayCommonTest` green on `jvmTest` + `jsNodeTest`, 0 FAIL/0 SKIP — the first kernel-grounded JS oracle) | **REGULAR_MODEL** | ~~1–2~~ done | WI-2, WI-3b |
| **WI-7** | Opt-in real-corpus sweeps (`../a12-kernel-mm` + workspaces) + the S2 seeding prerequisites | **REGULAR_MODEL** | 2–3 | WI-1, WI-2, WI-P, WI-9 |
| **WI-8a** | `-GAPS` doc rename sweep (§10) — **done 2026-07-06** (5 files renamed, all refs swept, CLAUDE.md suffix rule collapsed) | **REGULAR_MODEL** | ~~≤1~~ done | — |
| **WI-8b** | Consolidation: re-homing verify-sweep + semantics navigation map + resolve-guard | **BEST_MODEL** | 1–2 | — |
| **WI-9** | `DateFragmentType` eval support (IG36's open half): fragment precision model + loader case + comparability/`Add*` semantics, differential-locked (added 2026-07-07, decided over folding into WI-7 or skip-listing — a `Kind.DATE`+format carry would mis-evaluate month/year-only fragments) | **REGULAR_MODEL** | ≤1 | WI-2 |

Bounded totals after Phase-0: **BEST_MODEL ≈ 5–8 further sessions** (WI-0 done), **REGULAR_MODEL ≈ 12–20 sessions** (≈ 17–28 overall), plus the open-ended discovery items (§13). WI-1 is the urgent start; WI-P, WI-4, and WI-8a/b are independent fillers; WI-5a's schema design can begin while WI-2/3 build.

## 5. Model policy (owner directive, 2026-07-06)

**Switch models only at work-item or session boundaries — never mid-item.** Context continuity for related work beats per-token cost; a work item runs start-to-finish on its assigned model, and a session packs only same-model items. The assignments are **role-based, not product-pinned**: **BEST_MODEL** = the most capable coding-agent model available when the item starts (deepest reasoning, at a price premium); **REGULAR_MODEL** = the standard frontier coding model. Re-resolve both to concrete models at execution time.

- **Why the split:** the repo's guard net (differential, fuzz, conformance matrix, `verify-demos.sh`) catches correctness slips in mechanical work cheaply — that insures **REGULAR_MODEL** (at `xhigh` effort) for well-specified execution. What guards *cannot* catch — a wrong published contract, a symptom-patch instead of the root-cause class fix, a workaround that hides a gap, a stale skill claim — is where **BEST_MODEL** is the cheap insurance. The price premium is paid exactly where CI can't help.
- **Divergence protocol (the WI-T handoff):** when a tri-differential or sweep finds a divergence during a REGULAR_MODEL session, do **not** triage in-session. Record a minimized repro (a committed failing/quarantined case + a ledger row), finish the item's remaining mechanical scope, and hand the repro to a **fresh BEST_MODEL session**. The repro file is the context handoff.
- **Escalation:** a genuine design fork surfacing mid-REGULAR_MODEL-item → record it, stop at a clean boundary, take the fork to a BEST_MODEL session. **De-escalation:** a BEST_MODEL item's mechanical tail (e.g. writing probes after the schema settles) finishes in-session — no switch mid-item.
- Each WI section below repeats its `Model:` so the assignment surfaces when the item is picked up, not just in the table.

## 6. Work items in detail

### 6.0 WI-0 — Phase-0 research sprint — `Model: BEST_MODEL` — **DONE 2026-07-06**

The sprint's findings, applied throughout this proposal (the sweep method is recreatable: parse the catalog `validExample`s + the fuzz DSL + the MM/workspace stored conditions through `parseCondition`, classify failures):

| Item | Outcome |
|---|---|
| **S1 parser coverage** | **Benign — no program change, but a new bounded work item (WI-P / IG35).** Canonical populations parse 100% (catalog 108/108, fuzz 28/28); MM corpus 647/657; workspaces 46% raw — the failures collapse into a kernel-grammar-verified gap list: keyword **case variants** (`and`/`AND`, `or`/`OR`, `in`, `to` — enumerated per-variant in `LexerTerminals_en`, not blanket case-folding), **`;;` comments** (a lexer token with multi-line continuation), **`..Name` named-ancestor paths** (the `relativeUp` production), the **value-list literal form** `Pred(field, "a", "b")`, **`For`-semantic-index shapes**, plus Having/absolute-path residuals to re-triage after the case fix (~23 further failures were artifacts of the sweep's fixed base path). These are live product gaps too: `model eval` on real workspace models hits them today. |
| **S2 seeding** | Enum seeding is **already correct by construction** (`ValidValues` draws a declared member when `enumValues` is populated). The real WI-7 blocker is **`TypeDefType` resolution** (258 fields; 67% of kernel-mm; e-commerce's cross-model backbone) — the self-contained loader flattens typedef refs to STRING with empty `enumValues`; cheapest fix: kernel pre-expansion on the read tier before loading. Second tier: constraint-aware seeding (number bounds SMALL, string lengths TRIVIAL, pattern satisfaction MEDIUM or leave-empty fallback). Two discovered gaps recorded as ledger row **IG36** (`DateFragmentType` unmodeled; `TimeType.format` dropped by the loader). |
| **S3 normative strategy** | **Production-normative on the JVM = the dynamic Groovy service** (the kernel dev guide: "the dynamic solution is usually used"; static is a startup optimization); browser = pre-generated `JAVA_SCRIPT_VK` on the hand-ported TS runtime, a pre-check subordinate to the server. → the corpus `expected` anchor and WI-T's decision rule are **groovy-dynamic** — exactly what the differential already captures; java-static divergences are *language-execution artifacts* of the same generated logic (Groovy vs Java), recorded but never adopted as `expected`. |
| **S4 spec §11** | Fork **F4 resolved**: complete the **hand-written parser** (the option §11 didn't list — it weighed only kernel parse-tree-api vs ANTLR-from-`.g4`); the EUPL derivative question on the grammar is moot (reading the grammar for syntax *shapes* is mechanism, not expression). INTERPRETER-SPEC §11 records the decision when WI-P ships. |
| **S5 clock** | The kernel clock **is pinnable**: `DocumentProcessingConfig.currentDateForTest(Date)` pins **both** Today and Now from one instant (`RuntimeController.validationTime`; Today = the instant's date cleared in the model's timezone). Requires the modern `DocumentV2`+config path — the legacy `validateFull(IDocument, Locale)` the capture path uses today has no hook, so WI-3b routes capture/replay through the modern path. Corpus consequence (§7): `clock` is a **single instant**; an independent today/now pair is unrepresentable kernel-side. The existing differential's far-sentinel-date technique remains valid for its own tests. |
| **S6 Where/D1** | **Feasible now, light plumbing.** The kernel exposes no span/operator/operand at check time (`RankedNotification` = message + severity; positions hardcoded `-1`) — but the **`SpanResolver` read pass already holds the typed `Condition` AST**, pins the operator by catalog∩usage intersection, and re-derives fixes; operand kinds sit on `FieldRef`, literals are typed nodes (`StringLiteral` vs `DateLiteral`). → WI-5a's operand-aware fix selection lands **in `SpanResolver`**, not `DiagnosticEnricher`; no full parse needed; foreign-rule fallback via `parseCondition`. |

### 6.0b WI-P — parser completion (from S1) — `Model: REGULAR_MODEL` — **DONE 2026-07-06**

Completed the hand-written parser against the S1 gap list, TDD with the recreated sweep as the red/green driver. **Outcome:** all IG35 classes fixed, **both corpora parse 100%** — mm conditions 647→657/657; workspace conditions 177→368/368, operations 206→234/234 — with **zero residuals to classify**. The gap collapsed almost entirely to (1) **keyword case variants** (a fixed `KEYWORD_VARIANTS` table for And/Or/In per `LexerTerminals_en` — not case-folding; `to`/`To`/`TO` has no match site since `ValueNotConsistent` isn't parsed) and (2) **`..Name`** turning-point paths (all 10 mm failures; clean-room from `CheckRelativeUpImpl` — the label is ignored, up-count drives resolution — locked against the real kernel by `NamedAncestorPathDiffTest`). (3) **`;;` comments** (incl. multi-line continuation) were already skipped; (4) the **value-list literal form** already mapped onto `Cond.Pred`; (5) **`For` on bare args** needed a shared `entitySuffix` helper. (6) The Having/absolute residuals were all case-variant failures in disguise. The sweep (`adapter.laws.ParserCorpusSweepTest`) is kept as a permanent guard: catalog + fuzz always-on (100%), the corpora opt-in (`assumeTrue`-skip). Graduated: INTERPRETER-SPEC §11 (fork F4 — hand-written completion), IG35 → INTERPRETER-FINDINGS IF55. No GRAMMAR-LEDGER row flipped (it tracks the renderer/adapter, not the interpreter parser). **Gates WI-7** (real stored text now parses).

### 6.1 WI-1 — codegen tri-differential — `Model: REGULAR_MODEL` — **DONE 2026-07-06**

Wire `JavaKernelEngine` (static-JAVA codegen, in-memory javac, accepts arbitrary self-contained model JSON) as a **third engine** into the `FuzzDiagnostics` comparison, over the existing fuzz nets: `interp == groovy-dynamic == java-static` at `code|type|pointer` signature granularity. Codegen+compile is once-per-model (~hundreds of ms) — acceptable at fuzz-tag cadence. Per S3, **groovy-dynamic is the parity anchor** (the production-normative strategy); a java-static divergence is a language-execution artifact of the same generated logic — recorded, investigated (it may still reveal an interpreter bug *shared* with one side), never adopted as `expected`. Optional follow-on (+1 session): static-GROOVY via a `GroovyClassLoader` compile path — per S3 it is *not* a distinct production path, so this is diagnostic value only. Kernel-JS codegen targets and the TS runtime are **deferred** (need a JS host harness; re-open when WI-6's JS infrastructure exists). Divergences → WI-T per §5. The first full run doubles as the feasibility probe for codegen construct coverage.

**Outcome (2026-07-06).** Shipped. `RuntimeLaws.JavaKernelEngine` gained the full `code|type|pointer` signature (`messages`/`compute`, reusing `fmtPointer`/`toMsg`); `FuzzDiagnostics` runs it as a per-model-cached third engine so all six nets assert `interp == groovy-dynamic == java-static` (validateFull + `validatePart` + `compute`). **Static `validatePart` is supported** — verified by `JavaStaticPartialBaselineDiffTest` (it gates a relevant subset identically to Groovy via the modern `DocumentV2` conversion), so partial validation is **included** in the tri-comparison, not scoped out. **No divergences appeared** — `interp == groovy == java-static` held across every net (100/200/40×5 seeds), so no repro/quarantine/ledger row was needed. Cadence: the six nets run in ~1m49s in parallel; `ModelFuzzDiffTest`'s 40 fresh javac compiles cost ~47 s — inside the fuzz-tagged tier. Contract graduated to [TESTING-SPEC](TESTING-SPEC.md) (tier map "Codegen tri-differential" + the fuzz-lane note). **Static-GROOVY skipped** (not trivial — needs a `GroovyClassLoader` compile path; per S3 it is not a distinct production strategy, so pure diagnostic value); the follow-on remains available if a codegen divergence ever motivates it.

### 6.2 WI-2 — multiplatform DM-JSON loader — `Model: REGULAR_MODEL` — **DONE 2026-07-06**

A `commonMain` DM-JSON → `ModelDef` loader in `:interpreter` (expanded, self-contained models only — includes are resolved by the kernel's `expand()` upstream; documented as the v1 scope). This is a **product feature first**: it unlocks the standalone JS-library goal (today JS consumers must hand-build `ModelDef`), and it is structurally required even for *JVM* corpus replay — the existing `InterpreterModelLoader` lives in `:cli`, which `:adapter` cannot depend on without a cycle. `InterpreterModelLoader` shrinks to a thin delegate. TDD against the existing fixtures; the deserializer-tier quirks (locale formats, discriminators) are documented territory in [KERNEL-FINDINGS](KERNEL-FINDINGS.md). Ships with an [API-GUIDE](API-GUIDE.md) snippet + an [API-FINDINGS](API-FINDINGS.md) finding, and INTERPRETER-SPEC's standalone-boundary update, same commit. Serialization dependency per fork F1.

**Outcome (2026-07-06).** Shipped as `io.github.mbackschat.a12.dm.interpreter.load.ModelLoader` (`commonMain`, green on JVM + JS). Port is behavior-faithful: the new `:cli` `ModelLoaderParityTest` asserts `ModelLoader.load(json) == InterpreterModelLoader.load(json)` over **all 37 fixture models** across both roots (`cli/src/test/resources/models` + `examples/models`) — verified green *before* the switch, then kept as the delegation guard (runs in `docGuardTest` with both model roots as declared inputs). Per fork F1: kotlinx-serialization-json **tree API only** (`Json.parseToJsonElement` + a hand-written `JsonElement`→`ModelDef` mapper, no `@Serializable`/plugin), and the purity lint gained a **commonMain allowlist** (Kotlin stdlib + own packages + declared third parties) so `kotlinx.serialization.` is an explicit, reviewed boundary — any future common third-party import now fails the build until declared. Raw number text is preserved (`100.50` stays `"100.50"`), closing a latent Jackson `Double` round-trip gap no fixture exercised. Graduated: [API-GUIDE](API-GUIDE.md) §5e (loader teaser), [API-FINDINGS](API-FINDINGS.md) finding 57, [INTERPRETER-SPEC](INTERPRETER-SPEC.md) §6/§7 (standalone boundary gains the loader), [INTERPRETER-ARCHITECTURE](INTERPRETER-ARCHITECTURE.md) §2 (`.load` package). **IG36 (packed item) — TimeType half shipped** (a separate commit, INTERPRETER-FINDINGS IF56: the loader now carries `TimeType.format`; a kernel differential is impossible because codegen rejects non-default time formats, so it's unit-locked); the **`DateFragmentType` half stays open** in [INTERPRETER-GAPS](INTERPRETER-GAPS.md) — a focused eval effort (partial-date precision + comparability/gating laws), not a loader tweak.

### 6.3 WI-3a/b — the corpus — `Model: 3a BEST_MODEL · 3b REGULAR_MODEL` — **3a + 3b DONE 2026-07-06**

WI-3a fixes the published **contract** (§7): the case format, the canonicalization rules, the runner spec, the corpus-spec draft doc. WI-3b implements capture (promote `Place` to a public serializable type; pin fuzz seeds; a capture harness in `:adapter`) and JVM replay for interpreter + codegen-Java, with the build-discipline guards (§7 guards). Migrating the ~105 curated DiffTest scenarios into cases is **drip work, not a gate** — per-family, alongside other touches; corpus value comes first from fuzz-pinned + facet-generated cases.

**WI-3a outcome (2026-07-06).** The contract is [CONFORMANCE-CORPUS-SPEC](CONFORMANCE-CORPUS-SPEC.md): the layout (`corpus/cases/<family>/<id>.json`, own-domain lane), the case document, placement semantics (a serialized `PlacementSink`, parent-before-child, `EMPTY` = an instantiated-empty cell), the shared `formatPointer` pointer form including **phantom rows** (IF54), `code|type|pointer` signatures under sorted-multiset equality, and compute outcomes with **per-row pointers and exact stored-form values** — deliberately superseding the differential's regex index-strip, which obliges WI-3b to expose repetition indices on the interpreter's `Computed` (internally present as `ComputedInstance`) and to normalize kernel compute paths. Also fixed there: the one-instant clock with the modern-`DocumentV2` pin, the `Locale.US`/stored-English pins, per-strategy `divergences` as full-replacement overrides against the groovy-dynamic anchor, PASS/FAIL/SKIP verdict rules (a stored-condition parse failure is a FAIL — parser conformance is in scope), and the reference-implementation obligations WI-3b builds against (tri-verification at capture, the declared-inputs task, the case schema + id guard, the KERNEL-UPGRADE guardian registration).

**WI-3b outcome (2026-07-06).** Shipped, obligations met. **Per-row compute identity:** the interpreter's `Computed` carries the target cell's repetition indices (folding the internal `ComputedInstance` in), and the compute differential now compares the shared `formatPointer` form both sides — the index-strip is gone (`FuzzDiagnostics` + `ComputePerRowIdentityDiffTest`; no divergence surfaced). **Serializable case model:** `Placement` is promoted public, and `ConformanceCase`/`CaseJson` live in `:interpreter` commonMain (kotlinx tree API per F1, kernel-free, round-tripped on JVM + JS) so WI-6 shares them. **Capture:** `CorpusCapture` + `CorpusEngines` render a case's `(model, placements, op, clock?)` through all three JVM engines on the **clock-pinned modern `DocumentV2` + `DocumentProcessingConfig` path** (`currentDateForTest`) and tri-verify — anchor = groovy-dynamic, java-static split → a `divergences` entry, interp split → **blocks** (the WI-T handoff). **Replay:** `CorpusReplay`/`CorpusReplayTest` replays `corpus/cases/**` three ways with PASS/FAIL/SKIP (§12), in a dedicated `corpusReplayTest` task with the corpus files as declared inputs; the groovy leg doubles as the kernel-freshness guard. **Initial set:** seven own-domain (e-commerce) cases — `validateFull` (firing + empty-expected), a phantom-row `validatePart` (IF54), a per-row `compute`, a `Today` clock case, and two fuzz-seed cases — authored through the typed rulekit surface. **Guards/docs:** `corpus/case.schema.json` + `CorpusSchemaGuardTest` (id==stem, unique, `partial` ⇔ `validatePart`), `corpus/README.md`, the KERNEL-UPGRADE coupling-map row, and the TESTING-SPEC tier row — same commits. Migrating the ~105 DiffTest scenarios remains drip work (two fuzz exemplars seeded).

### 6.4 WI-4 — property pack — `Model: REGULAR_MODEL` (statement questions escalate per §5)

Kernel-free ∀-properties in `interpreter/commonTest` (runs JVM **and** JS), hand-rolled seeded style consistent with `SeedGenerator`; needs only `private` → `internal` seams. The pack (§8) is specified here so implementation is mechanical; any property whose *statement* proves contentious mid-item is recorded and escalated to a BEST_MODEL boundary rather than decided in-session.

### 6.5 WI-5a/b/c — facets — `Model: 5a BEST_MODEL · 5b REGULAR_MODEL · 5c BEST_MODEL`

WI-5a is a **vertical slice**: the `emptyOperand` dimension end-to-end — schema, population for its operators, the N7-style verification lane (every facet claim generates a kernel-executed probe), the lint rework (`RK_UNGUARDED_NUMBER_COMPARISON` becomes facet-driven and complete), the operand-aware fix selection **in `SpanResolver`** (per S6: the read pass already holds the typed `Condition` AST with `FieldRef` kinds and typed literals; the kernel exposes no span/operand, and none is needed), and serving through `operators` (including fixing the dropped `gotcha.kind`). Only after the slice survives its consumers does WI-5b populate the remaining dimensions (§9) across all 109 operators, wire the eval judge, regenerate demos, and **re-capture native-image metadata** (an `operators.json` change is a re-capture trigger). WI-5c migrates the skill's per-operator facts to facets under the full skill-edit discipline (re-read whole file, verify every claim through the binary) and re-runs the N6 eval via the `run-dmtool-eval` skill.

### 6.6 WI-6 — JS replay — `Model: REGULAR_MODEL` — **DONE 2026-07-06**

An `expect/actual` FS shim for Node `jsTest`, corpus JSON parsing in common code (rides WI-2's serialization choice), and the replay suite on the JS target — the first kernel-grounded oracle JS has. Also the prerequisite for ever adding kernel-JS-codegen comparison (deferred, WI-1).

**WI-6 outcome (2026-07-06).** Shipped, acceptance met. The interpreter-leg replay + signature rendering was extracted into a shared, kernel-free, multiplatform `conformance.InterpreterReplay` (commonMain) — the JVM three-engine runner's interpreter leg (`:adapter` `CorpusEngines`/`CorpusReplay`) now delegates to it, so JVM capture, JVM replay, and JS replay render **byte-identical** signatures (a JS-vs-JVM split then shows as a corpus FAIL, the bug class this item exists to catch). The **FS shim** is `expect fun readCorpusFiles()` in `commonTest` with a `jvmTest` actual (`java.nio`) and a `jsTest` actual (Node `fs` via typed externals); the corpus is located from the **`CORPUS_DIR` env var** Gradle sets on `jvmTest` + `jsNodeTest` (the robust path, not a `process.cwd()` guess), with those tasks declaring the corpus files as inputs. `CorpusReplayCommonTest` runs under `:interpreter:check` on **both** targets and is green — **0 FAIL / 0 SKIP** across the seven committed cases; a negative-control corruption was confirmed to FAIL `jsNodeTest`. No JS-vs-JVM eval-semantics split surfaced (nothing to hand to WI-T). Contract: [CONFORMANCE-CORPUS-SPEC](CONFORMANCE-CORPUS-SPEC.md) §12.

### 6.7 WI-7 — opt-in real-corpus sweeps — `Model: REGULAR_MODEL` (findings → WI-T)

For each model in `../a12-kernel-mm` (15 metamodels, 547 kernel-team-authored rules) and the A12 Tools workspaces: kernel `expand()` → loader → `SeedGenerator` instances → tri-engine comparison. **Prerequisites from S2** (why this item grew to 2–3 sessions): kernel pre-expansion must also resolve **`TypeDefType`** references into effective kinds/`enumValues`/constraints (258 fields — 67% of kernel-mm — otherwise seed as illegal strings and suppress the very rules under test); constraint-aware `ValidValues` (number bounds SMALL, string lengths TRIVIAL, pattern satisfaction MEDIUM — or a leave-empty fallback); and `DateFragmentType` eval support (WI-9; the `TimeType.format` half of IG36 shipped with WI-2). Opt-in and env-guarded (the `KernelMmIntegrationTest` pattern — skips when the sibling/volume is absent); **nothing persisted** from either corpus (EUPL / proprietary). Unsupported constructs → a skip-and-report triage list (itself valuable: interpreter coverage measured against *real* usage). Custom conditions skip by class (no SPI impls). A divergence found live is minimized and **re-expressed as an own-domain committed corpus case** — the real corpus discovers, the committed corpus records (the standing copy-the-mechanism rule, extended from parsing to evaluation).

### 6.8 WI-8a/b — consolidation — `Model: 8a REGULAR_MODEL · 8b BEST_MODEL`

**WI-8a** — the naming fix (§10): drop `-LEDGER` from the gap-tracker family so the suffix uniquely means "complete CI-locked classification". Pure markdown rename + reference grep-sweep (no code couples to the filenames; doc-to-doc links have no resolve-guard, so the sweep is deliberate).
**WI-8b** — (1) a **re-homing verify-sweep**: confirm every kernel truth discovered via the differential has its KERNEL-FINDINGS home (the routing held for IF53/IF54 — this sweep verifies the rest rather than assuming a backlog); IF entries keep only interpreter treatment. (2) The **semantics navigation map**: one page keyed by the KERNEL-SEMANTICS 14-section taxonomy — per section: the canonical prose (`../a12-kernel-demo/KERNEL-SEMANTICS.md` §n), our supplements (KERNEL-FINDINGS §n), facets, corpus families, locking tests — with a resolve-guard test (the `CommandReferencesResolveTest` pattern) so it cannot rot. The corpus/facet meta reuse the same §-taxonomy as tags, which is what makes the map derivable-and-checkable rather than hand-curated prose.

### 6.9 WI-9 — `DateFragmentType` eval support — `Model: REGULAR_MODEL` (added 2026-07-07)

Closes IG36's open half **before WI-7 sweeps the real corpora** (the A12 Tools `basic` workspace has 2 fragment fields; unfixed, the loader flattens them to STRING and seeding turns every rule touching them into noise). Deliberately **not** a loader tweak: a fragment's `formatOfFragment` can be month-/year-only — beyond `DatePrecision{FULL, DAY_OPTIONAL}` — and carries the comparability partition (`MVK_INVALID_COMPARE_TO_DATE`) and `Add*` granularity gating (`MVK_WRONG_DATE_FORMAT_FOR_OP`), so a `Kind.DATE`+format carry would mis-evaluate. The fix: a fragment precision model, the `ModelLoader` case (kind + `formatOfFragment` carried), fragment value parsing + comparison/`Add*` semantics on the legal inputs, kernel-differential-locked (both strategies). Bounded because the `MVK_*` codes are authoring-time gates — runtime parity is only needed on combinations a *valid* model can contain. The [INTERPRETER-GAPS](INTERPRETER-GAPS.md) IG36 row is the detailed spec; it graduates to an IF finding when this ships.

**The contract lives in [CONFORMANCE-CORPUS-SPEC.md](CONFORMANCE-CORPUS-SPEC.md)** (WI-3a, 2026-07-06) — case format, placement/pointer semantics, signature and compute canonicalization, clock/locale pins, divergence records, runner verdicts, and the reference-implementation obligations. This section keeps only the design intent the spec rests on:

- **Keying on DM-JSON is deliberate**: it is the production input any real engine must parse, and the embedded stored-English conditions make the corpus a **parser conformance suite for free** (which is why S1 gated WI-3). `validatePart` cases are included and tagged (fork F3, resolved): partial-only semantics — IF54's phantom-row firing — exist nowhere else, so the pointer contract represents non-existent rows.
- **The two-lane license structure**: the committed portable corpus (own-domain models + captured expectations; MIT-clean, the future third-party acceptance suite) vs the opt-in live sweeps of WI-7 (real corpora evaluated in place, nothing persisted; findings re-expressed own-domain into lane 1).
- **The replay matrix**: kernel Groovy-dynamic = the capture source (`RuntimeLaws`); kernel static-JAVA = `JavaKernelEngine` (WI-1); static-GROOVY = optional follow-on; interpreter JVM = loader (WI-2) + replay (WI-3b); interpreter JS = replay (WI-6, `CorpusReplayCommonTest` on `jsNodeTest`); kernel JS codegen / TS runtime = deferred until a JS host harness exists; a third-party engine = the JSON files + the runner contract.

## 8. Property pack (WI-4) — **DONE 2026-07-06**

Kernel-free, ∀-quantified, sampled via seeded models; each also runs on JS. Initial set:

1. **Lazy ≡ eager polarity** — `evalTypedLazy == evalTyped` on every sampled (model, doc); ~~currently a trusted KDoc induction~~ **now a swept law** (`LazyEagerPolarityPropertyTest` via the `Interpreter.lazyEagerConsistent` seam).
2. **Kleene monotonicity / suppression soundness** — routing any operand to UNKNOWN never flips TRUE↔FALSE; branch-scoped suppression follows (`KleeneMonotonicityPropertyTest`: the exhaustive `Kleene` table + the `invalid` mechanism over sampled docs). **Scoped:** the counting quantifiers (`NoFieldFilled`/`NotAllFieldsFilled`) are excluded — there "operand UNKNOWN" is a *tally* change (an operand dropped from a count), not a 3VL leaf going UNKNOWN, so Kleene monotonicity is not the applicable law (they are still swept by property 1). Not contentious — a scoping of the mechanism, recorded in-test.
3. **Row-permutation equivariance** — permuting repetition rows permutes pointers; the code multiset is invariant (`RowPermutationPropertyTest`: single-level + nested-top-level relabels, blanked-signature multiset invariant).
4. **Empty-row append laws** — stated against the settled IF53/IF54 semantics (`EmptyRowAppendPropertyTest`): appending an instantiated-empty row is *content* (full validation fires per row, IF53), `NumberOfFilledGroups` tracks the instantiated count (IF46), and a partial-relevant phantom row fires (IF54). The **declared-range clamp** side (`AllFieldsFilled` over the full declared range) is **IF49**, locked by its own `:adapter` differential — not restated here (not a property statement question; the settled deltas are covered, the clamp is elsewhere).
5. **Aggregate identities** — all-empty `Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues` = 0; operand-list `Min`/`Max` empty-substitution vs starred skip; distinct-count typed & scale-insensitive (`AggregateIdentityPropertyTest`: sampled `Aggregates` folds + engine-level empty handling).
6. **Hash-join ≡ nested-loop** — the `$`-correlation fast path equals the naive tally on the same input (`HashJoinPropertyTest` via the `Interpreter.correlationTallyBothWays` seam over duplicate-key samples).
7. **Determinism** — `validateFull`/`compute` twice ≡ once, reused *and* fresh engine (`DeterminismPropertyTest`).

Implementation notes: hand-rolled `kotlin.test` (no property framework, no new dependency — the module is purity-linted), a deterministic seed stream × diverse models (`PropertySupport`), and exactly **three `private`→`internal` seams** on the engine (annotated "WI-4 property seam"). Graduated to [TESTING-SPEC](TESTING-SPEC.md) ("The property pack — the third kind"). No property statement proved contentious enough to escalate.

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

**The `-GAPS` rename — DONE 2026-07-06 (WI-8a).** `-LEDGER` previously named two opposite instruments; after the rename the suffix is readable off the filename: `*-GAPS.md` = open work only, rows deleted on ship; `*-LEDGER.md` = complete CI-locked classification of an external constant, rows kept forever. The five gap-trackers were renamed (`FEATURE-GAPS-LEDGER` → `FEATURE-GAPS`, `DEV-API-GAPS-LEDGER` → `DEV-API-GAPS`, `INTERPRETER-GAPS-LEDGER` → `INTERPRETER-GAPS`, `SCHEMAKIT-GAPS-LEDGER` → `SCHEMAKIT-GAPS`, `MODEL-REVIEW-LEDGER` → `MODEL-REVIEW-GAPS`); every reference (docs + code-comment pointers) was swept; the `MVK`/`RT-SEMANTICS`/`GRAMMAR` ledgers are unchanged (the `-LEDGER` suffix is now uniquely theirs). CLAUDE.md's lifecycle section was collapsed so the suffix self-describes (no more "two opposite `-LEDGER` instruments"). WI-8b (below) is still open. When the corpus program adds its per-operator **case-coverage classification** (today half-embedded in `fuzzPending`), that is a true `-LEDGER`; the program's open build items live in INTERPRETER-GAPS.

## 11. Open forks

| # | Fork | State |
|---|---|---|
| F1 | Loader serialization | **resolved (owner, 2026-07-06): kotlinx-serialization-json, tree API only** — `Json.parseToJsonElement` + a hand-written mapper; runtime library only (no compiler plugin, no `@Serializable`); the purity lint whitelists the import explicitly so the dependency stays a deliberate boundary. Rejected: hand-rolled JSON reader (reuse-first rule) |
| F2 | Corpus publication | in-repo directory now; **publish when an external consumer materializes** (re-open trigger), after LICENSING §19 re-confirm |
| F3 | `validatePart` cases | **resolved: include, tagged `partial`** — IF54's phantom-row semantics are otherwise uncapturable |
| F4 | Parser strategy | **resolved + shipped 2026-07-06 (WI-P → IF55): completed the hand-written parser** — the gap was small and enumerable (both corpora now parse 100%), the EUPL ANTLR-from-`.g4` question is moot, and the hand-written parser is the `model eval` path |

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
- ~~S1 is the estimate swing~~ **retired 2026-07-06**: the sweep ran — the parser gap is bounded and enumerable (WI-P, 1–2 sessions); WI-3a is unblocked.
- Native-image re-capture (WI-5b) and the corpus-task Gradle-input discipline (§7) are known traps carried as explicit checklist items, not risks.
