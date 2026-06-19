# dmtool CLI — editing model structure (field add · group add · field remove)

*2026-06-11T21:02:45Z by Showboat 0.6.1*
<!-- showboat-id: af0727e1-6f18-458a-ba7a-84938e1b2b93 -->

The structure-edit companion to the [verb tour](cli-tour.md) (which reads rules): this demo **edits the model's structure** — add a field, add a group, and **safely remove** a referenced field. These mutating verbs write **in place** (`-o` redirects, `--dry-run` previews) and carry the result envelope's `.changed`. Every command is `dmtool -m <model> <target> <op>`, run from the repo root; some steps also use `jq`. Re-check the captured output with `uvx showboat@0.6.1 verify examples/cli-structure-edit.md` (exit 0 = it still matches the live CLI).

We work on a `/tmp` copy of the `order-ruled` fixture — its `DeliveryNotBeforeOrder` rule references `/Order/DeliveryDate`, which is what arms the safe-delete gate below — so the committed fixture stays put.

```bash
cp examples/models/order-ruled.dm.json /tmp/struct.dm.json && echo "copied order-ruled → /tmp/struct.dm.json"
```

```output
copied order-ruled → /tmp/struct.dm.json
```

## model new — or start one from scratch (no `-m`)

We copied an existing fixture above, but a model doesn't have to come from somewhere — `model new` is the one model-*originating* verb. It takes `--id`, `--locale`, and a root group name (no `-m` — it makes one), and prints the model JSON to stdout (`-o` writes a file).

```bash
dmtool model new --id catalog --locale en_US --root Catalog \
  | jq -c '{id: .header.id, root: .content.modelRoot.rootGroups[0].name}'
```

```output
{"id":"catalog","root":"Catalog"}
```

What it emits is **kernel-checked**, so the fresh model binds immediately — write it with `-o`, then edit it like any other:

```bash
dmtool model new --id catalog --locale en_US --root Catalog -o /tmp/created.dm.json \
  && echo "created /tmp/created.dm.json"
dmtool -m /tmp/created.dm.json model validate | jq -c '{ok, valid, fields: (.data.fields|length)}'
```

```output
created /tmp/created.dm.json
{"ok":true,"valid":true,"fields":0}
```

→ A valid, empty model (zero fields) — the starting point the verbs below populate.

## field add — a typed field into a group

`field add` drops a `--kind`-typed field under a `--group`. It emits the result envelope: `outcome: applied`, the new path under `.changed.added`, and `written: true` with the `output` path — the write landed **in place** (a copy of the model file). `--dry-run` would preview the same envelope with `written: false`.

```bash
dmtool -m /tmp/struct.dm.json field add --group /Order --name Discount --kind NUMBER
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
dmtool -m /tmp/struct.dm.json group add --parent /Order --name Notes --repeatable 5
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
dmtool -m /tmp/struct.dm.json field remove /Order/DeliveryDate >/tmp/struct-refusal.json 2>&1; echo "(exit $?)"; cat /tmp/struct-refusal.json
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
dmtool -m /tmp/struct.dm.json field remove /Order/DeliveryDate --cascade
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
dmtool -m /tmp/struct.dm.json model validate | jq -c "{outcome,valid,diagnostics}"
```

```output
{"outcome":"read","valid":true,"diagnostics":[]}
```

→ `valid: true` with no diagnostics: adding two structures and cascading the date away left a model the kernel still accepts.

## reading the shape — field read · group read

The two reads tell you what a path *is* before you edit it: `field read <fieldPath>` returns the field's declared `type`, `group read <groupPath>` returns whether the group repeats. Both land their answer under `.data` and write nothing (`written: false`). We work from a fresh copy of the `subscription` fixture — its `/Subscription/Billing/BaseFee` field and repeatable `/Subscription/Addons` group give us something to inspect.

```bash
cp examples/models/subscription.dm.json /tmp/struct2.dm.json && echo "copied subscription → /tmp/struct2.dm.json"
```

```output
copied subscription → /tmp/struct2.dm.json
```

`field read` resolves the field's full name-path and reports its kernel `type` (here `NumberType`):

```bash
dmtool -m /tmp/struct2.dm.json field read /Subscription/Billing/BaseFee
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
dmtool -m /tmp/struct2.dm.json group read /Subscription/Addons
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
dmtool -m /tmp/struct2.dm.json group remove /Subscription/Addons
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
cp examples/models/subscription.dm.json /tmp/struct2-td.json && echo "copied subscription → /tmp/struct2-td.json"
```

```output
copied subscription → /tmp/struct2-td.json
```

`typedef add` declares the reusable type over an easy `--kind`:

```bash
dmtool -m /tmp/struct2-td.json typedef add --id Currency --kind NUMBER
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
dmtool -m /tmp/struct2-td.json typedef read
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
dmtool -m /tmp/struct2-td.json \
  field add --group /Subscription/Billing --name SetupFee --typedef Currency
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
dmtool -m /tmp/struct2-td.json typedef remove Currency >/tmp/struct2-td-rej.json 2>&1; echo "(exit $?)"; cat /tmp/struct2-td-rej.json
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
dmtool -m /tmp/struct2-td.json field remove /Subscription/Billing/SetupFee | jq -c "{outcome,ok}"
dmtool -m /tmp/struct2-td.json typedef remove Currency
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

### typedef rename — a structural refactor (id + every using field, gated)

Renaming a type definition rewrites its declaration **and** every field that points at it, in one step. A typedef never appears in a condition, so this is a pure *structural* rewrite — no rule edits, no AST surgery. The refactor **safety gate** (CLI-SPEC §6) runs first: a new id that is already declared is **refused** before anything changes. On a fresh copy with `Currency` typing a new `SetupFee`:

```bash
cp examples/models/subscription.dm.json /tmp/struct2-tdr.json && \
  dmtool -m /tmp/struct2-tdr.json typedef add --id Currency --kind NUMBER >/dev/null && \
  dmtool -m /tmp/struct2-tdr.json field add --group /Subscription/Billing --name SetupFee --typedef Currency >/dev/null && \
  echo "ready: Currency declared, SetupFee typed by it"
```

```output
ready: Currency declared, SetupFee typed by it
```

`typedef rename Currency --to Money` moves the id and repoints the using field — `changed.repointedFields` names exactly what moved:

```bash
dmtool -m /tmp/struct2-tdr.json typedef rename Currency --to Money
```

```output
{
  "target" : "typedef",
  "op" : "rename",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "renamed typedef Currency to Money",
  "changed" : {
    "renamed" : "Currency",
    "to" : "Money",
    "repointedFields" : [ "/Subscription/Billing/SetupFee" ]
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-tdr.json"
}
```

→ `changed.repointedFields = [ "/Subscription/Billing/SetupFee" ]`: the declaration became `Money` and the field followed in the same write. The kernel re-validate confirms nothing dangles.

The gate refuses a new id that is already taken — *before* any mutation. With a second typedef `Fee` present, renaming `Money → Fee` would collide, so it is refused (`RK_NAME_EXISTS`, exit 2, nothing written):

```bash
dmtool -m /tmp/struct2-tdr.json typedef add --id Fee --kind NUMBER >/dev/null
dmtool -m /tmp/struct2-tdr.json typedef rename Money --to Fee >/tmp/tdr-refuse.json 2>&1; echo "(exit $?)"; cat /tmp/tdr-refuse.json
```

```output
(exit 2)
{
  "target" : "typedef",
  "op" : "rename",
  "outcome" : "refused",
  "ok" : false,
  "summary" : "'Fee' already exists — refusing",
  "diagnostics" : [ {
    "severity" : "ERROR",
    "source" : "PRECHECK",
    "code" : "RK_NAME_EXISTS",
    "summary" : "a type definition named 'Fee' already exists",
    "where" : {
      "path" : "Fee"
    },
    "fix" : "choose a new id that is not already declared, or remove the existing 'Fee' first"
  } ],
  "written" : false
}
```

→ `outcome: refused`, exit 2, with a corrective `fix` — and the model is untouched. This is the standing refactor contract (CLI-SPEC §6): **check before you change; if it isn't safe, refuse with a diagnostic and touch nothing.**

### typedef extract / inline — factor a shared type out, or fold it back

`typedef extract` is the DRY refactor: point several fields that already share **one concrete type** at a single new definition, in one step. On a fresh copy with two like-typed fee fields:

```bash
cp examples/models/subscription.dm.json /tmp/struct2-tdx.json && \
  dmtool -m /tmp/struct2-tdx.json field add --group /Subscription/Billing --name SetupFee --kind NUMBER >/dev/null && \
  dmtool -m /tmp/struct2-tdx.json field add --group /Subscription/Billing --name LateFee --kind NUMBER >/dev/null && \
  echo "ready: two NUMBER fee fields under /Subscription/Billing"
```

```output
ready: two NUMBER fee fields under /Subscription/Billing
```

```bash
dmtool -m /tmp/struct2-tdx.json \
  typedef extract \
  --from /Subscription/Billing/SetupFee,/Subscription/Billing/LateFee \
  --id Money
```

```output
{
  "target" : "typedef",
  "op" : "extract",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "extracted 2 field(s) into typedef Money",
  "changed" : {
    "created" : "Money",
    "fromFields" : [ "/Subscription/Billing/SetupFee", "/Subscription/Billing/LateFee" ]
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-tdx.json"
}
```

→ both fields are now typed by the new `Money` definition. The gate **never coerces** — hand it fields that don't share a concrete type and it refuses (`RK_TYPE_MISMATCH`) before touching anything:

```bash
dmtool -m /tmp/struct2-tdx.json typedef extract --from /Subscription/Billing/BaseFee,/Subscription/PlanName --id X >/tmp/x.json 2>&1; echo "(exit $?)"; jq -c "{outcome, code: .diagnostics[0].code, fix: .diagnostics[0].fix}" /tmp/x.json
```

```output
(exit 2)
{"outcome":"refused","code":"RK_TYPE_MISMATCH","fix":"pass --from fields that exist and have the same type (none already a typedef)"}
```

`typedef inline` is the inverse — expand the definition's concrete type back into each using field, then drop it:

```bash
dmtool -m /tmp/struct2-tdx.json typedef inline Money
```

```output
{
  "target" : "typedef",
  "op" : "inline",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "inlined typedef Money",
  "changed" : {
    "inlined" : "Money",
    "expandedFields" : [ "/Subscription/Billing/SetupFee", "/Subscription/Billing/LateFee" ]
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/struct2-tdx.json"
}
```

→ `changed.expandedFields` got their inline `NUMBER` type back and `Money` is gone — extract and inline round-trip.

## rename — reference-preserving rename

`field | group | rule rename <path> --to <newName>` renames an element's declaration **and rewrites every reference to it** — rule conditions, error texts, computations — via the kernel's own `MoveSupportDM`, then re-validates. So a *referenced* field renames cleanly; the gate refuses only a **name collision** (`RK_NAME_EXISTS`). On `order-ruled` (whose `DeliveryNotBeforeOrder` rule references `/Order/DeliveryDate`):

```bash
cp examples/models/order-ruled.dm.json /tmp/struct2-rn.json && echo "copied order-ruled → /tmp/struct2-rn.json"
```

```output
copied order-ruled → /tmp/struct2-rn.json
```

Renaming the **referenced** field rewrites the referrer and stays valid — `changed.rewroteReferences` names what followed the rename:

```bash
dmtool -m /tmp/struct2-rn.json field rename /Order/DeliveryDate --to ShipDate \
  | jq -c '{outcome, ok, changed}'
```

```output
{"outcome":"applied","ok":true,"changed":{"renamed":"/Order/DeliveryDate","to":"/Order/ShipDate","rewroteReferences":["/Order/DeliveryNotBeforeOrder"]}}
```

The `DeliveryNotBeforeOrder` condition now reads `/Order/ShipDate` where it read `/Order/DeliveryDate`, so the model still validates — a true reference-preserving rename, not a refusal:

```bash
dmtool -m /tmp/struct2-rn.json model validate | jq -c '{valid}'
```

```output
{"valid":true}
```

A name **collision** is still refused (the gate's one remaining block) — `RK_NAME_EXISTS`, exit 2, nothing written:

```bash
dmtool -m /tmp/struct2-rn.json field rename /Order/Quantity --to OrderDate >/tmp/rn-coll.json 2>&1; echo "(exit $?)"; jq -c '{outcome, code: .diagnostics[0].code, fix: .diagnostics[0].fix}' /tmp/rn-coll.json
```

```output
(exit 2)
{"outcome":"refused","code":"RK_NAME_EXISTS","fix":"choose a name not already used by a sibling, or rename/remove the existing one first"}
```

→ `group rename` re-homes the whole subtree **and** rewrites references into it from outside (starred references like `Sum(Items*/Amount)` follow too — KERNEL-FINDINGS §10); `rule rename` is collision-only (nothing references a rule). The rewriting engine is the kernel's `MoveSupportDM`, wrapped at the adapter boundary (`ReferenceRewriter`) — the same engine that powers `move`, next.

## move — relocate to a different parent (reference-preserving)

`field | group move <path> --to <newParentGroup>` re-homes an element under a different parent **and rewrites every reference to its new path** (the same `MoveSupportDM` engine as `rename`), then re-validates. Continuing on `/tmp/struct2-rn.json` — where the field is now `/Order/ShipDate`, referenced by `DeliveryNotBeforeOrder` — relocate it into the `ShippingAddress` subgroup:

```bash
dmtool -m /tmp/struct2-rn.json field move /Order/ShipDate --to /Order/ShippingAddress \
  | jq -c '{outcome, ok, changed}'
```

```output
{"outcome":"applied","ok":true,"changed":{"moved":"/Order/ShipDate","to":"/Order/ShippingAddress/ShipDate","rewroteReferences":["/Order/DeliveryNotBeforeOrder"]}}
```

The rule's error-entity path and its condition both followed the field — `DifferenceInDays(OrderDate, ShippingAddress/ShipDate)` now — so the model still validates:

```bash
dmtool -m /tmp/struct2-rn.json model validate | jq -c '{valid}'
```

```output
{"valid":true}
```

The move gate refuses before touching anything: a **missing destination** is `RK_NO_SUCH_GROUP` (exit 2, nothing written) — likewise a name collision at the destination (`RK_NAME_EXISTS`) and a group moved into its own subtree (`RK_MOVE_INTO_SELF`):

```bash
dmtool -m /tmp/struct2-rn.json field move /Order/Quantity --to /Order/Nope >/tmp/mv-refuse.json 2>&1; echo "(exit $?)"; jq -c '{outcome, code: .diagnostics[0].code, fix: .diagnostics[0].fix}' /tmp/mv-refuse.json
```

```output
(exit 2)
{"outcome":"refused","code":"RK_NO_SUCH_GROUP","fix":"pass --to an existing group's full name-path (see `group read` or `model describe`)"}
```

→ `move` relocates the node, rewrites every reference to it (error-entity paths *and* condition operands, absolute or relative), and re-validates — gated, never a silent dangle. A `group move` re-homes the whole subtree the same way.

## extract — group → include (path-preserving)

`group extract <path> --reference <id>` lifts a subtree into its own sub-model and re-mounts it **at the same name**, so every path is preserved — references into the subtree keep resolving without any condition being rewritten. It writes **two** files: the slimmed base and `<reference>.dm.json` beside it. On `order-aggregates` (which has a `/Order/BillingAddress` subgroup with its own rule):

```bash
mkdir -p /tmp/struct2-ext && cp examples/models/order-aggregates.dm.json /tmp/struct2-ext/order.dm.json && echo "ready: order-aggregates in /tmp/struct2-ext"
```

```output
ready: order-aggregates in /tmp/struct2-ext
```

```bash
dmtool -m /tmp/struct2-ext/order.dm.json \
  group extract /Order/BillingAddress --reference billing-address \
  | jq -c '{outcome, ok, changed: {extracted: .changed.extracted, reference: .changed.reference, mountedAt: .changed.mountedAt}}'
```

```output
{"outcome":"applied","ok":true,"changed":{"extracted":"/Order/BillingAddress","reference":"billing-address","mountedAt":"/Order/BillingAddress"}}
```

→ the subtree (and its own rule) moved into `billing-address.dm.json`; the base now **mounts** it at the same path. Two files, the base slimmed to a `modelAlias`:

```bash
ls /tmp/struct2-ext && jq -c '[.. | objects | select(has("modelAlias")) | .modelAlias]' /tmp/struct2-ext/order.dm.json
```

```output
billing-address.dm.json
order.dm.json
["billing-address"]
```

The composed pair re-validates through the real kernel — the base resolves the sub-model by id from the same directory (and the gate already re-validated *before* writing, so a bad split never reaches disk):

```bash
dmtool -m /tmp/struct2-ext/order.dm.json model validate | jq -c '{valid}'
```

```output
{"valid":true}
```

The inverse, `include inline`, folds a mounted include's content back into the base as a real group and drops the reference — so extract and inline **round-trip**:

```bash
dmtool -m /tmp/struct2-ext/order.dm.json include inline billing-address \
  | jq -c '{outcome, ok, changed}'
```

```output
{"outcome":"applied","ok":true,"changed":{"inlined":"billing-address","reference":"billing-address"}}
```

```bash
jq -c '[.. | objects | select(has("modelAlias")) | .modelAlias]' /tmp/struct2-ext/order.dm.json && dmtool -m /tmp/struct2-ext/order.dm.json model validate | jq -c '{valid}'
```

```output
[]
{"valid":true}
```

→ `BillingAddress` is a real group in the base again — no mount, self-contained, valid. The extract gate refuses what can't extract cleanly: a **repeatable** group (`RK_REPEATABLE_INCLUDE_ROOT`) and a subtree whose rule references **outside** it (`RK_OUTWARD_REFERENCE`). A rule *outside* the subtree pointing *in* is fine — the mount preserves the path.

## include — mount another model's content

An include declares a reference to another model (`--alias` → `--reference`) and mounts its content as a group (`--name`) under `--parent`. The referenced model is resolved by its header id from `-w/--workspace`. We stage a `/tmp` copy of `subscription` plus a tiny library directory holding the committed `catalog` model:

```bash
cp examples/models/subscription.dm.json /tmp/struct2-sub.json
mkdir -p /tmp/struct2-lib && cp examples/models/multifile/lib/catalog.dm.json /tmp/struct2-lib/catalog.dm.json
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

Now `include add` resolves `catalog` from `-w/--workspace` and mounts it under `/Subscription` as `Catalog`:

```bash
dmtool -m /tmp/struct2-sub.json \
  include add \
  --alias catalog --reference catalog \
  --parent /Subscription --name Catalog \
  -w /tmp/struct2-lib
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

`include read` lists the header's declared includes under `data.includes` — no `-w/--workspace` needed, it reports what the model *declares*, not the resolved content:

```bash
dmtool -m /tmp/struct2-sub.json include read
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
dmtool -m /tmp/struct2-sub.json include remove catalog -w /tmp/struct2-lib
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
cp examples/models/subscription.dm.json /tmp/struct2-cfg.json && echo "copied subscription → /tmp/struct2-cfg.json"
```

```output
copied subscription → /tmp/struct2-cfg.json
```

```bash
dmtool -m /tmp/struct2-cfg.json config read
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
    "fieldRefByShortNameAllowed" : true,
    "supportedCharacters" : [ ],
    "locales" : [ "en_US", "de_DE" ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ The fixture ships with a point decimal separator. That setting governs how numeric literals are written and parsed, so it's the one to get right for a German-facing model. `config modify --decimal-separator ","` flips it to a comma:

```bash
dmtool -m /tmp/struct2-cfg.json config modify --decimal-separator ","
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
dmtool -m /tmp/struct2-cfg.json config read | jq -c ".data"
```

```output
{"decimalSeparator":",","timeZone":"Europe/Berlin","conditionLanguage":"en_US","fieldRefByShortNameAllowed":true,"supportedCharacters":[],"locales":["en_US","de_DE"]}
```

→ The comma stuck; the other settings are untouched. (`config modify` also takes `--time-zone` and `--condition-language`; at least one of the three is required.) Note `locales: ["en_US", "de_DE"]` — this model is **bilingual**, so a new rule or computation must carry both an `en_US` and a `de_DE` message.

## export — the artifacts, not the envelope

`export` takes the model via the universal `-m` like every other verb (`dmtool -m <model> export [<artifact>]`), but it prints a plain artifact — *not* the JSON result envelope. With no artifact name it emits the **model card** (a Markdown summary); `fields | groups | rules` print line-delimited JSON instead, and `--out-dir` writes all four to files.

```bash
cp examples/models/subscription.dm.json /tmp/struct2-exp.json && echo "copied subscription → /tmp/struct2-exp.json"
```

```output
copied subscription → /tmp/struct2-exp.json
```

```bash
dmtool -m /tmp/struct2-exp.json export
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

## Group templates — multi-select & attachment

A12 has two canonical group **templates**, flagged by a group's `usageType`. Rather than hand-assemble them (and get the composition wrong), one verb expands the whole thing. The rest of this tour works on a fresh copy:

```bash
cp examples/models/order-ruled.dm.json /tmp/extras.dm.json && echo "copied order-ruled → /tmp/extras.dm.json"
```

```output
copied order-ruled → /tmp/extras.dm.json
```

`group multiselect` expands a repeatable group holding one enum/string selection field — you supply the field + `--max-rows`, the generator owns the `usageType` skeleton:

```bash
dmtool -m /tmp/extras.dm.json \
  group multiselect \
  --parent /Order --name Conditions --field Condition \
  --values NEW,USED,REFURBISHED --max-rows 3
```

```output
{
  "target" : "group",
  "op" : "multiselect",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added multi-select group /Order/Conditions",
  "changed" : {
    "added" : "/Order/Conditions",
    "field" : "Condition",
    "maxRows" : 3
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/extras.dm.json"
}
```

`group attachment` expands the kernel's canonical file-attachment composition whole — 8 named fields + 4 mandated rules + the `content` config — and you tune only the knobs (`--max-size-bytes`, `--allowed-mime`, `--categories`, `--repeatable`). The mandated rules are authored in the model's condition language, so it is `en_US`/`de_DE`-only (else it refuses, not crashes):

```bash
dmtool -m /tmp/extras.dm.json \
  group attachment \
  --parent /Order --name Invoice \
  --max-size-bytes 10000000 \
  --allowed-mime application/pdf,image/png
```

```output
{
  "target" : "group",
  "op" : "attachment",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "added attachment group /Order/Invoice",
  "changed" : {
    "added" : "/Order/Invoice",
    "template" : "attachment"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/extras.dm.json"
}
```

## Element metadata — describe & annotate anything

`meta <ref>` reads or edits an element's **internal/external descriptions** and **annotations** — uniformly across a field, group, rule (by name-path), or type-def (by id). With write flags it sets/removes them:

```bash
dmtool -m /tmp/extras.dm.json \
  meta /Order/CustomerName \
  --internal en_US="the customer's full legal name" \
  --annotate owner=orders-team
```

```output
{
  "target" : "meta",
  "op" : "modify",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "changed metadata of /Order/CustomerName",
  "changed" : {
    "internal.en_US" : "the customer's full legal name",
    "annotation.owner" : "orders-team"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/extras.dm.json"
}
```

With **no write flags** it reads back — so the agent learns the output shape by running it:

```bash
dmtool -m /tmp/extras.dm.json meta /Order/CustomerName
```

```output
{
  "target" : "meta",
  "op" : "read",
  "outcome" : "read",
  "ok" : true,
  "valid" : true,
  "summary" : "read metadata of /Order/CustomerName",
  "data" : {
    "internalDescription" : {
      "en_US" : "the customer's full legal name"
    },
    "externalDescription" : { },
    "annotations" : {
      "owner" : "orders-team"
    }
  },
  "diagnostics" : [ ],
  "written" : false
}
```

The same verb reaches a **rule** (not just fields/groups) — the metadata is element-wide:

```bash
dmtool -m /tmp/extras.dm.json \
  meta /Order/DeliveryNotBeforeOrder \
  --annotate ticket=JIRA-42 \
  --internal en_US="guards: delivery must not precede order"
```

```output
{
  "target" : "meta",
  "op" : "modify",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "changed metadata of /Order/DeliveryNotBeforeOrder",
  "changed" : {
    "internal.en_US" : "guards: delivery must not precede order",
    "annotation.ticket" : "JIRA-42"
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/extras.dm.json"
}
```

A reference that doesn't exist is a structured `refused` envelope — coded + with a `fix`, never a bare error:

```bash
dmtool -m /tmp/extras.dm.json meta /Order/Nope --annotate k=v
```

```output
{
  "target" : "meta",
  "op" : "modify",
  "outcome" : "refused",
  "ok" : false,
  "summary" : "element or type definition '/Order/Nope' does not exist — refusing",
  "diagnostics" : [ {
    "severity" : "ERROR",
    "source" : "PRECHECK",
    "code" : "RK_NO_SUCH_ELEMENT",
    "summary" : "no element or type definition at '/Order/Nope'",
    "where" : {
      "path" : "/Order/Nope"
    },
    "fix" : "pass an existing element's full name-path or a declared type-definition id (see `model describe`)."
  } ],
  "written" : false
}
```

## The model comment

`config modify --comment` sets a free-text model-level note (`modelInfo.comment`); `config read` surfaces it (alongside `supportedCharacters` — the runtime-enforced allowed-character set, set via `config modify --supported-character A-Z …`):

```bash
dmtool -m /tmp/extras.dm.json \
  config modify --comment "Customer order with delivery and eligibility rules."
```

```output
{
  "target" : "config",
  "op" : "modify",
  "outcome" : "applied",
  "ok" : true,
  "summary" : "changed document config",
  "changed" : {
    "comment" : "Customer order with delivery and eligibility rules."
  },
  "diagnostics" : [ ],
  "written" : true,
  "output" : "/tmp/extras.dm.json"
}
```

```bash
dmtool -m /tmp/extras.dm.json config read
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
    "fieldRefByShortNameAllowed" : true,
    "supportedCharacters" : [ ],
    "comment" : "Customer order with delivery and eligibility rules.",
    "locales" : [ "en_US" ]
  },
  "diagnostics" : [ ],
  "written" : false
}
```

→ Two more authoring verbs round out the model surface (multi-file, so shown via `-w/--workspace` rather than re-staged here): **`typedef import --reference <m>`** pulls another model's type definitions in by id (a `purpose=typeDefinitions` reference, not a mount), and **`include add --exclude-rules`** mounts a model while dropping its own rules/computations. Both are in `manifest` + `--help`; the contracts are in [`../docs/CLI-SPEC.md`](../docs/CLI-SPEC.md) §5/§6.

## field modify — change a field in place (re-type or edit metadata)

Changing an *existing* field — adding a constraint, or fixing its label — no longer means deleting and re-creating it. `field modify` is a **partial** edit located by `group` + `name`: it applies every aspect the spec carries and leaves the rest untouched. A `kind` + per-kind config re-types it (e.g. enforce that a postal code is exactly 5 digits); the `changed.aspects` list reports what was touched:

```bash
dmtool model new --id addr --locale en_US --root Address -o /tmp/addr.dm.json >/dev/null
dmtool -m /tmp/addr.dm.json field add --group /Address --name PostalCode --kind STRING >/dev/null
printf '%s' '{"group":"/Address","name":"PostalCode","kind":"STRING","string":{"pattern":"[0-9]{5}","patternMessage":"The postal code must be exactly 5 digits."}}' > /tmp/plz.spec.json
dmtool -m /tmp/addr.dm.json field modify /tmp/plz.spec.json | jq -c '{outcome, changed}'

```

```output
{"outcome":"applied","changed":{"modified":"/Address/PostalCode","kind":"STRING","aspects":["type"]}}
```

The same verb edits field **metadata** in place — here just the label, with no re-type (so no `kind`). A spec carrying an aspect is never silently ignored; one that would change nothing is refused:

```bash
printf '%s' '{"group":"/Address","name":"PostalCode","label":{"en_US":"Postal code"}}' > /tmp/plz.meta.json
dmtool -m /tmp/addr.dm.json field modify /tmp/plz.meta.json | jq -c '{outcome, changed}'

```

```output
{"outcome":"applied","changed":{"modified":"/Address/PostalCode","aspects":["label"]}}
```
