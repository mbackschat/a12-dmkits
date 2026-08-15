# dmtool — group operands: say "every field in here" once

A rule operand may name a **group** instead of a field list. The kernel expands it to every field below that group, recursively — so `NotAllFieldsFilled(/Order/ShippingAddress)` means "the address is partly filled" without naming `Street` or `City`, and it keeps meaning that when the address gains a field. This demo shows the payoff, the one place the spelling changes meaning, and the reporting consequence that catches people out.

The fixture's shipping address is an ordinary non-repeatable group with two fields.

```bash
dmtool -m examples/models/order-aggregates.dm.json group read /Order/ShippingAddress | jq -c '.data'

```

```output
{"group":"/Order/ShippingAddress","repeatable":false,"fields":["/Order/ShippingAddress/Street","/Order/ShippingAddress/City"]}
```

A candidate rule over the group is kernel-valid, and names no field. Remember the polarity: a rule's condition is true **on a violation**, so the incomplete-address rule uses `NotAllFieldsFilled`, not `AllFieldsFilled`.

```bash
dmtool -m examples/models/order-aggregates.dm.json rule check --group /Order \
  --field /Order/ShippingAddress/Street --code ADDRESS_INCOMPLETE \
  --condition 'NotAllFieldsFilled(/Order/ShippingAddress)' | jq -c '{valid, verification}'

```

```output
{"valid":true,"verification":"KERNEL_CONFIRMED"}
```

## The payoff: the rule follows the group

Persist it on a scratch copy, then evaluate a document whose address is complete. Nothing fires.

```bash
mkdir -p /tmp/grp && cp examples/models/order-aggregates.dm.json /tmp/grp/order.dm.json
cat > /tmp/grp/rule.json <<'EOF'
{ "group": "/Order", "name": "AddressComplete", "field": "/Order/ShippingAddress/Street",
  "condition": "NotAllFieldsFilled(/Order/ShippingAddress)", "code": "ADDRESS_INCOMPLETE",
  "severity": "ERROR", "messages": [ { "locale": "en_US", "text": "Shipping address is incomplete" } ] }
EOF
echo '{ "Order": { "ShippingAddress": { "Street": "1 High St", "City": "Ely" } } }' > /tmp/grp/doc.json
dmtool -m /tmp/grp/order.dm.json rule add /tmp/grp/rule.json -o /tmp/grp/order.dm.json \
  | jq -c '{outcome, verification, written}'
dmtool -m /tmp/grp/order.dm.json model eval --instance /tmp/grp/doc.json | jq -r '.summary'

```

```output
{"outcome":"applied","verification":"KERNEL_CONFIRMED","written":true}
0 rule(s) fired
```

Now add a third field to the group and evaluate the **same** document against the **same** rule. The rule is not edited, and it now requires the new field — an explicit `AllFieldsFilled(Street, City)` would have silently kept passing.

```bash
echo '{ "group": "/Order/ShippingAddress", "name": "Country", "kind": "STRING" }' > /tmp/grp/country.json
dmtool -m /tmp/grp/order.dm.json field add /tmp/grp/country.json -o /tmp/grp/order.dm.json | jq -r '.summary'
dmtool -m /tmp/grp/order.dm.json model eval --instance /tmp/grp/doc.json | jq -r '.summary'

```

```output
added field /Order/ShippingAddress/Country
1 rule(s) fired
```

## On a REPEATABLE group the star changes what the rule means

`/Order/Items` repeats. Here both spellings are legal, and they are different rules: `Items*` judges **all rows at once**, while a bare `Items` makes the rule iterate **per row**. The bare form is only available because this rule's error field sits *inside* `Items` — put the error field outside and the star is required (`MVK_NO_WILDCARD`). What the group declares as its index field makes no difference either way.

Inside that per-row iteration a second gate applies: the kernel refuses a condition it reads as able to fire on the **empty repetitions** of the iterated group (`MVK_NEG_CONDITION_IN_ITERATION`, a truth-propagation law it owns — see `docs/MVK-LEDGER.md`). For the four quantifiers below that lands on the negative pair.

```bash
mkdir -p /tmp/grp
jq -n '["AllFieldsFilled","NotAllFieldsFilled","AtLeastOneFieldFilled","NoFieldFilled"] as $ops
  | ["/Order/Items","/Order/Items*"] as $scopes
  | [ $ops[] as $o | $scopes[] as $s
      | { id: "\($o)(\($s))", verb: "rule",
          args: ["check","--group","/Order","--field","/Order/Items/Sku","--code","ITEMS",
                 "--condition","\($o)(\($s))"] } ]' > /tmp/grp/star-ops.json
dmtool -m examples/models/order-aggregates.dm.json batch /tmp/grp/star-ops.json \
  | jq -r '.summary, (.data.results[] | "  \(.id)  valid=\(.result.valid)  \(.result.diagnostics[0].code // "-")")'

```

```output
dispatched 8 ops; 2 of 8 exited nonzero (0 failed, 2 reported a negative verdict or comparison) — earlier writes were not rolled back
  AllFieldsFilled(/Order/Items)  valid=true  -
  AllFieldsFilled(/Order/Items*)  valid=true  -
  NotAllFieldsFilled(/Order/Items)  valid=false  MVK_NEG_CONDITION_IN_ITERATION
  NotAllFieldsFilled(/Order/Items*)  valid=true  -
  AtLeastOneFieldFilled(/Order/Items)  valid=true  -
  AtLeastOneFieldFilled(/Order/Items*)  valid=true  -
  NoFieldFilled(/Order/Items)  valid=false  MVK_NEG_CONDITION_IN_ITERATION
  NoFieldFilled(/Order/Items*)  valid=true  -
```

`batch` is the right shape for a sweep like this: eight verdicts in one process rather than eight launches, and its aggregate summary already separates the two nonzero children that reported a **negative verdict** from a child that actually **failed** — the distinction `ok` alone does not carry.

**Do not generalize that to "the kernel refuses negative quantifiers here."** The gate is the kernel's own syntactic analysis, and it matches neither the operator's name nor the condition's meaning. `FieldsNotCollectivelyFilled` is negative and accepted; `NumberOfFilledFields(...) < 1` contains no negation and is refused — and `< 1` is refused where `<= 0`, the same predicate on a count that cannot go below zero, is accepted.

```bash
jq -n '["FieldsNotCollectivelyFilled(/Order/Items)",
        "NumberOfFilledFields(/Order/Items) < 1",
        "NumberOfFilledFields(/Order/Items) <= 0"]
  | [ .[] | { id: ., verb: "rule",
      args: ["check","--group","/Order","--field","/Order/Items/Sku","--code","ITEMS",
             "--condition", .] } ]' > /tmp/grp/gate-ops.json
dmtool -m examples/models/order-aggregates.dm.json batch /tmp/grp/gate-ops.json \
  | jq -r '.data.results[] | "  \(.result.diagnostics[0].code // "accepted")  \(.id)"'

```

```output
  accepted  FieldsNotCollectivelyFilled(/Order/Items)
  MVK_NEG_CONDITION_IN_ITERATION  NumberOfFilledFields(/Order/Items) < 1
  accepted  NumberOfFilledFields(/Order/Items) <= 0
```

So the only reliable way to know is to ask: `rule check` is the kernel. When it refuses, the diagnostic's `fix` tells you to guard the condition with a positive existence check rather than to reason about the gate.

## The reporting consequence: two readers that disagree, correctly

A group operand reads fields it never names, so the exact question and the reachability question have different answers — and both are right. `where-used` is exact: it lists the rules that **name** an entity, and no rule names `Street`.

```bash
dmtool -m /tmp/grp/order.dm.json where-used /Order/ShippingAddress/Street | jq -r '.summary'

```

```output
0 referrer(s) of /Order/ShippingAddress/Street
```

`model usage`'s dead-field set asks the other question — does anything **read** it — and walks ancestor groups to answer, so it does not offer `Street` as safe to delete.

```bash
dmtool -m /tmp/grp/order.dm.json model usage \
  | jq -c '{unreferenced: [.data.unreferenced[] | select(startswith("/Order/ShippingAddress"))]}'

```

```output
{"unreferenced":[]}
```

Reach for `where-used` when you are about to edit a rule, and for `model usage` when you are about to delete a field. Taking the first as an answer to the second is how a live field gets removed from a model that stays kernel-valid, because the rule still names a group that still exists.
