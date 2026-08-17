# A12 Interpreter — performance and footprint

Measured evidence for the A12 Interpreter: what it costs to ship, how fast it runs, and what it costs to keep a model resident. Every figure here was executed; none is estimated or extrapolated. The method, timing boundaries and complete tables live in the [main project's performance documentation](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md), and the dated measurement record is its [results log](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE-RESULTS.md).

> **On the comparisons with the A12 Kernel.** They exist because a clean-room reimplementation has to prove it does the same work, and the only meaningful yardstick is the engine it reproduces. They are bounded same-machine measurements on specific scenarios, taken after an exact result preflight — not a general claim about either system. The Kernel does a great deal this interpreter does not attempt.

**Fast, and measured — never assumed.** The same engine runs on the JVM, Node.js and in the browser, and the numbers below come from two different runtimes — browser latency measured in system Chrome, cross-engine comparison and memory measured on the JVM. Each block says which. Browser figures are measured in a real browser, never inferred from Node.js.

## What you ship

What you actually ship, per target:

| | Browser / Node.js | Kotlin / JVM |
|---|---|---|
| Inside a bundled app | **240 kB gzip** entry, tree-shaken and minified | — |
| Third-party runtime dependencies | `big.js` (≈7 kB, bundled in) | Kotlin stdlib, kotlinx-serialization |
| Published artifact | 2.5 MB npm package — Kotlin runtime vendored in | 1.6 MB jar — Kotlin runtime resolved separately |

**Published artifact** and **inside a bundled app** measure different things, which is why they look so far apart: the published artifact is the whole library as distributed, while the bundled figure is what survives into an application after tree-shaking, minification and compression. The jar is the closer analogue of the npm package — and the npm package is in fact the larger of the two, because it vendors the Kotlin standard library and serialization runtime that the JVM resolves as ordinary dependencies.

No Kernel code ships in either target and neither declares a Kernel runtime dependency, which is what keeps the core MIT-licensed.

For scale, the A12 Kernel's own evaluation runtime, resolved from the 30.8.1 artifacts:

| A12 Kernel runtime | Jars | Size |
|---|---|---|
| Generated-Java strategy | 3 | **702 kB** |
| Document-model runtime service (dynamic Groovy) | 15 | **1 579 kB** |

*Declared first-level `com.mgmtp` dependencies only — the transitive closure is not walked, and Groovy itself, `slf4j`, `commons-lang3` and `base-model-*` are excluded, so both figures are lower bounds.*

The two are not directly substitutable, and the difference is structural rather than a matter of bytes. **The Kernel's runtime is a fixed library plus per-model generated code**: every Document Model is transformed into generated rule and computation classes that ship alongside it, so what a deployment actually carries grows with the number of models. The interpreter generates nothing — the artifact above is the complete cost for any number of models, which is also why its resident memory per model is a fraction of either Kernel strategy.

## How fast it runs (study)

Measured in system Chrome against the packed npm package — the same artifact consumers install, exercised in a real browser rather than inferred from Node.js — over the two Documents the collector defines — one representative, one stress. Each is timed **two ways**, because an application experiences both: the **first answer** a user waits for when a form opens, which includes building the interpreter, and the **warm** cost of revalidating after every keystroke, which does not.

| Document | Findings | First answer *(includes building the interpreter)* | Warm *(validation only)* |
|---|---|---|---|
| 200 rows — representative | — | **11.55 ms median** | **1.1 ms** |
| 10,000 rows — stress | 4,900 | **166.1 ms median** | **53.3 ms** |

First answers drawn to scale — the stress Document carries **50× the rows and costs 14× the time**:

```
   200 rows  ███                                       11.55 ms
10,000 rows  ████████████████████████████████████████  166.1 ms
```

Every first answer is the median of independent fresh Chrome processes — 10 for a stable series, automatically extended to 50 for a noisy one — clocked from interpreter construction through complete result materialization. The warm figure is the median of repeated validations against an interpreter built outside the clock. No warm-up credit, no partial results, no discarded samples.

The warm column is the timed region the JVM lane uses, so those runtimes and engines can be put side by side on the same 10,000-row Document:

| Engine and runtime | 10,000 rows, 4,900 findings |
|---|---|
| Interpreter — browser (Chrome) | 53.3 ms |
| **Interpreter — JVM** | **17.5 ms** |
| A12 Kernel, generated Java — JVM | 695.4 ms |
| A12 Kernel, dynamic Groovy — JVM | 794.7 ms |

Every row is validation only against an already-prepared engine and decoded Document. On the JVM, where all three engines can be compared directly, the interpreter is about **40× the A12 Kernel's generated Java** and about **45× its dynamic Groovy** on this Document. The browser row sits above them for scale: the same engine, in a browser, still finishes ahead of either Kernel strategy running on the JVM.

## Further results (JVM)

Measured on JDK 21 against both A12 Kernel evaluation strategies. The baseline is deliberately the toughest one available: the A12 Kernel's own **generated validator compiled as Java**, with code generation outside the clock. Each row says how many times faster the interpreter finished the same work.

| Suite | Interpreter is faster by — **JVM** |
|---|---|
| Default inventory, 15 cases | **2.8× – 17.1×** |
| Repeated-document evaluation | **12.2× – 16.8×** |
| Huge-document edge, 20,000 rows | **≈152×** |
| Hardest case — 384 concentrated low-firing rules | **1.73×** |

The spread does not track model size. The widest margins were measured on computation-heavy and repeated-document scenarios; the narrowest, 1.73×, on 384 concentrated rules that almost never fire.

**Not one eligible comparison went the other way.** Across the default, repeated, rule-count and edge suites, neither generated Java nor dynamic Groovy won a single case — the hardest case above is the interpreter's narrowest win, not a loss.

## What it costs to keep resident on the JVM

Memory goes the same direction, which is what decides how many models a server can hold in a cache. Holding one prepared model resident on JDK 21, across six models from type-definition-only to 192 rules, the last derived from a real model's shape:

| Engine | Resident cost per model — **JVM** | vs the interpreter |
|---|---|---|
| **Interpreter** — prepared model | **54 – 325 kB** | — |
| A12 Kernel, generated Java | 379 kB – 1.46 MB | **4.2× – 8.1×** more |
| A12 Kernel, dynamic Groovy | 1.09 – 11.98 MB | **18.5× – 37.9×** more |

The Kernel's own expanded Document Model is comparable in size to a prepared model; what separates them is the generated rule and computation code — and a fifth to a half of that is class metadata in Metaspace, which a heap measurement does not see at all. All three engines are first confirmed to produce identical findings over the identical Document, so this compares engines doing equal work.

For sizing a model cache, a resident prepared model costs about **1.4 kB per field plus 1.55 kB per rule plus 2.3 kB per group**, within 4% across all six.

<sub>Measured 2026-08-17 · Apple M1 · Chrome 151 · JDK 21 · no discarded samples · sizing fit from an earlier sweep · [method, timing boundaries and complete tables](https://github.com/mbackschat/a12-dmkits/blob/main/docs/INTERPRETER-PERFORMANCE.md)</sub>
