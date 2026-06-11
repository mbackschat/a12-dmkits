# rulekit CLI — editing model structure (add-field · add-group · remove)

*2026-06-11T14:46:56Z by Showboat 0.6.1*
<!-- showboat-id: a4fb4303-58f2-4e05-8754-c2c20da7259d -->

The structure-edit companion to [`cli-edit-loop.md`](cli-edit-loop.md) (which edits *rules*): this demo **edits the model's structure** — add a field, add a group, and **safely remove** a referenced field. Like `modify`, these verbs write **in place** (`-o` redirects, `--dry-run` previews) and re-bind through the real kernel. Every command runs through `bin/rk`; re-check with `uvx showboat@0.6.1 verify examples/cli-structure-edit.md`.

We work on a `/tmp` copy of the `order-ruled` fixture (its `DeliveryNotBeforeOrder` rule references `/Order/DeliveryDate`) so the fixture stays put.

```bash
cp cli/src/test/resources/models/order-ruled.dm.json /tmp/rk-struct.dm.json
echo "copied order-ruled → /tmp/rk-struct.dm.json"

```

```output
copied order-ruled → /tmp/rk-struct.dm.json
```

## add-field — add a typed field to a group

```bash
bin/rk add-field /tmp/rk-struct.dm.json --group /Order --name Discount --kind NUMBER 2>/dev/null
echo "--- /Order/Discount now in the model: $(grep -c '"/Order/Discount"' /tmp/rk-struct.dm.json)"

```

```output
{
  "added" : "/Order/Discount",
  "kind" : "NUMBER",
  "written" : true
}
--- /Order/Discount now in the model: 1
```

## add-group — add a repeatable group (--repeatable <max>)

```bash
bin/rk add-group /tmp/rk-struct.dm.json --parent /Order --name Notes --repeatable 5 2>/dev/null
echo "--- /Order/Notes now in the model: $(grep -c '"/Order/Notes"' /tmp/rk-struct.dm.json)"

```

```output
{
  "added" : "/Order/Notes",
  "repeatable" : true,
  "written" : true
}
--- /Order/Notes now in the model: 1
```

## remove — the safe-delete gate

Removing `/Order/DeliveryDate` would orphan the rule that references it (`DeliveryNotBeforeOrder`). `remove` runs `whereUsed` first and **refuses**, surfacing *which* rules block it (structured, on stderr, exit 2) — so an agent can decide.

```bash
bin/rk remove /tmp/rk-struct.dm.json --field /Order/DeliveryDate 2>/tmp/rk-refusal.json; echo "(exit $?)"
cat /tmp/rk-refusal.json

```

```output
(exit 2)
{
  "blocked" : "/Order/DeliveryDate",
  "referencedBy" : {
    "rules" : [ "/Order/DeliveryNotBeforeOrder" ],
    "computations" : [ ]
  },
  "hint" : "remove these first, or pass --cascade to remove them together with /Order/DeliveryDate"
}

```

Pass **`--cascade`** to remove the blocking rule(s) together with the field:

```bash
bin/rk remove /tmp/rk-struct.dm.json --field /Order/DeliveryDate --cascade 2>/dev/null
echo "--- DeliveryNotBeforeOrder remaining: $(grep -c DeliveryNotBeforeOrder /tmp/rk-struct.dm.json)"

```

```output
{
  "removed" : "/Order/DeliveryDate",
  "cascaded" : [ "/Order/DeliveryNotBeforeOrder" ],
  "written" : true
}
--- DeliveryNotBeforeOrder remaining: 0
```

The edited model still validates against the kernel:

```bash
bin/rk validate /tmp/rk-struct.dm.json | jq -c '.valid'

```

```output
true
```

## Combined — rulekit + modelkit on one model

Structure (modelkit) and rules (rulekit) edit the same model file, so they compose. Add a **rule** on the `Discount` field added above — `add` prints the new model, so redirect it to a fresh path:

```bash
printf '%s' '{"field":"/Order/Discount","condition":"FieldFilled(Discount) And [Discount] < 0","code":"DISCOUNT_NEGATIVE","messages":[{"locale":"en_US","text":"Discount must not be negative."}]}' > /tmp/rk-rule.json
bin/rk add /tmp/rk-struct.dm.json /tmp/rk-rule.json > /tmp/rk-combined.dm.json 2>/dev/null
echo "--- rule on the added field present: $(grep -c DISCOUNT_NEGATIVE /tmp/rk-combined.dm.json)"

```

```output
--- rule on the added field present: 3
```

Now  is referenced by that rule, so  refuses it — the safe-delete gate, triggered by a rule we just authored:

```bash
bin/rk remove /tmp/rk-combined.dm.json --field /Order/Discount 2>/tmp/rk-ref2.json; echo "(exit $?)"
cat /tmp/rk-ref2.json

```

```output
(exit 2)
{
  "blocked" : "/Order/Discount",
  "referencedBy" : {
    "rules" : [ "/Order/DISCOUNT_NEGATIVE" ],
    "computations" : [ ]
  },
  "hint" : "remove these first, or pass --cascade to remove them together with /Order/Discount"
}

```

 removes the field and the rule it triggered, together — one model, both kits:

```bash
bin/rk remove /tmp/rk-combined.dm.json --field /Order/Discount --cascade 2>/dev/null
echo "--- DISCOUNT_NEGATIVE remaining: $(grep -c DISCOUNT_NEGATIVE /tmp/rk-combined.dm.json)"
bin/rk validate /tmp/rk-combined.dm.json | jq -c '.valid'

```

```output
{
  "removed" : "/Order/Discount",
  "cascaded" : [ "/Order/DISCOUNT_NEGATIVE" ],
  "written" : true
}
--- DISCOUNT_NEGATIVE remaining: 0
true
```

## Type definitions & includes

Declare a reusable **type definition**, then type a field with it (`add-field --typedef`):

```bash
bin/rk add-typedef /tmp/rk-struct.dm.json --id Money --kind NUMBER 2>/dev/null
bin/rk add-field /tmp/rk-struct.dm.json --group /Order --name Credit --typedef Money 2>/dev/null
echo "--- /Order/Credit is a TypeDefType: $(grep -c TypeDefType /tmp/rk-struct.dm.json)"

```

```output
{
  "added" : "Money",
  "kind" : "NUMBER",
  "written" : true
}
{
  "added" : "/Order/Credit",
  "kind" : "TypeDefType",
  "written" : true
}
--- /Order/Credit is a TypeDefType: 2
```

Declare an **include** and mount another model under a group — resolved + kernel-validated via `--include-dir`:

```bash
mkdir -p /tmp/rk-lib && cp cli/src/test/resources/models/multifile/lib/catalog.dm.json /tmp/rk-lib/
bin/rk add-include /tmp/rk-struct.dm.json --alias catalog --reference catalog --parent /Order --name Catalog --include-dir /tmp/rk-lib 2>/dev/null
echo "--- mounted + still kernel-valid: $(bin/rk validate /tmp/rk-struct.dm.json --include-dir /tmp/rk-lib | jq -c '.valid')"

```

```output
{
  "added" : "catalog",
  "reference" : "catalog",
  "mountedAt" : "/Order/Catalog",
  "written" : true
}
--- mounted + still kernel-valid: true
```
