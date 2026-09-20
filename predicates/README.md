# `:predicates` — one A12 law, one live statement

Published as `io.github.mbackschat.a12.dm:dm-predicates` (MIT). Kotlin Multiplatform (JVM + JS). Package `io.github.mbackschat.a12.dm.predicates`.

A deliberately tiny leaf holding side-effect-free predicates over a stored A12 declaration — today [`ElementNamePredicates`](src/commonMain/kotlin/io/github/mbackschat/a12/dm/predicates/ElementNamePredicates.kt) (the reserved-namespace law) and [`PatternPredicates`](src/commonMain/kotlin/io/github/mbackschat/a12/dm/predicates/PatternPredicates.kt) (A12's pattern-admission blacklist).

**It exists because two implementations could otherwise only share a law by restating it.** [`:rulekit`](../rulekit) is JVM/Java; [`:consistency`](../consistency) is Kotlin Multiplatform. Before this leaf, the reserved-namespace law had **three** copies and had already drifted, and the pattern blacklist had two that disagreed in both directions. Now `Rule` and `Computation` call `isReservedElementName` from here, and the portable checker evaluates the same expression.

**Narrowness is the contract.** `verifyPredicatesPurity` fails on **any** resolved dependency beyond the Kotlin standard library — and, since 2026-09-17, on any intra-repo project edge too, which its module filter had been silently dropping because a project component carries this project's own group. A leaf that grows a dependency stops being a leaf and starts being a second checker.

**A predicate earns a place here by having two consumers that would otherwise state the same law twice.** That is the admission test; a law with one consumer belongs in that consumer.

Build and test: `./build.sh :predicates:check` — `check`, not `test`: a Kotlin Multiplatform module registers no plain `test` task, and the purity lint hangs off `check`.
