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

## reading the shape — field read · group read

The two reads tell you what a path *is* before you edit it: `field read <fieldPath>` returns the field's declared `type`, `group read <groupPath>` returns whether the group repeats. Both land their answer under `.data` and write nothing (`written: false`). We work from a fresh copy of the `subscription` fixture — its `/Subscription/Billing/BaseFee` field and repeatable `/Subscription/Addons` group give us something to inspect.

```bash
cp cli/src/test/resources/models/subscription.dm.json /tmp/struct2.dm.json && echo "copied subscription → /tmp/struct2.dm.json"
```

```output
copied subscription → /tmp/struct2.dm.json
```

`field read` resolves the field's full name-path and reports its kernel `type` (here `NumberType`):

```bash
bin/dmtool -m /tmp/struct2.dm.json field read /Subscription/Billing/BaseFee
```

```output
{
  "target" : "field",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read /Subscription/Billing/BaseFee",
  "data" : {
    "field" : "/Subscription/Billing/BaseFee",
    "type" : "NumberType"
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.type = NumberType` is the field's declared type. The envelope's `outcome: read` / `written: false` mark this as a non-mutating query.

`group read` answers the one structural question a group carries here — does it repeat? `/Subscription/Addons` is a repetition list (it was declared `--repeatable`), so `data.repeatable` is `true`:

```bash
bin/dmtool -m /tmp/struct2.dm.json group read /Subscription/Addons
```

```output
{
  "target" : "group",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read /Subscription/Addons",
  "data" : {
    "group" : "/Subscription/Addons",
    "repeatable" : true
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.repeatable: true` says `/Subscription/Addons` is a repetition list. A plain sub-group would read `false`.

## group remove — drop a whole sub-tree

`group remove` deletes the group and everything under it. Like `field remove`, it runs the reference check first and refuses (exit 2) a group still referenced by a rule or computation unless you pass `--cascade`. `/Subscription/Addons` is unreferenced, so it goes cleanly — `changed.cascaded` is empty, meaning nothing else had to come with it:

```bash
bin/dmtool -m /tmp/struct2.dm.json group remove /Subscription/Addons
```

```output
{
  "target" : "group",
  "op" : "remove",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "removed /Subscription/Addons",
  "changed" : {
    "removed" : "/Subscription/Addons",
    "cascaded" : [ ]
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2.dm.json"
}
```

→ `changed.removed` is the group; `changed.cascaded: [ ]` confirms it took nothing with it. Had a rule referenced anything inside the sub-tree, the verb would have refused with the same `RK_REFERENCED` gate the field-remove section showed.

## typedef — a reusable type, then a field that uses it

A type definition is a named, reusable type you declare once (`typedef add --id <id> --kind <KIND>`) and then point fields at (`field add --typedef <id>`). We work on a fresh copy so the reads above stay intact.

```bash
cp cli/src/test/resources/models/subscription.dm.json /tmp/struct2-td.json && echo "copied subscription → /tmp/struct2-td.json"
```

```output
copied subscription → /tmp/struct2-td.json
```

`typedef add` declares the reusable type over an easy `--kind`:

```bash
bin/dmtool -m /tmp/struct2-td.json typedef add --id Currency --kind NUMBER
```

```output
{
  "target" : "typedef",
  "op" : "add",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added typedef Currency",
  "changed" : {
    "added" : "Currency",
    "kind" : "NUMBER"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-td.json"
}
```

→ `changed.added = Currency` over `kind: NUMBER`: the model now carries a reusable type named `Currency`.

`typedef read` lists the declared ids under `data.typedefs` (a non-mutating query):

```bash
bin/dmtool -m /tmp/struct2-td.json typedef read
```

```output
{
  "target" : "typedef",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read 1 type definition(s)",
  "data" : {
    "typedefs" : [ "Currency" ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.typedefs = [ "Currency" ]` is the inventory of reusable types you can point a field at.

Now type a field by that definition with `field add --typedef Currency` (instead of `--kind`). The field's kind comes back as `TypeDefType` — it carries the typedef, not a primitive kind:

```bash
bin/dmtool -m /tmp/struct2-td.json field add --group /Subscription/Billing --name SetupFee --typedef Currency
```

```output
{
  "target" : "field",
  "op" : "add",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added field /Subscription/Billing/SetupFee",
  "changed" : {
    "added" : "/Subscription/Billing/SetupFee",
    "kind" : "TypeDefType"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-td.json"
}
```

→ `changed.kind = TypeDefType`: `SetupFee` is typed *by* the `Currency` definition, not by a bare `NUMBER`.

`typedef remove` deletes a definition — but the kernel rejects (exit 1) removing one a field still uses, because that would leave the field's type dangling. With `SetupFee` still referencing `Currency`, the removal is refused:

```bash
bin/dmtool -m /tmp/struct2-td.json typedef remove Currency >/tmp/struct2-td-rej.json 2>&1; echo "(exit $?)"; cat /tmp/struct2-td-rej.json
```

```output
(exit 1)
{
  "target" : "typedef",
  "op" : "remove",
  "outcome" : "rejected",
  "ok" : false,
  "summary" : "removal left the model invalid",
  "diagnostics" : [ {
    "severity" : "ERROR",
    "source" : "PRECHECK",
    "summary" : "the edited model is not kernel-valid",
    "where" : { }
  } ],
  "written" : false
}
```

→ `outcome: rejected`, exit `1`: the edited model would not be kernel-valid (a field typed by a missing definition). Nothing was written. Remove the using field first, then the typedef goes cleanly:

```bash
bin/dmtool -m /tmp/struct2-td.json field remove /Subscription/Billing/SetupFee | jq -c "{outcome,ok}"
bin/dmtool -m /tmp/struct2-td.json typedef remove Currency
```

```output
{"outcome":"applied","ok":true}
{
  "target" : "typedef",
  "op" : "remove",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "removed typedef Currency",
  "changed" : {
    "removed" : "Currency"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-td.json"
}
```

→ With no field using it, `typedef remove` lands `outcome: applied`. The order matters: the kernel won't let you orphan a field's type.

## include — mount another model's content

An include declares a reference to another model (`--alias` → `--reference`) and mounts its content as a group (`--name`) under `--parent`. The referenced model is resolved by its header id from `--include-dir`. We stage a `/tmp` copy of `subscription` plus a tiny library directory holding the committed `catalog` model:

```bash
cp cli/src/test/resources/models/subscription.dm.json /tmp/struct2-sub.json
mkdir -p /tmp/struct2-lib && cp cli/src/test/resources/models/multifile/lib/catalog.dm.json /tmp/struct2-lib/catalog.dm.json
echo "staged sub + lib"
```

```output
staged sub + lib
```

One catch worth knowing: the **included model must cover the host's locales**, or the kernel refuses the merged model. `subscription` declares `en_US` *and* `de_DE`; the committed `catalog` declares only `en_US`. So we first widen the library copy's locales to match the host (a one-line `jq` edit on the `/tmp` copy — never the committed fixture):

```bash
jq ".header.locales = [{\"code\":\"en_US\"},{\"code\":\"de_DE\"}]" /tmp/struct2-lib/catalog.dm.json > /tmp/struct2-catalog-bi.json && mv /tmp/struct2-catalog-bi.json /tmp/struct2-lib/catalog.dm.json && jq -c ".header.locales" /tmp/struct2-lib/catalog.dm.json
```

```output
[{"code":"en_US"},{"code":"de_DE"}]
```

Now `include add` resolves `catalog` from `--include-dir` and mounts it under `/Subscription` as `Catalog`:

```bash
bin/dmtool -m /tmp/struct2-sub.json include add --alias catalog --reference catalog --parent /Subscription --name Catalog --include-dir /tmp/struct2-lib
```

```output
{
  "target" : "include",
  "op" : "add",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added include catalog",
  "changed" : {
    "added" : "catalog",
    "reference" : "catalog",
    "mountedAt" : "/Subscription/Catalog"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-sub.json"
}
```

→ `changed.mountedAt = /Subscription/Catalog`: the catalog model's content now hangs under that path. The kernel re-bound the merged model on the way out, so a locale gap (or a missing reference) would have surfaced as diagnostics, not a silent write.

`include read` lists the header's declared includes under `data.includes` — no `--include-dir` needed, it reports what the model *declares*, not the resolved content:

```bash
bin/dmtool -m /tmp/struct2-sub.json include read
```

```output
{
  "target" : "include",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read 1 include(s)",
  "data" : {
    "includes" : [ {
      "alias" : "catalog",
      "reference" : "catalog"
    } ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ `data.includes` carries the `alias → reference` pair the header declares. `include remove <alias>` then drops the header reference *and* its mounts:

```bash
bin/dmtool -m /tmp/struct2-sub.json include remove catalog --include-dir /tmp/struct2-lib
```

```output
{
  "target" : "include",
  "op" : "remove",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "removed include catalog",
  "changed" : {
    "removed" : "catalog"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-sub.json"
}
```

→ `changed.removed = catalog`: the reference and the `/Subscription/Catalog` mount are both gone. (A rule still using the include would block the removal, the same reference-gate pattern as the field/group removes.)

## config — read and modify the document settings

`config read` reports the document-level settings (decimal separator, time zone, condition language) under `.data`; `config modify` changes them in place. We use a fresh copy so the comma-edit doesn't bleed into the other sections.

```bash
cp cli/src/test/resources/models/subscription.dm.json /tmp/struct2-cfg.json && echo "copied subscription → /tmp/struct2-cfg.json"
```

```output
copied subscription → /tmp/struct2-cfg.json
```

```bash
bin/dmtool -m /tmp/struct2-cfg.json config read
```

```output
{
  "target" : "config",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read document config",
  "data" : {
    "decimalSeparator" : ".",
    "timeZone" : "Europe/Berlin",
    "conditionLanguage" : "en_US",
    "fieldRefByShortNameAllowed" : true
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ The fixture ships with a point decimal separator. That setting governs how numeric literals are written and parsed, so it's the one to get right for a German-facing model. `config modify --decimal-separator ","` flips it to a comma:

```bash
bin/dmtool -m /tmp/struct2-cfg.json config modify --decimal-separator ","
```

```output
{
  "target" : "config",
  "op" : "modify",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "changed document config",
  "changed" : {
    "decimalSeparator" : ","
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-cfg.json"
}
```

→ `changed.decimalSeparator = ","` — the only field that moved. A read-back confirms it persisted:

```bash
bin/dmtool -m /tmp/struct2-cfg.json config read | jq -c ".data"
```

```output
{"decimalSeparator":",","timeZone":"Europe/Berlin","conditionLanguage":"en_US","fieldRefByShortNameAllowed":true}
```

→ The comma stuck; the other settings are untouched. (`config modify` also takes `--time-zone` and `--condition-language`; at least one of the three is required.)

## export — the artifacts, not the envelope

`export` is the odd one out: it is a **root verb** that takes the model as a **positional** argument (`bin/dmtool export <model>`), and it prints a plain artifact — *not* the JSON result envelope. With no artifact name it emits the **model card** (a Markdown summary); `fields | groups | rules` print line-delimited JSON instead, and `--out-dir` writes all four to files.

```bash
cp cli/src/test/resources/models/subscription.dm.json /tmp/struct2-exp.json && echo "copied subscription → /tmp/struct2-exp.json"
```

```output
copied subscription → /tmp/struct2-exp.json
```

```bash
bin/dmtool export /tmp/struct2-exp.json
```

```output
# Model: subscription (v28.4.0)

- fields: 8
- groups: 3 (1 repeatable)
- rules: 0

## Groups

- /Subscription
- /Subscription/Addons  (repeatable)
- /Subscription/Billing

## Enums

- /Subscription/Tier: BASIC, PRO, ENTERPRISE
```

→ The card is a scannable digest of the whole model: field/group/rule counts, the group tree (marking the repeatable one), and enum value lists. Note the shape break — there is **no envelope** here; `export` is for handing the model summary to a human or another tool, so it speaks Markdown/JSONL, not the `target/op/outcome` envelope the editing verbs return.
