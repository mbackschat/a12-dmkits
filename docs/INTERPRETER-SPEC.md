# Interpreter spec — a native-safe, cross-platform runtime evaluator

**Status: BUILT (2026-06-24), with live exceptions tracked.** The in-repo `:interpreter` is dmtool's kernel-free runtime evaluator: the native image runs [`examples/cli-runtime.md`](../examples/cli-runtime.md), and the local Kotlin/JVM + Kotlin/JS API implements full/partial validation, computations, and single-rule/single-computation evaluation. It is operator-inventory-complete—all 110 catalog constructs are handled—and the differential estate is at zero divergences on the surfaces and corpora it exercises. That is strong evidence, not proof over every legal composition, configuration value, platform dialect, assembly form, or API lifecycle. [INTERPRETER-GAPS.md](INTERPRETER-GAPS.md) is the exhaustive live-status ledger; this document owns the durable design, contract, and boundaries, [INTERPRETER-ARCHITECTURE.md](INTERPRETER-ARCHITECTURE.md) owns the implementation, [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) the verified rationale, and [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md) the non-functional method and evidence.

**One line:** an isolated runtime evaluator for conditions + computations that **interprets** the rule AST directly—instead of generating and executing code on the fly—so one common implementation runs in the JVM/GraalVM-native path and compiles to JavaScript. Node is the currently verified JS runtime; browser packaging/verification and commonMain raw-workspace preparation are open release requirements (IG86/IG68), not yet support claims.

**The committed goal — a full-blown interpreter serving two consumers:**

1. **dmtool's native image** — drive the runtime verbs (`model eval`, `model compute`, `rule eval`) without Groovy/codegen. **Acceptance:** the [`examples/cli-runtime.md`](../examples/cli-runtime.md) showboat demo — which exercises exactly those verbs — runs **end-to-end on the native image**. This is satisfied: the interpreter is now the sole eval engine and the former JVM-only `--kernel` eval option is retired (§8a).
2. **A standalone Kotlin/JVM + Kotlin/JS library** — for any consumer running **computations and validations — both full *and* partial** — over a document model, with no kernel runtime dependency (§7). The API must also evaluate a **single rule or a single computation** against a document (the `rule eval` shape — FIRED / PASSED / SUPPRESSED, and "what does this one computation yield"), not only whole-document validation — this is what dmtool's native-image `rule eval` verb consumes (§1).

Correctness is the user's criterion throughout: **the interpreter is correct iff it agrees with the real kernel**, proven by the differential oracle (§9).

## Non-functional contract — low startup, high performance, low relative footprint

Low startup, high warm performance, and low **relative** footprint are required product qualities, not descriptive aspirations. Exactness remains non-negotiable. Low first-answer latency and warm throughput are separate, co-equal regimes and neither may be “won” by hiding work outside its clock. Indices, hash maps, parsed-AST caches, row-context memos, and other bounded caches are allowed and **advised** when they prevent repeated work or improve performance; footprint instead constrains dependency accretion, cache lifetime, asymptotic space growth, and unexplained regressions relative to the previous release and an equivalent kernel-backed evaluator.

The exact numbers below were introduced by `e594f594` without an earlier owner decision or protocol-equivalent baseline, so they are **informative candidate budgets, not ratified release acceptance bars** — the posture the owner ratified for them (IF137, 2026-07-19). **The only ratified NFR/release-level catastrophe floor today is `PerfBounds`** (the sub-quadratic growth guards + the reduced against-kernel correctness canaries) — a regression backstop, **not** NFR certification. Other executable implementation-regression assertions remain in the test estate; they are diagnostic/catastrophe guards, not certified NFR bars. No candidate NFR-S/P/F number gates, including the fair generated-Java `≤1.25×` ratio (a portable warm-performance signal, informative, not an executable gate). **A number becomes a release bar only per-number, and only after all six hold:** (1) its scenario and estimator are fixed; (2) the runner/runtime is pinned; (3) the required p95/median sampling is performed; (4) semantic preflight passes — `unsupported.isEmpty()` + tri-engine agreement with the ratified Groovy-dynamic anchor (IF136); (5) the measurement is reproducible; (6) the owner ratifies it as a release bar. The **ratified estimator** is **interleaved N/4N samples, median of within-pair ratios**; the recommended sampling defaults (≥50 fresh processes for a p95, ≥5 forks for footprint, and the job/miss policy) bind a number only when it is promoted, not globally. IF156–IF159 own prepared eligibility, estimator, identity/inventory, and comparator guards; IF172 supplies the ordinary JVM/Node/native/fair-Java executable performance lane; IF173 supplies the same-collector delivery and previous-release comparison lane. **Owner policy settled 2026-07-21:** promote no candidate number now; retain the isolated `scripts/perf.sh gate` path as the nightly/manual catastrophe and regression lane, and keep the complete IF172 collector manual for release or performance-relevant evidence. Method + dated evidence: [INTERPRETER-PERFORMANCE.md](INTERPRETER-PERFORMANCE.md).

| ID | Requirement | Candidate bar — informative until per-number certified (IF137) |
|---|---|---|
| **NFR-S1 · JVM library first answer** | A fresh engine returns the first complete result quickly from an already-expanded in-memory `ModelDef` + materialized `Document`. | Clock `DmInterpreter(model)` through complete result materialization in at least 50 independent fresh JVM processes, discard nothing, and use nearest-rank p95. Candidate ceilings are **≤200 ms** at 48 rules / ~500 rows, **≤400 ms** at 192 rules / ~500 rows, and **≤1,500 ms** at 1 rule / 100,000 rows; candidate design targets are **≤150/300/1,000 ms**. The versioned model, operation, value/firing regime, result cardinality, locale, and zone are bound per-number at any future promotion through IF172's executable lane. A candidate 4× row/rule growth ceiling is **≤6×** under the ratified estimator (IF137: interleaved N/4N, median of within-pair ratios). |
| **NFR-S2 · Node + native first answer** | The Node-hosted JS library and native CLI stay interactive on first use. Node evidence is not browser evidence; IG86 adds a separately scoped real-browser first-answer bar before browser support ships. | Candidate Node case: 1 rule / 10,000 rows, p95 **≤350 ms**, design target **≤250 ms**, under the NFR-S1 fresh-process protocol. Candidate native case: `dmtool model eval`, 48 rules / ~500 rows, process spawn through complete envelope + exit over at least 50 runs, p95 **≤750 ms**, design target **≤500 ms**. IF172 binds both to versioned scenario identities for any future promotion. |
| **NFR-P1 · warm absolute + scaling** | The production `DmInterpreter` + real `Document` path remains high-throughput on group-heavy workloads. | On the pinned reference runner, warm B1 at 100,000 rows has **median ≤1,000 ms**. Under the ratified paired estimator (IF137), every benchmark axis declared linear or `N log N` has a candidate **≤6×** growth ceiling when its input grows 4×. Node B1/B2 at 10,000 rows have **median ≤1,000 ms** and the same candidate growth ceiling from 2,500 rows. An inherently pairwise operation may exceed it only when explicitly documented and no worse than the kernel's complexity class. |
| **NFR-P2 · kernel competitiveness** | The interpreter stays on-par with the fair generated-Java kernel baseline, not merely the slower Groovy runtime. | Every B-series catalog scenario the generated-Java harness supports is included by default; an exemption requires a documented semantic or harness reason. Cover both firing regimes for validation, representative VALUE/CLEARED/ERRORED computation regimes, and concentrated/distributed rule-count sweeps. Candidate same-run `interpreter median / kernel-Java median` is **≤1.25** (informative, not an executable gate — owner, IF137); the design target is **≤1.0**. Kernel codegen, model loading, and document construction stay outside warm timing. Before a timing is eligible, the outcome-bearing interpreter API must report zero unsupported and interpreter, dynamic kernel, and generated-Java results must agree with the ratified Groovy-dynamic anchor (IF136); comparing messages from lossy `validateFull` alone is insufficient. |
| **NFR-F1 · dependency + delivery footprint** | Equivalent evaluation capability remains materially smaller than the kernel/Groovy stack, without blocking useful caches. | No runtime dependency on kernel, Groovy, dmtool, `:adapter`, or the other kits. The resolved non-JDK interpreter runtime closure stays **≤50%** of the equivalent kernel-backed evaluation closure. A new runtime dependency or **>10%** packed-artifact/runtime-closure growth against the previous release requires an explicit reviewed baseline update and the correctness/performance benefit that pays for it. The native CLI hard ceiling is **≤40%** of the equivalent trimmed JVM app image; the design target is **≤⅓**. |
| **NFR-F2 · bounded runtime footprint** | Runtime state grows with the model/document it accelerates and reaches a plateau; it does not leak or duplicate document-wide state per rule/row. | Caches are owned by an engine, `Document`/index, validation call, or row and are invalidated with that owner—never global/unbounded. Repeating the same prepared-model/document evaluation reaches a retained-memory plateau after caches warm. Across incremental retained heap and allocation volume at `N, 2N, 4N, 8N`, at least five forked runs have a **median fitted space exponent ≤1.25** and no individual 4× step exceeds **8×**; instrumentable logical cache-entry counts grow **≤4×** for 4× rows. A **>20%** same-scenario regression requires review and may be accepted when a measured correctness or NFR-S/NFR-P win justifies it; there is intentionally **no arbitrary absolute heap cap**. |

| IDs | Evidence status | Basis |
|---|---|---|
| **NFR-P1/P2** | **Plausibility-backed candidates; no protocol-compliant acceptance evidence.** | Recorded warm B1 is ~250 ms at 100,000 rows, available Node B1/B2 results are ~61–140 ms at 10,000 rows, and the worst recorded fair kernel-Java ratio is 0.64. The cited 2.71–4.33× growth comes from best-minimum catastrophe guards, not the paired-median estimator above; the runs are not a pinned, versioned certification series. |
| **NFR-F1 native delivery** | **Executable informative comparison; no protocol-compliant certifying run yet.** | IF173 builds and executes native and Groovy-excluded trimmed-JVM comparators from each clean revision, records exact bytes/SHA identities, and applies the same collector to the current and previous release. The historical 33 MB / 119 MB pair remains non-certifying; no IF173 run promotes the candidate ≤40%/≤⅓ ratios. |
| **NFR-S1/S2** | **Provisional calibration budgets, not yet release-certified.** | Historical validate-only samples were 62/126/495 ms for the three JVM shapes, available Node results are ~61–140 ms at 10,000 rows, and native command startup was ~90 ms. The initial ceilings provide substantial headroom and the tighter values remain design targets, but no old run used the required fresh-process p95 clock and the native number did not include the full eval pipeline. |
| **NFR-F1 dependency/artifact closure / NFR-F2 runtime state** | **Dependency hard-zero and cache-owner lifetimes structurally locked; informative artifact and forked-runtime evidence lanes available; relative numbers not release-certified.** | IF160 reconciles every JVM and JS/Node production runtime file, recursively accounts production npm dependency/optional/peer declarations, and executes a fresh kernel validation consumer before reporting its derived comparison graph; IF161 locks exact same-owner logical-count plateau and discarded-owner release for the real document, parsed-model, and overlay cache owners. IF162 adds independently forked JVM/Node retained-heap and allocation evidence with semantic/cache/owner validity requirements and structured median/curve aggregation. IF173 adds same-collector current/previous bases for the JVM jar/runtime ZIP, JS KLIB, native CLI, and native-equivalent trimmed JVM app. A production npm base is deliberately absent until IG85 ships a real exported API, `.d.ts`, and consumer smoke. These are structural and informative facts, not promotion of candidate ratios. |

“Candidate” does not mean green. The product decision is made (IF137, 2026-07-19): the numbers are **informative until per-number certified**, `PerfBounds` is the only ratified NFR/release-level floor, the paired growth estimator is **interleaved N/4N with the median of within-pair ratios**, and every tighter design target is informative (not gating). Other executable implementation guards remain regression diagnostics, not promoted NFR bars. The proposed sample/fork/job and two-job-miss policy are recommended defaults that bind a number only at promotion. IF172 runs the performance protocol on an explicit runner; after calibration, every budget change is versioned here — a historical minimum, one noisy job, or an ad-hoc test constant cannot loosen it.

## 1. The problem

The kernel evaluates rules by **generating source code per model and executing it** (`ValidationCodeGenerator` emits one of six targets — `JAVA, GROOVY, JAVA_SCRIPT_VK, JAVA_SCRIPT_REDUCED, CPLUS, VBA`). On the JVM, dmtool's runtime path takes the Groovy target and compiles it at runtime in **one** place: `kernel-tool/kernel-core-ontheflytesting/.../OnTheFlyTestingValidatorGenerator.generateValidator()`, which calls `GroovyShell.parse(generatedCode)`.

That single step—compiling generated source to JVM bytecode at runtime—is what a GraalVM native image forbids. It was the "Groovy ceiling" recorded in [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md), and before the interpreter it barred exactly three dmtool verbs (then listed as `JVM_ONLY_VERBS` in `Cli.java`):

| verb | `KernelAdapter` entry | what it needs |
|---|---|---|
| `model eval` | `evaluate(…) → rt().validateFull` | run a model's rules over a document |
| `model compute` | `compute(…) → rt().compute` | run a model's computations |
| `rule eval` | `testRule(…)` (wraps `evaluate`) | fire one rule, report FIRED/PASSED/SUPPRESSED |

Everything else dmtool did—structural `checkConsistency`, the bulk of the verbs—was already native-safe. The interpreter closed that native runtime gap; its JS output is proven on Node, while a real browser consumer is now active IG86 release work and remains unclaimed until qualified.

## 2. Decision — interpret, don't generate-and-eval

Codegen is not wrong; it optimizes a case dmtool isn't and taxes a case dmtool is. It buys (a) **steady-state throughput** — generated code JITs and runs hot, which wins when a *fixed* model is validated against millions of documents; (b) **code export as a feature** — the C++/VBA/JS targets ship a validator into a foreign host; (c) one template set → many targets.

dmtool benefits from none of these. It loads a model at runtime, validates a document or two, and exits, so it rarely reaches the long steady state that repays runtime Groovy compilation, and it exports nothing. A **tree-walking interpreter** is native-image-clean by construction (static code walking data — no `GroovyShell`, no runtime bytecode generation, no embedded JS VM), avoids generated-source compilation in its first-answer path, and can be cross-platform from a single source. Its actual first-answer latency is a measured NFR question, not “zero” by architecture.

The line that matters for native-image and for "no on-the-fly codegen": **the interpreter may generate an internal *data structure* (its own AST/IR); it must never generate host-language *source* that gets compiled or `eval`'d.**

> Footnote — when codegen *would* win: a server with a **fixed** model catalog and a real throughput need should AOT-generate Java validators at *build* time (the kernel's `JAVA` target), which is throughput-optimal and also native-safe. That's a different product than dmtool; this proposal is for arbitrary-model-at-runtime consumers such as dmtool and standalone JS/JVM libraries. A browser consumer remains unverified while IG86 is open.

## 3. The honest tradeoffs

An interpreter is slower per operation and puts semantic fidelity on us. Both costs are unusually cheap here:

| You give up | Why it barely hurts |
|---|---|
| **Per-operator JIT dispatch speed** | The repetition/scale optimizations live in the kernel's **runtime library, not the generated code** — `EbenenIterator` (the `STERN_OPTIMIERUNGS_SCHWELLE = 100` `*`-iteration prune), and the `ValidationCache`/`GroupFillCache`/`IndexFieldCache` family. They are *algorithmic* and interpreter-agnostic; we reimplement the same strategies. Only a **constant factor** of node dispatch is lost, not the algorithmic wins. |
| **Byte-identical operator semantics for free** | We now implement operators — but against an **almost-complete executable spec we already mined**: ~70 [`KERNEL-FINDINGS`](KERNEL-FINDINGS.md), 109 N7-verified operators in the catalog, 300 classified [`MVK-LEDGER`](MVK-LEDGER.md) codes, all 28 runtime-core classes drained in [`RT-SEMANTICS-LEDGER`](RT-SEMANTICS-LEDGER.md), and 119 classified [`GRAMMAR-LEDGER`](GRAMMAR-LEDGER.md) productions. Implementing to a known spec, not reverse-engineering in the dark. |
| **Byte-identical localized message text** | The fiddliest part (4-locale, German-keyed resource bundles, param substitution). It's a *scoping decision* (see §11), not a blocker. |

## 4. Two interpreter flavors

"Interpreter" spans a spectrum; the architectural boundary (data, not host source) holds across all of it:

- **Tree-walking interpreter** — walk the AST, evaluate node by node. Simplest, most portable, slowest dispatch. **Start here** (get the laws green).
- **Compile-to-internal-IR VM** — per model, lower the AST to a compact internal instruction list (still just data → native-safe, no eval), then interpret that in a tight loop. Better cache locality, no per-node virtual dispatch. The **escape hatch** for throughput on huge nested-repetition documents — adopt only if measurement demands it.

(A Truffle self-optimizing interpreter would recover near-codegen speed but ties to the GraalVM optimizing runtime and complicates the browser; out of scope unless throughput forces it.)

## 5. Language — Kotlin Multiplatform

Once it's an interpreter, **Kotlin Multiplatform (KMP)** is the strongest fit: one interpreter codebase, both targets, and it collapses the kernel's *two hand-maintained* runtimes (`kernel-core-runtime` Java + `kernel-core-runtime-ts` TypeScript — file-by-file ports, no transpiler) into **one common engine + thin shims**.

```
                ONE interpreter  (Kotlin common code: AST, operators, polarity,
                                   empty/null logic, iteration, caching)
                ├── Kotlin/JVM  → static bytecode → native-image-clean, NO GraalJS,
                │                  native kernel-Java interop for the test oracle
                └── Kotlin/JS   → plain JS → Node-tested; browser qualification active (IG86), NO Wasm boundary

   platform-specific (expect/actual) — exactly the line the kernel already draws:
        decimals :  java.math.BigDecimal      ⟷   big.js
        dates    :  java.time                 ⟷   luxon
```

- **Why not TypeScript:** TS is native in the browser but would force a **GraalJS embed** on the JVM (the very JS VM an interpreter exists to avoid). Kotlin/JVM is static — it just runs in the native image.
- **Why not "host the kernel's JS on GraalJS":** viable and zero-reimplementation, but it ships a JS VM (size, perf caveats), keeps full kernel coupling, and trusts the kernel's TS runtime over its Java one. Kept as a fallback if the interpreter's semantic-fidelity cost proves too high; not the plan.
- **Why not the Wasm boundary:** a Wasm-compiled interpreter called from JS marshals the whole document across the JS↔Wasm boundary per validation — expensive for deep repetition docs. Kotlin/JS compiles to plain JS, so the document is read in place. (Confirmed concern; avoided by target choice.)

## 6. What's owned vs delegated

The interpreter **owns the runtime evaluation path** (the part that needed Groovy) and nothing else. Two things stay delegated:

1. **Structural `checkConsistency`** — already native-safe; dmtool keeps calling it for model validity. The interpreter does *not* re-implement structural validation.
2. **The parser → AST** — conditions live as *text* on disk, so something must parse them: a **hand-written recursive-descent parser** (`parse/Parser.kt`), completed against the real stored grammar in WI-P and locked to 100% over the catalog/fuzz + real corpora (§11, resolved; INTERPRETER-FINDINGS IF55).

The boundary to dmtool is a **clean data contract, not a shared object model**:

```
(condition/computation text  +  document JSON  +  locale)  →  diagnostics
```

The interpreter has its **own multiplatform AST** and does **not** import `dm.core`/`BoundModel` — which falls out of KMP anyway (common code can't see JVM-only Java types). dmtool *adapts to* the interpreter; the interpreter never depends on dmtool.

**Runtime temporal contract.** Stored and constructed Date/Time/DateTime values use the model's whole-second syntax. The injected `EvalClock.now` additionally accepts `.SSS` and retains that exact epoch-millisecond identity for temporal comparison, sub-day difference, and sub-day shift without exposing fractional authored syntax. `BaseYear` follows the kernel's context split: context-free arithmetic and Number computations read the fixed scale-0 configured year, while explicitly temporal consumers read the start of that year. The canonical semantics and oracle locks are [KERNEL-SEMANTICS.md](KERNEL-SEMANTICS.md) §6 and [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) IF206–IF207.

**The `ModelDef` is loadable, not only hand-buildable (WI-2), but only after preparation.** `:interpreter` `commonMain` ships `load.ModelLoader`, a kernel-free, multiplatform (JVM + JS) **prepared DM-JSON → `ModelDef`** loader, so a consumer holding expanded, self-contained, name-path-keyed DM-JSON need not hand-build it. It reads via kotlinx-serialization-json's tree API only (no `@Serializable`/compiler plugin—proposal §11 F1; the purity lint whitelists the dependency), preserving a number's raw literal. It does not mount includes, resolve/import typedefs, normalize opaque ids, or remap included references; an unprepared residue (an unexpanded include, an unresolved typedef, an opaque id) is **rejected loud**, not degraded (IF145). `ModelWorkspace` owns those raw-workspace transforms in commonMain. Every JVM CLI runtime consumer — including `model seed` — now collects and assembles through that public portable boundary, then enters the strict expanded-input evaluator gate; `model expand` returns the same assembler output before evaluator-only checks.

## 7. Standalone architecture = dependency hygiene; distribution is separate

"Standalone library" is a property of the artifact's dependencies and API, not of where the source sits. Four leaf rules make it standalone in either home:

1. **No dependency on `cli`/dmtool** — they consume it, never the reverse.
2. **No *runtime* dependency on the kernel** — the whole point. The kernel appears only as a **test-only** conformance oracle.
3. **A clean data contract, not a shared object model** (§6) — owns its AST and a kernel-free prepared-DM-JSON → `ModelDef` loader (`load.ModelLoader`, JVM + JS — WI-2; it rejects unprepared residue loud, IF145); the commonMain raw-workspace assembler is active IG68 release work.
4. **Eligible for independent publication** — the dependency boundary permits Maven (JVM) + npm-compatible (JS) artifacts under their own coordinates; GitHub-only publication/ABI work is active IG85 work.

Hold these from day one and extraction (§8) stays cheap.

## 8. Placement — built out in-repo; separation by convention + linting, not by a repo wall

**Decision (2026-06-23): the interpreter is built out *in this repo*, and dmtool's consumption of it evolves alongside it here.** The earlier plan ("spike in-repo, extract to a sibling `a12-dm-interpreter` before promoting") is **retired** — it solved a problem §7 already dissolves: *standalone is a property of the module's dependencies and API, not of where the source sits.* Co-locating dmtool + interpreter is the tighter loop for use-case 1 (the native-image verbs), and the differential oracle (§9) lives here too. The cross-repo tax bought only an independent release cadence we don't need yet.

```
   a12-dmkits (this repo, one build)
   ├── :interpreter  (KMP — commonMain engine, jvm/js shims)   ← publication-eligible standalone module
   │      ▲ no kernel, no dmtool — enforced by the purity lint below
   ├── :adapter (Java) ── testImplementation :interpreter       ← the kernel DIFFERENTIAL oracle lives here,
   │                                                              keeping :interpreter kernel-free even in test
   └── :cli / dmtool ──── (consumes :interpreter for the native-image runtime verbs)
```

**Strong separation is kept by *convention + linting*, not a repo boundary** — the four hygiene rules of §7 are enforced mechanically, so the coupling can't silently erode:

- A **purity lint** (`:interpreter`'s `verifyInterpreterPurity`, wired into `check`) fails the build if `commonMain` imports `java.*` / `com.mgmtp.*` / `groovy.*`, or if `jvmMain`/`jsMain` import `com.mgmtp.*`/`groovy.*` (the JVM shim may use `java.*`; the JS shim may use its externals). No kernel/dmtool type can reach the engine.
- The kernel **differential tests stay in `:adapter`** (not `:interpreter`'s test source) — so `:interpreter` carries **zero** kernel dependency even in test scope, which is exactly what keeps it cleanly publishable.
- `:interpreter` never depends on `:` (rulekit core), `:adapter`, or `:cli` — they consume it, never the reverse.

**The publication boundary is satisfied; publication itself is not.** `:interpreter` is kernel/dmtool-free and therefore eligible for Maven + npm-compatible packaging regardless of living in this repo, but its Gradle build currently has no Maven/npm publication wiring, production npm package, deployment, or KMP ABI lock. Those are active [IG85](INTERPRETER-GAPS.md) work under [INTERPRETER-RELEASE-PROPOSAL](INTERPRETER-RELEASE-PROPOSAL.md); development JS modules are not a production package or an NFR-F1 measurement.

**Extraction to a sibling repo remains a *future option*, no longer a prerequisite.** Do it **only if** the interpreter earns an independent life — a non-dmtool consumer needs its own release cadence, or it ships as standalone OSS with its own issue tracker. Because §7's hygiene is lint-enforced from day one, that extraction stays a *lift* (move the module + its tests, point dmtool at the published dep), not a rewrite — so deferring it costs nothing.

### 8a. The north star — the interpreter as the sole runtime EVAL engine; `--kernel` eval retired (decided 2026-07-11 "Scope A"; EXECUTED 2026-07-12, Set V)

**Engine switch — DONE (Set V, 2026-07-12).** The interpreter is the sole runtime engine for `model eval` / `rule eval` / `model compute`, so the **`--kernel` eval flag is retired** and evaluation is interpreter-only on native and JVM. That deployment fact is not a claim of strict equivalence on every legal input. The 2026-07-19 re-audit found additional exactness, platform, preparation, API-safety, and evidence gaps beyond the original certificate; [INTERPRETER-GAPS.md](INTERPRETER-GAPS.md) is authoritative. The CLI-level differential reaches the kernel via the `KernelAdapter` API (`KernelEval`), not a production flag (IF122).

**Scope — EVALUATION only (the distinction that must never collapse).** This covers the kernel's *evaluation* role: running rules/computations against document data. It does **not** cover the kernel's *consistency / MVK acceptance* role — `rule check`, `model check`, add-time validation (`checkConsistency` / `RuleValidator`), the F4 "the real kernel accepts this" guarantee. That role stays **kernel-backed** (the [decoupling](KERNEL-DECOUPLING-PROPOSAL.md) `ConsistencyOracle` port, R3). So **dmtool keeps bundling the kernel even after `--kernel` eval is gone** — the backend served three runtime uses (the MVK oracle, the read-back seam, and `--kernel` eval), and this goal retired only the third. Reproducing the MVK oracle clean-room so the kernel could leave the runtime *entirely* ("Scope B") is a **separate, deferred question** — out of this goal; never fold it in.

**What "drop-in" requires — the eval surface:**
- **(a) model ASSEMBLY parity** — expand includes (`modelReferences`), resolve typedef imports and effective types, normalize ids, and remap included references against kernel expansion. The dmtool runtime path and `model expand` now collect and assemble through the bounded commonMain `ModelWorkspace`; runtime alone continues through the strict expanded-input evaluator gate, so every CLI consumer (including `model seed`) crosses one enforced portable boundary (IF145). The reference-aware MOVE pass covers every rule/computation fragment, legal reference slot, quoted segment, pre-mount unique short-name binding, SINGLE_RG/MODEL_ROOT, nested depths, and legal `$`/`Having`; its dynamic-Groovy/generated-Java/interpreter lock is `IncludeAssemblyStrategyDiffTest` (IF146).
- **(b) evaluate / compute / formal-check parity** — 0 differential divergences on both prepared corpora, nice + hostile, over the exercised surface. The live ledger records legal-input dimensions outside those corpora and tests, including IG83. The reviewed IG88/IG92/IG95 chain is fully closed by IF151, IF150+IF152, and IF149; IG97/IG91/IG93/IG96 shipped as IF139/IF145/IF147/IF148, tolerance normalization and polarity completed as IF174, IG87 as IF146, IG81 mutate-after-cache reuse as IF142, and JS decimal/regex host semantics as IF163/IF164.
- **(c) messages** — interpolated + localized (EN/DE), differentially locked today. `$field.value$` reads immutable caller display text rather than the normalized evaluation cache, and a missing/empty display selects the admitted field format's exact default; `$field$` follows the configured provider → nonempty model label → indexed debug-path cascade (IF201).
- **(d) the customization/configuration surface** — the kernel's effective error-message locale, `currentDateForTest`/host-clock selection, custom conditions, custom field types, project charset, and model time zone all have explicit interpreter inputs. Kernel 30.8.1's deprecated-but-observable `additionalInformation` map is an explicit compatibility exclusion: consumers must close equivalent configuration over their supplied custom implementations; the interpreter does not silently pretend the slot is absent or add a generic context framework (see [`../interpreter/CLAUDE.md`](../interpreter/CLAUDE.md) "Customization-surface parity").
- **(e) output-shape parity** — or a documented-accepted difference (the `field` pointer is the resolved path, not the kernel's verbose pointer object).

**The removal gate—the compatibility certificate.** `--kernel` eval was retired after a documented certificate enumerated the then-known capabilities, locks, residuals, and coverage argument. The committed [INTERPRETER-DROPIN-CERTIFICATE.md](INTERPRETER-DROPIN-CERTIFICATE.md) now records that historical verdict plus the later re-audit: it is strong layered evidence on the exercised surface, not a current strict whole-domain certificate. IF140 has since completed the derived both-strategy evidence matrix; re-certification still needs the live correctness/capability gaps resolved or explicitly enforced as owner-ratified input exclusions.

**The one residual that may never reach zero-consumer-input:** custom-construct *implementations* (external logic) — the interpreter needs the impl supplied via its SPI; the certificate characterizes this boundary rather than claiming to erase it.

Record: [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md) IF122 (the retirement), IF134 (NFR history audit), and IF135 (source/certificate audit). Current work: [INTERPRETER-GAPS.md](INTERPRETER-GAPS.md). Scope B (MVK reproduction) stays a separate deferred question.

## 9. Verification — the differential oracle we already have

The acceptance criterion is the user's own: **the interpreter is correct iff it agrees with the kernel.** The harness already exists:

| tier | location | oracle | scale |
|---|---|---|---|
| runtime fires/does-not-fire | `adapter/.../laws/` | `RuntimeLaws` over `IDocumentRtService.validateFull` + `compute` | ~105 assertions, 29 classes |
| structural accept/reject | `src/.../validate/laws/` | `KernelLaws` over `RuleValidator`/`checkConsistency` | ~65 assertions, 19 classes |

The build measure: run each law's document through **both** the real kernel and the interpreter; assert identical `(code, errorField, MessageType)` triples and identical `compute()` outcomes (value / cleared / errored). A divergence is a finding — including any place the kernel's own Java and TS runtimes disagree (they are hand-maintained parallel copies). This is the proactive root-cause discipline applied to a new engine.

## 10. How it was built (spike → in-repo build-out) — DONE

The path from idea to shipped, kept as the de-risking record:

1. **The spike (2026-06-21).** A throwaway prototype interpreted four condition shapes end-to-end and ran them through `RuntimeLaws` against the real kernel — comparison + three-valued logic, the empty-as-0 type asymmetry, operand-list + starred aggregates, and per-row `*`-iteration. **All matched the kernel** (25 differential cases, JVM). It was lifted into a real Kotlin Multiplatform `:interpreter` module, and the **same `commonMain` engine ran on Kotlin/JS (Node)** — proving that the shared engine compiled and executed there, not that every host-sensitive semantic was equal. The only then-platform-specific code was the `expect/actual` decimal shim; `commonMain` had zero `java.*`/`com.mgmtp.*`/groovy imports; both the Kotlin-JVM and KMP/JS toolchains resolved in the isolated, public-repos-only Gradle home. Follow-up work added big.js, polarity typing, the `commonMain` text→AST parser, throughput, and localized message text. IF153/IF154 were the first host-semantics increments; IF163/IF164 completed the decimal and regex contracts.

2. **The in-repo build-out (per §8).** Past the spike the engine grew the public `DmInterpreter` API — `supportAudit` (value-independent prepared-model eligibility), `validateFull` (whole-document), `validatePart` (partial), `testRule`/`evalCandidate` (single-rule, FIRED/PASSED/SUPPRESSED), and `compute` (computations) — over a structured `ModelDef` + a general indexed `Document`. The derived handler inventory reached **all 110 catalog constructs** (the catalog-parity meter build-fails otherwise); IF140 now derives the capability × strategy × coverage-tier matrix, while the live gaps ledger preserves legal-input and host dimensions that remain open or explicitly excluded. The CLI wired the interpreter as the **default** engine for `model eval`/`compute`/`rule eval` (+ `model seed`), with `--kernel` the JVM-only opt-in (later retired, Set V — §8a); the native image then ran [examples/cli-runtime.md](../examples/cli-runtime.md) end-to-end (use-case-1 acceptance).

The live implementation structure is [INTERPRETER-ARCHITECTURE.md](INTERPRETER-ARCHITECTURE.md); the per-decision findings are [INTERPRETER-FINDINGS.md](INTERPRETER-FINDINGS.md).

## 11. Open decisions

Narrow, and each is measurable from the spike:

- **Throughput bar** — is there a real big-document/server scenario, or is dmtool's short-lived process the target? Decides tree-walk (§4) vs. investing early in the IR-VM.
- **Message-fidelity bar** — structural `Diagnostic` equality (code + where + severity + message-type), or **byte-identical localized prose**? The latter means reusing the kernel's message bundles + rendering.
- **Parser source** — ~~reuse the kernel parse-tree-api vs. generate an ANTLR parser from the kernel `.g4`~~ **resolved (WI-P, 2026-07-06): complete the hand-written recursive-descent parser** (`parse/Parser.kt`). The option the fork missed: neither kernel-dep. The gap turned out small and enumerable (Phase-0 S1 → the [SEMANTICS-FORMALIZATION-PROPOSAL](archived/SEMANTICS-FORMALIZATION-PROPOSAL.md) WI-P gap list), so completing the hand-written parser keeps the component **kernel-free** (native-safe, JS-compilable) and makes the EUPL-`.g4` derivative question (§13) **moot** — reading the grammar for syntax *shapes* is mechanism, not expression. Coverage is now proven, not assumed: the catalog + fuzz DSL parse 100% (always-on) and the `a12-kernel-mm` + A12 Tools workspace corpora parse 100% (opt-in), locked by `adapter.laws.ParserCorpusSweepTest`. Detail: INTERPRETER-FINDINGS IF55.

## 12. Non-goals / scope

- **Not** a replacement for structural validation — `checkConsistency` stays with the kernel (native-safe).
- **Not** a code generator, and **not** a kernel replacement — it reproduces *runtime evaluation* behavior, verified against the kernel.
- **In scope: both full *and* partial validation** (`validateFull` *and* `validatePart`) plus `compute()` — the standalone-consumer goal asks for partial, so its rule-level gates and three-value-logic suppression contract are full-kernel-parity work we take on (verified against the kernel like everything else), not deferred. Under the pinned kernel 30.8.1 contract, a rule containing any `Having` filter is skipped at method entry during partial validation before relevance, iteration, or condition evaluation; full validation evaluates the same rule normally (IF202).

## 13. Licensing

The project decision is settled in [`LICENSING.md`](LICENSING.md): a12-dmkits **source is MIT**; the kernel-bundling CLI distributable is **EUPL-1.2** (the kernel is dual `EUPL-1.2 OR commercial`). The interpreter aligns cleanly: built **clean-room from the mined behavioral spec** (the findings/laws/catalog — own-words, experiment-derived), it is MIT like the rest of the source, and — crucially — it carries **no kernel runtime dependency**, so a distributable that ships *only* the interpreter need not be EUPL. The one derivative-risk to settle: generating a parser from the kernel's EUPL `.g4` grammar (§11) would touch EUPL-licensed source. mgm-tp owns the kernel, so this is likely moot internally, but it matters if the interpreter ships as independent OSS — settle it before the Phase-2 extraction publishes.

---

**Cross-references:** [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) (the Groovy ceiling this removes) · [`RT-SEMANTICS-LEDGER.md`](RT-SEMANTICS-LEDGER.md) / [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) (the semantics to reproduce) · [`TESTING-SPEC.md`](TESTING-SPEC.md) (the law tiers / oracle) · [`PROJECT-DESIGN.md`](PROJECT-DESIGN.md) (the N8/D15 kernel boundary) · [`LICENSING.md`](LICENSING.md).
