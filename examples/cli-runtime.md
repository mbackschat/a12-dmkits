# dmtool CLI — runtime evaluation (eval & compute)

*2026-06-11T23:51:18Z by Showboat 0.6.1*
<!-- showboat-id: bf4f1a1d-efae-4469-94a0-28e706b4a839 -->

The previous tours operated on the **model** — its rules and computations as text. This one runs a document **instance** (actual field data) through the runtime engine and asks the *runtime* questions: which rules **fire** on this data, and what does a computed field **evaluate to**? Two read-only verbs, both `JSON-in / JSON-out`, both invoked through `dmtool` (the launcher shim) from the repo root; some steps also use `jq`. The document instance is a small JSON file — a nested `{"fields":{…}}` tree, grouped exactly like the model.

**The engine.** These runtime verbs default to the **native-safe interpreter** — a from-scratch evaluator that reproduces the A12 kernel's runtime semantics without the kernel's on-the-fly Groovy, so they run in the GraalVM native image too. Add **`--kernel`** to evaluate with the A12 kernel itself (JVM only; not available in the native image). The interpreter is verified rule-for-rule against the kernel, so the two agree on which rules fire and what computes. Output blocks are captured by [showboat](https://github.com/simonw/showboat); re-check them with `uvx showboat@0.6.1 verify examples/cli-runtime.md` (exit 0 = output still matches the live CLI).

## The document instance

A model is a *schema*; the runtime needs *data*. The instance below is the smallest order that exercises the date rule — just the two dates the rule turns on, nested under their `Order` group. Dates are written **ISO `yyyy-MM-dd`** (the model is `en_US`-only; that is the format the engine parses). Here the delivery (`2024-05-15`) lands **before** the order (`2024-06-01`) — the violation the stored rule `/Order/DeliveryNotBeforeOrder` is meant to catch.

```bash
cat > /tmp/rt-order-violation.json <<'JSON'
{ "fields": { "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "2024-05-15" } } }
JSON
cat /tmp/rt-order-violation.json
```

```output
{ "fields": { "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "2024-05-15" } } }
```

## model eval — which rules fire?

`model eval` runs the engine over the instance and reports, under `data.fired`, the **error codes of every rule that fired** (a rule *fires* when its violation condition evaluates true on this data). `--rule <path>` narrows the report to one stored rule and adds a `data.rule.{name,fired}` verdict for it. Discover its directional **I/O contract** from the CLI itself with `schema model eval` — what it consumes (a document instance) and the `EvalDocResult` it returns. (We show `schema`, not `--help`, on purpose: the I/O contract is **identical on the JVM and native builds**, whereas the flag list differs — `--kernel` is JVM-only and absent from the native binary.)

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
    "unsupported"
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
  "valid" : true,
  "summary" : "1 rule(s) fired",
  "data" : {
    "fired" : [ "DELIVERY_BEFORE_ORDER" ],
    "messages" : [ {
      "code" : "DELIVERY_BEFORE_ORDER",
      "rule" : "/Order/DeliveryNotBeforeOrder",
      "field" : "/Order[1]/DeliveryDate",
      "severity" : "ERROR",
      "type" : "VALUE_ERROR",
      "message" : "The delivery date must not be earlier than the order date.",
      "referenced" : [ "/Order[1]/OrderDate", "/Order[1]/DeliveryDate" ]
    } ],
    "unsupported" : [ {
      "name" : "/Order/EligibilityCheck",
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

→ `data.rule.fired: true` and `DELIVERY_BEFORE_ORDER` in `data.fired` — the rule fired on this data. The `messages[]` entry attributes it to its `rule` (`/Order/DeliveryNotBeforeOrder`), points at the offending `field` (path + repetition index, e.g. `/Order[1]/DeliveryDate`), and lists `referenced` — the operand fields the rule read. (An **OMISSION** error also carries `fillToFix`: the operands that, filled or changed, would clear it — the kernel's `refOmissionErrorResponsible`.) Remember the **polarity**: the condition describes the *violation* (delivery strictly before order), so "fired" means "this order is bad", not "this order is fine".

Notice `data.unsupported`. The model's *other* rule, `/Order/EligibilityCheck`, uses a `CustomCondition` (`ExternalEligibility`) — project code the native engine can't run. Rather than silently skip it (which would read as a clean pass), the engine **surfaces** it here so you know it wasn't evaluated. Custom *field types* whose format is declarative can be supplied with `--predefined-types`, and `--strict-custom` turns any such gap into a failure instead of a lenient pass — see [`cli-custom-types`](cli-custom-types.md).

Now the **empirical polarity check** — the same rule, the same model, a *compliant* instance. Only the delivery date changes: `2024-06-15` now lands **after** the order. Nothing should fire.

```bash
cat > /tmp/rt-order-ok.json <<'JSON'
{ "fields": { "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "2024-06-15" } } }
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
  "valid" : true,
  "summary" : "0 rule(s) fired",
  "data" : {
    "fired" : [ ],
    "messages" : [ ],
    "unsupported" : [ {
      "name" : "/Order/EligibilityCheck",
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
  "valid" : true,
  "summary" : "/Order/DeliveryNotBeforeOrder FIRED on this instance (a violation)",
  "data" : {
    "rule" : "/Order/DeliveryNotBeforeOrder",
    "fired" : true,
    "verdict" : "fired",
    "message" : "The delivery date must not be earlier than the order date."
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `verdict: fired` — a violation. Now feed it a **malformed** delivery date (`not-a-date`, which the `yyyy-MM-dd` field can't parse). The rule references `DeliveryDate`, so it is never evaluated:

```bash
cat > /tmp/rt-order-baddate.json <<'JSON'
{ "fields": { "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "not-a-date" } } }
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
  "valid" : true,
  "summary" : "/Order/DeliveryNotBeforeOrder was NOT evaluated — a referenced field is formally invalid",
  "data" : {
    "rule" : "/Order/DeliveryNotBeforeOrder",
    "fired" : false,
    "verdict" : "suppressed",
    "suppressedBy" : [ {
      "field" : "/Order/DeliveryDate",
      "formalErrorCode" : "datumFormatFalsch"
    } ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `verdict: suppressed`, **not** `passed` — and `suppressedBy` names the culprit (`/Order/DeliveryDate`, formal code `datumFormatFalsch`). A plain "did it fire" check would have silently reported `false` here and let you believe the order is clean. The three verdicts — `fired` / `passed` / `suppressed` — are the whole point.

## model eval — a candidate rule, not yet stored

You can also evaluate a rule that **isn't in the model** — `--condition "<DSL>" --field <path>` injects a one-off candidate (named `EvalDocCandidate` by default, error code `EVAL_DOC`), runs it against the instance, and tells you whether *it* fired. Nothing is persisted; it is the runtime twin of `rule check` (which only asks "is this valid?"). Here the candidate caps quantity at 100, and we feed it an order of 150.

```bash
cat > /tmp/rt-qty-over.json <<'JSON'
{ "fields": { "Order": { "Quantity": "150" } } }
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
  "valid" : true,
  "summary" : "1 rule(s) fired",
  "data" : {
    "fired" : [ "QTY_CAP" ],
    "messages" : [ {
      "code" : "QTY_CAP",
      "rule" : "/Order/EvalDocCandidate",
      "field" : "/Order/Quantity",
      "severity" : "ERROR",
      "type" : "VALUE_ERROR",
      "message" : "EvalDocCandidate"
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

→ The candidate fired (`QTY_CAP`, `data.rule.fired: true`) on `Quantity = 150`, attributed to the synthetic rule `/Order/EvalDocCandidate`. Drop the quantity below the cap and it would report `fired: false` — same dry-run, no write to the model.

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
    "computed"
  ]
}
```

We switch to the `subscription-computed` model, whose `EffectiveFeeComp` computes `/Subscription/Billing/EffectiveFee` as simply `[BaseFee]` — the effective fee equals the base fee. Give it a base fee and read back the result.

```bash
cat > /tmp/rt-sub-filled.json <<'JSON'
{ "fields": { "Subscription": { "Billing": { "BaseFee": "49.90" } } } }
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
  "valid" : true,
  "summary" : "computed 1 field(s)",
  "data" : {
    "computed" : [ {
      "field" : "/Subscription/Billing/EffectiveFee",
      "outcome" : "value",
      "value" : "49.9"
    } ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.computed[].value: "49.9"` — the computation evaluated `[BaseFee]` over the instance and returned the value. `outcome: "value"` says the field resolved to a concrete value (as opposed to staying empty or erroring). The trailing zero is dropped; the result is the engine's canonical numeric rendering (matching the kernel).

Now the **empty-operand** case — the same computation, but `BaseFee` is absent. In the A12 kernel an empty numeric operand reads as **0**, so `[BaseFee]` with no base fee does not stay empty or error: it computes `0`. This is a classic trap worth seeing once.

```bash
cat > /tmp/rt-sub-empty.json <<'JSON'
{ "fields": { "Subscription": { "Billing": { } } } }
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
  "valid" : true,
  "summary" : "computed 1 field(s)",
  "data" : {
    "computed" : [ {
      "field" : "/Subscription/Billing/EffectiveFee",
      "outcome" : "value",
      "value" : "0"
    } ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `value: "0"`, not empty and not an error: the absent `BaseFee` read as `0`, and `[BaseFee]` evaluated to `0`. **Empty-as-0** is the kernel's rule for numeric operands — a computed total over missing inputs silently lands at zero rather than staying blank. The lesson generalizes: trust the *measured* runtime value, never the value you expected from reading the formula.

## model seed — generate a sample instance

Writing instances by hand (as above) is fine for one check, but tedious for exploring a model. `model seed` **generates** a valid sample instance — every field a kind-appropriate value (numbers in scale, dates in their format, an enum a real member), repeatable groups populated — using the same kernel-free interpreter generator. It is **native-safe** (no kernel) and **deterministic** for a fixed `--seed`, and `--rows <group>:<n>` sets how many rows a repeatable group gets. Its output is the very `{"fields":{…}}` shape the runtime verbs consume.

```bash
dmtool -m examples/models/order-ruled.dm.json \
  model seed --seed 1 --rows /Order/Items:2 \
  | jq -c '.fields.Order | {topFields: (keys|length), items: (.Items|length), itemKeys: (.Items[0]|keys)}'
```

```output
{"topFields":13,"items":2,"itemKeys":["Count","Sku"]}
```

→ The model's shape drives the result: every top-level field present, the `Items` group instantiated with the **2** rows requested, each row carrying its declared fields. Values are random-but-valid; the structure is the model's.

Because the output IS a document instance, it round-trips straight into `model eval` — generate, then evaluate, no hand-authoring:

```bash
dmtool -m examples/models/order-ruled.dm.json model seed --seed 1 > /tmp/rt-seeded.json
dmtool -m examples/models/order-ruled.dm.json model eval --instance /tmp/rt-seeded.json | jq -c '{outcome}'
```

```output
{"outcome":"read"}
```

→ `outcome: "read"` — the generated instance is a valid document the runtime accepts.

## Recap

Read-only runtime verbs over a nested `{"fields":{…}}` instance — and `model seed` to generate one:

| verb | question | answer rides | proof shown |
|------|----------|--------------|-------------|
| `model eval` | which rules fire on this data? | `data.fired` (codes) + `data.rule.{name,fired}` with `--rule` | violation fired, compliant did not (same rule, flipped date) |
| `model eval --condition/--field` | would *this* candidate fire? | `data.rule.fired` | one-off `QTY_CAP` fired on `Quantity 150`, not persisted |
| `model compute` | what does a computed field evaluate to? | `data.computed[].{field,value,outcome}` | `[BaseFee]` = `49.9`; empty operand = `0` (empty-as-0) |
| `model seed` | give me a valid sample instance | the `{"fields":{…}}` document itself | structure matches the model; round-trips into `eval` |

Neither verb writes (`written: false`) — they evaluate the *model* against the *instance*, no mutation. The instance carries only the fields a check needs; everything else is absent (and, for numbers, reads as `0`).

## model eval — computations run first (the form-engine flow)

`model compute` showed `EffectiveFee` computes from `[BaseFee]`. Does `model eval` *see* that computed value? **By default, yes** — `model eval` runs the form-engine flow **compute → apply → validate**, so a rule (or a required check) over a computed field sees the computed value, exactly as the running application would. `--no-computations` drops to the kernel's bare `validateFull`, validating the *stored* values as-is. Watch a candidate rule `[EffectiveFee] > 0` flip between the two: `BaseFee` is `10`, `EffectiveFee` is left empty.

```bash
printf '%s' '{ "fields": { "Subscription": { "Billing": { "BaseFee": "10.00" } } } }' > /tmp/rt-eff.json
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
