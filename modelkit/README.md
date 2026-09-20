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

## Reference

Published as `io.github.mbackschat.a12.dm:dmkits-modelkit` (MIT). Package `io.github.mbackschat.a12.dm.modelkit`. Depends on [`:core`](../core) and Jackson (`implementation`-scoped, so it never appears on a consumer's compile classpath). Kernel-free, guarded over both compiled classes and resolved POM.

```sh
./build.sh :test   # the kits' suites span all four and run at the root project
```

Guide with runnable snippets: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md) · the refusal-basis rule: [`docs/MVK-LEDGER.md`](../docs/MVK-LEDGER.md).
