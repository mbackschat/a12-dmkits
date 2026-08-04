# dmtool CLI — packaged public-contract journey

*2026-08-03T19:00:00Z by Showboat 0.6.1*
<!-- showboat-id: 21d71042-7d21-4d8c-9b73-8f845d0cf9a3 -->

This is the shortest complete authoring journey through the installed `dmtool`: create a model, set up its fields atomically, preview and persist a computation, read it back, check the persisted model, and compute a document instance. The same journey runs in `PackagedDmtoolJourneyTest` against the exact packaged launcher, so the command spelling, distribution, provenance, result envelopes, mutation guarantees, and interpreter route are one public contract.

Start with a new model written to a temporary workspace. The projection keeps the captured output independent of the absolute checkout path.

```bash
mkdir -p /tmp/dmtool-public-contract
rm -f /tmp/dmtool-public-contract/order.dm.json
dmtool model new \
  --id order --locale en_US --root Order \
  -o /tmp/dmtool-public-contract/order.dm.json \
  | jq -c '{outcome,written,output:(.output|endswith("/order.dm.json"))}'
```

```output
{"outcome":"applied","written":true,"output":true}
```

Set up the source and target fields as one atomic `apply` transaction.

```bash
cat > /tmp/dmtool-public-contract/setup.json <<'EOF'
[
  {"target":"field","op":"add","group":"/Order","name":"BaseFee","kind":"NUMBER"},
  {"target":"field","op":"add","group":"/Order","name":"EffectiveFee","kind":"NUMBER"}
]
EOF
dmtool -m /tmp/dmtool-public-contract/order.dm.json \
  apply /tmp/dmtool-public-contract/setup.json \
  | jq -c '{outcome,committed:.data.committed,ops:.data.ops,written}'
```

```output
{"outcome":"applied","committed":true,"ops":2,"written":true}
```

Author the computation in the published JSON shape. Its first invocation is a byte-level dry run: the result says `preview`, and the SHA-256 digest proves the file did not change.

```bash
cat > /tmp/dmtool-public-contract/computation.json <<'EOF'
{
  "computedField":"/Order/EffectiveFee",
  "alternatives":[{"operation":"[/Order/BaseFee] * 2"}],
  "messages":[{"locale":"en_US","text":"Effective fee computed."}]
}
EOF
before=$(shasum -a 256 /tmp/dmtool-public-contract/order.dm.json | cut -d ' ' -f 1)
dmtool -m /tmp/dmtool-public-contract/order.dm.json \
  computation add /tmp/dmtool-public-contract/computation.json --dry-run \
  | jq -c '{outcome,written,verification}'
after=$(shasum -a 256 /tmp/dmtool-public-contract/order.dm.json | cut -d ' ' -f 1)
test "$before" = "$after" && echo 'model unchanged: true'
```

```output
{"outcome":"preview","written":false,"verification":"KERNEL_CONFIRMED"}
model unchanged: true
```

That `written:false` is a *contract* claim, so the tool publishes the claim's boundary instead of leaving it to prose. The commit profile declares `NO_WRITE`, and `contractVocabulary` — carried by every verb that can write — says which failures that covers and which two it does not.

```bash
dmtool schema computation add | jq -r '
  (.operationalProfiles[] | select(.id=="commit") | "commit: \(.effect), failure=\(.failurePersistence)"),
  .contractVocabulary.failurePersistence.scope'
```

```output
commit: MODEL_WRITE, failure=NO_WRITE
These values describe what survives a failure this tool detects and reports: a rejected invocation, a domain refusal, and a failing child op. They are not claims about an I/O failure during the write itself, or about abnormal process termination — every destination is truncated and rewritten in place, never atomically replaced, so in those two cases the destination is not guaranteed to be left unchanged. Re-verify an interrupted run with `dmtool model check`.
```

Persist the same accepted spec, then read its canonical stored form.

```bash
dmtool -m /tmp/dmtool-public-contract/order.dm.json \
  computation add /tmp/dmtool-public-contract/computation.json \
  | jq -c '{outcome,computation:.changed.computation,written}'
dmtool -m /tmp/dmtool-public-contract/order.dm.json \
  computation read /Order/EffectiveFeeComp \
  | jq -c '{outcome,computation:.data.computation,field:.data.calculatedField}'
```

```output
{"outcome":"applied","computation":"/Order/EffectiveFeeComp","written":true}
{"outcome":"read","computation":"/Order/EffectiveFeeComp","field":"/Order/EffectiveFee"}
```

The persisted result passes the kernel consistency gate.

```bash
dmtool -m /tmp/dmtool-public-contract/order.dm.json model check \
  | jq -c '{outcome,valid,verification}'
```

```output
{"outcome":"read","valid":true,"verification":"KERNEL_CONFIRMED"}
```

Finally, run the computation over A12's canonical nested Document JSON. Runtime evaluation identifies the interpreter that produced the value.

```bash
cat > /tmp/dmtool-public-contract/instance.json <<'EOF'
{"Order":{"BaseFee":10}}
EOF
dmtool -m /tmp/dmtool-public-contract/order.dm.json \
  model compute --instance /tmp/dmtool-public-contract/instance.json \
  | jq -c '{outcome,engine,field:.data.computed[0].field,value:.data.computed[0].value}'
```

```output
{"outcome":"read","engine":"DM_INTERPRETER","field":"Order/EffectiveFee","value":"20"}
```

This journey deliberately crosses every public boundary that unit tests can miss: launcher discovery, argument spelling, A12 document shape, schema-shaped input, kernel provenance for static consistency, interpreter provenance for runtime computation, no-write preview, persisted read-back, and final model validity.
