# dmtool CLI — write, edit & multi-file

*2026-06-11T21:02:17Z by Showboat 0.6.1*
<!-- showboat-id: 0cbe3a7c-75d1-448c-9808-fba2f6dba3e4 -->

The companion to [`cli-tour.md`](cli-tour.md) (which reads & inspects): this demo **writes**. It walks the F8 modify-by-re-express loop and the write surface on the `subscription-computed` fixture (which already carries a computation `EffectiveFeeComp = [BaseFee]`) — `where-used` · `computation explain` · `computation modify` (`--dry-run` previews, then write) · `rule add` · `doc describe --include-dir` (multi-file) · `batch`. Every command is `bin/dmtool -m <model> <target> <op>`: the model is set once with `-m`, then a `<target> <op>` selects the operation. Commands run through `bin/dmtool` from the repo root; re-check with `uvx showboat@0.6.1 verify examples/cli-edit-loop.md`.

**Migrated verbs write IN PLACE and return the result envelope** (`{target, op, outcome, ok, summary, changed, written, output, diagnostics}`): a mutation carries `.changed`; `--dry-run` previews read-only (writes nothing). So edits below operate on a `/tmp` copy, leaving the committed fixture untouched.

## where-used — what depends on a field

Before you touch a field, see what references it. `where-used` is a cross-cutting root verb (it spans the whole model, so it takes the model as a **positional** and keeps its own `{entity, rules, computations, count}` shape rather than the envelope). It reports the rules **and** computations that read an entity — so you know the blast radius of a change.

```bash
bin/dmtool where-used cli/src/test/resources/models/subscription-computed.dm.json --entity /Subscription/Billing/BaseFee
```

```output
{
  "entity" : "/Subscription/Billing/BaseFee",
  "rules" : [ ],
  "computations" : [ "/Subscription/Billing/EffectiveFeeComp" ],
  "count" : 1
}
```

→ `BaseFee` feeds **one** element — the computation `/Subscription/Billing/EffectiveFeeComp` (no validation rules). The split `rules` / `computations` arrays matter: a computation is stored as a rule in the kernel, but dmtool reports them apart so you see which is which.

## computation explain — read the existing computation

`computation explain` renders a computation in normalized form under the envelope's `data`: the computed field, its kind, the (precondition, operation) alternatives, and a one-line `gloss`. The computation is named by a positional path.

```bash
bin/dmtool -m cli/src/test/resources/models/subscription-computed.dm.json computation explain /Subscription/Billing/EffectiveFeeComp | jq '{computedField:.data.computedField, computedFieldKind:.data.computedFieldKind, gloss:.data.gloss}'
```

```output
{
  "computedField": "/Subscription/Billing/EffectiveFee",
  "computedFieldKind": "NUMBER",
  "gloss": "Computes /Subscription/Billing/EffectiveFee unconditionally."
}
```

→ `EffectiveFee` (a `NUMBER`) is computed **unconditionally** as `[BaseFee]` — a plain pass-through. That's the thing we'll re-express next.

## computation modify — re-express a computation (preview, then write)

`computation modify` is the re-express verb. By default it **writes** the modified model in place (`-o` redirects); **`--dry-run`** previews read-only (writes nothing). The before/after/lossiness ride the envelope's `.changed`. Re-express the computation as *base fee + the total of all add-on monthly fees*. We work on a `/tmp` copy so the fixture stays put, and count `Sum(` occurrences to prove what was written.

```bash
printf "%s" "{ \"computedField\":\"/Subscription/Billing/EffectiveFee\", \"alternatives\":[{\"operation\":\"[BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)\"}], \"messages\":[{\"locale\":\"en_US\",\"text\":\"Base plus add-ons.\"},{\"locale\":\"de_DE\",\"text\":\"Basis plus Zusatz.\"}] }" > /tmp/edit-eff.json
cp cli/src/test/resources/models/subscription-computed.dm.json /tmp/edit-sub.json
bin/dmtool -m /tmp/edit-sub.json computation modify /Subscription/Billing/EffectiveFeeComp --spec /tmp/edit-eff.json --dry-run
echo "--- Sum( occurrences in the file after --dry-run: $(grep -c "Sum(" /tmp/edit-sub.json)"
```

```output
{
  "target" : "computation",
  "op" : "modify",
  "outcome" : "preview",
  "ok" : true,
  "summary" : "would re-express /Subscription/Billing/EffectiveFeeComp",
  "changed" : {
    "before" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee]",
    "after" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)",
    "changed" : true,
    "lossiness" : [ ]
  },
  "diagnostics" : [ ],
  "written" : false
}
--- Sum( occurrences in the file after --dry-run: 0
```

→ `--dry-run` returns `outcome: "preview"` with `written: false`; the before/after sit under `.changed`, and the file still has **0** occurrences of `Sum(` — nothing was written. Now drop `--dry-run`:

```bash
bin/dmtool -m /tmp/edit-sub.json computation modify /Subscription/Billing/EffectiveFeeComp --spec /tmp/edit-eff.json
echo "--- Sum( occurrences in the file after modify: $(grep -c "Sum(" /tmp/edit-sub.json)"
echo "--- the written model still validates: $(bin/dmtool -m /tmp/edit-sub.json doc validate | jq -c ".valid")"
```

```output
{
  "target" : "computation",
  "op" : "modify",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "re-expressed /Subscription/Billing/EffectiveFeeComp",
  "changed" : {
    "before" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee]",
    "after" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)",
    "changed" : true,
    "lossiness" : [ ]
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/edit-sub.json"
}
--- Sum( occurrences in the file after modify: 1
--- the written model still validates: true
```

→ Now `outcome: "applied"` and `written: true` with `output` naming the file; the `Sum(` count is **1** and the written model re-validates against the kernel (`true`). `computation modify` wrote in place; `-o <file>` would redirect the write and leave the source untouched.

## rule add — persist a brand-new rule

`rule add` validates a candidate against the real kernel and, on accept, **writes it into the model in place** (it emits the envelope, not the model text). The new rule's path lands under `.changed.rule`. We add on a fresh `/tmp` copy, then `doc validate` the persisted model and confirm the rule count rose via `export`.

```bash
printf "%s" "{\"field\":\"/Subscription/Billing/EffectiveFee\",\"condition\":\"[EffectiveFee] < [BaseFee]\",\"code\":\"EFFECTIVE_BELOW_BASE\",\"messages\":[{\"locale\":\"en_US\",\"text\":\"Effective fee is below base fee.\"},{\"locale\":\"de_DE\",\"text\":\"Effektivgebuehr unter Basisgebuehr.\"}]}" > /tmp/edit-rule.json
cp cli/src/test/resources/models/subscription-computed.dm.json /tmp/edit-add.json
bin/dmtool -m /tmp/edit-add.json rule add /tmp/edit-rule.json | jq -c "{outcome, rule: .changed.rule, written}"
bin/dmtool -m /tmp/edit-add.json doc validate | jq -c "{valid, diagnostics}"
bin/dmtool export /tmp/edit-add.json | grep -E "^- rules:"
```

```output
{"outcome":"applied","rule":"/Subscription/Billing/EFFECTIVE_BELOW_BASE","written":true}
{"valid":true,"diagnostics":[]}
- rules: 1
```

→ The kernel accepted the candidate, `rule add` wrote it in place (`written: true`, `changed.rule` names it), and the model re-validates clean. `export`'s one-line summary shows the rule landed (`rules: 1`, up from the fixture's 0). The condition is true on the **violation** (effective below base) — A12 rules fire when their condition holds.

## doc describe --include-dir — a multi-file model

A model that `include`s another model needs `--include-dir` to resolve it when the included model lives elsewhere in the workspace. `storefront` (in `multifile/app/`) mounts the separate `catalog` model (in `multifile/lib/`) at `/Storefront/Inventory`. The structure rides the envelope's `.data.fields`.

```bash
bin/dmtool -m cli/src/test/resources/models/multifile/app/storefront.dm.json doc describe --include-dir cli/src/test/resources/models/multifile/lib | jq -c '.data.fields[] | select(.path|test("Inventory")) | {path,kind}'
```

```output
{"path":"/Storefront/Inventory/Sku","kind":"STRING"}
{"path":"/Storefront/Inventory/ListPrice","kind":"NUMBER"}
```

→ The mounted `catalog`'s fields (`/Storefront/Inventory/Sku`, `/Storefront/Inventory/ListPrice`) resolve — but **only** because `--include-dir` pointed at the `lib/` tree. Without it the model can't expand (the include is unresolved) and every verb fails until you supply it. `--include-dir` is a shared option on every model-loading verb.

## batch — many ops in one warm JVM

`batch` runs a JSON array of verb invocations in a single process, amortizing the kernel's warm-up. Each op is `{id, verb, args}` and dispatches to the flat verb name (`check`); each result is tagged by its `id`, so a producer can attribute every verdict.

```bash
printf "%s" "[{\"id\":\"ok\",\"verb\":\"check\",\"args\":[\"cli/src/test/resources/models/subscription-computed.dm.json\",\"--field\",\"/Subscription/Billing/EffectiveFee\",\"--condition\",\"[EffectiveFee] < [BaseFee]\",\"--code\",\"X\"]},{\"id\":\"bad\",\"verb\":\"check\",\"args\":[\"cli/src/test/resources/models/subscription-computed.dm.json\",\"--field\",\"/Subscription/Billing/EffectiveFee\",\"--condition\",\"[EffectiveFee] PatternViolated \\\"x\\\"\",\"--code\",\"Y\"]}]" > /tmp/edit-ops.json
bin/dmtool batch /tmp/edit-ops.json | jq -c ".[] | {id, valid: .result.valid}"
```

```output
{"id":"ok","valid":true}
{"id":"bad","valid":false}
```

→ Two `check`s in one JVM: the numeric comparison is `valid`, the pattern comparison on a number field is rejected — each verdict carries its `id`. This is how the eval runner re-validates many candidates at once.

## The tool describes itself — operators & schema

Two more self-description verbs round out the surface. `operators <id>` gives one DSL operator in full; `schema <target> <op>` gives that op's **directional contract** (`{op, input, returns, ...}`) — the rich rule/computation input schema (a `oneOf`) rides `.input`.

```bash
bin/dmtool operators DateRange | jq '{id, kind, meaning}'
bin/dmtool schema rule add | jq -c '.input.oneOf'
```

```output
{
  "id": "DateRange",
  "kind": "FUNCTION",
  "meaning": "Constructs a date range from a start and an end date. Computation-only: it assigns a DATE_RANGE field in a computation operation; it has no condition form (the overlap predicates take date-range FIELDS, and a constructed range cannot be nested or compared). Both operands must be format-compatible date fields."
}
[{"title":"rule-spec","required":["field","condition","code","messages"]},{"title":"computation-spec","required":["computedField","alternatives","messages"]}]
```

→ `operators DateRange` carries the meaning and the use-site rule (computation-only); `schema rule add` returns the op's directional contract, with the input shape under `.input` — a `oneOf` of a rule-spec (`field`/`condition`/`code`) or a computation-spec (`computedField`/`alternatives`), both requiring `messages`. A cold agent learns the whole contract from the tool, no external docs.
