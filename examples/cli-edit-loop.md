# dmtool CLI — write, edit & multi-file

*2026-06-11T21:02:17Z by Showboat 0.6.1*
<!-- showboat-id: 0cbe3a7c-75d1-448c-9808-fba2f6dba3e4 -->

The companion to [`cli-tour.md`](cli-tour.md) (which reads & inspects): this demo **writes**. It walks the F8 modify-by-re-express loop and the write surface on the `subscription-computed` fixture (which already carries a computation `EffectiveFeeComp = [BaseFee]`) — `where-used` · `computation explain` · `computation modify` (`--dry-run` previews, then write) · `rule add` · `model describe -w/--workspace` (multi-file) · `batch`. Every command is `dmtool -m <model> <target> <op>`: the model is set once with `-m`, then a `<target> <op>` selects the operation. Commands run through `dmtool` from the repo root; re-check with `uvx showboat@0.6.1 verify examples/cli-edit-loop.md`.

**Migrated verbs write IN PLACE and return the result envelope** (`{target, op, outcome, ok, summary, changed, written, output, diagnostics}`): a mutation carries `.changed`; `--dry-run` previews read-only (writes nothing). So edits below operate on a `/tmp` copy, leaving the committed fixture untouched.

## where-used — what depends on a field

Before you touch a field, see what references it. `where-used` reports the rules **and** computations that read an entity — so you know the blast radius of a change. The entity is **positional** — like `rule read <path>` — with the model as the `-m` context (`--entity` stays as the explicit alias); the referrers ride the result envelope's `data`, like every read (its model-wide sibling is `model usage`).

```bash
dmtool -m examples/models/subscription-computed.dm.json \
  where-used /Subscription/Billing/BaseFee
```

```output
{
  "target" : "where-used",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "1 referrer(s) of /Subscription/Billing/BaseFee",
  "data" : {
    "entity" : "/Subscription/Billing/BaseFee",
    "rules" : [ ],
    "computations" : [ "/Subscription/Billing/EffectiveFeeComp" ],
    "count" : 1
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `BaseFee` feeds **one** element — the computation `/Subscription/Billing/EffectiveFeeComp` (no validation rules). The split `data.rules` / `data.computations` arrays matter: a computation is stored as a rule in the kernel, but dmtool reports them apart so you see which is which.

## computation explain — read the existing computation

`computation explain` renders a computation in normalized form under the envelope's `data`: the computed field, its kind, the (precondition, operation) alternatives, and a one-line `gloss`. The computation is named by a positional path.

```bash
dmtool -m examples/models/subscription-computed.dm.json \
  computation explain /Subscription/Billing/EffectiveFeeComp \
  | jq '{computedField:.data.computedField, computedFieldKind:.data.computedFieldKind, gloss:.data.gloss}'
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
cp examples/models/subscription-computed.dm.json /tmp/edit-sub.json
dmtool -m /tmp/edit-sub.json \
  computation modify /Subscription/Billing/EffectiveFeeComp \
  --spec /tmp/edit-eff.json --dry-run
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
dmtool -m /tmp/edit-sub.json \
  computation modify /Subscription/Billing/EffectiveFeeComp \
  --spec /tmp/edit-eff.json
echo "--- Sum( occurrences in the file after modify: $(grep -c "Sum(" /tmp/edit-sub.json)"
echo "--- the written model still validates: $(dmtool -m /tmp/edit-sub.json model validate | jq -c ".valid")"
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

`rule add` validates a candidate against the real kernel and, on accept, **writes it into the model in place** (it emits the envelope, not the model text). The new rule's path lands under `.changed.rule`. We add on a fresh `/tmp` copy, then `model validate` the persisted model and confirm the rule count rose via `export`.

```bash
printf "%s" "{\"field\":\"/Subscription/Billing/EffectiveFee\",\"condition\":\"[EffectiveFee] < [BaseFee]\",\"code\":\"EFFECTIVE_BELOW_BASE\",\"messages\":[{\"locale\":\"en_US\",\"text\":\"Effective fee is below base fee.\"},{\"locale\":\"de_DE\",\"text\":\"Effektivgebuehr unter Basisgebuehr.\"}]}" > /tmp/edit-rule.json
cp examples/models/subscription-computed.dm.json /tmp/edit-add.json
dmtool -m /tmp/edit-add.json rule add /tmp/edit-rule.json \
  | jq -c "{outcome, rule: .changed.rule, written}"
dmtool -m /tmp/edit-add.json model validate | jq -c "{valid, diagnostics}"
dmtool -m /tmp/edit-add.json export | grep -E "^- rules:"
```

```output
{"outcome":"applied","rule":"/Subscription/Billing/EFFECTIVE_BELOW_BASE","written":true}
{"valid":true,"diagnostics":[]}
- rules: 1
```

→ The kernel accepted the candidate, `rule add` wrote it in place (`written: true`, `changed.rule` names it), and the model re-validates clean. `export`'s one-line summary shows the rule landed (`rules: 1`, up from the fixture's 0). The condition is true on the **violation** (effective below base) — A12 rules fire when their condition holds.

## model describe over a workspace — a multi-file model

A model that `include`s another model needs `-w/--workspace` to resolve it when the included model lives elsewhere in the workspace. `storefront` (in `multifile/app/`) mounts the separate `catalog` model (in `multifile/lib/`) at `/Storefront/Inventory`. The structure rides the envelope's `.data.fields`.

```bash
dmtool -m examples/models/multifile/app/storefront.dm.json \
  model describe \
  -w examples/models/multifile/lib \
  | jq -c '.data.fields[] | select(.path|test("Inventory")) | {path,kind}'
```

```output
{"path":"/Storefront/Inventory/Sku","kind":"STRING"}
{"path":"/Storefront/Inventory/ListPrice","kind":"NUMBER"}
```

→ The mounted `catalog`'s fields (`/Storefront/Inventory/Sku`, `/Storefront/Inventory/ListPrice`) resolve — but **only** because `-w/--workspace` pointed at the `lib/` tree. Without it the model can't expand (the include is unresolved) and every verb fails until you supply it. `-w/--workspace` is a shared option on every model-loading verb.

## batch — many ops in one warm JVM

`batch` runs a JSON array of verb invocations in a single process, amortizing the kernel's warm-up. Each op is `{id, verb, args}`, where `verb` is the **target** (`rule`) and the operation (`check`) leads `args` — the same target-first form a standalone call uses; each result is tagged by its `id`, so a producer can attribute every verdict.

```bash
printf "%s" "[{\"id\":\"ok\",\"verb\":\"rule\",\"args\":[\"check\",\"-m\",\"examples/models/subscription-computed.dm.json\",\"--field\",\"/Subscription/Billing/EffectiveFee\",\"--condition\",\"[EffectiveFee] < [BaseFee]\",\"--code\",\"X\"]},{\"id\":\"bad\",\"verb\":\"rule\",\"args\":[\"check\",\"-m\",\"examples/models/subscription-computed.dm.json\",\"--field\",\"/Subscription/Billing/EffectiveFee\",\"--condition\",\"[EffectiveFee] PatternViolated \\\"x\\\"\",\"--code\",\"Y\"]}]" > /tmp/edit-ops.json
dmtool batch /tmp/edit-ops.json | jq -c ".[] | {id, valid: .result.valid}"
```

```output
{"id":"ok","valid":true}
{"id":"bad","valid":false}
```

→ Two `rule check`s in one JVM: the numeric comparison is `valid`, the pattern comparison on a number field is rejected — each verdict carries its `id`. This is how the eval runner re-validates many candidates at once.

## The tool describes itself — operators & schema

Two more self-description verbs round out the surface. `operators <id>` gives one DSL operator in full; `schema <target> <op>` gives that op's **directional contract** (`{op, input, returns, ...}`) — the rich rule/computation input schema (a `oneOf`) rides `.input`.

```bash
dmtool operators DateRange | jq '{id, kind, meaning}'
dmtool schema rule add | jq -c '.input.oneOf'
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

## rule modify — re-express an existing rule (preview, then write)

`rule modify` is the rule-side twin of `computation modify`: it swaps a rule's condition for an equivalent re-expression, validating the candidate against the real kernel before it writes. By default it **writes in place** (`-o` redirects); **`--dry-run`** previews read-only. We re-express `/Order/DeliveryNotBeforeOrder` on `order-ruled` — its original `DifferenceInDays(...) < 0` becomes a direct date comparison `[/Order/DeliveryDate] < [/Order/OrderDate]` (delivery before order is the violation). The before/after ride `.changed`. We work on a `/tmp` copy so the fixture stays put.

```bash
rm -f /tmp/edit2-order.json
cp examples/models/order-ruled.dm.json /tmp/edit2-order.json
dmtool -m /tmp/edit2-order.json \
  rule modify /Order/DeliveryNotBeforeOrder \
  --condition "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And [/Order/DeliveryDate] < [/Order/OrderDate]" \
  --dry-run
echo "--- re-expression occurrences in the file after --dry-run: $(grep -c "DeliveryDate\] <" /tmp/edit2-order.json)"
```

```output
{
  "target" : "rule",
  "op" : "modify",
  "outcome" : "preview",
  "ok" : true,
  "summary" : "would re-express /Order/DeliveryNotBeforeOrder",
  "changed" : {
    "before" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0",
    "after" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And [/Order/DeliveryDate] < [/Order/OrderDate]",
    "changed" : true,
    "lossiness" : [ ]
  },
  "diagnostics" : [ ],
  "written" : false
}
--- re-expression occurrences in the file after --dry-run: 0
```

→ `--dry-run` returns `outcome: "preview"` with `written: false`; the before (`DifferenceInDays(...) < 0`) and after (`[/Order/DeliveryDate] < [/Order/OrderDate]`) sit under `.changed`, and the file still has **0** occurrences of the new form — nothing was written. Now drop `--dry-run` to persist it:

```bash
dmtool -m /tmp/edit2-order.json \
  rule modify /Order/DeliveryNotBeforeOrder \
  --condition "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And [/Order/DeliveryDate] < [/Order/OrderDate]"
echo "--- re-expression occurrences after modify: $(grep -c "DeliveryDate\] <" /tmp/edit2-order.json)"
echo "--- the written model still validates: $(dmtool -m /tmp/edit2-order.json model validate | jq -c ".valid")"
```

```output
{
  "target" : "rule",
  "op" : "modify",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "re-expressed /Order/DeliveryNotBeforeOrder",
  "changed" : {
    "before" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate) < 0",
    "after" : "AllFieldsFilled(/Order/OrderDate, /Order/DeliveryDate) And [/Order/DeliveryDate] < [/Order/OrderDate]",
    "changed" : true,
    "lossiness" : [ ]
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/edit2-order.json"
}
--- re-expression occurrences after modify: 1
--- the written model still validates: true
```

→ Now `outcome: "applied"` and `written: true` with `output` naming the file; the new condition is present **once** and the written model re-validates against the kernel (`true`). `order-ruled` declares only `en_US`, and a rule re-expression keeps the existing messages — so no locale juggling here. `rule modify` wrote in place; `-o <file>` would redirect the write and leave the source untouched.

## rule remove — drop a rule

`rule remove` deletes a rule by its slash-path and writes the model back (in place; `-o` redirects, `--dry-run` previews). The removed path lands under `.changed.removed`. We remove `/Order/DeliveryNotBeforeOrder` from a fresh `order-ruled` copy and confirm the rule count fell via `export`.

```bash
rm -f /tmp/edit2-rm-rule.json
cp examples/models/order-ruled.dm.json /tmp/edit2-rm-rule.json
echo "--- $(dmtool -m /tmp/edit2-rm-rule.json export | grep -E "^- rules:") (before)"
dmtool -m /tmp/edit2-rm-rule.json rule remove /Order/DeliveryNotBeforeOrder
echo "--- $(dmtool -m /tmp/edit2-rm-rule.json export | grep -E "^- rules:") (after)"
echo "--- the written model still validates: $(dmtool -m /tmp/edit2-rm-rule.json model validate | jq -c ".valid")"
```

```output
--- - rules: 3 (before)
{
  "target" : "rule",
  "op" : "remove",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "removed rule /Order/DeliveryNotBeforeOrder",
  "changed" : {
    "removed" : "/Order/DeliveryNotBeforeOrder"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/edit2-rm-rule.json"
}
--- - rules: 2 (after)
--- the written model still validates: true
```

→ `outcome: "applied"`, `written: true`, and `.changed.removed` names the gone rule. `export` shows the count dropped from **3 to 2**, and the model still validates — the other two rules are untouched. `--dry-run` would report the same removal with `written: false`.

## computation add — persist a brand-new computation

`computation add` is the computed-field counterpart to `rule add`: it takes a JSON computation-spec (`computedField`, `alternatives`, `messages`), kernel-validates it before and after, and on accept **writes it into the model in place** (emitting the envelope, not the model text). The synthesized computation path lands under `.changed.computation`. We add onto a fresh `subscription` copy — which has the bare field `/Subscription/Billing/EffectiveFee` but no computation for it — defining it as twice `BaseFee`. `subscription` declares `en_US` **and** `de_DE`, so the spec carries a message for each.

```bash
rm -f /tmp/edit2-comp-spec.json /tmp/edit2-sub.json
printf "%s" "{\"computedField\":\"/Subscription/Billing/EffectiveFee\",\"alternatives\":[{\"operation\":\"[BaseFee] * 2\"}],\"messages\":[{\"locale\":\"en_US\",\"text\":\"Effective fee is twice the base fee.\"},{\"locale\":\"de_DE\",\"text\":\"Effektivgebuehr ist das Doppelte der Basisgebuehr.\"}]}" > /tmp/edit2-comp-spec.json
cp examples/models/subscription.dm.json /tmp/edit2-sub.json
dmtool -m /tmp/edit2-sub.json computation add /tmp/edit2-comp-spec.json
echo "--- the written model validates: $(dmtool -m /tmp/edit2-sub.json model validate | jq -c ".valid")"
```

```output
{
  "target" : "computation",
  "op" : "add",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added computation /Subscription/Billing/EffectiveFeeComp",
  "changed" : {
    "computation" : "/Subscription/Billing/EffectiveFeeComp"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/edit2-sub.json"
}
--- the written model validates: true
```

→ The kernel accepted the spec and `computation add` wrote it in place (`written: true`). The computation path is **synthesized** from the field — `/Subscription/Billing/EffectiveFee` becomes the computation `/Subscription/Billing/EffectiveFeeComp` (the `…Comp` convention) — and surfaces under `.changed.computation`. The written model re-validates clean. That fresh computation is what we read back next.

## computation read — read a computation back

`computation read` round-trips a computation into rulekit's typed form (tolerant of Opaque constructs the typed model can't represent), reporting under `.data`. Where `computation explain` renders the full structure for a human, `read` is the machine-readable round-trip check: did the model parse back into our typed form, and what calculated field does it target. We read back the `EffectiveFeeComp` we just added (still on the `/tmp` `subscription` copy).

```bash
dmtool -m /tmp/edit2-sub.json computation read /Subscription/Billing/EffectiveFeeComp
```

```output
{
  "target" : "computation",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read /Subscription/Billing/EffectiveFeeComp",
  "data" : {
    "computation" : "/Subscription/Billing/EffectiveFeeComp",
    "converted" : true,
    "calculatedField" : "/Subscription/Billing/EffectiveFee"
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `outcome: "read"`, `ok: true`, and `.data` carries `converted: true` (the kernel construct round-tripped into rulekit's typed form cleanly) plus `calculatedField` — the `/Subscription/Billing/EffectiveFee` this computation assigns. `read` writes nothing (`written: false`); it's the verification half of the add we just made.

## computation remove — drop a computation

`computation remove` deletes a computation by its slash-path and writes the model back (in place; `-o` redirects, `--dry-run` previews) — the rule-removal twin for computed fields, with the gone path under `.changed.removed`. We remove `/Subscription/Billing/EffectiveFeeComp` from a fresh `subscription-computed` copy (the fixture that ships with that computation), confirming it's present first and gone after.

```bash
rm -f /tmp/edit2-rm-comp.json
cp examples/models/subscription-computed.dm.json /tmp/edit2-rm-comp.json
echo "--- computation present before? $(dmtool -m /tmp/edit2-rm-comp.json computation read /Subscription/Billing/EffectiveFeeComp | jq -c ".ok")"
dmtool -m /tmp/edit2-rm-comp.json computation remove /Subscription/Billing/EffectiveFeeComp
echo "--- read it again (exit code signals absence): $(dmtool -m /tmp/edit2-rm-comp.json computation read /Subscription/Billing/EffectiveFeeComp 2>&1 >/dev/null; echo "exit=$?")"
echo "--- the written model still validates: $(dmtool -m /tmp/edit2-rm-comp.json model validate | jq -c ".valid")"
```

```output
--- computation present before? true
{
  "target" : "computation",
  "op" : "remove",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "removed computation /Subscription/Billing/EffectiveFeeComp",
  "changed" : {
    "removed" : "/Subscription/Billing/EffectiveFeeComp"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/edit2-rm-comp.json"
}
--- read it again (exit code signals absence): '/Subscription/Billing/EffectiveFeeComp' is not a computation in this model — computations: []
exit=2
--- the written model still validates: true
```

→ `outcome: "applied"`, `written: true`, `.changed.removed` names the gone computation. Reading it back now exits non-zero (`exit=2`) — the computation is gone — and the model still validates clean. With this verb the rule + computation CRUD is complete: add · read · explain · modify · remove on both targets, every mutation kernel-validated and written in place.

## Comments — author once, preserved across the loop

A rule's condition can carry a `;;` line **comment** — concrete syntax the kernel tolerates. `rule add` takes an optional `comment` key (and `rule modify --comment` sets one); it persists as a leading `;;` line. The payoff is the loop: a later `rule modify` that *doesn't* mention the comment **keeps it** (preserve-by-default), so the note survives the re-express. We work on a `/tmp` copy of `subscription-computed` (bilingual → both messages).

```bash
cp examples/models/subscription-computed.dm.json /tmp/edit2-comment.json
cat > /tmp/edit2-rule.json <<'SPEC'
{
  "field": "/Subscription/Addons/MonthlyFee",
  "condition": "FieldFilled(/Subscription/Addons/MonthlyFee) And [/Subscription/Addons/MonthlyFee] > 1000",
  "comment": "cap the monthly fee per policy",
  "code": "FEE_CAP", "name": "FeeCap",
  "messages": [
    {"locale": "en_US", "text": "Monthly fee is too high."},
    {"locale": "de_DE", "text": "Monatsgebühr ist zu hoch."}
  ]
}
SPEC
dmtool -m /tmp/edit2-comment.json rule add /tmp/edit2-rule.json | jq -r '.outcome, .written'
echo "--- persisted comment:"
grep -o ';;[^\\"]*' /tmp/edit2-comment.json

```

```output
applied
true
--- persisted comment:
;; cap the monthly fee per policy
```

→ `outcome: "applied"`, and the model carries the `;;` note ahead of the condition. Now re-express the threshold **without** restating the comment — it rides along:

```bash
dmtool -m /tmp/edit2-comment.json \
  rule modify /Subscription/Addons/FeeCap \
  --condition "FieldFilled(/Subscription/Addons/MonthlyFee) And [/Subscription/Addons/MonthlyFee] > 500" \
  | jq -r '.outcome'
echo "--- comment still present:"
grep -o ';;[^\\"]*' /tmp/edit2-comment.json
echo "--- new threshold applied (count of '> 500'):"
grep -c "> 500" /tmp/edit2-comment.json

```

```output
applied
--- comment still present:
;; cap the monthly fee per policy
--- new threshold applied (count of '> 500'):
1
```

→ The comment survived a comment-less `modify`, and the threshold dropped to 500. To *change* the note, pass `rule modify --comment "<text>"` (it wins over the preserved one). The same `;;` comment is authorable from the typed library (`Rule.Builder.comment(...)` / `Computation.Builder.comment(...)`, on operations and preconditions too) and from `apply` rule ops (a `comment` key) — background in KERNEL-FINDINGS "Condition comments".

