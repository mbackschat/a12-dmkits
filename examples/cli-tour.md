# dmtool CLI — verb tour

*2026-06-11T20:58:01Z by Showboat 0.6.1*
<!-- showboat-id: 6f8f5996-873b-407f-b7d7-45e47239a2f0 -->

The **dmtool** CLI is **JSON-in / JSON-out** over the A12 kernel. Every command is `dmtool -m <model> <target> <op> [args]`: the model is set once with `-m` (accepted before or after the verb), then a `<target> <op>` selects the operation. Commands run through `bin/dmtool` (the launcher shim) from the repo root; some steps also use `jq`. Output blocks are captured by [showboat](https://github.com/simonw/showboat); re-check them with `uvx showboat@0.6.1 verify examples/cli-tour.md` (exit 0 = output still matches the live CLI).

## Discover the tool

A cold agent learns the CLI **from the CLI** — no external docs needed. `manifest` lists every verb as a `target op` pair (and `bin/dmtool <target> <op> --help` shows its parameters); `operators` browses the DSL vocabulary; `schema <target> <op>` gives that op's directional I/O contract.

```bash
bin/dmtool manifest | jq -r ".verbs[].verb"
```

```output
doc validate
doc describe
doc read
doc eval
doc compute
rule check
rule read
rule explain
rule add
rule modify
rule remove
computation add
computation read
computation explain
computation modify
computation remove
field add
field read
field remove
group add
group read
group remove
typedef add
typedef read
typedef remove
include add
include read
include remove
config read
config modify
export
where-used
batch
schema
operators
manifest
```

→ The surface is **two axes**: a *target* (`doc`, `rule`, `computation`, `field`, `group`, `typedef`, `include`, `config`) crossed with an *op* (`add`/`read`/`modify`/`remove`, plus per-target verbs like `rule check` or `doc validate`). The manifest carries each verb's `target`/`op`, its params (with the op-record `key`), and a `schema` pointer. The tool describes itself — the skill teaches *judgment* (polarity, the traps), not this catalog.

## The model — `order-ruled`

Rules don't mean anything without the model they guard. This fixture is an **Order**: a customer and product, a quantity and pricing, shipping/billing addresses, a repeating `Items` line-item group, a `Priority` enum, and the two dates a delivery turns on. `doc describe` returns the structure (under `data`); read the relevant fields and their **kinds**.

```bash
bin/dmtool -m cli/src/test/resources/models/order-ruled.dm.json doc describe | jq -c ".data.fields[] | select(.path|test(\"OrderDate|DeliveryDate|/Quantity\$\")) | {path,kind} + (if .scale!=null then {scale:.scale} else {} end)"
```

```output
{"path":"/Order/Quantity","kind":"NUMBER","scale":0}
{"path":"/Order/OrderDate","kind":"DATE"}
{"path":"/Order/DeliveryDate","kind":"DATE"}
```

→ Two `DATE` fields and a scale-0 `NUMBER`. The kinds drive everything that follows: `DifferenceInDays` only applies because both dates are `DATE`, and `Quantity` being a `NUMBER` is exactly why the empty-reads-as-0 trap surfaces in the `rule check` section.

## doc validate

Runs a model through the **real kernel** consistency check — the same engine that gates persistence. Like every verb it returns the **result envelope**: `outcome`, `ok` (did the op run), `valid` (is the subject model valid), and `diagnostics[]`, with exit 0 valid / 1 invalid.

```bash
bin/dmtool -m cli/src/test/resources/models/order-ruled.dm.json doc validate
```

```output
{
  "target" : "doc",
  "op" : "validate",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "model is valid",
  "diagnostics" : [ ],
  "written" : false
}
```

→ `valid: true` with an empty `diagnostics` array means the kernel accepts the model as-is. Note the **ok/valid split**: `ok` says the validate op ran; `valid` is its verdict about the model. A failing model would list diagnostics (each with `code`, `severity`, message) and exit `1`.

## rule read

Reads a rule stored in the model and renders its condition back as DSL — the kernel's own canonical text. The rule is named by a positional path; a read's payload rides the envelope's `data`. A partial parse returns a tolerant `Opaque` passthrough instead.

```bash
bin/dmtool -m cli/src/test/resources/models/order-ruled.dm.json rule read /Order/DeliveryNotBeforeOrder
```

```output
{
  "target" : "rule",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read /Order/DeliveryNotBeforeOrder",
  "data" : {
    "rule" : "/Order/DeliveryNotBeforeOrder",
    "converted" : true,
    "condition" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0"
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.converted: true` confirms dmtool fully modeled every construct (`AllFieldsFilled`, `DifferenceInDays`, `<`, `And`). Note the **polarity**: the condition describes the *violation* (delivery strictly before order), not the requirement — an A12 rule fires when its condition is true.

## rule explain

Projects a condition in **normalized** form under `data`: a flat **`operators` glossary** (each distinct construct once, with meaning + gotchas) plus a structural **`tree`** whose nodes are `{operator, text, children}` — `operator` backlinks into the glossary. The tree gives the And/Or nesting the flat list throws away.

```bash
bin/dmtool -m cli/src/test/resources/models/order-ruled.dm.json rule explain /Order/DeliveryNotBeforeOrder | jq "{operators: .data.operators, tree: .data.tree}"
```

```output
{
  "operators": [
    {
      "id": "And",
      "keyword": "And",
      "kind": "OPERATOR",
      "meaning": "True iff both boolean operands are true.",
      "gotchas": [
        "there is no 'Not' operator"
      ]
    },
    {
      "id": "AllFieldsFilled",
      "keyword": "AllFieldsFilled",
      "kind": "PREDICATE",
      "meaning": "True iff every listed field has a value.",
      "gotchas": [
        "a field carrying a formal error is removed from evaluation (see FieldFilled)"
      ]
    },
    {
      "id": "LessThan",
      "keyword": "<",
      "kind": "OPERATOR",
      "meaning": "True iff the left operand is strictly less than the right."
    },
    {
      "id": "DifferenceInDays",
      "keyword": "DifferenceInDays",
      "kind": "FUNCTION",
      "meaning": "The number of whole days from the first date to the second.",
      "gotchas": [
        "differences are a floor, not a calendar count (DifferenceInMonths(31.01,30.03)=1); fractional offsets truncate"
      ]
    }
  ],
  "tree": {
    "operator": "And",
    "text": "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0",
    "children": [
      {
        "operator": "AllFieldsFilled",
        "text": "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate)"
      },
      {
        "operator": "LessThan",
        "text": "DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0",
        "children": [
          {
            "operator": "DifferenceInDays",
            "text": "DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate)"
          }
        ]
      }
    ]
  }
}
```

→ The shape is explicit: an `And` of a guard (`AllFieldsFilled`) and a comparison, with `DifferenceInDays` nested *inside* the `<`. Each node's `operator` keys into the glossary for its meaning/gotchas — stated **once**, even if it recurs. A flat list can't tell you the dates are **compared**, not merely referenced; the tree can.

## rule check

Validates a **candidate** rule you haven't persisted yet — kernel verdict *plus* dmtool's lint backstop, in the same envelope. The candidate below compares a number whose presence isn't guarded.

```bash
bin/dmtool -m cli/src/test/resources/models/order-ruled.dm.json rule check --field /Order/Quantity --condition "[/Order/Quantity] < 1" --code RK_DEMO
```

```output
{
  "target" : "rule",
  "op" : "check",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "candidate is kernel-valid",
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
  } ],
  "written" : false
}
```

→ The kernel says `valid: true`, but the lint backstop attached a `WARNING` — `RK_UNGUARDED_NUMBER_COMPARISON`. Because an empty number reads as **0** (KERNEL-SEMANTICS §2), the rule would also fire on an empty `Quantity`; the `fix` field spells out the guard. The trap is caught at authoring time, before the rule is ever persisted.
