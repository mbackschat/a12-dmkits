# `:rulekit` — validation rules and computations as typed Java

Published as `io.github.mbackschat.a12.dm:dmkits-rulekit` (MIT). Package `io.github.mbackschat.a12.dm.rulekit.*`.

An A12 DocumentModel carries **validation rules** and **computations** whose logic is a condition in the kernel's expression language. Writing those by hand is fragile — a mistyped path, a wrong operator or decimal scale, or, most insidiously, **inverted polarity** (the condition true on the requirement instead of the violation). This kit makes them typed, model-bound Java instead, so the mistakes become compile errors or a refused build.

| entry point | what it does |
|---|---|
| [`Dsl`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Dsl.java) | the condition builders — field refs, comparisons, aggregates, filters, iteration |
| [`Rule`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Rule.java) / [`Computation`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Computation.java) | the declarations themselves; the error path and the rendered condition are *derived*, never passed in |
| [`DslRenderer`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/DslRenderer.java) | renders to the kernel's canonical text — English by default, German as a supported target |
| [`RuleValidator`](src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RuleValidator.java) | puts a candidate through the real kernel, so *valid* means accepted rather than plausible |
| `dm.rulekit.spi.ConditionReadBack` | the read seam: parse a stored condition back into the typed form. [`:adapter`](../adapter) provides the authoritative, total implementation; [`:interpreter-bridge`](../interpreter-bridge) provides a kernel-free one |

**The operator catalog ships as a resource.** [`src/main/resources/catalog/operators.json`](src/main/resources/catalog/operators.json) carries every operator with its verified keywords, examples and *gotchas*; `dmtool operators <id>` serves it, and CI runs every catalog example through the real kernel, so a wrong example fails the build rather than misleading a reader.

**Kernel-free.** No kernel type on any signature and none in the published POM (`verifyKitPurity`, `verifyPublishedClasspathsAreKernelFree`). A consumer who wants the kernel oracle adds a backend; without one the kit still builds and renders and reports `STRUCTURAL_ONLY` rather than claiming confirmation.

Build and test: `./build.sh :test` — the suites span all four kits and run at the root project. Guide: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md) · design findings: [`docs/API-FINDINGS.md`](../docs/API-FINDINGS.md).
