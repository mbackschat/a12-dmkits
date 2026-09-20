# `:rulekit` — validation rules and computations as typed Java

## The problem

An A12 DocumentModel carries **validation rules** and **computations**, and each one's logic is a string in the kernel's expression language:

```
AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
```

Writing those by hand goes wrong in four ways, and only the first is obvious. A **mistyped path** fails when the kernel runs. A **wrong operator** or a wrong decimal scale is accepted and computes something else. An **error field out of scope** is rejected with a message about containment that takes a while to decode. And the insidious one is **inverted polarity**: the condition written as the *requirement* ("the delivery date is on or after the order date") instead of the *violation*. That is a valid condition, the kernel accepts it, and the rule then fires on every correct document and stays silent on every broken one.

## What this module does about it

It makes the condition typed, model-bound Java, so three of those four become compile errors and the fourth becomes a lint:

| entry point | what it is for |
|---|---|
| [`Dsl`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Dsl.java) | the condition builders — field references, comparisons, aggregates, filters, iteration |
| [`Rule`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Rule.java) / [`Computation`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/Computation.java) | the declaration itself. The error path and the rendered condition are **derived** from the refs you gave, never passed in separately — so they cannot disagree |
| [`DslRenderer`](src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/DslRenderer.java) | renders to the kernel's canonical text; English by default, German a supported target |
| [`RuleValidator`](src/main/java/io/github/mbackschat/a12/dm/rulekit/validate/RuleValidator.java) | puts a candidate through the **real kernel**, so *valid* means accepted rather than plausible |
| `dm.rulekit.spi.ConditionReadBack` | the other direction — reading a model's *existing* conditions back into typed form. See [`:interpreter-bridge`](../interpreter-bridge) |

**A path is written once and validated where you write it.** Acquire a typed ref (`m.num("/Order/Total")`) or navigate relative to an editor (`s.group("/Order").num("Total")`) and reuse it; a repeated path string in new code is a smell here, because the ref form already exists.

## The operator catalog ships as a resource

[`src/main/resources/catalog/operators.json`](src/main/resources/catalog/operators.json) carries every operator with its verified keywords, a valid and an invalid example, and its **gotchas** — the semantic surprises that are not guessable from a signature. `dmtool operators <id>` serves it.

**The catalog cannot rot, because CI executes it.** Every example in it goes through the real kernel on every run, so an example that stops being accepted fails the build rather than misleading whoever reads it next. That is why a fact about one operator belongs in the catalog rather than in prose: the catalog is checked and prose is not.

## Reference

Its publication coordinate is `io.github.mbackschat.a12.dm:dmkits-rulekit` (MIT). Packages `io.github.mbackschat.a12.dm.rulekit.*` (one strongly-connected component, deliberately). Depends on [`:core`](../core) and, for the laws it shares with the portable checker, [`:predicates`](../predicates). It has no dependency on [`:modelkit`](../modelkit) and modelkit has none on it — that boundary is a build-graph fact, not a convention. The publish wiring is landed and the public deploy is **deferred** ([`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §11), so today you get it by `./build.sh publishToMavenLocal` and `mavenLocal()`, not from a registry.

**Kernel-free:** no kernel type on any signature and none in the published POM (`verifyKitPurity` over compiled classes, `verifyPublishedClasspathsAreKernelFree` over what the POM resolves). Without a backend the kit still builds and renders, and reports `STRUCTURAL_ONLY` rather than claiming a confirmation.

```sh
./build.sh :test   # the kits' suites span all four and run at the root project
```

Guide with runnable snippets: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md) · design findings: [`docs/API-FINDINGS.md`](../docs/API-FINDINGS.md) · the operator catalog's contract: [`docs/CATALOG-SPEC.md`](../docs/CATALOG-SPEC.md).
