# dmtool CLI — discover the tool

A cold agent learns the **dmtool** CLI **from the CLI** — no external docs. This tour is the self-describing surface: `manifest` (every verb), `operators`/`patterns` (the DSL vocabulary + idioms), `diagnostics` (the error codes), and `schema` (the I/O contracts). Commands run through `dmtool` from the repo root; some use `jq`. Re-check with `uvx showboat@0.6.1 verify examples/cli-discover.md` (exit 0 = output still matches the live CLI).

## Discover the tool

`manifest` lists every verb as a `target op` pair (and `dmtool <target> <op> --help` shows its parameters); `operators` browses the DSL vocabulary; `schema <target> <op>` gives that op's directional I/O contract.

```bash
dmtool manifest | jq -r ".verbs[].verb"
```

```output
model new
model info
model check
model describe
model diff
model read
model usage
model report
model rename
model normalize
model expand
model eval
model compute
model seed
model import-jsonschema
model export-jsonschema
profile extract
profile synthesize
profile compare
rule check
rule read
rule explain
rule deps
rule format
rule eval
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
include set-reference
include rename
include remove
include inline
config read
config modify
workspace list
workspace graph
workspace check
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

→ The surface is **two axes**: a *target* (`model`, `profile`, `rule`, `computation`, `field`, `group`, `typedef`, `include`, `config`, `workspace`) crossed with an *op* (`add`/`read`/`modify`/`remove`, plus per-target verbs like `profile extract`/`synthesize`/`compare`, `rule check`, or `model check`). (`workspace` is the cross-model exception — it scans a *directory*, not the `-m` model.) The manifest carries each verb's `target`/`op`, its params (with the op-record `key`), and a schema pointer for every directional contract, including the explicitly described `profile synthesize` artifact operation. The tool describes itself — the skill teaches *judgment* (polarity, the traps), not this catalog.

## profile extract, synthesize, and compare

`profile extract` turns a model into the domain-blind workload shape used for performance calibration. The ordinary form is a read: the profile rides `data`. This projection keeps the demonstration compact while proving the current artifact's four top-level sections.

```bash
dmtool -m examples/models/order-ruled.dm.json profile extract \
  | jq '{outcome, sections: (.data|keys)}'
```

```output
{
  "outcome": "read",
  "sections": [
    "computations",
    "rules",
    "structure",
    "wiring"
  ]
}
```

→ Use `-o target.profile.json` when the profile is a reusable artifact: the file is the bare profile and stdout becomes an `applied` write receipt. `schema profile extract` describes the current profile shape; there is no profile-version selector.

`profile synthesize` consumes that current artifact and emits an own-domain DM-JSON model. Its seed makes the result reproducible; it is an artifact producer rather than a result-envelope read.

```bash
dmtool profile synthesize \
  --input examples/profiles/compact.json \
  --seed 42 \
  | jq '{hasId: ((.header.id // "") | length > 0), hasGroup: ([.. | objects | select(.type? == "Group")] | length > 0)}'
```

```output
{
  "hasId": true,
  "hasGroup": true
}
```

→ Add `-o calibration.dm.json` to write the synthesized model and receive an `applied` receipt on stdout. The input can also be a complete `profile extract` envelope; `schema profile synthesize` describes the current profile input, the DM-JSON artifact, and that receipt branch. No compatibility or legacy-version path exists.

`profile compare` classifies every fidelity axis after synthesis and re-extraction. A match is a normal successful read; a mismatch keeps every failed axis visible in `data`, sets `valid:false`, and exits 1.

```bash
dmtool profile compare \
  --target examples/profiles/compact.json \
  --actual examples/profiles/compact.json \
  | jq '{outcome, valid, matches: .data.matches}'
```

```output
{
  "outcome": "read",
  "valid": true,
  "matches": true
}
```

→ Both inputs may be bare artifacts or `profile extract` envelopes. `schema profile compare` describes the comparison payload; there is still one current profile format and no compatibility mode.

## operators

The DSL vocabulary is a self-verifying catalog, served by the tool itself — no external operator reference needed. `operators` (no arg) lists every construct, each tagged with a `kind` (`OPERATOR`/`PREDICATE`/`FUNCTION`/`CONSTANT`/`PATH_OP`) and a one-line `meaning`; `--keyword`/`--kind` filter it. The list here is summarized with `jq` (the catalog is large).

```bash
dmtool operators | jq "{verifiedAgainst, count: (.operators|length), kinds: (.operators|map(.kind)|unique)}"
```

```output
{
  "verifiedAgainst": "30.8.1",
  "count": 110,
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
  "meaning": "The signed number of complete model-zone legacy-calendar day steps from the first date/date-time to the second."
}
```

→ Looked up **directly**, by id, without a rule that uses it. The full record (omitted by the `jq` projection above) also carries `operands`/`returns`, `constraints`, `gotchas`, and a runnable `validExample`, so the agent can compose the operator correctly from the catalog alone.

## patterns — scaffold a correct rule from an idiom

Where `operators` is the *vocabulary*, `patterns` is the *idiom* catalogue — the recurring BA tasks, each a typed-DSL-backed template that's correct by construction, across three `kind`s: **rule** idioms (date-order, mutually-exclusive, …) bake in the two hardest rule traps — the **violation polarity** and a **referenced error field**; the **computation** idiom `tiered-amount` bakes in a **mutually-exclusive, exhaustive precondition table**; the **field** idioms (bounded-number, formatted-string, value-set-enum) scaffold the **field-level alternative** to a rule. `patterns` lists them, with each idiom's `kind`; the summary here is projected with `jq`.

```bash
dmtool patterns | jq '{count, ids: (.patterns|map(.id))}'
```

```output
{
  "count": 9,
  "ids": [
    "date-order",
    "mutually-exclusive",
    "at-least-one-of",
    "required-when",
    "sum-of-line-items",
    "tiered-amount",
    "bounded-number",
    "formatted-string",
    "value-set-enum"
  ]
}
```

→ Nine idioms. Pass an id with `--arg name=value` parameters (and `-m <model>`) to **scaffold** the artifact from one — a rule-spec, a computation-spec (`tiered-amount`), or a field-spec (the field idioms) — built through the typed DSL (correct by construction) and **auto-checked** against the kernel.

```bash
dmtool -m examples/models/order-ruled.dm.json \
  patterns date-order --arg earlier=/Order/OrderDate --arg later=/Order/DeliveryDate \
  | jq '{pattern, spec: {field: .spec.field, condition: .spec.condition}, valid}'
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
dmtool diagnostics | jq '{count, severities: (.diagnostics|map(.severity)|unique), sources: (.diagnostics|map(.source)|unique)}'
```

```output
{
  "count": 45,
  "severities": [
    "ERROR",
    "INFO",
    "WARNING"
  ],
  "sources": [
    "ENVIRONMENT",
    "INTERNAL",
    "KERNEL",
    "LINT",
    "PRECHECK"
  ]
}
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

Every verb returns the **same envelope shape**. `schema result` emits its JSON Schema, so an agent learns that contract once and reads every command's output the same way. Projected here to the property names and their meanings.

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
    "verification": "(check/validate verdicts) how the verdict was reached: KERNEL_CONFIRMED = the kernel backend arbitrated (the CLI always bundles it); STRUCTURAL_ONLY = no backend, so `valid` reflects structural prechecks only and must NOT be read as kernel-green (a library-consumer posture; the CLI surfaces it for visibility)",
    "summary": "one human-readable line (the agent's quick read / log line)",
    "changed": "(mutations) the delta on success — e.g. {added, kind}, a refactor's rewritten references",
    "data": "(reads/queries) the op's payload — explanation tree, model card, fired-list, …; shape is op-specific (see `schema <target> <op>`)",
    "diagnostics": "structured findings — see `schema diagnostic`",
    "written": "whether the model was written to disk",
    "output": "the path the model was written to; absent for read/preview/refused/rejected"
  }
}
```

→ Seven keys are always present (`target`, `op`, `outcome`, `ok`, `summary`, `diagnostics`, `written`); the rest are conditional. Note the **`ok`/`valid` split** — `ok` says the op ran, `valid` is the verdict on the model — and that **reads** put their payload under **`data`** (whose shape is op-specific: `schema <target> <op>` gives it per verb). This is why one output reader suffices for the whole CLI.

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
    "annotation",
    "code",
    "comment",
    "commonPrecondition",
    "computedField",
    "condition",
    "external-description",
    "field",
    "group",
    "internal-description",
    "messages",
    "name",
    "severity"
  ]
}
```

→ `returns: "result"` — the same envelope `schema result` describes, so the agent already knows how to read it. The `inputKeys` are the **union** of two specs (`rule add` accepts a rule-spec *or* a computation-spec, chosen by which keys are present): `field`/`condition`/`code` author a rule, `computedField`/`alternatives` a computation. The full schema (omitted by this projection) spells out each key, the `oneOf`, and the required sets — enough to construct a valid `add` payload without trial and error.
