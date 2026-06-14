# API study & review surface — what rulekit feels like

> **Status:** living **design study and review surface**, expanded 2026-06-04 (first version 2026-06-03) — code studies for **every stated proposal goal** ([`PROJECT-PROPOSAL.md`](./PROJECT-PROPOSAL.md) v2 §2/§3), so the end-state experience can be reviewed *before* each part is built. Names and signatures of unbuilt parts are **not final**. Status markers throughout: ✅ = works today, verbatim or near-verbatim; 🔮 = Phase-1/2 scope, illustrative.
>
> **This doc is deliberately kept alive:** whenever a feature lands or an interesting usage emerges, the corresponding study here is updated to showcase it (see the doc-evolution note in `CLAUDE.md`). It doubles as the seed material for the LLM skill (N1) and the self-sufficiency eval (N6).
>
> Domain: the committed **subscription** fixture (our own original) — `Subscription` root with `Tier` (enum `BASIC/PRO/ENTERPRISE`, category `Pricing` FREE/PAID), `AutoRenew` (boolean), `TermsAccepted` (confirm), repeatable `Addons{Name, MonthlyFee(scale 2)}`, `Billing{InvoiceEmail, BaseFee(scale 2), EffectiveFee(scale 2)}`; declared locales `en_US` + `de_DE` (header locales are arbitrary — en/de are not special); the **order** fixture adds `ShippingAddress`/`BillingAddress` groups and boolean/confirm fields. Extended illustratively where a construct needs it.

---

## 1. Setup — bind once, everything flows from the model (F0) ✅

<!-- teaser -->
```java
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.*;
import io.github.mbackschat.a12.dm.rulekit.dsl.*;

BoundModel m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));   // ✅ today
// BoundModel m = BoundModel.load(path);                      // 🔮 path-loading sugar (today: read the text yourself)
BoundModel withIncludes = BoundModel.ofJson(json, resolver);  // ✅ a model with includes — rulekit expands via the resolver (F0; §5d)

NumberRef  fee   = m.num("/Subscription/Addons/MonthlyFee");  // knows: number, scale 2
EnumRef    tier  = m.enumOf("/Subscription/Tier");            // knows: BASIC, PRO, ENTERPRISE
BoolRef    renew = m.bool("/Subscription/AutoRenew");         // boolean ≠ confirm (§5b)
ConfirmRef terms = m.confirm("/Subscription/TermsAccepted");
GroupRef   addons = m.group("/Subscription/Addons");          // knows: repeatable
```

Everything the safety story needs is in hand at bind time: kinds, scale, enum domain, repetition. `m.num(stringPath)` fails *here* with the kind named — not as an `MVK_*` three steps later.

## 2. The type system steers (F0a/§5b) ✅ — a study in what you *cannot* write

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

## 3. Combinators & parameterization (F5) — the reuse study

### 3.1 Parameterized fragments are ordinary methods ✅

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

### 3.2 Fragments with domain parameters ✅ / 🔮 (tolerance ✅ — operator landed, marker flipped on the 2026-06-10 sweep; the chronology composite still 🔮)

<!-- teaser -->
```java
/** Tolerance check — only the fixed 1/2/5/10 variants exist (the catalog teaches this; the enum enforces it). */
static Condition withinTolerance(NumberRef actual, NumberRef expected, Tolerance t) {
    return allFieldsFilled(actual.field(), expected.field())
            .and(actual.differsWithTolerance(t, expected));   // ✅ DiffersWithToleranceRange{N} (NumberRef.differsWithTolerance)
}

/** Chronology over a repeating group — composes the iteration cluster (backlog item 7).
 *  `currentRepetition(group)` ✅ (7e), semantic index `num(f).forIndex("k")` ✅ (7f); date+atRepetition 🔮. */
static Condition chronological(GroupRef repeating, DateRef date) {
    return date.value().lt(date.atRepetition(currentRepetition(repeating).plus(1)));  // ✅ currentRepetition; 🔮 rest
}

/** Uniqueness by key ✅ (7b) — one operator, but the fragment names the business intent.
 *  The rule sits at the PARENT of the repeating group (kernel position law, KERNEL-FINDINGS §9). */
static Condition uniqueBy(GroupRef repeating, FieldRef key) {
    return groupFilled(repeating).and(repetitionNotUnique(key));               // ✅ + .from(scope) for @From
}
```

The fragments show how backlog items compose into the **business idioms** the proposal's §2 names (`mandatory`, `withinTolerance`, `chronological`, `uniqueBy`) — each is 1–3 lines once its operators exist.

### 3.3 A domain fragment library — packaging reuse ✅ (all shown fragments work today)

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
    /** Format idiom ✅ — present-but-malformed (the FieldFilled guard is the kernel's own pattern habit). */
    public static Condition malformedEmail(StringRef email) {
        return email.filled().and(email.violates(".+@.+\\..+"));   // regex renders verbatim — no escaping layer
    }
    /** Reserved-prefix idiom ✅ — the shape of the metamodel's own HEADER_ID_STARTS_WITH_XML rule. */
    public static Condition reservedPrefix(StringRef id) {
        return id.matches("[xX][mM][lL].*");                       // anchored whole-value match (§7)
    }
    /** Allow-list idiom ✅ — enum membership beats chained Or; the domain is pre-checked. */
    public static Condition rushOrder(EnumRef priority) {
        return priority.in("EXPRESS", "OVERNIGHT");                // FieldValueIncludedInValueList(…)
    }
    /** Placeholder sweep ✅ — one quantifier over several same-type fields (In-list shape). */
    public static Condition noTestPlaceholders(StringRef first, StringRef... more) {
        return noFieldValueIncludedInValueList(List.of("test", "demo", "n/a"), first, more);
    }
    /** Threshold idiom ✅ — the count is an ordinary number expression (kinds may be mixed). */
    public static Condition incompleteBilling(BoundModel m) {
        return numberOfFilledFields(m.group("/Subscription/Billing")).lt(3);   // group form: count inside
    }
    /** Aggregate idiom ✅ (7c) — Sum over a repetition list; unspecified rows count as 0. */
    public static Condition addonBudgetExceeded(BoundModel m, String budget) {
        return sum(m.flatten("/Subscription/Addons*/MonthlyFee")).gt(budget);  // scale-faithful literal
    }
    /** Filtered aggregate ✅ (7d) — Having narrows the *-list before Sum consumes it. */
    public static Condition paidAddonBudget(BoundModel m, String budget) {
        return sum(m.flatten("/Subscription/Addons*/MonthlyFee")
                .having(m.num("/Subscription/Addons/MonthlyFee").gt(0))).gt(budget);
    }
    /** Escape hatch ✅ (item 8) — the Zod `.refine` analogue: name a host-registered check. */
    public static Condition reversibleTier(EnumRef tier) {
        return tier.is("ENTERPRISE").and(customCondition("NotReverse"));   // host code decides NotReverse
    }
    /** Date threshold ✅ (9a) — the most frequent date pattern; Today composes directly. */
    public static Condition deliveryWithinDays(DateRef order, DateRef delivery, long days) {
        return allOf(order.filled(), delivery.filled(),
                delivery.after(addDays(order, days)));        // [Delivery] > AddDays(Order, days)
    }
}
```

No framework, no registry — a fragment library is a Java class; discoverability comes from javadoc + the skill. (This is the Zod lesson: schemas-are-code.)

### 3.4 Parameterized *rules* — templates over the model ✅ (message interpolation ✅ — `Dsl.fieldValue`)

<!-- teaser -->
```java
/** One rule per monetary field: never negative. Navigation (§6c) drives the loop. */
static List<Rule> nonNegativeMoneyRules(BoundModel m) {
    return m.fields()                                             // ✅ Level-1 navigation
            .filter(f -> f.kind() == ValueKind.NUMBER && f.scale() == 2)   // "money" by convention
            .map(f -> m.rule(f.group())                         // ✅ Rule authoring object (F2a)
                    .name(f.name() + "NonNegativeRule")
                    .errorField(f.asNum())
                    .when(f.asNum().filled().and(f.asNum().lt(0)))
                    .code("NON_NEGATIVE_" + f.name().toUpperCase())
                    .severity(Severity.ERROR)
                    .message(en(f.name() + " must not be negative."),   // ✅ F2a: interpolate via Dsl.fieldValue(f) → $path.value$
                             de(f.name() + " darf nicht negativ sein.")) // a message per declared locale (the locale law)
                    .build())
            .toList();
}
```

This is the bulk-authoring payoff: a *rule template* is a function `BoundModel → List<Rule>`; an LLM (or CI) applies it to any model.

### 3.4a Authoring-time reuse over a multi-field structure — `sameAddress` ✅ (field-vs-field equality landed; finding 18)

When you author **many rules in Java that compare the same multi-field structure** (e.g. a seven-field address), a fragment removes the boilerplate — ordinary F5 combinator reuse, two levels deep. (Note: this is a convenience in the *authoring code*, not a transformation of the stored rule — the kernel DSL is flat, so the rendered `errorCondition` is the same string either way; what improves is the Java you write, not the persisted text.)

<!-- teaser -->
```java
// field level — "null-safe equality": both present and equal, OR both absent
static Condition equalOrBothEmpty(StringRef a, StringRef b) {
    return allFieldsFilled(a.field(), b.field()).and(a.eq(b))    // ✅ field-vs-field eq (string: no scale)
            .or(noFieldFilled(a.field(), b.field()));
    // renders ( (AllFieldsFilled(a,b) And [a]==[b]) Or NoFieldFilled(a,b) ) — rulekit auto-parenthesizes
}
// address level — parametrized over WHICH fields hold the address
record Address(StringRef street, StringRef zip, StringRef city, /* … 7 */) {}
static Condition sameAddress(Address x, Address y) {
    return allOf(equalOrBothEmpty(x.street(), y.street()),
                 equalOrBothEmpty(x.zip(),    y.zip()), /* … */);
}
// each rule then reads at intent level — e.g. "the warehouse ships from the sender's or the buyer's address":
m.rule(g).when(warehouseAddr.filled().and(sameAddress(warehouse, sender).or(sameAddress(warehouse, buyer)))) …
```

The fragment is just a function — the F5 payoff for repetitive authoring. **Field-vs-field equality** (`a.eq(b)`) landed for string + number (number `==`/`!=` pre-checks equal scale; ordering exempt — finding 18); **enum-vs-enum ✅** (`EnumRef.is(EnumRef)`/`isNot(EnumRef)` authoring + `adapter.EnumFieldComparisonReadTest` read). (Later: a `sameAddress(GroupRef, GroupRef)` that zips like-named fields via the model index — Level-2 navigation.)

### 3.5 n-ary composition ✅ (finding 10 — implemented 2026-06-04)

<!-- snippet: nary -->
```java
Condition c = allOf(r1, r2, r3);            // And-fold: "r1 And r2 And r3" — no redundant parentheses
Condition d = allOf(r1, anyOf(r2, r3));     // "r1 And (r2 Or r3)" — parens per the kernel's mixing rule
```

## 4. Authoring a rule end-to-end (F1, F2, F2a, F3, F4, F6)

### 4.1 The rule object — slots from typed handles (F2a) ✅ — implemented 2026-06-04 (`RuleAuthoringTest`; message *interpolation* ✅ 2026-06-07 — `Dsl.fieldValue`)

<!-- teaser -->
```java
Rule rule = m.rule(addons)                        // model-aware entry: rule lives in a group by construction
        .name("AddonFeeRequiredRule")             // the element name (the study originally forgot it)
        .errorField(fee)                          // errorEntityRelPath computed — "../MonthlyFee"
        .when(SubscriptionIdioms.feeRequired(tier, fee))
        .code("ADDON_FEE_REQUIRED_FOR_PRO")
        .severity(Severity.ERROR)
        .message(en("A PRO subscription requires a monthly fee per add-on."),
                 de("Ein PRO-Abo erfordert eine Monatsgebühr je Zusatzoption."))   // ✅ F2a landed: interpolate via Dsl.fieldValue(name) → $Name.value$
        .build();   // pre-checks at build(): error field under the group; error-field-referenced
                    // (directly or via enclosing group ref — kernel-verified); the LOCALE LAW:
                    // a message for EVERY declared locale (MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING),
                    // none for undeclared ones (stricter than the kernel, which ignores them silently);
                    // header locales are arbitrary — Dsl.text("fr_FR", …) for anything beyond en/de sugar
// rule.draft() bridges to the F4 validator; rule.render(DE) renders the same AST in German.
// ConditionReferences.of(condition) — the build-check's collector — is the seed of F9's referenced-fields projection.
```

### 4.2 Render → validate → emit ✅

<!-- teaser -->
```java
new DslRenderer().render(condition);                       // ✅ canonical EN, absolute paths (no group context)
rule.render();                                             // ✅ fork-9: paths relative to the rule's group (../ to siblings)
new DslRenderer(ConditionLanguage.DE).render(condition);   // ✅ same AST, German keywords

ValidationResult vr = new RuleValidator().check(modelJson, draftRule);   // ✅ F4: real kernel, no compile
vr.diagnostics().forEach(d -> System.out.println(d.code() + " " + d.fix()));  // ✅ F4a: code + catalog-joined fix

// ✅ F4a unified surface: KERNEL (span-pointed) + LINT warnings + gotcha INFO in one result
ValidationResult all = new RuleDiagnostics().diagnose(boundModel, rule);
System.out.println(all.toJson());                          // ✅ agent-parseable, one array, every origin (files-as-API)

BoundModel m2 = m.add(rule);                               // ✅ snapshot (F0): write → NEW binding, re-validated
m2.writeTo(path);                                          // ✅ DM-JSON write path — authored JSON, includes preserved (D9, F6)
```

### 4.3 A computation, same materials (F2) ✅ — implemented 2026-06-04 (`ComputationAuthoringTest`; aggregates/arithmetic in operations are corpus items 6–7); **read + full cycle ✅ 2026-06-08** (`readComputation`/`replaceComputation`; kernel-mm 100%, Corpus-1 100% — §9 board); **BA-doc worked-example shapes transferred** (`BaComputationCorpusTest`: concat, tiered+rounding, division/grouping, common-precondition, current-repetition, parallel-iteration — the current-repetition one surfaced + fixed an over-strict calculated-field pre-check, KERNEL-FINDINGS §11)

<!-- teaser -->
```java
Computation tiered = m.computation(m.num("/Subscription/Billing/EffectiveFee"))   // element placed in the
        .name("TieredFeeComp")                                                     // computed field's group;
        .commonPrecondition(fieldFilled(base.field()))                             // relPath computed
        .when(base.ge(100)).calc(base.value())             // staged: every when() must get its calc()
        .when(base.lt(100)).calc(NumberLiteral.of("0.00"))
        .message(en("EffectiveFee follows the fee tiers."))
        .build();
```

**The original sketch here used `.otherwise(...)` — that was kernel-illegal** (verified: mixing preconditioned and unconditional alternatives draws `MVK_ERROR_EMPTY_PRECONDITION`), so the API doesn't offer it: either one unconditional `calc(...)` or all-preconditioned `when(...).calc(...)`. Further slot rules pre-checked at `build()` with the code named: the computed field must not appear in preconditions *or* operations (`MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD` — both verified), operation **kind** must match the computed field (`Calculation.kind()` — the typed AST now carries the §5b lattice), message required. Mutual exclusivity of preconditions stays the kernel/runtime's judgment; result *scale* stays the kernel's call (D13).

**Cross-slot reuse for free:** `SubscriptionIdioms.paidTier(tier)` drops unchanged into a rule `errorCondition` *or* a computation `precondition` — one expression language, many slots (§5). That is the concrete payoff of combinators + the slot model.

## 5. The bidirectional edit loop (F8, F10) — modify studies

### 5.1 In-session transform (#1) ✅ — working code, CI-locked verbatim to `TransformConditionTest`

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

### 5.2 Read a foreign, BA-authored rule — the full loop, load to save (F10) ✅ 2026-06-06

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

### 5.3 Explain (F9) ✅ 2026-06-04 (structural `tree` added 2026-06-05) — same projection for built and read rules

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

*Carried forward (the 2026-06-03 sketch's richer rule-envelope, not yet shipped):* per-reference **roles** (error-field / operand) and **severity** need the rule envelope, not just the condition; the **`suspicious`** hints are available separately today via `RuleDiagnostics`/`lint.Linter`; authored path-style is a render concern. See proposal §F9 ("rule-envelope enrichment").

### 5.4 Re-express, diff, validate, write back (#2) ✅ 2026-06-04

<!-- teaser -->
```java
Condition current = ((ReadResult.Converted) adapter.read("/Subscription/Addons/FeeCapRule")).condition();  // ✅ F10
Condition fixed   = current.and(m.num(FEE).filled());            // ✅ re-express: compose (or transform inside)
RenderedDiff diff = adapter.diff("/Subscription/Addons/FeeCapRule", fixed);   // ✅ F8 — before/after + lossiness
//  diff.unified() → "- [/…/MonthlyFee] > 1000  / + [/…/MonthlyFee] > 1000 And FieldFilled(/…/MonthlyFee)"
//  diff.lossiness() → ["…inline/trailing comments not preserved…"] (the leading comment is carried — §5f/finding 27)
ValidationResult vr = new RuleDiagnostics().diagnose(m, m.rule(g).errorField(fee).when(fixed)…build());  // ✅ F4/F4a
BoundModel m2 = model.replaceCondition("/Subscription/Addons/FeeCapRule", fixed);  // ✅ F8 #3 — new binding (F0), re-validated; persist via m2.writeTo(...) (§5.2)
```

Note how 3.3's fragment library closes the loop: **re-expression frequently means recognizing a known idiom** — the LLM maps a foreign rule onto `feeRequired(...)` and the diff shows the cleanup.

## 5b. Editing the model — structure *and* rules (modelkit + `EditSession`) ✅ — 2026-06-10

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

### Read & enumerate an existing model (M7)

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
doc.fieldType("/Subscription/Tier");   // Optional["EnumerationType"]
doc.isRepeating("/Subscription/Addons"); // true
doc.toJson();                          // content-equal to the source, idempotent, still kernel-valid
```
*Locked by `modelkit.ModelDocumentTest`.*

### Edit fields & groups (M8)

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

### The coverage tail — doc config, type definitions, includes (M10+)

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

### Richer field types & element metadata (M10+)

Beyond easy kinds: add a fully-configured field (`FieldTypeSpec` — string bounds/pattern, number scale, enum values + categories, date format/precision, a `TypeDefRef`), **re-type** an existing field, and set per-locale **labels** + name/value **annotations** on a field or group.

<!-- teaser -->
```java
ModelDocument.read(json)
    .addField("/Subscription", "Coupon",
        new FieldTypeSpec.StringSpec(3, 10, "[A-Z]{3,10}", "3–10 uppercase letters"))   // pattern + bounds
    .addField("/Subscription", "RiskBand", new FieldTypeSpec.EnumSpec(values, categories)) // enum + categories
    .setFieldType("/Subscription/AutoRenew", new FieldTypeSpec.ConfirmSpec())            // re-type a field
    .setLabel("/Subscription/Tier", Map.of("en_US", "Plan tier", "de_DE", "Tarifstufe")) // per-locale label
    .setAnnotation("/Subscription/Tier", "ui:widget", "dropdown")                        // name/value metadata
    .validate();
```
A label must cover every declared locale (fails fast otherwise); the add/re-type reuse the build-from-scratch emitter, so a configured field is shaped identically to a built one. *Locked by `modelkit.ModelMetadataTest`.*

### Rules *along with* structure — one session (M9)

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

## 5c. Refs & editors — the L2c ergonomic layer ✅ (implemented 2026-06-12, decision D7)

> **Status: implemented 2026-06-12 (proposal D7 / binding level *L2c*).** This is the agreed design, now built, and **replaces the dropped L2b codegen**. It spans **both kits** — rulekit rule authoring *and* modelkit structure editing. The session-bound handles are the **`Editor`** family — `GroupEditor` / `FieldEditor` / `RuleEditor` / `ComputationEditor` / `TypeDefEditor` / `IncludeEditor` / `ConfigEditor` — a **complete surface**: every editable element has an editor, so asking "what editors exist?" finds no gaps. The earlier working name *"cursor"* was dropped — naming decision + rejected alternatives (`Node`, `Handle`) in proposal §18.7 (fork #7). **No deferrals** (§18.7): `IncludeEditor` also does `setReference` (re-point) and `rename` (header + every mount move in step, so rules over mounted content don't ripple).

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
```
*(There is **no** session-as-actor / ref-returning duplicate — no `NumberRef d = s.addField(order, …)`. The editor's fluent edits + relative navigation are the **whole** editing story; the lower-level session mutator, if it exists at all, is an internal delegate the editor forwards to, not public surface.)*

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

> ✅ **The whole block runs today** — test-backed by `GroupEditorTest` / `FieldEditorTest` / `RuleComputationEditorTest` (2026-06-12): `EditSession.group(path)` → a `GroupEditor`, chaining `addField` (kind or `FieldTypeSpec`) / `addTypedefField`, relative navigation to pure refs across **every** kind (`num`/`str`/`date`/`time`/`dateTime`/`bool`/`confirm`/`enumOf`/…), the nested-builder `addGroup`/`addRepeatableGroup` lambdas, editor-scoped `addRule`/`addComputation`, and `m.rule(order)` taking the editor straight as the scope — it downgrades to its `GroupRef` internally (the AST stores only the pure ref) via a `GroupReference` type both `GroupRef` and `GroupEditor` satisfy (no `rulekit → dm.edit` dependency). The `addRule(…)` chain above is **abbreviated** (the full builder — `name`/`code`/`severity`/`message`/`build` — is in the test).

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

### 5c.1 Composition — parameterized document-part builders ✅ (2026-06-12)

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

## 5d. Working across models — includes & type-defs ✅ (2026-06-12)

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

## 5e. Run rules against data — does it actually fire? ✅ (runtime evaluation)

Beyond "is this rule kernel-valid?" (the static gate) is "does it do the **right thing** on real data?" — the verify-by-execution angle that catches an *inverted-but-valid* rule. `KernelAdapter.evaluate` runs a model's rules over a **document instance** (a nested, name-keyed tree — repeatable groups as lists, field values as their kernel string form) and reports which rule **codes** fired; its peer `compute` reports what each field computes. (CLI: `eval-doc` / `compute-doc`.)

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

## 5f. Comments — author `;;` notes, preserved across the loop ✅ (2026-06-12)

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

## 5g. Validation-pattern catalogue — idioms that bake in polarity + a valid error field ✅ (2026-06-14, FEATURE-GAPS G2)

A BA does not re-derive "delivery can't precede the order" from scratch — they reach for the *date-order* idiom. The pattern catalogue (`rulekit.catalog.PatternCatalog`, the rule-level peer of `OperatorCatalog`) makes that an API: a named idiom, parameterized over a few field refs, that produces a **kernel-valid rule** with the two hardest traps solved structurally — the **violation polarity** (an A12 rule fires on the error scenario) and a **referenced error field** in scope (the `MVK_ERROR_FIELD_NOT_REFERENCED` law).

The five built-ins span the BA-doc's typical tasks: **date-order**, **required-when** (field-equals-value form), **mutually-exclusive**, **at-least-one-of**, **sum-of-line-items**. Each produces its condition through the **typed DSL** — so it's correct *by construction*, not merely rejected if wrong:

<!-- teaser -->
```java
PatternEntry dateOrder = PatternCatalog.load().byId("date-order").orElseThrow();

// instantiate against the model with the caller's field refs:
PatternEntry.Instantiation it = dateOrder.instantiate(boundModel,
        Map.of("earlier", "/Order/OrderDate", "later", "/Order/DeliveryDate"));

it.condition();    // "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(…) < 0"
it.errorField();   // "/Order/DeliveryDate" — referenced, in scope (a legal error field, baked in)
it.suggestedCode();// "DATE_ORDER"
```

The condition is built — not string-templated — inside the pattern: `Dsl.allFieldsFilled(earlier.field(), later.field()).and(Dsl.differenceInDays(earlier, later).lt(0))`. The `skeleton` string an agent browses (`${earlier}`/`${later}`) is illustration; the typed build is authoritative.

**Two design decisions worth recording.** *(1) Typed built-ins, data-extensible later.* The shipped idioms are code (typed fragments — correct by construction, composing the §5c.1 document-part builders), behind a `PatternEntry` **interface**. That interface is the seam a future *user* extension plugs into — a `${param}` data-template loaded from a workspace file and kernel-validated on load — so "users extend the catalogue" is additive, not a rewrite. The tension is real and was chosen consciously: typed = correct-by-construction but not user-authorable without recompiling; data = user-authorable but validated only at runtime (consistent with the BA persona's no-compile-step model, D7). *(2) The no-rot gate.* Exactly as the operator catalogue CI-validates every `validExample`, every pattern's `example` is instantiated and run through the real kernel (`PatternCatalogKernelTest`) — a pattern that no longer scaffolds a valid rule fails the build.

**CLI peer.** `dmtool patterns` lists the idioms, `patterns <id>` details one, and `patterns <id> --arg k=v …` scaffolds a rule-spec and **auto-checks** it (the G4 verdict folded in); `--apply` persists it through the same path as `rule add`. *Test-backed: `rulekit.catalog.PatternCatalogTest` (structure) + `PatternCatalogKernelTest` (the kernel no-rot gate); `cli.PatternsVerbTest`. Demo: `examples/cli-tour.md`.*

## 6. Semantics as data (F7, N6) ✅ — the catalog is queryable today

<!-- snippet: catalog -->
```java
OperatorCatalog catalog = OperatorCatalog.load();                                  // ✅
OperatorEntry e = catalog.byId("GroupFilled").orElseThrow();
e.meaning();      // "True iff the referenced group is filled. For a repeatable group this means the row EXISTS — ..."
e.gotchas();      // ["'filled' means 'row exists', not 'row has data' — ...", "an enclosing GroupFilled(Group) counts as referencing the rule's error field ..."]
e.keywords().de(); // "KontextAngegeben"
e.invalidExample(); // dsl + the exact MVK_* + the fix — every example kernel-validated in CI (N7)
```

An LLM reads exactly this. The **self-sufficiency eval** (N6) is the runnable acceptance test: given only the catalog + skill + javadoc, author and correctly explain a rule per covered operator — where it can't, the material (not the LLM) is deemed incomplete.

### 6b. The model-context surface (F9a, `context` package) — `describe`/`find` ✅ 2026-06-04, `whereUsed`/`export` ✅ — "explain the rule on birthdate"

The agent's entry point is usually a *name*, not a rule. Three projections compose the answer. (Shipped API is instance methods — `new ModelContext(m).describe()` / `.find(...)` / `.explain(...)`; the `Context.*` static shape below is the original sketch.)

<!-- teaser -->
```java
ModelCard card = Context.describe(m);             // ✅ 2026-06-04 — the agent's map, PROGRESSIVE: default is the
                                                  //    skeleton (group tree + counts/kind histograms — low-1000s
                                                  //    of tokens even on a 10k-field model); drill down per group:
ModelCard addons = Context.describe(m, m.group("/Subscription/Addons"));
List<FieldMatch> hits = Context.find(m, "birthdate");   // ✅ 2026-06-04
// → [/Customer/BirthDate (date, format dd.MM.yyyy, in group /Customer)]   — dumb string ranking, deliberately;
//    fuzzy/NL interpretation is the LLM's job

Set<String> rules = adapter.whereUsed(field);                   // ✅ 2026-06-07 exact (adapter) — validation rules
Set<String> comps = adapter.whereUsedInComputations(field);     // ✅ 2026-06-08 — computations, attributed separately
// → rules: [/Customer/AgeCheckRule, /Customer/BirthDateRule]   computations: [/Customer/AgeComp]
//   (a computation is also a rule in the kernel's getAllRules(); the two legs keep the kinds distinct — a field
//    read only inside a computation's operation shows up under computations, never under rules)

if (adapter.read(rule) instanceof ReadResult.Converted c) ctx.explain(c.condition());  // F9 explain ✅ → RuleExplanation
ctx.explainComputation(comp);                                   // ✅ 2026-06-08 → ComputationExplanation
```

The N8 seam: `context` lives in the public-tier core; the exact `whereUsed` comes from the Phase-2 adapter (`getDirectlyReferencedEntities`, one kernel call per rule, no converter needed). **Rules and computations stay attributed separately** — `KernelAdapter.whereUsed`/`ruleFullNames`/`readAll` are validation rules only, the computation peers are `whereUsedInComputations`/`computationFullNames`/`readAllComputations`/`readModel` (a computation is stored as a rule in `getAllRules()`, so the partition is deliberate; see KERNEL-INTEGRATION-NOTES "`getAllRules()` ⊇ computations").

**And for agents, the primary form is files** (owner direction — agents are trained to export-then-grep). The same projections, two persona surfaces (N10): the **in-repo developer** calls them programmatically; the **modeller/BA's** agent runs the **CLI verb** (which serializes the same projections) — no Java toolchain.

<!-- teaser -->
```java
// developer, in their own project — programmatic projection (core):
Context.export(m, Artifact.FIELDS, System.out);   // 🔮 stream-first primitive — stdout pipes like git
Context.export(m, Path.of("ctx/"));               // 🔮 convenience: all four artifacts to files
```

```bash
# modeller/BA — the standalone CLI verb (JVM bundled; no jbang, no .m2):
# one-shot question — pipe (mind JVM startup: seconds, not git-milliseconds):
dmtool export model.json fields | grep -i birthdate
# repeated exploration — export once, then grep (the recommended loop):
dmtool export model.json --output ctx/        # all artifacts
grep -i birthdate ctx/fields.jsonl
# {"path":"/Customer/BirthDate","kind":"date","format":"dd.MM.yyyy","group":"/Customer","repeatsUnder":null}
grep BirthDate ctx/rules.jsonl
# {"rule":"/Customer/AgeCheckRule","slot":"errorCondition","condition":"DifferenceInYears(BirthDate, Today) < 18","referencedFields":null,"resolution":"heuristic"}
```

The 10k-field problem dissolves: the files hold everything, only grep hits enter context; `model-card.md` (skeleton) is the single read-whole artifact. JSONL = one line per fact = greppable *and* `jq`/Python-parseable; each artifact stamps the snapshot's model id/version.

### 6c. Navigating the model (finding 9, Level 1) ✅ — jOOQ-`Meta`-style, no query language (implemented 2026-06-04; `NavigationTest`)

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

Safe by F0's snapshot semantics (immutable binding → internal memoization, clients never index); **Level 2** (glob paths, ancestry axes, annotation queries) stays parked behind named triggers — see finding 9. (The *static* codegen-refs counterpart, once mooted as L2b, was **dropped** — proposal D7; the ergonomic layer **L2c** — runtime value refs + a session-bound editor, across both kits — replaces it (✅ §5c).)

## 7. The LLM session (N1, N10, §14) 🌓 first skill draft landed 2026-06-05 (`skill/SKILL.md`) — the loop the skill teaches, for both personas

> **Skill status:** the first BA-path skill draft exists and was **eval-measured** (a clean Opus subagent, given only the CLI + catalog + skill, went from multi-attempt/medium-confidence to **first-attempt/high-confidence** on the T1 authoring task, with the silent-inversion polarity trap closed by the skill's polarity section). Method, lifts, and caveats in [`EVAL-SPEC.md`](./EVAL-SPEC.md). Structurally-distinct benchmark tasks **T2–T6** now span the families (field-vs-field, pattern, aggregate+Having, date, value-list+boolean; golds kernel-verified) — answering the "T1 ≈ the skill's worked example" caveat — and **T7** adds the first **comprehension** task (the `order-ruled` fixture; the oracle asserts its rules read to `Converted`, readable by `explain`). Remaining: the **Workflow pass@k runner** that drives the agent passes (T3–T6 authoring + T7 explanation).

The **same loop, two surfaces** (N10 — one engine, two interactions): the **developer's** agent (Claude Code) runs these as ordinary calls on rulekit-the-library *inside the developer's own project* — the project compiler is the type-steering, no special vehicle. The **modeller/BA's** agent runs the equivalent **CLI verbs** target-first against the standalone binary (`dmtool -m model rule read/explain/add/modify`, `dmtool -m model model validate …`, JSON in/out) — no JDK, no `.m2`. The steps below show the developer's library calls; each maps 1:1 to a CLI verb the BA path uses.

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

## 8. Polyglot peek (N2, §13) 🔮 deferred (very-future, design-informing only — not on the near agenda) — the same catalog, a Zod-flavored skin

```typescript
import { bind } from "@a12-dmkits/core";                       // reads the SAME catalog JSON + DM-JSON

const m = bind(dmJson);
const fee = m.num("/Subscription/Addons/MonthlyFee");
const proNeedsFee = m.enum("/Subscription/Tier").is("PRO").and(fee.notFilled());
proNeedsFee.render();                                            // identical canonical DSL text
// no TS read/validate: the TS kernel stack has no AST and no buildable runtime (survey §15) — by design
```

The contract is data (catalog + DM-JSON + render rules), so this is a parallel reading, not a port — and naming parity means an agent transfers Java↔TS knowledge 1:1.

## 9. Implementation-status board (canonical)

**This table is the single source of truth for what rulekit has built.** Other docs defer here: the [README](../README.md) gives the outside capability snapshot, [`PROJECT-PROPOSAL.md`](./PROJECT-PROPOSAL.md) states intent (not per-increment status), and [`WORKSPACE-CORPUS-ANALYSIS.md`](./WORKSPACE-CORPUS-ANALYSIS.md) tracks the orthogonal operator-coverage/backlog axis. When a feature lands, update this row (and flip its study snippet 🔮→✅) — not four scattered status lists.

| Study element | Status |
|---|---|
| Handles (`num/str/enumOf/bool/confirm/group/date`), typed conditions, EN/DE render, validator, D14 ids + `PathStyle`, `Dsl.*`, literal overloads, `transform` | ✅ (Phase 1; operator coverage = corpus items 1–6, 8, 9a + iteration 7a–7f — patterns, value lists, count + list aggregates, `*` lists, `repetitionNotUnique(...).from(...)`, `flatten(...).having(filter)`, `currentRepetition`, `num(f).forIndex(...)`, parallel iteration, `customCondition`, dates, field-vs-field) |
| §3.1 fragments, §3.3 library pattern, §6 catalog queries, §2 type-steering | ✅ pattern works today on covered operators |
| §3.2 domain fragments (`withinTolerance`, `chronological`, `uniqueBy`) | all now **buildable** from shipped operators (these are user-side combinators, not a rulekit API): `uniqueBy` ✅ (7b), `chronological` ✅ (dates), `withinTolerance` ✅ (tolerance ops landed 2026-06-07 — `NumberExpr.differsWithTolerance`) |
| §3.4a `sameAddress` (field-vs-field equality) | ✅ string + number (finding 18) + **enum-vs-enum** (`EnumRef.is(EnumRef)`, 2026-06-06) |
| §6c navigation (Level 1: `fields()`/`groups()`/`fieldAt` + strict narrowing), §3.5 `allOf`/`anyOf` | ✅ implemented 2026-06-04 (findings 9–10) |
| §3.4 rule templates | ✅ full form works (navigation + `Rule` object) |
| `Rule` + `Computation` authoring objects (computed relPaths, slot-rule pre-checks, validator bridges, `Calculation.kind()`) | ✅ implemented 2026-06-04 |
| DM-JSON write path: `ofJson`/`add(Rule)`/`toJson`/`writeTo` (snapshot, re-validated, includes-preserving) | ✅ F6 (2026-06-04); `replaceCondition` ✅; **`add(Computation)` ✅ + `removeRule` ✅** (2026-06-07, `ModelWriter`; snapshot + re-validated, `removeRule` throws on a missing path); **`replaceComputation`/`removeComputation` ✅** (2026-06-08 — the computation write-back twins); **slot-based `addComputation`/`replaceComputation(json, DraftComputation, messages)` ✅** (2026-06-08 — the CLI/polyglot computation authoring path, the `addRule`-from-slots peer; shares the `computationNode` builder) |
| **Computation read — the calculated-field read path** (`KernelAdapter.readComputation`/`readAllComputations` → `ReadComputation`) | ✅ **2026-06-08**: the full cycle (read → modify → write-back → re-read identity) over computations, via `ICalculationService.extractCalculationInfo`; the operation vocabulary (constants of any kind, field values, numeric arithmetic/aggregates/coercions, string concat, dates) reuses the comparison-operand converters; non-EXACT rounding → tolerant `Opaque`. **kernel-mm 100% (6/6), Corpus-1 100% (626/626)**; own-domain shape locks `ComputationShapesTest`/`ComputationReadTest`. **Unified whole-model read ✅ (2026-06-08):** `readModel() → ModelRead{rules, computations}` — two disjoint maps (no double-count; `getAllRules()` ⊇ computations so `readAll` is partitioned to validation rules only), `convertedRules()`/`convertedComputations()`/`fullyTyped()`; CLI `read --all` (`adapter.UnifiedModelReadTest`, `cli.ReadVerbTest`). **Computation modify-loop ✅ (2026-06-08):** `KernelAdapter.diffComputation(name, modified) → RenderedDiff` (the `diff` peer — a before/after rendering of the whole computation), and the CLI write/modify verbs `add` (computation-spec) + `modify --computation … --spec …` over multi-file models (`cli.ComputationCliTest`, `adapter.ComputationDiffTest`). Detail: KERNEL-INTEGRATION-NOTES "Computation read" / "`getAllRules()` ⊇ computations" |
| Structured `Diagnostic` (`severity·source·code·summary·where·fix·explain`) + catalog-join + JSON | ✅ F4a first increment (2026-06-04) — KERNEL, LINT, PRECHECK origins land (ENVIRONMENT added with F11) |
| PRECHECK diagnostics — pre-checks carry a structured `Diagnostic` (`PrecheckException`) | ✅ 2026-06-04 (Rule.Builder; still an `IllegalStateException` so fail-fast is unchanged; Computation.Builder + handle refs follow). **F11 split (2026-06-04):** completeness/structural pre-checks stay hard; the kernel-semantic `MVK_ERROR_FIELD_NOT_REFERENCED` (`covers()` heuristic) is *deferred* to the live kernel (`Rule.deferredChecks()`, advisory WARNING) — never a hard `build()` throw |
| Unified diagnose — one call returns KERNEL+LINT+INFO (+deferred PRECHECK, +ENVIRONMENT) for a rule (`lint.RuleDiagnostics`) | ✅ 2026-06-04 (`diagnose(model, rule) → ValidationResult`; KERNEL span-pointed; deferred prechecks deduped against the kernel verdict; F11 kernel-version advisory once per instance) |
| Span-pointing — pin a KERNEL diagnostic's `where.operator` + `where.span` via AST↔problem matching (`lint.SpanResolver`) | ✅ 2026-06-04 (used∩candidate operators → operator-exact join; `where.span` from the renderer emit-map `DslRenderer.renderWithSpans` for the single-node case) |
| "Did you mean" suggestions — nearest path / enum value on fail-fast handle errors (`model.Suggest`) | ✅ 2026-06-04 (Levenshtein, typo-plausible threshold) |
| Lint layer — valid-but-suspicious WARNINGs over the AST (`lint.Linter`, `RK_*` codes) | ✅ pure-AST (contradictory/non-complementary presence, duplicate operand, `RK_UNGUARDED_NUMBER_COMPARISON` empty-number-as-0) + rule-context (`RK_NEG_CONDITION_IN_ITERATION`, `RK_INVERTED_REQUIREDNESS`) (2026-06-04; unguarded-number 2026-06-05, from the dev-path eval) |
| Gotcha/INFO channel — catalog `gotchas` keyed by operators a rule uses (`lint.Advisor`, `RK_GOTCHA`) | ✅ 2026-06-04 (descends nested `Calculation`s via `dsl.OperatorUsage`); gotcha-relevance curation ✅ (`GotchaKind` SURPRISE/AUTHORING/RENDERING, suppresses trivia) |
| Message *interpolation* (F2a) | ✅ 2026-06-07 — `Dsl.fieldValue(FieldRef)` renders the kernel `$<path>.value$` placeholder (the no-asterisk rule holds by typing — a `FieldRef` is never a wildcard list; the "referenced in the condition" rule stays the kernel's call, verified end-to-end via `RuleAuthoringTest`). `advice()` was never specified (no spec anywhere) — dropped, not a remainder. **`$Field$` label + `$#Group$` repetition-index ✅ (FEATURE-GAPS §3.6, 2026-06-13):** `Dsl.fieldLabel(FieldRef)` → `$<path>$` (the localized-label form, bare path no suffix), `Dsl.repetitionIndex(GroupRef)` → `$#<groupPath>$` (the repeatable group's 1-based row number) — both kernel-accepted end-to-end with full paths (`MessageInterpolationTest`); the tokens are documented on the `add` schema's `messages.text` field so a CLI agent discovers them |
| F9a model-context: `describe()` model card + `find()` name resolution (`context.ModelContext`) | ✅ Phase-1 half (2026-06-04); `explain` ✅ (F9, its own row); `export` ✅ (CLI verb, 2026-06-05); **`whereUsed` ✅** (2026-06-07, exact via the adapter's `getDirectlyReferencedEntities` — `KernelAdapter.whereUsed` + the `where-used` CLI verb); **`whereUsedInComputations` ✅** (2026-06-08 — the computation peer; `whereUsed`/`ruleFullNames`/`readAll` are validation rules only, computations attributed separately since `getAllRules()` ⊇ computations; CLI `where-used` emits `rules` + `computations`) |
| fork #9 / F3a path policy — relative-to-group paths (`../` to siblings) + keyword-segment quoting | ✅ 2026-06-04 (kernel-verified): relative rendering + single-quoting keyword-named segments (catalog keywords; full lexer set rides N3) |
| N3 keyword-parity anchor — catalog keywords ↔ kernel lexer terminals (`catalog.KeywordParityTest`) | ✅ 2026-06-04 (forward: every catalog/stub keyword is a real terminal, or a language-independent symbol) |
| N3 reverse construct-name parity — every parsetree-api construct-name enum member is cataloged or explicitly excluded (`adapter.KernelConstructParityTest`) | ✅ 2026-06-04 (the "foresee new operators" early-warning: a kernel-added construct fails the test; self-cleaning on stale exclusions; exclusions double as the coverage backlog). MVK error-code parity = the MVK ledger; **reverse *keyword* parity ✅** (2026-06-07, `catalog.KeywordReverseParityTest`: every EN+DE lexer terminal is a cataloged keyword or a reasoned, self-cleaning exclusion — catches a kernel-added keyword/alias/separator) |
| F11 kernel-version tolerance — bias to acceptance, never a self-inflicted false positive | ✅ 2026-06-04: pre-check split (semantic `MVK_ERROR_FIELD_NOT_REFERENCED` deferred to the kernel); runtime skew advisory (`validate.KernelVersion` via `Package.getImplementationVersion()` vs the catalog's `verifiedAgainst`, ENVIRONMENT INFO, once per instance). Read opaque passthrough ✅ 2026-06-04 (sealed `ReadResult` `Converted`/`Opaque`, `readAll` — preserve-the-unknown); CI version matrix 🔮 (single-version until a newer kernel publishes) |
| Kernel-adapter bootstrap — opt-in `adapter` module, `KernelAdapter` over the a12internal read chain (`getMvkServiceForModel` → `ElementContainer.getAllRules`), boundary-converting | ✅ 2026-06-04 (Phase 2 start): `referencedFields` (exact F9a whereUsed), `canonicalConditionText` (kernel parse+format round-trip), `hasValidCondition` (per-rule fast lane); canary = N8 pin+canary |
| F10 read — text→AST converter (`adapter.ConditionConverter`, `KernelAdapter.read`) | ✅ **kernel-mm read is 100% (553/553), 2026-06-06**: composite And/Or; comparisons (num/str/enum vs constant/field, **enum field-to-field**, **enum category-navigation** incl. category-vs-category, **constant-on-the-left** normalization); string patterns; field/group/**root-group** presence; the field-list + group-list quantifier families **and group-scoped field-fill** (`AllFieldsFilled(Group*)` via the new `GroupListRef`, nested wildcards faithful); value-list membership (constant **and entity-valued** — field-list vs field-list); **aggregates + `Having`** (`Sum`/`MaxValue`/`MinValue`/`NumberOfDifferentValues`, multi-field + wildcard scope) plus the **count aggregates** `NumberOfFilledFields`/`NumberOfFilledGroups`; the iteration/uniqueness cluster; **dates** (vs `Today`/another date, `DifferenceIn…`); the **`Length`** operand wrapper; **arithmetic** (`+`/`-`/`*`/`/`/`^` → `Arithmetic`); and **custom conditions** (`CustomCondition Y` → the typed, editable `CustomConditionRef`). Re-authored through the handle API (D14-preserving); render→parse→convert→render round-trip oracle. **Tolerant: returns a sealed `ReadResult` (`Converted`/`Opaque`), `readAll()` for a whole DM — an unmodeled construct is preserved verbatim, never lost/corrupt (F11).** **Workspace-corpus (Corpus-1) read 87.0% (1122/1289), 2026-06-06** — driving the operator tail: also wired since are boolean `!=`; **rounding** (`RoundDown`/`RoundUp`/`RoundAccounting`); **string concatenation** (`+`) incl. aggregate/coercion parts; **string coercions** (`RangeAsString` — 1-based syntax over the kernel's 0-based start — and `FieldValueAsString`); **`FirstFilledValue`** (number/date/string); date **shifts** (`AddDays`/`AddMonths`/`AddYears`); date→number **extraction** (`YearFromDate`/`MonthFromDate`/`DayFromDate`/`QuarterFromDate`); the **group-scope** `Not*`/`MoreThanOne` field-fill quantifier branch; and **root-group count** (`NumberOfFilledGroups` over a repeatable root group → `RootGroupCount`). Still preserved-opaque (deferred): `Date()` construction (needs digit-patterned component fields)/`Now`/`Valid`/`Invalid`, tolerance comparisons, `SumOfProducts`, `DateRangesOverlap`, semantic index, plus TIME/DATE_TIME-field sources, non-constant date-add amounts, and time-of-day extraction; `$` outer-correlation (D13) **read complete** (2026-06-07) — Corpus-1 87.0% → **95.0% (1225/1289)**, `$` opaques 127 → **0**: threaded through every field-vs-field kind + `CurrentRepetition($)` + presence predicates (`NoFieldFilled($a,$b)`) + value-list operands (`FieldListRef.outer`), plus the key fix — routing filtered field-list quantifiers (`AtLeastOneFieldFilled(Group*/Field Having …)`) through `flattenScope` (they were dropping the whole filter; caught by the N8 count-check). `verifyOuterPreserved` keeps any un-threaded construct Opaque/verbatim. Authoring of `$` also kernel-validated. **Comparison-tail wins (2026-06-07) — Corpus-1 95.0% → 96.1% (1239/1289):** a **date expression on the *left*** of a comparison (`AddDays(date, n) == [field]` / `<= Today` — the dominant comparison-tail form; `ConditionConverter` now routes a left `IDateAddOperation` through a generalized `dateComparison`) and **arithmetic on *both* sides** (`([a]+1) == ([b]-2)`, via a left-`numericExpr` → `exprVsExpr` fallback). The IComparisonCondition tail (26→11) is now dominated by the **TIME value-kind** family. **TIME + DATE_TIME value kinds (2026-06-07) — Corpus-1 96.1% → 97.7% (1259/1289):** TIME is orderable like DATE (`TimeRef`/`TimeOperand` via `BoundModel.time`, `[ServiceWindowStart] >= [ServiceWindowEnd]`), and DATE_TIME (`BoundModel.dateTime`) decomposes via the full kernel extraction lattice — `HoursFromTime`/`MinutesFromTime`/`SecondsFromTime` (→ number), `DateFromDateTime`/`TimeFromDateTime` (→ date/time, the `DateTimePart` node), and `*FromDate` now reading a DATE_TIME source too (a common `TemporalOperand` supertype). Cleared the whole `DateTimeValues_DM` decomposition cluster. modelkit gained `timeField`/`dateTimeField` to author the kinds. **Read-tail sweep to 99.5% (1283/1289, 2026-06-07):** date-valued `FirstFilledValue`/`Max`/`Min` on a comparison's left; date shift by a non-constant amount (`AddDays(d, [n])`); `SumOfProducts`; enum-valued `FirstFilledValue`; and **`Date(...)` construction + `Valid`/`Invalid`** (the `DateConstruction`/`DateValidity` nodes, bounded component fields, plus `MonthFromDate`/`YearFromDate` over a `DATE_FRAGMENT` source — a 4th `TemporalOperand` kind). **Corpus-1 read reaches 100% (1289/1289, 0 opaque, 2026-06-07)** — the final tail cleared too: `Date(...)` construction + `Valid`/`Invalid`, group-scope `Sum` (expanded to the group's number fields), field-level `RoundDownValue`/`RoundUpValue`/`RoundAccountingValue`/`AbsValue` (`FieldRounding`), a mixed group+field field-fill quantifier (group operand expanded), and a value list with a field entry. Every rule in every loaded model converts to a typed AST. **BA-reference DSL corpus — families 1–6 landed (2026-06-07):** date/time **constant literals** (`[date] > "01.01.2022"`, `[time] > "17:00:00"`); **sub-day time arithmetic** (`AddHours/Minutes/Seconds`, `DifferenceInHours/Minutes/Seconds` over DATE_TIME); **`Time(...)`/`DateTime(...)` construction** + direct **DATE_TIME comparison** (the new `DateTimeOperand`); **numeric coercions** (`FieldValueAsNumber`, `RangeAsNumber`); **operand-list math** (`Abs`/`Min`/`Max`, distinct from field-list `MaxValue`/`MinValue`); and **`NumberOfValueInFields`**. Each catalog + N7 + laws-locked; the curated `adapter.BaCorpusTest` reaches 100% modeled. **`ValueAsDate`** also landed (the partially-known-date resolver — modelkit gained `DatePrecision`/`dateField(d -> d.datePrecision(…))` to author the fixture). **`Now`/`BaseYear`** point-in-time constants landed too (modelkit gained `baseYear(int)`; `BaseYear` is polymorphic — date vs a date field, number vs a number field). **Tolerance comparisons** (`DiffersWithToleranceRange1/2/5/10` → `CompareOp` + a `Tolerance` enum) landed. The residual unmodeled set is now down to specialized constructs: `ValueNotConsistent` (**un-buildable** — its table-backed enum field isn't DM-JSON-serializable, so no fixture can carry it; KERNEL-FINDINGS §8), the multi-form/Vordruck root-group conditions, and a couple of German-only legacy ops. **Renderer↔kernel canonical-parity guardian (2026-06-07, `adapter.CatalogRenderCanonicalParityTest`):** every SUPPORTED catalog example (106) is read, re-rendered, and the kernel's canonical formatter (`FormatterVisitor`) must format *our render* identically — **106/106, 0 opaque**. It caught + fixed 4 read defects the our-renderer round-trip couldn't (2 silent losses: `FieldValuesNotUnique(Items*/Sku)` dropped the `*`, `[field For "x"]` semantic index dropped; 2 coverage gaps: multi-group `NumberOfFilledGroups`, `DateFromDateTime` on the left) |
| F9 explain — structured projection of a condition (`context.ModelContext.explain` → `RuleExplanation`) | ✅ 2026-06-04: operators (catalog meaning + gotchas), references (+model kind), iteration scope, NL gloss, JSON. One mechanism, two consumers — works identically on built and **read** conditions (demonstrated end-to-end on a foreign aggregate rule). **`explainComputation` ✅ (2026-06-08)** — the calculated-field peer (`→ ComputationExplanation`: computed field+kind, common precondition + per-alternative precondition/operation with the catalog glossary, messages, gloss); CLI `explain --computation` (`context.ComputationExplanationTest`, `cli.ExplainVerbTest`) |
| F8 modify-by-re-express + diff + write-back (`dsl.RenderedDiff`, `KernelAdapter.diff`, `BoundModel.replaceCondition`) | ✅ 2026-06-04: re-express a built/read condition (compose/`transform`), `RenderedDiff` (before/after/changed/lossiness, `unified()` + JSON) **preserves the leading `;;` comment** (carried on both sides; `Comments.preserveLeading`, finding 27/§5f) and discloses only inline/trailing comment + formatting loss, and **`replaceCondition` writes the reviewed change back** (new binding, re-validated, `writeTo` persists). Full loop *read → re-express → diff → validate → write-back* tested. **`IProblem→Diagnostic` ✅ (2026-06-07):** the adapter no longer discards the kernel's per-rule problems — `KernelAdapter.diagnose(rule)` (stored condition) and `diagnose(rule, candidateText)` (a proposed condition, via `parseConditionNoExpand`, no mutation) capture the `IProblem`s (each is a `RankedNotification`) and map them through the public core seam `validate.KernelDiagnostics` (the same `Diagnostic.fromKernel` + catalog-join the whole-model `RuleValidator` uses). The candidate overload is the modify-loop's loaded-model fast lane (structured verdict, not an exception) |
| CLI — the modeller/BA standalone front-end (N10, `:cli` subproject, [CLI-SPEC](./CLI-SPEC.md)): pure-testable `Cli.run(args,out,err)` seam + `CliMain` shell; `application` plugin (dev via `:cli:run`/`installDist`, jlink/jpackage Phase-B only) | ✅ scaffold + `export`/`describe`/`validate` verbs (2026-06-05): `export` (fields/groups/rules JSONL + model-card.md, stdout or `--out-dir`), `describe` (F9a model card JSON), `validate` (real-kernel consistency → `{valid,diagnostics[]}`, exit 0/1); serialization in `:cli`, not core; SLF4J silenced; verb behavior unit-tested at the `Cli.run` seam (sub-second, no packaging). **`check`** (candidate → kernel verdict), **`add`** (JSON rule-spec → persisted DM-JSON; built on the dsl-free `write.DmJson`), and the read-back pair **`read`** (rule → AST, tolerant `Converted`/`Opaque`) + **`explain`** (→ `RuleExplanation`) all landed 2026-06-05 — `:cli` now bundles `:adapter`. **`batch`** ✅ (2026-06-05) runs a JSON array of ops in one warm-JVM process (amortizing the kernel's per-process StringTemplate warm-up — the N6 runner enabler); each result is self-describing (`op` index + echoed `id`/`args`) so diagnostics are never mis-attributed. **`modify`** ✅ (2026-06-08) — the F8 re-express loop over the CLI (`--rule` + `--condition` → `RenderedDiff`; structured when `Converted`, textual when `Opaque`): validates, then writes the modified model in place; `--dry-run` previews read-only (a brief read-only `diff` verb was retired the same day as redundant). **`schema`** ✅ (2026-06-05) emits the **JSON Schema** of a verb's I/O on demand (`schema explain|describe|validate|read` outputs, `add|batch` inputs) — drift-locked by `SchemaVerbTest`; `schema explain` documents the `tree.operator → operators[].id` backlink. **Self-explaining surface (2026-06-05, CLI-SPEC §1a — standing requirement):** migrated to **picocli** (one `@Command` per verb → parsing + `--help` + manifest from one source); **`operators`** browses the DSL catalog (list/filter + one operator's full semantics — the proactive complement to `explain`); **`manifest`** emits the machine-readable verb×param map (drift-guarded by `ManifestVerbTest`). So the tool describes its verbs, parameters, I/O shapes, and DSL vocabulary itself — the N1 skill no longer needs to. **Full surface: export·describe·validate·check·eval-doc·compute-doc·add·read·explain·where-used·modify·batch·apply·schema·operators·manifest — complete.** **Inverted-requiredness lint backstop ✅ on `add` and `check`** (2026-06-05): the shared `LintBackstop` reads a rule → AST → `Linter` → warns `RK_INVERTED_REQUIREDNESS` (best-effort) — `add` on the persisted rule (stderr), `check` via a temporary in-memory injection (merged into its diagnostics, WARNING so `valid` is unaffected). The #1 silent-polarity gap is now caught automatically at both BA entry points, not only taught by the skill. **Multi-file models ✅ (2026-06-08, F-RES):** every model-loading verb takes `-w/--workspace` (repeatable; default = the directory of `<model.json>`, so siblings resolve with no flag) and threads a reference resolver through `BoundModel`/`KernelAdapter.ofJson` and `RuleValidator.check(…, resolver)` — so the CLI works on apps that `include`/import other models, not just self-contained ones (`IncludeDirOption` mixin; `cli.IncludeResolverVerbTest`). **Computation write/modify ✅ (2026-06-08):** `add` also persists a **computation** when the spec carries `computedField` (a computation-spec; `cli.ComputationSpec` → `DraftComputation`), and **`modify --computation <name> --spec <file>`** re-expresses a whole computation — so the computation loop (read/explain → add → modify → write-back) is complete over the CLI, on multi-file models too (`cli.ComputationCliTest`). **Runtime evaluation ✅ (2026-06-09):** **`eval-doc`** runs the kernel's `validateFull` over a document INSTANCE (which rules FIRE on the data — whole-model, or narrowed to an existing `--rule` or an injected candidate) and **`compute-doc`** runs `compute` (what each field COMPUTES — value/cleared/error); both read-only (never write `--doc`), mirroring the kernel's validate/compute split. The runtime tier (`IDocumentRtService.validateFull`/`compute`) was promoted from the test-only F-LAW oracle to a public `KernelAdapter.evaluate`/`compute` seam (JDK `Map` in, rulekit records out — N8 boundary held); lets a future N6 judge verify rule/computation equivalence by EXECUTION (`cli.EvalDocVerbTest`/`ComputeDocVerbTest`, `adapter.laws.EvalDocSeamLawsTest`). **Diagnostic-code catalog ✅ (2026-06-13):** the **`diagnostics`** verb is the `operators` twin for diagnostics — `diagnostics` lists every `RK_*` code (filterable by `--severity`/`--source`), `diagnostics <CODE>` explains one (meaning + canonical fix) — all derived from the single-source typed `RkCode` registry, so a code an agent receives is *explorable*, not only self-carried. First piece of the **self-explain + diagnosis harness** ([CLI-CONFORMANCE-SPEC](./CLI-CONFORMANCE-SPEC.md); `cli.DiagnosticsVerbTest`, `RkCodeTest`) |
| **Model-level header metadata** (FEATURE-GAPS §2 Config) | ✅ 2026-06-13: per-locale model **label** + model-level **annotation**, authored (`DocumentModelDraft.label/annotation`) and edited in-session (`ConfigEditor.label/annotation` → `ModelDocument.setModelLabel/setModelAnnotation`), emitted into the header arrays. Verified by a kernel deserialize→serialize **round-trip** (the values survive the kernel reading + re-writing the model — proof of honoring, since the deserializer ignores unknown keys). `modelkit.ModelHeaderTest`. Also closed this session: **index-field auto-rules** (declaring `indexFieldName` ⇒ the kernel auto-generates the uniqueness + mandatory pair — `adapter.laws.IndexFieldLawsTest`); and **include repeatability** (`mount(name, alias, repeatability)` author + `IncludeEditor.mount(…, repeatability)` edit → a repeating mounted sub-form — `modelkit.IncludeRepeatabilityTest`); and **include exclude-rules/exclude-computations** (`MountOptions.single().excludingRules()` → `mount(…, MountOptions)` / `IncludeEditor.mount(…, MountOptions)` / CLI `include add --exclude-rules` — the kernel drops the included model's rules/computations at expand, fields stay — `edit.IncludeExcludeRulesTest`) |
| **Field-level metadata — flags + descriptions** (FEATURE-GAPS §3.5) | ✅ 2026-06-13: the kernel field flags `.global()` (partial-validation relevance) and `.notPersisted()` (the kernel `transient` flag), plus three per-locale text slots distinct from the label — `.internalDescription(loc,t)` / `.externalDescription(loc,t)` (element-level) and `.helperText(loc,t)` (field-level). On the shared `FieldAttrs` base, so every field builder gets them; `ElementDraft.Field` carries them (pre-§3.5 5-arg ctor kept as a delegate). Emitted in the right place per the kernel serializer (global/transient/helperText in the `Field` body; descriptions element-level) and **verified by kernel read-back** (`ModelIndex.field(path) → IField`). `modelkit.FieldLevelMetadataTest` |
| **Field-config completeness sweep** (FEATURE-GAPS §3.4) | ✅ 2026-06-13: the metamodel-mined field-type slots modelkit didn't surface — **Number** `fractionDigits(min,max)` range, `zeroNotAllowed()`, `leadingZerosAllowed()`; **String** `suggestions(locale, …)` (the kernel `hintList`), `lineBreaks()`, `alphabeticalSorting()`; **Enum** `alphabeticalSorting()`, `invalidValueMessage(text)` (the localized `errorMessage`); the **Custom** field kind (`customField(name → .type(...).minLength/maxLength)` → the `CustomFieldType` discriminator); and the number **unit** `.unit(NumberTrait.AMOUNT|PERCENT|PERMILLE)` → the lowercase `trait` slot (api.ts contract; `AmountWithCent` is a separate *derived* cross-field-check category, not this slot — KERNEL-FINDINGS). Each is a `FieldTypeSpec` slot + builder method + emit, **verified by kernel read-back** via the new `ModelIndex.fieldType(path)` — because the DM-JSON deserializer ignores unknown keys (KERNEL-FINDINGS), emit+load-valid alone proves nothing. Custom round-trips **structurally** (name + bounds; value validation needs a registered validator). The `NumberSpec`/`StringSpec`/`EnumSpec` records keep their pre-§3.4 constructors as convenience delegates (zero churn at call sites). **CLI: the whole sweep + §3.5 metadata is reachable via the rich `field add <spec.json>`** (`FieldSpec` parser → the kernel; input contract on `schema field add`; `cli.FieldAddSpecTest`). `modelkit.FieldConfigExtrasTest` |
| **Scoped single-rule test (`rule test --doc`)** (FEATURE-GAPS §3.3) | ✅ 2026-06-13: the rule-first, suppression-aware runtime polarity check — `KernelAdapter.testRule` packages over `evaluate()` and returns a three-way `RuleTestOutcome.Verdict`: **FIRED** (a message carries the rule's path), **PASSED** (evaluated, condition false), or **SUPPRESSED** (never evaluated — a `referencedFields` field drew a formal-check error, so the kernel skipped the rule). The distinction is the point: a plain "did it fire" answer reports a suppressed rule as `fired:false` and misleads. CLI `rule test <rulePath> --doc <instance>` (read envelope; `data.{verdict,fired,suppressedBy[]}`); rejects a computation name. Builds on the existing `model eval --rule` scoping (which it complements, not replaces). The `--doc` parse is now one shared `DocInstance` helper across `model eval`/`model compute`/`rule test`. `adapter.laws.RuleTestLawsTest` (fires/passes/suppressed) / `cli.RuleTestVerbTest`; demoed in `cli-runtime.md` |
| **Canonical condition format (`rule format` / `computation format`) + EN↔DE** (FEATURE-GAPS §3.2) | ✅ 2026-06-13: the kernel's *own* canonical text for a rule/computation condition — a parse+format round-trip via `KernelAdapter.canonicalConditionText`, with a `(…, ConditionLanguage)` translate peer over the kernel's `formatAndTranslateConditionText` (the engine is the authority on the EN↔DE keyword switch, complementing `DslRenderer(DE)`). Verbs `rule format` / `computation format` (`--lang EN|DE`; a read envelope, text under `data.canonical`); each refuses a name of the wrong element kind. The adapter seam takes rulekit's `ConditionLanguage` (not a raw `Locale`) because the kernel recognizes **only** `Locale.US`/`Locale.GERMANY` and silently falls back to German for any other locale (KERNEL-FINDINGS) — the footgun is made unrepresentable at the N8 boundary. Lossy for a `$` correlation (a display/normalization read, never a write-back). `adapter.KernelAdapterFormatTest` / `cli.FormatVerbTest`; demoed in `cli-tour.md` |
| **modelkit structure read + round-trip** (`modelkit.ModelDocument`, M7) | ✅ 2026-06-10: read the authored tree (kernel-free), enumerate header/config/includes/typedef-ids + the group/field tree (name-path, field-type, repeatability), lossless `toJson()` round-trip (content-equal, idempotent, kernel-valid). `ModelDocumentTest`; §5b |
| **modelkit structure edit** (`ModelDocument`, M8/M10+) | ✅ 2026-06-10: add/modify/delete fields + groups (`addField`/`addGroup`/`addRepeatableGroup`/`removeField`/`removeGroup`/`setRepeatable`; **Multi-Select group template ✅ 2026-06-13** — `multiSelectGroup(name, m -> …)` / `ModelDocument.addMultiSelectGroup(…)` / CLI `group multiselect`: a `usageType:"multi-select"` repeatable group with one enum/string field, kernel-validated by `MultiSelectGroupRule`; `modelkit.MultiSelectTemplateTest`. **Attachment template ✅ 2026-06-13** — `attachmentGroup(name, a -> …)` / `ModelDocument.addAttachmentGroup(…)` / CLI `group attachment`: the kernel's canonical 8-field + 4-rule + `content`-config composition, the generator owning the skeleton and the caller tuning only the knobs (size/MIME/category/repeatability, per-rule message overrides via the `AttachmentRule` enum); `en_US`/`de_DE` only; `group remove` takes its internal rules with it. `modelkit.AttachmentTemplateTest`); the tail — doc config (`setDecimalSeparator`/`setTimeZone`/`setConditionLanguage`/`setFieldRefByShortNameAllowed`/`setBaseYear`/`setSupportedCharacters` — the runtime-enforced allowed-character set, KERNEL-FINDINGS), type definitions (`addTypeDefinition` — incl. a rich `FieldTypeSpec` overload — and `addTypedefField`; **type-def-only model + import ✅ 2026-06-13** — `DocumentModelDraft.typeDefModel(id)` authors a root-less type-def library, a consumer pulls it in via `importTypeDefs(ref)` / `ModelDocument.addImport(ref)` / CLI `typedef import` → a `purpose=typeDefinitions` reference, types merged by id; `modelkit.TypeDefModelImportTest`, KERNEL-FINDINGS), includes (`addInclude`/`mount`); `validate()`/`validate(resolver)` = the kernel gate (both rejection tiers → `ModelConsistencyException`). `ModelEditTest`/`ModelStructureCoverageTest`; §5b. **Richer field types + metadata ✅ 2026-06-10:** `addField(FieldTypeSpec)` (string bounds/pattern, number scale, enum + categories, date format/precision, typedef ref), `setFieldType` (re-type), `setLabel` (per-locale), `setAnnotation` (`ModelMetadataTest`); `setRequiredness` (2026-06-12, `FieldEditorTest`). **CLI verbs ✅ 2026-06-10:** `add-field` (`--kind`/`--typedef`) · `add-group` · `add-typedef` · `add-include` · `remove` (safe-delete refuse-or-`--cascade`, structured) — plus the combined rulekit+modelkit flow, all demoed in `cli-structure-edit.md` (CI-verified). **Tail:** a CLI `set`-verb for labels/annotations + field re-type (library-only today); placement/top-level constraint surfacing |
| **unified edit session** (`edit.EditSession`, M9) | ✅ 2026-06-10: compose structure (modelkit) + rule/computation (rulekit) edits over one DM-JSON — the orchestration layer above the two ⊥ kits; validates once. `EditSessionTest`; §5b. Delete-safety (dangling-ref rejected at the gate) locked + documented (KERNEL-INTEGRATION-NOTES) |
| **`apply` session verb** (CLI-SPEC §7, the transactional CLI edit flow) | ✅ the north-star: an ordered array of op-records `{target,op,…args}` on ONE `EditSession` → one terminal kernel gate → one write, **atomic rollback** (a mid-sequence failure writes nothing); per-op `staged`/`read` envelopes + the `{ops,committed,failedAt,written,results[]}` summary; transient-invalid states tolerated between ops. Op-set: **Local** (field/group/typedef/include add·remove, rule/computation add·modify, config modify, the structure reads) **+ the reference-rewriting refactors** field/group rename·move, rule rename, typedef rename (**2026-06-13**, `ApplyRefactorTest` — each runs its §6 gate against the live session, a refusal rolls back; `extract`/`inline` stay standalone). Op-arg validation (`RK_UNKNOWN_ARG`/`RK_UNKNOWN_OP` + did-you-mean) + the cross-type `batch`↔`apply` corrective; dispatch is an exhaustive `switch` over the `Op` enum (no string drift). `ApplyVerbTest`/`ApplyRefactorTest`; `schema apply` (CI-executed examples). Distinct from `batch` (stateless per-op dispatch, no rollback) |
| **L2c — refs/editor ergonomic layer** (binding level, proposal D7) | ✅ **implemented 2026-06-12** (`GroupEditorTest` / `FieldEditorTest` / `RuleComputationEditorTest`): the **`Editor`** family over `EditSession` — `GroupEditor` (relative navigation to pure refs for any kind; chaining `addField` (kind/`FieldTypeSpec`) / `addTypedefField` / `addGroup` / `addRepeatableGroup` incl. nested-builder lambdas; editor-scoped `addRule`/`addComputation`; `.ref()` downgrade; accepted as a rule scope via `GroupReference`), `FieldEditor` (re-type/label/annotate/requiredness), `RuleEditor`/`ComputationEditor` (modify/remove by held name — incl. a typed-builder formula re-express; **`RuleEditor.severity(...)` / `.messages(...)` in-place edits ✅ FEATURE-GAPS §3.7, 2026-06-13** — `EditSession.modifyRuleSeverity`/`modifyRuleMessages` → `ModelWriter.replaceSeverity`/`replaceMessages`, condition untouched; also **CLI `rule modify --severity/--message`**; `RuleComputationEditorTest`/`RuleVerbTest`), plus `TypeDefEditor` (`setType`/`remove`), `IncludeEditor` (`mount` — incl. `MountOptions` for repeatability + rule/computation exclusion, §3.9 — / `remove`), `ConfigEditor` (`s.config()` chaining setters, incl. `supportedCharacters` + the model `comment`). **Element metadata CRUD ✅ 2026-06-13:** a shared `ElementMeta` builder mixin (describe/annotate, factored out of `FieldAttrs`) + element-path edit (`ModelDocument`/`EditSession`/the editors `setInternalDescription`/`setExternalDescription`/`setAnnotation`/`remove…`) cover **field, group, rule, and type-def** (by id) uniformly — plus the CLI **`meta <ref>`** verb (read/set/remove) and the model `comment`; `internal/external description` + `annotations` are element-wide (`IDescribed`/`IAnnotated`, KERNEL-FINDINGS). `modelkit.ElementDescriptionTest`/`edit.ElementMetadataEditTest`/`cli.MetaVerbTest` — a **complete editor surface** (every editable element has one; `IncludeEditor` includes `mount`/`remove`/`setReference`/`rename` — no deferrals). Document-part builders compose with no new machinery (§5c.1). AST holds pure refs only; replaces the **dropped** L2b codegen. Naming `Editor` (not *cursor*/*node*): §18.7. Design: §5c / finding 23 |
| **structure refactors — the safety-gate layer + Tier-1/Tier-2/Tier-3 refactors + move** (CLI-SPEC §6) | ✅ 2026-06-12: a uniform **refactor safety gate** — `SafetyGate` / `SafetyChecks` / `Refusal` (check → refuse-with-diagnostics → mutate; CLI-SPEC §6 standing principle); safe-delete adopted it (its two hand-rolled refusals de-duplicated). **Tier-1 `typedef rename` ✅** (rewrites the declaration + every using field's `typeDefinitionId`, no condition DSL; gate refuses a taken id `RK_NAME_EXISTS`); **`typedef extract` / `inline` ✅** (factor fields sharing one concrete type into a shared definition + repoint, and the inverse; extract gate refuses a taken id + a type mismatch `RK_TYPE_MISMATCH`, never coercing). **Tier-2 reference-preserving rename ✅** — `field` / `group` / `rule rename <path> --to <newName>`: rename the declaration **and rewrite every reference** to it (rule conditions, error-entity paths, computations) via the kernel's `MoveSupportDM` (wrapped as `adapter.ReferenceRewriter`), then re-validate; a group re-homes its whole subtree and references *into* it are rewritten too (starred refs follow). The gate refuses only a name **collision** (`RK_NAME_EXISTS`); `rule rename` is collision-only (rules aren't referenced). **Tier-3 `group extract` ✅** — lift a subtree into a sub-model re-mounted at the same name (paths preserved; writes two files, base + `<reference>.dm.json`); the gate refuses a repeatable group (`RK_REPEATABLE_INCLUDE_ROOT`) or a subtree rule referencing **outward** (`RK_OUTWARD_REFERENCE`), and the composed pair re-validates **before** anything is written. **`include inline` ✅** — the inverse: fold a mounted include's content back into the base as a real group (resolves the sub-model by id from the include-dir), dropping the reference; extract↔inline round-trips. **Reference-preserving `field` / `group move` ✅** — `… move <path> --to <newParentGroup>`: relocate the node under a new parent (re-prefixing its + descendants' ids, `ModelDocument.moveField/moveGroup`) **and rewrite every reference** to its new path via the same `MoveSupportDM` engine, then re-validate; a group re-homes its whole subtree. The move gate refuses a missing destination (`RK_NO_SUCH_GROUP`), a group moved into its own subtree (`RK_MOVE_INTO_SELF`, would orphan it), or a name collision at the destination (`RK_NAME_EXISTS`). Structural **referrer queries ✅** (`fieldsUsingTypeDefinition` / `mountsOfInclude`, task #2). `SafetyGateTest` / `StructuralReferrerTest` / `TypeDefRenameTest` / `TypeDefExtractInlineTest` / `RenameRefactorTest` / `MoveRefactorTest` / `ExtractAsIncludeProbeTest` / `ExtractGroupAsIncludeTest` / `TypedefRenameVerbTest` / `TypedefExtractInlineVerbTest` / `RenameVerbTest` / `MoveVerbTest` / `GroupExtractVerbTest` / `IncludeInlineVerbTest` / `ReferenceRewriteTest`; demoed (CI-verified) in `cli-structure-edit.md`. **Reference-rewriting engine ✅** — `adapter.ReferenceRewriter` wraps the kernel's `MoveSupportDM` (FEATURE-GAPS-PROPOSAL §3.1; plain + starred refs both follow a rename, plus a group's index/sort-field refs — KERNEL-FINDINGS §10), wired into `field`/`group rename` **and `field`/`group move`**. **The FEATURE-GAPS §3.1 refactor set is complete** (the eval task `03-rename-field` is now active — Layer-1 oracle-backed). **In-session refactors ✅ (2026-06-13):** the reference-rewriting refactors (field/group **rename·move**, **rule rename**, **typedef rename**) also run as **`apply` op-records** — a single `cli.StructureRefactor` seam single-sources each op's gate + entity-moves + node change, so the standalone verb and the in-session apply op share one gate (can't drift); inside `apply` the gate runs against the **live (transiently-edited)** model and a refusal rolls the whole sequence back (`ApplyRefactorTest`). `extract`/`inline` stay standalone (multi-artifact). Fixed a latent bug surfaced doing this: the kernel serializer **omits a childless group's `elements` array**, so `move` into a freshly-added empty group failed "no group at path" (standalone too) — `DmJson` now materializes it (KERNEL-FINDINGS "Serialization shape"; `MoveVerbTest.movesAFieldIntoAChildlessGroup`). |
| **self-explain + diagnosis conformance harness** *(CLI tool — not DSL/API)* | ▶ The CLI's self-describing + diagnostic contract — `manifest`/`schema`/`diagnostics`, the `RkCode` registry, the conformance fuzzer + **matrix**, the per-verb **demonstrations**, and the **gap-finder** — is CLI-tooling, outside this study's DSL/API scope. It lives in **[CLI-CONFORMANCE-SPEC](./CLI-CONFORMANCE-SPEC.md)** (contract + gates + tests there). Its one API-facing half — the public `RkCode`/`Diagnostic` model a library consumer receives from `ValidationResult.diagnostics()` — is documented in that spec's "Library consumers" section. |
| TS twin | 🔮 **deferred — very-future; design-informing only, not on the near agenda** (Phase 4) |

## 10. Design findings (the review surface's output)

1. ✅ ~~`Dsl.*` facade~~ — implemented (handles-only factories; string-path convenience stays on `BoundModel`).
2. ✅ ~~Scale-aware literals~~ — implemented; premise corrected: the kernel's scale check is **field-vs-field only**, literals are exempt (verified) — overloads are rendering-fidelity; D13's pre-check waits for field-operand comparisons.
3. ✅ ~~`transform` rewriter~~ — implemented (bottom-up, identity-preserving; calculation twin triggers with the first nesting calculation node).
4. **Rule identity matters for the edit loop** — `ExistingRule` (full name + slots + raw text) should be designed *with* the `Rule` authoring object (F2a), not after.
5. ✅ *(implemented 2026-06-04)* **`ReadResult` as a sealed result** (`adapter.ReadResult` = `Converted(condition)` / `Opaque(canonicalText, reason)`) — converter gaps become data, not exceptions. `KernelAdapter.read` is tolerant (preserves an unmodeled construct verbatim, never corrupt — F11 "preserve the unknown"); `readAll()` reads a whole DM per-rule. The strict `UnsupportedConstructException` stays the converter's internal unwind signal.
6. **LLM execution surfaces — two personas, one engine** (2026-06-04; **revised same day, N10**). *Original framing (now demoted):* a compile-first **single-file Java / JBang** snippet vehicle, so a failing `javac` hands the agent the type-steering fix before execution. *The revision:* that "developer execution vehicle" added **no real value for developers and missed use cases** — a developer uses rulekit *as a library inside their own project*, where Claude Code edits the project source and the project's own compiler supplies exactly that compile-first steering. The premise "the LLM needs a special vehicle" only ever held for the **modeller/BA**, who can't write Java — and for them the answer is a **standalone CLI** (verbs + JSON in/out, JVM bundled), not JBang (which is itself an install). So the two imagined vehicles (JBang-for-devs + CLI-for-BAs) **collapse to one** (the CLI), shrinking blast radius. *Net:* **developer** = no bespoke vehicle (in-repo library + project compiler + `RuleDiagnostics`); **BA** = the CLI (safety at runtime via builder pre-checks + kernel `Diagnostic`s). The JBang / `java --source 21` idea survives only as a possible internal mechanism for the **N6 self-sufficiency eval** harness. See proposal **N10 / D16 / §14**.
7. **`explain` should flag suspicions, not just facts** — cheap heuristics (inverted predicate, unguarded negative condition on a repeatable group) over the same projection. *(2026-06-04: promoted to its own requirement **F4a — rich diagnostics**; this finding is its lint half. See finding 17. First lint increment ✅ 2026-06-04 — `lint.Linter`: contradictory/non-complementary presence + duplicate operand, all pure-AST; the model-context heuristics named here follow.)*
8. **Diff must disclose lossiness up front** — what re-rendering drops belongs in `RenderedDiff`'s contract. *(Refined by finding 27: the leading `;;` comment is now preserved, not dropped; only inline/trailing comments + non-canonical formatting are lost, and that's what the diff discloses.)*
9. ✅ *(implemented 2026-06-04, same-day design)* **The model index gets a Level-1 navigation surface** — the unifying design for rule templates (§3.4), F9a's `find`/`describe`/`export` (which iterate everything anyway), and the fork-9 renderer internals (ancestry, short-name uniqueness — built regardless). Shape: `m.fields()`/`m.groups()`/`m.fields(group)` return **streams of rich info handles**; `m.fieldAt(path)` is the non-throwing peek; **matching is plain `Stream.filter`** — deliberately *no* query language/matcher API (the jOOQ `Meta` restraint; XPath-style DSLs rejected); **info handles narrow to typed authoring handles** via `asNum()`/`asStr()`/… (lenient exploring, strict authoring — jOOQ's `Field<?>` coercion pattern). Safe because the binding is an immutable snapshot (F0) — everything memoizes internally. Polyglot contract is "iterate model facts", not `java.util.Stream`. **Level 2 is parked with named triggers:** glob path patterns (a template inexpressible via `filter`), ancestry axes (the iteration cluster's authoring needs), annotation queries (real-model demand) — none gets built without its trigger. (The *static* codegen-refs counterpart, once L2b, was **dropped** — proposal D7; runtime value refs + a session-bound editor — **L2c**, finding 23 / §5c — replace it.)
10. ✅ *(implemented 2026-06-04)* ~~n-ary `allOf`/`anyOf` in `Dsl`~~ — varargs And/Or folds; parenthesization falls out of the existing renderer mixing rule.
11. *(new)* **Fragment libraries are the re-expression vocabulary** — §5.4 shows modify-by-re-express often means *recognizing a named idiom*; the skill should encourage teams to keep a `…Idioms` class and teach the LLM to prefer its fragments over raw composition. (Also the natural home for org-specific conventions.)
12. *(new, 2026-06-04)* **The model-context surface is F9's other half** (owner idea, recorded as **F9a** in the proposal): `describe()`/`find()`/`whereUsed()` in an own `context` package — the agent's entry point is a *name*, and "explain the rule on birthdate" needs name resolution + the field→rules inverse index (slot-labeled) before F9 can fire. Exact where-used rides the Phase-2 adapter (`getDirectlyReferencedEntities`, no converter needed); `describe`/`find` are Phase-1-sized and share finding 9's index queries. **Two constraints recorded same day (owner):** *(a) snapshot semantics* — a `BoundModel` is an immutable snapshot (no events, no staleness; writes produce a new binding; derived lookups memoized internally so clients never build their own indexes); *(b) model scale* — 1k fields / 6-level nesting is a *normal* DM, 10k exists → `describe()` is progressive disclosure (skeleton + drill-down), never a full catalogue.

13. *(new, 2026-06-04)* **Files/stdout are the agent API** (owner direction): the agent-facing form of the context surface is greppable JSONL artifacts (`model-card.md` + `fields.jsonl`/`groups.jsonl`/`rules.jsonl`) — grep is the query language agents are trained on, dissolving both the 10k-field context problem and most of the bespoke-query-API surface (the Java projections stay as the programmatic layer that `export` serializes). **Emitters are stream-first** (git-style: per-artifact stdout default, `--output` optional) so agents can pipe — `… fields | grep -i birthdate` — while the JVM-startup cost (seconds per invocation, unlike git) keeps *export-once-then-grep* (`> ctx/fields.jsonl`) the recommended loop for repeated exploration. The skill's first taught step becomes "export, then grep". Heuristic-vs-exact reference resolution is labeled *in the artifact* (`"resolution":"heuristic"`), keeping agents honest pre-adapter.

14. *(new, 2026-06-04 — owner direction after the locale-law surprise)* **Constraint mining beats whack-a-mole.** Coverage was corpus-frequency-driven (finds operators), blind to *structural laws* — which sit in enumerable sources: the public accessor lists (slot parity: `IRule.guiPatterns/variantFilter/variantErrorMessages` and `IComputation.roundingMode/errorCodesToSuppress/variantFilter` are gaps found *by inspection*), the `MVK_*` enums (~100+ codes — every surprise so far was in there all along; classify each: erased/pre-checked/delegated/pending/unknown→experiment-queue, CI-checked = the **MVK ledger**), the **kernel-mm metamodels** (source of truth for valid DM structure — schema + constraint-rule oracles, EUPL-respecting own-words analysis), and the deserializer tier. Standing discipline: consult `../a12-kernel-demo/KERNEL-SEMANTICS.md` (read-only) before each increment; record what it lacks in our own `docs/KERNEL-FINDINGS.md`. Instruments 1+2+3 shipped 2026-06-04: `SlotParityTest` (every public IRule/IComputation accessor classified; found+deferred guiPatterns/variantFilter/variantErrorMessages/errorCodesToSuppress/roundingMode) and `docs/MVK-LEDGER.md` + `MvkLedgerTest` (194+2 codes, exact-parity CI; unclassified = the experiment queue); `KernelMmIntegrationTest` (instrument 3, opt-in: 15 canonical metamodels load under 30.8.1, 547 rules, zero UNKNOWN kinds — schema-oracle findings in KERNEL-FINDINGS).

15. *(new, 2026-06-04 — from the pattern increment)* **A new operator family's experiments routinely surface adjacent kernel laws — budget for them.** Probing the four pattern slot codes also yielded: the portable-regex-subset gate is *enforced* (Java-valid lookbehind rejected → `Pattern.compile` pre-checks can only be necessary conditions), and **DSL string literals escape by quote-doubling, not backslashes** — which exposed and fixed a latent renderer bug (`quote()` was C-style; any `\`-bearing regex would have rendered wrong). The N7/lock harness then froze both laws for free. Pattern: when an increment's probes return something unexpected about a *neighboring* mechanism (literals, lexing, evaluation order), chase it in the same increment — it is already reproduced, and the fix + lock are cheapest right there.

16. *(new, 2026-06-04 — owner review of §5.2)* **Compose to extend; transform only to operate inside.** A plain extension is `ast.and(more)` — the immutable original becomes a subtree, intent stays on the surface. `transform` is the *surgical* tool and must be presented as such: node replacement (§5.2's `[Tier] != "BASIC"` → `[Tier -> Pricing] == "PAID"` modernization — impossible by composing), placement, bulk rewrites across matches. The first draft used `transform` for a plain extension — machinery over intent, exactly what §2's thesis forbids. Skill consequence: teach the decision rule ("extending? `and()`. editing inside? `transform`."), not just the APIs.

17. *(new, 2026-06-04 — owner idea; first increment ✅ 2026-06-04)* **Rich diagnostics are an LLM-facing requirement (F4a).** Errors *and* warnings, each with **what / where / how-to-fix**, in one structured shape modelled on **LSP `Diagnostic`** (`severity · code · summary · where · fix · explain · source`) — the lingua franca editors and agents already parse. Origins unified: PRECHECK (rulekit pre-checks — hard at build, or *deferred*/advisory for kernel-semantic ones, F11), KERNEL (`MVK_*` from F4), LINT (valid-but-suspicious WARNINGs over the AST — finding 7's heuristics — plus the gotcha INFO channel), and ENVIRONMENT (toolchain notes, chiefly the F11 kernel-version skew advisory). Two researched moves give the leverage: **(a) catalog-join** — a kernel `MVK_*` inherits the matching catalog entry's `fix`/`gotchas`, so kernel diagnostics get the curated, N7-locked fix DB for free (no drift); **(b) applicable fixes** — `fix` carries valid replacement DSL (rustc/`cargo fix` model), not prose, so the agent pastes it; plus **"did you mean"** off the model index (Elm/TS hints) for the top mistakes (enum-not-in-domain, wrong path). The lint layer is the distinguishing half: an LLM can't get "compiles, kernel-accepts, but probably not what you meant" from `checkConsistency`. **Three diagnostic kinds, by the structural-vs-semantic split:** ERROR (structural — F4 catches), WARNING (probable mistake — lint), and **INFO/gotcha (correct-but-surprising — `checkConsistency` is silent because the rule is *valid*)**. The third is sourced from the catalog `gotchas` keyed by the operators a rule *uses* — e.g. authoring `AddYears` surfaces "anchors end-of-February; differs from `AddMonths` across a leap year (`AddYears(28.02.2010,2)=29.02.2012`, not `28.02.2012`)" (owner's 2026-06-04 leap-day find); likewise `DifferenceInMonths` floor-not-count, `Sum`-substitutes-0-vs-`Max/Min`-ignore, `GroupFilled`=row-exists, `FieldFilled`≠strict-complement. Sketch of the shape:
    ```jsonc
    { "severity": "WARNING", "code": "RK_INVERTED_REQUIREDNESS", "source": "LINT",
      "summary": "rule fires when the data looks VALID — did you mean FieldNotFilled?",
      "where": { "rule": "/Subscription/Addons/LegacyFeeRule", "operator": "FieldFilled" },
      "fix": "FieldNotFilled(MonthlyFee) And [Tier] != \"BASIC\"",
      "explain": "FieldFilled is true when the field HAS a value; a requiredness rule fires on absence." }
    ```
    **First build increment — shipped 2026-06-04** (`DiagnosticTest`): `Problem` promoted to `Diagnostic` (`severity·source·code·summary·where·fix·explain`); the `KERNEL` source with `where.rule`; catalog-join (`OperatorCatalog.byInvalidError`) reusing the matching `invalidExample.fix` + operator `meaning`; and `ValidationResult.toJson()`. A real kernel rejection now renders as:
    ```jsonc
    // [CustomerName] < [ProductCode]  (ordering two strings)
    { "severity": "ERROR", "source": "KERNEL", "code": "MVK_INVALID_TYPE_FOR_COMPARISON",
      "summary": "Rule with path '/Order/OrderingProbe': 'Less than' or 'greater than' comparative is only allowed to be used for numbers, amounts and dates. [MVK_INVALID_TYPE_FOR_COMPARISON]",
      "where": { "rule": "/Order/OrderingProbe" },
      "fix": "ordering needs numbers or dates; compare strings with == / != instead",
      "explain": "True iff the left operand is less than or equal to the right." }
    ```
    (The `explain` is from the first catalog entry sharing the code — `<=` — since several ordering ops report it; exact-operator pinning + `where.operator`/span come with AST↔problem linking.) **Lint layer first increment also shipped 2026-06-04** (`lint.Linter`, `LinterTest`): `source=LINT`/`severity=WARNING` findings over the AST with the offending operator in `where.operator` — `RK_CONTRADICTORY_PRESENCE`, `RK_PRESENCE_NOT_COMPLEMENTARY`, `RK_REDUNDANT_OPERAND` (all pure-AST). **Gotcha/INFO channel shipped 2026-06-04 too** (`lint.Advisor`, `AdvisorTest`): an `INFO`/`RK_GOTCHA` note per catalog `gotcha` for the operators a rule *uses*, collected by the reusable `dsl.OperatorUsage` walker that descends into nested `Calculation`s — so authoring `AddYears` surfaces the leap-day disagreement with `AddMonths` even when it's buried in a date comparison. **Gotcha-relevance curation shipped 2026-06-04** — each catalog gotcha carries a `GotchaKind` (`SURPRISE`/`AUTHORING`/`RENDERING`); the channel emits the first two and suppresses `RENDERING` trivia (e.g. "the symbol is the same in every language"). A findings→catalog sweep promoted two verified KERNEL-FINDINGS §9 surprises (`RepetitionNotUnique` parent-position; `CurrentRepetition` must-iterate). **Model-context lint shipped 2026-06-04** (`Linter.lint(BoundModel, Rule)`): `RK_NEG_CONDITION_IN_ITERATION` pre-warns the kernel-delegated `MVK_NEG_CONDITION_IN_ITERATION` for an iterating rule with an unguarded negative presence predicate. **Still to come:** the PRECHECK source, inverted-requiredness, "did you mean", and span-pointing. Recorded as proposal **F4a**.

18. *(new, 2026-06-04 — ✅ string + number landed same day)* **Field-vs-field comparison.** Comparing two fields (`[A] == [B]`, `[A] <= [B]`) was a basic capability rulekit lacked (comparisons took literals only) — it makes whole classes of ordinary rules inexpressible: confirmation match (`[Email] == [EmailConfirm]`), range sanity (`[MinQty] <= [MaxQty]`), reconciliation (`[Net] + [Tax] == [Gross]`), and the multi-field-structure reuse of §3.4a. The recordable consequence is **prioritization**: field-vs-field (currently the vague "D13 later") blocks *two* independent high-value features — those cross-field rules **and** `$` correlation (§5.2/7d). The cheap unlock is real: **string/enum field-vs-field equality has no scale**, so it's buildable now exactly like date ordering was (slice 9a); only **number** field-vs-field needs the D13 decimal-scale pre-check. Done 2026-06-04: `StringRef.eq/ne(StringRef)` + `NumberRef.{lt,le,gt,ge,eq,ne}(NumberRef)`, the number `==`/`!=` pre-checking equal scale. **Enum-vs-enum ✅** (`EnumRef.is/isNot(EnumRef)` + `adapter.EnumFieldComparisonReadTest`). This unblocks `sameAddress` (§3.4a). **`$` correlation landed 2026-06-07 — both directions, essentially complete:** authoring (the `FieldValue.outer`/`EnumCategoryAccess.outer`/`CurrentRepetition.outer` markers + `.outer()` on the handles + renderer, kernel-validated) and read (threaded through every field-vs-field kind + `CurrentRepetition($)`, plus routing filtered field-list quantifiers through `flattenScope` — the lever that converted the product-family overlap template). Corpus-1 read 87.0% → 95.0%; the N8 count-check (`verifyOuterPreserved`) keeps any un-threaded construct Opaque/verbatim (see the F10 row).

19. *(new, 2026-06-04 — owner challenge "is creating a rule not already a form of reading?")* **Authored-rule span-pointing is a Phase-1 increment, not a Phase-2 one — via a renderer emit-map.** The earlier framing ("character spans need read") conflated two things: *having* node positions (the renderer already produces the text, so it can record each node's `[start,end)` — no parse needed) versus *the kernel telling us which node a problem is about* (it doesn't — `getSource()` is rule-granular, the same in both phases). So for rulekit-**authored** rules, character-precise pointing is achievable now; only *foreign*-rule spans truly need read (no AST exists until parsed; the kernel parser may also carry token positions). **The emit-map increment** — *first slice ✅ 2026-06-04:* (a) `DslRenderer.renderWithSpans` returns the text + a `node → Span` map (reusing `render` for leaf conditions, special-casing only `LogicCondition`, so the text stays byte-identical); (b) `SpanResolver` attaches `where.span` when exactly one rendered node carries the pinned operator-id; (c) `Diagnostic.Where.span` (a `validate.Span`) is JSON-serialized as `[start,end]`. Still to do: descend into `Having`-filter sub-conditions and calculation operands; resolve the node *instance* even when the operator repeats. KERNEL-FINDINGS §"getSource() granularity" carries the scope correction. **Do-when:** gate the second slice on a *consumer* that reads `where.span` (an editor/LSP integration, or the N1/N6 skill/eval) — until then it's speculative. When triggered, order is (1) LINT/gotcha self-spanning (cheap, uncapped — those layers already hold the flagged node), (2) emit-map descent into `Having`/calc operands (pull-based), (3) foreign-rule spans with F10/read; note the KERNEL path is capped at the single-node case by the kernel's rule-granular `getSource()`, so don't invest there.

20. *(new, 2026-06-10 — the RT-semantics drain, instrument 6)* **Runtime traps belong in the catalog `gotchas`, and the drain is their supply line.** Mining the kernel's runtime evaluation core ([RT-SEMANTICS-LEDGER](RT-SEMANTICS-LEDGER.md)) produced a class of facts that are neither slot rules (the builder's job) nor accept/reject laws (F4's job): **fires-on-data judgment traps** — exactly what an agent gets wrong with a *valid* condition. The 2026-06-10 batch (each law-locked in `adapter/…/laws/`, each now a catalog gotcha served by `cli operators`): tolerance comparisons don't fire at exactly-N; touching date ranges *overlap* (closed intervals); `Length("")` is 0 and **fires** where a bare string comparison stays silent; operand-list `Min`/`Max` let an empty-as-0 win where the starred aggregates skip; no 0-substitution inside value lists (an empty list-entry field is unmatchable); a formal error suppresses a `NumberOfFilledFields` count entirely yet only *unknowns its own branch* of an `Or`; `RoundDown` is floor, not truncation; division by zero quietly computes nothing (and a bare division can't assign to a scaled field without a rounding wrapper). Two consequences: (a) the **gotcha channel is the formalization vehicle for behavioral traps** — prose findings rot, but a gotcha rides the self-describing CLI straight into the agent's context (the consumer-exists test holds); (b) these traps are **prime N6 task material** — each is a case where a plausible rule validates yet misbehaves on data, the judgment dimension the eval's bare-tool mode probes.

21. *(new, 2026-06-12 — from the N6 baseline's T13 miss)* **Close a measured cold-agent gap at the surface that owns it — tool first, then catalog, then skill.** The dmtool baseline surfaced two real bare-tool traps on the DateRange task (T13): agents **bracketed function arguments** (`DateRange([…], …)` → `MVK_UNEXPECTED_TOKEN` — the comparison-bracket habit leaking into call syntax, KERNEL-FINDINGS §165), and they **didn't provision a message per declared locale** (`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`). Each was fixed at the surface that *owns* it. The bracket trap is operator vocabulary + judgment → **catalog** `AUTHORING` gotchas on the date-range functions (served by `cli operators`) **plus** a sharpened **skill** field-path rule and a corrected `MVK_UNEXPECTED_TOKEN` hint (it can mean *too many* brackets, not only too few). The per-locale gap was **pure discoverability** — the failure diagnostic was already precise (`LocalizedText.checkDeclared` names the missing locale + the fix), but `config read` exposed only `conditionLanguage`, not the declared `header.locales` that *drives* the requirement, so an agent couldn't see a model was multi-locale until it failed → **the tool** now carries `locales` in both `config read` data and the `model describe` model card. The order is the principle: prefer the **tool** self-describing (deterministic, helps even a skill-less agent), fall back to the **catalog** (self-verifying vocabulary), reserve the **skill** for the judgment the other two can't encode. (Re-measured: targeted T13 re-run — [eval/RESULTS.md](../eval/RESULTS.md).)

22. *(new, 2026-06-12 — version awareness)* **Make the build's versions a self-describing fact, and let the kernel be the authority on compatibility — rulekit only classifies.** A cold agent (and a human) now reads what the build *is* and *targets* from `--version` (human) and `manifest.version` (machine-readable): the rulekit release, the kernel built-against/runtime, the catalog floor, the model-format reference. For loading an off-version document model, the **kernel already** accepts an older same-major model and fail-fasts on a newer/different-major one with a clear `"Version mismatch …"` ERROR (probed + locked, KERNEL-FINDINGS *Header `modelVersion` compatibility*). So the tolerance posture isn't ours to invent — rulekit **classifies** the kernel's verdict into stable, machine-actionable codes: tolerant INFO `RK_MODEL_VERSION_SKEW` (accepted but off-reference) vs fail-fast `RK_MODEL_VERSION_INCOMPATIBLE` (the coded form of the kernel's mismatch ERROR). Same shape as the F11 kernel-skew advisory: *inform, don't second-guess the kernel.* And write-back never bumps the version (surgical edits leave the `header` untouched; locked by `ModelVersionPolicyTest`). Demo: [`examples/cli-version.md`](../examples/cli-version.md).

23. *(new, 2026-06-12 — field/group reference ergonomics; decision D7, milestone L2c)* **Refs/pointers — to fields *and* groups — become the preferred currency across both kits; codegen (L2b) is dropped.** Today an element is referenced by string path returning a typed handle (L2a: `m.num("/Order/Discount")`); the path is a string and you re-type it to reference what you just created. The decided design (✅ implemented 2026-06-12 — §5c) makes **value refs** the currency: get one for *any* field **or** group (existing — by path/navigation — or **just-created**, fetched by relative navigation after the edit), use it everywhere — rule scope/error-field/operands (**rulekit**) *and* structure-edit targets (**modelkit**) — with a thin session-bound **editor** as the *one* fluent editing idiom (`order.addField(…)` chains; the session-as-actor/ref-returning form is dropped), plus **relative navigation** off a group (`order.num("Qty")`). The safety invariant: the immutable AST holds **only pure refs**, never the session-carrying editor (functional core / effects at the edge). **L2b codegen is dropped, not parked** — it's structurally incapable of the runtime create→reference flow (codegen lags the live edit), and the editor/value-ref design is its complete replacement *without* a build step (the one thing it alone offered, compile-time path checking, stays out of scope by design). **Why not live nodes:** their only unique wins — node-anchored chaining + rename-tracking — and rename-tracking (a held ref auto-following a rename — distinct from the now-shipped model-level rename engine) needs a mutable graph that fights the immutable-snapshot/AST grain; value refs get ~90% within the paradigm. Lineage (idiomatic, not novel): pure ref ≈ jOOQ `Field`/QueryDSL Q-type/Roslyn `SyntaxNode`; editor ≈ jOOQ `UpdatableRecord`/active-record (lighter than a JPA managed entity). Full sketch + rationale: §5c; decision + dropped-L2b: proposal D7; binding-level taxonomy: §16; naming (`Editor`, not *cursor*/*node*) + rejected alternatives: §18.7.

24. *(new, 2026-06-12 — composition over the editor; ✅ implemented)* **The editor lets rule fragments extend to document-part builders — a consequence of the design, not new machinery.** rulekit already has reusable rule fragments (`(refs, params) → Condition`, finding 11). Once the L2c editor takes a "where to build" handle and returns refs, the same shape works for *structure*: an ordinary method `(GroupEditor, params) → structure + rules` that returns a ref to what it built. They're plain functions (no template engine), they compose (one builder calls another), and they parameterize over names / refs / kinds / other builders; each assembled result still passes the single `validate()` gate. Whether a team keeps such a "document-component library" (`Money`/`Address`/`LineItems`/…) is their call — the only claim here is that **nothing extra is needed** — it falls out of the editor + flowing refs (before, you threaded raw string paths and couldn't return a usable handle). Demonstrated by the `money`/`address` builders in `GroupEditorTest`. Examples: §5c.1.

25. *(new, 2026-06-12 — working across models; ✅ implemented)* **Includes and type-defs make rulekit a multi-model tool — rules author over included/mounted fields exactly like local ones.** A shared sub-model is included into a base and mounted at one or more places (`addInclude` + `IncludeEditor.mount` for extra locations); the base then authors rules over the mounted fields by their **mount-name path** (`/Catalog/Retail/ListPrice`), and a `typedefField` resolves to its underlying kind. The binding threads a **resolver** (`EditSession.read(json, resolver)` / `BoundModel.ofJson(json, resolver)`) that expands the include through the kernel; write-back keeps the include as a `modelAlias` reference (**never inlined**, F6/D9), and an included model's *own* rules travel with it (mounted twice → applied at both mounts) — unless the mount sets `excludeRules`/`excludeComputations` (`MountOptions.excludingRules()`), which drops them at expand while keeping the fields (FEATURE-GAPS §3.9, `edit.IncludeExcludeRulesTest`). Full flow + example: §5d. Test-backed: `edit.MultiModelTest`, `rulekit.dsl.IncludesTypedefsRuleTest`.

26. *(new, 2026-06-12 — runtime evaluation as a use case; ✅ implemented)* **"Does this rule actually fire?" is a first-class capability, not just static validity.** `KernelAdapter.evaluate(documentTree, locale)` runs the model's rules over a document instance and returns the fired rule **codes** (its peer `compute` returns what each field computes); the CLI peers are `eval-doc`/`compute-doc`. This is verify-**by-execution**: run a passing and a violating document and compare `firedCodes()` — the angle that exposes an inverted-but-kernel-valid rule, and the engine behind the eval's execution-based equivalence judge. Example: §5e. Test-backed: `adapter.RuntimeEvalExampleTest`, `adapter.laws.EvalDocSeamLawsTest`.

27. *(new, 2026-06-12 — comments as a side-channel; ✅ implemented)* **`;;` comments turned from a lossiness footnote into a first-class, preserved authoring concern — without touching the AST.** The kernel tolerates `;;` line comments but drops them from its parsed AST, so a pure read→re-render lost them (the old "#3 lossiness"). The fix is a **side-channel** (`rulekit.dsl.Comments`, node-identity map over both `Condition` *and* `Calculation`) rather than 15 new AST node types + ~9 switch rewrites — the carrier-(c) decision, chosen over the parked Phase-5 concrete-syntax re-render because it gets the common case for a fraction of the blast radius. Two capabilities fall out: **author** (`Rule.Builder.comment`/`commentAfter`, `Computation.Builder.comment(...)` on operations + preconditions, the CLI `comment` key / `--comment`) and **preserve-by-default** (`Comments.preserveLeading` in `ModelWriter.replaceCondition` — a comment-less `modify` keeps the leading note). Residual loss shrank to inline/trailing comments + non-canonical formatting, which `diff` now discloses precisely. Two recordable lessons: (a) a side-channel keyed by node identity is the cheap way to attach metadata to an immutable AST you don't want to re-shape; (b) the *preserve* and *author* halves split cleanly into separate increments (mirrors the rule path — authoring then preservation), and computation-modify preservation followed the same split (`ModelWriter.replaceComputation` carries the common-precondition + first-operation leading comments; per-alternative matching across a re-expression stays positional). Showcase: §5f. Test-backed: `rulekit.dsl.RuleCommentTest`/`CommentRenderTest`, `ComputationAuthoringTest`, `adapter.CommentRoundTripTest`/`ConditionCommentLossinessTest`, `cli.AddVerbTest`/`ModifyVerbTest`.

28. *(2026-06-13 — methodology)* **Gates over checklists: anything an LLM can skip *will* eventually be skipped, so a criterion that matters becomes a build-failing gate derived from the live artifact, not a checklist item.** The cross-cutting lesson is recorded here; its full realization is a **CLI-tool** concern (the self-describing + diagnosis conformance harness — single-source `RkCode` registry, the conformance fuzzer + matrix, per-verb demonstrations, the gap-finder) and lives in [`CLI-CONFORMANCE-SPEC`](./CLI-CONFORMANCE-SPEC.md), not this DSL/API study.

29. *(2026-06-14 — negation discoverability; ✅ implemented, FEATURE-GAPS G3)* **The catalog now names each predicate's negation partner, turning the kernel's "no generic negation" model from a trap into a one-hop lookup.** The kernel has no `Not(…)` — the inverse of a predicate is *another* predicate (`FieldFilled`→`FieldNotFilled`, `AllFieldsFilled`→`NotAllFieldsFilled`, `NoFieldFilled`→`AtLeastOneFieldFilled`, `<`→`>=`, `==`→`!=`, `PatternMatched`→`PatternViolated`, `Valid`→`Invalid`). Each `OperatorEntry` carries an `opposite` list (served by `operators <id>`), so an agent sitting on the wrong polarity finds the right operator without re-deriving it. It's a **discoverability pointer, not a strict-complement claim**: three-valued/formal-error semantics mean a field with a formal error is removed from evaluation, so `FieldFilled`/`FieldNotFilled` aren't exhaustive (their existing gotcha says so) — the pairing answers "which operator expresses the inverse", which is exactly the BA's question. Lock: the pairings must be **resolvable + symmetric** (`OperatorCatalogTest.oppositePairingsAreResolvableAndSymmetric`), so a dangling or one-way pointer fails the build.

30. *(2026-06-14 — proactive error-field guidance; ✅ implemented, FEATURE-GAPS G4)* **The error field's legality is computable up front, so the reject→fix round-trip becomes an up-front pick.** A rule's error field must be *referenced* by the condition and lie inside its *iteration scope* (the `MVK_ERROR_FIELD_NOT_REFERENCED` law) — a top reject class an agent otherwise learns only by getting rejected. `ModelContext.errorFieldCandidates(condition)` returns the legal picks — the referenced fields inside the iteration scope — reusing the same `iterationScope` + reference reasoning behind `explain`/`rule deps`; the CLI surfaces it as `rule check --suggest-error-field` (`data.candidates` + `fieldIsCandidate`). Two design notes worth keeping: (a) it has a **set-based overload** fed by the kernel's own `getDirectlyReferencedEntities` (robust where the rulekit converter NPEs on an *inconsistent* candidate), so even a candidate with a *wrong* error field still gets the corrective list; (b) that required a **lenient bind** (`BoundModel.ofJsonLenient` / `ModelLoader.expandOnly` — expand without the consistency gate) for read-only inspection of a not-yet-valid model — a small, reusable capability the write path must never use. Lock: `ModelContextTest.errorFieldCandidates…` (scope filter) + `cli.RuleCheckSuggestVerbTest`.

31. *(2026-06-14 — computation author-time lints; ✅ implemented, FEATURE-GAPS G5)* **Two computation laws the BA doc mandates are now caught before the kernel round-trip, by a text-level lint that needs no parse.** A computation precondition must not use `CustomCondition`/`RepetitionNotUnique` (the kernel rejects with `MVK_ERROR_INVALID_LANGUAGE_FEATURE`), and alternatives are meant to be mutually exclusive (the kernel takes the first match in declaration order, so a duplicate precondition means the later alternative can never win). `ComputationLinter` (the computation peer of `Linter`) works on the `DraftComputation`'s precondition **texts**: the forbidden constructs are matched as operator *calls* by their EN **and** DE keywords (a field reference can't be `CustomCondition(`…), and overlap is **exact-duplicate** detection — precise and cheap. True logical disjointness needs a solver and is deliberately out of scope (best-effort: the duplicate case is the one that always means a bug). The advisories (`RK_COMPUTATION_FORBIDDEN_CONSTRUCT` / `RK_COMPUTATION_OVERLAPPING_PRECONDITIONS`) ride the `computation add` envelope whether the kernel accepts (WARNING alongside APPLIED) or rejects (alongside the kernel diagnostics, with the corrective). Lock: `lint.ComputationLinterTest` + `cli.ComputationVerbTest`.

32. *(2026-06-14 — validation-pattern catalogue; ✅ implemented, FEATURE-GAPS G2)* **The recurring validation idioms become a typed-DSL-backed catalogue, so scaffolding a correct rule is a parameter fill, not a re-derivation.** `PatternCatalog`/`PatternEntry` (the rule-level peer of `OperatorCatalog`) hold five idioms (date-order, required-when, mutually-exclusive, at-least-one-of, sum-of-line-items); each builds its condition through the typed DSL — **correct by construction** (violation polarity + a referenced error field baked in), the two traps an agent otherwise re-risks every time. Three decisions: **(a)** built-ins are *typed* (not string templates) behind a `PatternEntry` **interface** that a future *data-template user extension* plugs into additively — the typed-vs-data tension resolved as "correct-by-construction now, user-extensible later" (§5g); **(b)** the **no-rot gate** kernel-validates every pattern's example in CI (the operator-catalogue precedent), so an idiom can't go stale; **(c)** the verb is **emit-spec + auto-check** by default (one mutation path, `rule add`; the G4 verdict folded in) with an opt-in `--apply`. Showcased in §5g. Lock: `rulekit.catalog.PatternCatalogTest` + `PatternCatalogKernelTest` + `cli.PatternsVerbTest`.

33. *(2026-06-14 — typed date/time literals; ✅ implemented, DEV-API-GAPS DG4)* **The last fragile string in the typed DSL — a date/time constant — gets a `java.time` overload, so a literal is valid by construction rather than kernel-checked.** `Dsl.dateValue(String "01.01.2022")` / `timeValue(String)` forced a format-coupled string whose typo or wrong format surfaced only at the kernel — the exact failure the typed builder erases everywhere else. `Dsl.dateValue(LocalDate)` / `timeValue(LocalTime)` render to the kernel's German `dd.MM.yyyy` / `HH:mm:ss` form (a `LocalDate` can't be a malformed date), delegating to the same `DateLiteral` / `TimeLiteral` node; the `String` overload stays for the CLI/spec path where values legitimately arrive as text. First item off the developer-perspective review ([`DEV-API-GAPS-PROPOSAL.md`](DEV-API-GAPS-PROPOSAL.md)). Lock: `DateConditionTest.javaTimeLiteralsRenderLikeTheirStringForms`.

*(4–6 shape Phase-2 API design; 7–8 are proposal §12/§14 refinements; 9–13 are scheduled — 9/10/12-describe-find/13-export Phase 1, the rest Phase 2; 14 is the meta-instrument now in CLAUDE.md; 15–16 are increment/authoring practice; 17 is F4a; 18 prioritizes field-vs-field comparison; 19 is the authored-rule emit-map span path; 20 routes the RT-drain traps through the catalog gotchas and marks them N6 task candidates; 21 closes the T13 cold-agent gaps tool→catalog→skill; 22 adds the version surface + kernel-authoritative model-version classification; 23 makes refs the field/group currency across both kits — L2c — and drops L2b codegen; 24 notes document-part builders fall out of the editor — no new machinery; 25 covers working across models — includes/type-defs with rules over mounted fields; 26 makes runtime evaluation (verify-by-execution) a first-class use case; 27 makes `;;` comments an authored, preserved concern via a node-identity side-channel; 28 makes the self-explain/diagnose promise a CI-enforced contract — gates over checklists — via the conformance fuzzer + the single-source `RkCode` registry; 29 adds the operator catalog's `opposite` negation pairings — one-hop inverse discovery serving the "no generic negation" model; 30 makes the error field's legality computable up front via `errorFieldCandidates` / `rule check --suggest-error-field`; 31 adds computation author-time lints — forbidden constructs + overlapping preconditions caught before the kernel round-trip; 32 makes the recurring validation idioms a typed-DSL-backed, kernel-verified catalogue (`patterns`); 33 gives date/time literals a `java.time` overload — the first developer-perspective (DEV-API-GAPS) item.)*
