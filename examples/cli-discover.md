# dmtool CLI — discover the tool

*2026-06-30T00:06:51Z by Showboat 0.6.1*
<!-- showboat-id: 7b629f7c-e167-40e2-a65f-de2f1bd1bd05 -->

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
include remove
include inline
criterion add
criterion read
criterion remove
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
mcp
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
  "valid": null,
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
  "verifiedAgainst": "31.3.1",
  "count": 111,
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
  "meaning": "The signed number of complete days between the model-zone wall clocks of the first date/date-time and the second."
}
```

→ Looked up **directly**, by id, without a rule that uses it. The full record (omitted by the `jq` projection above) also carries `operands`/`returns`, `constraints`, `gotchas`, and a runnable `validExample`, so the agent can compose the operator correctly from the catalog alone.

## patterns — scaffold a correct rule from an idiom

Where `operators` is the *vocabulary*, `patterns` is the *idiom* catalogue — the recurring BA tasks, each a typed-DSL-backed template that's correct by construction, across three `kind`s: **rule** idioms (date-order, mutually-exclusive, …) bake in the two hardest rule traps — the **violation polarity** and a **referenced error field**; the **computation** idiom `tiered-amount` bakes in a **mutually-exclusive, exhaustive precondition table**; the **field** idioms (bounded-number, formatted-string, value-set-enum) scaffold the **field-level alternative** to a rule. `patterns` lists them, with each idiom's `kind`; the summary here is projected with `jq`.

```bash
dmtool patterns | jq '{count: .data.count, ids: (.data.patterns|map(.id))}'
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
  | jq '{pattern: .data.pattern, spec: {field: .data.spec.field, condition: .data.spec.condition}, valid}'
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
  "count": 102,
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
    "PORTABLE_CHECKER",
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

Invocations whose effective output kind is `RESULT_ENVELOPE` share the **same envelope shape**. A matching `operationalProfiles[].returns.views[]` overrides the primary `returns.kind`; raw JSON/text and empty-stdout modes bypass the envelope. `schema result` emits the envelope's JSON Schema, projected here to the property names and their meanings.

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
    "target": "the element family acted on: model | rule | computation | field | group | typedef | include | config | workspace | patterns | profile | where-used | meta",
    "op": "the operation: add | read | modify | remove | validate | check | explain | describe | export | eval | compute | compare | scaffold | …",
    "outcome": "The execution result class. `error`: the tool itself failed (an unexpected throwable, exit 70), unlike `rejected` (input rejected, exit 1). `read` covers EVERY non-mutating class (a query, verdict, runtime observation or comparison), so `outcome` alone does not tell you whether `valid` may be present. For `batch`, `completed` means every child was dispatched: inspect `data.allExitedZero` and each child's exit. An ordinary batch with a nonzero child sets outer `ok` false and exits 1; an observation batch (`batch --observations`) sets `ok` true and exits 0 after full dispatch and the artifact write, whatever `allExitedZero` says. Batch is non-transactional, so an earlier successful write remains, unlike `apply`, whose ops are one transaction and roll back together",
    "ok": "The operation succeeded: true for applied | preview | read | staged; false for refused | rejected | error. A completed ordinary batch is true only when `data.allExitedZero` is; a completed observation batch is true when every child was dispatched and the artifact was written, whatever `data.allExitedZero` says, and false when the artifact write failed",
    "valid": "The subject model is kernel-valid, a consistency VERDICT distinct from `ok`. PRESENT only on a genuine verdict (`model check`, `rule check`, `workspace check`, a `patterns` scaffold review) and on a rejection the kernel gate produced (then `false`); ABSENT on every read, runtime evaluation, comparison, mutation, refusal and pre-flight rejection. A per-entry `valid` inside `data` (`workspace list --validate`, `workspace check`) is a different, model-scoped fact that never affects the exit code. THE ABSENCE IS ASYMMETRIC, the one trap here: a gate that REJECTED emits `valid:false`, a gate that ACCEPTED emits nothing, so absence covers both 'no verdict' and 'a green verdict'. Never read it as 'nothing was checked': `verification` answers that. An accepted `--dry-run` preview is this case: `ok:true`, `verification:KERNEL_CONFIRMED` and no `valid` mean the kernel DID arbitrate and passed the change",
    "verification": "How a consistency gate arbitrated THIS invocation. KERNEL_CONFIRMED: a kernel backend ran the gate (the CLI always bundles one). STRUCTURAL_ONLY: the gate ran with NO backend, so a verdict beside it reflects structural prechecks only and must not be read as kernel-green (a library-consumer posture). PORTABLE_CHECKED: the EXPLICITLY REQUESTED kernel-free checker arbitrated (`model check --engine=portable`), a real re-implementation of A12 constraints rather than a precheck, and never a kernel confirmation. PORTABLE_CHECKED says nothing about COMPLETENESS: that checker cannot report a model VALID today, so a model it finds nothing wrong with returns NO `valid` key at exit 3, and its coverage evidence (`data.status`, `data.complete`, `data.gaps`) is the half to read. PRESENT whenever the gate ran, a `rejected` run included (KERNEL_CONFIRMED means the kernel ARBITRATED, not that the model was valid) and a WRITE included, since an edit verb gates before writing; ABSENT when no gate ran. So it is per-INVOCATION, never per-verb: `model report` carries it and `model report --tree` does not, `field add` does and `model normalize` (a pure reformat) does not. On a `--dry-run` preview it answers what `written:false` cannot: nothing was written, but the change WAS checked",
    "engine": "(runtime evaluation only) which implementation produced the semantic observation. `model eval`/`model compute`/`rule eval` evaluate through the kernel-free DM_INTERPRETER. Orthogonal to `verification`: an evaluation yields observations, not a verdict, so a runtime envelope carries `engine` and never `valid`",
    "engineRequested": "(a verdict from a verb that SELECTS an engine, today `model check`) the engine the caller asked for, present for all three request shapes, the default included, since an absent `--engine` IS a request for the kernel. The option spells them `kernel` and `portable`; the envelope uses the runtime `engine` key's names instead, so one reader learns one set of engine names. ABSENT on a verdict from a verb with no choice (`rule check`, `workspace check`), where `verification` still names what arbitrated",
    "engineExecuted": "(with `engineRequested`) the engine that actually produced the verdict, derived from the arbitration receipt so it cannot contradict `verification`. It ALWAYS EQUALS `engineRequested`: the selected engine is never substituted, not on an incomplete answer, a failure, or when the other engine would agree, and no envelope is emitted where they would differ. Published so a consumer can assert this rather than trust it",
    "engineVersion": "The build of the engine that produced this result, read from that engine, not stated by dmtool. With `engine` (a runtime observation): the `:interpreter` release for DM_INTERPRETER, the kernel actually on the classpath (never the built-against pin) for A12_KERNEL. With `engineExecuted` (a verdict): the portable checker artifact's own baked version, so a tool linked against another checker reports that one, or the live-classpath kernel version for A12_KERNEL. A verdict and an observation never share an envelope, so the key has one meaning wherever it appears",
    "toolVersion": "(with `engine`) the `dmtool` build that projected and serialized the observation. Emitted beside `engineVersion`, not inferred from it: evaluation semantics can move in the engine while pointer spelling, summaries and envelope shape move independently in the tool, so one version cannot identify what produced a retained result",
    "summary": "one human-readable line (the agent's quick read / log line)",
    "changed": "(mutations) the delta on success — e.g. {added, kind}, a refactor's rewritten references",
    "data": "(reads/queries/verdicts/comparisons) the op's payload — explanation tree, model card, fired-list, per-model verdicts, a profile comparison, …; shape is op-specific (see `schema <target> <op>`)",
    "diagnostics": "Structured findings (see `schema diagnostic`). NOT A DEFECT COUNT, in either direction: a rule with two independent defects can return ONE diagnostic (the gate suppresses the other), and one defect can return TWO (the same problem under two `MVK_*` codes). Which objection wins is not a fixed order, measured both ways. So read a lone diagnostic as AN objection, never THE objection: to attribute a refusal to one gate, remove the other candidate defect and re-run. A precheck refusal (`RK_*`, `source: PRECHECK`) means the kernel gate was never asked, so no `MVK_*` appears beside it however many kernel-level defects the input carries.",
    "written": "whether this invocation wrote its selected model or artifact destination to disk",
    "output": "the path of the model or artifact destination written by this invocation; absent when `written:false`"
  }
}
```

→ Within a result envelope, seven keys are always present (`target`, `op`, `outcome`, `ok`, `summary`, `diagnostics`, `written`); the rest are conditional. Note the **`ok`/`valid` split** — `ok` says the op ran, `valid` is the verdict on the model — and that **reads** put their payload under **`data`** (whose shape is op-specific: `schema <target> <op>` gives it per verb). One envelope reader therefore covers the envelope-producing routes; inspect the operational profile before choosing it instead of a raw JSON/text/artifact reader.

## schema rule add — a directional contract

`schema <target> <op>` gives one verb's **directional** I/O contract: what it consumes and what it returns. For a mutating verb like `rule add`, the input is a spec and its default effective output is the `result` envelope above.

```bash
dmtool schema rule add | jq '{op, returns, inputKeys: (.input.properties|keys)}'
```

```output
{
  "op": "rule add",
  "returns": "result",
  "inputKeys": [
    "allowDifferingDecimals",
    "annotation",
    "code",
    "comment",
    "condition",
    "external-description",
    "field",
    "group",
    "internal-description",
    "messages",
    "name",
    "possibleCauses",
    "severity"
  ]
}
```

→ `returns: "result"` — the same envelope `schema result` describes, so the agent already knows how to read it. The `inputKeys` are target-specific: `schema rule add` exposes only rule fields, while `schema computation add` exposes only computation fields. A key owned by the other spec is rejected instead of silently ignored. The full schema (omitted by this projection) spells out each key and the required set — enough to construct a valid payload without trial and error.
