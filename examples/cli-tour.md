# dmtool CLI — read & understand a model

A tour of **reading** one A12 model with **dmtool** (JSON-in / JSON-out over the real kernel): check it (`model check`), read its rules (`rule read`/`format`/`explain`/`check`), read the whole card (`model read`), audit field usage (`model usage`), and read structural facts (`field`/`group`/`config read`). Every command is `dmtool -m <model> <target> <op> [args]`. The tool's *self-describing* surface (manifest/operators/patterns/diagnostics/schema) lives in [cli-discover](cli-discover.md); the **review** verbs (`model report`/`model diff`) in [cli-review](cli-review.md); the cross-model `workspace` verbs in [cli-workspace](cli-workspace.md). Commands run through `dmtool` from the repo root; some use `jq`. Re-check with `uvx showboat@0.6.1 verify examples/cli-tour.md` (exit 0 = output still matches the live CLI).

## The model — `order-ruled`

Rules don't mean anything without the model they guard. This fixture is an **Order**: a customer and product, a quantity and pricing, shipping/billing addresses, a repeating `Items` line-item group, a `Priority` enum, and the two dates a delivery turns on. `model describe` returns the structure (under `data`); read the relevant fields and their **kinds**.

```bash
dmtool -m examples/models/order-ruled.dm.json model describe | jq -c ".data.fields[] | select(.path|test(\"OrderDate|DeliveryDate|/Quantity\$\")) | {path,kind} + (if .scale!=null then {scale:.scale} else {} end)"
```

```output
{"path":"/Order/Quantity","kind":"NUMBER","scale":0}
{"path":"/Order/OrderDate","kind":"DATE"}
{"path":"/Order/DeliveryDate","kind":"DATE"}
```

→ Two `DATE` fields and a scale-0 `NUMBER`. The kinds drive everything that follows: `DifferenceInDays` only applies because both dates are `DATE`, and `Quantity` being a `NUMBER` is exactly why the empty-reads-as-0 trap surfaces in the `rule check` section.

## model check

Runs a model through the **real kernel** consistency check — the same engine that gates persistence. Like every verb it returns the **result envelope**: `outcome`, `ok` (did the op run), `valid` (is the subject model valid), and `diagnostics[]`, with exit 0 valid / 1 invalid.

```bash
dmtool -m examples/models/order-ruled.dm.json model check
```

```output
{
  "target" : "model",
  "op" : "check",
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

Reads a rule stored in the model and renders its condition back as DSL — the kernel's own canonical text — plus the rule's stored error `messages` (locale → text, raw/uninterpolated, for auditing the wording without a runtime-eval verb). The rule is named by a positional path; a read's payload rides the envelope's `data`. A partial parse returns a tolerant `Opaque` passthrough instead.

```bash
dmtool -m examples/models/order-ruled.dm.json rule read /Order/DeliveryNotBeforeOrder
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
    "severity" : "ERROR",
    "errorCode" : "DELIVERY_BEFORE_ORDER",
    "errorField" : "/Order/DeliveryDate",
    "converted" : true,
    "condition" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0",
    "messages" : [ {
      "locale" : "en_US",
      "text" : "The delivery date must not be earlier than the order date."
    } ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.converted: true` confirms dmtool fully modeled every construct (`AllFieldsFilled`, `DifferenceInDays`, `<`, `And`). Note the **polarity**: the condition describes the *violation* (delivery strictly before order), not the requirement — an A12 rule fires when its condition is true.

## rule format

The kernel's **own** canonical text for a rule's condition — a parse + format round-trip through the engine's formatter (distinct from `rule read`, which renders dmtool's AST). Its use is diff-stable normalization: re-express → format → compare. `--lang` re-emits in the other keyword language, the engine being the authority on the EN↔DE switch.

```bash
dmtool -m examples/models/order-ruled.dm.json rule format /Order/DeliveryNotBeforeOrder --lang DE
```

```output
{
  "target" : "rule",
  "op" : "format",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "formatted /Order/DeliveryNotBeforeOrder",
  "data" : {
    "rule" : "/Order/DeliveryNotBeforeOrder",
    "lang" : "de",
    "canonical" : "AlleFelderAngegeben(OrderDate, DeliveryDate)\nUnd DifferenzInTagen(OrderDate, DeliveryDate) < 0"
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ The same condition as `rule read`, but in the engine's canonical form and **translated to German** (`AlleFelderAngegeben`, `Und`, `DifferenzInTagen`) — the *keywords* switch language, the field paths don't. Omit `--lang` for the model's own language. `computation format` is the peer for the model's computations.

## rule explain

Projects a condition in **normalized** form under `data`: a flat **`operators` glossary** (each distinct construct once, with meaning + gotchas) plus a structural **`tree`** whose nodes are `{operator, text, children}` — `operator` backlinks into the glossary. The tree gives the And/Or nesting the flat list throws away.

```bash
dmtool -m examples/models/order-ruled.dm.json rule explain /Order/DeliveryNotBeforeOrder | jq "{operators: .data.operators, tree: .data.tree}"
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

The same payload also carries the two facts an agent needs to **explain the rule to a user without inverting it** — `polarity` and the rule's stored `messages`:

```bash
dmtool -m examples/models/order-ruled.dm.json rule explain /Order/DeliveryNotBeforeOrder \
  | jq '{polarity: .data.polarity, messages: [.data.messages[].text]}'
```

```output
{
  "polarity": {
    "firesOn": "violation",
    "note": "An A12 rule condition is TRUE when the rule is VIOLATED — it models the state to reject, not the requirement. Explain it as the forbidden case; the rule's message states the requirement positively."
  },
  "messages": [
    "The delivery date must not be earlier than the order date."
  ]
}
```

→ Without `polarity`, the condition `DifferenceInDays(OrderDate, DeliveryDate) < 0` reads as "*checks the day difference is negative*" — the **opposite** of the rule's intent. `firesOn: "violation"` says the condition models the **state to reject**, and the modeler's `messages` state the requirement positively ("must not be earlier"). So an agent explains it correctly: *"flags an order whose delivery date is before its order date."* (The message is the same one `rule read` surfaces — one shared source.)

## rule check

Validates a **candidate** rule you haven't persisted yet — kernel verdict *plus* dmtool's lint backstop, in the same envelope. The candidate below compares a number whose presence isn't guarded.

```bash
dmtool -m examples/models/order-ruled.dm.json rule check --field /Order/Quantity --condition "[/Order/Quantity] < 1" --code RK_DEMO
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
    "fix" : "decide by intent: if an empty/missing '/Order/Quantity' should NOT trip the rule, guard it — FieldFilled(/Order/Quantity) And <the comparison>; if a missing number IS a violation (empty-as-0 should fire), the unguarded form is intentional — keep it",
    "explain" : "an unspecified number defaults to 0 in a comparison (KERNEL-SEMANTICS §2) — unlike an empty string/date/enum, which is not evaluated — so this is only a trap when empty should be NEUTRAL; an empty-as-violation rule is legitimately unguarded"
  } ],
  "written" : false
}
```

→ The kernel says `valid: true`, but the lint backstop attached a `WARNING` — `RK_UNGUARDED_NUMBER_COMPARISON`. Because an empty number reads as **0** (KERNEL-SEMANTICS §2), the rule would also fire on an empty `Quantity`; the `fix` field is **two-sided** — guard it if an empty value should be neutral, or keep it unguarded if a missing number is itself a violation. The trap is caught at authoring time, before the rule is ever persisted.

`--suggest-error-field` adds the legal error-field picks to `data` — the condition's referenced fields inside its iteration scope. The error field must be one of these (the `MVK_ERROR_FIELD_NOT_REFERENCED` law), so you choose it up front instead of after a reject.

```bash
dmtool -m examples/models/order-ruled.dm.json rule check --field /Order/Quantity --condition "[/Order/Quantity] < 1" --code RK_DEMO --suggest-error-field | jq .data
```

```output
{
  "field": "/Order/Quantity",
  "candidates": [
    "/Order/Quantity"
  ],
  "fieldIsCandidate": true
}
```

→ `candidates` lists the fields the condition references in scope; `fieldIsCandidate: true` confirms the chosen `--field` is a legal error field. Pick a candidate before the round-trip, not after the kernel rejects.

## model read — the whole model

Where `rule read` renders one rule, `model read` reads the **entire** model card in one shot: every rule and computation rendered to DSL, plus a roll-up `summary`. The payload rides `data`, like every read.

```bash
dmtool -m examples/models/order-ruled.dm.json model read
```

```output
{
  "target" : "model",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read 3 rule(s), 0 computation(s)",
  "data" : {
    "rules" : {
      "/Order/BillingAddress/PostalCodeFormat" : {
        "converted" : true,
        "condition" : "FieldFilled(/Order/BillingAddress/PostalCode) And [/Order/BillingAddress/PostalCode] PatternViolated \"[0-9]{5}\""
      },
      "/Order/DeliveryNotBeforeOrder" : {
        "converted" : true,
        "condition" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0"
      },
      "/Order/EligibilityCheck" : {
        "converted" : true,
        "condition" : "FieldFilled(/Order/CustomerName) And CustomCondition ExternalEligibility"
      }
    },
    "computations" : { },
    "summary" : {
      "rules" : 3,
      "convertedRules" : 3,
      "computations" : 0,
      "convertedComputations" : 0,
      "fullyTyped" : true
    }
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ All three rules at once, each rendered to canonical DSL (the `DeliveryNotBeforeOrder` one is the rule the per-rule sections drilled into). `data.summary` confirms the conversion was total — `convertedRules: 3` of `3`, `fullyTyped: true` — so dmtool modeled every construct with no `Opaque` passthrough. The header `summary` ("read 3 rule(s), 0 computation(s)") is the one-line read; `data` is the full card.

## model usage — the whole-model audit

Where `rule read` / `model read` render conditions, `model usage` answers the **maintainer's** question across the whole model at once: which rules or computations reference each field — and, the headline, which fields **nothing** references. One call replaces a `where-used`-per-field loop. The payload rides `data` (`usage` maps every field to its referrers; `unreferenced` is the subset with none); projected here to the *referenced* fields plus a count of the rest.

```bash
dmtool -m examples/models/order-ruled.dm.json model usage \
  | jq '{ referenced: (.data.usage | to_entries | map(select(.value | length > 0)) | from_entries), unreferencedCount: (.data.unreferenced | length) }'
```

```output
{
  "referenced": {
    "/Order/CustomerName": [
      "/Order/EligibilityCheck"
    ],
    "/Order/BillingAddress/PostalCode": [
      "/Order/BillingAddress/PostalCodeFormat"
    ],
    "/Order/OrderDate": [
      "/Order/DeliveryNotBeforeOrder"
    ],
    "/Order/DeliveryDate": [
      "/Order/DeliveryNotBeforeOrder"
    ]
  },
  "unreferencedCount": 11
}
```

→ Four fields carry rules (each value is the referencing rule's full name — `OrderDate` and `DeliveryDate` both feed the delivery-date rule); the other **11** fields nothing reads. That's the audit a `where-used`-per-field loop used to assemble by hand — useful for spotting a field a rule *should* guard, or dead structure. Built on the same reference primitive as `where-used`, so the two never disagree. (For the whole-model *comprehension* view — this plus a glossed rule catalog — see `model report` in [cli-review](cli-review.md).)

## field / group / config read — the structural facts

Beyond rules, the same `<target> read` pattern reads the model's **structure**. Each returns just the fact under `data` (projected here with `jq` for brevity): `field read` a field's declared kind, `group read` whether a group repeats + its child fields, `config read` the document's rendering config. These are the facts that decide how a condition must be written.

```bash
dmtool -m examples/models/order-ruled.dm.json field read /Order/OrderDate | jq '.data'
```

```output
{
  "field": "/Order/OrderDate",
  "kind": "DATE",
  "required": false,
  "date": {
    "format": "yyyy-MM-dd"
  }
}
```

→ `/Order/OrderDate` reports `kind: DATE` — the same value-kind vocabulary `model describe` and `field add` use (the kernel's internal `DateType` discriminator never leaks). The kind is what lets `DifferenceInDays` take this field as an operand. `required` echoes the field's requiredness in the same vocabulary you set it with (`true`/`false`/`"ifParentPresent"`), and the per-kind `date` config (its `format`) rides the same read — so a constraint is verifiable here, never from the raw model JSON.

```bash
dmtool -m examples/models/order-ruled.dm.json group read /Order/BillingAddress | jq '.data'
```

```output
{
  "group": "/Order/BillingAddress",
  "repeatable": false,
  "fields": [
    "/Order/BillingAddress/PostalCode"
  ]
}
```

→ `repeatable: false` — `BillingAddress` is a single nested group, not a line-item list. Repeatability is load-bearing: a rule scoped under a repeating group iterates per row, and aggregate/iteration operators only apply over a repeatable group.

```bash
dmtool -m examples/models/order-ruled.dm.json config read | jq '.data'
```

```output
{
  "decimalSeparator": ".",
  "timeZone": "Europe/Berlin",
  "conditionLanguage": "en_US",
  "fieldRefByShortNameAllowed": true,
  "supportedCharacters": [],
  "locales": [
    "en_US"
  ]
}
```

→ The document config decides how literals render and resolve: `decimalSeparator: "."` (so `1.5` not `1,5`), `conditionLanguage: "en_US"` (English keywords — `And`, not `Und`), `timeZone` for date arithmetic, and `fieldRefByShortNameAllowed: true` (a condition may name a field by its short name, not just an absolute path). The `locales` are the **declared message locales** — every rule and computation needs a message for *each* (here just `en_US`), so read this before authoring to avoid `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`. These settings are why the rendered DSL throughout this tour looks the way it does.
