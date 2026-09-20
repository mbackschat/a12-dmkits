# `:interpreter-bridge` — read a model's existing rules without the A12 Kernel

## The problem

A DocumentModel stores each validation rule's logic as a **string** in A12's expression language:

```
AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
```

[`:rulekit`](../rulekit) can *produce* that string from typed Java — you build a condition and it renders. **It cannot go the other way.** Turning a stored string back into structure needs a parser, and rulekit has none.

That matters because most real work starts from a model that already exists. To export a model to JSON Schema, explain what a rule checks, list the fields a condition reads, re-render it in German, or modify it by re-expression, you first have to *understand* the rule that is already there.

There are only two parsers for this language. **The A12 Kernel has one** — authoritative and total, but the kernel is EUPL-licensed, is not on Maven Central, and reaching it means building [`:adapter`](../adapter) from source. **The [interpreter](../interpreter) has the other**, because it cannot evaluate a rule without parsing it first, and it is MIT and kernel-free by construction.

## What this module does

It adapts the second parser into the shape rulekit expects, so **adding one Maven dependency gives a consumer rule read-back with no kernel anywhere.** That is the whole purpose.

```java
// with :interpreter-bridge on the classpath and no kernel in sight
Map<String, ReadResult> rules = ReadBackends.require()
        .readAll(modelJson, ReferenceResolvers.selfContained());

// keyed by the rule's declaring group path plus its own name
switch (rules.get("/Order/DELIVERY_BEFORE_ORDER")) {
    case ReadResult.Converted(Condition c) -> // the typed condition: navigate, explain, re-render
    case ReadResult.Opaque(String text, String why) -> // a construct this mapping doesn't model yet
}
```

Today's published consumer is [`:schemakit`](../schemakit)'s Tier-A projection, which reads every stored rule in order to export the model. ([`:cli`](../cli) does *not* go through this port — it bundles the kernel and calls `KernelAdapter`'s richer surface directly.)

## Three behaviours worth knowing before you depend on it

**It maps a subset of the grammar, and says so instead of guessing.** A construct the mapping does not model comes back as `Opaque` carrying the rule's canonical text and a reason — the rule is neither lost nor silently wrong. `:adapter`'s provider remains the total one; reach for it when you need every construct.

**Two providers on one classpath are refused, not resolved arbitrarily.** With both this module and `:adapter` present, `ReadBackends.require()` throws `AmbiguousBackendException` naming both, and the caller settles it with `ReadBackends.prefer(…)`. `ReadBackends.current()` reports all three states (`None`/`Sole`/`Several`) without throwing, so code can ask before committing.

**No provider at all fails fast rather than degrading.** Elsewhere in this project a missing backend means a narrower honest answer — but read-back has no honest partial answer, so `require()` throws `BackendRequiredException` instead of returning an empty map that reads as "this model has no rules".

**It is tolerant by contract, which makes it the wrong instrument for one job.** *Every* failure becomes `Opaque`, including a rulekit rejection — right for a caller who only wants to know what a rule says, and wrong for measuring what rulekit catches. For that, drive the internal `ConditionBridge` directly, where a rejection and an unmapped construct are different exceptions.

## Why it is its own module

It is the one module depending on **both** `:rulekit` and `:interpreter`. Folding it into either would put an `api project(…)` edge into that product's published POM — a dmkits consumer would start carrying the interpreter, or an interpreter consumer would start carrying rulekit. It is also a *provider* of a kit's port rather than a kit, which is why the `dmkits` umbrella still names five and this artifact is published on its own: pulling a parser onto every umbrella consumer's classpath is not a choice they made.

Its agreement with the kernel provider is held by `ConditionReadBackParityTest` over in [`:adapter`](../adapter) — the kernel cannot be the oracle *inside* a module whose whole claim is that no kernel is present.

## Reference

Published as `io.github.mbackschat.a12.dm:dmkits-interpreter-bridge` (MIT). Kotlin/JVM, package `io.github.mbackschat.a12.dm.bridge`. The port is the only API; everything the mapping is built from is Kotlin `internal` — which is a compiler boundary, not a JVM one, so those classes are public in bytecode and appear in [`../api/dmkits-interpreter-bridge.api`](../api/dmkits-interpreter-bridge.api). They are implementation detail; the golden exists so movement in them is a reviewed diff.

```sh
./build.sh :interpreter-bridge:check   # check, not test — verifyInterpreterBridgePurity is the guard
```

`check` rather than `test`, because `verifyInterpreterBridgePurity` holds this module kernel-free in **test** scope as well as main: a test that reached a kernel would be measuring the wrong product.
