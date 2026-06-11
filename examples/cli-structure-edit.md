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
