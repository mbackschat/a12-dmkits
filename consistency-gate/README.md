# `:consistency-gate` — reaching the portable checker from the other products

## The problem

[`:consistency`](../consistency) checks a model's rules and computations with no A12 Kernel. To stay publishable to npm and the browser it depends on **nothing** of ours but the tiny [`:predicates`](../predicates) leaf — not the kits, not the interpreter.

That independence is the point, and it leaves two consumers stranded.

**A dmkits caller** already holds DM-JSON and a `ModelResolver`, and already handles rulekit's `ValidationResult` everywhere. Asking the checker directly means assembling a `List<ModelSource>` by hand and then re-shaping the report into the type the rest of their code handles — twice the work, and a second shape of "what went wrong" to keep in agreement.

**An interpreter consumer** has a sharper problem: they are about to ask the wrong question and get a convincing answer. `ModelWorkspace.prepare` tells them the interpreter can build an evaluator; `supportReport()` tells them which rules it can execute. **Neither consults A12's constraints at all** — so a model A12 would reject prepares perfectly well and reports full support. Gate evaluation on preparation and you evaluate a broken model and get answers about it. That is measured here, not assumed.

## What this module does about it

Two entry points, each answering one of those:

| entry point | the question |
|---|---|
| [`PortableConsistencyGate`](src/main/kotlin/io/github/mbackschat/a12/dm/gate/PortableConsistencyGate.kt) | run the checker and hand me the verdict **as a rulekit `ValidationResult`** — `ok()`, `diagnostics()` with the checker's own codes, and a `consistencyGate()` receipt |
| [`ConsistencyPreflight`](src/main/kotlin/io/github/mbackschat/a12/dm/preflight/ConsistencyPreflight.kt) | take the sources I would hand `ModelWorkspace.fromSources` and tell me whether **A12 rejects** this model |

The preflight builds no prepared graph, holds no interpreter, and changes no evaluator admission — the interpreter accepts and refuses exactly what it did before, which is locked by a test rather than asserted.

## Two things not to misread

**`ok() == true` is not `VALID`, and no façade can make it one.** The checker cannot conclude validity — it is not feature-complete against A12, by construction rather than by omission. So an accepted model comes back `ok()` beside a status of `Inconclusive`, and `report()` carries the coverage evidence saying how much weight that bears. A consumer reading only `ok()` has silently upgraded "nothing rejected it" into "it is valid".

**The receipt is `PORTABLE_CHECKED`, which is deliberately neither neighbour** — not `KERNEL_CONFIRMED`, because no kernel saw the model, and not `STRUCTURAL_ONLY`, because a measured re-implementation of A12's constraints did run rather than the kits' own prechecks.

**And selection is explicit: this module registers no `ConsistencyOracle` provider.** It would be the obvious wiring and it would be wrong — a portable verdict arriving through that port would make `Backends.verification()` answer `KERNEL_CONFIRMED` for a run no kernel arbitrated, an over-claim no consumer could detect because the kernel is never asked. You reach these façades **by name**.

## Why the façades sit here rather than inside either product

An integration that depends on two products must not live inside either: an `api project(…)` edge folds the whole other product into that one's published POM. So a dmkits consumer would start carrying the interpreter, or the checker would acquire a dependency on the kits and stop being publishable to npm. Beside both, inside neither — the same reasoning as [`:interpreter-bridge`](../interpreter-bridge).

**One artifact carries both façades, and the cost is stated rather than hidden.** The preflight had its own `dmkits-interpreter-preflight` coordinate until 2026-09-20, so each POM named only its own consumer's dependencies. That hygiene is worth what publication is worth — and [`docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §1/§11 defer the dmkit developer wave, so nobody could resolve either coordinate while the split cost a second publication, purity lint, API golden and CI lane for two source files. The packages never moved, so re-splitting when that wave ships is a build-file change, not an API break.

## Reference

Published as `io.github.mbackschat.a12.dm:dmkits-consistency-gate` (MIT). Kotlin/JVM, packages `io.github.mbackschat.a12.dm.gate` and `…dm.preflight`.

```sh
./build.sh :consistency-gate:check   # check, not test
```

`verifyConsistencyGatePurity` holds this module kernel-free in **test** scope as well as main: the whole claim is a verdict reached with no kernel on the classpath, so a test that reached one would be measuring the wrong product. The gate's suite is Java and the preflight's is Kotlin — each in the language its own consumers write, because a Kotlin suite over the gate would exercise property syntax no Java caller can use.

Full contract: [`docs/CONSISTENCY-API.md`](../docs/CONSISTENCY-API.md).
