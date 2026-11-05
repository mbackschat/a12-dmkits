# `:modelkit` — a model's structure, typed

## The problem

[`:rulekit`](../rulekit) handles what a model *checks*. This handles what a model *is*: its fields, groups, type definitions, includes, document config and element metadata.

Writing that structure by hand means writing DM-JSON, and DM-JSON has rules you can only discover by being rejected. **Each field kind admits different configuration** — a `STRING` takes a pattern and a length, a `DATE` takes a format and a precision, a `DATERANGE` takes a format *and* a separator and only certain pairs are legal, an `ENUM` takes categories whose special values come from a fixed domain. Put a `TIME` format on a `DATE` and the model is refused. There are also whole-model constraints that are easy to violate from a distance: a repeatable group needs `max ≥ 2`, a field cannot reference a type definition that is not declared, two children of one group cannot share a name whatever their kinds.

Discovering those from kernel rejections is slow, and a rejection tells you about the symptom rather than the shape.

## What this module does about it

**A builder per field kind**, so the wrong configuration is not expressible rather than rejected: [`DateFieldBuilder`](src/main/java/io/github/mbackschat/a12/dm/modelkit/DateFieldBuilder.java), [`EnumFieldBuilder`](src/main/java/io/github/mbackschat/a12/dm/modelkit/EnumFieldBuilder.java), [`DateRangeFieldBuilder`](src/main/java/io/github/mbackschat/a12/dm/modelkit/DateRangeFieldBuilder.java), [`CustomFieldBuilder`](src/main/java/io/github/mbackschat/a12/dm/modelkit/CustomFieldBuilder.java), and the rest. You reach a kind's configuration only through that kind's builder.

**And a refusal at build time for what the type system cannot carry.** [`DocumentModelDraft`](src/main/java/io/github/mbackschat/a12/dm/modelkit/DocumentModelDraft.java) rejects a repeatable group with `max < 2` and a field referencing an undeclared type definition **as you build**, not after a round-trip. Some of those refusals come from [`:predicates`](../predicates), which is how the portable checker applies the same ones.

Every refusal here is deliberately backed by a measurement that the **real kernel refuses the same input** — delegating can only under-report and is safe, but an invented refusal can be wrong, and it is invisible precisely because the kernel is never asked. The sharper half is that a constructor check also fires when a model is read *inbound*, so an over-strict check makes a legal model unreadable rather than merely unauthorable.

The kernel still has the final say: every result re-binds through `checkConsistency`.

## Editing an existing model is a different job

This builds a model. To change one that already exists — add and remove fields, declare type definitions, alter config — use [`:edit`](../edit)'s `EditSession`, which puts structure *and* rule changes behind one kernel gate and one write.

## In practice

### Build a model from scratch

```java
DocumentModelDraft order = DocumentModelDraft.named("order")
    .locale("en_US")
    .rootGroup("Order", g -> g
        .field("OrderDate", FieldKind.DATE, Requiredness.REQUIRED)
        .numberField("Quantity", n -> n.fractionDigits(0).min("1"))   // a whole number ≥ 1
        .enumField("Priority", e -> e.value("LOW").value("HIGH"))
        .group("Items", items -> items
            .repeatable(99)                                           // a repeating line-item group
            .field("Sku", FieldKind.STRING)
            .numberField("Amount", n -> n.fractionDigits(2))))        // a money amount, scale 2
    .build();

String dmJson = order.toJson();   // DM-JSON the kernel accepts
```

### Field kinds, fully configured

Each kind carries its full configuration — string length/line-breaks, number scale/min/max/unit, dates with a format, enums, and a **custom** field type:

```java
DocumentModelDraft policy = DocumentModelDraft.named("policy").locale("en_US")
    .rootGroup("Policy", g -> g
        .stringField("Reference", s -> s.minLength(3).maxLength(12))                       // bounded text
        .stringField("Notes",     s -> s.lineBreaks().maxLength(500))                      // multi-line
        .numberField("Premium",   n -> n.unit(NumberTrait.AMOUNT).fractionDigits(2).min("0"))  // money ≥ 0
        .numberField("Discount",  n -> n.unit(NumberTrait.PERCENT).fractionDigits(1))      // a percentage
        .dateField("EffectiveOn", d -> d.format("dd.MM.yyyy"))
        .dateRangeField("CoverageYears", d -> d.format("yyyy").separator("/"))             // a from–to range
        .enumField("Tier", "BRONZE", "SILVER", "GOLD")                                     // enum (varargs shorthand)
        .customField("Iban", c -> c.type("IbanType").minLength(15).maxLength(34)))         // a custom field type
    .build();
```

### Group templates — multi-select & attachment

Two ready-made compositions: a repeatable selection list, and the kernel's canonical file-attachment group (generated whole):

```java
DocumentModelDraft claim = DocumentModelDraft.named("claim").locale("en_US")
    .rootGroup("Claim", g -> g
        .multiSelectGroup("Conditions", ms -> ms.enumField("Condition", "NEW", "USED").maxRows(2)))  // pick up to 2
    .attachmentGroup("Evidence", a -> a.repeatable(10))   // canonical file-attachment group, generated whole
    .build();
```

### Type definitions & includes

Declare a reusable **type** once and reference it by name; **include** another model's tree under a group, or **import** a type-def-only model's types (merged by id, not mounted):

```java
DocumentModelDraft catalog = DocumentModelDraft.named("catalog").locale("en_US")
    .typeDef("Money", t -> t.number(n -> n.unit(NumberTrait.AMOUNT).fractionDigits(2)))  // a reusable type, declared once
    .typeDef("Sku",   t -> t.string(s -> s.maxLength(12)))
    .rootGroup("Catalog", g -> g
        .typedefField("ListPrice", "Money")      // …then a field OF that type, referenced by id
        .typedefField("Code",      "Sku"))
    .include("supplier", "SupplierModel")        // mount another model's tree under a group named "supplier"
    .importTypeDefs("SharedTypes")               // merge a type-def-only model's types (by id), no mount
    .build();
```

### Document config & metadata

Locale, base year, a runtime-enforced character set — plus per-locale labels, a model comment, and name/value annotations on any element:

```java
DocumentModelDraft m = DocumentModelDraft.named("order").locale("en_US")
    .baseYear(2026)                                       // anchors relative-date comparisons
    .supportedCharacters("A-Z", "a-z", "0-9", " ", "-")   // runtime-enforced character set
    .comment("the customer order model")                 // a model-level note
    .label("en_US", "Order").label("de_DE", "Bestellung")// per-locale display labels
    .annotation("owner", "orders-team")                  // a name/value annotation
    .rootGroup("Order", g -> g.field("Reference", FieldKind.STRING))
    .build();
```

## Reference

Its publication coordinate is `io.github.mbackschat.a12.dm:dmkits-modelkit` (MIT). Package `io.github.mbackschat.a12.dm.modelkit`. Depends on [`:core`](../core) and Jackson (`implementation`-scoped, so it never appears on a consumer's compile classpath). Kernel-free, guarded over both compiled classes and resolved POM. The publish wiring is landed and the public deploy is **deferred** ([`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §11), so today you get it by `./build.sh publishToMavenLocal` and `mavenLocal()`, not from a registry.

```sh
./build.sh :test   # the kits' suites span all four and run at the root project
```

Guide with runnable snippets: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md) · the refusal-basis rule: [`docs/MVK-LEDGER.md`](../docs/MVK-LEDGER.md).
