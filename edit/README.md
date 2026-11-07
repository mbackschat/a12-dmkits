# `:edit` — one model, one gate, one write

## The problem

A real change to a DocumentModel is several edits at once: add a field, declare the type definition it uses, add the rule that validates it, adjust the error message. Done naively, that goes wrong in two ways.

**Half-applied state.** Each edit written separately means a failure on the third one leaves a model on disk with the first two applied — structurally present, semantically incomplete, and now a model whose next reader has to work out what was intended.

**Gating each step separately is both slow and wrong.** You would pay a kernel consistency check per edit, and intermediate states are legitimately inconsistent: the field exists before its rule does, the rule references a type definition declared in the same batch. Gating each step refuses work that is fine, while gating nothing ships work that is not.

## What this module does about it

[`EditSession`](src/main/java/io/github/mbackschat/a12/dm/edit/EditSession.java) loads the model **once**, applies every edit to that one in-memory copy, and puts the result through a **single** kernel consistency gate before anything is written. Intermediate states are never gated and never persisted; a failure rolls the whole session back and writes nothing.

It is the only module depending on both [`:modelkit`](../modelkit) and [`:rulekit`](../rulekit), which is what lets one session mix structure and rule work at all.

**Editors are reached by navigation, not by repeating paths.** A path is written once, at the point where it is validated:

| editor | scope |
|---|---|
| [`FieldEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/FieldEditor.java) · [`GroupEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/GroupEditor.java) | structure, in place — including re-typing a field rather than delete-and-recreate |
| [`RuleEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/RuleEditor.java) · [`ComputationEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ComputationEditor.java) | declarations, by re-expression |
| [`TypeDefEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/TypeDefEditor.java) · [`IncludeEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/IncludeEditor.java) | reusable types and mounted sub-models |
| [`ConfigEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ConfigEditor.java) · [`ElementMetadataEditor`](src/main/java/io/github/mbackschat/a12/dm/edit/ElementMetadataEditor.java) | document config, and labels/descriptions on any element |

`dmtool apply` is this session behind a CLI verb — read, ordered surgery, one gate, one write, rollback on failure. If you reach for `batch` instead, note that it is the opposite trade: independent dispatch, continuing past a child failure, and no rollback of an earlier successful write.

## In practice

The north-star flow: read a model, make a series of structure *and* rule edits (a rule may reference a field added earlier in the same session), validate **once**, write back. Intermediate invalid states are fine; only the final result must satisfy the kernel.

The idiom is the **editor**: you hold an editor on a group rather than re-typing string paths — structure edits chain, a typed ref to a just-created field comes from relative navigation, and rules author in place against it.

```java
EditSession s = EditSession.read(dmJson);
GroupEditor order = s.group("/Order");                       // a session-bound editor on /Order
order.addField("Discount", FieldKind.NUMBER);                // structure edit (modelkit) — chains
NumberRef discount = order.num("Discount");                  // a typed ref to the field just added
order.addRule(r -> r                                         // a TYPED rule (rulekit), pre-scoped to /Order
        .name("DiscountNotNegative").errorField(discount)
        .when(discount.filled().and(discount.lt(0)))         // the violation: a discount below zero
        .code("DISCOUNT_NEGATIVE").severity(Severity.ERROR)
        .message(en("Discount must not be negative.")).build());
String updated = s.validate().toJson();                      // one kernel gate
```

**Refs are the currency.** `order.num("Discount")` resolves *only because* `addField` ran first, and it returns a typed ref, not a string path you re-type. Rules author in place (`order.addRule(r -> …)`, the builder pre-scoped to the group) or take the editor straight as a scope (`m.rule(order)`). Either way the immutable AST holds **only the pure ref, never the session**, so a rule can't leak an effect.

What the editors carry:

- `GroupEditor` nests (`addGroup(name, sub -> …)`) and has the same per-type field builders as the from-scratch builder (`order.numberField("Surcharge", n -> n.fractionDigits(2))`, `enumField`, `dateField`, …). A *configured* field added mid-edit is byte-identical to one built from scratch.
- `FieldEditor` re-types, labels and annotates a field.
- `RuleEditor` / `ComputationEditor` modify or remove by held reference: a rule's condition, **or its severity / messages in place** (`s.rule(name).severity(WARNING).messages(en("…"))`).
- `EditSession` carries the **reference-preserving refactors**. `renameField`/`renameGroup`/`moveField`/`moveGroup` rewrite every reference to the element (via the kernel's `MoveSupportDM`) and re-validate, so a rename or relocation never dangles a referrer.

Design and rationale: [API guide §5c](../docs/API-GUIDE.md).

**Blend both as a developer would** — the editor for a field you just added, a path lookup for one that already exists, in the same condition (they are the same pure ref):

```java
GroupEditor billing = s.group("/Subscription/Billing");
billing.addField("Surcharge", FieldKind.NUMBER);                       // NEW field — via the editor
NumberRef surcharge = billing.num("Surcharge");                        // editor relative ref
s.addRule(m -> m.rule(billing.ref()).name("SurchargeUnderBase").errorField(surcharge)
        .when(surcharge.filled().and(surcharge.gt(m.num("/Subscription/Billing/BaseFee"))))  // ← path lookup, existing field
        .code("SURCHARGE_OVER_BASE").severity(Severity.ERROR)
        .message(en("Surcharge must not exceed the base fee.")).build());
```

The flat session verbs are still there for a quick one-off — `s.addField(path, …)`, `s.addRule(m -> m.rule(m.group(path)) …)` — and `s.addRule(DraftRule)` takes a condition already rendered as text, as the CLI's `apply`/spec path does, but it's not the idiom for library code.

More library idioms and the design rationale live in the [API guide](../docs/API-GUIDE.md); every doc is indexed in [`docs/`](../docs/README.md).

## Reference

Its publication coordinate is `io.github.mbackschat.a12.dm:dmkits-edit` (MIT). Package `io.github.mbackschat.a12.dm.edit`. Depends on [`:core`](../core), [`:modelkit`](../modelkit) and [`:rulekit`](../rulekit). Kernel-free, guarded over both compiled classes and resolved POM. The publish wiring is landed and the public deploy is **deferred** ([`../docs/RELEASE-SPEC.md`](../docs/RELEASE-SPEC.md) §11), so today you get it by `./build.sh publishToMavenLocal` and `mavenLocal()`, not from a registry.

```sh
./build.sh :test   # the kits' suites span all four and run at the root project
```

Guide with runnable snippets: [`docs/API-GUIDE.md`](../docs/API-GUIDE.md).
