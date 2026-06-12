# dmtool CLI — the apply session verb

*2026-06-11T23:51:39Z by Showboat 0.6.1*
<!-- showboat-id: ba00a911-930f-43c4-bd51-82507f783de5 -->

The **apply** verb is dmtool's *session* surface — the companion to the single-shot edit verbs in [`cli-structure-edit.md`](cli-structure-edit.md) and [`cli-edit-loop.md`](cli-edit-loop.md). It runs an **ordered array of op-records** against ONE model in a single session: one read → ordered surgery → one terminal kernel gate → one write, with **atomic rollback** (a mid-sequence failure writes NOTHING). Each op-record is `{target, op, …args}` — the same axes as the standalone verbs (`{target, op}` from `manifest`, the per-op keys from each verb's params) — *not* `batch`'s `{verb, args}`. The call is `bin/dmtool -m <model> apply <ops.json>` from the repo root; `--dry-run` runs the gate but writes nothing, `-o` redirects the write. **apply writes IN PLACE by default**, so every section below operates on a `/tmp` copy and leaves the committed fixture untouched. Each op emits a per-op envelope (edits land `staged`, reads land `read` + `data`); the wrapper is `{ops, committed, failedAt, written, results[]}`. Some steps also use `jq`. Re-check the captured output with `uvx showboat@0.6.1 verify examples/cli-apply.md` (exit 0 = it still matches the live CLI).

## Discover the frame — `schema apply`

A cold agent learns the op-record frame **from the tool**. `schema apply` returns apply's directional **input** contract: an *array* of op-records, each requiring `target` + `op` (`additionalProperties: true` — the per-op keys are that verb's params, discovered from `manifest` + `schema <target> <op>`), plus a **worked example**. We project the frame and the example with `jq`.

```bash
bin/dmtool schema apply | jq "{title, type, item_required: .items.required, example: .examples[0]}"
```

```output
{
  "title": "ApplyOps",
  "type": "array",
  "item_required": [
    "target",
    "op"
  ],
  "example": [
    {
      "target": "field",
      "op": "add",
      "group": "/Subscription",
      "name": "Note",
      "kind": "STRING"
    },
    {
      "target": "group",
      "op": "add",
      "parent": "/Subscription",
      "name": "Extras"
    }
  ]
}
```

→ The frame is an **ordered array**: each item needs `target` + `op`, and the worked example shows two op-records (`field add` then `group add`) — the same shape the standalone verbs take. `apply` is distinct from `batch`: `batch` runs stateless `{verb, args}` dispatches, `apply` runs a *single session* of `{target, op, …args}` surgery with one terminal gate.

## Atomic multi-op — a field AND the rule that guards it

The north-star: one `apply` that **adds a field and the rule guarding it together**, committed atomically. On `order-ruled` (en_US-only), we add a `Discount` number and a rule that fires when it's present and negative. The ops file is a JSON array of op-records; we write it to `/tmp` and operate on a `/tmp` copy of the fixture.

```bash
cp cli/src/test/resources/models/order-ruled.dm.json /tmp/apply-atomic.dm.json
cat > /tmp/apply-atomic-ops.json <<'JSON'
[ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
  {"target":"rule","op":"add","field":"/Order/Discount","code":"DISCOUNT_NOT_NEGATIVE",
    "condition":"FieldFilled(/Order/Discount) And [/Order/Discount] < 0",
    "messages":[{"locale":"en_US","text":"Discount must not be negative."}]} ]
JSON
echo "ops file written: $(jq length /tmp/apply-atomic-ops.json) op-records"
```

```output
ops file written: 2 op-records
```

```bash
bin/dmtool -m /tmp/apply-atomic.dm.json apply /tmp/apply-atomic-ops.json
```

```output
{
  "verb" : "apply",
  "ops" : 2,
  "committed" : true,
  "failedAt" : null,
  "written" : true,
  "output" : "/tmp/apply-atomic.dm.json",
  "results" : [ {
    "target" : "field",
    "op" : "add",
    "outcome" : "staged",
    "ok" : true,
    "summary" : "staged field add",
    "changed" : {
      "added" : "/Order/Discount",
      "kind" : "NUMBER"
    },
    "diagnostics" : [ ],
    "written" : false
  }, {
    "target" : "rule",
    "op" : "add",
    "outcome" : "staged",
    "ok" : true,
    "summary" : "staged rule add",
    "changed" : {
      "rule" : "/Order/DISCOUNT_NOT_NEGATIVE"
    },
    "diagnostics" : [ ],
    "written" : false
  } ]
}
```

→ `committed: true`, `written: true` (`output` names the file) — the **whole sequence** passed the one terminal kernel gate and was written atomically. Note each `result` is `outcome: "staged"`, **not** `applied`: a per-op edit is *staged* in the session (`written: false` on each), and only the wrapper's terminal gate commits the lot. `changed.added` and `changed.rule` name what each op staged. The rule references `/Order/Discount` — a field that did not exist when the file was read; it works because op 0 staged it *first* in the same session.

```bash
bin/dmtool -m /tmp/apply-atomic.dm.json doc validate | jq -c "{valid, diagnostics}"
echo "--- field present: $(bin/dmtool -m /tmp/apply-atomic.dm.json doc describe | jq -c "[.data.fields[].path | select(test(\"Discount\"))]")"
echo "--- rule present: $(bin/dmtool export /tmp/apply-atomic.dm.json | grep -E "^- rules:")"
```

```output
{"valid":true,"diagnostics":[]}
--- field present: ["/Order/Discount"]
--- rule present: - rules: 4
```

→ The written model re-validates against the real kernel (`valid: true`), the `Discount` field is present, and the rule count rose (`rules: 4`, up from the fixture's 3). Two interdependent edits committed as one transaction.

## Read mid-sequence — the unified read+edit

A session can **mix edits and reads**. Here op 0 stages a `Discount` field; op 1 *reads it back* — and the read returns the **just-staged** state, because both ops see the same in-session model. (Read ops carry no `changed`; their payload rides `.data`, like the standalone read verbs.) The `field read` op-record key is `fieldPathInModel` (from `manifest`).

```bash
cp cli/src/test/resources/models/order-ruled.dm.json /tmp/apply-midread.dm.json
cat > /tmp/apply-midread-ops.json <<'JSON'
[ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
  {"target":"field","op":"read","fieldPathInModel":"/Order/Discount"} ]
JSON
bin/dmtool -m /tmp/apply-midread.dm.json apply /tmp/apply-midread-ops.json
```

```output
{
  "verb" : "apply",
  "ops" : 2,
  "committed" : true,
  "failedAt" : null,
  "written" : true,
  "output" : "/tmp/apply-midread.dm.json",
  "results" : [ {
    "target" : "field",
    "op" : "add",
    "outcome" : "staged",
    "ok" : true,
    "summary" : "staged field add",
    "changed" : {
      "added" : "/Order/Discount",
      "kind" : "NUMBER"
    },
    "diagnostics" : [ ],
    "written" : false
  }, {
    "target" : "field",
    "op" : "read",
    "outcome" : "read",
    "ok" : true,
    "valid" : true,
    "summary" : "read field read",
    "data" : {
      "field" : "/Order/Discount",
      "type" : "NumberType"
    },
    "diagnostics" : [ ],
    "written" : false
  } ]
}
```

→ Op 1 (`field read`) returns `outcome: "read"` with the field's state under `.data` — `{field: /Order/Discount, type: NumberType}` — the field op 0 just staged. The read **sees the staged surgery**, not the on-disk model. A read op also carries `valid` (the read/edit envelopes differ: edits expose `changed`, reads expose `valid` + `data`). The sequence had no failing op, so it still committed and wrote.

## Rollback on a failing op — atomic, nothing written

The session is **all-or-nothing**. Here op 0 stages a valid `Discount` field, but op 1 targets a **non-existent group** (`/Order/NoSuchGroup`). The op fails → the sequence stops, the wrapper reports `committed: false` and `failedAt: 1`, and **nothing is written** — including op 0's otherwise-valid edit. A failing op exits non-zero, so we capture the output explicitly.

```bash
cp cli/src/test/resources/models/order-ruled.dm.json /tmp/apply-rollback.dm.json
cat > /tmp/apply-rollback-ops.json <<'JSON'
[ {"target":"field","op":"add","group":"/Order","name":"Discount","kind":"NUMBER"},
  {"target":"field","op":"add","group":"/Order/NoSuchGroup","name":"Tax","kind":"NUMBER"} ]
JSON
bin/dmtool -m /tmp/apply-rollback.dm.json apply /tmp/apply-rollback-ops.json 2>&1; echo "(exit $?)"
```

```output
{
  "verb" : "apply",
  "ops" : 2,
  "committed" : false,
  "failedAt" : 1,
  "written" : false,
  "results" : [ {
    "target" : "field",
    "op" : "add",
    "outcome" : "staged",
    "ok" : true,
    "summary" : "staged field add",
    "changed" : {
      "added" : "/Order/Discount",
      "kind" : "NUMBER"
    },
    "diagnostics" : [ ],
    "written" : false
  }, {
    "target" : "field",
    "op" : "add",
    "outcome" : "rejected",
    "ok" : false,
    "summary" : "op 1 failed — sequence rolled back",
    "diagnostics" : [ {
      "severity" : "ERROR",
      "source" : "PRECHECK",
      "code" : "RK_APPLY_OP_FAILED",
      "summary" : "op 1 (field add) failed: no group at path: /Order/NoSuchGroup",
      "where" : { },
      "fix" : "fix this op and re-run; the whole sequence rolled back (nothing was written)"
    } ],
    "written" : false
  } ]
}
(exit 1)
```

```bash
echo "Discount occurrences in the file after rollback: $(grep -c "Discount" /tmp/apply-rollback.dm.json)"
```

```output
Discount occurrences in the file after rollback: 0
```

→ `committed: false`, `failedAt: 1`, `written: false`, exit `1`. Op 0 still shows `staged` (it ran), but op 1 is `rejected` with `RK_APPLY_OP_FAILED` naming the cause (`no group at path: /Order/NoSuchGroup`) and a `fix`. The grep proves the rollback: **0** occurrences of `Discount` in the file — op 0's staged edit was discarded along with everything else. The terminal gate is the *only* writer, and it never ran.

## Op-arg corrective — a typo'd key self-corrects

`apply` validates each op's args **before** dispatching it. A typo'd key — here `"grop"` instead of `"group"` — is caught and rejected with `RK_UNKNOWN_ARG` carrying a **did-you-mean** `fix`. The whole sequence rolls back (this is the only, failing op).

```bash
cp cli/src/test/resources/models/order-ruled.dm.json /tmp/apply-typo.dm.json
cat > /tmp/apply-typo-ops.json <<'JSON'
[ {"target":"field","op":"add","grop":"/Order","name":"Discount","kind":"NUMBER"} ]
JSON
bin/dmtool -m /tmp/apply-typo.dm.json apply /tmp/apply-typo-ops.json 2>&1; echo "(exit $?)"
```

```output
{
  "verb" : "apply",
  "ops" : 1,
  "committed" : false,
  "failedAt" : 0,
  "written" : false,
  "results" : [ {
    "target" : "field",
    "op" : "add",
    "outcome" : "rejected",
    "ok" : false,
    "summary" : "op 0 has an invalid arg — sequence rolled back",
    "diagnostics" : [ {
      "severity" : "ERROR",
      "source" : "PRECHECK",
      "code" : "RK_UNKNOWN_ARG",
      "summary" : "unknown arg 'grop' for op 'field add'",
      "where" : {
        "arg" : "grop"
      },
      "fix" : "did you mean 'group'?"
    } ],
    "written" : false
  } ]
}
(exit 1)
```

→ `failedAt: 0`, `outcome: "rejected"`, `written: false`. `RK_UNKNOWN_ARG` points `where.arg` at the offending key (`grop`) and the `fix` is the correction (`did you mean 'group'?`). A **bad op** is the sibling case — `RK_UNKNOWN_OP` lists the supported ops:

```bash
printf "%s" '[ {"target":"field","op":"bogus","group":"/Order","name":"X","kind":"NUMBER"} ]' > /tmp/apply-badop-ops.json
bin/dmtool -m /tmp/apply-typo.dm.json apply /tmp/apply-badop-ops.json 2>&1 | jq -c ".results[0].diagnostics[0] | {code, summary, fix: (.fix | .[0:40] + \"…\")}"; echo "(exit ${PIPESTATUS[0]})"
```

```output
{"code":"RK_UNKNOWN_OP","summary":"apply does not support op 'field bogus'","fix":"supported ops: computation add, computat…"}
(exit 1)
```

→ `RK_UNKNOWN_OP` rejects `field bogus` and the `fix` enumerates the supported `target op` pairs — so the agent discovers the valid set from the diagnostic itself. (We truncate the long `fix` for the demo.)

## Cross-type corrective — a batch op fed to apply

`apply` and `batch` take **different** op shapes: `apply` wants `{target, op, …args}`, `batch` wants `{verb, args}`. Feed `apply` a *batch-shaped* op (one with `verb`/`argv`) and it doesn't guess — it rejects with `RK_WRONG_BATCH_KIND`, pointing you at the `batch` verb.

```bash
cp cli/src/test/resources/models/order-ruled.dm.json /tmp/apply-xtype.dm.json
cat > /tmp/apply-xtype-ops.json <<'JSON'
[ {"verb":"field add","args":["--group","/Order","--name","Discount","--kind","NUMBER"]} ]
JSON
bin/dmtool -m /tmp/apply-xtype.dm.json apply /tmp/apply-xtype-ops.json 2>&1; echo "(exit $?)"
```

```output
{
  "verb" : "apply",
  "ops" : 1,
  "committed" : false,
  "failedAt" : 0,
  "written" : false,
  "results" : [ {
    "target" : "",
    "op" : "",
    "outcome" : "rejected",
    "ok" : false,
    "summary" : "op 0 has an invalid arg — sequence rolled back",
    "diagnostics" : [ {
      "severity" : "ERROR",
      "source" : "PRECHECK",
      "code" : "RK_WRONG_BATCH_KIND",
      "summary" : "this op has verb/argv — it is a `batch` op-record, not an `apply` op",
      "where" : { },
      "fix" : "use the `batch` verb for {verb,args} ops; `apply` ops are {target,op,…args}"
    } ],
    "written" : false
  } ]
}
(exit 1)
```

→ `RK_WRONG_BATCH_KIND` names the mistake (`verb/argv` → it's a `batch` op-record) and the `fix` points at the right verb. `apply` and `batch` are siblings — *session surgery* vs *stateless dispatch* — and the tool tells you when you've reached for the wrong one. Discover the apply frame anytime with `bin/dmtool schema apply` (the first section).
