# dmtool CLI — editing model structure (field add · group add · field remove)

*2026-06-11T21:02:45Z by Showboat 0.6.1*
<!-- showboat-id: af0727e1-6f18-458a-ba7a-84938e1b2b93 -->

The structure-edit companion to the [verb tour](cli-tour.md) (which reads rules): this demo **edits the model's structure** — add a field, add a group, and **safely remove** a referenced field. These mutating verbs write **in place** (`-o` redirects, `--dry-run` previews) and carry the result envelope's `.changed`. Every command is `bin/dmtool -m <model> <target> <op>`, run from the repo root; some steps also use `jq`. Re-check the captured output with `uvx showboat@0.6.1 verify examples/cli-structure-edit.md` (exit 0 = it still matches the live CLI).

We work on a `/tmp` copy of the `order-ruled` fixture — its `DeliveryNotBeforeOrder` rule references `/Order/DeliveryDate`, which is what arms the safe-delete gate below — so the committed fixture stays put.

```bash
cp cli/src/test/resources/models/order-ruled.dm.json /tmp/struct.dm.json && echo "copied order-ruled → /tmp/struct.dm.json"
```

```output
copied order-ruled → /tmp/struct.dm.json
```

## field add — a typed field into a group

`field add` drops a `--kind`-typed field under a `--group`. It emits the result envelope: `outcome: applied`, the new path under `.changed.added`, and `written: true` with the `output` path — the write landed **in place** (a copy of the model file). `--dry-run` would preview the same envelope with `written: false`.

```bash
bin/dmtool -m /tmp/struct.dm.json field add --group /Order --name Discount --kind NUMBER
```

```output
{
  "target" : "field",
  "op" : "add",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added field /Order/Discount",
  "changed" : {
    "added" : "/Order/Discount",
    "kind" : "NUMBER"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct.dm.json"
}
```

→ `outcome: applied` with `changed.added = /Order/Discount` is the whole story: the field exists now, and `written: true` / `output` says it was persisted to the file. The kernel re-binds the edited model on the way out, so a structurally invalid edit would surface as diagnostics rather than a silent write.

## group add — a repeatable group

`group add` attaches a child group to `--parent`. Pass `--repeatable <max>` to make it a repetition list (a line-item-style group that repeats up to `max` times); the envelope echoes `changed.repeatable: true`.

```bash
bin/dmtool -m /tmp/struct.dm.json group add --parent /Order --name Notes --repeatable 5
```

```output
{
  "target" : "group",
  "op" : "add",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added group /Order/Notes",
  "changed" : {
    "added" : "/Order/Notes",
    "repeatable" : true
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct.dm.json"
}
```

→ `changed.added = /Order/Notes` and `changed.repeatable: true` confirm the new group is a repetition list. Omit `--repeatable` for a plain (single-occurrence) sub-group.

## field remove — the safe-delete gate

Removing `/Order/DeliveryDate` would orphan the rule that references it (`DeliveryNotBeforeOrder`). `field remove` runs the reference check **first** and **refuses**: it returns a `refused` envelope (`ok: false`, `written: false`), an `RK_REFERENCED` diagnostic carrying the `fix`, and the blocking referrers under `data.referencedBy` — and it exits **2**. Never a silent cascade. We redirect the envelope to a file so the exit code prints alongside it.

```bash
bin/dmtool -m /tmp/struct.dm.json field remove /Order/DeliveryDate >/tmp/struct-refusal.json 2>&1; echo "(exit $?)"; cat /tmp/struct-refusal.json
```

```output
(exit 2)
{
  "target" : "field",
  "op" : "remove",
  "outcome" : "refused",
  "ok" : false,
  "summary" : "/Order/DeliveryDate is referenced — refusing to remove",
  "data" : {
    "referencedBy" : {
      "rules" : [ "/Order/DeliveryNotBeforeOrder" ],
      "computations" : [ ]
    }
  },
  "diagnostics" : [ {
    "severity" : "ERROR",
    "source" : "PRECHECK",
    "code" : "RK_REFERENCED",
    "summary" : "/Order/DeliveryDate is referenced by 1 element(s); removing it would dangle them",
    "where" : {
      "path" : "/Order/DeliveryDate"
    },
    "fix" : "pass --cascade to remove the referrers too, or remove them first"
  } ],
  "written" : false
}
```

→ `outcome: refused` and exit `2`. The `RK_REFERENCED` diagnostic names what blocks the delete (`data.referencedBy.rules`) and its `fix` spells out the choice: remove the referrers first, or pass `--cascade`. The field is still in the model — nothing was written.

Pass **`--cascade`** to remove the blocking rule(s) together with the field — an explicit opt-in, so the cascade is never a surprise:

```bash
bin/dmtool -m /tmp/struct.dm.json field remove /Order/DeliveryDate --cascade
```

```output
{
  "target" : "field",
  "op" : "remove",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "removed /Order/DeliveryDate",
  "changed" : {
    "removed" : "/Order/DeliveryDate",
    "cascaded" : [ "/Order/DeliveryNotBeforeOrder" ]
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct.dm.json"
}
```

→ Now `outcome: applied`: `changed.removed` is the field and `changed.cascaded` lists exactly what went with it (`/Order/DeliveryNotBeforeOrder`) — so the caller sees the blast radius. The write landed in place.

The edited model still passes the real kernel consistency check:

```bash
bin/dmtool -m /tmp/struct.dm.json doc validate | jq -c "{outcome,valid,diagnostics}"
```

```output
{"outcome":"read","valid":true,"diagnostics":[]}
```

→ `valid: true` with no diagnostics: adding two structures and cascading the date away left a model the kernel still accepts.
