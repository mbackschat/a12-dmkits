# `:core` — what every kit needs, and the seam to the A12 Kernel

## The problem

Only the A12 Kernel can say whether a DocumentModel is valid. It owns the model contract, and nothing here is going to second-guess it.

But the kernel cannot be *inside* these libraries. It is EUPL-licensed, so shipping it would pull copyleft across an MIT project; it is not on Maven Central, so a consumer could not resolve it; and it cannot be compiled into a native image, because its validator generation needs Groovy at runtime. The kits therefore have to be **kernel-free artifacts that are nevertheless kernel-confirmed when a kernel is present** — and honest, rather than silent, when one is not.

That is the problem this module solves, plus the ordinary one: four kits all need to read a DM-JSON model, index it, resolve its includes and speak a common diagnostic vocabulary, and none of them should own that for the others.

## What it does about it

**It holds the ports, not the kernel.** [`ConsistencyOracle`](src/main/java/io/github/mbackschat/a12/dm/core/spi/ConsistencyOracle.java) and [`BackendInfo`](src/main/java/io/github/mbackschat/a12/dm/core/spi/BackendInfo.java) are `ServiceLoader` seams, and [`Backends`](src/main/java/io/github/mbackschat/a12/dm/core/Backends.java) is how a kit asks what is available:

```java
Backends.hasBackend();      // is a real oracle on this classpath?
Backends.verification();    // KERNEL_CONFIRMED, or an honest lesser answer
```

Put [`:adapter`](../adapter) on the runtime classpath and `BoundModel.ofJson` and `RuleValidator.check` are arbitrated by the real kernel, reporting `KERNEL_CONFIRMED`. Leave it off and the kits still load, index, build and render — they report `STRUCTURAL_ONLY` instead of claiming a verdict nobody gave. **The degraded answer is narrower, never wrong**, and [`../src/noBackendTest`](../src) exists to prove the no-backend lane behaves that way rather than throwing or silently passing.

**And it holds the shared reading machinery:** [`ModelReader`](src/main/java/io/github/mbackschat/a12/dm/core/ModelReader.java) and [`ModelIndex`](src/main/java/io/github/mbackschat/a12/dm/core/ModelIndex.java) (parse and index a model, including its declared field configuration), [`ReferenceResolvers`](src/main/java/io/github/mbackschat/a12/dm/core/ReferenceResolvers.java) and [`ModelResolver`](src/main/java/io/github/mbackschat/a12/dm/core/ModelResolver.java) (how includes and imported type definitions are found), [`EntityPath`](src/main/java/io/github/mbackschat/a12/dm/core/EntityPath.java) (a model path as a type rather than a string), and the diagnostic vocabulary — `Severity`, `Notification`, `Verification`, `Suggest`.

One law lives here rather than in a kit: `KernelPatternPolicy`, A12's pattern-admission blacklist, which reads its statement from [`:predicates`](../predicates) so the portable checker applies the same one.

## Kernel-freedom is guarded twice, on purpose

`verifyKitPurity` fails the build if any kit's **compiled classes** link a `com.mgmtp.*` type. That is not enough on its own: a dependency that is *declared but never imported* reaches a consumer's classpath while being invisible to linkage analysis. So `verifyPublishedClasspathsAreKernelFree` independently fails if the published **POM resolves** one. The claim a consumer actually cares about is the second; the first is the one easy to check.

## Reference

Published as `io.github.mbackschat.a12.dm:dmkits-core` (MIT). Package `io.github.mbackschat.a12.dm.core`. Layering is `core ← {rulekit, modelkit} ← edit`; consumed by every kit plus [`:schemakit`](../schemakit), [`:adapter`](../adapter) and [`:cli`](../cli).

```sh
./build.sh :test           # the kits' tests live at the root project, not here
./build.sh :noBackendTest  # the degraded lane
```

The kits have no test source of their own because the suites span all four — a rule from `rulekit` over structure from `modelkit`, gated by the kernel — so they run at the [root project](../src), which keeps the kernel out of any kit's own dependencies. Design: [`docs/ARCHITECTURE.md`](../docs/ARCHITECTURE.md) · [`docs/PROJECT-DESIGN.md`](../docs/PROJECT-DESIGN.md).
