# dmtool CLI — runtime evaluation (eval & compute)

*2026-06-11T23:51:18Z by Showboat 0.6.1*
<!-- showboat-id: bf4f1a1d-efae-4469-94a0-28e706b4a839 -->

The previous tours operated on the **model** — its rules and computations as text. This one runs a document **instance** (actual field data) through the runtime engine and asks the *runtime* questions: which rules **fire** on this data, and what does a computed field **evaluate to**? Two read-only verbs, both `JSON-in / JSON-out`, both invoked through `dmtool` (the launcher shim) from the repo root; some steps also use `jq`. The document instance is a small JSON file in **A12's own canonical Document JSON** — the shape the A12 kernel's document serializer reads and writes, so a document a real A12 application produced drops straight in. It is a nested tree grouped exactly like the model, with a **root group name as a top-level key** and no wrapper.

**The engine.** These runtime verbs run on the **native-safe interpreter** — a from-scratch evaluator that reproduces the A12 kernel's runtime semantics without the kernel's on-the-fly Groovy, so they run in the GraalVM native image too; it is the sole runtime eval engine on every target. The interpreter is verified rule-for-rule against the kernel, so the two agree on which rules fire and what computes. Output blocks are captured by [showboat](https://github.com/simonw/showboat); re-check them with `uvx showboat@0.6.1 verify examples/cli-runtime.md` (exit 0 = output still matches the live CLI).

## The document instance

A model is a *schema*; the runtime needs *data*. The instance below is the smallest order that exercises the date rule — just the two dates the rule turns on, nested under their `Order` group. Dates are written **ISO `yyyy-MM-dd`** (the model is `en_US`-only; that is the format the engine parses). Here the delivery (`2024-05-15`) lands **before** the order (`2024-06-01`) — the violation the stored rule `/Order/DeliveryNotBeforeOrder` is meant to catch.

```bash
cat > /tmp/rt-order-violation.json <<'JSON'
{ "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "2024-05-15" } }
JSON
cat /tmp/rt-order-violation.json
```

```output
{ "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "2024-05-15" } }
```

## model eval — which rules fire?

`model eval` runs the engine over the instance and reports, under `data.fired`, the **error codes of every rule that fired** (a rule *fires* when its violation condition evaluates true on this data). `--rule <path>` narrows the report to one stored rule and adds a `data.rule.{name,fired}` verdict for it. Discover its directional **I/O contract** from the CLI itself with `schema model eval` — what it consumes (a document instance) and the `EvalDocResult` it returns. (We show `schema`, not `--help`, on purpose: the I/O contract is **identical on the JVM and native builds**, whereas `--help` can differ by picocli synopsis wrapping between builds.)

```bash
dmtool schema model eval \
  | jq '{op, returns, consumes: .docInput.title, dataPayload: .dataSchema.title, dataKeys: (.dataSchema.properties|keys)}'
```

```output
{
  "op": "model eval",
  "returns": "result",
  "consumes": "DocumentInstance",
  "dataPayload": "EvalDocResult",
  "dataKeys": [
    "fired",
    "messages",
    "rule",
    "unsupported",
    "validationMode"
  ]
}
```

Evaluate the violation instance, narrowed to the date rule:

```bash
dmtool -m examples/models/order-ruled.dm.json \
  model eval \
  --instance /tmp/rt-order-violation.json \
  --rule /Order/DeliveryNotBeforeOrder
```

```output
{
  "target" : "model",
  "op" : "eval",
  "outcome" : "read",
  "ok" : true,
  "engine" : "DM_INTERPRETER",
  "engineVersion" : "0.13.0",
  "toolVersion" : "0.13.0",
  "summary" : "1 rule(s) fired",
  "data" : {
    "fired" : [ "DELIVERY_BEFORE_ORDER" ],
    "messages" : [ {
      "code" : "DELIVERY_BEFORE_ORDER",
      "rule" : "/Order/DeliveryNotBeforeOrder",
      "field" : "Order/DeliveryDate",
      "severity" : "ERROR",
      "type" : "VALUE_ERROR",
      "message" : "The delivery date must not be earlier than the order date.",
      "referenced" : [ "Order/OrderDate", "Order/DeliveryDate" ]
    } ],
    "unsupported" : [ {
      "name" : "/Order/EligibilityCheck",
      "subject" : "element",
      "reason" : "unregistered custom condition \"ExternalEligibility\" — register it in the custom-condition registry"
    } ],
    "rule" : {
      "name" : "/Order/DeliveryNotBeforeOrder",
      "fired" : true
    }
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.rule.fired: true` and `DELIVERY_BEFORE_ORDER` in `data.fired` — the rule fired on this data. The `messages[]` entry attributes it to its `rule` (`/Order/DeliveryNotBeforeOrder`), points at the offending `field` in **canonical A12 pointer form** — no leading slash, and the first repetition left implicit, so it reads `Order/DeliveryDate` here and `Order/Lines[2]/Quantity` for row 2 of a repeated group — and lists `referenced` — the operand fields the rule read. (An **OMISSION** error also carries `fillToFix`: the operands that, filled or changed, would clear it — the kernel's `refOmissionErrorResponsible`.) Remember the **polarity**: the condition describes the *violation* (delivery strictly before order), so "fired" means "this order is bad", not "this order is fine".

Notice `data.unsupported`. The model's *other* rule, `/Order/EligibilityCheck`, uses a `CustomCondition` (`ExternalEligibility`) — project code the native engine can't run. Rather than silently skip it (which would read as a clean pass), the engine **surfaces** it here so you know it wasn't evaluated. Custom *field types* whose format is declarative can be supplied with `--predefined-types`, and `--strict-custom` turns any such gap into a failure instead of a lenient pass — see [`cli-custom-types`](cli-custom-types.md).

Now the **empirical polarity check** — the same rule, the same model, a *compliant* instance. Only the delivery date changes: `2024-06-15` now lands **after** the order. Nothing should fire.

```bash
cat > /tmp/rt-order-ok.json <<'JSON'
{ "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "2024-06-15" } }
JSON
dmtool -m examples/models/order-ruled.dm.json \
  model eval \
  --instance /tmp/rt-order-ok.json \
  --rule /Order/DeliveryNotBeforeOrder
```

```output
{
  "target" : "model",
  "op" : "eval",
  "outcome" : "read",
  "ok" : true,
  "engine" : "DM_INTERPRETER",
  "engineVersion" : "0.13.0",
  "toolVersion" : "0.13.0",
  "summary" : "0 rule(s) fired",
  "data" : {
    "fired" : [ ],
    "messages" : [ ],
    "unsupported" : [ {
      "name" : "/Order/EligibilityCheck",
      "subject" : "element",
      "reason" : "unregistered custom condition \"ExternalEligibility\" — register it in the custom-condition registry"
    } ],
    "rule" : {
      "name" : "/Order/DeliveryNotBeforeOrder",
      "fired" : false
    }
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.rule.fired: false`, empty `fired`/`messages` — the compliant order does **not** trip the rule. The two runs together are the proof: flip the one date that matters and the verdict flips with it. Same rule, opposite data, opposite outcome.

## model eval — partial validation for one page

`--relevant` exposes the form engine's partial/wizard-page validation. The JSON array names the field or group instances currently relevant; `[*]` (or `[0]`) means every repetition and `[n]` selects one row; an **omitted** index on a repeatable segment is refused rather than guessed, because that spelling means repetition 1 in A12 and reading it as "all" would silently widen the check. A rule runs only when its error field is relevant, and an omitted operand is UNKNOWN. (A rule over a *whole-repetition* aggregate or value-list is fully known only when that repeatable level is wildcarded — `[*]` or a covering group — not when its rows are listed individually.) Selecting only `DeliveryDate` therefore suppresses the date comparison because `OrderDate` is not on this page:

```bash
dmtool -m examples/models/order-ruled.dm.json \
  model eval --instance /tmp/rt-order-violation.json --no-computations \
  --relevant '["/Order/DeliveryDate"]' \
  | jq '{mode:.data.validationMode,fired:.data.fired}'
```

```output
{
  "mode": "partial",
  "fired": []
}
```

Selecting the `Order` group makes all of its descendants relevant, so the same violation fires:

```bash
dmtool -m examples/models/order-ruled.dm.json \
  model eval --instance /tmp/rt-order-violation.json --no-computations \
  --relevant '["/Order"]' \
  | jq '{mode:.data.validationMode,fired:.data.fired}'
```

```output
{
  "mode": "partial",
  "fired": [
    "DELIVERY_BEFORE_ORDER"
  ]
}
```

Global fields are added automatically even when the caller omits them. A concrete repetition pointer such as `/Order/Lines[2]/Quantity` selects row 2; `/Order/Lines[*]/Quantity` selects all rows.

## rule eval — one rule, three outcomes (fired / passed / suppressed)

`model eval --rule` answers "did it fire?" — but `fired: false` is **two** different things: the rule was evaluated and *passed*, or it was **never evaluated** because a field it references is formally invalid (the engine skips a rule whose operand is *unknown*). `rule eval` is the rule-first verb that tells them apart, with a three-way `verdict`. First the violation instance from above — the rule fires:

```bash
dmtool -m examples/models/order-ruled.dm.json \
  rule eval /Order/DeliveryNotBeforeOrder \
  --instance /tmp/rt-order-violation.json
```

```output
{
  "target" : "rule",
  "op" : "eval",
  "outcome" : "read",
  "ok" : true,
  "engine" : "DM_INTERPRETER",
  "engineVersion" : "0.13.0",
  "toolVersion" : "0.13.0",
  "summary" : "/Order/DeliveryNotBeforeOrder FIRED on this instance (a violation)",
  "data" : {
    "rule" : "/Order/DeliveryNotBeforeOrder",
    "fired" : true,
    "verdict" : "fired",
    "code" : "DELIVERY_BEFORE_ORDER",
    "field" : "Order/DeliveryDate",
    "severity" : "ERROR",
    "type" : "VALUE_ERROR",
    "message" : "The delivery date must not be earlier than the order date.",
    "referenced" : [ "Order/OrderDate", "Order/DeliveryDate" ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `verdict: fired` — a violation, reported with the firing message's own channels: the same `code`/`field`/`severity`/`type`/`message`/`referenced` keys a `model eval` `messages[]` entry carries (plus `fillToFix` on an omission). `type` is the one worth knowing about — `VALUE_ERROR` means *what is there is wrong*, `OMISSION_ERROR` means *filling something could clear it* — and it is a semantic fact, not a label: for `FieldValuesNotUnique` a reached `Having` on the starred operand flips a firing to `OMISSION_ERROR` on otherwise identical data. Reading it here needs no persisted rule, so a `--condition` candidate can be measured without touching the model. Now feed it a **malformed** delivery date (`not-a-date`, which the `yyyy-MM-dd` field can't parse). The rule references `DeliveryDate`, so it is never evaluated:

```bash
cat > /tmp/rt-order-baddate.json <<'JSON'
{ "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "not-a-date" } }
JSON
dmtool -m examples/models/order-ruled.dm.json \
  rule eval /Order/DeliveryNotBeforeOrder \
  --instance /tmp/rt-order-baddate.json
```

```output
{
  "target" : "rule",
  "op" : "eval",
  "outcome" : "read",
  "ok" : true,
  "engine" : "DM_INTERPRETER",
  "engineVersion" : "0.13.0",
  "toolVersion" : "0.13.0",
  "summary" : "/Order/DeliveryNotBeforeOrder was NOT evaluated — a referenced field is formally invalid",
  "data" : {
    "rule" : "/Order/DeliveryNotBeforeOrder",
    "fired" : false,
    "verdict" : "suppressed",
    "suppressedBy" : [ {
      "field" : "Order/DeliveryDate",
      "formalErrorCode" : "datumFormatFalsch"
    } ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `verdict: suppressed`, **not** `passed` — and `suppressedBy` names the culprit (`/Order/DeliveryDate`, formal code `datumFormatFalsch`). A plain "did it fire" check would have silently reported `false` here and let you believe the order is clean. The three verdicts — `fired` / `passed` / `suppressed` — are the whole point.

## model eval — a candidate rule, not yet stored

You can also evaluate a rule that **isn't in the model** — `--condition "<DSL>" --field <path>` **injects** a one-off candidate (named `EvalDocCandidate` by default, error code `EVAL_DOC`) into the model, runs the **whole** document, and narrows `data.rule` to whether *it* fired. Nothing is persisted; it is the runtime twin of `rule check` (which only asks "is this valid?"), and — because the candidate is injected and the full model runs — the candidate's message is row-indexed exactly as if you'd persisted it, and the other rules still report (here `EligibilityCheck` is an external `CustomCondition` with no impl, so it lands in `data.unsupported`). Here the candidate caps quantity at 100, and we feed it an order of 150.

```bash
cat > /tmp/rt-qty-over.json <<'JSON'
{ "Order": { "Quantity": "150" } }
JSON
dmtool -m examples/models/order-ruled.dm.json \
  model eval \
  --instance /tmp/rt-qty-over.json \
  --condition "[/Order/Quantity] > 100" \
  --field /Order/Quantity \
  --code QTY_CAP
```

```output
{
  "target" : "model",
  "op" : "eval",
  "outcome" : "read",
  "ok" : true,
  "engine" : "DM_INTERPRETER",
  "engineVersion" : "0.13.0",
  "toolVersion" : "0.13.0",
  "summary" : "1 rule(s) fired",
  "data" : {
    "fired" : [ "QTY_CAP" ],
    "messages" : [ {
      "code" : "QTY_CAP",
      "rule" : "/Order/EvalDocCandidate",
      "field" : "Order/Quantity",
      "severity" : "ERROR",
      "type" : "VALUE_ERROR",
      "message" : "EvalDocCandidate",
      "referenced" : [ "Order/Quantity" ]
    } ],
    "unsupported" : [ {
      "name" : "/Order/EligibilityCheck",
      "subject" : "element",
      "reason" : "unregistered custom condition \"ExternalEligibility\" — register it in the custom-condition registry"
    } ],
    "rule" : {
      "name" : "/Order/EvalDocCandidate",
      "fired" : true
    }
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ The candidate fired (`QTY_CAP`, `data.rule.fired: true`) on `Quantity = 150`, attributed to the synthetic rule `/Order/EvalDocCandidate` at the row-indexed `/Order[1]/Quantity`. Drop the quantity below the cap and it would report `fired: false` — same dry-run, no write to the model.

## model compute — what does a computed field evaluate to?

`model compute` is the other runtime read: it runs the model's **computations** over the instance and returns each computed field's **value** under `data.computed`. Its I/O contract — same build-stable `schema` (not `--help`) as `model eval` above:

```bash
dmtool schema model compute \
  | jq '{op, returns, consumes: .docInput.title, dataPayload: .dataSchema.title, dataKeys: (.dataSchema.properties|keys)}'
```

```output
{
  "op": "model compute",
  "returns": "result",
  "consumes": "DocumentInstance",
  "dataPayload": "ComputeDocResult",
  "dataKeys": [
    "computed",
    "declarations",
    "instances"
  ]
}
```

We switch to the `subscription-computed` model, whose `EffectiveFeeComp` computes `/Subscription/Billing/EffectiveFee` as simply `[BaseFee]` — the effective fee equals the base fee. Give it a base fee and read back the result.

```bash
cat > /tmp/rt-sub-filled.json <<'JSON'
{ "Subscription": { "Billing": { "BaseFee": "49.90" } } }
JSON
dmtool -m examples/models/subscription-computed.dm.json \
  model compute --instance /tmp/rt-sub-filled.json
```

```output
{
  "target" : "model",
  "op" : "compute",
  "outcome" : "read",
  "ok" : true,
  "engine" : "DM_INTERPRETER",
  "engineVersion" : "0.13.0",
  "toolVersion" : "0.13.0",
  "summary" : "computed 1 computation(s) over 1 field instance(s)",
  "data" : {
    "computed" : [ {
      "field" : "Subscription/Billing/EffectiveFee",
      "declaration" : "Subscription/Billing/EffectiveFee",
      "outcome" : "value",
      "value" : "49.9"
    } ],
    "declarations" : 1,
    "instances" : 1
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.computed[].value: "49.9"` — the computation evaluated `[BaseFee]` over the instance and returned the value. `outcome: "value"` says the field resolved to a concrete value (as opposed to staying empty or erroring). The trailing zero is dropped; the result is the engine's canonical numeric rendering (matching the kernel).

Now the **empty-operand** case — the same computation, but `BaseFee` is absent. In the A12 kernel an empty numeric operand reads as **0**, so `[BaseFee]` with no base fee does not stay empty or error: it computes `0`. This is a classic trap worth seeing once.

```bash
cat > /tmp/rt-sub-empty.json <<'JSON'
{ "Subscription": { "Billing": { } } }
JSON
dmtool -m examples/models/subscription-computed.dm.json \
  model compute --instance /tmp/rt-sub-empty.json
```

```output
{
  "target" : "model",
  "op" : "compute",
  "outcome" : "read",
  "ok" : true,
  "engine" : "DM_INTERPRETER",
  "engineVersion" : "0.13.0",
  "toolVersion" : "0.13.0",
  "summary" : "computed 1 computation(s) over 1 field instance(s)",
  "data" : {
    "computed" : [ {
      "field" : "Subscription/Billing/EffectiveFee",
      "declaration" : "Subscription/Billing/EffectiveFee",
      "outcome" : "value",
      "value" : "0"
    } ],
    "declarations" : 1,
    "instances" : 1
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `value: "0"`, not empty and not an error: the absent `BaseFee` read as `0`, and `[BaseFee]` evaluated to `0`. **Empty-as-0** is the kernel's rule for numeric operands — a computed total over missing inputs silently lands at zero rather than staying blank. The lesson generalizes: trust the *measured* runtime value, never the value you expected from reading the formula.

## model seed — generate a sample instance

Writing instances by hand (as above) is fine for one check, but tedious for exploring a model. `model seed` **generates** a best-effort, model-derived **sample candidate** — every field a kind-appropriate value (numbers in scale, dates in their format, an enum a real member), repeatable groups populated — using the same kernel-free interpreter generator. It is **native-safe** (no kernel) and **deterministic** for a fixed `--seed`, and `--rows <group>:<n>` sets how many rows a repeatable group gets. Its output is the very shape the runtime verbs consume — the same canonical writer, so it round-trips by construction: a NUMBER and a BOOLEAN come back as native JSON, a repeatable group as an array.

```bash
dmtool -m examples/models/order-ruled.dm.json \
  model seed --seed 1 --rows /Order/Items:2 \
  | jq -c '.Order | {topFields: (keys|length), items: (.Items|length), itemKeys: (.Items[0]|keys)}'
```

```output
{"topFields":13,"items":2,"itemKeys":["Count","Sku"]}
```

→ The model's shape drives the result: every top-level field present, the `Items` group instantiated with the **2** rows requested, each row carrying its declared fields. Values are random, kind-appropriate best-effort samples; the structure is the model's.

Because the output IS a document instance, it round-trips straight into `model eval` — generate, then evaluate, no hand-authoring:

```bash
dmtool -m examples/models/order-ruled.dm.json model seed --seed 1 > /tmp/rt-seeded.json
dmtool -m examples/models/order-ruled.dm.json model eval --instance /tmp/rt-seeded.json | jq -c '{outcome}'
```

```output
{"outcome":"read"}
```

→ `outcome: "read"` — the eval **ran** over the instance (it parsed and evaluated). `read` is the read-verb's outcome; it is **not** a claim that the document passed validation or is model-accepted — a fired rule would still return `outcome:"read"`, so read `data.fired` for any violations.

## Recap

Read-only runtime verbs over a document instance in A12's canonical Document JSON — and `model seed` to generate one:

| verb | question | answer rides | proof shown |
|------|----------|--------------|-------------|
| `model eval` | which rules fire on this data? | `data.fired` (codes) + `data.rule.{name,fired}` with `--rule` | violation fired, compliant did not (same rule, flipped date) |
| `model eval --condition/--field` | would *this* candidate fire? | `data.rule.fired` | one-off `QTY_CAP` fired on `Quantity 150`, not persisted |
| `model compute` | what does a computed field evaluate to? | `data.computed[].{field,value,outcome}` | `[BaseFee]` = `49.9`; empty operand = `0` (empty-as-0) |
| `model seed` | give me a best-effort sample candidate | the canonical document itself | structure matches the model; round-trips into `eval` |

Neither verb writes (`written: false`) — they evaluate the *model* against the *instance*, no mutation. The instance carries only the fields a check needs; everything else is absent (and, for numbers, reads as `0`).

## model eval — computations run first (the form-engine flow)

`model compute` showed `EffectiveFee` computes from `[BaseFee]`. Does `model eval` *see* that computed value? **By default, yes** — `model eval` runs the form-engine flow **compute → apply → validate**, so a rule (or a required check) over a computed field sees the computed value, exactly as the running application would. `--no-computations` drops to the kernel's bare `validateFull`, validating the *stored* values as-is. Watch a candidate rule `[EffectiveFee] > 0` flip between the two: `BaseFee` is `10`, `EffectiveFee` is left empty.

```bash
printf '%s' '{ "Subscription": { "Billing": { "BaseFee": "10.00" } } }' > /tmp/rt-eff.json
dmtool -m examples/models/subscription-computed.dm.json \
  model eval --instance /tmp/rt-eff.json \
  --field /Subscription/Billing/EffectiveFee --condition "[EffectiveFee] > 0" --code EFF_POSITIVE \
  | jq -c '{fired: .data.fired, ruleFired: .data.rule.fired}'

```

```output
{"fired":["EFF_POSITIVE"],"ruleFired":true}
```

→ the computation filled `EffectiveFee` (`10`) **before** the candidate ran, so `[EffectiveFee] > 0` fired — the form-engine answer.

```bash
dmtool -m examples/models/subscription-computed.dm.json \
  model eval --instance /tmp/rt-eff.json \
  --field /Subscription/Billing/EffectiveFee --condition "[EffectiveFee] > 0" --code EFF_POSITIVE \
  --no-computations \
  | jq -c '{fired: .data.fired, ruleFired: .data.rule.fired}'

```

```output
{"fired":[],"ruleFired":false}
```

→ with `--no-computations` the stored `EffectiveFee` is empty (an empty number reads as `0`), so `0 > 0` is false and nothing fired. Same instance, same rule — the only difference is whether computations ran first. **Use the default to validate as the app will; `--no-computations` to validate stored values as-is.**
