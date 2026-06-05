# rulekit CLI — verb tour

*2026-06-05T13:33:12Z by Showboat 0.6.1*
<!-- showboat-id: 3e29da35-656b-4983-82b1-bb00e19cc4d8 -->

The rulekit CLI is **JSON-in / JSON-out** over the A12 kernel. Every command runs through `bin/rk` (the launcher shim) from the repo root; the field breakdown also uses `jq`. Output blocks below are captured by [showboat](https://github.com/simonw/showboat); re-check them any time with `uvx showboat@0.6.1 verify examples/cli-tour.md` (exit 0 = output still matches the live CLI).

## The model — `order-ruled`

Rules don't mean anything without the model they guard. This fixture is an **Order**: a customer and product, a quantity and pricing, shipping/billing addresses, a repeating `Items` line-item group, a `Priority` enum, and the two dates a delivery turns on. The rules in this tour guard those dates and the quantity — so start by reading the relevant fields and their **kinds**.

```bash
bin/rk describe cli/src/test/resources/models/order-ruled.dm.json | jq -c '.fields[] | select(.path|test("OrderDate|DeliveryDate|/Quantity$")) | {path,kind} + (if .scale!=null then {scale:.scale} else {} end)'
```

```output
{"path":"/Order/Quantity","kind":"NUMBER","scale":0}
{"path":"/Order/OrderDate","kind":"DATE"}
{"path":"/Order/DeliveryDate","kind":"DATE"}
```

→ Two `DATE` fields and a scale-0 `NUMBER`. The kinds drive everything that follows: `DifferenceInDays` only applies because both dates are `DATE`, and `Quantity` being a `NUMBER` is exactly why the empty-reads-as-0 trap surfaces in the `check` section.

## validate

Runs a model through the **real kernel** consistency check — the same engine that gates persistence — and returns `{valid, diagnostics[]}` with exit 0/1. The ground-truth oracle for whether a model and its rules would actually be accepted.

```bash
bin/rk validate cli/src/test/resources/models/order-ruled.dm.json
```

```output
{
  "valid" : true,
  "diagnostics" : [ ]
}
```

→ `valid: true` with an empty `diagnostics` array means the kernel accepts the model as-is: every rule's condition parsed, typed, and passed consistency. A failing model would list diagnostics here (each with a `code`, `severity`, and message) and exit `1`.

## ingest

Reads a rule already stored in the model and renders its condition back as DSL — the kernel's own canonical text. A partial parse would return a tolerant `Opaque` passthrough instead. Use it to inspect a foreign rule before editing it.

```bash
bin/rk ingest cli/src/test/resources/models/order-ruled.dm.json --rule /Order/DeliveryNotBeforeOrder
```

```output
{
  "rule" : "/Order/DeliveryNotBeforeOrder",
  "converted" : true,
  "condition" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0"
}
```

→ `converted: true` confirms rulekit fully modeled every construct (`AllFieldsFilled`, `DifferenceInDays`, `<`, `And`). Note the **polarity**: the condition describes the *violation* (delivery strictly before order), not the requirement — an A12 rule fires when its condition is true.

## explain

Breaks a condition into its operators, predicates, and functions — each with a plain-language **meaning** and the **gotchas** that bite authors. It also lists referenced fields and whether the rule iterates.

```bash
bin/rk explain cli/src/test/resources/models/order-ruled.dm.json --rule /Order/DeliveryNotBeforeOrder
```

```output
{
  "text" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0",
  "operators" : [ {
    "id" : "And",
    "keyword" : "And",
    "kind" : "OPERATOR",
    "meaning" : "True iff both boolean operands are true.",
    "gotchas" : [ "there is no 'Not' operator" ]
  }, {
    "id" : "AllFieldsFilled",
    "keyword" : "AllFieldsFilled",
    "kind" : "PREDICATE",
    "meaning" : "True iff every listed field has a value.",
    "gotchas" : [ "a field carrying a formal error is removed from evaluation (see FieldFilled)" ]
  }, {
    "id" : "LessThan",
    "keyword" : "<",
    "kind" : "OPERATOR",
    "meaning" : "True iff the left operand is strictly less than the right."
  }, {
    "id" : "DifferenceInDays",
    "keyword" : "DifferenceInDays",
    "kind" : "FUNCTION",
    "meaning" : "The number of whole days from the first date to the second.",
    "gotchas" : [ "differences are a floor, not a calendar count (DifferenceInMonths(31.01,30.03)=1); fractional offsets truncate" ]
  } ],
  "references" : [ {
    "path" : "/Order/DeliveryDate",
    "kind" : "DATE"
  }, {
    "path" : "/Order/OrderDate",
    "kind" : "DATE"
  } ],
  "iterates" : false,
  "gloss" : "Uses And, AllFieldsFilled, <, DifferenceInDays over /Order/DeliveryDate, /Order/OrderDate."
}
```

→ Each construct carries its meaning plus traps: `DifferenceInDays` *floors* rather than counting calendar days, and the catalog reminds you there is no `Not` operator. `iterates: false` marks this a whole-order rule; `references` is exactly the two date fields it reads.

## check

Validates a **candidate** rule you haven't persisted yet — kernel verdict *plus* rulekit's lint backstop. The candidate below compares a number whose presence isn't guarded.

```bash
bin/rk check cli/src/test/resources/models/order-ruled.dm.json --field /Order/Quantity --condition "[/Order/Quantity] < 1" --code RK_DEMO
```

```output
{
  "valid" : true,
  "diagnostics" : [ {
    "severity" : "WARNING",
    "source" : "LINT",
    "code" : "RK_UNGUARDED_NUMBER_COMPARISON",
    "summary" : "'/Order/Quantity' is a number compared with an ordering operator but its presence isn't guarded — an empty number reads as 0, so the rule also fires on an empty '/Order/Quantity'",
    "where" : {
      "rule" : "",
      "operator" : "LessThan"
    },
    "fix" : "FieldFilled(/Order/Quantity) And <the comparison>",
    "explain" : "an unspecified number defaults to 0 in a comparison (KERNEL-SEMANTICS §2) — unlike an empty string/date/enum, which is not evaluated — so guard the number with FieldFilled if an empty value should not trip the rule"
  } ]
}
```

→ The kernel says `valid: true`, but the lint backstop attached a `WARNING` — `RK_UNGUARDED_NUMBER_COMPARISON`. Because an empty number reads as **0** (KERNEL-SEMANTICS §2), the rule would also fire on an empty `Quantity`; the `fix` field spells out the guard. This catches the trap at authoring time, before the rule is ever persisted.
