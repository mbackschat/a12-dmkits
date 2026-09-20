# `:core` — the shared foundation the kits stand on

Published as `io.github.mbackschat.a12.dm:dmkits-core` (MIT). Package `io.github.mbackschat.a12.dm.core`.

The layer both sibling kits depend on and neither owns: the DM-JSON reader, index and resolver, the diagnostic vocabulary, the entity-path types, and the **SPI ports** through which a kit reaches an A12 Kernel it does not itself carry.

**It is kernel-free, and that is enforced rather than intended.** `verifyKitPurity` fails the build if any kit's compiled classes link a `com.mgmtp.*` type, and `verifyPublishedClasspathsAreKernelFree` fails if the published POM *resolves* one — the second guard exists because a declared-but-unimported dependency reaches a consumer's classpath while being invisible to linkage analysis.

**The ports are the whole design.** [`ConsistencyOracle`](src/main/java/io/github/mbackschat/a12/dm/core/spi/ConsistencyOracle.java) and [`BackendInfo`](src/main/java/io/github/mbackschat/a12/dm/core/spi/BackendInfo.java) are `ServiceLoader` seams: with [`:adapter`](../adapter) on the runtime classpath a model is confirmed by the real kernel and `Backends.verification()` answers `KERNEL_CONFIRMED`; with no provider present the kits still load, read and build, and say so rather than claiming a verdict nobody gave. `KernelPatternPolicy` holds A12's pattern-admission law, sharing its statement with the portable checker through [`:predicates`](../predicates).

Layering: `core ← {rulekit, modelkit} ← edit`. Consumers: [`:rulekit`](../rulekit), [`:modelkit`](../modelkit), [`:edit`](../edit), [`:schemakit`](../schemakit), [`:adapter`](../adapter), [`:cli`](../cli).

Build and test: `./build.sh :test` — the kits have no test source of their own, because the suites span all four and run at the root project ([`../src/test`](../src)). Design: [`docs/PROJECT-DESIGN.md`](../docs/PROJECT-DESIGN.md) · [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md).
