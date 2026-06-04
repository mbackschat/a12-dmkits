# API study & review surface — what rulekit feels like

> **Status:** living **design study and review surface**, expanded 2026-06-04 (first version 2026-06-03) — code studies for **every stated proposal goal** ([`PROJECT-PROPOSAL.md`](./PROJECT-PROPOSAL.md) v2 §2/§3), so the end-state experience can be reviewed *before* each part is built. Names and signatures of unbuilt parts are **not final**. Status markers throughout: ✅ = works today, verbatim or near-verbatim; 🔮 = Phase-1/2 scope, illustrative.
>
> **This doc is deliberately kept alive:** whenever a feature lands or an interesting usage emerges, the corresponding study here is updated to showcase it (see the doc-evolution note in `CLAUDE.md`). It doubles as the seed material for the LLM skill (N1) and the self-sufficiency eval (N6).
>
> Domain: the committed **subscription** fixture (our own original) — `Subscription` root with `Tier` (enum `BASIC/PRO/ENTERPRISE`, category `Pricing` FREE/PAID), `AutoRenew` (boolean), `TermsAccepted` (confirm), repeatable `Addons{Name, MonthlyFee(scale 2)}`, `Billing{InvoiceEmail, BaseFee(scale 2), EffectiveFee(scale 2)}`; declared locales `en_US` + `de_DE` (header locales are arbitrary — en/de are not special); the **order** fixture adds `ShippingAddress`/`BillingAddress` groups and boolean/confirm fields. Extended illustratively where a construct needs it.

---

## 1. Setup — bind once, everything flows from the model (F0) ✅

```java
import static io.github.mbackschat.a12.rulekit.dsl.Dsl.*;
import io.github.mbackschat.a12.rulekit.dsl.*;

BoundModel m = BoundModel.of(ModelIndex.of(ModelLoader.loadSelfContained(reader)));   // ✅ today
// BoundModel m = BoundModel.load(path);                      // 🔮 sugar
// BoundModel m = BoundModel.load(path, resolver);            // 🔮 model with includes: rulekit expands (F0)

NumberRef  fee   = m.num("/Subscription/Addons/MonthlyFee");  // knows: number, scale 2
EnumRef    tier  = m.enumOf("/Subscription/Tier");            // knows: BASIC, PRO, ENTERPRISE
BoolRef    renew = m.bool("/Subscription/AutoRenew");         // boolean ≠ confirm (§5b)
ConfirmRef terms = m.confirm("/Subscription/TermsAccepted");
GroupRef   addons = m.group("/Subscription/Addons");          // knows: repeatable
```

Everything the safety story needs is in hand at bind time: kinds, scale, enum domain, repetition. `m.num(stringPath)` fails *here* with the kind named — not as an `MVK_*` three steps later.

## 2. The type system steers (F0a/§5b) ✅ — a study in what you *cannot* write

The largest `MVK_*` class is type rejection; the API removes it method-by-method. All of these are **compile errors or immediate failures today**:

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

### 3.2 Fragments with domain parameters 🔮 (needs the named operators from the backlog)

```java
/** Tolerance check — only the fixed 1/2/5/10 variants exist (the catalog teaches this; the enum enforces it). */
static Condition withinTolerance(NumberRef actual, NumberRef expected, Tolerance t) {
    return allFieldsFilled(actual.field(), expected.field())
            .and(actual.value().differsWithTolerance(t, expected.value()));   // 🔮 DiffersWithToleranceRange{N}
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

### 3.4 Parameterized *rules* — templates over the model ✅ (message interpolation still 🔮)

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
                    .message(en(f.name() + " must not be negative."))   // 🔮 {0}→$Field.value$ interpolation
                    .build())
            .toList();
}
```

This is the bulk-authoring payoff: a *rule template* is a function `BoundModel → List<Rule>`; an LLM (or CI) applies it to any model.

### 3.4a Authoring-time reuse over a multi-field structure — `sameAddress` ✅ (field-vs-field equality landed; finding 18)

When you author **many rules in Java that compare the same multi-field structure** (e.g. a seven-field address), a fragment removes the boilerplate — ordinary F5 combinator reuse, two levels deep. (Note: this is a convenience in the *authoring code*, not a transformation of the stored rule — the kernel DSL is flat, so the rendered `errorCondition` is the same string either way; what improves is the Java you write, not the persisted text.)

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

The fragment is just a function — the F5 payoff for repetitive authoring. **Field-vs-field equality** (`a.eq(b)`) landed for string + number (number `==`/`!=` pre-checks equal scale; ordering exempt — finding 18); enum-vs-enum is deferred (text-bearing rules + fixture). (Later: a `sameAddress(GroupRef, GroupRef)` that zips like-named fields via the model index — Level-2 navigation.)

### 3.5 n-ary composition ✅ (finding 10 — implemented 2026-06-04)

```java
Condition c = allOf(r1, r2, r3);            // And-fold: "r1 And r2 And r3" — no redundant parentheses
Condition d = allOf(r1, anyOf(r2, r3));     // "r1 And (r2 Or r3)" — parens per the kernel's mixing rule
```

## 4. Authoring a rule end-to-end (F1, F2, F2a, F3, F4, F6)

### 4.1 The rule object — slots from typed handles (F2a) ✅ — implemented 2026-06-04 (`RuleAuthoringTest`; message *interpolation* still 🔮)

```java
Rule rule = m.rule(addons)                        // model-aware entry: rule lives in a group by construction
        .name("AddonFeeRequiredRule")             // the element name (the study originally forgot it)
        .errorField(fee)                          // errorEntityRelPath computed — "../MonthlyFee"
        .when(SubscriptionIdioms.feeRequired(tier, fee))
        .code("ADDON_FEE_REQUIRED_FOR_PRO")
        .severity(Severity.ERROR)
        .message(en("A PRO subscription requires a monthly fee per add-on."),
                 de("Ein PRO-Abo erfordert eine Monatsgebühr je Zusatzoption."))   // 🔮 {0}→$Name.value$ interpolation: F2a follow-up
        .build();   // pre-checks at build(): error field under the group; error-field-referenced
                    // (directly or via enclosing group ref — kernel-verified); the LOCALE LAW:
                    // a message for EVERY declared locale (MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING),
                    // none for undeclared ones (stricter than the kernel, which ignores them silently);
                    // header locales are arbitrary — Dsl.text("fr_FR", …) for anything beyond en/de sugar
// rule.draft() bridges to the F4 validator; rule.render(DE) renders the same AST in German.
// ConditionReferences.of(condition) — the build-check's collector — is the seed of F9's referenced-fields projection.
```

### 4.2 Render → validate → emit ✅ / 🔮

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

### 4.3 A computation, same materials (F2) ✅ — implemented 2026-06-04 (`ComputationAuthoringTest`; aggregates/arithmetic in operations are corpus items 6–7)

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

### 5.1 In-session transform (#1) ✅ — working code, verbatim in `TransformConditionTest`

```java
Condition widened = proNeedsFee.transform(node -> switch (node) {
    case ComparisonCondition c when c.operatorId().equals("Equal")
            && c.right() instanceof EnumValueLiteral(String v) && v.equals("PRO")
            -> tier.is("PRO").or(tier.is("ENTERPRISE"));
    default -> node;
});
// bottom-up; untouched subtrees keep reference identity; D14 ids make the guards readable
```

### 5.2 Ingest a foreign, BA-authored rule — the full loop, load to save (F10) 🔮 Phase 2

```java
// LOAD — deserialize → expand → checkConsistency → bind (F0); the binding is an immutable snapshot
BoundModel m = BoundModel.load(Path.of("subscription.dm.json"));

ExistingRule legacy = m.existingRule("/Subscription/Addons/LegacyFeeRule");   // fetch ≠ m.rule(group) (build)
// its errorCondition (hand-written, with comments and short names):
//   ;; fee must be set for paid tiers
//   FieldFilled(MonthlyFee) AND [/Subscription/Tier] != "BASIC"

// INGEST — kernel parses → converter visitor → rulekit AST (fail-fast on gaps, never silent loss)
Condition ast = switch (KernelAdapter.ingest(legacy, m)) {
    case Ingest.Converted(Condition c)         -> c;
    case Ingest.Gap(String construct, var loc) -> throw new UnsupportedOperationException(construct);
};
// FieldRefs carry authored PathStyle hints (SHORT, ABSOLUTE); comments are flagged as lost-on-re-render

// WORK — extend the rule so the fee must also be positive. A plain extension COMPOSES:
// the immutable original becomes a subtree, the addition is appended — intent on the surface.
NumberRef fee = m.num("/Subscription/Addons/MonthlyFee");
Condition changed = ast.and(fee.gt(0));            // … And [MonthlyFee] > 0

// transform (#1, ✅ today) is the SURGICAL tool — reach for it when the edit happens INSIDE the
// tree (node replacement, bulk rewrites). Sensible change: [Tier] != "BASIC" hardcodes the tier
// list — re-express it via the Pricing category so future paid tiers are covered automatically.
// Composing can't do this: the old comparison node must be REPLACED where it sits.
Condition modernized = changed.transform(node -> switch (node) {
    case ComparisonCondition(FieldValue(var f), var op, EnumValueLiteral v)
            when f.path().endsWith("/Tier")
        -> tier.category("Pricing").is("PAID");    // [Tier] != "BASIC" → [Tier -> Pricing] == "PAID"
    default -> node;                               // untouched subtrees are reused by reference
});

// REVIEW → VALIDATE → PERSIST — snapshot semantics (F0): a write yields a NEW model + binding
RenderedDiff diff = m.diff(legacy, changed);                       // the human gate; discloses lost comments
ValidationResult vr = m.validate(legacy.withCondition(changed));   // kernel confirms before anything is written
BoundModel m2 = m.replaceCondition(legacy, changed);               // new binding over the updated model
m2.writeTo(Path.of("subscription.dm.json"));                       // SAVE — DM-JSON, the portable artifact
// m still sees the old snapshot; m2 the new one — no in-place mutation, no staleness (F0)
```

```text
--- /Subscription/Addons/LegacyFeeRule (authored text; 1 comment will be dropped on write)
+++ modified via rulekit (composed: appended at the end)
- ;; fee must be set for paid tiers
- FieldFilled(MonthlyFee) AND [/Subscription/Tier] != "BASIC"
+ FieldFilled(MonthlyFee) And [/Subscription/Tier] != "BASIC" And [/Subscription/Addons/MonthlyFee] > 0
(with the transform's category replacement on top, `modernized` renders:
 FieldFilled(MonthlyFee) And [/Subscription/Tier -> Pricing] == "PAID" And [/Subscription/Addons/MonthlyFee] > 0)
```

### 5.3 Explain (F9) 🔮 Phase 2 — same projection for built and ingested rules

```jsonc
// m.explain(legacy).toJson() — abbreviated; full shape in the 2026-06-03 study version (git history)
{
  "gloss": "Fires (data invalid) when an add-on's MonthlyFee is filled while the tier is not BASIC.",
  "suspicious": ["gloss reads inverted for a requiredness rule — did the author mean FieldNotFilled? (true ⇒ invalid)"],
  "iterates": { "value": true, "why": "error field sits under repeatable group Addons — the rule runs per add-on" },
  "referencedFields": [ { "path": ".../MonthlyFee", "kind": "number", "scale": 2, "roles": ["error-field","operand"], "authoredPathStyle": "SHORT" } ],
  "operators": [ { "id": "FieldFilled", "meaning": "...", "gotchas": ["a field with a formal error is removed from evaluation"] } ]
}
```

### 5.4 Re-express, diff, validate, write back (#2) 🔮 Phase 2

```java
Condition fixed = SubscriptionIdioms.feeRequired(tier, fee);     // rebuilt from intent — often a library fragment!
RenderedDiff diff = m.diff(legacy, fixed);                       // discloses lossiness: "1 comment will be dropped"
ValidationResult vr = m.validate(legacy.withCondition(fixed));
BoundModel m2 = m.replaceCondition(legacy, fixed);   // new binding (F0); persist via m2.writeTo(...) as in §5.2
```

Note how 3.3's fragment library closes the loop: **re-expression frequently means recognizing a known idiom** — the LLM maps a foreign rule onto `feeRequired(...)` and the diff shows the cleanup.

## 6. Semantics as data (F7, N6) ✅ — the catalog is queryable today

```java
OperatorCatalog catalog = OperatorCatalog.load();                                  // ✅
OperatorEntry e = catalog.byId("GroupFilled").orElseThrow();
e.meaning();      // "True iff the referenced group is filled. For a repeatable group this means the row EXISTS — ..."
e.gotchas();      // ["'filled' means 'row exists', not 'row has data' — ...", "an enclosing GroupFilled(Group) counts as referencing the rule's error field ..."]
e.keywords().de(); // "KontextAngegeben"
e.invalidExample(); // dsl + the exact MVK_* + the fix — every example kernel-validated in CI (N7)
```

An LLM reads exactly this. The **self-sufficiency eval** (N6) is the runnable acceptance test: given only the catalog + skill + javadoc, author and correctly explain a rule per covered operator — where it can't, the material (not the LLM) is deemed incomplete.

### 6b. The model-context surface (F9a, `context` package) 🔮 — "explain the rule on birthdate"

The agent's entry point is usually a *name*, not a rule. Three projections compose the answer:

```java
ModelCard card = Context.describe(m);             // 🔮 Phase 1 — the agent's map, PROGRESSIVE: default is the
                                                  //    skeleton (group tree + counts/kind histograms — low-1000s
                                                  //    of tokens even on a 10k-field model); drill down per group:
ModelCard addons = Context.describe(m, m.group("/Subscription/Addons"));
List<FieldMatch> hits = Context.find(m, "birthdate");
// → [/Customer/BirthDate (date, format dd.MM.yyyy, in group /Customer)]   — dumb string ranking, deliberately;
//    fuzzy/NL interpretation is the LLM's job

List<Usage> usages = Context.whereUsed(m, hits.getFirst().field());   // 🔮 Phase 2 exact (adapter)
// → [ {rule: "/Customer/AgeCheckRule",   slot: ERROR_CONDITION, role: "operand"},
//     {rule: "/Customer/BirthDateRule",  slot: ERROR_FIELD},
//     {computation: "/Customer/Age",     slot: OPERATION} ]                — slot-labeled (§5 vocabulary)

usages.forEach(u -> m.explain(u.owner()));        // 🔮 F9 — and the loop closes
```

The N8 seam: `context` lives in the public-tier core; `whereUsed` takes a pluggable reference-resolver — core ships a labeled heuristic (text matching), the Phase-2 adapter contributes the exact one (`getDirectlyReferencedEntities`, one kernel call per rule, no converter needed).

**And for agents, the primary form is files** (owner direction — agents are trained to dump-then-grep):

```java
Context.dump(m, Artifact.FIELDS, System.out);   // 🔮 stream-first primitive — stdout pipes like git
Context.dump(m, Path.of("ctx/"));               // 🔮 convenience: all four artifacts to files
```

```bash
# one-shot question — pipe (mind JVM startup: seconds, not git-milliseconds):
jbang rulekit-ctx.java model.json fields | grep -i birthdate
# repeated exploration — dump once, then grep (the recommended loop):
grep -i birthdate ctx/fields.jsonl
# {"path":"/Customer/BirthDate","kind":"date","format":"dd.MM.yyyy","group":"/Customer","repeatsUnder":null}
grep BirthDate ctx/rules.jsonl
# {"rule":"/Customer/AgeCheckRule","slot":"errorCondition","condition":"DifferenceInYears(BirthDate, Today) < 18","referencedFields":null,"resolution":"heuristic"}
```

The 10k-field problem dissolves: the files hold everything, only grep hits enter context; `model-card.md` (skeleton) is the single read-whole artifact. JSONL = one line per fact = greppable *and* `jq`/Python-parseable; each artifact stamps the snapshot's model id/version.

### 6c. Navigating the model (finding 9, Level 1) ✅ — jOOQ-`Meta`-style, no query language (implemented 2026-06-04; `NavigationTest`)

The programmatic counterpart to 6b's files — and the machinery beneath them (`fields.jsonl` *is* `m.fields()` serialized). Streams of rich **info handles**; matching is plain Java; narrowing bridges back to strict authoring handles:

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
        .map(f -> m.rule(f.group()).errorField(f.asNum())
                .when(f.asNum().filled().and(f.asNum().lt(0)))
                .code("NON_NEGATIVE_" + f.name().toUpperCase()).severity(ERROR).build())
        .toList();
```

Safe by F0's snapshot semantics (immutable binding → internal memoization, clients never index); **Level 2** (glob paths, ancestry axes, annotation queries) stays parked behind named triggers — see finding 9. The *static* navigation counterpart is L2b codegen refs (Phase 3, jOOQ's generated-schema analogue).

## 7. The LLM session (N1, §14) 🔮 Phase 2 — the loop the skill teaches

> **User:** "Customers can't save BASIC subscriptions — something fires on add-ons without a fee. Find and fix it so only paid tiers require a fee."

| Step | Agent does | rulekit gives back |
|---|---|---|
| 1 | `m.rules()` → `m.explain(each)` | the `suspicious` hint flags `LegacyFeeRule` (inverted predicate) |
| 2 | reads the cited catalog entries | `FieldFilled` vs `FieldNotFilled` + the error-scenario inversion — no BA docs (N6) |
| 3 | re-expresses: `feeRequired(tier, fee)` | a typed AST — illegal variants didn't compile |
| 4 | validates | ok / `MVK_*` mapped to a mechanical fix |
| 5 | diffs | the reviewable diff for the PR — the human gate |
| 6 | writes back | exactly-scoped DM-JSON change |

Authoring direction: *"ENTERPRISE needs ≥ 2 add-ons"* → agent queries the index (`addons.repeating()`), finds `NumberOfFilledFields` in the catalog (operands/returns/example), composes, validates. Wrong guesses die at compile (step 3) or parse (step 4) with the fix named — the feedback loop agents thrive on. **Execution vehicle:** scratch JUnit/JBang patterns shipped with the skill (finding 6; a CLI is a separate, later decision).

## 8. Polyglot peek (N2, §13) 🔮 Phase 4 — the same catalog, a Zod-flavored skin

```typescript
import { bind } from "@a12-rulekit/core";                       // reads the SAME catalog JSON + DM-JSON

const m = bind(dmJson);
const fee = m.num("/Subscription/Addons/MonthlyFee");
const proNeedsFee = m.enum("/Subscription/Tier").is("PRO").and(fee.notFilled());
proNeedsFee.render();                                            // identical canonical DSL text
// no TS ingest/validate: the TS kernel stack has no AST and no buildable runtime (survey §15) — by design
```

The contract is data (catalog + DM-JSON + render rules), so this is a parallel reading, not a port — and naming parity means an agent transfers Java↔TS knowledge 1:1.

## 9. Implementation-status board (canonical)

**This table is the single source of truth for what rulekit has built.** Other docs defer here: the [README](../README.md) gives the outside capability snapshot, [`PROJECT-PROPOSAL.md`](./PROJECT-PROPOSAL.md) states intent (not per-increment status), and [`WORKSPACE-CORPUS-ANALYSIS.md`](./WORKSPACE-CORPUS-ANALYSIS.md) tracks the orthogonal operator-coverage/backlog axis. When a feature lands, update this row (and flip its study snippet 🔮→✅) — not four scattered status lists.

| Study element | Status |
|---|---|
| Handles (`num/str/enumOf/bool/confirm/group/date`), typed conditions, EN/DE render, validator, D14 ids + `PathStyle`, `Dsl.*`, literal overloads, `transform` | ✅ (Phase 1; operator coverage = corpus items 1–6, 8, 9a + iteration 7a–7f — patterns, value lists, count + list aggregates, `*` lists, `repetitionNotUnique(...).from(...)`, `flatten(...).having(filter)`, `currentRepetition`, `num(f).forIndex(...)`, parallel iteration, `customCondition`, dates, field-vs-field) |
| §3.1 fragments, §3.3 library pattern, §6 catalog queries, §2 type-steering | ✅ pattern works today on covered operators |
| §3.2 domain fragments (`withinTolerance`, `chronological`, `uniqueBy`) | `uniqueBy` ✅ (7b); the others 🔮 (tolerance ops, `CurrentRepetition`) |
| §3.4a `sameAddress` (field-vs-field equality) | ✅ string + number (finding 18); enum-vs-enum 🔮 |
| §6c navigation (Level 1: `fields()`/`groups()`/`fieldAt` + strict narrowing), §3.5 `allOf`/`anyOf` | ✅ implemented 2026-06-04 (findings 9–10) |
| §3.4 rule templates | ✅ full form works (navigation + `Rule` object) |
| `Rule` + `Computation` authoring objects (computed relPaths, slot-rule pre-checks, validator bridges, `Calculation.kind()`) | ✅ implemented 2026-06-04 |
| DM-JSON write path: `ofJson`/`add(Rule)`/`toJson`/`writeTo` (snapshot, re-validated, includes-preserving) | ✅ F6 first slice (2026-06-04); `add(Computation)`/`replaceCondition`/`removeRule` 🔮 |
| Structured `Diagnostic` (`severity·source·code·summary·where·fix·explain`) + catalog-join + JSON | ✅ F4a first increment (2026-06-04) — KERNEL, LINT, and PRECHECK origins all land |
| PRECHECK diagnostics — pre-checks carry a structured `Diagnostic` (`PrecheckException`) | ✅ 2026-06-04 (Rule.Builder; still an `IllegalStateException` so fail-fast is unchanged; Computation.Builder + handle refs follow). **F11 split (2026-06-04):** completeness/structural pre-checks stay hard; the kernel-semantic `MVK_ERROR_FIELD_NOT_REFERENCED` (`covers()` heuristic) is *deferred* to the live kernel (`Rule.deferredChecks()`, advisory WARNING) — never a hard `build()` throw |
| Unified diagnose — one call returns KERNEL+LINT+INFO (+deferred PRECHECK, +ENVIRONMENT) for a rule (`lint.RuleDiagnostics`) | ✅ 2026-06-04 (`diagnose(model, rule) → ValidationResult`; KERNEL span-pointed; deferred prechecks deduped against the kernel verdict; F11 kernel-version advisory once per instance) |
| Span-pointing — pin a KERNEL diagnostic's `where.operator` + `where.span` via AST↔problem matching (`lint.SpanResolver`) | ✅ 2026-06-04 (used∩candidate operators → operator-exact join; `where.span` from the renderer emit-map `DslRenderer.renderWithSpans` for the single-node case) |
| "Did you mean" suggestions — nearest path / enum value on fail-fast handle errors (`model.Suggest`) | ✅ 2026-06-04 (Levenshtein, typo-plausible threshold) |
| Lint layer — valid-but-suspicious WARNINGs over the AST (`lint.Linter`, `RK_*` codes) | ✅ pure-AST (contradictory/non-complementary presence, duplicate operand) + rule-context (`RK_NEG_CONDITION_IN_ITERATION`, `RK_INVERTED_REQUIREDNESS`) (2026-06-04) |
| Gotcha/INFO channel — catalog `gotchas` keyed by operators a rule uses (`lint.Advisor`, `RK_GOTCHA`) | ✅ 2026-06-04 (descends nested `Calculation`s via `dsl.OperatorUsage`); gotcha-relevance curation ✅ (`GotchaKind` SURPRISE/AUTHORING/RENDERING, suppresses trivia) |
| Message *interpolation*, fork-9 path policy, `advice()` | 🔮 Phase-1 remainder (F2a; F9) |
| F9a model-context: `describe()` model card + `find()` name resolution (`context.ModelContext`) | ✅ Phase-1 half (2026-06-04); `whereUsed`/`explain`/`dump` 🔮 |
| fork #9 / F3a path policy — relative-to-group paths (`../` to siblings) + keyword-segment quoting | ✅ 2026-06-04 (kernel-verified): relative rendering + single-quoting keyword-named segments (catalog keywords; full lexer set rides N3) |
| N3 keyword-parity anchor — catalog keywords ↔ kernel lexer terminals (`catalog.KeywordParityTest`) | ✅ 2026-06-04 (forward: every catalog/stub keyword is a real terminal, or a language-independent symbol); reverse + construct-name/error-type parity 🔮 |
| F11 kernel-version tolerance — bias to acceptance, never a self-inflicted false positive | ✅ 2026-06-04: pre-check split (semantic `MVK_ERROR_FIELD_NOT_REFERENCED` deferred to the kernel); runtime skew advisory (`validate.KernelVersion` via `Package.getImplementationVersion()` vs the catalog's `verifiedAgainst`, ENVIRONMENT INFO, once per instance). Ingest opaque-node passthrough 🔮 Phase 2; CI version matrix 🔮 (single-version until a newer kernel publishes) |
| Kernel-adapter bootstrap — opt-in `adapter` module, `KernelAdapter` over the a12internal read chain (`getMvkServiceForModel` → `ElementContainer.getAllRules`), boundary-converting | ✅ 2026-06-04 (Phase 2 start): `referencedFields` (exact F9a whereUsed), `canonicalConditionText` (kernel parse+format round-trip), `hasValidCondition` (per-rule fast lane); canary = N8 pin+canary |
| ingest / explain / diff / LLM loop | 🔮 Phase 2 (adapter): F10 converter visitor + IProblem→Diagnostic next |
| TS twin | 🔮 Phase 4 |

## 10. Design findings (the review surface's output)

1. ✅ ~~`Dsl.*` facade~~ — implemented (handles-only factories; string-path convenience stays on `BoundModel`).
2. ✅ ~~Scale-aware literals~~ — implemented; premise corrected: the kernel's scale check is **field-vs-field only**, literals are exempt (verified) — overloads are rendering-fidelity; D13's pre-check waits for field-operand comparisons.
3. ✅ ~~`transform` rewriter~~ — implemented (bottom-up, identity-preserving; calculation twin triggers with the first nesting calculation node).
4. **Rule identity matters for the edit loop** — `ExistingRule` (full name + slots + raw text) should be designed *with* the `Rule` authoring object (F2a), not after.
5. **`Ingest` as a sealed result** (`Converted`/`Gap`) — converter gaps become data; the corpus round-trip oracle is then a loop over `Ingest` results.
6. **LLM execution vehicle — compile-first, not REPL** (2026-06-04). The agent runs a snippet as a **single-file Java program** (`java --source 21 [--enable-preview] Script.java`, JEP 330/445 — bare `void main()`, no boilerplate) or, when dependencies matter, **JBang** with `//DEPS com.mgmtp.a12:…:30.8.1` resolving rulekit + the kernel from mavenLocal (zero classpath assembly — the actual pain point, not the language). *Rationale:* rulekit's value is making illegal states **unrepresentable at compile time**, so a failing `javac` hands the agent the type-steering fix *before* execution (the feedback loop of finding §2) — strictly more than a runtime verdict. `jshell` stays the **interactive-exploration** mode, not the scripted-run path (looser diagnostics, manual `--class-path`). *Use/potential:* the skill teaches "write `Rule.java`, run it, read the compiler error or the kernel `Problems`"; JBang `//DEPS` makes a snippet self-contained and shareable; a thin `rulekit-cli` (stdin → run, classpath baked in) remains a separate, later decision that this path doesn't block.
7. **`explain` should flag suspicions, not just facts** — cheap heuristics (inverted predicate, unguarded negative condition on a repeatable group) over the same projection. *(2026-06-04: promoted to its own requirement **F4a — rich diagnostics**; this finding is its lint half. See finding 17. First lint increment ✅ 2026-06-04 — `lint.Linter`: contradictory/non-complementary presence + duplicate operand, all pure-AST; the model-context heuristics named here follow.)*
8. **Diff must disclose lossiness up front** — "n comments will be dropped" belongs in `RenderedDiff`'s contract.
9. ✅ *(implemented 2026-06-04, same-day design)* **The model index gets a Level-1 navigation surface** — the unifying design for rule templates (§3.4), F9a's `find`/`describe`/`dump` (which iterate everything anyway), and the fork-9 renderer internals (ancestry, short-name uniqueness — built regardless). Shape: `m.fields()`/`m.groups()`/`m.fields(group)` return **streams of rich info handles**; `m.fieldAt(path)` is the non-throwing peek; **matching is plain `Stream.filter`** — deliberately *no* query language/matcher API (the jOOQ `Meta` restraint; XPath-style DSLs rejected); **info handles narrow to typed authoring handles** via `asNum()`/`asStr()`/… (lenient exploring, strict authoring — jOOQ's `Field<?>` coercion pattern). Safe because the binding is an immutable snapshot (F0) — everything memoizes internally. Polyglot contract is "iterate model facts", not `java.util.Stream`. **Level 2 is parked with named triggers:** glob path patterns (a template inexpressible via `filter`), ancestry axes (the iteration cluster's authoring needs), annotation queries (real-model demand) — none gets built without its trigger. The static counterpart remains L2b codegen refs (Phase 3 — jOOQ's generated schema classes).
10. ✅ *(implemented 2026-06-04)* ~~n-ary `allOf`/`anyOf` in `Dsl`~~ — varargs And/Or folds; parenthesization falls out of the existing renderer mixing rule.
11. *(new)* **Fragment libraries are the re-expression vocabulary** — §5.4 shows modify-by-re-express often means *recognizing a named idiom*; the skill should encourage teams to keep a `…Idioms` class and teach the LLM to prefer its fragments over raw composition. (Also the natural home for org-specific conventions.)
12. *(new, 2026-06-04)* **The model-context surface is F9's other half** (owner idea, recorded as **F9a** in the proposal): `describe()`/`find()`/`whereUsed()` in an own `context` package — the agent's entry point is a *name*, and "explain the rule on birthdate" needs name resolution + the field→rules inverse index (slot-labeled) before F9 can fire. Exact where-used rides the Phase-2 adapter (`getDirectlyReferencedEntities`, no converter needed); `describe`/`find` are Phase-1-sized and share finding 9's index queries. **Two constraints recorded same day (owner):** *(a) snapshot semantics* — a `BoundModel` is an immutable snapshot (no events, no staleness; writes produce a new binding; derived lookups memoized internally so clients never build their own indexes); *(b) model scale* — 1k fields / 6-level nesting is a *normal* DM, 10k exists → `describe()` is progressive disclosure (skeleton + drill-down), never a full catalogue.

13. *(new, 2026-06-04)* **Files/stdout are the agent API** (owner direction): the agent-facing form of the context surface is greppable JSONL artifacts (`model-card.md` + `fields.jsonl`/`groups.jsonl`/`rules.jsonl`) — grep is the query language agents are trained on, dissolving both the 10k-field context problem and most of the bespoke-query-API surface (the Java projections stay as the programmatic layer that `dump` serializes). **Emitters are stream-first** (git-style: per-artifact stdout default, `--output` optional) so agents can pipe — `… fields | grep -i birthdate` — while the JVM-startup cost (seconds per invocation, unlike git) keeps *dump-once-then-grep* (`> ctx/fields.jsonl`) the recommended loop for repeated exploration. The skill's first taught step becomes "dump, then grep". Heuristic-vs-exact reference resolution is labeled *in the artifact* (`"resolution":"heuristic"`), keeping agents honest pre-adapter.

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

18. *(new, 2026-06-04 — ✅ string + number landed same day)* **Field-vs-field comparison.** Comparing two fields (`[A] == [B]`, `[A] <= [B]`) was a basic capability rulekit lacked (comparisons took literals only) — it makes whole classes of ordinary rules inexpressible: confirmation match (`[Email] == [EmailConfirm]`), range sanity (`[MinQty] <= [MaxQty]`), reconciliation (`[Net] + [Tax] == [Gross]`), and the multi-field-structure reuse of §3.4a. The recordable consequence is **prioritization**: field-vs-field (currently the vague "D13 later") blocks *two* independent high-value features — those cross-field rules **and** `$` correlation (§5.2/7d). The cheap unlock is real: **string/enum field-vs-field equality has no scale**, so it's buildable now exactly like date ordering was (slice 9a); only **number** field-vs-field needs the D13 decimal-scale pre-check. Done 2026-06-04: `StringRef.eq/ne(StringRef)` + `NumberRef.{lt,le,gt,ge,eq,ne}(NumberRef)`, the number `==`/`!=` pre-checking equal scale. Enum-vs-enum still deferred (text-bearing rules + fixture). This unblocks `sameAddress` (§3.4a); `$` correlation remains its own deferred feature.

19. *(new, 2026-06-04 — owner challenge "is creating a rule not already a form of ingestion?")* **Authored-rule span-pointing is a Phase-1 increment, not a Phase-2 one — via a renderer emit-map.** The earlier framing ("character spans need ingest") conflated two things: *having* node positions (the renderer already produces the text, so it can record each node's `[start,end)` — no parse needed) versus *the kernel telling us which node a problem is about* (it doesn't — `getSource()` is rule-granular, the same in both phases). So for rulekit-**authored** rules, character-precise pointing is achievable now; only *foreign*-rule spans truly need ingest (no AST exists until parsed; the kernel parser may also carry token positions). **The emit-map increment** — *first slice ✅ 2026-06-04:* (a) `DslRenderer.renderWithSpans` returns the text + a `node → Span` map (reusing `render` for leaf conditions, special-casing only `LogicCondition`, so the text stays byte-identical); (b) `SpanResolver` attaches `where.span` when exactly one rendered node carries the pinned operator-id; (c) `Diagnostic.Where.span` (a `validate.Span`) is JSON-serialized as `[start,end]`. Still to do: descend into `Having`-filter sub-conditions and calculation operands; resolve the node *instance* even when the operator repeats. KERNEL-FINDINGS §"getSource() granularity" carries the scope correction. **Do-when:** gate the second slice on a *consumer* that reads `where.span` (an editor/LSP integration, or the N1/N6 skill/eval) — until then it's speculative. When triggered, order is (1) LINT/gotcha self-spanning (cheap, uncapped — those layers already hold the flagged node), (2) emit-map descent into `Having`/calc operands (pull-based), (3) foreign-rule spans with F10/ingest; note the KERNEL path is capped at the single-node case by the kernel's rule-granular `getSource()`, so don't invest there.

*(4–6 shape Phase-2 API design; 7–8 are proposal §12/§14 refinements; 9–13 are scheduled — 9/10/12-describe-find/13-dump Phase 1, the rest Phase 2; 14 is the meta-instrument now in CLAUDE.md; 15–16 are increment/authoring practice; 17 is F4a; 18 prioritizes field-vs-field comparison; 19 is the authored-rule emit-map span path.)*
