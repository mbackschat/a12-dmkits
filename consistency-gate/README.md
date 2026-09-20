# `:consistency-gate` — the two façades over the portable checker

Published as `io.github.mbackschat.a12.dm:dmkits-consistency-gate` (MIT). Kotlin/JVM. Packages `io.github.mbackschat.a12.dm.gate` and `…dm.preflight`.

[`:consistency`](../consistency) is the Kernel-free checker, and it deliberately depends on nothing of ours but [`:predicates`](../predicates). This module is where callers who already hold one of the other products meet it, so neither side has to depend on the other.

| entry point | the question it answers |
|---|---|
| [`PortableConsistencyGate`](src/main/kotlin/io/github/mbackschat/a12/dm/gate/PortableConsistencyGate.kt) | a dmkits caller wants the checker's verdict **in the shape they already consume** — a rulekit `ValidationResult`, with the checker's own codes and a `PORTABLE_CHECKED` receipt |
| [`ConsistencyPreflight`](src/main/kotlin/io/github/mbackschat/a12/dm/preflight/ConsistencyPreflight.kt) | an interpreter consumer wants to know whether **A12 rejects** a model — a different question from whether the interpreter can *prepare* it |

**The preflight exists because the two questions look alike and one answers first.** `ModelWorkspace.prepare` says the interpreter can build an evaluator; `supportReport()` says which rules it can execute. Neither consults A12's constraints, so a model A12 rejects prepares perfectly well and reports full support — measured, not assumed. A consumer gating evaluation on preparation would evaluate a broken model and get answers about it. This façade builds no prepared graph and changes no evaluator admission.

**`ok() == true` is not `VALID`, and neither façade can make it one.** The checker cannot conclude validity, so an accepted model comes back `ok()` beside a status of `Inconclusive`, and `report()` carries the coverage evidence that says how much weight that bears. Selection is explicit: this module registers **no** `ConsistencyOracle` provider, because a portable verdict arriving through that port would make `Backends.verification()` answer `KERNEL_CONFIRMED` for a run no kernel arbitrated — an over-claim no consumer could detect.

**One artifact, two façades, and the cost is stated.** The preflight had its own `dmkits-interpreter-preflight` coordinate until 2026-09-20, so each POM named only its own consumer's dependencies. With the dmkit developer wave deferred ([`docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §1/§11) nobody could resolve either coordinate, and the split cost a second publication, lint, API golden and CI lane for two source files. The packages never moved, so re-splitting when that wave ships is a build-file change rather than an API break.

Build and test: `./build.sh :consistency-gate:check` — `check`, not `test`: `verifyConsistencyGatePurity` holds main **and** test scope kernel-free, because a test that reached a kernel would be measuring the wrong product. The gate's suite is Java and the preflight's is Kotlin, each in the language its consumers write. Contract: [`docs/CONSISTENCY-API.md`](../docs/CONSISTENCY-API.md).
