# API study — what rulekit feels like in the end

> **Status:** illustrative **design study**, 2026-06-03 — a peek at the end-state developer & LLM experience the v2 proposal enables ([`PROJECT-PROPOSAL.md`](./PROJECT-PROPOSAL.md) §10/§12/§14). Names and exact signatures are **not final**; the AST/renderer/validator/catalog parts exist today, the `Rule`/`Computation` authoring objects, transform API, adapter (`ingest`), `explain`, and `diff` are Phase-1/2 scope. §6 collects the **design findings** writing this study surfaced — that feedback is the point of a POC study.
>
> Domain: the committed **subscription** fixture (our own original — `Subscription` root, repeatable `Addons` group with `Name`/`MonthlyFee` (scale 2), `Tier` enum `BASIC/PRO/ENTERPRISE`), extended illustratively where a construct needs it.

---

## 1. Setup — bind once, everything flows from the model

```java
import static io.github.mbackschat.a12.rulekit.Dsl.*;          // literals, fragments, severities
import io.github.mbackschat.a12.rulekit.*;

// Self-contained model (or: BoundModel.load(json, resolver) when it carries includes — F0)
BoundModel m = BoundModel.load(Path.of("subscription.dm.json"));

// Typed handles — runtime lookups, fail fast on wrong path or kind (L2a, §5a)
NumberRef fee  = m.num("/Subscription/Addons/MonthlyFee");     // knows: number, scale 2
EnumRef   tier = m.enumOf("/Subscription/Tier");               // knows: BASIC, PRO, ENTERPRISE
StringRef name = m.str("/Subscription/Addons/Name");
GroupRef  addons = m.group("/Subscription/Addons");            // knows: repeatable (max 20)
```

Everything the safety story needs is now in hand: kinds, scale, enum domain, repetition. `m.num(NAME)` throws *here*, not as an `MVK_*` three steps later.

## 2. Authoring a rule

### 2.1 The condition — typed composition

```java
// "A PRO subscription's add-ons must each carry a positive monthly fee."
Condition feeMissing  = tier.is("PRO").and(fee.notFilled());
Condition feeNotPos   = fee.filled().and(fee.le(0));            // le("0.00") overload: scale-explicit

// What cannot be written (compile-time, §5b):
// name.le(...)      — no ordering on strings: the method does not exist
// tier.is("GOLD")   — fails fast: not in the model's enum domain
// anything.not()    — no negation in the kernel: no such method; use the negative predicate families
```

### 2.2 Combinators — fragments are just methods (F5)

```java
static Condition mandatoryWhen(Condition gate, FieldRef field) {
    return gate.and(fieldNotFilled(field));
}
static Condition chargedAddon(BoundModel m) {                   // reusable, model-bound fragment
    return m.num("/Subscription/Addons/MonthlyFee").filled();
}

Condition proNeedsFee = mandatoryWhen(tier.is("PRO"), fee.field());
```

The same fragment drops into a rule `errorCondition` or a computation `precondition` unchanged — one expression language, many slots (§5).

### 2.3 The rule object — slots filled from typed handles (F2a)

```java
Rule rule = Rule.in(addons)                                     // rule must live in a group — by construction
        .errorField(fee)                                        // errorEntityRelPath computed, not hand-written
        .when(proNeedsFee)                                      // builder checks: error field referenced? → actionable error here
        .code("ADDON_FEE_REQUIRED_FOR_PRO")
        .severity(ERROR)
        .message(en("A PRO subscription requires a monthly fee per add-on: {0}", name),
                 de("Ein PRO-Abo erfordert eine Monatsgebühr je Zusatzoption: {0}", name))
        .build();                                               // message helper emits $Name.value$ correctly
```

What used to be five hand-written strings (`errorCondition`, `errorEntityRelPath`, message interpolations, …) is now one typed object; the §1-motivation failure classes (keyword-named field, error-field-not-referenced, rule-at-root, wrong relative path) are unrepresentable or fail at `build()` with the fix in the message.

### 2.4 Render → validate → emit

```java
rule.render();                       // "FieldFilled(MonthlyFee) And ..." — relative paths per fork 9
rule.render(ConditionLanguage.DE);   // same AST, German keywords — free (one catalog, two dialects)

ValidationResult vr = m.validate(rule);                 // F4: real kernel, parse/check only, no compile
if (!vr.ok()) {
    vr.problems().forEach(p -> System.out.println(p.advice()));
    // e.g. "MVK_INVALID_COMPARE_DEC_PLACES: operands differ in decimal scale (0 vs 2).
    //       Round one side: RoundDown([UnitPrice], 0) — see catalog: Round*"
}

m.add(rule);                          // injects the Rule node into the group's DM-JSON (public write path, D9)
m.writeTo(Path.of("subscription.dm.json"));
```

### 2.5 A computation, same materials (F2)

```java
// Illustrative computed field: /Subscription/AnnualCost (number, scale 2) = sum of add-on fees × 12
Computation annual = Computation.of(m.num("/Subscription/AnnualCost"))
        .commonPrecondition(atLeastOneFieldFilled(fee.field()))
        .when(tier.is("ENTERPRISE")).calc(sum(m.nums("Addons*/MonthlyFee")).times(12).discounted("0.90"))
        .otherwise(sum(m.nums("Addons*/MonthlyFee")).times(12));
// type & scale of every alternative checked against AnnualCost's declared scale (cheap case — D13);
// alternatives' mutual exclusivity pre-checked, kernel confirms; cycle check via the adapter (survey §11)
```

## 3. Modifying a rule — the edit loop (§12)

### 3.1 In-session transform (#1 — exists in spirit today; API sketch)

```java
// "Make the fee rule apply to PRO *and* ENTERPRISE"
Condition widened = proNeedsFee.transform(node -> switch (node) {
    case ComparisonCondition c when c.operatorId().equals("Equal")
            && c.right() instanceof EnumValueLiteral(String v) && v.equals("PRO")
            -> tier.is("PRO").or(tier.is("ENTERPRISE"));
    default -> node;
});
```

Bottom-up rewrite over the sealed tree; untouched nodes are reused (immutability, N5). The D14 ids make matching readable (`operatorId().equals("Equal")` instead of structural guessing).

### 3.2 Ingest a foreign, BA-authored rule (F10 — Phase 2)

```java
ExistingRule legacy = m.rule("/Subscription/Addons/LegacyFeeRule");
// its errorCondition text (hand-written years ago, with comments and short names):
//   ;; fee must be set for paid tiers
//   FieldFilled(MonthlyFee) AND [/Subscription/Tier] != "BASIC"

Ingest in = KernelAdapter.ingest(legacy, m);    // kernel parses → converter visitor → rulekit AST
switch (in) {
    case Ingest.Converted(Condition ast)      -> work(ast);
    case Ingest.Gap(String construct, var loc) -> log("converter gap: " + construct);  // fail-fast, named
}
// the AST's FieldRefs carry authored PathStyle hints (SHORT for MonthlyFee, ABSOLUTE for Tier);
// comments are concrete syntax — recorded as lost-on-re-render in the diff header (§12 lossiness)
```

### 3.3 Explain (F9) — same projection for built and ingested rules

```java
Explanation ex = m.explain(legacy);             // or m.explain(rule) for a rulekit-built one
System.out.println(ex.toJson());
```

```jsonc
{
  "rule": "/Subscription/Addons/LegacyFeeRule",
  "gloss": "Fires (data invalid) when an add-on's MonthlyFee is filled while the subscription tier is not BASIC.",
  "suspicious": ["gloss reads inverted for a requiredness rule — did the author mean FieldNotFilled? (error scenario: true ⇒ invalid)"],
  "errorField": { "path": "/Subscription/Addons/MonthlyFee", "kind": "number", "scale": 2 },
  "iterates": { "value": true, "why": "the error field sits under repeatable group Addons (max 20) — the rule runs per add-on, the message lands on each offending repetition" },
  "referencedFields": [
    { "path": "/Subscription/Addons/MonthlyFee", "kind": "number", "scale": 2, "roles": ["error-field", "operand"], "authoredPathStyle": "SHORT" },
    { "path": "/Subscription/Tier", "kind": "enumeration", "values": ["BASIC", "PRO", "ENTERPRISE"], "roles": ["operand"], "authoredPathStyle": "ABSOLUTE" }
  ],
  "operators": [
    { "id": "FieldFilled", "meaning": "true iff the field holds a value", "gotchas": ["a field with a formal error is removed from evaluation — FieldFilled is then neither true nor false"] },
    { "id": "And", "meaning": "logical conjunction", "gotchas": ["parentheses required when mixed with Or"] },
    { "id": "NotEqual", "meaning": "true iff operands differ", "gotchas": ["enums compare by stored value, never display text"] }
  ],
  "structural": { "group": "/Subscription/Addons", "severity": "ERROR", "conditionLanguage": "EN" }
}
```

Everything an LLM (or a human reviewer) needs to reason about *this* rule travels with it — model types, iteration behavior, operator semantics from the catalog, even a heuristic "suspicious" hint. It is a pure projection of catalog + model index onto the AST (D14 pays off here).

### 3.4 Re-express, diff, validate, write back (#2)

```java
Condition fixed = tier.isNot("BASIC").and(fee.notFilled());     // rebuilt from intent, not patched text

RenderedDiff diff = m.diff(legacy, fixed);
System.out.println(diff);
```

```
--- /Subscription/Addons/LegacyFeeRule (authored text; 1 comment will be dropped on write)
+++ re-expressed via rulekit
- ;; fee must be set for paid tiers
- FieldFilled(MonthlyFee) AND [/Subscription/Tier] != "BASIC"
+ [/Subscription/Tier] != "BASIC" And FieldNotFilled(MonthlyFee)
```

```java
ValidationResult vr = m.validate(legacy.withCondition(fixed));  // kernel confirms before anything is written
m.replaceCondition(legacy, fixed);                              // DM-JSON write path; diff was the review gate
```

Every modify — transform or re-express — ends in a **rendered diff + kernel validation**; nothing edits strings or the kernel's tree directly (D4).

## 4. The LLM interaction this enables (§14)

The skill teaches one loop: **ingest → explain → re-express/transform → diff → validate**. A realistic session:

> **User:** "Customers report they can't save BASIC subscriptions — something fires on add-ons without a fee. Find it and fix it so only paid tiers require a fee."

| Step | Agent does | rulekit gives back |
|---|---|---|
| 1 | `m.rules()` → scan; `m.explain(each)` | structured explanations; the gloss + `suspicious` hint flags `LegacyFeeRule` ("fires when fee **is** filled on non-BASIC" — inverted) |
| 2 | reads the catalog entries cited in the explanation | `FieldFilled` vs `FieldNotFilled` semantics, the error-scenario inversion from the language-semantics layer — no BA docs needed (N6) |
| 3 | re-expresses intent with the builder: `tier.isNot("BASIC").and(fee.notFilled())` | a typed AST — illegal variants didn't compile |
| 4 | `m.validate(...)` | `ok` — or an `MVK_*` mapped to an actionable fix the agent can apply mechanically |
| 5 | `m.diff(legacy, fixed)` | the reviewable diff above — pasted into the PR/answer for the human gate |
| 6 | `m.replaceCondition(...)`, write DM-JSON | the model file change, exactly scoped |

And the authoring direction:

> **User:** "Add a rule: ENTERPRISE subscriptions need at least two add-ons."

The agent never guesses syntax: it asks the model index for the group (`addons.repeatable()` → true, max 20), finds the aggregate in the catalog (`NumberOfFilledFields` — operands, return kind, example), composes `tier.is("ENTERPRISE").and(numberOfFilledFields(m.nums("Addons*/Name")).lt(2))`, validates, and presents rendered text + diff. A wrong guess fails in step 3 (compile) or 4 (kernel) with a message that names the fix — the feedback loop an LLM thrives on, instead of a silent `MVK_UNEXPECTED_TOKEN` at codegen time.

**Execution vehicle:** rulekit is a library; the skill's recommended harness is a scratch JUnit test or `jshell`/JBang snippet in the consumer repo (model file + assertions + diff output) — see finding 6 below.

## 5. What exists today vs. this study

| Study element | Status |
|---|---|
| `BoundModel` handles, typed conditions, catalog-driven EN/DE render, `m.validate` (as `RuleValidator`), D14 ids + `PathStyle` | **exists** (Phase 1, partial operator coverage) |
| `Dsl.*` static facade (handle-based factories), scale-faithful literal overloads (`eq("0.00")`/BigDecimal), `Condition.transform` (#1) | **exists** (findings 1–3 implemented 2026-06-03; literal-scale behavior kernel-verified — see `KERNEL-INTEGRATION-NOTES.md`) |
| `Rule`/`Computation` authoring objects, message helper, `m.add`/`replaceCondition`/`writeTo`, relative-path rendering | Phase 1 remainder (F2/F2a, fork 9) |
| `KernelAdapter.ingest`, `m.explain`, `m.diff` | Phase 2 (the edit loop) |
| `sum(...)`, aggregates, `*` paths, `numberOfFilledFields` | corpus backlog items 6–7 |

## 6. Design findings this study surfaced (feedback into the proposal/code)

1. **The `Dsl.*` facade is missing and needed.** Free-standing forms (`fieldNotFilled(ref)`, `atLeastOneFieldFilled(...)`, `sum(...)`) read better in combinators than handle-method chains; today everything hangs off `BoundModel`/handles. → introduce `Dsl` with static factories that *take handles*, keeping model-awareness (no string paths in `Dsl.*`).
2. **Literal ergonomics must be scale-aware.** `fee.le(0)` (long) vs a scale-2 field invites the `==`-scale trap on equality; offer `le("0.00")`/BigDecimal overloads and document that comparison-vs-literal scale is the kernel's call (D13).
3. **`transform` wants a single bottom-up rewriter** (`Condition.transform(UnaryOperator<Condition>)` + the calculation twin) — with sealed types + `when` guards it reads well; no visitor class needed. Cheap to add now.
4. **Rule identity matters for the edit loop**: ingest/diff/replace all key on the rule's full name + group. `ExistingRule` (name + slots + raw text) earns its place as a first-class handle — design it with the `Rule` authoring object, not after.
5. **`Ingest` should be a sealed result** (`Converted` / `Gap(construct, location)`) so converter gaps are data, not exceptions — the corpus round-trip oracle (`WORKSPACE-CORPUS-ANALYSIS.md`) then *is* a loop over `Ingest` results.
6. **The LLM needs an execution vehicle** — the library alone isn't invokable by an agent. Candidates: skill-documented scratch-test/JBang patterns (cheap, Phase 2) vs. a thin `rulekit-cli` (`explain <model> <rule>`, `validate`, `diff`) later. The skill should ship the scratch-test pattern first; a CLI is a separate decision.
7. **`explain` should flag suspicions, not just facts.** The inverted-predicate hint in §3.3 (error-scenario inversion misuse) is cheap heuristics over the same projection and is disproportionately valuable to the LLM loop. Candidate F9 refinement.
8. **Diff must disclose lossiness up front** — the "1 comment will be dropped" header line is the honest form of §12's canonicalization caveat; make it part of `RenderedDiff`'s contract.

*(1–3 are Phase-1-sized; 4–5 shape Phase-2 API design; 6–8 are proposal-level refinements to fold into §12/§14 when Phase 2 starts.)*
