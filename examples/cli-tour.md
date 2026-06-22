# dmtool CLI — verb tour

*2026-06-11T20:58:01Z by Showboat 0.6.1*
<!-- showboat-id: 6f8f5996-873b-407f-b7d7-45e47239a2f0 -->

The **dmtool** CLI is **JSON-in / JSON-out** over the A12 kernel. Every command is `dmtool -m <model> <target> <op> [args]`: the model is set once with `-m` (accepted before or after the verb), then a `<target> <op>` selects the operation. Commands run through `dmtool` (the launcher shim) from the repo root; some steps also use `jq`. Output blocks are captured by [showboat](https://github.com/simonw/showboat); re-check them with `uvx showboat@0.6.1 verify examples/cli-tour.md` (exit 0 = output still matches the live CLI).

## Discover the tool

A cold agent learns the CLI **from the CLI** — no external docs needed. `manifest` lists every verb as a `target op` pair (and `dmtool <target> <op> --help` shows its parameters); `operators` browses the DSL vocabulary; `schema <target> <op>` gives that op's directional I/O contract.

```bash
dmtool manifest | jq -r ".verbs[].verb"
```

```output
model new
model info
model validate
model describe
model read
model usage
model rename
model eval
model compute
rule check
rule read
rule explain
rule deps
rule format
rule test
rule add
rule modify
rule remove
rule rename
computation add
computation read
computation explain
computation format
computation modify
computation remove
field add
field modify
field read
field remove
field rename
field move
group add
group modify
group multiselect
group attachment
group read
group remove
group rename
group move
group extract
typedef add
typedef modify
typedef read
typedef remove
typedef rename
typedef extract
typedef inline
typedef import
typedef unimport
include add
include read
include remove
include inline
config read
config modify
workspace list
workspace graph
workspace validate
workspace roles
export
where-used
meta
batch
apply
schema
operators
patterns
diagnostics
manifest
```

→ The surface is **two axes**: a *target* (`model`, `rule`, `computation`, `field`, `group`, `typedef`, `include`, `config`, `workspace`) crossed with an *op* (`add`/`read`/`modify`/`remove`, plus per-target verbs like `rule check` or `model validate`). (`workspace` is the cross-model exception — it scans a *directory*, not the `-m` model.) The manifest carries each verb's `target`/`op`, its params (with the op-record `key`), and a `schema` pointer. The tool describes itself — the skill teaches *judgment* (polarity, the traps), not this catalog.

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

## model validate

Runs a model through the **real kernel** consistency check — the same engine that gates persistence. Like every verb it returns the **result envelope**: `outcome`, `ok` (did the op run), `valid` (is the subject model valid), and `diagnostics[]`, with exit 0 valid / 1 invalid.

```bash
dmtool -m examples/models/order-ruled.dm.json model validate
```

```output
{
  "target" : "model",
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

## operators

The DSL vocabulary is a self-verifying catalog, served by the tool itself — no external operator reference needed. `operators` (no arg) lists every construct, each tagged with a `kind` (`OPERATOR`/`PREDICATE`/`FUNCTION`/`CONSTANT`/`PATH_OP`) and a one-line `meaning`; `--keyword`/`--kind` filter it. The list here is summarized with `jq` (the catalog is large).

```bash
dmtool operators | jq "{verifiedAgainst, count: (.operators|length), kinds: (.operators|map(.kind)|unique)}"
```

```output
{
  "verifiedAgainst": "30.8.1",
  "count": 109,
  "kinds": [
    "CONSTANT",
    "FUNCTION",
    "OPERATOR",
    "PATH_OP",
    "PREDICATE"
  ]
}
```

→ 109 constructs, verified against kernel `30.8.1`, across five **kinds**. That `kind` is the agent's first cut at how a construct composes: an `OPERATOR` (`And`, `<`) joins operands, a `PREDICATE` (`FieldFilled`) tests a field, a `FUNCTION` (`DifferenceInDays`) returns a value, a `CONSTANT` is a literal, a `PATH_OP` walks the model tree. The catalog is the inventory; the single-operator view below is the detail.

Pass an `operatorId` for one construct in **full** — its bilingual keyword, signature (`operands` → `returns`), `constraints`, `gotchas`, and a `validExample`. This is the page an agent reads before reaching for an operator it hasn't used.

```bash
dmtool operators DifferenceInDays | jq '{id,kind,meaning}'
```

```output
{
  "id": "DifferenceInDays",
  "kind": "FUNCTION",
  "meaning": "The number of whole days from the first date to the second."
}
```

→ The same `DifferenceInDays` the `rule explain` glossary surfaced — here looked up **directly**, by id, without a rule that uses it. The full record (omitted by the `jq` projection above) also carries `operands`/`returns`, `constraints`, `gotchas`, and a runnable `validExample`, so the agent can compose the operator correctly from the catalog alone.

## patterns — scaffold a correct rule from an idiom

Where `operators` is the *vocabulary*, `patterns` is the *idiom* catalogue — the recurring BA tasks, each a typed-DSL-backed template that's correct by construction, across three `kind`s: **rule** idioms (date-order, mutually-exclusive, …) bake in the two hardest rule traps — the **violation polarity** and a **referenced error field**; the **computation** idiom `tiered-amount` bakes in a **mutually-exclusive, exhaustive precondition table**; the **field** idioms (bounded-number, formatted-string, value-set-enum) scaffold the **field-level alternative** to a rule (the constraint R2's `seeAlso` bridge names). `patterns` lists them, with each idiom's `kind`; the summary here is projected with `jq`.

```bash
dmtool patterns | jq -c '{count, ids: (.patterns|map(.id))}'
```

```output
{"count":9,"ids":["date-order","mutually-exclusive","at-least-one-of","required-when","sum-of-line-items","tiered-amount","bounded-number","formatted-string","value-set-enum"]}
```

→ Nine idioms. Pass an id with `--arg name=value` parameters (and `-m <model>`) to **scaffold** the artifact from one — a rule-spec, a computation-spec (`tiered-amount`), or a field-spec (the field idioms) — built through the typed DSL (correct by construction) and **auto-checked** against the kernel.

```bash
dmtool -m examples/models/order-ruled.dm.json patterns date-order --arg earlier=/Order/OrderDate --arg later=/Order/DeliveryDate | jq '{pattern, spec: {field: .spec.field, condition: .spec.condition}, valid}'
```

```output
{
  "pattern": "date-order",
  "spec": {
    "field": "/Order/DeliveryDate",
    "condition": "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0"
  },
  "valid": true
}
```

→ The idiom picked the **error field** (`/Order/DeliveryDate` — referenced, in scope) and the **polarity** (fires when delivery is *before* order), and `valid: true` confirms the kernel accepts it. The agent reviews the `spec` then `rule add`s it — or adds `--apply` to persist it in one step.

## diagnostics — the code catalogue

The twin of `operators`, for **diagnostic codes**: when a verb refuses or rejects, its `diagnostics[].code` (e.g. `RK_NO_SUCH_FIELD`) is explorable — so an agent that gets a code back can look up what it means and how to fix it, rather than guess. `diagnostics` (no arg) lists every `RK_*` code (filter with `--severity`/`--source`); the summary here is projected with `jq`.

```bash
dmtool diagnostics | jq -c '{count, severities: (.diagnostics|map(.severity)|unique), sources: (.diagnostics|map(.source)|unique)}'
```

```output
{"count":40,"severities":["ERROR","INFO","WARNING"],"sources":["ENVIRONMENT","INTERNAL","KERNEL","LINT","PRECHECK"]}
```

Pass a code for its full entry — meaning + the canonical fix:

```bash
dmtool diagnostics RK_NO_SUCH_FIELD
```

```output
{
  "code" : "RK_NO_SUCH_FIELD",
  "severity" : "ERROR",
  "source" : "PRECHECK",
  "meaning" : "no field exists at the given path.",
  "fix" : "pass an existing field's full name-path (see `model describe` or `field read`)."
}
```

→ The catalogue is the single source the diagnostics themselves draw from, so a `code` carried in any envelope resolves here. (`MVK_*` codes are the kernel's — their meaning lives in the operator catalogue, via `operators`/`rule explain`.)

## schema result — the output envelope

Every verb in this tour returned the **same envelope shape**. `schema result` emits its JSON Schema, so an agent learns that contract once and reads every command's output the same way. Projected here to the property names and their meanings.

```bash
dmtool schema result | jq "{required, properties: (.properties | map_values(.description))}"
```

```output
{
  "required": [
    "target",
    "op",
    "outcome",
    "ok",
    "summary",
    "diagnostics",
    "written"
  ],
  "properties": {
    "target": "the element family acted on: model | rule | computation | field | group | typedef | include | config | workspace",
    "op": "the operation: add | read | modify | remove | validate | check | explain | describe | export | eval | compute | …",
    "outcome": "the execution result class; `error` = the tool itself failed (an unexpected throwable caught at the boundary, exit 70), distinct from `rejected` (input rejected, exit 1)",
    "ok": "the operation executed as asked (outcome in applied | preview | read | staged); false for refused | rejected | error",
    "valid": "validate/check (and after a mutating op): the subject model is kernel-valid — distinct from `ok`",
    "summary": "one human-readable line (the agent's quick read / log line)",
    "changed": "(mutations) the delta on success — e.g. {added, kind}, a refactor's rewritten references",
    "data": "(reads/queries) the op's payload — explanation tree, model card, fired-list, …; shape is op-specific (see `schema <target> <op>`)",
    "diagnostics": "structured findings — see `schema diagnostic`",
    "written": "whether the model was written to disk",
    "output": "the path the model was written to; absent for read/preview/refused/rejected"
  }
}
```

→ Seven keys are always present (`target`, `op`, `outcome`, `ok`, `summary`, `diagnostics`, `written`); the rest are conditional. Note the recurring **`ok`/`valid` split** seen throughout this tour — `ok` says the op ran, `valid` is the verdict on the model — and that **reads** put their payload under **`data`** (whose shape is op-specific: `schema <target> <op>` gives it per verb). This is why one output reader suffices for the whole CLI.

## schema rule add — a directional contract

`schema <target> <op>` gives one verb's **directional** I/O contract: what it consumes and what it returns. For a mutating verb like `rule add` the input is a spec; the output is — universally — the `result` envelope above.

```bash
dmtool schema rule add | jq '{op, returns, inputKeys: (.input.properties|keys)}'
```

```output
{
  "op": "rule add",
  "returns": "result",
  "inputKeys": [
    "allowDifferingDecimals",
    "alternatives",
    "code",
    "comment",
    "commonPrecondition",
    "computedField",
    "condition",
    "field",
    "group",
    "messages",
    "name",
    "severity"
  ]
}
```

→ `returns: "result"` — the same envelope `schema result` describes, so the agent already knows how to read it. The `inputKeys` are the **union** of two specs (`rule add` accepts a rule-spec *or* a computation-spec, chosen by which keys are present): `field`/`condition`/`code` author a rule, `computedField`/`alternatives` a computation. The full schema (omitted by this projection) spells out each key, the `oneOf`, and the required sets — enough to construct a valid `add` payload without trial and error.

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

→ Four fields carry rules (each value is the referencing rule's full name — `OrderDate` and `DeliveryDate` both feed the delivery-date rule); the other **11** fields nothing reads. That's the audit a `where-used`-per-field loop used to assemble by hand — useful for spotting a field a rule *should* guard, or dead structure. Built on the same reference primitive as `where-used`, so the two never disagree.

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

## model info — one model's header dashboard

`model info` is the model's identity card in a single read: `id`/`modelType`/`modelVersion`, the super/subtype graph (the `abstract`/`superTypes`/`subTypes` convention), and every outbound reference — `include`s and type-def imports — **resolved to its file** (via `-w/--workspace`, default the model's own folder). It carries *counts*, never contents — the field/rule/config detail stays in `model describe`/`model read`/`config read`, so it adds no redundancy. Projected here with `jq`.

```bash
dmtool -m examples/models/multifile/app/storefront.dm.json model info -w examples/models/multifile | jq -c '.data | {id, includes, counts}'
```

```output
{"id":"storefront","includes":[{"alias":"catalog","ref":"catalog","resolvedPath":"lib/catalog.dm.json"}],"counts":{"groups":2,"fields":1,"rules":0,"computations":0,"typeDefinitions":0}}
```

→ `storefront` resolves its `catalog` include to `lib/catalog.dm.json` (the file to hand `-w/--workspace`), and the counts orient you — 2 groups, 1 field, no rules/computations — without dumping their contents. This is the single-model peer of `workspace list` below, which does the same across a whole folder.

## workspace list — what's in a folder of models

Everything above operated on one `-m` model. When you're handed a **directory** of models instead, `workspace list` is the cross-model "ls" that goes *into* them: a per-model index where every `include` / type-def import is **cross-resolved to its file within the scan**. So an agent learns which file provides a referenced model — instead of guessing an `-w/--workspace`. Kernel-free (a half-wired workspace still lists); `--recursive` widens the resolution scope, `--validate` adds a per-model validity flag, `--format table` renders the same facts for humans. Projected here with `jq`.

```bash
dmtool workspace list examples/models/multifile --recursive | jq -c '.data.models[] | {id, path, includes: [.includes[] | {ref, resolvedPath}]}'
```

```output
{"id":"storefront","path":"app/storefront.dm.json","includes":[{"ref":"catalog","resolvedPath":"lib/catalog.dm.json"}]}
{"id":"catalog","path":"lib/catalog.dm.json","includes":[]}
```

→ `storefront` declares an `include` of the model id `catalog`, and the scan **resolves it to `lib/catalog.dm.json`** — the file an agent must put on the `-w/--workspace` to load `storefront`. Were the target outside the scan, `resolvedPath` would be `null` (widen with `--recursive`, as here). The same resolution covers type-def imports and surfaces the sub/supertype convention (`abstract`/`superTypes`/`subTypes`), so one read maps a whole workspace.

## workspace graph — the inheritance hierarchy

Where `workspace list` is the flat per-model index, `workspace graph` draws the **sub/supertype hierarchy** a flat list can't show at a glance — subtype→supertype edges from the `superTypes`/`subTypes` convention. It is **inheritance only** (the composition relations — includes/imports — stay in `list`/`model info`, where they're already resolved). `--format tree` renders it for humans:

```bash
dmtool workspace graph examples/models/inheritance --format tree
```

```output
Product_Base (abstract)
  - ProductBundle
  - ProductSingle
```

→ `Product_Base` is the abstract root; its two subtypes nest under it. The default `--format json` returns the same hierarchy as `{nodes, edges}` (each edge `resolved` iff both ends are in the scan — a dangling supertype shows `resolved:false`), and `--format dot` emits a Graphviz digraph.

## workspace roles — does every model gate to a defined role?

A12 models carry a `roles` header annotation (a comma-separated list) naming who may access them; a workspace declares those roles in `auth/roles.yaml` and its users (with the roles each holds) in `auth/users.yaml`. `workspace roles` is the **access-control lint** that joins all three: it resolves every model's gating roles — and every user's authorities — against the roles file and reports the cross-file inconsistencies the kernel never sees. It discovers both files under the workspace root (`auth/roles.yaml` / `auth/users.yaml`, also the project-template `import/auth/…` and the Preview-App conventions), scans the models recursively, and surfaces the defined roles, the users, each model's gating roles, and the findings:

```bash
dmtool workspace roles examples/models/storefront-workspace \
  | jq -c '{rolesFile: .data.rolesFile, usersFile: .data.usersFile, definedRoles: [.data.definedRoles[].name],
            users: [.data.users[] | {username, authorities}], models: [.data.models[] | {id, roles}], findings: .data.findings}'
```

```output
{"rolesFile":"auth/roles.yaml","usersFile":"auth/users.yaml","definedRoles":["shopper","merchant"],"users":[{"username":"alice","authorities":["shopper"]},{"username":"bob","authorities":["merchant"]}],"models":[{"id":"Catalog_DM","roles":["merchant"]},{"id":"Storefront_DM","roles":["shopper","merchant"]}],"findings":[]}
```

→ Both models gate to roles `auth/roles.yaml` defines (`shopper`/`merchant`), so the lint is clean. The headline finding is an **undefined role** — a model gating to a role the file doesn't declare, which even the A12 model editor permits silently. Resolving the same models against a roles file that omits `merchant` surfaces it. The lint is **purely advisory — it warns, it never blocks** (access-control config is often a dev seed or owned by an external IdP, so it must not stop work on the models); the verb **always exits 0**:

```bash
dmtool workspace roles examples/models/storefront-workspace \
  --roles examples/models/shopper-only-roles.yaml 2>&1 \
  | jq -c '{warnings: .data.warnings, findings: [.data.findings[] | {code, modelId, username, role}]}'; echo "(exit ${PIPESTATUS[0]})"
```

```output
{"warnings":3,"findings":[{"code":"UNDEFINED_ROLE","modelId":"Catalog_DM","username":null,"role":"merchant"},{"code":"UNDEFINED_ROLE","modelId":"Storefront_DM","username":null,"role":"merchant"},{"code":"UNDEFINED_AUTHORITY","modelId":null,"username":"bob","role":"merchant"}]}
(exit 0)
```

→ The omission ripples across the whole **RBAC triangle** (models gate to roles ← `roles.yaml` → users hold authorities). `merchant` is referenced in three places this roles file no longer declares — the two models that **gate** to it (`UNDEFINED_ROLE`) and the user **bob** who **holds** it (`UNDEFINED_AUTHORITY`, the membership-edge twin) — so each draws a **warning** (never an error; exit stays 0). The lint surfaces the rest of the triangle too: a model left ungated when a roles file exists (`MISSING_ROLE_ASSIGNMENT`), roles declared with no roles file (`NO_ROLES_FILE`), more than one roles file (`MULTIPLE_ROLES_FILES`), a model gated to roles **no user holds** (`UNREACHABLE_MODEL` — nobody can open it), and, as an `INFO`, a declared role held by no user and gating no model (`ORPHAN_ROLE` — dead). `--roles` / `--users` override discovery.

Every finding carries a **`fix`** alongside its `message` — the concrete remedy, not just the diagnosis (the same `{message, fix}` shape the `RK_*` diagnostic catalog uses). So an agent reads what to *do*, not only what's wrong:

```bash
dmtool workspace roles examples/models/storefront-workspace \
  --roles examples/models/shopper-only-roles.yaml | jq -r '.data.findings[].fix'
```

```output
declare 'merchant' in the workspace roles file, or remove it from Catalog_DM's roles annotation
declare 'merchant' in the workspace roles file, or remove it from Storefront_DM's roles annotation
declare 'merchant' in the workspace roles file, or remove it from bob's authorities
```

→ Each finding names its remedy — declare the role, or drop the reference — so the next action is unambiguous. (A diagnostic `code` you get back from any verb is explorable too: `data.findings[]` codes via that verb's `schema <target> <op>`, `RK_*` codes via `dmtool diagnostics <code>`, `MVK_*` kernel codes via `dmtool operators` / `rule explain`.)
