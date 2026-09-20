# `:interpreter-bridge` — condition read-back with no kernel present

Published as `io.github.mbackschat.a12.dm:dmkits-interpreter-bridge` (MIT). Kotlin/JVM. Package `io.github.mbackschat.a12.dm.bridge`.

[`:rulekit`](../rulekit) can *render* a typed condition without a kernel, but reading a stored condition string **back** into the typed form needs a parser. This module is the kernel-free provider of that seam: [`InterpreterConditionReadBack`](src/main/kotlin/io/github/mbackschat/a12/dm/bridge/InterpreterConditionReadBack.kt) maps [`:interpreter`](../interpreter)'s AST onto rulekit's typed `Condition` and registers itself through `META-INF/services`. So a published consumer gets read-back by adding one dependency instead of building [`:adapter`](../adapter) from source.

**It is the one module depending on both `:rulekit` and `:interpreter`** — which is exactly why it is its own module rather than a class inside either: an `api project(…)` edge that folds an integration into a core's published POM is the coupling this layout exists to prevent. It is a *provider* of a kit's SPI port rather than a kit, which is why the `dmkits` umbrella still names five and this artifact is published standalone.

**Two caveats a consumer must be told, and both are in the POM description.** It maps a **subset** of the grammar and answers `ReadResult.Opaque` outside it — `:adapter`'s provider remains the authoritative, total one. And a classpath carrying **both** providers is *refused* rather than resolved arbitrarily: `ReadBackends.require()` throws `AmbiguousBackendException` naming both, and the caller settles it with `ReadBackends.prefer(…)`. `ReadBackends.current()` reports all three states (`None`/`Sole`/`Several`) without throwing, so a consumer can ask before committing.

Its agreement with the kernel provider is held by `ConditionReadBackParityTest` in [`:adapter`](../adapter) — the kernel cannot be the oracle inside a module whose whole claim is that no kernel is present.

Build and test: `./build.sh :interpreter-bridge:check` — `check`, not `test`, because `verifyInterpreterBridgePurity` is the guard behind the kernel-free claim, in test scope as well as main.
