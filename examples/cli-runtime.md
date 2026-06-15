# dmtool CLI — runtime evaluation (eval & compute)

*2026-06-11T23:51:18Z by Showboat 0.6.1*
<!-- showboat-id: bf4f1a1d-efae-4469-94a0-28e706b4a839 -->

The previous tours operated on the **model** — its rules and computations as text. This one runs the kernel over a document **instance** (actual field data) and asks the *runtime* questions: which rules **fire** on this data, and what does a computed field **evaluate to**? Two read-only verbs, both `JSON-in / JSON-out` over the A12 kernel, both invoked through `dmtool` (the launcher shim) from the repo root; some steps also use `jq`. The document instance is a small JSON file — a nested `{"fields":{…}}` tree, grouped exactly like the model. Output blocks are captured by [showboat](https://github.com/simonw/showboat); re-check them with `uvx showboat@0.6.1 verify examples/cli-runtime.md` (exit 0 = output still matches the live CLI).

## The document instance

A model is a *schema*; the runtime needs *data*. The instance below is the smallest order that exercises the date rule — just the two dates the rule turns on, nested under their `Order` group. Dates are written **ISO `yyyy-MM-dd`** (the model is `en_US`-only; that is the format the kernel parses). Here the delivery (`2024-05-15`) lands **before** the order (`2024-06-01`) — the violation the stored rule `/Order/DeliveryNotBeforeOrder` is meant to catch.

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

`model eval` runs the kernel over the instance and reports, under `data.fired`, the **error codes of every rule that fired** (a rule *fires* when its violation condition evaluates true on this data). `--rule <path>` narrows the report to one stored rule and adds a `data.rule.{name,fired}` verdict for it. The flags come from the CLI itself — no external doc needed.

```bash
dmtool -m cli/src/test/resources/models/order-ruled.dm.json model eval --help
```

```output
Usage: dmtool model eval [-hV] [--code=<errorCode>] [--condition=<DSL>]
                         --doc=<document.json> [--field=<fieldPathInModel>]
                         [--group=<groupPathInModel>] [--locale=<locale>]
                         [-m=<model.json>] [--name=<name>]
                         [--rule=<rulePathInModel>] [--severity=ERROR|WARNING]
                         [-w=<dir>]...
Run the kernel over a document INSTANCE; report which rules fire in `data`
(runtime).
      --code=<errorCode>     the candidate rule's error code (default: EVAL_DOC)
      --condition=<DSL>      evaluate a CANDIDATE rule (with --field): a
                               condition true on a VIOLATION; injected, not
                               persisted
      --doc=<document.json>  the document INSTANCE (field data) — a nested JSON
                               tree under "fields". See `schema model eval`.
      --field=<fieldPathInModel>
                             the candidate rule's error field as a path INSIDE
                               the model (with --condition)
      --group=<groupPathInModel>
                             the candidate rule's iteration scope (group path);
                               defaults to the field's parent
  -h, --help                 Show this help message and exit.
      --locale=<locale>      the evaluation locale (default: en_US)
  -m, --model=<model.json>   the DM-JSON model file to operate on (set once;
                               accepted before or after the verb)
      --name=<name>          the candidate rule's name (default:
                               EvalDocCandidate)
      --rule=<rulePathInModel>
                             narrow the report to an EXISTING rule (its full
                               slash-path)
      --severity=ERROR|WARNING
                             the candidate rule's severity (default: ERROR)
  -V, --version              Print version information and exit.
  -w, --workspace=<dir>      the workspace root(s): directory tree(s) to
                               resolve a model's included / type-def-imported
                               models from (repeatable), set once for every
                               verb so multi-file models resolve with no
                               per-verb flag. Env DMTOOL_WORKSPACE is the
                               set-once-per-session fallback; default is the
                               model's own folder.
```

Evaluate the violation instance, narrowed to the date rule:

```bash
dmtool -m cli/src/test/resources/models/order-ruled.dm.json \
  model eval \
  --doc /tmp/rt-order-violation.json \
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
      "field" : "PartiallyKnownDocumentMultiPointerImpl[/Order/DeliveryDate, [1, 1]]",
      "severity" : "ERROR",
      "type" : "VALUE_ERROR",
      "message" : "The delivery date must not be earlier than the order date."
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

→ `data.rule.fired: true` and `DELIVERY_BEFORE_ORDER` in `data.fired` — the rule fired on this data. The `messages[]` entry attributes it to its `rule` (`/Order/DeliveryNotBeforeOrder`) and points at the offending `field`. Remember the **polarity**: the condition describes the *violation* (delivery strictly before order), so "fired" means "this order is bad", not "this order is fine".

Now the **empirical polarity check** — the same rule, the same model, a *compliant* instance. Only the delivery date changes: `2024-06-15` now lands **after** the order. Nothing should fire.

```bash
cat > /tmp/rt-order-ok.json <<'JSON'
{ "fields": { "Order": { "OrderDate": "2024-06-01", "DeliveryDate": "2024-06-15" } } }
JSON
dmtool -m cli/src/test/resources/models/order-ruled.dm.json \
  model eval \
  --doc /tmp/rt-order-ok.json \
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

## rule test — one rule, three outcomes (fired / passed / suppressed)

`model eval --rule` answers "did it fire?" — but `fired: false` is **two** different things: the rule was evaluated and *passed*, or it was **never evaluated** because a field it references is formally invalid (the kernel skips a rule whose operand is *unknown*). `rule test` is the rule-first verb that tells them apart, with a three-way `verdict`. First the violation instance from above — the rule fires:

```bash
dmtool -m cli/src/test/resources/models/order-ruled.dm.json \
  rule test /Order/DeliveryNotBeforeOrder \
  --doc /tmp/rt-order-violation.json
```

```output
{
  "target" : "rule",
  "op" : "test",
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
dmtool -m cli/src/test/resources/models/order-ruled.dm.json \
  rule test /Order/DeliveryNotBeforeOrder \
  --doc /tmp/rt-order-baddate.json
```

```output
{
  "target" : "rule",
  "op" : "test",
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
dmtool -m cli/src/test/resources/models/order-ruled.dm.json \
  model eval \
  --doc /tmp/rt-qty-over.json \
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
      "field" : "PartiallyKnownDocumentMultiPointerImpl[/Order/Quantity, [1, 1]]",
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

`model compute` is the other runtime read: it runs the model's **computations** over the instance and returns each computed field's **value** under `data.computed`. We switch to the `subscription-computed` model, whose `EffectiveFeeComp` computes `/Subscription/Billing/EffectiveFee` as simply `[BaseFee]` — the effective fee equals the base fee. Give it a base fee and read back the result.

```bash
dmtool -m cli/src/test/resources/models/subscription-computed.dm.json model compute --help
```

```output
Usage: dmtool model compute [-hV] --doc=<document.json> [--locale=<locale>]
                            [-m=<model.json>] [-w=<dir>]...
Run computations over a document INSTANCE; report each computed field's value
in `data` (read-only).
      --doc=<document.json>  the document INSTANCE (field data) — a nested JSON
                               tree under "fields". See `schema model compute`.
  -h, --help                 Show this help message and exit.
      --locale=<locale>      the computation locale (default: en_US)
  -m, --model=<model.json>   the DM-JSON model file to operate on (set once;
                               accepted before or after the verb)
  -V, --version              Print version information and exit.
  -w, --workspace=<dir>      the workspace root(s): directory tree(s) to
                               resolve a model's included / type-def-imported
                               models from (repeatable), set once for every
                               verb so multi-file models resolve with no
                               per-verb flag. Env DMTOOL_WORKSPACE is the
                               set-once-per-session fallback; default is the
                               model's own folder.
```

```bash
cat > /tmp/rt-sub-filled.json <<'JSON'
{ "fields": { "Subscription": { "Billing": { "BaseFee": "49.90" } } } }
JSON
dmtool -m cli/src/test/resources/models/subscription-computed.dm.json \
  model compute --doc /tmp/rt-sub-filled.json
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

→ `data.computed[].value: "49.9"` — the computation evaluated `[BaseFee]` over the instance and returned the value. `outcome: "value"` says the field resolved to a concrete value (as opposed to staying empty or erroring). The trailing zero is dropped; the result is the kernel's own numeric rendering.

Now the **empty-operand** case — the same computation, but `BaseFee` is absent. In the A12 kernel an empty numeric operand reads as **0**, so `[BaseFee]` with no base fee does not stay empty or error: it computes `0`. This is a classic trap worth seeing once.

```bash
cat > /tmp/rt-sub-empty.json <<'JSON'
{ "fields": { "Subscription": { "Billing": { } } } }
JSON
dmtool -m cli/src/test/resources/models/subscription-computed.dm.json \
  model compute --doc /tmp/rt-sub-empty.json
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

## Recap

Two read-only runtime verbs, both `--doc <instance.json>` over a nested `{"fields":{…}}` tree:

| verb | question | answer rides | proof shown |
|------|----------|--------------|-------------|
| `model eval` | which rules fire on this data? | `data.fired` (codes) + `data.rule.{name,fired}` with `--rule` | violation fired, compliant did not (same rule, flipped date) |
| `model eval --condition/--field` | would *this* candidate fire? | `data.rule.fired` | one-off `QTY_CAP` fired on `Quantity 150`, not persisted |
| `model compute` | what does a computed field evaluate to? | `data.computed[].{field,value,outcome}` | `[BaseFee]` = `49.9`; empty operand = `0` (empty-as-0) |

Neither verb writes (`written: false`) — they evaluate the *model* against the *instance*, no mutation. The instance carries only the fields a check needs; everything else is absent (and, for numbers, reads as `0`).
