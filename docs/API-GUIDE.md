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
                                       //   the kernel's portable-subset gate has the final word: lookbehind dies at F4)
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

/** A group that exists must have real content — guards the "blank added row" trap (GroupFilled = row exists). */
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
```

The fragments show how the shipped primitives compose into the **business idioms** the proposal's §2 names (`mandatory`, `withinTolerance`, per-row thresholds, `uniqueBy`) — each is 1–3 lines built from the operators in hand.

The per-row threshold uses the **semantic index** `num(f).forIndex(key)` — read *this field's value in the row whose index field equals `key`*. It is **not number-only**: `forIndex` lives on every comparable ref (`num`/`str`/`enumOf`/`date`/`time`/`dateTime`), and the key is either a **constant** (`forIndex("AUTO")`) or **another field** (`forIndex(m.field("/Policy/PreferredCode"))`). The kernel places no kind restriction on the indexed field — its own flagship example indexes an *enum* (`[Addresses/Country For PreferredAddress]`) — so the ref you call it on decides the result kind. (Finding 55 / DEV-API-GAPS DG15.)

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
    /** Escape hatch (item 8) — the Zod `.refine` analogue: name a host-registered check. */
    public static Condition reversibleTier(EnumRef tier) {
        return tier.is("ENTERPRISE").and(customCondition("NotReverse"));   // host code decides NotReverse
    }
    /** Date threshold (9a) — the most frequent date pattern; Today composes directly. */
    public static Condition deliveryWithinDays(DateRef order, DateRef delivery, long days) {
        return allOf(order.filled(), delivery.filled(),
                delivery.after(addDays(order, days)));        // [Delivery] > AddDays(Order, days)
    }
}
```

No framework, no registry — a fragment library is a Java class; discoverability comes from javadoc + the skill. (This is the Zod lesson: schemas-are-code.)

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

`agreesWith` ships the most-repeated mirroring idiom as a one-call ref combinator on `StringRef`, `NumberRef`, and `EnumRef` (number inherits the equal-scale gate; enum compares by stored value via `is`). It's written — and its polarity reviewed — **once**, not per pair. **Field-vs-field equality** (`a.eq(b)`) underneath landed for string + number (finding 18); **enum-vs-enum** (`EnumRef.is(EnumRef)`/`isNot(EnumRef)`). `sameAddress` is then just `agreesWith` zipped over the fields and folded with `allOf(List)` (38) — the full worked rebuild is the **Study, §3.6**. (Later: a `sameAddress(GroupRef, GroupRef)` that zips like-named fields via the model index — Level-2 navigation.)

### 3.5 n-ary composition

Varargs for hand-written composition; a `List<Condition>` overload for the **generated/bulk** case (one clause per navigated field, folded). The presence quantifiers (`allFieldsFilled`/`noFieldFilled`/`atLeastOneFieldFilled`) gained the same `List<? extends FieldOperand>` overload, so a presence guard over a built field set needs no `toArray` splat.

<!-- snippet: nary -->
```java
Condition c = allOf(r1, r2, r3);            // And-fold: "r1 And r2 And r3" — no redundant parentheses
Condition d = allOf(r1, anyOf(r2, r3));     // "r1 And (r2 Or r3)" — parens per the kernel's mixing rule
Condition e = allOf(List.of(r1, r2, r3));   // the LIST form — fold clauses a loop/stream built (bulk authoring)
```

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

**Date-valued `Min`/`Max`.** `Dsl.min`/`Dsl.max` are overloaded for date operands — the result is a `DateExpr`, comparable to any date operand — so number/date mixing can't be written (the operands are dates by type). The kernel evaluates it through its `DateCombiner` (findings 58, KERNEL-FINDINGS §6):

<!-- snippet: date-minmax -->
```java
// Min/Max over DATE operands yield a DateExpr — comparable to any date operand (KERNEL-FINDINGS §6).
// The type system forbids number/date mixing: min(dates) accepts only date operands.
Condition earliestBeforeCutoff =
        Dsl.min(m.date("/Order/OrderDate"), m.date("/Order/DeliveryDate"))
                .before(Dsl.dateValue("20.03.2026"));   // Min([A], [B]) < "20.03.2026"
```

**Presence over a semantic index.** A `forIndex(...)` value expression grows `.filled()`/`.notFilled()`, so the presence of the cell an index resolves to is authorable — rendered bare (no value brackets). It is a fail-fast corrective: only an indexed cell has a presence, so `sum(...).filled()` throws rather than emit a kernel-illegal `FieldFilled(Sum(...))` (finding 59):

<!-- snippet: indexed-presence -->
```java
// The semantic index [Count For "SKU-1"] reads a cell; .filled()/.notFilled() test its presence.
// (Only a forIndex(...) result carries a presence — a bare aggregate does not.)
Condition present = m.num("/Order/Items/Count").forIndex("SKU-1").filled();
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
rule.render();                                             // fork-9: paths relative to the rule's group (../ to siblings)
new DslRenderer(ConditionLanguage.DE).render(condition);   // same AST, German keywords

ValidationResult vr = new RuleValidator().check(modelJson, draftRule);   // F4: real kernel, no compile
vr.diagnostics().forEach(d -> System.out.println(d.code() + " " + d.fix()));  // F4a: code + catalog-joined fix

// F4a unified surface: KERNEL (span-pointed) + LINT warnings + gotcha INFO in one result
ValidationResult all = new RuleDiagnostics().diagnose(boundModel, rule);
System.out.println(all.toJson());                          // agent-parseable, one array, every origin (files-as-API)

BoundModel m2 = m.add(rule);                               // snapshot (F0): write → NEW binding, re-validated
m2.writeTo(path);                                          // DM-JSON write path — authored JSON, includes preserved (D9, F6)
```

### 4.3 A computation, same materials

<!-- snippet: computation -->
```java
Computation tiered = m.computation(m.num("/Subscription/Billing/EffectiveFee"))   // element placed in the
        .name("TieredFeeComp")                                                     // computed field's group;
        .commonPrecondition(fieldFilled(base.field()))                             // relPath computed
        .when(base.ge(100)).calc(base.value())             // staged: every when() must get its calc()
        .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
        .message(en("EffectiveFee follows the fee tiers."),
                 de("EffectiveFee folgt den Gebührenstufen."))   // a message per declared locale (the locale law)
        .build();
```

**The original sketch here used `.otherwise(...)` — that was kernel-illegal** (verified: mixing preconditioned and unconditional alternatives draws `MVK_ERROR_EMPTY_PRECONDITION`), so the API doesn't offer it: either one unconditional `calc(...)` or all-preconditioned `when(...).calc(...)`. Further slot rules pre-checked at `build()` with the code named: the computed field must not appear in preconditions *or* operations (`MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` — both verified), operation **kind** must match the computed field (`Calculation.kind()` — the typed AST now carries the §5b lattice), message required. Mutual exclusivity of preconditions stays the kernel/runtime's judgment; result *scale* stays the kernel's call (D13).

**Cross-slot reuse for free:** `SubscriptionIdioms.paidTier(tier)` drops unchanged into a rule `errorCondition` *or* a computation `precondition` — one expression language, many slots (§5). That is the concrete payoff of combinators + the slot model.

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
      "gotchas":["a field carrying a formal error is removed from evaluation, so 'FieldFilled(F) Or FieldNotFilled(F)' is not a tautology"] },
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

## 5b. Editing the model — structure *and* rules (modelkit + `EditSession`)

The use case in one place: **read a DM → add fields/groups → add/modify rules → maybe delete → write back.** modelkit (`ModelDocument`) edits *structure*; rulekit edits *rules*; they are siblings over `dm.core` that cannot see each other (`dm.modelkit ⊥ dm.rulekit`), so `dm.edit.EditSession` is the orchestration layer above both. Every edit is surgical on the **authored** DM-JSON tree (includes preserved, never inlined) and the **real kernel is the gate** — nothing structural is reimplemented.

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
// constraint read-backs (peers of enumValues) — no raw fieldConfig dig, the schema-block quirks stay hidden:
doc.fieldPattern("/Subscription/Code");          // Optional["[A-Z]+"]   (+ fieldMinLength/fieldMaxLength : OptionalInt)
doc.fieldMaxFractionalDigits("/Subscription/Fee"); // OptionalInt[2]     (+ fieldMinValue/fieldMaxValue : BigDecimal)
doc.typeDefinitionMaxLength("ShortText");        // OptionalInt[…]       the same peers for a leaf typeDefinition
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

A repeatable group can also declare a **display row sort** on the from-scratch builder — `GroupBuilder.sortField("Amount", SortOrder.DESC).sortField("Sku", SortOrder.ASC)` (multi-key, in declaration order). Like `indexField` it fails fast on a non-repeatable group or a non-child field. It is display/USB-only — the kernel doesn't reorder rows for validate/compute (so the interpreter ignores it) — making it an authoring + read-fidelity slot (finding 51). *Locked by `modelkit.GroupSortFieldTest` (kernel read-back).*

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

### Richer field types & element metadata

Beyond easy kinds: add a fully-configured field (`FieldTypeSpec` — string bounds/pattern, number scale, enum values + categories, date format/precision, a `TypeDefRef`), **re-type** an existing field, and set its metadata in place — requiredness, per-locale **labels** + **helper text**, descriptions, and name/value **annotations** on a field or group.

<!-- teaser -->
```java
ModelDocument.read(json)
    .addField("/Subscription", "Coupon",
        new FieldTypeSpec.StringSpec(3, 10, "[A-Z]{3,10}", "3–10 uppercase letters"))   // pattern + bounds
    .addField("/Subscription", "RiskBand", new FieldTypeSpec.EnumSpec(values, categories)) // enum + categories
    .setFieldType("/Subscription/AutoRenew", new FieldTypeSpec.ConfirmSpec())            // re-type a field
    .setLabel("/Subscription/Tier", Map.of("en_US", "Plan tier", "de_DE", "Tarifstufe")) // per-locale label
    .setHelperText("/Subscription/Tier", Map.of("en_US", "Affects pricing"))             // per-locale helper text
    .setAnnotation("/Subscription/Tier", "ui:widget", "dropdown")                        // name/value metadata
    .validate();
```
A label must cover every declared locale (fails fast otherwise); the add/re-type reuse the build-from-scratch emitter, so a configured field is shaped identically to a built one. *Locked by `modelkit.ModelMetadataTest`.* The pattern's error text can be **localized**: the single message in `pattern(regex, msg)` covers every declared locale, and `StringFieldBuilder.patternMessage(locale, text)` (CLI: `string.patternMessages`) overrides it per locale — the string sibling of an enum's per-locale invalid-value text, so a bilingual model carries a German pattern error (*locked by `FieldAddSpecTest`*). Each aspect has its own setter (`setRequiredness`/`setLabel`/`setHelperText`/`setGlobal`/…); `ModelDocument.applyModification(path, FieldModification)` applies a **partial** bundle of them at once (only the present aspects), which is what the CLI's **`field modify <spec.json>`** rides (G6) — so an agent can add a STRING `pattern` *or* change a label on an existing field without delete-and-recreate, and an aspect it asks for is never silently dropped (CLI-SPEC §5). `StringFieldBuilder.noValueValidation()` authors the value-skip flag (the attachment-content idiom), and the builder **fails fast** on the kernel's co-requirement — a no-value-validation string must permit line breaks (`MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK`) — with a named cause at `build()` rather than a late kernel reject (finding 47).

A **group** carries a display label too — it is the kernel's `ILabeled` via `IEntity`, exactly like a field. The from-scratch builder authors it (`GroupBuilder.label("en_US", "Order lines").label("de_DE", "Bestellzeilen")`), the edit path sets it on an existing group (`setLabel(groupPath, …)`, CLI `meta <groupPath> --label`), and the same cover-every-declared-locale rule applies to both (finding 56 / DG17). *Locked by `modelkit.GroupLabelTest` (kernel read-back).*

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

## 5c. Refs & editors — the L2c ergonomic layer

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

**A computed target is any field kind — not only a number.** The editor's `computation(…)`/`addComputation(…)` take a `NumberRef`, a `StringRef` (a concatenation target), or the general `FieldRef` — mirroring `BoundModel.computation`'s three overloads, so e.g. a computed display string authors through the same typed surface:

<!-- teaser -->
```java
var order = s.group("/Order");
order.addComputation(order.str("CustomerName"), c -> c.name("NameComp")
    .calc(order.str("ProductCode").asString().plus("-X").expression())
    .message(Dsl.en("Customer display name.")));
```

*(Surfaced by dogfooding the conformance corpus: its string-concat guard case couldn't be authored through the editor while only the `NumberRef` overload existed. Test-backed by `RuleComputationEditorTest.authorsAStringComputationThroughTheEditor`.)*

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

Real apps compose models: a shared sub-model is **included** into a base and mounted at one or more places, and the base authors rules over the mounted fields exactly as if they were local. rulekit binds a model **with includes** by threading a resolver (`EditSession.read(json, resolver)` / `BoundModel.ofJson(json, resolver)`), expands it through the kernel, and writes back **without inlining** the include — the `modelAlias` link is preserved (F6/D9). A field from an include is addressed by its **mount-name path** (`/Catalog/Retail/ListPrice` = the mount `Retail` + the included field `ListPrice`); a **type-def** field behaves as its underlying kind.

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

The included price list's **own** rule travels with it — mounted twice, it applies at both `/Catalog/Retail` and `/Catalog/Wholesale` — and the base's rules over the mounted fields validate against the real, expanded model. The written-back DM-JSON keeps the include as a `modelAlias` reference, never inlined; included content doesn't leak into the base. *Test-backed: `edit.MultiModelTest` (two-location include of a ruled model + rules over mounted fields + type-def) and `rulekit.dsl.IncludesTypedefsRuleTest` (single mount + type-def, EN/DE render).*

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

`KernelAdapter` above is the JVM-only path that *uses* the kernel. Its twin is [`:interpreter`](INTERPRETER-SPEC.md) — a **standalone, kernel-free, multiplatform (JVM + JS)** evaluator (the engine dmtool's native image runs, and a publishable Kotlin/JS library). Its `ModelLoader` reads an **expanded, self-contained** DM-JSON model straight into a `ModelDef` on **both** targets — so a browser/JVM library consumer no longer hand-builds the model (the standalone-loader design observation, finding 57):

<!-- teaser -->
```kotlin
// tree API only (no @Serializable, no compiler plugin); a number's raw literal is preserved — a declared
// minValue of 100.50 stays "100.50" (never round-tripped through a Double/JS number).
val model: ModelDef = ModelLoader.load(dmJson)

// …build a Document for the instance, then evaluate with NO kernel on the classpath:
val fired = DmInterpreter(model).validate(document, "en_US").messages.map { it.code }
```

dmtool's JVM `InterpreterModelLoader.load` is a thin delegate over this same loader — parity across every fixture model is CI-guarded (`ModelLoaderParityTest`), so the native runtime verbs and a JS library see identical `ModelDef`s. *Test-backed: `interpreter/…/load/ModelLoaderTest` (JVM + JS), `cli.interp.ModelLoaderParityTest`, `cli.interp.InterpreterRealModelDiffTest`.*

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

**Two design decisions worth recording.** *(1) Typed built-ins, data-extensible later.* The shipped idioms are code (typed fragments — correct by construction, composing the §5c.1 document-part builders), behind a `PatternEntry` **interface**. That interface is the seam a future *user* extension plugs into — a `${param}` data-template loaded from a workspace file and kernel-validated on load — so "users extend the catalogue" is additive, not a rewrite. The tension is real and was chosen consciously: typed = correct-by-construction but not user-authorable without recompiling; data = user-authorable but validated only at runtime (consistent with the BA persona's no-compile-step model, D7). *(2) The no-rot gate.* Exactly as the operator catalogue CI-validates every `validExample`, every pattern's `example` is instantiated and run through the real kernel (`PatternCatalogKernelTest`) — a pattern that no longer scaffolds a valid rule fails the build.

**CLI peer.** `dmtool patterns` lists the idioms, `patterns <id>` details one, and `patterns <id> --arg k=v …` scaffolds a rule-spec and **auto-checks** it (the G4 verdict folded in); `--apply` persists it through the same path as `rule add`. *Test-backed: `rulekit.catalog.PatternCatalogTest` (structure) + `PatternCatalogKernelTest` (the kernel no-rot gate); `cli.PatternsVerbTest`. Demo: `examples/cli-tour.md`.*

---

## Part V — Discovery & the agent loop

The semantics an agent reads — the queryable operator catalog, the model-context projections, model navigation — and the LLM session loop those feed, for both the developer and the BA persona.

## 6. Semantics as data — the catalog is queryable today

<!-- snippet: catalog -->
```java
OperatorCatalog catalog = OperatorCatalog.load();
OperatorEntry e = catalog.byId("GroupFilled").orElseThrow();
e.meaning();      // "True iff the referenced group is filled. For a repeatable group this means the row EXISTS — ..."
e.gotchas();      // ["'filled' means 'row exists', not 'row has data' — ...", "an enclosing GroupFilled(Group) counts as referencing the rule's error field ..."]
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

### 6c. Navigating the model — jOOQ-`Meta`-style, no query language

The programmatic counterpart to 6b's files — and the machinery beneath them (`fields.jsonl` *is* `m.fields()` serialized). Streams of rich **info handles**; matching is plain Java; narrowing bridges back to strict authoring handles:

<!-- snippet: navigation -->
```java
// iterate + match — Stream.filter IS the matcher (no custom matcher API, no XPath-for-DM):
List<FieldInfo> money = m.fields()
        .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)
        .toList();

m.fields(m.group("/Subscription/Addons"))        // scoped to a group
        .forEach(f -> System.out.println(f.path() + " : " + f.kind()));

Optional<FieldInfo> peek = m.fieldAt("/Subscription/Tier");   // non-throwing — exploring is lenient,
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

## 7. The LLM session — the loop the skill teaches, for both personas

> **Skill status:** the first BA-path skill draft exists and was **eval-measured** (a clean Opus subagent, given only the CLI + catalog + skill, went from multi-attempt/medium-confidence to **first-attempt/high-confidence** on the T1 authoring task, with the silent-inversion polarity trap closed by the skill's polarity section). Method, lifts, and caveats in [`EVAL-SPEC.md`](./EVAL-SPEC.md). Structurally-distinct benchmark tasks **T2–T6** now span the families (field-vs-field, pattern, aggregate+Having, date, value-list+boolean; golds kernel-verified) — answering the "T1 ≈ the skill's worked example" caveat — and **T7** adds the first **comprehension** task (the `order-ruled` fixture; the oracle asserts its rules read to `Converted`, readable by `explain`). Remaining: the **Workflow pass@k runner** that drives the agent passes (T3–T6 authoring + T7 explanation).

The **same loop, two surfaces** (N10 — one engine, two interactions): the **developer's** agent (Claude Code) runs these as ordinary calls on rulekit-the-library *inside the developer's own project* — the project compiler is the type-steering, no special vehicle. The **modeller/BA's** agent runs the equivalent **CLI verbs** target-first against the standalone binary (`dmtool -m model rule read/explain/add/modify`, `dmtool -m model model check …`, JSON in/out) — no JDK, no `.m2`. The steps below show the developer's library calls; each maps 1:1 to a CLI verb the BA path uses.

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
