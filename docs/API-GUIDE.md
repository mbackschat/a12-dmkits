# rulekit & modelkit — the API guide

> What the dmkits library (**rulekit** + **modelkit**) feels like to use, as a typed Java library: bind a model, author rules and computations the compiler steers, edit structure, read foreign rules back, explain them, and run them against data — all confirmed by the real A12 kernel. **Part I** motivates it with a worked case study; **Part II** introduces the handle families everything is built from; **Parts III–V** take authoring, the edit loop, and discovery + the agent loop in turn. The numbered **design findings** — the *why* behind these capabilities — live in [`API-FINDINGS.md`](API-FINDINGS.md) (this guide stays a clean capability narrative).

---
## Part I — Motivation & case study

### Why this exists

A12 validation- and computation-rules are stored as **`errorCondition` strings** — `FieldFilled(MonthlyFee) And [MonthlyFee] < 0` — hand-written, untyped, and only checked when the kernel finally runs. A typo in a field name, a number operator on a date, the wrong enum value, the missing error message: all surface late, as cryptic `MVK_*` codes, far from where you wrote the mistake.

rulekit/modelkit replace that with a **typed, model-aware builder** and a full **read → explain → modify → validate → write-back** loop. The model is bound once; every handle (`m.num(path)`, `m.enumOf(path)`) knows its kind, scale, and enum domain, so a wrong path or a nonsensical comparison fails *at the call site* with the cause named — not three steps later in the engine. The rendered DSL text the kernel stores is identical to what you'd hand-write; what changes is that the compiler, not the kernel, catches the mistakes first.

### Case study: a subscription model, end to end

One pass through the whole loop on a small **subscription** model — `Subscription { Tier (enum BASIC/PRO/ENTERPRISE), MonthlyFee (money, scale 2) }`. Every step below is real, runnable code, **confirmed by the A12 kernel** (it's a locked test — the doc can't drift from what runs).

**1 — Bind once, then explore.** Binding indexes the model; from there everything flows off typed handles. Exploration is a plain `Stream` (no query language):

<!-- snippet: cs-bind -->
```java
BoundModel m = BoundModel.ofJson(subscription);          // bound once — kinds, scale, enum domain in hand
List<FieldInfo> money = m.fields()                       // explore: every money field; Stream.filter IS the matcher
        .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
        .toList();
```

**2 — Author a rule the compiler steers, then let the kernel confirm it.** Handles know their kind/scale/enum domain, so a wrong operand fails at the call site; the rendered DSL is then validated by the real kernel — no codegen, no compile:

<!-- snippet: cs-author -->
```java
NumberRef fee  = m.num("/Subscription/MonthlyFee");      // knows: NUMBER, scale 2
EnumRef   tier = m.enumOf("/Subscription/Tier");         // knows: BASIC / PRO / ENTERPRISE
Rule rule = m.rule(m.group("/Subscription"))
        .name("ProNeedsFee").errorField(fee)
        .when(tier.is("PRO").and(fee.notFilled()))       // a PRO subscription must carry a monthly fee
        .code("PRO_FEE_REQUIRED").severity(Severity.ERROR)
        .message(Dsl.en("A PRO subscription requires a monthly fee."))
        .build();                                        // build-time pre-checks: error field in scope, locale law
ValidationResult vr = new RuleValidator().check(subscription, rule.draft());   // the REAL kernel confirms it
```

**3 — Edit structure *and* rules in one session, then write back.** Add a field and a rule that guards it in a single `EditSession`; the reference to the just-created field comes back by navigation, and one terminal kernel gate validates the whole change:

<!-- snippet: cs-edit -->
```java
EditSession s = EditSession.read(subscription);
GroupEditor sub = s.group("/Subscription");
sub.addField("Discount", FieldKind.NUMBER);              // structure edit (modelkit)
NumberRef discount = sub.num("Discount");                // a ref to the field just created
sub.addRule(r -> r.name("DiscountNonNeg").errorField(discount)
        .when(discount.filled().and(discount.lt(0)))     // rule (rulekit) over the new field
        .code("DISCOUNT_NEG").severity(Severity.ERROR)
        .message(Dsl.en("Discount must not be negative.")).build());
String updated = s.validate().toJson();                  // one terminal kernel gate, then write back
```

**4 — Run it against data — does it actually fire?** The empirical check a static "is it valid?" can't give: evaluate the rule over a real document instance and see which codes fired (the angle that catches an inverted-but-valid rule):

<!-- snippet: cs-run -->
```java
// does it actually FIRE? — the empirical check a static "is it valid?" can't give
EvalResult clean = KernelAdapter.ofJson(updated)
        .evaluate(Map.of("Subscription", Map.of("Discount", "0")), Locale.US);
assertFalse(clean.firedCodes().contains("DISCOUNT_NEG"));        // a compliant instance — silent

EvalResult violating = KernelAdapter.ofJson(updated)
        .evaluate(Map.of("Subscription", Map.of("Discount", "-5")), Locale.US);
assertTrue(violating.firedCodes().contains("DISCOUNT_NEG"));     // a negative discount — the rule fires
```

That's the arc — **bind → explore → author → validate → edit → write back → run** — and rulekit also **reads** foreign `errorCondition` strings back into the same typed form and **explains** them. The handle families you'll work with throughout come next (Part II); Parts III–V then take authoring, the edit loop, and discovery in turn.

## Part II — The handle families (`Ref` · `Info` · `Editor`)

Read this first — it's the vocabulary the rest of the guide is built from. Almost everything you hold is one of **three handle families**, and the **suffix tells you the role** — point at an element, describe it, or change it. They sit on a deliberate **purity gradient**, which is the single most important thing to internalize:

```
   Ref  (pure value, no model)     →   Info  (model-aware, read-only)   →   Editor (session-aware, mutating)
   "point at it, to author"            "describe it, to explore"            "change it"
   goes INTO the immutable AST          narrows to a Ref                     produces Refs of what it creates
```

- **`XRef` — point at it, to author.** A **typed pointer** to a model element, and the *currency* of authoring: a **pure immutable value** (no model/session inside), so it's safe to embed in the AST. You get one from a `BoundModel` lookup (`m.num(path)`) or relative navigation off an `Info`/`Editor` (`app.str("Zip")`); it holds nothing but the path + static type facts, and you use it for operands, error fields, and rule/computation scopes. *Members:* the per-kind scalars `NumberRef` `StringRef` `EnumRef` `BoolRef` `ConfirmRef` `DateRef` `TimeRef` `DateTimeRef` `DateFragmentRef` `DateRangeRef`; `GroupRef`; `CategoryRef`; the list/wildcard `FieldListRef` `GroupListRef`; the kind-erased base `FieldRef`; and the read-back `CustomConditionRef`. A **custom-typed** field's value is text, so its handle is a `StringRef` from `m.custom(path)` — the full string-comparison surface, while `m.str(path)` stays STRICTLY for `STRING` fields (finding 46).
- **`XInfo` — describe it, to explore.** A **rich read-only descriptor** — the navigation/exploration currency (jOOQ-`Meta` style). You get one from `BoundModel.fields()`/`groups()`/`fieldAt()`/`groupAt()`; it holds the model (so it can read metadata and navigate), and you use it to read `kind`/`scale`/`annotations`, filter, then **narrow** to a `Ref` to author. *Members:* `FieldInfo` · `GroupInfo`.
- **`XEditor` — change it.** A **session-bound mutator** (jOOQ `UpdatableRecord`/active-record style) — the *one* editing idiom. You get one from an `EditSession` (`s.group(path)`, `s.field(path)`, …); it holds the live session, and you use it to mutate: fluent edits chain, and relative navigation yields `Ref`s of just-created elements. *Members:* `GroupEditor` `FieldEditor` `RuleEditor` `ComputationEditor` `TypeDefEditor` `IncludeEditor` `ConfigEditor`.

**The invariant that ties it together (decision §5c / D7):** the immutable AST holds **only pure `Ref`s — never an `Info` or `Editor`**. That's the functional-core / effects-at-the-edge split: a `Ref` carries no model or session, so a rule can't accidentally capture one and leak a side effect; the model-awareness lives on `Info` (read) and the mutation on `Editor` (write), at the edges. It's also why relative navigation lives on `Info`/`Editor` (they hold the model) and **not** on `GroupRef` (a pure value) — see finding 40.

**The producers** these come from: **`BoundModel`** — the immutable bound snapshot (F0); hands out `Ref`s (lookup) and `Info`s (navigation). **`EditSession`** — the read→edit→write-back session; hands out `Editor`s. **`ModelContext`** — the agent's name-resolution + explain surface (`find`/`describe`/`explain`).

<!-- teaser -->
```java
// Ref — point at it, to author (pure value → goes in the rule's AST):
NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
Condition c   = fee.filled().and(fee.lt(0));

// Info — describe it, to explore; narrow to a Ref when you want to author:
FieldInfo info = m.fieldAt("/Subscription/Addons/MonthlyFee").orElseThrow();
info.kind();   // ValueKind.NUMBER    info.scale();   // 2    info.annotation("audited");
info.numberMin();  info.numberMax();  info.minLength();  info.maxLength();  info.pattern();  // declared bounds (finding 54)
NumberRef sameFee = info.asNum();                      // lenient explore → strict author

// Editor — change it; a ref to what you just created comes back by relative navigation:
GroupEditor addons = s.group("/Subscription/Addons");
addons.addField("Discount", FieldKind.NUMBER);          // mutate (chains)
NumberRef discount = addons.num("Discount");            // Ref of the just-added field
```

Read the rest of this guide with that gradient in mind: §3–§4 author with **`Ref`s**, §6c explores with **`Info`s**, §5b/§5c edit with **`Editor`s**.



## Part III — Authoring rules

The detailed parts (III–V) all use the committed **subscription** fixture (our own original): a `Subscription` root with `Tier` (enum `BASIC/PRO/ENTERPRISE`, category `Pricing` FREE/PAID), `AutoRenew` (boolean), `TermsAccepted` (confirm), a repeatable `Addons{Name, MonthlyFee(scale 2)}`, and `Billing{InvoiceEmail, BaseFee(scale 2), EffectiveFee(scale 2)}`; declared locales `en_US` + `de_DE` (header locales are arbitrary — en/de are not special). A few examples use the **order** fixture (`ShippingAddress`/`BillingAddress` groups, boolean/confirm fields), or extend a model illustratively where a construct needs it. Snippets marked as locked are byte-identical to a kernel-run test; teasers (elided/illustrative) are hand-maintained.

---

## 1. Setup — bind once, everything flows from the model

<!-- teaser -->
```java
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.*;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

BoundModel m = BoundModel.ofJson(json);                       // bind self-contained DM-JSON
BoundModel fromFile = BoundModel.load(path);                  // …or straight from a file on disk
BoundModel withIncludes = BoundModel.ofJson(json, resolver);  // a model with includes — rulekit expands via the resolver (F0; §5d)

NumberRef  fee   = m.num("/Subscription/Addons/MonthlyFee");  // knows: number, scale 2
EnumRef    tier  = m.enumOf("/Subscription/Tier");            // knows: BASIC, PRO, ENTERPRISE
BoolRef    renew = m.bool("/Subscription/AutoRenew");         // boolean ≠ confirm (§5b)
ConfirmRef terms = m.confirm("/Subscription/TermsAccepted");
GroupRef   addons = m.group("/Subscription/Addons");          // knows: repeatable
```

Everything the safety story needs is in hand at bind time: kinds, scale, enum domain, repetition. `m.num(stringPath)` fails *here* with the kind named — not as an `MVK_*` three steps later.

## 2. The type system steers — a study in what you *cannot* write

The largest `MVK_*` class is type rejection; the API removes it method-by-method. All of these are **compile errors or immediate failures today**:

<!-- teaser -->
```java
m.str(NAME).le("x")          // does not compile — strings have no ordering (MVK_INVALID_TYPE_FOR_COMPARISON erased)
anything.not()                // does not compile — the kernel has no negation; use the negative predicate families
tier.is("GOLD")               // fails fast — not in the model's enum domain (checked at build, not at the kernel)
terms.isFalse()               // does not exist — confirm compares only to True (MVK_INVALID_COMPARE_TO_YES erased)
fieldFilled(addons)           // does not compile — FieldRef ≠ GroupRef (MVK_NO_GROUPS_ALLOWED / MVK_NO_FIELDS_ALLOWED erased)
m.bool("/Subscription/TermsAccepted")  // fails fast — confirm is a distinct kind from boolean (§5b)
tier.category("Nope")                  // fails fast — not a declared category (MVK_CATEGORY_UNKOWN erased)
tier.category("Pricing").is("BASIC")   // fails fast — BASIC is an enum value, not a Pricing category value
                                       //   (MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON erased)
m.num(FEE).matches(".*")               // does not compile — patterns work on string fields only
                                       //   (MVK_INVALID_TYPE_FOR_PATTERN_COMPARISON erased; enums too)
email.matches("(")                     // fails fast — not a compilable regex (MVK_INVALID_PATTERN pre-checked;
                                       //   Java-valid kernel-rejected forms such as lookbehind fail at the same preflight)
renew.in("x")                          // does not exist — value lists exclude booleans/confirms
                                       //   (MVK_ONLY_STRING_ENUM_NUMBER_ALLOWED erased)
fee.in(10.50)                          // does not compile — value-list numbers are INTEGER literals only
                                       //   (long... signature; a decimal is a kernel parse error)
tier.in("PRO", "GOLD")                 // fails fast — GOLD is outside the enum domain
noFieldValueIncludedInValueList(List.of("x"), name, fee)  // does not compile — field lists must be
                                       //   type-homogeneous (MVK_VARYING_TYPES_NOT_ALLOWED erased)
fieldFilled(m.flatten("/S/Addons*/Name"))  // does not compile — scalar slots take FieldRef, starred
                                       //   lists are FieldListRef (MVK_NO_WILDCARDS_ALLOWED erased)
m.flatten("/S/Billing*/BaseFee")       // fails fast — Billing is not repeatable (MVK_INVALID_WILDCARD)
```

Each erased mistake corresponds to a **kernel-verified** rejection code (see `KERNEL-INTEGRATION-NOTES.md`) — the type system is the documentation.

**Equality-verb naming (the one convention worth stating).** `eq`/`ne` are the *consistent* equality verbs — every value ref (`NumberRef`, `StringRef`, the date/time refs, and now `EnumRef`/`CategoryRef` and their expressions) accepts them, so cross-type generic code reads one way. On enums `is`/`isNot` stay as the **domain-natural** reading (`tier.is("PRO")`), so both forms render identically — pick whichever reads best. `BoolRef` (`isTrue`/`isFalse`) and `ConfirmRef` (`confirmed`/`notConfirmed`) are the **deliberate exception**: they compare only to a *fixed constant*, not an arbitrary value, so they carry no `eq`/`ne` — a generic `== value` would re-open the kernel-illegal `confirm == False` (`MVK_INVALID_COMPARE_TO_YES`) the type erases.

**Numeric equality carries the parser's scale summary.** The gate is not “fields must match; literals are exempt.” Each expression carries a known signed scale or unknown plus an independent multiplicative-constant capability; only a capable operand on the smaller-scale side may be padded. Thus a scale-2 field may equal literal `0` in either operand order, but a scale-0 field cannot equal literal `0.00`; ordering remains scale-free. Arithmetic composes the summary (`+`/`−` maximum, `×` sum, `/` unknown, explicit rounding known), so the typed builder rejects known-illegal equality early and leaves generic loaded expressions with unavailable model metadata to the live kernel. `NumberExpr` retains `new NumberExpr(Calculation)`, `expression()`, value equality/hash/text, and all builder method descriptors, but it is now a final value class rather than a Java record; source using record patterns/deconstruction or reflection/serialization that requires `Class.isRecord()` must migrate to `expression()`.

**Arithmetic rendering preserves both fold order and authoring regions.** Every arithmetic node rounds independently, so a right same-precedence child is grouped even for `+`/`*`. The renderer also inserts the curly-brace reset needed to keep nested division/power trees kernel-legal: an AST for `(8/4)/2` renders `{ 8 / 4 } / 2`, and a right division under multiplication renders `8 * { 4 / 2 }`. These braces preserve the typed fold and the parser's structural legality; they intentionally do not suppress the kernel/interpreter's later one-pass division lowering.

## 3. Combinators & parameterization — the reuse study

### 3.1 Parameterized fragments are ordinary methods

<!-- teaser -->
```java
/** Gate + requiredness — the corpus's most frequent pattern shape. */
static Condition mandatoryWhen(Condition gate, FieldRef field) {
    return gate.and(fieldNotFilled(field));
}

/** All-or-nothing: fields must be filled together (the partial-fill error). */
static Condition requiredTogether(FieldRef first, FieldRef... rest) {
    return fieldsNotCollectivelyFilled(first, rest);
}

/** A repeatable group row that exists must have real field content — GroupFilled already sees the row itself. */
static Condition groupNotBlank(GroupRef group, FieldRef witness) {
    return groupFilled(group).and(fieldNotFilled(witness));
}

// Composed like values:
Condition r1 = mandatoryWhen(tier.is("PRO"), fee.field());
Condition r2 = mandatoryWhen(renew.isTrue().and(terms.confirmed()), m.field("/Subscription/Billing/InvoiceEmail"));
Condition r3 = groupNotBlank(addons, m.field("/Subscription/Addons/Name"));
```

### 3.2 Fragments with domain parameters
<!-- snippet: study-fragments -->
```java
/** Tolerance check — only the fixed 1/2/5/10 variants exist (the catalog teaches this; the enum enforces it). */
static Condition withinTolerance(NumberRef actual, NumberRef expected, Tolerance t) {
    return allFieldsFilled(actual.field(), expected.field())
            .and(actual.differsWithTolerance(t, expected));   // DiffersWithToleranceRange{N} (NumberRef.differsWithTolerance)
}

/** Per-row threshold over a repeating group — composes the iteration cluster (item 7): the semantic index
 *  `num(f).forIndex("k")` (7f) reads this field's value in the row whose index field equals "k", so a row
 *  can be compared against a keyed baseline row. */
static Condition exceedsBaselineRow(GroupRef repeating, NumberRef amount, String baselineKey) {
    return groupFilled(repeating).and(amount.gt(amount.forIndex(baselineKey)));
}

/** Uniqueness by key (7b) — one operator, but the fragment names the business intent.
 *  The rule sits at the PARENT of the repeating group (kernel position law, KERNEL-FINDINGS §9). */
static Condition uniqueBy(GroupRef repeating, FieldRef key) {
    return groupFilled(repeating).and(repetitionNotUnique(key));               // + .from(scope) for @From
}

/** Bulk authoring over a field set gathered by navigation — every fill quantifier and the filled COUNT
 *  take the same `List`, so a caller holding one never unrolls it back into varargs. */
static Condition partiallyCompleted(List<FieldRef> section) {
    return Dsl.notAllFieldsFilled(section)                                     // the list peer of every
            .and(Dsl.numberOfFilledFields(section).gt(0));                     // quantifier, count included
}
```

The fragments show how the shipped primitives compose into the **business idioms** the proposal's §2 names (`mandatory`, `withinTolerance`, per-row thresholds, `uniqueBy`) — each is 1–3 lines built from the operators in hand.

The per-row threshold uses the **semantic index** `num(f).forIndex(key)` — read *this field's value in the row whose index field equals `key`*. It is **not number-only**: `forIndex` lives on every comparable ref (`num`/`str`/`enumOf`/`date`/`time`/`dateTime`/`dateRange`), and the key is either a **constant** (`forIndex("AUTO")`) or **another field** (`forIndex(m.field("/Policy/PreferredCode"))`). The kernel places no kind restriction on the indexed field — its own flagship example indexes an *enum* (`[Addresses/Country For PreferredAddress]`) — so the ref you call it on decides the result kind. (Finding 55 / DEV-API-GAPS DG15.)

### 3.3 A domain fragment library — packaging reuse

<!-- teaser -->
```java
/** The team's validation idiom collection: plain static methods over Dsl + handles. */
public final class SubscriptionIdioms {
    private SubscriptionIdioms() {}

    public static Condition paidTier(EnumRef tier) {
        return tier.isNot("BASIC");
    }
    public static Condition feeRequired(EnumRef tier, NumberRef fee) {
        return mandatoryWhen(paidTier(tier), fee.field());
    }
    public static Condition feePositiveWhenPresent(NumberRef fee) {
        return fee.filled().and(fee.le(0));    // error scenario: fee present but not positive
    }
    /** Format idiom — present-but-malformed (the FieldFilled guard is the kernel's own pattern habit). */
    public static Condition malformedEmail(StringRef email) {
        return email.filled().and(email.violates(".+@.+\\..+"));   // regex renders verbatim — no escaping layer
    }
    /** Reserved-prefix idiom — the shape of the metamodel's own HEADER_ID_STARTS_WITH_XML rule. */
    public static Condition reservedPrefix(StringRef id) {
        return id.matches("[xX][mM][lL].*");                       // anchored whole-value match (§7)
    }
    /** Allow-list idiom — enum membership beats chained Or; the domain is pre-checked. */
    public static Condition rushOrder(EnumRef priority) {
        return priority.in("EXPRESS", "OVERNIGHT");                // FieldValueIncludedInValueList(…)
    }
    /** Placeholder sweep — one quantifier over several same-type fields (In-list shape). */
    public static Condition noTestPlaceholders(StringRef first, StringRef... more) {
        return noFieldValueIncludedInValueList(List.of("test", "demo", "n/a"), first, more);
    }
    /** Threshold idiom — the count is an ordinary number expression (kinds may be mixed). */
    public static Condition incompleteBilling(BoundModel m) {
        return numberOfFilledFields(m.group("/Subscription/Billing")).lt(3);   // group form: count inside
    }
    /** Aggregate idiom (7c) — Sum over a repetition list; unspecified rows count as 0. */
    public static Condition addonBudgetExceeded(BoundModel m, String budget) {
        return sum(m.flatten("/Subscription/Addons*/MonthlyFee")).gt(budget);  // scale-faithful literal
    }
    /** Filtered aggregate (7d) — Having narrows the *-list before Sum consumes it. */
    public static Condition paidAddonBudget(BoundModel m, String budget) {
        return sum(m.flatten("/Subscription/Addons*/MonthlyFee")
                .having(m.num("/Subscription/Addons/MonthlyFee").gt(0))).gt(budget);
    }
    /** Ordered fallback: later operands are visited only when no earlier operand supplied a value. */
    public static Condition firstAvailableFeeTooHigh(BoundModel m, String limit) {
        return firstFilledNumber(
                m.flatten("/Subscription/Addons*/MonthlyFee"),
                m.num("/Subscription/Billing/BaseFee").field()).gt(limit);
    }
    /** Escape hatch (item 8) — the Zod `.refine` analogue: name a host-registered check. */
    public static Condition reversibleTier(EnumRef tier) {
        return tier.is("ENTERPRISE").and(customCondition("NotReverse"));   // host code decides NotReverse
    }
    /** Date threshold (9a) — the most frequent date pattern; Today composes directly. */
    public static Condition deliveryWithinDays(DateRef order, DateRef delivery, long days) {
        return allOf(order.filled(), delivery.filled(),
                delivery.after(addDays(order, days)));        // [Delivery] > AddDays(Order, days)
    }
    /** A DATE_TIME AddDays keeps its DATE_TIME result type, including a field-valued day count. */
    public static Condition sameWallTimeTomorrow(DateTimeRef start, DateTimeRef expected) {
        return addDays(start, 1).eq(expected);                 // AddDays(datetime, 1) == [datetime]
    }
}
```

No framework, no registry — a fragment library is a Java class; discoverability comes from javadoc + the catalog. `AddDays` is temporal-output-polymorphic: a DATE operand yields `DateExpr`, while a DATE_TIME operand yields `DateTimeExpr`; the compiler therefore preserves the kernel's time-preserving wall-day semantics instead of narrowing the result to a date (finding 71). (This is the Zod lesson: schemas-are-code.)

A `Having` keeps a candidate only when its complete filter result is known TRUE; FALSE or UNKNOWN drops the candidate before the aggregate or quantifier reads the selected cell. Correlation includes the current outer coordinate by default, so exclude it explicitly with `CurrentRepetition(G) != CurrentRepetition($G)` when the intended relation is "another row."

`sumOfProducts(left, right)` has a narrower list contract than the general aggregates: each operand must be an unfiltered single-star number list, and the two **fields** must share one owning group — sharing a starred group is not enough, so `Dsl.sumOfProducts(basket.flatten("Lines*/Qty"), basket.flatten("Lines*/Packaging/Weight"))` is rejected even though both star `Lines`. `Dsl.sumOfProducts(order.flatten("Items*/Quantity"), order.flatten("Items*/UnitPrice"))` is admitted. Each refusal quotes the code the kernel emits for that **pair**: an operand below a repeatable level it does not wildcard is `MVK_NO_WILDCARD`, one outside the other's starred group is `MVK_DIFFERENT_GROUPS`, and only a pair with no repeatable level at all is `MVK_REPEATABLE_LEVEL_REQUIRED`. One check is deliberately left to the kernel — that the star sits on the *lowest* repeatable segment needs model repeatability the operand does not carry, so `Milestones*/Tasks/Effort` passes construction and `rule check` rejects it. Multi-star totals remain available through aggregates such as `sum`.

**An aggregate operand may be a whole GROUP, not just a field list.** `Sum`, `MaxValue`, `MinValue`, `NumberOfDifferentValues` and `NumberOfFilledFields` all take an `AggregateOperand`, which is either the familiar field operand or the `GroupListRef` from `flattenGroup` — so `Dsl.sum(invoice.flattenGroup("/Invoice/Lines*"))` totals every number the kernel expands `Lines` to, recursively into nested subgroups, without naming one field. That is the kernel's own `ExpandService` shape; before, it could only be written as a condition string.

It behaves like a repetition list at every entity-list gate: one group scope satisfies the arity law alone, the same starred group may be listed twice, and nested stars (`flattenGroup("/Invoice/Lines*/Fees*")`) both survive. One gate stays with the kernel, and for a reason: the **kind** rule applies to the *expansion*, not to the group — `Sum` over a group containing a string is `MVK_NO_NUMBER` — so pre-checking it would mean owning the expansion's extent. A group beside a **descendant** of itself is `MVK_DUPLICATE_PARAM2` — the one duplicate arm a wildcard does not escape — and that one IS pre-checked, ahead of the kind loop because the kernel answers the overlap even when the descendant is also the wrong kind. **Do not forget the star on a repeatable group:** `flattenGroup("/Invoice/Lines")` is a legal bare scope for the group-list quantifiers but `MVK_NO_WILDCARD` inside an aggregate. A `Having` filter on a group operand is rejected outright (`MVK_NO_GROUPS_ALLOWED`), which is why the typed surface offers none. (Finding 121; measured matrix in `AggregateGroupOperandLawsTest`, runtime behaviour in `GroupStarValueAggregateDiffTest`.)

For a multi-star `Having`, binding begins at the first starred segment. Thus `Aisles*/Bins*/Quantity` spans bins across every aisle; leave `Aisles` unstarred when the current aisle must remain outer-bound. Inside that filter, `CurrentRepetition(Bins) != CurrentRepetition($Bins)` compares the leaf index, not physical row identity, and excludes every candidate with the outer bin's leaf index across aisle rows. More generally, `$` captures the complete named outer repetition environment: `CurrentRepetition($Aisles)` reads the captured aisle coordinate while `CurrentRepetition($Aisles/Bins)` reads the captured bin coordinate from the same outer rule instance. Do not model `$` as one scalar outer row number. The `Having` and `CurrentRepetition` catalog entries cross-link the equality-correlation and self-exclusion examples (finding 76; full-environment lock IF178).

`RepetitionNotUnique` is an ordinary three-valued leaf under legal `And`/`Or` composition, but one condition tree may contain only one such leaf. Put every component of a composite uniqueness key into that call. Its duplicate relation covers the full declared scope regardless of sibling guards: formally invalid and required-empty keys exclude a row, all-empty composite keys are skipped, optional-empty components participate with omission polarity, numeric equality is scale-insensitive, and every row in a duplicate set fires. An iterating `Or` branch containing a negative-presence predicate is separately model-illegal (`MVK_NEG_CONDITION_IN_ITERATION`); rewrite that branch as a positively guarded `And` instead of treating it as a limitation of uniqueness composition (finding 77).

### 3.4 Parameterized *rules* — templates over the model (message interpolation — `Dsl.fieldValue`)

<!-- teaser -->
```java
/** One rule per monetary field: never negative. Navigation (§6c) drives the loop. */
static List<Rule> nonNegativeMoneyRules(BoundModel m) {
    return m.fields()                                             // Level-1 navigation
            .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)   // "money" by convention
            .map(f -> m.rule(f.group())                         // Rule authoring object (F2a)
                    .name(f.name() + "NonNegativeRule")
                    .errorField(f.asNum())
                    .when(f.asNum().filled().and(f.asNum().lt(0)))
                    .code("NON_NEGATIVE_" + f.name().toUpperCase())
                    .severity(Severity.ERROR)
                    .message(en(f.name() + " must not be negative."),   // F2a: interpolate via Dsl.fieldValue(f) → $path.value$
                             de(f.name() + " darf nicht negativ sein.")) // a message per declared locale (the locale law)
                    .build())
            .toList();
}
```

This is the bulk-authoring payoff: a *rule template* is a function `BoundModel → List<Rule>`; an LLM (or CI) applies it to any model.

### 3.4a Authoring-time reuse over a multi-field structure — `sameAddress`

When you author **many rules in Java that compare the same multi-field structure** (e.g. a seven-field address), a fragment removes the boilerplate — ordinary F5 combinator reuse, two levels deep. (Note: this is a convenience in the *authoring code*, not a transformation of the stored rule — the kernel DSL is flat, so the rendered `errorCondition` is the same string either way; what improves is the Java you write, not the persisted text.)

<!-- teaser -->
```java
// field level — "null-safe equality" is now a SHIPPED ref combinator (finding 39):
//   a.agreesWith(b) == both present and equal, OR both absent
//   renders ( (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b) ) — rulekit auto-parenthesizes
record Address(StringRef street, StringRef zip, StringRef city, /* … 7 */) {}
static Condition sameAddress(Address x, Address y) {
    return allOf(List.of(x.street().agreesWith(y.street()),   // agreesWith (39) folded by allOf(List) (38)
                         x.zip().agreesWith(y.zip()), /* … */));
}
// each rule then reads at intent level — e.g. "the warehouse ships from the sender's or the buyer's address":
m.rule(g).when(warehouseAddr.filled().and(sameAddress(warehouse, sender).or(sameAddress(warehouse, buyer)))) …
```

`agreesWith` ships the most-repeated mirroring idiom as a one-call ref combinator on every value ref — `StringRef`, `NumberRef`, `EnumRef`, and the temporal refs `DateRef`/`TimeRef`/`DateTimeRef` (number inherits the derived signed-scale/constant-capability gate; enum compares by stored value via `is`; the temporal refs use their own date/time/date-time equality). It's written — and its polarity reviewed — **once**, not per pair. **Field-vs-field equality** (`a.eq(b)`) underneath landed for string + number (finding 18); **enum-vs-enum** (`EnumRef.is(EnumRef)`/`isNot(EnumRef)`). `sameAddress` is then just `agreesWith` zipped over the fields and folded with `allOf(List)` (38) — the full worked rebuild is the **Study, §3.6**. (Later: a `sameAddress(GroupRef, GroupRef)` that zips like-named fields via the model index — Level-2 navigation.)

### 3.5 n-ary composition

Varargs for hand-written composition; a `List<Condition>` overload for the **generated/bulk** case (one clause per navigated field, folded). The presence quantifiers (`allFieldsFilled`/`noFieldFilled`/`atLeastOneFieldFilled`) gained the same `List<? extends FieldOperand>` overload, so a presence guard over a built field set needs no `toArray` splat.

<!-- snippet: nary -->
```java
Condition c = allOf(r1, r2, r3);            // And-fold: "r1 And r2 And r3" — no redundant parentheses
Condition d = allOf(r1, anyOf(r2, r3));     // "r1 And (r2 Or r3)" — parens per the kernel's mixing rule
Condition e = allOf(List.of(r1, r2, r3));   // the LIST form — fold clauses a loop/stream built (bulk authoring)
```

### 3.5a Field-fill quantifiers over a group

All seven field-fill quantifiers also accept a `GroupListRef`: the group is expanded recursively to its contained fields, so authors do not have to duplicate the group's structure as a hand-maintained field list. A bare group comes from `flattenGroup("/Order/ShippingAddress")`; starred forms keep their repetition scope (`flattenGroup("/Order/Items*")`).

<!-- teaser -->
```java
GroupListRef address = m.flattenGroup("/Order/ShippingAddress");
Condition complete = Dsl.allFieldsFilled(address);
Condition empty = Dsl.noFieldFilled(address);
Condition any = Dsl.atLeastOneFieldFilled(address);
Condition incomplete = Dsl.notAllFieldsFilled(address);
Condition partial = Dsl.fieldsNotCollectivelyFilled(address);
Condition notExactlyOne = Dsl.notExactlyOneFieldFilled(address);
Condition multiple = Dsl.moreThanOneFieldFilled(address);
```

The traversal order is observable in computations: fields stream recursively in declaration order, field-major then repetition-major, and each quantifier stops as soon as its result is decisive. A malformed cell after that decision is not read; one reached before it can poison the computation. The typed render/read forms are locked by `GroupScopedFieldFillReadTest`; the transitive poison discriminator is `BareGroupComputePoisonDiffTest`.

**`FieldValuesNotUnique` takes a group scope too**, though it is a value-distinctness predicate rather than a fill quantifier — "no two values inside this group are equal", with the group expanded to its contained fields the same way:

<!-- teaser -->
```java
Condition duplicateInAddress = Dsl.fieldValuesNotUnique(m.flattenGroup("/Order/ShippingAddress"));
Condition duplicateAcrossRows = Dsl.fieldValuesNotUnique(m.flattenGroup("/Order/Items*"));
```

Two gates read the group differently, and the pair is worth knowing because neither is guessable from the other. **Arity** is satisfied by the group slot alone — a group expanding to a *single* field is admitted, while that same field named directly is `MVK_PARAMSIZE_INVALIDN`. **Comparability** reads the expansion, so a group whose fields mix categories is `MVK_VARYING_TYPES_NOT_ALLOWED` exactly as the equivalent explicit list would be; that gate stays kernel-arbitrated for the same reason the aggregates delegate theirs. (Finding 122; measured in `FieldValuesNotUniqueAdmissionLawsTest`, runtime agreement in `FieldValuesNotUniqueGroupOperandDiffTest`.)

**Fields and groups mix in ONE list.** The kernel has a single entity list per quantifier whose members may each be a field or a group, in either order, and rulekit says the same: `Dsl.entityListPredicate` takes a mixed list where the varargs families above take a homogeneous one.

<!-- snippet: mixed-entity-list -->
```java
// ONE entity list, mixed members — the kernel's own shape: each member may be a field or a group,
// in either order. The homogeneous varargs families stay the ergonomic choice; reach for this when
// a list genuinely mixes, and on the read path, where a mixed list has to be representable at all.
Condition partiallyFilled = Dsl.entityListPredicate(Quantifier.FIELDS_NOT_COLLECTIVELY_FILLED,
        List.of(m.flattenGroup("/Order/ShippingAddress"), m.field("/Order/Quantity")));
```

Reach for it when a list genuinely mixes; the two homogeneous families stay the ergonomic choice otherwise. It also matters on the way IN: `KernelAdapter.read` needs it to represent a mixed list the kernel handed back, and without it the adapter used to rewrite each group into the fields the kernel expands it to — a rewrite that twice lost a `*` and produced a condition the kernel then refused. (Finding 124; `MixedEntityListRoundTripTest`.)

**Author an entity-list aggregate through the model when its list holds a group.** A12 reads every operand before it asks any list gate, and the first operand it cannot read answers: `MVK_GROUP_IS_EMPTY` or `MVK_ROOT_GROUP_IS_EMPTY` for a group that holds no field, an index class for a key that does not resolve ([KF458](KERNEL-FINDINGS.md#kf458)). A `Dsl` factory sees operands, not the model, so on a list holding a group or an index it still refuses what is refused but leaves the class unnamed: a plain `IllegalArgumentException` rather than a `KernelCodeRefusal`. The `BoundModel` twins — `m.sum`, `m.maxValue`, `m.minValue`, `m.numberOfFilledFields`, `m.numberOfFilledGroups` and `m.numberOfFilledRootGroups`, beside `m.latestOf` and its temporal peers and `m.numberOfDifferentValues` — read each group's expansion first and then name A12's class. Behind a semantic index they withhold as `Dsl` does, because whether a key resolves is not read here. On a list of plain fields both routes cite. (`EntityListOperandReadLawsTest`.)

<!-- snippet: model-bound-entity-list -->
```java
// Through the MODEL, an entity-list aggregate reads each group operand's expansion first, as A12
// does, so a refusal can name A12's own class on a list that holds a group. Dsl's twin cannot see
// the expansion, so it refuses the same input with the class left unnamed.
NumberExpr filledParts = m.numberOfFilledFields(m.flattenGroup("/Order/ShippingAddress"),
        m.field("/Order/Quantity"));
// One non-repeatable group is too few to count: MVK_PARAMSIZE_INVALIDGN, named because the model
// shows the group holds a field. An empty group would have answered MVK_GROUP_IS_EMPTY first.
KernelCodeRefusal tooFew = assertThrows(KernelCodeRefusal.class,
        () -> m.numberOfFilledGroups(m.group("/Order/ShippingAddress")));
```

**The DATE extrema take a starred group too**, and the shape of that widening is worth a sentence because it differs from the others. `MaxValue`/`MinValue` over date-like values carry a KIND, and a `GroupListRef` has none — a group's kind lives in its expansion, which the kit delegates to the kernel rather than re-deriving the extent. So the aggregate now takes its kind explicitly, from the factory that already names it:

<!-- teaser -->
```java
GroupListRef inspections = m.flattenGroup("/Claim/Inspections*");
Condition late = Dsl.latestOf(inspections).after(m.date("/Claim/ClaimedOn"));
```

`latestOf` is the DATE one, `latestTimeOf` the TIME one, `latestDateTimeOf` the DATETIME one — the information was always at the call site and merely re-derived from the operand afterwards. The **field** arm keeps a kind check, so a DATE list handed to `latestTimeOf` still fails fast. Only the starred group form is offered, for the same reason as above. (`DateExtremaGroupOperandLawsTest`.)

**…and they take an operand LIST, not one scope**, exactly as the numeric `maxValue`/`minValue` do. Two or more direct date fields are a legal entity list, and the kernel compares their declared **component sets** rather than their format spellings — so two differently spelled complete dates aggregate fine:

<!-- teaser -->
```java
// FiledOn is declared yyyy-MM-dd, ClosedOn dd.MM.yyyy — one component set, two spellings.
Condition reopenedOutOfOrder = Dsl.latestOf(m.field("/Claim/FiledOn"), m.field("/Claim/ClosedOn"))
        .after(m.date("/Claim/ReopenedOn"));
```

This closed a read-path hole, which is the half worth knowing: while the aggregate held a single scope, a stored rule using that kernel-legal shape came back from `rule read` as **Opaque** with *"MaxValue takes number fields"* — the model was unreadable, not merely unauthorable. Mind the neighbouring gate: `FieldValuesNotUnique` refuses that same pair, because its temporal gate is the declared format *string*. (Finding 127; `TemporalAggregateComponentSetLawsTest`, `MultiOperandTemporalExtremumReadTest`.)

**`NumberOfValueInFields` counts across a starred group's expansion.** It is an entity-list operator like the aggregates, so its operand list is an `AggregateOperand` list and a `GroupListRef` sits in it for the same reason it sits under `Sum` — "how many cells anywhere below this group equal X", without naming one field:

<!-- teaser -->
```java
NumberExpr flaggedLines = Dsl.numberOfValueInFields("BACKORDER", m.flattenGroup("/Order/Items*"));
Condition tooManyBackorders = Dsl.fieldFilled(m.str("/Order/Reference")).and(flaggedLines.gt(2));
```

Only the **starred** form is offered. The unstarred group operand has no measurement on this carrier, and offering it because a sibling carrier admits one would be an analogy standing in for a measurement — the same move that made the *absence* of this overload look deliberate for as long as it did. (`ValueInFieldsGroupOperandLawsTest` locks the admission; runtime behaviour, including the row-spanning count, is `GroupStarValueInFieldsDiffTest`.)

### 3.5b Group presence and group-count quantifiers <a id="group-quantifiers"></a>

Group presence is validation-stage state, not merely JSON object existence. For a repeatable group, an instantiated row is content even when every field in that row is empty. For a non-repeatable group, `GroupRef.filled()` needs a formally admitted descendant value or an instantiated repeatable descendant row; the group's own structural presence is not enough. A non-repeatable group containing only formally invalid values is neither filled nor definitely empty, so `filled()` and `notFilled()` are not strict Boolean complements in that state.

The five group-list predicates (`AllGroupsFilled`, `NoGroupFilled`, `AtLeastOneGroupFilled`, `NotAllGroupsFilled`, and `GroupsNotCollectivelyFilled`) tally operands into definitely-filled and definitely-empty buckets. A formally erroneous group enters neither bucket: it can be bypassed only when the known groups already provide the predicate's decisive witness, not treated as filled or empty.

`NoGroupFilled` and `AtLeastOneGroupFilled` also accept the exact `GroupListRef` returned by `flattenGroup` for their kernel-legal starred form. When the terminal group itself is repeatable (`Samples*`), these predicates count instantiated repetitions: an empty created row is still an existing row. When only ancestors are starred (`Sections*/Items*/Details`, with non-repeatable `Details`), the stars select the ancestor environments and `Details` contributes its ordinary group state in each one: admitted scalar or repeatable-descendant content fills it, malformed-only content is unavailable, and ancestor rows alone do not fill it. Use the ordinary `GroupRef.notFilled()`/`filled()` form for scalar group presence without ancestor expansion.

<!-- snippet: group-count-quantifiers -->
```java
GroupListRef samples = m.flattenGroup("/Calibration/Samples*");
Condition noRows = Dsl.noGroupFilled(samples);
Condition someRows = Dsl.atLeastOneGroupFilled(samples);
```

Typed read-back retains the star and the collection-scan meaning; `WildcardGroupFillQuantifierReadTest` locks the render → parse → typed-read → render identity for both polarity siblings.

Inside a `Having` filter a group-presence leaf takes the `$` outer-correlation marker, exactly as a field reference does — `Dsl.groupFilled(group, true)` and `Dsl.groupNotFilled(group, true)`, the group twin of `Dsl.currentRepetition(group, true)`. The marker moves no verdict when the named group is **not repeatable**: as a filter's **sole** condition either spelling draws `MVK_NO_ITERATION_FOR_WILDCARD`, and beside a reference into the filtered list's own iterated group both are admitted ([KF242](KERNEL-FINDINGS.md#kf242)). So a refusal of the marked form alone is about the missing in-scope reference, never about the `$`. Naming a **repeatable** group outside the operand's path is a different gate and a different code — `MVK_INVALID_ITERATION_IN_FILTER_CONDITION`, which no conjunct clears and which the enclosing rule iteration does not clear either, so there the marker is the remedy rather than a redundancy ([KF244](KERNEL-FINDINGS.md#kf244)).

<!-- snippet: correlated-group-presence -->
```java
// A $-marked group-presence leaf reads the group in the outer environment the Having captured.
// It is admitted as long as the filter ALSO carries a reference into the filtered list's own
// iterated group — here fieldLocal(), the predicate on /Order/Lines/Qty — which binds the wildcard.
Condition markedPresence = Dsl.groupFilled(M.group("/Order/Billing"), true);
Condition boundFilter = fieldLocal().and(markedPresence);
```

The marker survives typed read-back rather than being normalized away (`CorrelatedGroupPresenceReadTest`), which is the direction that matters most: a client type unable to hold it would make a kernel-legal model come back as a verbatim `ReadResult.Opaque` instead of a typed condition.

Despite their names, the five fixed group-list predicates consume one common **field-or-group entity list** (`GroupListOperand`): a checked field enters as the `FieldRef` from `m.field(...)`, a group scope as the `GroupListRef` from `m.flattenGroup(...)` (an unstarred path yields the bare scope). A field operand contributes filled / empty / unavailable directly — a malformed or (in partial validation) non-relevant field joins neither bucket, exactly like an erroneous group — and a decisive witness fires beside an unavailable sibling. Mixing kinds is kernel-legal:

<!-- snippet: group-list-entity-operands -->
```java
Condition authored = model.field("/Claim/PolicyNumber").filled()
        .and(Dsl.atLeastOneGroupFilled(
                model.field("/Claim/IncidentNote"),
                model.flattenGroup("/Claim/Vehicle")));
```

**Duplicates and group/descendant overlap are pre-checked**; the arity, root and wildcard gates stay kernel-arbitrated — the typed surface represents the list and `rule check` refuses those shapes (`MVK_PARAMSIZE_INVALID2`, `MVK_NO_WILDCARDS_G_ALLOWED`). The duplicate bar is **direct-only**: a *wildcarded* operand is exempt, so `NoGroupFilled(Damages*, Damages*)` is legal (the same exemption the aggregate lists carry) while `NoGroupFilled(Handler, Handler)` is not — the operand list keeps both occurrences, and the evaluator tallies the rows once per occurrence. A fixed singleton has the scalar presence semantics: `NoGroupFilled(F)` ≡ `FieldNotFilled(F)` (and reads back as it), `AtLeastOneGroupFilled(F)` ≡ `FieldFilled(F)`. Both duplicate arms are refused by the shared `DuplicateGate` at construction, so an illegal list never reaches the renderer; the exemption keys on whether the operand renders with a star, which is what makes the `Damages*` pair legal and the `Handler` pair not. Round-trip and runtime locks: `GroupListEntityOperandReadTest`, tri-engine `GroupListEntityOperandDiffTest`, JVM+Node `GroupListFieldOperandTest`; the typed refusals in `dsl.GroupListDuplicateGateTest`.

`NumberOfFilledGroups` has two different contracts. The plain form takes two or more groups and counts their definitely-filled states, but the result is unavailable when any operand group is formally erroneous; it never silently returns the count of only the admitted subset. The single-star form (`NumberOfFilledGroups(Items*)`) is a structural row count: every instantiated repetition counts, including an empty row or a row whose field value is formally invalid.

### 3.6 Study — a real, highly-repetitive rule, rebuilt (before → after)

**Where this comes from.** A12 models *forms*, and a real pattern in them is that the same logical block is captured in more than one place. Here (an **insurance** application submitted through a broker portal): the policyholder block restated on the **policy schedule** (`Schedule/*`) — a section captured apart from where the underlying name and address were entered — must stay **consistent with** the application data. The engine can't auto-sync across form sections, so a **consistency rule** enforces that the schedule block **mirrors** whichever source the application's flags select — *null-safe*, so a not-yet-filled block (both empty) is allowed. The source is a **2×2**: policyholder type (organization vs person → which **name**) × whether a separate correspondence address was given (correspondence vs residence → which **locality**).

Authored by hand, that consistency condition reads like this — the **full** rendered rule, wrapped for reading (the kernel sees one flat expression; this exact string is pinned in `MirroringRuleStudyTest`):

```text
GroupFilled(/Application/BrokerSubmission)
And AtLeastOneFieldFilled(/Application/Schedule/Name, /Application/Schedule/Zip, /Application/Schedule/City)
And (
  ( FieldFilled(/Application/PolicyholderIsOrganization) And AtLeastOneFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City)
    And ((AllFieldsFilled(/Application/OrgName, /Application/Schedule/Name) And [/Application/OrgName] == [/Application/Schedule/Name]) Or NoFieldFilled(/Application/OrgName, /Application/Schedule/Name))
    And ((AllFieldsFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip) And [/Application/Correspondence/Zip] == [/Application/Schedule/Zip]) Or NoFieldFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip))
    And ((AllFieldsFilled(/Application/Correspondence/City, /Application/Schedule/City) And [/Application/Correspondence/City] == [/Application/Schedule/City]) Or NoFieldFilled(/Application/Correspondence/City, /Application/Schedule/City)) )
  Or
  ( FieldFilled(/Application/PolicyholderIsOrganization) And NoFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City)
    And ((AllFieldsFilled(/Application/OrgName, /Application/Schedule/Name) And [/Application/OrgName] == [/Application/Schedule/Name]) Or NoFieldFilled(/Application/OrgName, /Application/Schedule/Name))
    And ((AllFieldsFilled(/Application/Residence/Zip, /Application/Schedule/Zip) And [/Application/Residence/Zip] == [/Application/Schedule/Zip]) Or NoFieldFilled(/Application/Residence/Zip, /Application/Schedule/Zip))
    And ((AllFieldsFilled(/Application/Residence/City, /Application/Schedule/City) And [/Application/Residence/City] == [/Application/Schedule/City]) Or NoFieldFilled(/Application/Residence/City, /Application/Schedule/City)) )
  Or
  ( FieldNotFilled(/Application/PolicyholderIsOrganization) And AtLeastOneFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City)
    And ((AllFieldsFilled(/Application/PersonName, /Application/Schedule/Name) And [/Application/PersonName] == [/Application/Schedule/Name]) Or NoFieldFilled(/Application/PersonName, /Application/Schedule/Name))
    And ((AllFieldsFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip) And [/Application/Correspondence/Zip] == [/Application/Schedule/Zip]) Or NoFieldFilled(/Application/Correspondence/Zip, /Application/Schedule/Zip))
    And ((AllFieldsFilled(/Application/Correspondence/City, /Application/Schedule/City) And [/Application/Correspondence/City] == [/Application/Schedule/City]) Or NoFieldFilled(/Application/Correspondence/City, /Application/Schedule/City)) )
  Or
  ( FieldNotFilled(/Application/PolicyholderIsOrganization) And NoFieldFilled(/Application/Correspondence/Zip, /Application/Correspondence/City)
    And ((AllFieldsFilled(/Application/PersonName, /Application/Schedule/Name) And [/Application/PersonName] == [/Application/Schedule/Name]) Or NoFieldFilled(/Application/PersonName, /Application/Schedule/Name))
    And ((AllFieldsFilled(/Application/Residence/Zip, /Application/Schedule/Zip) And [/Application/Residence/Zip] == [/Application/Schedule/Zip]) Or NoFieldFilled(/Application/Residence/Zip, /Application/Schedule/Zip))
    And ((AllFieldsFilled(/Application/Residence/City, /Application/Schedule/City) And [/Application/Residence/City] == [/Application/Schedule/City]) Or NoFieldFilled(/Application/Residence/City, /Application/Schedule/City)) )
)
```

Look at what that is: the same null-safe-equality idiom `(AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b)` **hand-written 12 times** across **4 near-identical branches** — a `FieldFilled`/`FieldNotFilled` × `AtLeastOneFieldFilled`/`NoFieldFilled` cross-product whose 2×2 structure is invisible in the flat text. A one-field change is four edits; the polarity is re-judged on every line; a wrong paste between branches is undetectable by eye. This is what we want to stop writing — and the rest of this guide is how.

**After** — the build that produces *exactly* the string above (the test asserts the full rendered output, so the rebuild is provably faithful):

<!-- snippet: study-mirroring -->
```java
// the fixed target block — the policyholder block restated on the policy schedule
List<StringRef> target = List.of(m.str("/Application/Schedule/Name"),
        m.str("/Application/Schedule/Zip"), m.str("/Application/Schedule/City"));

// the two binary axes that SELECT the source block
StringRef isOrg = m.str("/Application/PolicyholderIsOrganization");
List<FieldRef> correspondence = List.of(
        m.field("/Application/Correspondence/Zip"), m.field("/Application/Correspondence/City"));

// assemble the source block from the two axis choices (organization or person? correspondence address?)
BiFunction<Boolean, Boolean, List<StringRef>> sources = (org, useCorrespondence) -> List.of(
        org ? m.str("/Application/OrgName") : m.str("/Application/PersonName"),
        useCorrespondence ? m.str("/Application/Correspondence/Zip") : m.str("/Application/Residence/Zip"),
        useCorrespondence ? m.str("/Application/Correspondence/City") : m.str("/Application/Residence/City"));

// every target field mirrors its source, null-safe — the inner 3× repetition collapses to a zip + fold
BiFunction<List<StringRef>, List<StringRef>, Condition> mirrors = (src, tgt) -> allOf(
        IntStream.range(0, tgt.size()).mapToObj(i -> src.get(i).agreesWith(tgt.get(i))).toList());

// the 4 branches ARE the 2×2 cross-product — a loop, not four copy-pasted blocks
List<Condition> branches = new ArrayList<>();
for (boolean org : new boolean[] {true, false}) {
    for (boolean useCorrespondence : new boolean[] {true, false}) {
        Condition selector = (org ? isOrg.filled() : isOrg.notFilled())
                .and(useCorrespondence ? atLeastOneFieldFilled(correspondence) : noFieldFilled(correspondence));
        branches.add(selector.and(mirrors.apply(sources.apply(org, useCorrespondence), target)));
    }
}

Condition rule = m.group("/Application/BrokerSubmission").filled()
        .and(atLeastOneFieldFilled(target.stream().map(StringRef::field).toList()))
        .and(anyOf(branches));
```

**What collapsed:**

| in the hand-written rule | in the build |
|---|---|
| the null-safe idiom, 12× | `a.agreesWith(b)` — written, and its polarity reviewed, **once** (39) |
| each branch's 3 mirror clauses | a `mirrors` zip folded by `allOf(List)` (38) |
| the 2×2 as four pasted blocks | a `for`×`for` over the two axes — the structure is now *visible* |
| the presence guard over a field set | `atLeastOneFieldFilled(List)` (38) — no `toArray` splat |
| add a field pair → 4 identical edits | one entry in `target` + each source block |

~15 lines instead of ~40, correct by construction (each pair type- and scale-checked, auto-parenthesized). As always (§3.4a) this is authoring-time only — it **renders to the identical flat kernel DSL**, so it stays exactly what the kernel accepts.

#### Going parametric — the same shape, reused across rules

The build above still tangles the rule's *shape* (a context guard → "the target has data" → an OR of guarded mirrors) with *this* rule's data. Extract the shape **once** as parametric combinators:

<!-- snippet: study-mirror-combinators -->
```java
/** Null-safe block mirror: each target field agrees with its source (both filled & equal, or both empty). */
static Condition mirrors(List<StringRef> sources, List<StringRef> target) {
    return allOf(IntStream.range(0, target.size())
            .mapToObj(i -> sources.get(i).agreesWith(target.get(i))).toList());
}

/** One guarded source alternative: when {@code when} holds, the target must mirror {@code sources}. */
record SourceChoice(Condition when, List<StringRef> sources) {}

/** The whole rule SHAPE, parameterized: a context guard + "target has data" + an OR of guarded mirrors. */
static Condition mirrorRule(GroupRef context, List<StringRef> target, List<SourceChoice> choices) {
    return context.filled()
            .and(atLeastOneFieldFilled(target.stream().map(StringRef::field).toList()))
            .and(anyOf(choices.stream().map(c -> c.when().and(mirrors(c.sources(), target))).toList()));
}
```

Now any rule of this kind is just **data + a call**. A different one — *the premium-payer block must mirror either the policyholder or a designated third-party payer* — is its target, its guarded sources, and `mirrorRule(...)`:

<!-- snippet: study-mirror-reuse -->
```java
// the premium-payer block must mirror either the policyholder or a designated third-party payer
StringRef byThirdParty = m.str("/Application/PaidByThirdParty");
List<StringRef> payer = List.of(m.str("/Application/Payer/Name"), m.str("/Application/Payer/Iban"));
Condition payerRule = mirrorRule(m.group("/Application/BrokerSubmission"), payer, List.of(
        new SourceChoice(byThirdParty.filled(),
                List.of(m.str("/Application/ThirdParty/Name"), m.str("/Application/ThirdParty/Iban"))),
        new SourceChoice(byThirdParty.notFilled(),
                List.of(m.str("/Application/Policyholder/Name"), m.str("/Application/Policyholder/Iban")))));
```

**Location-independent, too.** The combinators take refs, never paths, so a rule can be written as a function of its **root group** — navigate *relative* to a `GroupInfo` (finding 40) and the same rule applies to any subtree (a mounted/included sub-form), not one hardcoded mount point:

<!-- snippet: study-mirror-relative -->
```java
/** The flagship rule as a function of its ROOT group — navigates RELATIVE to {@code app} (GroupInfo), so
 *  the same rule applies to any {@code /Application} subtree (a mounted/included sub-form), not one path. */
static Condition scheduleConsistency(GroupInfo app) {
    List<StringRef> schedule = List.of(app.str("Schedule/Name"), app.str("Schedule/Zip"), app.str("Schedule/City"));
    StringRef isOrg = app.str("PolicyholderIsOrganization");
    List<FieldRef> correspondence = List.of(app.field("Correspondence/Zip"), app.field("Correspondence/City"));
    List<SourceChoice> choices = new ArrayList<>();
    for (boolean org : new boolean[] {true, false}) {
        for (boolean useCorrespondence : new boolean[] {true, false}) {
            choices.add(new SourceChoice(
                    (org ? isOrg.filled() : isOrg.notFilled())
                            .and(useCorrespondence ? atLeastOneFieldFilled(correspondence)
                                    : noFieldFilled(correspondence)),
                    List.of(org ? app.str("OrgName") : app.str("PersonName"),
                            useCorrespondence ? app.str("Correspondence/Zip") : app.str("Residence/Zip"),
                            useCorrespondence ? app.str("Correspondence/City") : app.str("Residence/City"))));
        }
    }
    return mirrorRule(app.group("BrokerSubmission"), schedule, choices);
}
```

Applied to `/Application`, `scheduleConsistency(...)` reproduces the flagship rule above **byte-for-byte** (the test asserts it), so the extraction is faithful, not a lookalike. This is the §3.2/finding-24 endgame: a team accrues a small library of **rule-shaped combinators**, and each new rule of a known shape supplies only what differs — restrained to plain functions over the shipped primitives (`agreesWith` 39, `allOf`/presence-`List` 38, relative navigation 40), no templating DSL.

### 3.7 A few more operand shapes the type system steers

The same handle discipline extends to operands that used to force a drop to a hand-written string. Each renders to the exact kernel-accepted stored form (locked to a kernel-run test) and stays type-safe.

**Date-like `Min`/`Max`.** `Dsl.min`/`Dsl.max` are overloaded for DATE, TIME, and DATETIME operands. The result preserves that exact family (`DateExpr`, `TimeExpr`, or `DateTimeExpr`), so number/temporal and cross-temporal mixing cannot be written. The kernel evaluates every temporal form through its `DateCombiner` (findings 58 and 92, KERNEL-FINDINGS §6):

<!-- snippet: date-minmax -->
```java
// Min/Max preserve a homogeneous temporal family: DATE → DateExpr, TIME → TimeExpr,
// DATETIME → DateTimeExpr. Mixing families does not compile.
DateExpr earliestDate = Dsl.min(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
TimeExpr earliestTime = Dsl.min(m.time("/Order/PickupTime"), Dsl.time("09", "00", "00"));
DateTimeExpr latestInstant = Dsl.max(m.dateTime("/Order/ScheduledAt"),
        m.dateTime("/Order/CompletedAt"));
```

**Date/Date-time ordering shares one instant timeline.** A DATE reads as model-zone midnight, so the typed operands admit `<`, `<=`, `>`, and `>=` across DATE and DATETIME in either direction. Cross-kind equality is deliberately absent: the kernel rejects `DATE == DATETIME`, even though it admits their ordering.

<!-- snippet: temporal-cross-ordering -->
```java
Condition startsAfterOrderDay = m.dateTime("/Order/ScheduledAt")
        .after(m.date("/Order/OrderDate"));
Condition orderDayNotAfterCompletion = m.date("/Order/OrderDate")
        .onOrBefore(m.dateTime("/Order/CompletedAt"));
```

**A temporal field's DECLARED FORMAT decides which typed handle it has, not its kind.** A12's format vocabulary is kind-independent, and so is every gate below the declaration: a `TimeType` field declared `yyyy-MM-dd` carries the Gregorian floor, compares directly against a DATE, and answers `YearFromDate`, while its own kind's `HoursFromTime` is refused (KERNEL-FINDINGS [KF211](KERNEL-FINDINGS.md#kf211), measured across the complete format × kind grid on both codegen strategies). So `date(path)`/`time(path)`/`dateTime(path)` accept a field whose declared format carries that component set, **in addition to** the matching kind — additive on purpose, since refusing a kind-matching field on its format alone would be a client-side refusal with no measured kernel basis. `FieldInfo.format()` reads the declaration that decided it. You meet this on models you did not author: an imported or hand-edited model that stores a date in a `TimeType` field is legal, and before this its rules were authorable only as raw condition strings. And **`FieldInfo.temporalClass()` reads the declaration's verdict** — which classifier the field actually has (`DATE`, `TIME` or `DATE_TIME`), whatever `kind()` says.

<!-- snippet: temporal-declared-format -->
```java
// A12 reads a temporal field's DECLARED FORMAT and never its kind, at every gate below the
// declaration — so a TimeType field declared `yyyy-MM-dd` IS a date: it carries the Gregorian floor,
// compares against a DATE, and answers the date extractors. The typed handles follow the declaration,
// so `date(...)` accepts it and no raw condition string is needed for a model that arrives this way.
Condition promisedAfterShipping = fulfilment.date("/Fulfilment/PromisedDay")
        .after(fulfilment.date("/Fulfilment/ShippedOn"));
NumberExpr promisedYear = Dsl.yearFromDate(fulfilment.date("/Fulfilment/PromisedDay"));
// The rule reaches the CONSTRUCTORS too: such a cell is an admitted DateRange endpoint, and a field
// declared `yyyy` is admitted at Date(...)'s complete-Year position, on any date-bearing kind.
Condition windowFollowsPromise = fulfilment.dateRange("/Fulfilment/DeliveryWindow")
        .ne(Dsl.dateRange(fulfilment.date("/Fulfilment/PromisedDay"),
                fulfilment.date("/Fulfilment/ShippedOn")));
// …and the entity-list EXTREMA: MaxValue/MinValue compare their operands' declared component sets,
// so this cell is a legal member of a plain DATE list. No factory refuses the mixture; the kernel
// arbitrates whether the sets agree.
Condition latestIsPromise = Dsl.latestOf(fulfilment.field("/Fulfilment/PromisedDay"),
        fulfilment.field("/Fulfilment/ShippedOn")).eq(fulfilment.date("/Fulfilment/PromisedDay"));
// Read the declaration that decided it; `HoursFromTime` on the same cell is refused by the kernel.
String declared = fulfilment.fieldAt("/Fulfilment/PromisedDay").orElseThrow().format().orElseThrow();
// …and `temporalClass()` is that declaration's verdict: which classifier the field actually has,
// whatever its kind says. Use it to DISPATCH on a model you did not author, never to refuse.
ValueKind classifier = fulfilment.fieldAt("/Fulfilment/PromisedDay").orElseThrow().temporalClass();
```

**Reading an AST instead of writing Java? The same gate is available over a bare `Calculation`.** The typed handles above cannot be handed a wrong operand, because `BoundModel` only issues one for a field whose declaration fits — but a consumer translating parsed condition text holds a `Calculation`, which carries a `kind()` and no format. `Dsl.dateExtraction(model, unit, source)` and `Dsl.dateTimePart(model, unit, source)` close that gap: they resolve the operand's declared format through the model and refuse exactly what the kernel refuses — an extraction whose component the format does not carry ([KF426](KERNEL-FINDINGS.md#kf426): `DayFromDate` over a `yyyy-MM` field is `MVK_WRONG_DATE_FORMAT_FOR_OP`, over `yyyy-MM-dd` it is fine). They are named for the family rather than per operator because that path holds the unit as a value. An operand with no declaration to read — an expression, a literal — is **delegated**, not refused. *Test-backed: `dsl.UntypedTemporalExtractionGateTest`, whose expectation is computed from the rule over the whole admitted format vocabulary — registered with that vocabulary's owner so a short list cannot pass — against the kernel measurement in `validate.laws.TemporalExtractionFormatGateLawsTest`. The snippet below is locked to it.*

<!-- snippet: untyped-temporal-gate -->
```java
// A caller reading a parsed AST holds a bare Calculation, which carries a kind and no format. These
// factories resolve the operand's DECLARED FORMAT through the model and refuse exactly what the
// kernel refuses — a `yyyy` fragment answers YearFromDate and nothing below it.
Calculation fragment = model.field("/Probe/Fragment").value();
Calculation year = Dsl.dateExtraction(model, DateExtraction.Unit.YEAR, fragment);

// MonthFromDate over the same operand is MVK_WRONG_DATE_FORMAT_FOR_OP: `yyyy` carries no month.
assertThrows(IllegalArgumentException.class,
        () -> Dsl.dateExtraction(model, DateExtraction.Unit.MONTH, fragment));
```

**A second, independent gate on the same factory: declared date PRECISION.** The format gate above asks whether the declared format carries the component at all; this asks whether a value written with that component is allowed to leave it unknown. A `DateType` may declare `datePrecision` — `DAY_OPTIONAL`, `MONTH_OPTIONAL`, `YEAR_OPTIONAL` — which makes it a *partially known date* (`00.02.2024` under `dd.MM.yyyy`), and the kernel then admits an extraction only for a component the precision GUARANTEES: `DAY_OPTIONAL` guarantees the year and month, `MONTH_OPTIONAL` the year alone, `YEAR_OPTIONAL` nothing. A refusal is `MVK_INVALID_DATE_TYPE`, a different code from the format gate's, and a field can pass one gate and fail the other. Absence of the declaration means `FULL` — A12 omits the key for the kernel default — so a complete date is never refused here. As with the format gate, an operand with nothing to read is **delegated**, and the rule is silent on the time components because a `DateType` carries none. *Test-backed: `dsl.DatePrecisionExtractionGateTest`, whose expectation is computed from the rule over the whole 4x4 precision-by-unit grid, against the kernel measurement in `validate.laws.DateExtractionLawsTest`. The snippet below is locked to it.*

<!-- snippet: date-precision-gate -->
```java
// The SECOND gate on the same factory, and independent of the first: the declared FORMAT says which
// components a value is written with, the declared PRECISION says which of them may be unknown. A
// DAY_OPTIONAL date is written `dd.MM.yyyy` and still admits `00.02.2024`.
Calculation manufactured = model.field("/Probe/ManufacturedOn").value();
Calculation month = Dsl.dateExtraction(model, DateExtraction.Unit.MONTH, manufactured);

// DayFromDate over the same operand is MVK_INVALID_DATE_TYPE: the precision admits an unknown day.
assertThrows(IllegalArgumentException.class,
        () -> Dsl.dateExtraction(model, DateExtraction.Unit.DAY, manufactured));
```

**A constructed date range is an equality operand as well as a computation value.** `Dsl.dateRange` takes two `DateOperand` values but accepts only owner `entitySpec` shapes: a **date-bearing or date-fragment** field, directly, through a semantic index, or with the `$` outer-iteration marker. What the kernel's endpoint gate actually reads is the **declared format**, so a TIME- or DATE_TIME-declared field carrying `yyyy-MM-dd` is admitted here and the kind check stops at the kinds measured refused ([KF271](KERNEL-FINDINGS.md#kf271)). It does not require a *complete* date format, and it does not pre-check partial `datePrecision` — both are built and left to the kernel. It refuses calculated dates such as `Today` and date shifts before rendering; the kernel remains the format-compatibility oracle because DATE and DATE_FRAGMENT may be mixed when their date-component sets match. The kernel admits `==` and `!=` against a DATE_RANGE field in either direction and between two constructions; it rejects ordering and does not admit a nested construction inside the overlap predicates. `DateRangeOperand` exposes exactly that result domain, including `DateRangeExpr` results such as a date-range `FirstFilledValue`. A DATE_RANGE field is also a computation TARGET and a rule ERROR FIELD through its own typed handle — `m.computation(m.dateRange(path))` and `.errorField(m.dateRange(path))`, the peers of the other per-kind overloads (DEV-API-GAPS DG37). Those two surfaces are held in lockstep by `ErrorFieldComputationParityTest`: the first version of DG37's fix reached only the computation half, and the parity lock is what caught it. Two range operands must additionally expose the **same declared date-component set**, whichever side is stored, constructed, or computed: a `yyyy` range against a full `yyyy-MM-dd` one is `MVK_INVALID_COMPARE_TO_DATE_RANGE` in either order (`DateRangeComparabilityLawsTest`). **`m.rangesEqual(a, b)` / `m.rangesNotEqual(a, b)` pre-check that**, reading the declared sets of a stored range and of a construction's endpoints; a computed or index-selected operand is delegated, and a declared base year does **not** relax it. The bare `a.eq(b)` stays unchecked — that is the form the inbound converters need. **The construction itself has a second, separate law**: its two endpoints must declare the same date components, and the kernel raises `MVK_WRONG_DATE_FORMAT_FOR_OP` *at the construction*, naming the offending endpoint. `m.dateRangeOf(start, end)` pre-checks that (`dsl.DateRangeEndpointComponentGateTest`); the comparison gate cannot, because a construction whose endpoints disagree exposes no profile to compare and is delegated. `Dsl.dateRange` stays ungated for the same reason `eq` does. **`m.baseYear()`** and its three peers — `baseYearNumber()`, `startOfBaseYear()`, `endOfBaseYear()` — pre-check the one rule the constant really has: it is legal only in a model that DECLARES a base year, else `MVK_NO_BASE_YEAR`, measured uniform over all six carriers the kits can build (`dsl.BaseYearDeclarationGateTest`). It is a different rule from the FORMAT gate over the same constant, which also exists: the declaration is a fact the model states, while the constant's FORMAT is its kind's default — measured to behave exactly as a date field declaring none, so month/day/quarter extraction is admitted and the time family refused. Assigning it `yyyy`, the shape it reads like, would refuse legal models. `Dsl.baseYear()` and its peers stay ungated for the inbound converters. **`m.dateOf(day, month, year)`** is the peer for the `Date(...)` construction: a NUMBER component must declare a lower bound and an upper bound equal to its SLOT's maximum, which is per-slot and exact rather than containment — a `1..12` field is refused in the DAY slot although every value it holds is a legal day (`dsl.DateConstructionSlotBoundsGateTest`). A lower bound other than 1, a STRING component's digit pattern, and the year slot's `yyyy` exception are delegated as unmeasured, so the gate never refuses them. The typed `FirstFilledValue` family also covers TIME and DATETIME (`firstFilledTime`/`firstFilledDateTime`); an empty selection produces a value only for NUMBER (zero), while every other admitted kind remains unspecified and clears a computation.

<!-- snippet: date-range-equality -->
```java
var requestedWindow = Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"));
Condition windowChanged = m.dateRange("/Order/CoverageWindow").ne(requestedWindow);
Condition requestedWindowChanged = requestedWindow.ne(
        Dsl.dateRange(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate")));
var firstObservedWindow = Dsl.firstFilledDateRange(m.flatten("/Order/Items*/ItemCoverage"));
Computation coverageWindow = m.computation(m.dateRange("/Order/CoverageWindow"))
        .name("CoverageWindowComp").calc(requestedWindow.expression())
        .message(Dsl.en("Coverage window follows the order and delivery dates.")).build();
Rule windowMismatch = m.rule(m.group("/Order")).name("CoverageWindowMatchesOrder")
        .errorField(m.dateRange("/Order/CoverageWindow")).when(windowChanged)
        .code("COVERAGE_WINDOW_MISMATCH").severity(Severity.ERROR)
        .message(Dsl.en("The coverage window must span the order and delivery dates.")).build();
```

**`FirstFilledValue` takes owner entity selectors, not merely a field list.** Each `FirstFilledOperand` is one field or group target with composable owner modifiers: category projection, semantic index, wildcard filter, and outer correlation. A plain group may use `.outer()` for correlation inside `Having`; A12 rejects every semantic index inside `Having`, so `having(...)` refuses both literal- and field-indexed outer groups with `MVK_SEMANTIC_INDEX_NOT_ALLOWED`. The owner expands group and category selectors in model order, then scans the resulting cells and moves to the next operand only while no filled value has been found. Choose the factory for the result family: mixed String/Custom/Enumeration operands belong in `firstFilledString`, while `firstFilledEnum` is deliberately enum-only; DATE and DATE_FRAGMENT share the date family; Boolean is supported by `firstFilledBoolean`, while Confirm is owner-rejected. `firstFilledGroup(path)` and `firstFilledGroupFor(path, key)` derive and validate a group's homogeneous result family from the model rather than asking callers to invent one. The typed owner also mirrors A12's static entity-list gate: wildcarded slots may repeat, the first exact non-wildcard duplicate completed in authored order is refused before any strict group/descendant overlap, and its field-or-group path is the repair witness.

<!-- snippet: first-filled-owner-entities -->
```java
StringExpr firstLabel = Dsl.firstFilledString(
        m.enumOf("/Order/Priority").category("SpeedClass").firstFilledOperand(),
        m.str("/Order/CustomerName").field().firstFilledOperand());
NumberExpr firstIndexedQuantity = Dsl.firstFilledNumber(
        m.num("/Order/Items/Count").semanticIndex("SKU-1").index().firstFilledOperand(),
        m.num("/Order/Items/UnitWeight").semanticIndex("SKU-1").index().firstFilledOperand());
BoolExpr firstApproval = Dsl.firstFilledBoolean(
        approval.flatten("/Approval/Rows*/Approved"));
StringExpr firstBillingValue = Dsl.firstFilledString(
        m.firstFilledGroup("/Order/BillingAddress"));
```

**Date/Time construction uses a model-checked component union.** A supplied component is a `TemporalComponent`: a typed `FieldRef`, a numeric expression such as the matching date/time extractor, or a quoted lexical component from `Dsl.temporalComponent`. The lexical factory is deliberate: `Dsl.temporalComponent("01")` preserves `"01"` where routing through a numeric literal would normalize it. The real kernel validates a field's declaration for its position, so the typed surface keeps model-dependent legality authorable without pretending every field kind works everywhere: bounded Number fields, exact-width digit-pattern String fields, and an exact-`yyyy` Date field in the complete-year position are admitted. `Dsl.date` accepts three or four components.

<!-- teaser -->
```java
TemporalComponent day = m.field("/Parts/DayText");       // STRING: maxLength 2 + digit-only pattern
TemporalComponent year = m.field("/Parts/Year");         // DATE: exact format yyyy
DateExpr firstOfJanuary = Dsl.date(day, Dsl.temporalComponent("01"), year);

TimeExpr capturedClock = Dsl.time(
        m.field("/Parts/HourText"),
        Dsl.temporalComponent("30"),
        Dsl.secondsFromTime(m.time("/Parts/SourceTime")));
```

**Time construction uses a prefix.** `Dsl.time()` through `Dsl.time(hour, minute, second)` preserve the kernel's four legal arities. Omitted trailing components are fixed zero: `Dsl.time()` is midnight, `Dsl.time(Dsl.temporalComponent("10"))` is 10:00:00, and the two-component form is 10:30:00. The public `TimeConstruction` node therefore carries a copied `List<Calculation>` rather than three mandatory record slots; its former three-argument constructor remains as the source-compatible full-form convenience (findings 105–106 / IF232–IF234).

**Arithmetic on a kind-typed ref.** `plus`/`minus`/`times`/`dividedBy`/`toThePowerOf` sit directly on `NumberRef`, taking another ref, a `NumberExpr` or a `long`, and `Computation.Builder.calc` takes a `NumberExpr` beside its `Calculation` form. Both are sugar over `asNumber()`/`expression()` and render identically, so the calculation arm of a ref now reads like its comparison arm instead of widening to `NumberExpr` and back (DG60):

<!-- snippet: ref-arithmetic -->
```java
// Arithmetic reads off a kind-typed ref, like comparison does — no asNumber()/expression() round trip.
NumberExpr lineTotal = m.num("/Order/Quantity").times(m.num("/Order/UnitPrice"));
Condition mostlyBackordered = m.num("/Order/BackorderQuantity").times(2).gt(m.num("/Order/Quantity"));
```

**Rounding with an optional scale.** Both the expression `roundDown`/`roundUp`/`roundAccounting` family and its bare-field `…Value` siblings retain the kernel's optional decimal-places argument on the typed AST. The domain is `0..14`; every one-argument form means zero places. `RoundDown` is floor, `RoundUp` is ceiling, and accounting is `HALF_UP`, each after the kernel's scale-19 pre-round:

<!-- snippet: field-rounding -->
```java
// Expression and *Value forms both accept optional 0..14 places; omission means 0.
NumberExpr wholeUnitPrice = Dsl.roundAccounting(m.num("/Order/UnitPrice").asNumber());
NumberExpr roundedUnitPrice = Dsl.roundAccountingValue(m.num("/Order/UnitPrice"), 2);
Condition unitPriceNeedsRounding = m.num("/Order/UnitPrice").ne(roundedUnitPrice);
```

Persisted explicit zero is semantically identical to omission and the kernel parse tree does not retain that lexical distinction, so typed read-back canonicalizes `RoundDown(expr, 0)` to `RoundDown(expr)` and `RoundDownValue(F, 0)` to `RoundDownValue(F)`. Positive arguments such as `2` remain explicit.

**Category-based numeric coercion.** `FieldValueAsNumber` accepts either a direct field/enum or a typed enum category. The category form converts the category's selected token, not the stored enum token; the live kernel still validates the entire selected domain and derives its numeric scale:

<!-- snippet: category-number-coercion -->
```java
// A category projection converts the selected category token, not the enum's stored token.
NumberExpr multiplier = Dsl.fieldValueAsNumber(
        m.enumOf("/Tariff/Band").category("Multiplier"));
Condition premiumMultiplier = multiplier.ge("1.5");
```

**A category projection is a value expression, not only a quantifier member.** `CategoryRef` hands out its projection once per role: `listOperand()` for a value-list quantifier, `firstFilledOperand()` for `FirstFilledValue`, and `value()` for the plain value-expression slots a computation assigns and a comparison reads. `[field -> Cat]` is a *different operation* from `[field]` — it reads the field's value in the category's domain, and the kernel's static diagnostics against a computed field differ accordingly — so it must never be flattened to the bare field on either the write or the read side:

<!-- snippet: category-value-expression -->
```java
// A category projection is a value expression too, not only a quantifier member: `value()` is the
// Calculation peer of listOperand()/firstFilledOperand(), so a computation can assign one directly.
Computation direct = m.computation(m.enumOf("/Fulfilment/SelectedTier").field())
        .name("SelectedTierFromRequest")
        .calc(m.enumOf("/Fulfilment/RequestedTier").category("ServiceTier").value())
        .message(en("The selected tier follows the requested service tier.")).build();
```

Read-back preserves the projection in every value-expression slot the converter threads, and an operand position that is *not* yet threaded degrades the rule/computation to a verbatim `Opaque` naming the dropped projection — never to a converted-looking read of a different operation (finding 119).

**Presence over a semantic index.** `ref.semanticIndex(key)` is a dedicated handle for a semantic index (`[Field For key]`); `.filled()`/`.notFilled()` author its presence — rendered bare (no value brackets). Presence lives only on this handle, never on the `forIndex(...)` value expression, so the non-index misuse `sum(...).filled()` is a compile error rather than a runtime throw (finding 63, superseding finding 59):

<!-- snippet: indexed-presence -->
```java
// semanticIndex(key) is the presence handle for the field value a semantic index resolves to;
// .filled()/.notFilled() test its presence. (The VALUE for a comparison is forIndex(key).)
Condition present = m.num("/Order/Items/Count").semanticIndex("SKU-1").filled();
```

**A keyed DATE_RANGE cell keeps its own type.** `dateRange(f).forIndex(key)` yields a `DateRangeIndexedValue` rather than the kind-erased computed `DateRangeExpr`, which is what lets the bound extractors accept it while still rejecting `StartOfDateRange(FirstFilledValue(…))` — the kernel admits the first and refuses the second (KERNEL-FINDINGS [KF205](KERNEL-FINDINGS.md#kf205); DEV-API-GAPS DG39):

<!-- snippet: indexed-range -->
```java
// dateRange(f).forIndex(key) is the range cell a semantic index resolves to. It keeps its own type,
// so the bound extractors take it — which FirstFilledValue's computed range, deliberately, cannot.
DateRangeIndexedValue springRun = promo.dateRange("/Shop/Runs/RunWindow").forIndex("spring");
Condition startsOnCheckDate =
        Dsl.startOfDateRange(springRun).eq(promo.date("/Shop/CheckDate"));
```

The same `semanticIndex(...)` handle is the operand of `Dsl.fieldValueAsString`, so a semantic index's field value coerces to string — the kernel's `FieldValueAsString([Field For key])`, the peer of the plain `fieldValueAsString(field)` (D11):

<!-- snippet: indexed-coercion -->
```java
// fieldValueAsString also takes a semanticIndex(key) operand — the indexed field value coerced to
// string, e.g. to splice a keyed number value into a concatenation (the corpus shape).
Condition c = m.str("/Order/CustomerName").eq(
        Dsl.text("C-").plus(Dsl.fieldValueAsString(m.num("/Order/Items/Count").semanticIndex("SKU-1"))));
```

Both indexed shapes are bidirectional: `KernelAdapter.read` reconstructs the same `SemanticIndex` payload for foreign `FieldFilled([… For …])`, `FieldNotFilled([… For …])`, and `FieldValueAsString([… For …])` expressions instead of reducing them to the underlying field.

**An index-selected GROUP** (`Packages For "P1"`) is an `IndexedGroupValue`: `m.flattenGroup(path).forIndex(key)`, or `.forIndex(keyField)` for a key read from a field. It is an operand of the group-list quantifiers (`Dsl.noGroupFilled(p1)`), of the group-scoped field-fill quantifiers, where it and a plain scope are both a `GroupScope`, and of the aggregates, alone or beside other operands. Alone it satisfies an aggregate's arity (`Dsl.sum(p1)`, `Dsl.numberOfFilledFields(p1)`), exactly where A12 admits it, and `KernelAdapter.read` reads each of these shapes back with the key kept (`validate.laws.SemanticIndexGroupOperandCarrierLawsTest`, `adapter.SemanticIndexGroupOperandReadTest`). An index-selected FIELD, `m.num(path).semanticIndex(key).index()`, is a group-list operand too, asking whether the selected cell is filled ([AF167](API-FINDINGS.md#af167)):

<!-- snippet: indexed-group -->
```java
// forIndex(key) selects one row of a group scope, as semanticIndex(key) selects one cell of a field.
// The row is a GroupScope, so it stands beside a plain scope in the group-scoped field-fill quantifiers.
GroupListRef items = m.flattenGroup("/Order/Items");
Condition itemComplete = Dsl.allFieldsFilled(items.forIndex("SKU-1"), m.flattenGroup("/Order/ShippingAddress"));
// The selected cell is a group-list operand beside a field.
Condition anyGiven = Dsl.atLeastOneGroupFilled(
        m.num("/Order/Items/Count").semanticIndex("SKU-1").index(), m.field("/Order/CustomerName"));
```

**The rule's own group.** `Dsl.ruleGroup()` is the self-reference to the rule's containing group (the kernel's `RegelKontext`); it renders the reserved keyword (EN `RuleGroup`, DE `RegelKontext`), never a path. On a rule iterating a repeatable group it is the "is this row present" guard — the shape behind attachment/row-content rules (finding 60):

<!-- snippet: rule-group -->
```java
// Dsl.ruleGroup() is the rule's OWN group (the kernel's RegelKontext) — it renders the reserved
// keyword, never a path. On a rule iterating a repeatable group it guards "is this row present".
Condition attachment = Dsl.ruleGroup().filled()
        .and(Dsl.notExactlyOneFieldFilled(m.num("/Order/Items/Count").field(),
                m.num("/Order/Items/UnitWeight").field()));
```

**Starred value-list quantifiers.** The `*ValueList` quantifiers take a flattened repetition list (`BoundModel.flatten`) directly — the starred FIELDS side — the list-homogeneity kept by the `FieldOperand` type split:

<!-- snippet: starred-value-list -->
```java
// A value-list quantifier over a flattened repetition list — the starred FIELDS side.
Condition anyForbidden = Dsl.atLeastOneFieldValueIncludedInValueList(
        List.of("A", "B"), m.flatten("/Order/Items*/Sku"));   // …(/Order/Items*/Sku In "A", "B")
```

**…and over a GROUP scope, where the kernel expands the fields for you.** The same three quantifiers take a `GroupListRef` (`BoundModel.flattenGroup`) as their whole field side: the kernel expands the group to every field below it, recursing into nested subgroups. This is a **separate entry point** rather than another overload, and the reason is the type system rather than convenience — the overloads above are typed *by kind* (`StringRef`, `EnumRef`), which is how they erase `MVK_VARYING_TYPES_NOT_ALLOWED` without consulting a model, and a group has no kind to type them with. Its expansion's kind is the kernel's verdict, so this arm delegates that one gate instead of inventing a client-side copy of the expansion's extent:

<!-- snippet: group-scope-value-list -->
```java
// A value-list quantifier over a GROUP scope — the kernel expands the group's fields (recursively).
// No kind-typed overload: a group has no kind of its own, so the expansion's kind is kernel-arbitrated.
Condition addressPlaceholder = Dsl.noFieldValueIncludedInValueList(
        List.of("unknown", "n/a"), m.flattenGroup("/Order/ShippingAddress"));
```

**`numberOfValueInFields` — the single-list form.** The multi-field overloads take two-or-more `FieldOperand`s, but the kernel's arity gate is "at least two fields **OR** at least one group", so a **single flattened repetition list** is legal on its own (a starred list is a "group" operand). The `value × FieldListRef` overloads author exactly that — one per constant family (number/string/boolean) — and the list may carry a `Having` filter:

<!-- snippet: number-of-value-single-list -->
```java
// NumberOfValueInFields over ONE flattened repetition list — the single-list overload. The kernel's
// arity gate is "≥2 fields OR ≥1 group", so one starred list (a "group" operand) is legal on its own;
// the constant may be a number, string, or boolean, and the list may be Having-filtered.
Condition anyZeroCount = Dsl.numberOfValueInFields(0L, m.flatten("/Order/Items*/Count")).gt(0);
Condition anyZeroCounted = Dsl.numberOfValueInFields(
        0L, m.flatten("/Order/Items*/Count").having(m.num("/Order/Items/Count").ge(0))).gt(0);
```

**Enum value-list quantifiers — union admission + category projections.** An enum member may be a direct reference (`m.enumOf(path)`) or a category projection (`m.enumOf(path).category(name)`), and a single list may mix them. Each literal is admitted against the **union** of the selected members' domains (a direct reference's stored values plus a category's values), so a value in only one listed enumeration is legal — and a direct reference and a `[field -> Cat]` reference on the same field are distinct members:

<!-- snippet: enum-value-list-union -->
```java
// An enum value-list quantifier member may be a direct reference OR a category projection
// [field -> Cat]; each literal rides the UNION of the selected members' domains — "STANDARD" from
// Priority's stored values, "FAST" from the SpeedClass category (KERNEL-FINDINGS §8).
Condition standardOrFast = Dsl.atLeastOneFieldValueIncludedInValueList(
        List.of("STANDARD", "FAST"),
        m.enumOf("/Order/Priority"),
        m.enumOf("/Order/Priority").category("SpeedClass"));
```

## 4. Authoring a rule end-to-end

### 4.1 The rule object — slots from typed handles

<!-- teaser -->
```java
Rule rule = m.rule(addons)                        // model-aware entry: rule lives in a group by construction
        .name("AddonFeeRequiredRule")             // the element name (the guide originally forgot it)
        .errorField(fee)                          // errorEntityRelPath computed — "../MonthlyFee"
        .when(SubscriptionIdioms.feeRequired(tier, fee))
        .code("ADDON_FEE_REQUIRED_FOR_PRO")
        .severity(Severity.ERROR)
        .message(en("A PRO subscription requires a monthly fee per add-on."),
                 de("Ein PRO-Abo erfordert eine Monatsgebühr je Zusatzoption."))   // F2a landed: interpolate via Dsl.fieldValue(name) → $Name.value$
        .build();   // pre-checks at build(): error field under the group; error-field-referenced
                    // (directly or via enclosing group ref — kernel-verified); the LOCALE LAW:
                    // a message for EVERY declared locale (MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING),
                    // none for undeclared ones (stricter than the kernel, which ignores them silently);
                    // header locales are arbitrary — Dsl.text("fr_FR", …) for anything beyond en/de sugar
// rule.draft() bridges to the F4 validator; rule.render(DE) renders the same AST in German.
// ConditionReferences.of(condition) — the build-check's collector — is the seed of F9's referenced-fields projection.
```

### 4.2 Render → validate → emit

<!-- teaser -->
```java
new DslRenderer().render(condition);                       // canonical EN, absolute paths (no group context)
rule.render();                                             // relative within one root; cross-root references stay absolute
new DslRenderer(ConditionLanguage.DE).render(condition);   // same AST, German keywords

ValidationResult vr = new RuleValidator().check(modelJson, draftRule);   // F4: real kernel, no compile
vr.diagnostics().forEach(d -> System.out.println(d.code() + " " + d.fix()));  // F4a: code + catalog-joined fix

// Authored messages travel with the typed rule, so candidate validation also reaches the error-text checker.
DraftRule withText = rule.draft();
new RuleValidator().undeclaredMessageLocales(modelJson, withText);   // refuse these: the kernel never checks them

// F4a unified surface: KERNEL (span-pointed) + LINT warnings + gotcha INFO in one result
ValidationResult all = new RuleDiagnostics().diagnose(boundModel, rule);
System.out.println(all.toJson());                          // agent-parseable, one array, every origin (files-as-API)

BoundModel m2 = m.add(rule);                               // snapshot (F0): write → NEW binding, re-validated
m2.writeTo(path);                                          // DM-JSON write path — authored JSON, includes preserved (D9, F6)
```

Both `rule.draft()` and `computation.draft()` preserve their authored localized messages, including when rendering conditions in German. `RuleValidator` checks supplied text for the model's declared locales; an explicitly message-free draft retains the per-locale placeholders, and a partial draft replaces only the supplied locales. `CandidateMessageFidelityTest` locks message-only acceptance/rejection through the real kernel; `CandidateMessageCheckTest` covers rule/computation specs and pattern scaffolds. The older `DraftComputation` constructors remain message-free compatibility forms. See [AF144](API-FINDINGS.md#af144).

Ancestor group operands end on the parent-navigation segment: from `/Shipment/Parcels/Labels`, its parent renders as `..` and its root as `../..`. Neither carries a trailing slash. `AncestorGroupRenderingTest` checks both rendered forms against the kernel ([AF145](API-FINDINGS.md#af145)).

Relative paths never escape their root group: a rule under `/Dashboard` renders a typed reference to `/Dashboard/Status` as `Status`, while a typed reference to `/Application/Approved` remains `/Application/Approved`. The latter cannot be rendered as `../Application/Approved`; the DSL's parent navigation does not cross root-group boundaries.

**One reference can override that choice.** `absolute()` on a field or list ref keeps that reference absolute while everything else in the condition follows the rule's group. Reach for it when the spelling is the point, as with an aggregate that reads more clearly from the root; otherwise leave the renderer to choose, because the relative form is the kernel's preferred one and survives a group rename. A `RELATIVE` hint with no group to relativize against, or a `SHORT` one, refuses rather than silently emitting another spelling ([AF159](API-FINDINGS.md#af159)).

<!-- snippet: path-style-override -->
```java
// Rendered against /Project/Milestones/Tasks, only the hinted aggregate escapes relativization:
//   FieldFilled(Effort) And Sum(/Project/Milestones*/Budget) > 0
Condition mixed = m.field("/Project/Milestones/Tasks/Effort").filled()
        .and(Dsl.sum(m.flatten("/Project/Milestones*/Budget").absolute()).gt(0));
```

Typed persistence takes the stored condition language from the destination model. The AST remains language-independent; `BoundModel.add`, typed `ModelWriter` operations, and typed replacements render English or German only at the write boundary. Raw string/draft overloads remain verbatim for callers that already provide stored DSL text.

<!-- snippet: condition-language-write -->
```java
BoundModel model = germanModel();

BoundModel added = model.add(positiveAmountRule(model, "PositiveAmount"));
var amount = added.num("/Policy/Amount");
BoundModel replaced = added.replaceCondition(
        "/Policy/PositiveAmount",
        amount.filled().and(amount.gt(10)));
```

For a generated or profile-derived model, collect typed programs first and append the complete population in one immutable write. This preserves the same terminal consistency gate without multiplying whole-model parse and pretty-print work by the number of rules and computations:

<!-- snippet: bulk-program-write -->
```java
BoundModel written = model.addAll(
        List.of(positiveFee, billingPresent),
        List.of(effectiveFee));
```

### 4.3 A computation, same materials

<!-- snippet: computation -->
```java
Computation tiered = m.computation(m.num("/Subscription/Billing/EffectiveFee"))   // element placed in the
        .name("TieredFeeComp")                                                     // computed field's group;
        .commonPrecondition(fieldFilled(base.field()))                             // relPath computed
        .when(base.ge(100)).calc(base.value(), ToleranceBand.TR1)  // the selected tier keeps its own
        .when(base.lt(100)).calc(NumberLiteral.of("0.00"),         // implicit-validation tolerance;
                                 ToleranceBand.TR2)                 // absent means strict inequality
        .message(en("EffectiveFee follows the fee tiers."),
                 de("EffectiveFee folgt den Gebührenstufen."))   // a message per declared locale (the locale law)
        .build();
```

**The original sketch here used `.otherwise(...)` — that was kernel-illegal** (verified: mixing preconditioned and unconditional alternatives draws `MVK_ERROR_EMPTY_PRECONDITION`), so the API doesn't offer it: either one unconditional `calc(...)` or all-preconditioned `when(...).calc(...)`. The optional `ToleranceBand` overload carries one of the kernel's four closed `toleranceRangeOp` values on that alternative; it changes the computation's implicit validation from strict inequality to “difference exceeds N” and round-trips through read/modify/write. Further slot rules pre-checked at `build()` with the code named: the computed field must not appear in preconditions *or* operations (`MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` — both verified; a self-read carrying an enum category or a semantic index is the kernel's own separate class `MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD`, so the pre-check names that one instead and the repair is to drop the projection — [kf182](KERNEL-FINDINGS.md#kf182), finding 120), operation **kind** must match the computed field (`Calculation.kind()` — the typed AST now carries the §5b lattice), message required. Keep tier preconditions mutually exclusive: computation takes the first holding alternative in declaration order, but the generated validation rule retains every holding alternative's mismatch. With overlapping guards, the first operation can supply the computed value while a later operation still makes that stored value invalid. The linter warns on the always-overlapping identical-precondition case; true logical overlap remains the author/kernel's judgment. Result *scale* stays the kernel's call (D13).

**A computation LIVES in one group and WRITES to another.** `group(...)` declares the element outside the computed field's parent — the A12 shape a `*_Rules`-style container uses, where the policy is owned in one place and the value lands in another. It takes a `GroupReference`, so the group is model-resolved rather than a path string, and the stored `computedFieldRelPath` is derived from the element/target pair in both directions (`../Field` beside the field, a walk out and back down elsewhere). The default is unchanged: omit it and the computation lands beside its computed field exactly as before.

<!-- snippet: computation-group -->
```java
// A computation LIVES in one group and WRITES to another. `group(...)` declares the element in the
// policy container while it computes the stock block's field; the stored computedFieldRelPath is
// derived from the pair, never authored. The one refusal is CONTAINMENT UNDER DERIVED ITERATION:
// when the computation iterates, the computed field must lie at or below this group
// (MVK_ERROR_FIELD_NOT_IN_RULEGROUP). It iterates from a per-row operand OR from a repeatable target.
Computation floorPolicy = depot.computation(depot.num("/Depot/Stock/StockLevel"))
        .name("StockFloorPolicy")                        // → /Depot/Policy/StockFloorPolicy
        .group(depot.group("/Depot/Policy"))
        .calc(depot.num("/Depot/Policy/MinimumOnHand").value())
        .message(Dsl.en("Stock level follows the depot's minimum-on-hand policy."))
        .build();
```

Placement is an **iteration-scope** decision, not a filing one: the declaring group is the base the operand paths render against, and a runtime computation iterates where it *writes* rather than where it is declared ([kf19](KERNEL-FINDINGS.md#kf19)). The one refusal is **containment under derived iteration** — when the computation iterates, the computed field must lie at or below the declaring group, so an ancestor is fine and an unrelated sibling is not. Iteration is derived from a per-row operand of a repeatable declaring group *or* from a repeatable computed field, independently; the second needs no operand at all, so even a bare constant into a repeating target is refused from an unrelated group. The gate falls identically on rules and computations, though only a computation can exhibit that second source alone — a rule's error field must be referenced by its condition, so it is always an operand too. The measured tables are [kf212](KERNEL-FINDINGS.md#kf212). *Test-backed: `ComputationDeclaringGroupLawsTest` (the kernel's verdict across four declaring-group relations), `ComputationDeclaringGroupTest`, `CrossGroupComputationReadTest` (the placement survives read → write-back).* The CLI peer is the computation-spec's optional `group` key.

Off a `GroupEditor` the same override reads `editor.addComputation(target, c -> c.group(editor) …)` — `GroupEditor` **is** a `GroupReference`, so the path stays written once. It is an override there rather than the default, deliberately unlike `addRule`, which scopes to the editor's group: a rule's group *is* its iteration scope, while a computation's decides whether it runs once or per row, so defaulting to the editor would lift a computation authored over `editor.num("Lines/Amount")` out of a repeatable `Lines` and silently make it single. *Test-backed: `edit.GroupEditorComputationDeclaringGroupTest`.*

**Cross-slot reuse for free:** `SubscriptionIdioms.paidTier(tier)` drops unchanged into a rule `errorCondition` *or* a computation `precondition` — one expression language, many slots (§5). That is the concrete payoff of combinators + the slot model.

**The boolean constants are computation operations too, and CONFIRM is asymmetric.** `BoolLiteral` is an ordinary `Calculation`, so a boolean flag is computed through the same typed surface as any other kind — `m.computation(m.bool("/Claim/Escalated")).calc(new BoolLiteral(true))`. A **CONFIRM** target accepts only `True`: a confirm has no false, and the kernel rejects a `False` operation on one with `MVK_INVALID_COMPARE_TO_YES`, so "clear the confirmation" cannot be expressed as a computation. The builder used to refuse *both* constants into a CONFIRM target on plain operation-kind equality, which made the legal model unauthorable; it now permits `True` and refuses `False` carrying the kernel's own code rather than a kind-mismatch message. *Test-backed: `ConfirmComputationSourceKindLawsTest` (the kernel measured through raw operations that bypass the pre-check, plus a BOOLEAN polarity control and the both-directions agreement of the typed builder), `ComputationAuthoringTest`.*

When the model declares a base year, its numeric role is also a normal numeric operand: `Dsl.baseYearNumber().plus(1)`, `Dsl.roundDown(Dsl.baseYearNumber())`, and `Dsl.abs(Dsl.baseYearNumber())` produce the configured scale-0 year through arithmetic or wrappers. The same constant remains date-valued through `Dsl.baseYear()` in explicitly temporal expressions; a derived numeric component such as `Dsl.yearFromDate(Dsl.baseYear())` can be wrapped in the same way. Omitting the model configuration is rejected as `MVK_NO_BASE_YEAR`. *Test-backed: `BaseYearArithmeticDiffTest`, `TemporalNumericWrapperDiffTest`, `BaseYearComputationTest`, `TemporalNumericWrapperTest`, and `BaseYearArithmeticLawsTest`.*

**`errorCodesToSuppress` — the kernel's model-level escape hatch.** A computation may declare codes to suppress; the builder carries them, **domain-checked** against the kernel's supported set (currently only `MVK_INVALID_COMPARE_DEC_PLACES`, the static assignment-scale gate) — an unsupported code is refused fail-fast, not written. Its sole use today is letting a *scale-mismatched* operation load (finding 61):

<!-- snippet: computation-suppress -->
```java
// errorCodesToSuppress — the kernel's model-level escape hatch (domain-checked): it lets a
// scale-mismatched operation load (the static assignment-scale gate, MVK_INVALID_COMPARE_DEC_PLACES).
NumberExpr product = m.num("/Order/UnitPrice").asNumber().times(m.num("/Order/UnitPrice").asNumber());
Computation comp = m.computation(m.num("/Order/BackorderQuantity"))
        .name("BackorderProjection")
        .calc(product.expression())              // scale-4 product into a scale-0 target
        .errorCodesToSuppress("MVK_INVALID_COMPARE_DEC_PLACES")
        .message(Dsl.en("Backorder derived from the unit price."))
        .build();
```

`KernelAdapter.readComputation` recovers the suppression list from the source `IComputation`, so the typed read → modify → write cycle preserves it together with the parsed calculation slots (finding 74).

---

## Part IV — The bidirectional edit loop

Read foreign rules back into the typed AST, modify them, edit structure and rules in one session, work across included models, run rules against data, carry comments through, and reach for ready-made validation patterns — every change gated by the real kernel.

## 5. Modify studies — read a rule back, re-express it

### 5.1 In-session transform

<!-- snippet: transform -->
```java
Condition widened = proNeedsFee.transform(node -> switch (node) {
    case ComparisonCondition c when c.operatorId().equals("Equal")
            && c.right() instanceof EnumValueLiteral(String v) && v.equals("PRO")
            -> tier.is("PRO").or(tier.is("ENTERPRISE"));
    default -> node;
});
// bottom-up; untouched subtrees keep reference identity; D14 ids make the guards readable
```

### 5.2 Read a foreign, BA-authored rule — the full loop, load to save

`read` is **kernel-mm-complete** (553/553 typed) — including `X And CustomCondition Y`, which converts to a typed, editable AST (the custom condition is the name-only `CustomConditionRef`). The loop below is the real API.

<!-- teaser -->
```java
// LOAD — deserialize → expand → checkConsistency → bind (F0); an immutable snapshot
BoundModel m = BoundModel.ofJson(Files.readString(Path.of("subscription.dm.json")));
KernelAdapter adapter = KernelAdapter.of(m);

// its errorCondition (hand-written): FieldFilled(MonthlyFee) And CustomCondition LegacyFeeCheck

// INGEST — kernel parses → ConditionConverter → rulekit AST; tolerant sealed result (never throws,
// never silent loss). An unmodeled construct (e.g. a date shift) would arrive as Opaque, preserved verbatim.
Condition ast = switch (adapter.read("/Subscription/Addons/LegacyFeeRule")) {
    case ReadResult.Converted c -> c.condition();                  // FieldFilled(MonthlyFee) And CustomCondition LegacyFeeCheck
    case ReadResult.Opaque o    -> throw new UnsupportedOperationException(o.reason());
};

// EDIT — compose (append a guard) …
Condition extended = ast.and(m.num("/Subscription/Addons/MonthlyFee").gt(0));

// … or transform surgically INSIDE the tree — e.g. rename the custom condition (now possible because
// it's typed, not opaque): CustomCondition LegacyFeeCheck → CustomCondition FeeEligibility
Condition renamed = ast.transform(node -> node instanceof CustomConditionRef
        ? Dsl.customCondition("FeeEligibility") : node);

// VALIDATE + PERSIST — snapshot semantics (F0): replaceCondition re-binds + re-checkConsistency’s,
// then a NEW model is written; the original binding stays valid.
BoundModel m2 = m.replaceCondition("/Subscription/Addons/LegacyFeeRule", renamed);
m2.writeTo(Path.of("subscription.dm.json"));   // SAVE — DM-JSON, the portable artifact
// (KernelAdapter.diff renders a before/after RenderedDiff for the human gate; see the F8 row.)
```

```text
- FieldFilled(MonthlyFee) And CustomCondition LegacyFeeCheck
+ FieldFilled(MonthlyFee) And CustomCondition FeeEligibility      (renamed via transform)
```

### 5.3 Explain — same projection for built and read rules

`ModelContext.explain(condition)` → `RuleExplanation` (JSON below). One mechanism, two consumers — and it works **identically on a rulekit-built condition and a converted-foreign one** (F10 read feeds it). The snippet below is the *real* output (`ExplainSnippetProbe`/`ExplainReadRuleTest`) of explaining the **read** foreign rule `FieldFilled(CustomerName) And Sum(Items*/Count) > 100` — parsed by the kernel, converted to a rulekit AST, then explained with no adapter-specific code.

The flat `operators` list answers *which* constructs appear; the **`tree`** answers *how they nest* — it walks the sealed AST so the And/Or structure and operator-wraps-operand relationships survive (the flat list loses them). The two are **normalized, not duplicated**: `operators` is the glossary (each distinct operator once, with meaning/gotchas); each tree node is just `{operator, text, children}` where `operator` **backlinks** into that glossary. Operator-bearing operands (functions/aggregates) become child nodes; value operands (fields, literals) stay in each node's `text`. So an operator used five times is described once, and the tree stays compact.

```jsonc
// new ModelContext(model).explain(read).toJson()
{
  "text" : "FieldFilled(/Order/CustomerName) And Sum(/Order/Items*/Count) > 100",
  "operators" : [
    { "id":"And",        "keyword":"And", "kind":"OPERATOR",  "meaning":"True iff both boolean operands are true.",
      "gotchas":["there is no 'Not' operator"] },
    { "id":"FieldFilled","keyword":"FieldFilled","kind":"PREDICATE","meaning":"True iff the referenced field currently holds a value.",
      "gotchas":["a field carrying a formal error makes FieldFilled and FieldNotFilled unavailable rather than treating the cell as empty, so 'FieldFilled(F) Or FieldNotFilled(F)' is not a tautology"] },
    { "id":"GreaterThan","keyword":">", "kind":"OPERATOR",  "meaning":"True iff the left operand is strictly greater than the right." },
    { "id":"Sum",        "keyword":"Sum","kind":"FUNCTION",  "meaning":"The sum of the listed number fields' values, typically over a repetition list.",
      "gotchas":["an UNSPECIFIED field counts as 0 — unlike MaxValue/MinValue, which ignore unspecified fields (KERNEL-SEMANTICS §9)"] }
  ],
  "references" : [
    { "path":"/Order/CustomerName", "kind":"STRING" },
    { "path":"/Order/Items/Count",  "kind":"NUMBER" }
  ],
  "iterates" : true,
  "iterationGroup" : "/Order/Items",
  // tree — structure only; each `operator` backlinks into the operators glossary above (meanings live there, once)
  "tree" : {
    "operator":"And", "text":"FieldFilled(/Order/CustomerName) And Sum(/Order/Items*/Count) > 100",
    "children" : [
      { "operator":"FieldFilled", "text":"FieldFilled(/Order/CustomerName)" },
      { "operator":"GreaterThan", "text":"Sum(/Order/Items*/Count) > 100",
        "children" : [ { "operator":"Sum", "text":"Sum(/Order/Items*/Count)" } ] }
    ]
  },
  "gloss" : "Uses And, FieldFilled, >, Sum over /Order/CustomerName, /Order/Items/Count; iterates within /Order/Items."
}
```

*Scope of condition-level `explain`:* per-reference **roles** (error-field / operand) and **severity** need the rule *envelope*, not just the condition, so they're out of this projection (tracked as PROJECT-DESIGN §F9 "rule-envelope enrichment"); the **`suspicious`** hints are available separately via `RuleDiagnostics`/`lint.Linter`; authored path-style is a render concern.

### 5.4 Re-express, diff, validate, write back

<!-- teaser -->
```java
Condition current = ((ReadResult.Converted) adapter.read("/Subscription/Addons/FeeCapRule")).condition();  // F10
Condition fixed   = current.and(m.num(FEE).filled());            // re-express: compose (or transform inside)
RenderedDiff diff = adapter.diff("/Subscription/Addons/FeeCapRule", fixed);   // F8 — before/after + lossiness
//  diff.unified() → "- [/…/MonthlyFee] > 1000  / + [/…/MonthlyFee] > 1000 And FieldFilled(/…/MonthlyFee)"
//  diff.lossiness() → ["…inline/trailing comments not preserved…"] (the leading comment is carried — §5f/finding 27)
ValidationResult vr = new RuleDiagnostics().diagnose(m, m.rule(g).errorField(fee).when(fixed)…build());  // F4/F4a
BoundModel m2 = model.replaceCondition("/Subscription/Addons/FeeCapRule", fixed);  // F8 #3 — new binding (F0), re-validated; persist via m2.writeTo(...) (§5.2)
```

Note how 3.3's fragment library closes the loop: **re-expression frequently means recognizing a known idiom** — the LLM maps a foreign rule onto `feeRequired(...)` and the diff shows the cleanup.

## 5b. Editing the model — structure *and* rules (modelkit + `EditSession`) <a id="editing-the-model"></a>

The use case in one place: **read a DM → add fields/groups → add/modify rules → maybe delete → write back.** modelkit (`ModelDocument`) edits *structure*; rulekit edits *rules*; they are siblings over `dm.core` that cannot see each other (`dm.modelkit ⊥ dm.rulekit`), so `dm.edit.EditSession` is the orchestration layer above both. Every edit is surgical on the **authored** DM-JSON tree (includes preserved, never inlined). The kernel-free `dm.core.ModelReader` owns structural parsing, include/type-def expansion, id normalization, and indexing; when the backend is present, its real `checkConsistency` verdict remains the semantic write gate, while a no-backend binding is explicitly structural-only and never presented as kernel-confirmed.

### Create a model from scratch (`DocumentModelDraft`)

Before there's a model to read or edit, the typed builder authors one. `DocumentModelDraft.named(id)` opens a builder; `.rootGroup` nests groups/fields with the same fluent shape the editors mirror; `.build().toJson()` yields **kernel-valid** DM-JSON, ready for `ModelDocument.read` / `EditSession`:

<!-- snippet: create-model -->
```java
String json = DocumentModelDraft.named("orders").locale("en_US")
        .rootGroup("Order", o -> o
                .field("CustomerName", FieldKind.STRING)
                .field("Quantity", FieldKind.NUMBER)
                .group("Shipping", s -> s.field("PostalCode", FieldKind.STRING)))
        .build()
        .toJson();                         // kernel-valid DM-JSON — ready to read/edit
```

The smallest valid model is just `named(id).locale(l).rootGroup(name, g -> {})` — an empty root group is accepted. For a type-def-only library, `DocumentModelDraft.typeDefModel(id)` (a consumer pulls it in via `importTypeDefs(ref)`). *This is the shape every test fixture is built with (e.g. `ModelDrafts`).*

The model-level legal charset accepts a singleton BMP character, an inclusive `x-y` range, or one atomic combined grapheme. The atomic entry remains one list item; its base and combining mark do not become independently legal. An absent/empty list selects the full BMP except surrogate code units, so it does not admit supplementary-plane characters:

<!-- teaser -->
```java
String decomposedEAcute = "e\u0301";
String restricted = DocumentModelDraft.named("permit").locale("en_US")
        .supportedCharacters("A-Z", decomposedEAcute)
        .rootGroup("Permit", p -> p.field("Name", FieldKind.STRING))
        .build().toJson();

EditSession charsetEdit = EditSession.read(restricted);
charsetEdit.config().supportedCharacters(decomposedEAcute);
ModelDocument.read(charsetEdit.validate().toJson()).config().supportedCharacters();
// ["é"] — exactly one atomic list entry
```

**The base year is settable and now clearable, on the same editor.** `EditSession.read(json).config().baseYear(2024)` declares the `modelInfo.baseYear` that resolves year-less dates, and `clearBaseYear()` removes it — the inverse that was missing through the whole stack until 2026-08-31, because `setBaseYear` took a primitive `int` while `baseYear()` returned an `Optional`, so absence was readable and unreachable. It matters to anyone building the *negative* control for a yearless-date experiment: a fresh model has no base year, but a model that has one could not be returned to that state without hand-editing its JSON. The CLI peer is `dmtool config modify --clear-base-year`.

`SupportedCharactersContract` is the shared typed/CLI definition gate: it rejects an empty entry, a surrogate, a reversed range, a plain multi-character non-grapheme, a combined entry above three UTF-16 units, and the kernel's ambiguous-overlap shape before mutation. `config modify --supported-character` and its `apply` peer return the same `RK_INVALID_CONFIG_VALUE` corrective, while the IF151 differential proves the runtime admits the whole configured grapheme but rejects its base, combining mark, reverse, and repeated fragments (finding 78).

### Read & enumerate an existing model

`ModelDocument.read(json)` reads the authored source tree — **kernel-free**, so a model with unresolved includes still reads — enumerates its structure name-path-addressed, and round-trips losslessly (the generic Jackson tree, not the kernel's `NON_EMPTY`/`NON_DEFAULT` typed serializer):

<!-- teaser -->
```java
ModelDocument doc = ModelDocument.read(json);
doc.id();                              // "subscription"
doc.locales();                         // [en_US, de_DE]
doc.config().decimalSeparator();       // "."   (+ timeZone / conditionLanguage / fieldRefByShortNameAllowed / supportedCharacters)
doc.includes();                        // [Include[alias=catalog, reference=catalog]]
doc.typeDefinitionIds();               // [ShortText, …]
doc.groupPaths();                      // [/Subscription, /Subscription/Addons, /Subscription/Billing]
doc.fieldType("/Subscription/Tier");   // Optional["EnumerationType"]  (raw kernel discriminator)
doc.fieldKind("/Subscription/Tier");   // Optional["ENUMERATION"]  (clean ValueKind name; typedef → resolved underlying)
doc.isRepeating("/Subscription/Addons"); // true
doc.enumValues("/Subscription/Tier");  // [FREE, PRO, ENTERPRISE]   typed read-back of the enum values
// constraint read-backs (peers of enumValues) — no raw config-block dig, the schema-block quirks stay hidden:
doc.fieldPattern("/Subscription/Code");          // Optional["[A-Z]+"]   (+ fieldMinLength/fieldMaxLength : OptionalInt)
doc.fieldMaxFractionalDigits("/Subscription/Fee"); // OptionalInt[2]     (+ fieldMinValue/fieldMaxValue : BigDecimal)
doc.typeDefinitionMaxLength("ShortText");        // OptionalInt[…]       the same peers for a leaf typeDefinition
doc.fieldConfigJson("/Subscription/Fee");        // Optional[{"number":{…}}]  the WHOLE per-kind block as JSON text
                                                 // (the `field read` render source; typeDefinitionConfigJson for typedefs)
doc.toJson();                          // content-equal to the source, idempotent, still kernel-valid
```
*Locked by `modelkit.ModelDocumentTest`.*

### Edit fields & groups

<!-- teaser -->
```java
ModelDocument.read(json)
    .addField("/Subscription/Addons", "Discount", FieldKind.NUMBER)   // DATE carries its default format
    .addRepeatableGroup("/Subscription", "History", 10)
    .removeField("/Subscription/AutoRenew")
    .removeGroup("/Subscription/Billing")
    .setRepeatable("/Subscription/Billing", 5)
    .validate();   // the kernel gate (checkConsistency); both rejection tiers → ModelConsistencyException
```
Add-ops reuse the build-from-scratch node emitter, so an *added* field is byte-shaped identically to a *built* one. A missing target path fails fast with `IllegalArgumentException`; the full constraint set rides `validate()`. *Locked by `modelkit.ModelEditTest`.*

Both `ModelDocument.validate(resolver)` and `EditSession.validate()` preserve the origin of a failure: malformed model input raises `ModelInputException`, an unresolved include raises `UnresolvedReferenceException`, and a diagnosed kernel expansion or consistency refusal raises `ModelConsistencyException`. An unrelated resolver exception propagates unchanged; it does not constitute a model verdict. The adapter recognizes A12's `DocumentModelExpansionException` at the expansion call, rather than classifying every exception from a validation entry point as a rejection. *Locked by `ValidationFailureBoundaryTest` and `cli.EditGateRejectionTierTest`.*

`validate()` also leaves a **receipt**: `consistencyGate()` returns `Optional<Verification>` — `KERNEL_CONFIRMED` when the real oracle arbitrated, `STRUCTURAL_ONLY` in a backend-free build, and **empty** until the gate has run. It is state-checked, so an edit *after* the gate revokes it rather than vouching for a tree the kernel never saw; re-validating restores it. Read it when you publish provenance ("who confirmed this model") — never `Backends.verification()`, which answers the different question of whether a backend is on the classpath. `EditSession.consistencyGate()` is the same accessor over the session's one terminal gate. *Locked by `cli.EditGateReceiptTest`; the rationale is [finding 113](API-FINDINGS.md).*

An **index field** may be declared on a non-repeatable group as well as a repeatable one: `rootGroup("Single", g -> g.indexField("Key").field("Key", FieldKind.STRING))`. `GroupBuilder.indexField`, `ModelDocument.setIndexField`, and `GroupEditor.setIndexField` all require an existing direct-child field, without imposing repeatability. *Kernel acceptance and read/edit preservation: `modelkit.IndexFieldTest`; enumeration declaration and message-parameter distinction: `RuleMessageIndexTerminalLawsTest` ([KF280](KERNEL-FINDINGS.md#kf280)).*

A repeatable group can also declare a **display row sort** on the from-scratch builder — `GroupBuilder.sortField("Amount", SortOrder.DESC).sortField("Sku", SortOrder.ASC)` (multi-key, in declaration order). It fails fast on a non-repeatable group or a non-child field. It is display/USB-only — the kernel doesn't reorder rows for validate/compute (so the interpreter ignores it) — making it an authoring + read-fidelity slot (finding 51). *Locked by `adapter.GroupSortFieldTest` (kernel read-back).*

An enumeration index field can declare its **S-value** — the model-owned stored-token default an eligible clean-empty index cell receives during FULL validation and computation, and that partial validation silently suppresses (SPEC-2026-07-23-14/-15):

<!-- teaser -->
```java
.group("Lines", lines -> lines
        .repeatable(50).indexField("ServiceLevel")
        .enumField("ServiceLevel", e -> e.value("STD").value("EXPRESS").sValue("STD"))
        .field("OrderedQty", FieldKind.NUMBER))
```

The builder rejects a blank token and a token outside the declared values fail-fast (the kernel stages the S-value into evaluation *unvalidated*, so a typo would silently leak an out-of-domain token into rules). The key round-trips through the authored state (`EnumSpec.sValue()`) and the CLI field-spec (`enum.sValue`, `schema field add`). *Locked by `modelkit.EnumSValueTest` (kernel read-back + fail-fast); runtime semantics by `adapter.laws.IndexDefaultStagingDiffTest` + the KERNEL-FINDINGS §1/§12 supplement.*

### The coverage tail — doc config, type definitions, includes

<!-- teaser -->
```java
ModelDocument.read(json)
    .setDecimalSeparator(",").setTimeZone("UTC")                       // doc config (comma vs point)
    .addTypeDefinition("Money", FieldKind.NUMBER)                      // a reusable type
    .addTypedefField("/Subscription", "Credit", "Money")
    .addInclude("catalog", "catalog").mount("/Subscription", "Catalog", "catalog")  // an include + its mount
    .validate(resolver);   // includes validate against the referenced model via a resolver
```
*Locked by `modelkit.ModelStructureCoverageTest`.*

The time-zone slot has four authoring domains, exposed by `ModelTimeZoneContract.classify(slotPresent, value)`: an absent slot defaults to UTC; exact `UTC`/`GMT`/`Europe/Berlin` are interpreter-supported; a known Java zone such as `America/New_York` is kernel-valid but outside the current evaluator set; and blank/whitespace/unknown ids are kernel-invalid. The typed setters preserve the value for the kernel gate rather than silently normalizing it. dmtool reports an invalid id correctively and warns on a successful outside-set mutation before a later runtime verb refuses it (finding 72).

### Richer field types & element metadata

Beyond easy kinds: add a fully-configured field (`FieldTypeSpec` — string bounds/pattern, number scale, enum values + categories, date format/precision, a `TypeDefRef`), **re-type** an existing field, and set its metadata in place — requiredness, per-locale **labels** + **helper text**, descriptions, and name/value **annotations** on a field or group.

<!-- teaser -->
```java
ModelDocument.read(json)
    .addField("/Subscription", "Coupon",
        new FieldTypeSpec.StringSpec(3, 10, "[A-Z]{3,10}", "3–10 uppercase letters"))   // pattern + bounds
    .addField("/Subscription", "RiskBand", new FieldTypeSpec.EnumSpec(values, categories)) // enum + categories
    .setFieldType("/Subscription/AutoRenew", new FieldTypeSpec.ConfirmSpec(null, null))            // re-type a field
    .setLabel("/Subscription/Tier", Map.of("en_US", "Plan tier", "de_DE", "Tarifstufe")) // per-locale label
    .setHelperText("/Subscription/Tier", Map.of("en_US", "Affects pricing"))             // per-locale helper text
    .setAnnotation("/Subscription/Tier", "ui:widget", "dropdown")                        // name/value metadata
    .validate();
```
A label owes no locale coverage — answering one of several declared locales is legal, and a locale the model does not declare is written through rather than dropped ([KF406](KERNEL-FINDINGS.md#kf406); the all-or-nothing law is the enum-value one, [KF321](KERNEL-FINDINGS.md#kf321)); the add/re-type reuse the build-from-scratch emitter, so a configured field is shaped identically to a built one. *Locked by `modelkit.ElementLabelLocaleTest` (both the authoring and the edit path).* The pattern's error text can be **localized**: the single message in `pattern(regex, msg)` covers every declared locale, and `StringFieldBuilder.patternMessage(locale, text)` (CLI: `string.patternMessages`) overrides it per locale — the string sibling of an enum's per-locale invalid-value text, so a bilingual model carries a German pattern error (*locked by `FieldAddSpecTest`*). Each aspect has its own setter (`setRequiredness`/`setLabel`/`setHelperText`/`setGlobal`/…); `ModelDocument.applyModification(path, FieldModification)` applies a **partial** bundle of them at once (only the present aspects). The CLI splits that library bundle by contract: **`field modify <spec.json>`** owns the field's type and field-specific properties, while **`meta <ref>`** is the sole post-creation writer for labels, descriptions, and annotations across every describable element (G45). Thus an agent can add a STRING `pattern` or merge one locale of a label without delete-and-recreate, and an aspect it asks for is never silently dropped (CLI-SPEC [§5](CLI-SPEC.md#verb-surface)). `StringFieldBuilder.noValueValidation()` authors the value-skip flag (the attachment-content idiom), and the builder **fails fast** on the kernel's co-requirement — a no-value-validation string must permit line breaks (`MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK`) — with a named cause at `build()` rather than a late kernel reject (finding 47).

A Number has three distinct textual-size declarations in addition to scale and value bounds: `minLength(n)` / `maxLength(n)` count the rendered stored-number text (including sign and decimal separator), while `maxIntegerDigits(n)` counts only digits before the separator. Do not substitute the latter for `maxLength`: the kernel's `Date(...)` / `Time(...)` component gate consults `maxLength`. The same keys are accepted by the CLI's `number` block and returned by `field read` / `typedef read`; direct `FieldTypeSpec.NumberSpec` construction uses its fluent builder rather than a ten-position constructor (finding 104 / DG26).

A Date field's `DatePrecision` is monotone: `DAY_OPTIONAL` allows an exact `00` day, `MONTH_OPTIONAL` additionally allows month zero only after day zero, and `YEAR_OPTIONAL` additionally allows year zero only after both. Modelkit authors all three through `DateFieldBuilder.datePrecision`; the interpreter loads the same complete A12 enum and `ValueAsDate` resolves known-year earliest/latest boundaries without substituting year 2000 for an unknown year (IF233). A separate opt-in constraint rejects concrete values before 1900:

<!-- snippet: date-before-1900 -->
```java
DocumentModelDraft draft = permit()
        .rootGroup("Permit", group -> group
                .dateField("IssuedOn", d -> d.format("yyyy-MM-dd").rejectBefore1900())
                .dateField("ObservedOn", d -> d.format("yyyy-MM-dd")))
        .build();
```

This emits A12's `DateType.youngerThan1900Check`; the CLI peer is `"date":{"rejectBefore1900":true}`. It applies to stored values and any authorable computed target. Do not infer that a computation may target the partial precision shown above: kernel 30.8.1 rejects `DAY_OPTIONAL`, `MONTH_OPTIONAL`, and `YEAR_OPTIONAL` computation targets because the computation's generated implicit comparison reads the partial target as a value (`MVK_INVALID_DATE_TYPE`), and that code is not suppressible (finding 108 / IF237).

**That check requires the declared format to carry a YEAR**, and the requirement is the format's rather than the kind's: over the kernel's admitted vocabulary it is accepted on `yyyy`, `yyyy-MM`, `yyyyMM`, `yyyy-MM-dd`, `dd.MM.yyyy`, `yyyyMMdd` and `yyyy-MM-dd'T'HH:mm:ss`, and refused `MVK_ADDITIONAL_CHECK_INVALID` on the year-free `MM`, `MM-dd`, `MMdd`, `ddMM` and `HH:mm:ss`. Incompleteness is not the trigger — `yyyy` carries no day and is accepted. The builder does not pre-check it: the kernel's own diagnostic carries a curated corrective naming the accepted formats (`StructuralKernelFix`), and a client-side refusal would also fire on the READ path, making an inconsistent-but-present model unreadable rather than merely unauthorable ([KF251](KERNEL-FINDINGS.md#kf251)).

**The two BOOLEAN-valued kinds are declared with their own builders, and each can override the text it stores:**

<!-- snippet: boolean-stored-values -->
```java
DocumentModelDraft draft = DocumentModelDraft.named("subscription").locale("en_US")
        .rootGroup("Subscription", g -> g
                .stringField("Reference", s -> { })
                .booleanField("Paperless", b -> { })
                .booleanField("MarketingOptIn", b -> b.trueValue("J").falseValue("N"))
                .confirmField("TermsAccepted", c -> { })
                .confirmField("AutoRenew", c -> c.trueValue("Y").falseValue("N")))
        .build();
```

`booleanField` declares A12's three-state `BooleanType` and `confirmField` its two-state `ConfirmType`, whose unspecified value reads as False (§1). The difference is in EVALUATION rather than in configuration, which is why one `BooleanFieldBuilder` serves both: `trueValue`/`falseValue` emit `notInDCustomTrueValue` / `notInDCustomFalseValue` on either kind, and they are independent — override one and the other keeps A12's default. A bare declaration writes no inner block at all.

**The same check rides two further declarations, and a Number can be declared unsigned:**

<!-- snippet: unsigned-and-pre-1900-carriers -->
```java
DocumentModelDraft draft = permit()
        .rootGroup("Permit", group -> group
                .numberField("Deposit", n -> n.fractionDigits(2).positivesOnly())
                .numberField("Adjustment", n -> n.fractionDigits(2))
                .dateFragmentField("BilledMonth", f -> f.fragmentFormat("yyyy-MM").rejectBefore1900())
                .dateFragmentField("NotedMonth", f -> f.fragmentFormat("yyyy-MM"))
                .dateRangeField("CoverWindow",
                        r -> r.format("dd.MM.yyyy").separator("-").rejectBefore1900())
                .dateRangeField("NotedWindow", r -> r.format("dd.MM.yyyy").separator("-")))
        .build();
```

`positivesOnly()` emits A12's `NumberType.positivesOnly` (CLI: `"number":{"positivesOnly":true}`), which is the **only** input to the runtime's `hatVorzeichen` — a non-negative `min` bounds the value without changing the sign polarity. Two consequences, both measured: a minus is the formal error `zahlMitVorzeichen`, and an *empty* unsigned number can be filled bigger but never smaller, so a directional comparison over it types VALUE where a signed one types OMISSION. A minus on a **zero** magnitude reports the sign class rather than `zahlNullEnthaeltVorzeichen`, so the two are alternatives rather than a precedence pair.

`DateFragmentFieldBuilder.rejectBefore1900()` and `DateRangeFieldBuilder.rejectBefore1900()` (CLI: `"dateFragment"`/`"dateRange"` `{"rejectBefore1900":true}`) carry the check on the other two declarations. **They do not share the year co-requirement.** The fragment kind reproduces the DATE gate exactly, while a DATERANGE takes the check on every admitted `(format, separator)` pair — the year-free ones included, where it then measures the model's base-year completion. So if you need a year-free period guarded against pre-1900 dates, declare it as a range; there is no year-free DATE or DATEFRAGMENT that will hold the flag ([KF276](KERNEL-FINDINGS.md#kf276)).

A **date RANGE** adds one property beyond format and separator: `interpretationOfYear` decides which endpoint of a *wrapping* yearless range keeps the base year. Without it, an ordinary winter cover window (`01.11-31.01` on a `dd.MM` range) reads as start-after-end and is reported invalid. The direction is the reverse of what the names suggest — the value names the endpoint the base year **labels**, and the opposite endpoint moves:

<!-- snippet: date-range-year-interpretation -->
```java
DocumentModelDraft draft = DocumentModelDraft.named("policy").locale("en_US").baseYear(2026)
        .rootGroup("Policy", group -> group
                .dateRangeField("WinterCover", range -> range
                        .format("dd.MM").separator("-")
                        .interpretationOfYear(RangeYearInterpretation.TO)))
        .build();
```

`TO` keeps the end at the base year and rolls the START back a year; `FROM` keeps the start and rolls the END forward. `STANDARD` is the default and **has no DM-JSON spelling** — it is expressed by omitting the key, which is the kernel's own `NON_EMPTY` write behaviour, so passing it emits nothing. The declaration admits only `FROM` and `TO`; the tool API's `PREVIOUS`/`SUBSEQUENT` spellings are refused by the kernel, and the typed enum makes them unrepresentable rather than deferring to a runtime check. Reading an externally authored model recovers the property, and an unrecognized token **fails fast** rather than degrading to `STANDARD` — degrading would re-emit the field with the key gone, which is the silent drop the component exists to prevent. The CLI peer is `"dateRange":{"format":…,"separator":…,"interpretationOfYear":"TO"}` on `field add`/`field modify`/`typedef add`, echoed by `field read`/`typedef read` so the read block is itself a legal write input, and visible to `model diff`. *Test-backed: `modelkit.DateRangeYearInterpretationTest`; the runtime direction is pinned against both kernel strategies by `adapter.laws.WrappingYearlessRangeDiffTest`.*

A **group** carries a display label too — it is the kernel's `ILabeled` via `IEntity`, exactly like a field. The from-scratch builder authors it (`GroupBuilder.label("en_US", "Order lines").label("de_DE", "Bestellzeilen")`), the edit path sets it on an existing group (`setLabel(groupPath, …)`, CLI `meta <groupPath> --label`), and neither owes any locale coverage — the kernel accepts a label answering one of two declared locales, and one written solely for an undeclared locale ([KF406](KERNEL-FINDINGS.md#kf406)); the draft demanded full coverage until 2026-09-14, by analogy with the enum-VALUE law that really does require it ([AF149](API-FINDINGS.md#af149)). *Locked by `modelkit.GroupLabelTest` (kernel read-back) and `modelkit.ElementLabelLocaleTest`.*

### Rules *along with* structure — one session

`EditSession` threads one DM-JSON through both kits, and the **preferred idiom is the editor** (the L2c `GroupEditor`/`FieldEditor`/… — §5c): hold an editor on a group, edits chain, and a ref to a just-created field comes from relative navigation. The decisive case: a rule whose error field **is the field the structure step just added** — only kernel-valid if both edits composed over the same document.

<!-- snippet: edit-session -->
```java
EditSession s = EditSession.read(json);
GroupEditor addons = s.group("/Subscription/Addons");
addons.addField("Discount", FieldKind.NUMBER);                                 // structure (modelkit) — chains
NumberRef discount = addons.num("Discount");                                   // ref to the just-added field
addons.addRule(r -> r.name("DiscountNotNegative")                              // rule (rulekit), pre-scoped here
        .errorField(discount)
        .when(discount.filled().and(discount.lt(0)))
        .code("DISCOUNT_NEGATIVE").severity(Severity.ERROR)
        .message(Dsl.en("Discount must not be negative."), Dsl.de("Rabatt darf nicht negativ sein."))
        .build());
s.removeGroup("/Subscription/Billing");                                        // delete (modelkit, session-level)
String edited = s.validate().toJson();                                         // kernel gate, once
```
The editor authors against the **current** session state, so `addons.num("Discount")` resolves *only because* `addField` already ran; each rule edit re-binds the whole model as it applies (fail-fast), and `validate()` re-confirms once. *The editor flow is locked by `edit.GroupEditorTest`; the flat session verbs it forwards to — `s.addField(path, …)`, `s.addRule(m -> m.rule(m.group(path)) …)`, `s.removeGroup(…)` — by `edit.EditSessionTest`.*

The **whole rule lifecycle composes with structure edits** in the one session: `addRule` / `modifyRule(name, m -> newCondition)` / `removeRule(name)` / `addComputation` / `removeComputation` interleave freely with `addField` / `addGroup` / `removeField` / `removeGroup` / `setFieldType` / `setLabel` / `setAnnotation` — every edit reads the current DM-JSON and writes it back, so a rule can reference a field added earlier in the same chain, and `validate()` checks the whole result once.

### Delete safety — the gate, not a reimplementation

Removing a field a rule still references is rejected at **`validate()`** (`ModelConsistencyException`, the kernel's own dangling-entity diagnostic), **not** at `remove()` — which tolerates the invalid intermediate state so the same session can drop the rule too (the safe ordering: remove the rule, then the field). A *proactive* "what references this?" check (`adapter.whereUsed`) is deferred to the CLI remove verb, where the capability lives — never a silent cascade. *Locked by `edit.EditSessionTest.removingAFieldARuleReferencesIsRejectedByTheGate`; rationale in [`KERNEL-INTEGRATION-NOTES.md`](KERNEL-INTEGRATION-NOTES.md) "Structure edits + the delete-safety rule".*

## 5c. Refs & editors — the L2c ergonomic layer <a id="refs-and-editors"></a>

> **Status: implemented 2026-06-12 (PROJECT-DESIGN D7 / binding level *L2c*).** This is the agreed design, now built, and **replaces the dropped L2b codegen**. It spans **both kits** — rulekit rule authoring *and* modelkit structure editing. The session-bound handles are the **`Editor`** family — `GroupEditor` / `FieldEditor` / `RuleEditor` / `ComputationEditor` / `TypeDefEditor` / `IncludeEditor` / `ConfigEditor` — a **complete surface**: every editable element has an editor, so asking "what editors exist?" finds no gaps. The earlier working name *"cursor"* was dropped — naming decision + rejected alternatives (`Node`, `Handle`) in PROJECT-DESIGN §18.7 (fork #7). **No deferrals** (§18.7): `IncludeEditor` also does `setReference` (re-point) and `rename` (header + every mount move in step, so rules over mounted content don't ripple).

Today you reference an element by **string path**, which returns a typed handle (L2a): `m.num("/Order/Discount")`, `m.group("/Order")`, `addField("/Order", "Discount", NUMBER)`. The path is a string (a typo throws at *bind*, not at compile), and after an edit you **re-type the path** to reference what you just created. L2c makes **refs/pointers the preferred currency** — for fields *and* groups, existing *or* just-created — across both kits, staying pure runtime (no codegen).

**Two types — a pure value, and a session-bound editor.**

<!-- teaser -->
```java
// PURE VALUE REF — immutable (path + kind + scale/domain); carries operators; AST-safe.   (≈ jOOQ Field<T>)
NumberRef qty = order.num("Quantity");        // relative navigation off a group ref → an existing child
qty.lt(0);                                    // → an immutable Condition; no session involved

// EDITOR = value ref + the EditSession; carries the EDIT verbs and **chains** (each edit returns the editor);
//          downgrades to its pure ref for the AST.   (≈ jOOQ UpdatableRecord / active-record — but lighter)
```

**modelkit (structure) — FLUENT editor edits (they chain, mirroring modelkit's from-scratch builder); refs by navigation:**

<!-- teaser -->
```java
var s = EditSession.read(json);
GroupEditor order = s.group("/Order");             // hold ONE editor on the EXISTING /Order group …

// the editor CHAINS — each structure edit returns the editor, so editing an existing model reads exactly
// like the from-scratch DocumentModelDraft builder (.field(…).group(name, sub -> …)):
order.addField("Discount", NUMBER)
    .addField("LoyaltyPoints", NUMBER)
    .addGroup("Items", items -> items              // a nested-builder lambda for the new subgroup …
        .addField("Sku", STRING)
        .addField("Qty", NUMBER));                 // … same shape as building from scratch

// a ref to ANY child — new or existing — comes from relative navigation off that SAME editor:
NumberRef discount = order.num("Discount");        // … never a re-typed "/Order/..." path
GroupRef  items    = order.group("Items");
FieldListRef lines = order.flatten("Items*/Qty");  // star aggregate, relative — base prepended (DG11)
```
*(There is **no** session-as-actor / ref-returning duplicate — no `NumberRef d = s.addField(order, …)`. The editor's fluent edits + relative navigation are the **whole** editing story; the lower-level session mutator, if it exists at all, is an internal delegate the editor forwards to, not public surface.)*

**Relative navigation covers star aggregates too (DG11).** Alongside the scalar `num`/`str`/`group`/… nav, `GroupEditor.flatten(relativeStarPath)` / `flattenGroup(…)` (and the read peers on `GroupInfo`) yield a `FieldListRef`/`GroupListRef` with the editor's path prepended and the relative portion carrying its own `*`(s) — so `sum(order.flatten("Items*/Qty"))` needs no full `/Order/Items*/Qty` string. The base path lives once, in the editor.

**Build a `Rule`/`Computation` off the editor — with or without adding it (DG12).** `addRule(b -> …)` / `addComputation(field, b -> …)` build-AND-add to the session; `rule(b -> …): Rule` / `computation(field, b -> …): Computation` are the **compose peers** that build-and-RETURN the typed object without mutating the session. Either way the rule's error field + condition are refs and its `errorEntityRelPath()` (`"../…"`) + `render()` are **derived**, never hand-written — so a differential harness can author one typed `Rule` and feed it to two engines from a single source. (Both surfaced + filled by dogfooding the perf benchmark through the editor — DEV-API-GAPS DG11/DG12.)

**A computed target is any field kind — not only a number.** The editor's `computation(…)`/`addComputation(…)` take a `NumberRef`, a `StringRef` (a concatenation target), or the general `FieldRef` — mirroring `BoundModel.computation`'s per-kind overloads, so e.g. a computed display string authors through the same typed surface:

<!-- teaser -->
```java
var order = s.group("/Order");
order.addComputation(order.str("CustomerName"), c -> c.name("NameComp")
    .calc(order.str("ProductCode").asString().plus("-X").expression())
    .message(Dsl.en("Customer display name.")));
```

*(Surfaced by dogfooding the conformance corpus: its string-concat guard case couldn't be authored through the editor while only the `NumberRef` overload existed. Test-backed by `RuleComputationEditorTest.authorsAStringComputationThroughTheEditor`.)*

**Every held field, group, rule, and computation editor carries the same element-metadata contract.** Their shared `ElementMetadataEditor` base exposes annotation set/remove plus internal- and external-description set/remove, so a caller that has already selected `/Permit/CalculateFee` never drops back to the flat session or repeats that path just to describe it:

<!-- teaser -->
```java
s.computation("/Permit/CalculateFee")
    .setExternalDescription("en_US", "Calculates the permit fee from the requested area.")
    .setAnnotation("owner", "permit-pricing");
```

The complete fluent set is `setAnnotation` / `removeAnnotation`, `setInternalDescription` / `removeInternalDescription`, and `setExternalDescription` / `removeExternalDescription`. This is the typed peer of A12's common element-metadata contract rather than a special rule/computation feature. (`EditSessionEditorParityTest`, `ElementMetadataEditTest`; DEV-API-GAPS DG29.)

The same **per-type field builders** as the from-scratch builder are on the editor too (DG1) — configure scale/bounds/pattern/enum-domain + metadata fluently while editing, instead of dropping to a hand-built `FieldTypeSpec` (a field added this way is byte-identical to one built from scratch — one shared `FieldAttrs.toField` assembly):

<!-- snippet: editor-number-field -->
```java
s.group("/Order").numberField("Surcharge", n -> n.fractionDigits(2).min("0"));   // configured field, mid-edit
```

**The editor carries the full attribute surface too (F7).** Beyond *adding* structure, an editor sets every element aspect in place — the editor peers of the flat session verbs (§5b), so an existing field/group is fully configurable without re-typing its path. `GroupEditor` gains `setLabel` / `setRepeatable` / `setNonRepeatable` / `setIndexField` / `setSortFields` (alongside `setAnnotation` / `set{Internal,External}Description`); `FieldEditor` gains `setHelperText` / `setGlobal` / `setTransient` / `setRequiredErrorMessages` and `modify(FieldModification)` for a partial bundle. `EditSession` also gained `setModelId` (rename the model) and `inlineInclude(alias, includedJson)` — the inverse of `extractGroupAsInclude`, folding an include's content back into the base.

<!-- teaser -->
```java
s.group("/Subscription/Lines").setRepeatable(50).setIndexField("Sku")               // group config, mid-edit
    .setSortFields(List.of(new ElementDraft.SortField("Amount", SortOrder.DESC)));
s.field("/Subscription/Addons/MonthlyFee")                                          // field config, chaining
    .setHelperText(Map.of("en_US", "Monthly fee in EUR")).setGlobal(true);
```
*(Locked by `edit.EditorCompletionsTest`.)*

**rulekit (rules) — the *same* refs are the scope / error-field / operands:**

<!-- teaser -->
```java
s.addRule(m -> m.rule(order)                                // a GROUP ref as the rule scope …
    .errorField(discount)                                   // … a FIELD ref as the error field …
    .when(discount.filled().and(discount.lt(0))));          // … and as condition operands — never a string
```

**Both kits, one session** — the create→reference flow **codegen (L2b) could never do** (the field exists only at runtime):

<!-- teaser -->
```java
var order = s.group("/Order");                              // a group editor
order.addField("Discount", NUMBER);                         // modelkit: fluent structure edit (chains if more)
NumberRef discount = order.num("Discount");                 // ref to the just-created field, by navigation
s.addRule(m -> m.rule(order).errorField(discount)           // rulekit: the editor downgrades to its GroupRef …
    .when(discount.filled().and(discount.lt(0))));          // … and `discount` is a pure ref in the AST
s.validate().toJson();
```

> **The whole block runs today** — test-backed by `GroupEditorTest` / `FieldEditorTest` / `RuleComputationEditorTest` (2026-06-12): `EditSession.group(path)` → a `GroupEditor`, chaining `addField` (kind or `FieldTypeSpec`) / `addTypedefField`, relative navigation to pure refs across **every** kind (`num`/`str`/`date`/`time`/`dateTime`/`bool`/`confirm`/`enumOf`/…), the nested-builder `addGroup`/`addRepeatableGroup` lambdas, editor-scoped `addRule`/`addComputation`, and `m.rule(order)` taking the editor straight as the scope — it downgrades to its `GroupRef` internally (the AST stores only the pure ref) via a `GroupReference` type both `GroupRef` and `GroupEditor` satisfy (no `rulekit → dm.edit` dependency). The `addRule(…)` chain above is **abbreviated** (the full builder — `name`/`code`/`severity`/`message`/`build` — is in the test).

**The one safety rule:** the immutable AST holds **only pure value refs**, never the session-carrying editor — operators (`.lt`, `.filled`, `.eq`) live on the pure ref and produce immutable `Condition`s, so the session never leaks into a rule. (Functional core / effects at the edge — the same discipline that keeps jOOQ's `Field<T>` pure and Roslyn's `SyntaxNode` immutable.)

**Why value refs, not live nodes** (the fork considered):

| | value refs (**chosen**) | live nodes (rejected) |
|---|---|---|
| nature | immutable `(path, kind)`; the editor is a thin forwarder | mutable node bound to the session; tracks element identity |
| rename-aware | no — dangles if the element is renamed (resolved-on-use; fine for add→reference) | yes — but only by adding **live-ref rename-tracking** (rejected below; the model-level rename engine itself now ships, but auto-following a *held* ref needs a mutable graph) |
| cost / risk | small; fits the immutable-snapshot (F0) + immutable-AST grain | a parallel mutable object graph + aliasing/staleness; clashes with immutability |

Live nodes buy only **node-anchored chaining** and **rename-tracking** over value refs — and rename-tracking is exactly the heavy, parked part. Value refs get ~90% of the win within the paradigm that's already here.

**Reference map** (no familiarity with these assumed — just the lineage that makes this idiomatic):

| pattern | precedent | our use |
|---|---|---|
| immutable typed value ref | jOOQ `Field<T>` · QueryDSL Q-type · Roslyn `SyntaxNode` | the **pure ref** (AST coordinate + operators) |
| handle that edits through a held context | jOOQ `UpdatableRecord` · active-record | the **editor** (ref + `EditSession`) |
| *avoid:* context-managed, auto-tracked entity | JPA managed entity · DOM node | rejected — too heavy; needs rename-tracking |

**One idiom — no duplicate.** The **session-as-actor / ref-returning** shape (`s.addField(order, …)` returning a ref) is **dropped** from the public surface — there is exactly one way to edit: the editor (fluent edits + relative navigation). At most it survives as an *internal* mutator the editor delegates to, never taught. Deliberate non-goal (carried from D7): **no compile-time path checking** — paths resolve at runtime (a typo throws at bind), consistent with the BA/LLM persona (CLI, no compile step). Full decision + the dropped-L2b rationale: proposal **D7**; the binding-level taxonomy (L2a/L2c, L2b dropped) is in §16.

### 5c.1 Composition — parameterized document-part builders

The editor + flowing refs lift rulekit's **parameterized rule fragments** (`(refs, params) → Condition`; the `…Idioms` library, finding 11) to **parameterized document-part builders** — plain functions `(editor, params) → structure *and* rules` that take *where to build* and **return a ref** to what they built. They compose, and every result still passes the one `validate()` gate.

<!-- teaser -->
```java
// a configured "money" field + its bound rules — returns the ref so the caller can wire it into more rules:
static NumberRef money(GroupEditor g, String name, BigDecimal cap) {
    g.addNumberField(name, n -> n.fractionDigits(2));
    NumberRef f = g.num(name);
    g.addRule(r -> r.errorField(f).when(f.lt(0)).code(name + "_NEGATIVE") …);
    if (cap != null) g.addRule(r -> r.errorField(f).when(f.gt(cap)).code(name + "_OVER_CAP") …);
    return f;
}

// a reusable sub-structure, built N times under different names, configured per use:
static GroupRef address(GroupEditor parent, String name, Country c) {
    parent.addGroup(name, a -> {
        a.addField("Street", STRING).addField("City", STRING);
        a.addStringField("PostalCode", s -> s.pattern(c.postalRegex(), "bad postal code"));
    });
    return parent.group(name);
}

// compose — builders call builders; refs flow between them:
var order = s.group("/Order");
NumberRef base = money(order, "BaseFee", new BigDecimal("9999"));   // money() composed …
address(order, "ShippingAddress", Country.DE);
address(order, "BillingAddress",  Country.US);                      // … and reused under two names
```

**What you parameterize:** names (build the same component many times), refs (wire a rule around a passed field), kinds / scales / bounds / value-sets (configure the field), and **other builders** (a `lineItems(…)` calls `money(…)`; a `tieredFee(base, Tier…)` emits one computation alternative per tier). A team accrues a **document-component library** (`Money` / `Address` / `LineItems` / `AuditBlock`) and assembles models from it — restrained to **plain functions** (no templating DSL — the same "no query language" discipline as the navigation surface), each assembled result kernel-confirmed at the single gate. Finding 24.

## 5d. Working across models — includes & type-defs

Real apps compose models: a shared sub-model is **included** into a base and mounted at one or more places, and the base authors rules over the mounted fields exactly as if they were local. rulekit binds a model **with includes** by threading a kernel-free resolver (`EditSession.read(json, resolver)` / `BoundModel.ofJson(json, resolver)`) into `ModelReader`, which expands the structure, imports effective typedefs, and normalizes name-path ids; an available backend separately supplies the kernel consistency verdict. Mounting is a reference-aware **MOVE**: included rules/computations are bound before the move and their element references are remapped to the mounted path, while literals and comments are not rewritten. The retained authored JSON writes back **without inlining** the include — the `modelAlias` link is preserved (F6/D9). A field from an include is addressed by its **mount-name path** (`/Catalog/Retail/ListPrice` = the mount `Retail` + the included field `ListPrice`); a **type-def** field behaves as its underlying kind.

<!-- teaser -->
```java
// a shared price list — carrying its OWN rule (list price not negative)
EditSession plSession = EditSession.read(pricelistStructureJson);     // modelkit-authored structure
GroupEditor pl = plSession.group("/Pricelist");
NumberRef listPrice = pl.num("ListPrice");
pl.addRule(r -> r.name("ListPriceNonNeg").errorField(listPrice)
        .when(listPrice.filled().and(listPrice.lt(0)))
        .code("LIST_PRICE_NEG").severity(Severity.ERROR)
        .message(en("List price must not be negative.")).build());
String pricelist = plSession.validate().toJson();

// a base model that INCLUDES the price list at TWO locations + declares a Sku type-def
EditSession s = EditSession.read(catalogJson, resolver);              // resolver supplies "pricelist" → the JSON above
s.addInclude("pricelist", "pricelist", "/Catalog", "Retail");        // declare + mount #1
s.include("pricelist").mount("/Catalog", "Wholesale");               // mount #2 — same include, second place

// author rules over the MOUNTED fields (each mount its own copy) and the type-def field
GroupEditor catalog = s.group("/Catalog");
NumberRef retail    = catalog.num("Retail/ListPrice");               // relative navigation across the mount
NumberRef wholesale = catalog.num("Wholesale/ListPrice");
StringRef sku       = catalog.str("ProductSku");                     // STRING, via the Sku type-def
catalog.addRule(r -> r.name("RetailCap").errorField(retail)
        .when(retail.filled().and(retail.gt(1_000_000))) /* …code/severity/message/build… */ .build());
catalog.addRule(r -> r.name("WholesaleCap").errorField(wholesale) /* … */ .build());
catalog.addRule(r -> r.name("SkuFormat").errorField(sku)
        .when(sku.filled().and(sku.violates("[A-Z]{3}.*"))) /* … */ .build());
s.validate();                                                        // the resolver threads through the gate
```

The included price list's **own** rule travels with it — mounted twice, it applies at both `/Catalog/Retail` and `/Catalog/Wholesale` — and the base's rules over the mounted fields validate against the real, expanded model. An included model may also declare a type definition used by a field in the base; `DocumentModelDraft` defers that externally supplied reference to resolver-backed preparation just as it does for an explicit `typeDefinitions` import. A closed self-contained draft still rejects an undeclared id immediately. The written-back DM-JSON keeps the include as a `modelAlias` reference, never inlined; included content doesn't leak into the base. *Test-backed: `edit.MultiModelTest` (two-location include of a ruled model + rules over mounted fields + type-def), `rulekit.dsl.IncludesTypedefsRuleTest` (single mount + type-def, EN/DE render), and `modelkit.TypeDefModelImportTest` (include-contributed definition; finding 99).*

### 5d.1 Compare two model states headlessly (`ModelDiff`)

The structural semantic diff is a reusable `:adapter` library primitive; the CLI is only one renderer. `ModelDiff.between` binds both model states, ignores JSON formatting differences, and returns typed immutable changes with element kind, change kind, review-impact tier, reason codes, and per-aspect deltas. Stable `before()`/`after()` strings are ready for display and transport; additive `beforeValue()`/`afterValue()` accessors expose sealed natural values for headless consumers:

<!-- snippet: model-diff -->
```java
ModelDiff diff = ModelDiff.between(beforeJson, afterJson);

for (ModelDiff.ElementChange change : diff.changes()) {
    System.out.println(change.element() + " " + change.id() + " " + change.risk());
    for (ModelDiff.Delta delta : change.deltas()) {
        String typedAfter = delta.afterValue().map(value -> switch (value) {
            case ModelDiff.TextValue text -> text.value();
            case ModelDiff.BooleanValue bool -> Boolean.toString(bool.value());
            case ModelDiff.IntegerValue integer -> Integer.toString(integer.value());
            case ModelDiff.DecimalValue decimal -> decimal.value().toPlainString();
            case ModelDiff.StringListValue strings -> String.join(", ", strings.value());
        }).orElse("<removed>");
        System.out.println(delta.aspect() + ": " + delta.before() + " -> " + typedAfter);
    }
}
```

Fields, groups, rules, computations, typedefs, authored includes/imports, and model configuration are first-class element families. Fields carry declared/effective type, complete typed configuration, requiredness/messages, enum state, metadata, and flags; rules carry canonical condition, error entity, severity/code, localized messages, and metadata. A referenced field removal or retype is HIGH even when its reader is removed or rewritten in the same change. Group aspects include repeatability, index and ordered sort fields, mount alias/options, and metadata; child additions/removals are reported as their actual elements instead of a duplicate `children` summary. Reordering uses one authored mixed sequence, so a group moving relative to a field is visible. Computation aspects include the computed target, common precondition, ordered alternative preconditions/operations/tolerances, suppressions, localized messages, and verbatim stored DSL for an opaque computation. Typedef aspects include their complete sealed type/config and metadata; rename pairing is exact-only. Direct includes and type-definition imports expose purpose/alias/reference separately from provider content, while a mount-alias rewire is the corresponding group delta. The single stable `CONFIG model` change covers identity/version, locales, scalar settings, supported characters, base year, labels, comment, annotations, and roles; locales, supported characters, and roles are canonicalized as sorted JSON-array strings so source order does not create noise. Reference-rewriting field/group renames/moves are also returned as `refactors()`, with both rule and computation readers attributed. `risk()` is a fixed review-impact tier, not a probability estimate. For includes-bearing models in one workspace snapshot, pass its kernel-free resolver to `ModelDiff.between(beforeJson, afterJson, resolver)`. When the states belong to different revisions, worktrees, or service snapshots, use `ModelDiff.between(beforeJson, afterJson, beforeResolver, afterResolver)`; each side binds independently. A web service can serialize these records into its own transport contract without invoking the CLI. *Test-backed: `adapter.review.ModelDiffTypedDeltaTest`, `ModelDiffSideResolverTest`, `ModelDiffFieldCompletenessTest`, `ModelDiffRuleEnvelopeTest`, `ModelDiffReaderRiskTest`, `ModelDiffComputationTest`, `ModelDiffGroupTest`, `ModelDiffTypedefTest`, `ModelDiffIncludeTest`, `ModelDiffConfigTest`, the existing `ModelDiff*Test` family, `cli.ModelDiffTypedDeltaCliTest`, and `ModelDiffWorkspaceCliTest`; executable example: [`examples/cli-review.md`](../examples/cli-review.md).*

### 5d.2 Read authored field/group/rule state once (`ModelDocument`)

Consumers building structural tools should not call path-addressed getters for every element: each lookup walks the authored tree. `authoredState()` exposes one immutable snapshot collected in a single pass, with the mixed field/group/rule/computation order plus typed field, group, and rule lookups:

<!-- snippet: authored-state -->
```java
ModelDocument.AuthoredState authored = ModelDocument.read(session.toJson()).authoredState();
authored.elements().forEach(element ->
        System.out.println(element.type() + " " + element.path()));

ModelDocument.GroupState group = authored.groups().get("/Invoice");
System.out.println(group.structure().repeatability());

ModelDocument.FieldState field = authored.fields().get("/Invoice/Amount");
System.out.println(field.type() + " " + field.requirements().requiredness());

ModelDocument.RuleState rule = authored.rules().get("/Invoice/PositiveAmount");
System.out.println(rule.meta().annotations());
```

The snapshot is kernel-free and reads the authored source rather than an expanded include tree. A field carries a sealed `FieldTypeSpec`, requiredness/custom messages, and `FieldMeta`; a rule carries its authored descriptions/annotations while canonical logic and localized runtime messages stay on `KernelAdapter`. A mount group carries a typed `MountState` with `MountOptions`; mounted children remain owned by the referenced model. *Test-backed: `ModelDocumentAuthoredStateTest` and `ModelDocumentAuthoredFieldRuleStateTest`; consumed by the focused `ModelDiff*Test` families and the profile-shaped diff performance canary.*

Reusable declarations and model references stay typed as well:

<!-- snippet: authored-references -->
```java
ModelDocument document = ModelDocument.read(json);
ModelDocument.TypeDefinitionState sku = document.typeDefinitions().get("Sku");
Map<ModelDocument.ReferencePurpose, Map<String, String>> references =
        document.authoredReferences();
```

`typeDefinitions()` returns a document-ordered id map of `TypeDefinitionState` (`FieldTypeSpec` plus `DescriptiveState`), while `authoredReferences()` returns direct alias→reference maps grouped by `INCLUDE` versus `TYPE_DEFINITION_IMPORT`. Transitive references added by expansion are deliberately excluded. *Test-backed: `ModelDocumentAuthoredReferenceStateTest`.*

## 5e. Run rules against data — does it actually fire? (runtime evaluation)

Beyond "is this rule kernel-valid?" (the static gate) is "does it do the **right thing** on real data?" — the verify-by-execution angle that catches an *inverted-but-valid* rule. `KernelAdapter.evaluate` runs a model's rules over a **document instance** (a nested, name-keyed tree — repeatable groups as lists, field values as their kernel string form) and reports which rule **codes** fired; its peer `compute` reports what each field computes. (CLI: `model eval` / `model compute`.)

<!-- teaser -->
```java
String model = …;   // a model whose /Order/Discount carries a "DiscountNonNeg" rule (code DISCOUNT_NEG)

// a compliant document — the rule does NOT fire
EvalResult ok = KernelAdapter.ofJson(model).evaluate(Map.of("Order", Map.of("Discount", "0")), Locale.US);
ok.firedCodes();        // [] — a discount of 0 is not negative

// a violating document — the rule FIRES
EvalResult bad = KernelAdapter.ofJson(model).evaluate(Map.of("Order", Map.of("Discount", "-5")), Locale.US);
bad.firedCodes();                       // [DISCOUNT_NEG]
bad.messages().get(0).message();        // "Discount of -5.00 must not be negative." — the INTERPOLATED end-user text
```

So you can prove a rule *behaves* — run a passing and a violating document and read `firedCodes()` — not just that it parses. Each fired `EvalMessage` also carries `message()`: the kernel's **interpolated** end-user text — the authored template with its `$Field$`/`$Field.value$` tokens resolved against this very document (G1). That closes the loop on the BA's real deliverable: not "did it fire" but "here is exactly what the user will read". This is the basis of the eval's **verify-by-execution** judge (construct discriminating documents, compare what fires for a *gold* vs a *candidate* rule — equivalence by execution, not by recalling polarity/empty-operand semantics), and the natural close of the read→modify loop: change a rule, then *show* it now fires correctly on the cases that matter. *Test-backed: `adapter.RuntimeEvalExampleTest` + `adapter.MessageInterpolationTest`; the seam's depth (per-row repetition indices, `compute`, empty-operand semantics) by `adapter.laws.EvalDocSeamLawsTest`.*

### The kernel-free twin — load a model and evaluate it without the kernel (`ModelLoader` + `DmInterpreter`)

`KernelAdapter` above is the JVM-only path that *uses* the kernel. Its twin is [`:interpreter`](INTERPRETER-SPEC.md) — a **standalone, kernel-free, multiplatform (JVM + JS)** evaluator (the engine dmtool's native image runs, and a publishable Kotlin/JS library). Its `ModelLoader` reads an **expanded, self-contained** DM-JSON model straight into a `ModelDef` on **both** targets — so a JS (Node, the supported JS runtime) or JVM library consumer no longer hand-builds the model (the standalone-loader design observation, finding 57):

<!-- teaser -->
```kotlin
// tree API only (no @Serializable, no compiler plugin); a number's raw literal is preserved — a declared
// minValue of 100.50 stays "100.50" (never round-tripped through a Double/JS number).
val model: ModelDef = ModelLoader.load(dmJson)

// …build a Document for the instance, then evaluate with NO kernel on the classpath.
// validateFull fails loudly if the model contains an unsupported construct.
val fired = DmInterpreter(model).validateFull(document, "en_US").map { it.code }
```

Use `validate(document)` when a tolerant caller needs the full `ValidationOutcome` and will handle both `messages` and `unsupported`; projecting only `.messages` is an explicit decision to discard the unsupported report. dmtool's JVM `InterpreterModelLoader.load` is a thin delegate over this same loader — parity across every fixture model is CI-guarded (`ModelLoaderParityTest`), so the native runtime verbs and a JS library see identical `ModelDef`s. *Test-backed: `interpreter/…/load/ModelLoaderTest` (JVM + JS), `cli.interp.ModelLoaderParityTest`, `cli.interp.InterpreterRealModelDiffTest`.*

Validation-message field names are customizable without changing evaluation. The default uses the localized model label when present and otherwise the field name; a provider's non-null result wins verbatim, including an empty string, while `null` continues to the model label and indexed debug identity:

<!-- teaser -->
```kotlin
val interpreter = DmInterpreter(
    model,
    messageLabelProvider = MessageLabelProvider { field, locale ->
        applicationLabels[field.path]?.get(locale) // null keeps the interpreter fallback cascade
    },
)
```

`$Field.value$` deliberately uses the original stored display text even when evaluation uses a normalized value (for example CRLF becomes LF for a length condition). A missing or explicitly empty exact Number renders its declared `minFractionalDigits` default (`0`, `0.00`, …); the current `DocumentV2` kernel path uses a dot for that default independently of the message locale. Substitution is one pass, so placeholder-looking bytes inside a field value remain data. *Test-backed on JVM + JS by `MessageDisplayPolicyTest`, and against both real-kernel strategies by `MessageValueDisplayPolicyDiffTest`.*

The same façade computes, whole-document or one field at a time. `compute(document)` runs **every** computation (the twin of the kernel's `compute()`); `computeField(fieldPath, document)` runs **one** computed field "like a function" — only its transitive computed-dependency closure, in dependency order, so a cascade operand is never read stale (the single-computation twin of `testRule`; IF129). It returns one `Computed` per target-cell instance (a repeated computed field → one per row) and throws if no computation targets the field:

<!-- teaser -->
```kotlin
val interp = DmInterpreter(model)
val all = interp.compute(document)                                 // every computed instance (VALUE / CLEARED / ERRORED)
val amounts = interp.computeField("/Order/Lines/Amount", document) // just this field — one Computed per row, closure-pruned
```

*Test-backed: `interpreter/…/ComputeFieldInvariantTest` (JVM + JS — `computeField` == `compute` projected to the field, for every target/row/outcome) + `adapter.laws.ComputeFieldDiffTest` (== the real kernel's `compute()`, projected per field).*

## 5f. Comments — author `;;` notes, preserved across the loop

A rule's condition can carry a `;;` line **comment** — concrete syntax the kernel tolerates but does **not** keep in its parsed AST. rulekit carries comments in a **side-channel** beside the immutable AST (`rulekit.dsl.Comments`, keyed by *node identity*, covering both a `Condition` branch and a `Calculation` operation — the "carrier (c)" design, so the sealed hierarchies + their exhaustive switches stay untouched). The payoff: you **author** comments with the typed builder, and a rule's **leading comment is preserved by default** across read→modify→write-back.

<!-- teaser -->
```java
// a leading comment on a rule (also: commentAfter(...) for a trailing note; comment(node, "…") to
// annotate one branch of an And/Or by identity)
Rule rule = m.rule(m.group("/Subscription/Addons"))
        .name("FeeCap").errorField(fee)
        .when(fee.filled().and(fee.gt(1000)))
        .comment("cap the monthly fee per policy")          // ← rendered as a leading ;; line
        .code("FEE_CAP").severity(Severity.ERROR)
        .message(en("Monthly fee is too high."), de("Monatsgebühr ist zu hoch."))
        .build();
rule.render();   // ";; cap the monthly fee per policy\nFieldFilled(MonthlyFee) And [MonthlyFee] > 1000"

// computations carry comments too — on the operation AND on preconditions (hold the node to attach by identity)
Calculation passThrough = base.value();
m.computation(effectiveFee).name("EffFeeComp")
        .commonPrecondition(filled).comment(filled, "only when a base fee is set")
        .when(base.ge(100)).calc(passThrough).comment(passThrough, "tiered: pass the base fee through")
        .message(en("…"), de("…")).build();
```

Preserve-by-default closes the loop — re-express a commented rule's condition and the note rides along, **no manual sourcing**:

<!-- teaser -->
```java
// the stored rule already carries ";; cap the monthly fee"; tighten the threshold — the comment survives
EditSession.read(json)
        .modifyRule("/Subscription/Addons/FeeCap",
                m -> m.num("/Subscription/Addons/MonthlyFee").filled()
                      .and(m.num("/Subscription/Addons/MonthlyFee").gt(500)))
        .validate().toJson();        // still contains ";; cap the monthly fee", now with > 500
```

Only *inline/trailing* comments and non-canonical formatting are re-rendered away (no AST node anchors them across a re-expression) — `KernelAdapter.diff` discloses exactly that, carrying the leading comment on both sides. The CLI mirrors the surface: a `comment` key on `rule add` and `computation add` (the computation key leads its first operation), the matching `apply` rule/computation ops, and `rule modify --comment`. *Test-backed: `rulekit.dsl.RuleCommentTest`/`CommentRenderTest`, `ComputationAuthoringTest`, `adapter.CommentRoundTripTest`/`ConditionCommentLossinessTest`, `cli.AddVerbTest`/`ModifyVerbTest`/`ComputationVerbTest`; demo `examples/cli-edit-loop.md`. Background: KERNEL-FINDINGS "Condition comments".*

## 5g. Validation-pattern catalogue — idioms that bake in polarity + a valid error field

A BA does not re-derive "delivery can't precede the order" from scratch — they reach for the *date-order* idiom. The pattern catalogue (`rulekit.catalog.PatternCatalog`, the rule-level peer of `OperatorCatalog`) makes that an API: a named idiom, parameterized over a few field refs, that produces a **kernel-valid rule** with the two hardest traps solved structurally — the **violation polarity** (an A12 rule fires on the error scenario) and a **referenced error field** in scope (the `MVK_ERROR_FIELD_NOT_REFERENCED` law).

The five built-ins span the BA-doc's typical tasks: **date-order**, **required-when** (field-equals-value form), **mutually-exclusive**, **at-least-one-of**, **sum-of-line-items**. Each produces its condition through the **typed DSL** — so it's correct *by construction*, not merely rejected if wrong:

<!-- snippet: pattern-instantiate -->
```java
PatternEntry dateOrder = PatternCatalog.load().byId("date-order").orElseThrow();

// date-order scaffolds a RULE — instantiate against the model with the caller's field refs:
var it = (PatternEntry.RuleInstantiation) dateOrder.instantiate(boundModel,
        Map.of("earlier", "/Order/OrderDate", "later", "/Order/DeliveryDate"));

it.condition();    // "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(…) < 0"
it.errorField();   // "/Order/DeliveryDate" — referenced, in scope (a legal error field, baked in)
it.suggestedCode();// "DATE_ORDER"
```

The condition is built — not string-templated — inside the pattern: `Dsl.allFieldsFilled(earlier.field(), later.field()).and(Dsl.differenceInDays(earlier, later).lt(0))`. The `skeleton` string an agent browses (`${earlier}`/`${later}`) is illustration; the typed build is authoritative.

`DifferenceInDays` means signed complete **model-zone legacy-calendar steps**, not milliseconds or wall-label seconds divided by 86,400. Time of day and a DST-gap landing can therefore affect the count. `Date(...)` uses the same model-zone non-lenient legacy calendar (Julian through 1582-10-04, then the ten-label cutover hole, then Gregorian), distinct from the later 1583-10-16 floor applied to stored/computed values. Tooling that previews these expressions should surface that calendar/zone contract rather than silently assuming proleptic Gregorian arithmetic.

**Two design decisions worth recording.** *(1) Typed built-ins, data-extensible later.* The shipped idioms are code (typed fragments — correct by construction, composing the §5c.1 document-part builders), behind a `PatternEntry` **interface**. That interface is the seam a future *user* extension plugs into — a `${param}` data-template loaded from a workspace file and kernel-validated on load — so "users extend the catalogue" is additive, not a rewrite. The tension is real and was chosen consciously: typed = correct-by-construction but not user-authorable without recompiling; data = user-authorable but validated only at runtime (consistent with the BA persona's no-compile-step model, D7). *(2) The no-rot gate.* Exactly as the operator catalogue CI-validates every `validExample`, every pattern's `example` is instantiated and run through the real kernel (`PatternCatalogKernelTest`) — a pattern that no longer scaffolds a valid rule fails the build.

**CLI peer.** `dmtool patterns` lists the idioms, `patterns <id>` details one, and `patterns <id> --arg k=v …` scaffolds a rule-spec and **auto-checks** it (the G4 verdict folded in); `--apply` persists it through the same path as `rule add`. *Test-backed: `rulekit.catalog.PatternCatalogTest` (structure) + `PatternCatalogKernelTest` (the kernel no-rot gate); `cli.PatternsVerbTest`. Demo: `examples/cli-tour.md`.*

---

## Part V — Discovery & the agent loop

The semantics an agent reads — the queryable operator catalog, the model-context projections, model navigation — and the LLM session loop those feed, for both the developer and the BA persona.

## 6. Semantics as data — the catalog is queryable today <a id="semantics-as-data"></a>

<!-- snippet: catalog -->
```java
OperatorCatalog catalog = OperatorCatalog.load();
OperatorEntry e = catalog.byId("GroupFilled").orElseThrow();
e.meaning();      // "True iff the referenced group has admitted content. For a repeatable group the row itself is content, ..."
e.gotchas();      // ["'filled' means 'row exists' only for a repeatable group; ...", "a non-repeatable group containing only formally invalid values is neither filled nor definitely empty; ...", ...]
e.keywords().de(); // "KontextAngegeben"
e.invalidExample(); // dsl + the exact MVK_* + the fix — every example kernel-validated in CI (N7)
```

An LLM reads exactly this. The **self-sufficiency eval** (N6) is the runnable acceptance test: given only the catalog + skill + javadoc, author and correctly explain a rule per covered operator — where it can't, the material (not the LLM) is deemed incomplete.

**This is also why `Dsl` stays a flat factory.** A coding agent discovers an operator by *querying this catalog* (and grepping examples), not by browsing IDE autocomplete the way a human does. So the ~130 `Dsl.*` factory methods are deliberately **flat** — one import, greppable, `Dsl.addYears` with no "which sub-facade?" hop, and a single way to reach each node. Grouping them into `Dsl.dates().…` sub-facades would help a human's autocomplete but cost an agent an indirection and a dual surface, so the discovery investment goes into the catalog + consistent verb-prefix naming instead (the *why*: API-FINDINGS finding 52).

### 6b. The model-context surface (`context` package) — `describe`/`find`/`whereUsed`/`export`

The agent's entry point is usually a *name*, not a rule. Three projections compose the answer. (Shipped API is instance methods — `new ModelContext(m).describe()` / `.find(...)` / `.explain(...)`; the `Context.*` static shape below is the original sketch.)

<!-- teaser -->
```java
ModelCard card = Context.describe(m);             // 2026-06-04 — the agent's map, PROGRESSIVE: default is the
                                                  //    skeleton (group tree + counts/kind histograms — low-1000s
                                                  //    of tokens even on a 10k-field model); drill down per group:
ModelCard addons = Context.describe(m, m.group("/Subscription/Addons"));
List<FieldMatch> hits = Context.find(m, "birthdate");   // 2026-06-04
// → [/Customer/BirthDate (date, format dd.MM.yyyy, in group /Customer)]   — dumb string ranking, deliberately;
//    fuzzy/NL interpretation is the LLM's job

Set<String> rules = adapter.whereUsed(field);                   // 2026-06-07 exact (adapter) — validation rules
Set<String> comps = adapter.whereUsedInComputations(field);     // 2026-06-08 — computations, attributed separately
// → rules: [/Customer/AgeCheckRule, /Customer/BirthDateRule]   computations: [/Customer/AgeComp]
//   (a computation is also a rule in the kernel's getAllRules(); the two legs keep the kinds distinct — a field
//    read only inside a computation's operation shows up under computations, never under rules)

if (adapter.read(rule) instanceof ReadResult.Converted c) ctx.explain(c.condition());  // F9 explain → RuleExplanation
ctx.explainComputation(comp);                                   // 2026-06-08 → ComputationExplanation
```

The N8 seam: `context` lives in the public-tier core; the exact `whereUsed` comes from the Phase-2 adapter (`getDirectlyReferencedEntities`, one kernel call per rule, no converter needed). **Rules and computations stay attributed separately** — `KernelAdapter.whereUsed`/`ruleFullNames`/`readAll` are validation rules only, the computation peers are `whereUsedInComputations`/`computationFullNames`/`readAllComputations`/`readModel` (a computation is stored as a rule in `getAllRules()`, so the partition is deliberate; see KERNEL-INTEGRATION-NOTES "`getAllRules()` ⊇ computations").

**And for agents, the primary form is files** (owner direction — agents are trained to export-then-grep). The same projections, two persona surfaces (N10): the **in-repo developer** reads them programmatically off the navigation surface (`m.fields()`/`m.groups()`, §6c); the **modeller/BA's** agent runs the **CLI verb** `dmtool export` (which serializes the same projections to JSONL + a model card) — no Java toolchain.

```bash
# modeller/BA — the standalone CLI verb (JVM bundled; no jbang, no .m2):
# one-shot question — pipe (mind JVM startup: seconds, not git-milliseconds):
dmtool -m model.json export fields | grep -i birthdate
# repeated exploration — export once, then grep (the recommended loop):
dmtool -m model.json export --out-dir ctx/        # all artifacts
grep -i birthdate ctx/fields.jsonl
# {"path":"/Customer/BirthDate","kind":"date","format":"dd.MM.yyyy","group":"/Customer","repeatsUnder":null}
grep BirthDate ctx/rules.jsonl
# {"rule":"/Customer/AgeCheckRule","slot":"errorCondition","condition":"DifferenceInYears(BirthDate, Today) < 18","referencedFields":null,"resolution":"heuristic"}
```

The 10k-field problem dissolves: the files hold everything, only grep hits enter context; `model-card.md` (skeleton) is the single read-whole artifact. JSONL = one line per fact = greppable *and* `jq`/Python-parseable; each artifact stamps the snapshot's model id/version.

### 6c. Navigating the model — jOOQ-`Meta`-style, no query language <a id="navigating-the-model"></a>

The programmatic counterpart to 6b's files — and the machinery beneath them (`fields.jsonl` *is* `m.fields()` serialized). Streams of rich **info handles**; matching is plain Java; narrowing bridges back to strict authoring handles:

<!-- snippet: navigation -->
```java
// iterate + match — Stream.filter IS the matcher (no custom matcher API, no XPath-for-DM):
List<FieldInfo> money = m.fields()
        .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
        .toList();

m.fields(m.group("/Subscription/Addons"))        // scoped to a group
        .forEach(f -> System.out.println(f.path() + " : " + f.kind()));
long subtree = m.descendantFields(m.group("/Subscription")).count(); // every depth: a group operand's expansion

Optional<FieldInfo> peek = m.fieldAt("/Subscription/Tier");   // non-throwing — exploring is lenient,
List<String> tierValues = peek.orElseThrow().allowedValues(); // strict enum-only, values without categories
NumberRef fee = money.getFirst().asNum();                      // narrowing is strict (authoring guarantees)

// which makes rule templates (§3.4) one stream pipeline:
List<Rule> rules = m.fields()
        .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
        .map(f -> m.rule(f.group()).name(f.name() + "NonNegative").errorField(f.asNum())
                .when(f.asNum().filled().and(f.asNum().lt(0)))
                .code("NON_NEGATIVE_" + f.name().toUpperCase()).severity(ERROR)
                .message(en(f.name() + " must not be negative."),               // a message per declared
                         de(f.name() + " darf nicht negativ sein."))            // locale (the locale law)
                .build())
        .toList();
```

Safe by F0's snapshot semantics (immutable binding → internal memoization, clients never index); **Level 2** (glob paths) stays parked behind named triggers — see finding 9. **Relative navigation** off a known group landed (finding 40, §3.6): `m.groupAt(path)` → `GroupInfo`, then `app.str("Schedule/Name")`/`group`/`field`/… resolve relative to it — the read peer of `GroupEditor`, so a combinator can take a root group and apply to any subtree. (The *static* codegen-refs counterpart, once mooted as L2b, was **dropped** — PROJECT-DESIGN D7; the ergonomic layer **L2c** — runtime value refs + a session-bound editor, across both kits — replaces it (§5c).)

**Reading annotations closes the write/read asymmetry (finding 37).** `FieldInfo` (and, symmetrically, `GroupInfo`) now exposes the element-level `annotations()` it could already *write* (`EditSession.setAnnotation`), plus `annotation(name)` as the matcher — the pair share a top-level `Annotation` record. So "find every field tagged `audited`, then attach a sibling `_AUDIT` group" is one stream over the navigation surface composed with an `EditSession`, no query language:

<!-- snippet: navigation-annotations -->
```java
BoundModel m = BoundModel.ofJson(json);       // navigate this immutable snapshot
EditSession s = EditSession.read(json);       // mutate this session

m.fields()
        .filter(f -> f.annotation("audited").isPresent())   // find by annotation — plain Stream.filter
        .toList()                                           // materialize before mutating (don't match new fields)
        .forEach(f -> {
            String audit = f.name() + "_AUDIT";
            s.addGroup(f.groupPath(), audit);               // a sibling group of the matched field
            s.group(f.groupPath() + "/" + audit)            // → GroupEditor: three audit fields
                    .stringField("auditUser", b -> {})
                    .dateTimeField("auditAt", b -> {})
                    .stringField("auditAction", b -> {});
        });
String edited = s.validate().toJson();        // kernel gate, once
```

**Group template markers stay on the typed navigation surface (finding 100).** `GroupInfo.usageType()` reads markers such as `attachment` and `multi-select`; `ModelIndex.usageTypeOfGroup(path)` is the kernel-free index peer for consumers working below `BoundModel`. This matters after include preparation as well as on authored models: a `SINGLE_RG` mount takes the discarded provider root's final marker, and final absence clears any raw marker on the mount. For example, `m.groupAt("/Claim/Evidence").orElseThrow().usageType().filter("attachment"::equals)` recognizes an expanded attachment template without inspecting raw DM-JSON keys.

**Raw-string policy is readable without inspecting DM-JSON (finding 118).** `ModelIndex.stringNoValueValidation(path)` reports the effective String declaration and returns false for ordinary String and non-String fields. Rule diagnostics use it to distinguish the raw-field `Length` integer-conversion defect from an unrelated `MVK_INTERNAL_ERROR`, so the focused corrective is model-backed rather than inferred from the generic code.

**Computations are enumerable too (kernel-free).** `ModelIndex.computations()` lists every authored computation with its declared `computedFieldRelPath` resolved to the **absolute target field path** (the rel base is the computation *element*, so a same-group target reads `"../Field"`). This is what a consumer that must reason about computation targets without the kernel uses — e.g. to reject a computation whose target sits in a repeatable scope:

<!-- snippet: model-computations -->
```java
// every authored computation, its computedFieldRelPath RESOLVED to the absolute target field:
List<ComputationInfo> computations = ModelIndex.ofSelfContained(dmJson).computations();
ComputationInfo payout = computations.get(0);
payout.path();              // "/Claim/PayoutCalc"  — the computation element's name-path
payout.groupPath();         // "/Claim"             — its containing group
payout.targetFieldPath();   // "/Claim/Payout"      — "../Payout" resolved (rel base = the element)
String resolved = ModelIndex.resolvePath(payout.path(), "../Payout"); // shared rule/computation anchor resolver
```

### 6d. Extract a performance-calibration profile

`ModelProfiles` reduces a model to the deterministic, identifier-free axes that determine reporting and evaluation cost. The public boundary is deliberately the bare JSON artifact rather than the internal profile records, so consumers can persist or transport a profile without coupling to the profiler's planning representation:

<!-- snippet: profile-extract -->
```java
String profileJson = ModelProfiles.extract(modelJson);
```

The one-argument form requires a self-contained model. For a model with includes or imported type definitions, pass the same kernel-free `ModelResolver` used by `BoundModel.ofJson`; extraction expands the effective model before measuring it. The artifact contains counts, distributions, categorical vocabulary, and bounded/unbounded capacities, but no model identifiers, paths, literals, patterns, messages, descriptions, or raw rule/computation text. It is a performance-calibration description, not a behavioral substitute for the source model.

Compare a synthesized model's re-extracted profile with its target through the same JSON-only boundary. Each input may be a bare artifact or the normal extraction result envelope; the returned deterministic JSON reports `matches` plus every classified axis with its policy, target, actual, verdict, and rule:

<!-- snippet: profile-compare -->
```java
String comparisonJson = ModelProfiles.compare(targetProfileJson, actualProfileJson);
```

Diagnosed profile parsing failures and the synthesizer's bounded-group requirement raise `ProfileInputException`, an `IllegalArgumentException` subtype. Unexpected processing exceptions remain distinct. This type describes our performance-profile tooling format; it is not an A12 model or Document type. *Locked by `ModelProfileJsonTest` and `cli.LocalFailureBoundaryTest`.*

## 7. The LLM session — the loop the skill teaches, for both personas

> **Skill status:** the first BA-path skill draft exists and was **eval-measured** (a clean Opus subagent, given only the CLI + catalog + skill, went from multi-attempt/medium-confidence to **first-attempt/high-confidence** on the T1 authoring task, with the silent-inversion polarity trap closed by the skill's polarity section). Method, lifts, and caveats in [`EVAL-SPEC.md`](./EVAL-SPEC.md). Structurally-distinct benchmark tasks **T2–T6** now span the families (field-vs-field, pattern, aggregate+Having, date, value-list+boolean; golds kernel-verified) — answering the "T1 ≈ the skill's worked example" caveat — and **T7** adds the first **comprehension** task (the `order-ruled` fixture; the oracle asserts its rules read to `Converted`, readable by `explain`). Remaining: the **Workflow pass@k runner** that drives the agent passes (T3–T6 authoring + T7 explanation).

The **same loop, two surfaces** (N10 — one engine, two interactions): the **developer's** agent (Claude Code) runs these as ordinary calls on rulekit-the-library *inside the developer's own project* — the project compiler is the type-steering, no special vehicle. The **modeller/BA's** agent runs the equivalent **CLI verbs** target-first against the standalone binary (`dmtool -m model rule read/explain/add/modify`, `dmtool -m model model check …`, with JSON specs and result envelopes) — no JDK, no `.m2`. The steps below show the developer's library calls; each maps 1:1 to a CLI verb the BA path uses.

> **User:** "Customers can't save BASIC subscriptions — something fires on add-ons without a fee. Find and fix it so only paid tiers require a fee."

| Step | Agent does | rulekit gives back |
|---|---|---|
| 1 | `m.rules()` → `m.explain(each)` | the `suspicious` hint flags `LegacyFeeRule` (inverted predicate) |
| 2 | reads the cited catalog entries | `FieldFilled` vs `FieldNotFilled` + the error-scenario inversion — no BA docs (N6) |
| 3 | re-expresses: `feeRequired(tier, fee)` | a typed AST — illegal variants didn't compile |
| 4 | validates | ok / `MVK_*` mapped to a mechanical fix |
| 5 | diffs | the reviewable diff for the PR — the human gate |
| 6 | writes back | exactly-scoped DM-JSON change |

Authoring direction: *"ENTERPRISE needs ≥ 2 add-ons"* → agent queries the index (`addons.repeating()`), finds `NumberOfFilledFields` in the catalog (operands/returns/example), composes, validates. The feedback loop differs by persona (N10): the **developer's** wrong guesses die at **compile** (step 3, the project's own `javac`) or **parse** (step 4); the **BA's** wrong guesses die at **schema validation + builder pre-check + kernel `Diagnostic`** (no compile step, same engine). **Execution surfaces:** developer = the library in their own repo (no bespoke vehicle); BA = the standalone CLI. *(The earlier "scratch JBang vehicle" is demoted — see finding 6 — to at most an internal N6-eval mechanism.)*

## 8. Polyglot peek — the same catalog, a Zod-flavored skin

```typescript
import { bind } from "@a12-dmkits/core";                       // reads the SAME catalog JSON + DM-JSON

const m = bind(dmJson);
const fee = m.num("/Subscription/Addons/MonthlyFee");
const proNeedsFee = m.enum("/Subscription/Tier").is("PRO").and(fee.notFilled());
proNeedsFee.render();                                            // identical canonical DSL text
// no TS read/validate: the TS kernel stack has no AST and no buildable runtime (survey §15) — by design
```

The contract is data (catalog + DM-JSON + render rules), so this is a parallel reading, not a port — and naming parity means an agent transfers Java↔TS knowledge 1:1.
