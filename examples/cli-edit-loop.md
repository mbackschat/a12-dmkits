# rulekit CLI — write, edit & multi-file

*2026-06-08T17:07:32Z by Showboat 0.6.1*
<!-- showboat-id: 2b98deb6-9a76-414a-b7cc-b8d840adeca7 -->

The companion to [`cli-tour.md`](cli-tour.md) (which reads & inspects): this demo **writes**. It walks the F8 modify-by-re-express loop and the write surface on the `subscription-computed` fixture (which already carries a computation `EffectiveFeeComp = [BaseFee]`) — `where-used` · `explain --computation` · `modify` (`--dry-run` previews, then write) · `add` · `--include-dir` (multi-file) · `batch`. Every command runs through `bin/rk`; re-check with `uvx showboat@0.6.1 verify examples/cli-edit-loop.md`.

**`modify` — the one rule to remember:** it's the sole re-express verb. By default it **writes** the modified model (in place; `-o` redirects); **`--dry-run`** previews read-only (writes nothing).

## where-used — what depends on a field

Before you touch a field, see what references it. `where-used` reports the rules **and** computations that read an entity — so you know the blast radius of a change.

```bash
bin/rk where-used cli/src/test/resources/models/subscription-computed.dm.json --entity /Subscription/Billing/BaseFee

```

```output
{
  "entity" : "/Subscription/Billing/BaseFee",
  "rules" : [ ],
  "computations" : [ "/Subscription/Billing/EffectiveFeeComp" ],
  "count" : 1
}
```

→ `BaseFee` feeds **one** element — the computation `/Subscription/Billing/EffectiveFeeComp` (no validation rules). The split `rules` / `computations` arrays matter: a computation is stored as a rule in the kernel, but rulekit reports them apart so you see which is which.

## explain --computation — read the existing computation

`explain --computation` renders a computation in normalized form: the computed field, its kind, the (precondition, operation) alternatives, and a one-line `gloss`.

```bash
bin/rk explain cli/src/test/resources/models/subscription-computed.dm.json --computation /Subscription/Billing/EffectiveFeeComp | jq '{computedField, computedFieldKind, gloss}'

```

```output
{
  "computedField": "/Subscription/Billing/EffectiveFee",
  "computedFieldKind": "NUMBER",
  "gloss": "Computes /Subscription/Billing/EffectiveFee unconditionally."
}
```

→ `EffectiveFee` (a `NUMBER`) is computed **unconditionally** as `[BaseFee]` — a plain pass-through. That's the thing we'll re-express next.

## modify — re-express a computation (preview, then write)

`modify` is the re-express verb: by default it **writes** the modified model back; **`--dry-run`** previews read-only (writes nothing, shows the before/after). Re-express the computation as *base fee + the total of all add-on monthly fees*. We work on a `/tmp` copy so the fixture stays put, and count `Sum(` occurrences to prove what was written.

```bash
printf '%s' '{ "computedField":"/Subscription/Billing/EffectiveFee", "alternatives":[{"operation":"[BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)"}], "messages":[{"locale":"en_US","text":"Base plus add-ons."},{"locale":"de_DE","text":"Basis plus Zusatz."}] }' > /tmp/rk-eff.json
cp cli/src/test/resources/models/subscription-computed.dm.json /tmp/rk-sub.json
bin/rk modify /tmp/rk-sub.json --computation /Subscription/Billing/EffectiveFeeComp --spec /tmp/rk-eff.json --dry-run 2>/dev/null
echo "--- Sum( occurrences in the file after --dry-run: $(grep -c 'Sum(' /tmp/rk-sub.json)"

```

```output
{
  "before" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee]",
  "after" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)",
  "changed" : true,
  "lossiness" : [ ]
}
--- Sum( occurrences in the file after --dry-run: 0
```

→ `--dry-run` prints the before/after (read-only) and the file still has **0** occurrences of `Sum(` — nothing was written. Now drop `--dry-run`:

```bash
bin/rk modify /tmp/rk-sub.json --computation /Subscription/Billing/EffectiveFeeComp --spec /tmp/rk-eff.json 2>/dev/null
echo "--- Sum( occurrences in the file after modify: $(grep -c 'Sum(' /tmp/rk-sub.json)"
echo "--- the written model still validates: $(bin/rk validate /tmp/rk-sub.json | jq -c '.valid')"

```

```output
{
  "before" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee]",
  "after" : "computes /Subscription/Billing/EffectiveFee\nalways => [/Subscription/Billing/BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)",
  "changed" : true,
  "lossiness" : [ ]
}
--- Sum( occurrences in the file after modify: 1
--- the written model still validates: true
```

→ Same before/after, but now the file carries the new operation (`Sum(` count is **1**) and the written model re-validates against the kernel (`true`). `modify` wrote in place; `-o <file>` would redirect the write and leave the source untouched.

## add — persist a brand-new rule

`add` validates a candidate against the real kernel and, on accept, prints the new model (redirect it to a file). Here we add a requiredness-style comparison rule, then re-`validate` the persisted model and confirm the rule count rose.

```bash
printf '%s' '{"field":"/Subscription/Billing/EffectiveFee","condition":"[EffectiveFee] < [BaseFee]","code":"EFFECTIVE_BELOW_BASE","messages":[{"locale":"en_US","text":"Effective fee is below base fee."},{"locale":"de_DE","text":"Effektivgebuehr unter Basisgebuehr."}]}' > /tmp/rk-rule.json
bin/rk add cli/src/test/resources/models/subscription-computed.dm.json /tmp/rk-rule.json > /tmp/rk-added.json
bin/rk validate /tmp/rk-added.json
bin/rk export /tmp/rk-added.json | grep -E '^- rules:'

```

```output
{
  "valid" : true,
  "diagnostics" : [ ]
}
- rules: 1
```

→ The kernel accepted the candidate, `add` wrote the new model, and it re-validates clean. `export`'s one-line summary shows the rule landed (`rules: 1`, up from the fixture's 0). The condition is true on the **violation** (effective below base) — A12 rules fire when their condition holds.

## --include-dir — a multi-file model

A model that `include`s another model needs `--include-dir` to resolve it when the included model lives elsewhere in the workspace. `storefront` (in `multifile/app/`) mounts the separate `catalog` model (in `multifile/lib/`) at `/Storefront/Inventory`.

```bash
bin/rk describe cli/src/test/resources/models/multifile/app/storefront.dm.json --include-dir cli/src/test/resources/models/multifile/lib | jq -c '.fields[] | select(.path|test("Inventory")) | {path,kind}'

```

```output
{"path":"/Storefront/Inventory/Sku","kind":"STRING"}
{"path":"/Storefront/Inventory/ListPrice","kind":"NUMBER"}
```

→ The mounted `catalog`'s fields (`/Storefront/Inventory/Sku`, `/Storefront/Inventory/ListPrice`) resolve — but **only** because `--include-dir` pointed at the `lib/` tree. Without it the model can't expand (the include is unresolved) and every verb fails until you supply it. `--include-dir` is a shared option on every model-loading verb (`check`, `modify`, `describe`, …).

## batch — many ops in one warm JVM

`batch` runs a JSON array of verb invocations in a single process, amortizing the kernel's warm-up. Each result is tagged by its `id`, so a producer can attribute every verdict.

```bash
printf '%s' '[{"id":"ok","verb":"check","args":["cli/src/test/resources/models/subscription-computed.dm.json","--field","/Subscription/Billing/EffectiveFee","--condition","[EffectiveFee] < [BaseFee]","--code","X"]},{"id":"bad","verb":"check","args":["cli/src/test/resources/models/subscription-computed.dm.json","--field","/Subscription/Billing/EffectiveFee","--condition","[EffectiveFee] PatternViolated \"x\"","--code","Y"]}]' > /tmp/rk-ops.json
bin/rk batch /tmp/rk-ops.json | jq -c '.[] | {id, valid: .result.valid}'

```

```output
{"id":"ok","valid":true}
{"id":"bad","valid":false}
```

→ Two `check`s in one JVM: the numeric comparison is `valid`, the pattern comparison on a number field is rejected — each verdict carries its `id`. This is how the eval runner re-validates many candidates at once.

## The tool describes itself — operators & schema

Two more self-description verbs round out the surface. `operators <id>` gives one DSL operator in full; `schema <verb>` gives a verb's JSON I/O contract — here `add`'s two input shapes (rule-spec vs computation-spec).

```bash
bin/rk operators DateRange | jq '{id, kind, meaning}'
bin/rk schema add | jq -c '.oneOf'

```

```output
{
  "id": "DateRange",
  "kind": "FUNCTION",
  "meaning": "Constructs a date range from a start and an end date. Computation-only: it assigns a DATE_RANGE field in a computation operation; it has no condition form (the overlap predicates take date-range FIELDS, and a constructed range cannot be nested or compared). Both operands must be format-compatible date fields."
}
[{"title":"rule-spec","required":["field","condition","code","messages"]},{"title":"computation-spec","required":["computedField","alternatives","messages"]}]
```

→ `operators DateRange` carries the meaning and the use-site rule (computation-only); `schema add` declares the `oneOf` — a rule-spec (`field`/`condition`/`code`) or a computation-spec (`computedField`/`alternatives`), both requiring `messages`. A cold agent learns the whole contract from the tool, no external docs.

