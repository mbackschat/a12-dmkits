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

## In practice

Bind a model once, then author rules against typed refs taken from it:

```java
BoundModel m = BoundModel.ofJson(Files.readString(modelPath));   // bind a model to the real kernel

GroupRef  addons = m.group("/Subscription/Addons");              // resolve each element ONCE …
NumberRef fee    = m.num("/Subscription/Addons/MonthlyFee");     // … then reuse the typed ref — no repeated paths

Rule rule = m.rule(addons)
    .name("FeeCapRule")
    .errorField(fee)                                            // fields come OFF the model …
    .when(fee.gt(1000))                                         // … a wrong path can't compile
    .code("FEE_CAP").severity(Severity.ERROR)
    .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
    .build();

String dmJson = m.add(rule).toJson();   // re-validated through the kernel; ready to write back
```

Or hold an **editor** on the group and navigate relatively — the model path is typed once, and the rule authors in place against it:

```java
EditSession s      = EditSession.read(dmJson);
GroupEditor addons = s.group("/Subscription/Addons");           // a session-bound editor on the group
NumberRef   fee    = addons.num("MonthlyFee");                  // a typed ref off the editor — relative, no full path
addons.addRule(r -> r                                           // pre-scoped to /Subscription/Addons
        .name("FeeCapRule").errorField(fee)
        .when(fee.gt(1000))                                     // same typed condition, same kernel gate
        .code("FEE_CAP").severity(Severity.ERROR)
        .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch.")).build());
String updated = s.validate().toJson();                         // one kernel gate
```

### Referencing a field — by path, or through an editor

Two ways to name the field a rule talks about; they return the **same pure refs**, so you blend them as fits:

| Style | Looks like | Reach for it when |
|---|---|---|
| **By path** | `m.num("/Order/Discount")` | referencing an **existing** element while authoring against a loaded model — the common case |
| **Through an editor** | `s.group("/Order").num("Discount")` | the read→**edit**→reference flow: a ref to a field you *just created*, or relative navigation, without re-typing paths — see [`:edit`](../edit/README.md#in-practice) |

Neither is "older": a path lookup is the foundation; the editor adds relative navigation, refs that flow out of edits, and `m.rule(editor)` for when structure and rules move together. The examples below use whichever fits — and [mix both](../edit/README.md#in-practice) when a rule spans a just-added field and an existing one.

### Author (a quick start)

The rule above is the whole pattern: pick the **scope group**, the **error field**, the **condition** (true on the violation), a **code** and per-locale **messages**, then `build()`. A **computation** (a field whose value is derived from others) reads the same way — either one unconditional `calc(...)`, or a **precondition table**: each `when(...)` guards its `calc(...)`, and a `commonPrecondition` guards them all:

```java
NumberRef base = m.num("/Subscription/Billing/BaseFee");
Computation comp = m.computation(m.num("/Subscription/Billing/EffectiveFee"))
    .name("EffectiveFeeComp")
    .commonPrecondition(base.filled())                 // guards every alternative below
    .when(base.ge(100)).calc(base.value())             // at or above the threshold: pass the base fee through
    .when(base.lt(100)).calc(NumberLiteral.of("0.00")) // below it: waived
    .message(en("Effective fee follows the fee tiers."), de("Effektivgebühr folgt den Gebührenstufen."))
    .build();
```

### Interpolated error messages

An error message can splice in the **runtime value**, the **label**, or the **row number** of a field/group through typed helpers — each takes a *ref*, so it can't name a field that doesn't exist. The kernel substitutes the token when it renders the message for a failing document:

| Helper | Renders the token | Substituted with… |
|---|---|---|
| `fieldValue(ref)` | `$/Order/Items/Count.value$` | the field's runtime **value** |
| `fieldLabel(ref)` | `$/Order/Items/Count$` | the field's localized **label** |
| `repetitionIndex(groupRef)` | `$#/Order/Items$` | the repeatable group's 1-based **row number** |
| `repetitionIndex(indexedGroup)` | `$#/Order/Items For "b"$` | the selected row number; requires a matching positive presence guard |
| `indexValue(indexField)` | `$index('Sku').value$` | the index value on the error field’s ancestry or current parallel token |
| `indexCategory(category)` | `$index('Sku')->'Band'$` | its mapped Enumeration category token |

The group token names the rule’s own group, an ancestor, or a group reached by its condition; absolute and relative spellings are supported. An unavailable group draws `INVALID_GROUP`. A repeatable descendant needs its own ordinary iteration; expanding a parent group’s contents does not create that iteration. Take refs from the bound model to check existence, then validate the rule’s message against its condition ([message controls](../docs/KERNEL-FINDINGS.md#kf546)).

```java
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.*;   // en, de, fieldValue, fieldLabel, repetitionIndex

NumberRef count = m.num("/Order/Items/Count");
GroupRef  items = m.group("/Order/Items");
// renders, e.g., "Row #2: Count (now 0) must be positive."
m.rule(items).name("CountPositive").errorField(count)
    .when(count.filled().and(count.le(0))).code("COUNT_POSITIVE").severity(Severity.ERROR)
    .message(en("Row #" + repetitionIndex(items) + ": " + fieldLabel(count.field())
            + " (now " + fieldValue(count.field()) + ") must be positive."))
    .build();
```

The CLI `rule add` / `apply` message path accepts the same `$…$` tokens as plain text — the helpers are just the typed, ref-checked way to write them.

### What you can express

The condition language is rich; a flavour of what the typed builder covers (each renders to kernel-valid DSL):

| Want to say… | DSL it produces |
|---|---|
| a field is present / absent | `FieldFilled(OrderDate)` · `FieldNotFilled(DeliveryDate)` |
| compare a field to a value **or another field** | `[Quantity] > 0` · `[MinQty] <= [MaxQty]` |
| enums by their stored value (incl. category navigation) | `[/Subscription/Tier] == "PRO"` · `[Tier -> Pricing] == "PAID"` |
| dates & arithmetic | `DifferenceInDays(OrderDate, DeliveryDate) < 0` · `[OrderDate] >= Today` |
| aggregates over a repeating group | `Sum(Items*/Amount) > 500` · `MaxValue(Items*/Price)` |
| string patterns (portable regex) | `PatternViolated(PostalCode, "[0-9]{5}")` |
| "for every add-on row, the fee is present" | `GroupFilled(/Subscription/Addons) And FieldNotFilled(MonthlyFee)` |

Browse the full, self-verifying catalogue (every operator with its meaning, examples, and **gotchas**) via the CLI's `operators` verb, or in the [API guide](../docs/API-GUIDE.md).

### Read a foreign rule back

```java
KernelAdapter adapter = KernelAdapter.of(m);
ReadResult read = adapter.read("/Order/DeliveryNotBeforeOrder");   // text → typed AST, tolerant
// → ReadResult.Converted(condition)  … or  ReadResult.Opaque(text, reason)  (preserved verbatim)
```

### Explain, navigate, find

```java
ModelContext ctx = new ModelContext(m);
ModelCard card  = ctx.describe();                 // every field + kind + enum domain + group repeatability
RuleExplanation why = ctx.explain(condition);     // what it checks, and WHEN it fires (polarity made explicit)
List<FieldMatch> hits = ctx.find("birthdate");    // resolve a fuzzy name to model fields
Set<String> users = adapter.whereUsed("/Customer/BirthDate");   // which rules reference a field
m.fields().filter(f -> f.annotation("audited").isPresent());    // find fields by annotation (GroupInfo too)
```

### Modify by re-expression, with a diff — and render to German

```java
new DslRenderer(ConditionLanguage.DE).render(condition);   // German keywords: Und, Heute, AlleFelderGefüllt …
```

Re-expressing an existing rule produces a **`RenderedDiff`** that discloses any lossiness *up front* — you review before you commit. The rule's **leading `;;` comment is preserved by default** across the loop (only inline/trailing comments are re-rendered away, and the diff says so).

### Comments — author once, preserved across the loop

Conditions can carry `;;` line comments (concrete syntax the kernel tolerates). Author them with the typed builder, and a comment-less `modify` keeps them:

```java
NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
m.rule(m.group("/Subscription/Addons")).name("FeeCap").errorField(fee)
        .when(fee.filled().and(fee.gt(1000)))
        .comment("cap the monthly fee per policy")     // ← rendered as a leading ;; line, kernel-tolerated
        .code("FEE_CAP").severity(Severity.ERROR)
        .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
        .build();
```

The CLI mirrors it: a `comment` key on `rule add` and `computation add`, plus `rule modify --comment`. In the typed library, computations carry comments on operations and preconditions too.

### Validation-pattern catalogue

The recurring idioms are a **catalogue**, `PatternCatalog.load().byId("date-order")`, across three kinds:

- **rule** idioms: date-order, required-when, mutually-exclusive, at-least-one-of, sum-of-line-items;
- the **computation** idiom `tiered-amount`;
- **field-constraint** idioms: bounded-number, formatted-string, value-set-enum, the field-level alternatives the `seeAlso` bridge names.

Each builds its artifact through the **typed DSL**, so it's correct by construction: a rule's right polarity and referenced error field, a computation's mutually-exclusive precondition table, or a typed field config. The library returns a sealed `RuleInstantiation` | `ComputationInstantiation` | `FieldInstantiation`, and the CLI `patterns` verb scaffolds and `--apply`s them. Each entry's example is kernel-validated in CI, so the catalogue can't go stale. Full API and the typed-vs-data design: [API guide](../docs/API-GUIDE.md).

More rulekit idioms and the design rationale are in the [API guide](../docs/API-GUIDE.md).

## Reference

Its publication coordinate is `io.github.mbackschat.a12.dm:dmkits-rulekit` (MIT). Packages `io.github.mbackschat.a12.dm.rulekit.*` (one strongly-connected component, deliberately). Depends on [`:core`](../core) and, for the laws it shares with the portable checker, [`:predicates`](../predicates). It has no dependency on [`:modelkit`](../modelkit) and modelkit has none on it — that boundary is a build-graph fact, not a convention. The publish wiring is landed and the public deploy is **deferred** ([`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §11), so today you get it by `./build.sh publishToMavenLocal` and `mavenLocal()`, not from a registry.

**Kernel-free:** no kernel type on any signature and none in the published POM (`verifyKitPurity` over compiled classes, `verifyPublishedClasspathsAreKernelFree` over what the POM resolves). Without a backend the kit still builds and renders, and reports `STRUCTURAL_ONLY` rather than claiming a confirmation.

```sh
./build.sh :test   # the kits' suites span all four and run at the root project
```

Guide with runnable snippets: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md) · design findings: [`docs/API-FINDINGS.md`](../docs/API-FINDINGS.md) · the operator catalog's contract: [`docs/CATALOG-SPEC.md`](../docs/CATALOG-SPEC.md).
