# dmtool — versions & model-version compatibility

*2026-06-12T01:27:50Z by Showboat 0.6.1*
<!-- showboat-id: 27570390-855c-4fb8-91c4-1a8957c0a204 -->

A release knows **what it is** and **what it targets**, and says so on two peer surfaces: `--version` (human) and `manifest.version` (machine-readable). The kernel is the authority on whether a document model's version is compatible; `dmtool` is *tolerant but explicit* — it warns on a difference it can load and fails fast (with a stable code) on one it can't. Writing a model back never bumps its version.

**`--version`** — the five axes: the rulekit release, the kernel built-against / runtime, the catalog floor, and the document-model reference version.

```bash
dmtool --version

```

```output
a12-dmkits 0.1.0
  kernel: 30.8.1 (built) / 30.8.1 (runtime)
  catalog verified against: 30.8.1
  model version (reference): 28.4.0
```

The same facts machine-readably, so a cold agent reads them like any other manifest entry (`kernel.skewed` flags a runtime kernel newer than the catalog floor — F11, always informational).

```bash
dmtool manifest | jq .version

```

```output
{
  "rulekit": "0.1.0",
  "kernel": {
    "builtAgainst": "30.8.1",
    "runtime": "30.8.1",
    "skewed": false
  },
  "catalogVerifiedAgainst": "30.8.1",
  "modelVersion": "28.4.0"
}
```

## Loading a model with a different version

The kernel accepts an **older same-major** model and rejects a **newer or different-major** one. First, two variants of a fixture — one older, one newer:

```bash
python3 - <<'PY'
import json
base = json.load(open("examples/models/order-ruled.dm.json"))
for v in ("28.0.0", "99.0.0"):
    base["header"]["modelVersion"] = v
    json.dump(base, open(f"/tmp/dmver-{v}.json", "w"))
print("wrote /tmp/dmver-28.0.0.json (older, same major) and /tmp/dmver-99.0.0.json (newer)")
PY

```

```output
wrote /tmp/dmver-28.0.0.json (older, same major) and /tmp/dmver-99.0.0.json (newer)
```

**Tolerant** — the older same-major model still validates (`valid:true`, exit 0), and the difference is made explicit as an informational `RK_MODEL_VERSION_SKEW`:

```bash
dmtool -m /tmp/dmver-28.0.0.json model validate | jq -c '{valid, diagnostics: [.diagnostics[] | {severity, code}]}'

```

```output
{"valid":true,"diagnostics":[{"severity":"INFO","code":"RK_MODEL_VERSION_SKEW"}]}
```

**Fail-fast** — a newer model the kernel can't load is rejected (`valid:false`, exit 1); rulekit codes the kernel's version-mismatch as `RK_MODEL_VERSION_INCOMPATIBLE` so an agent can branch on it:

```bash
dmtool -m /tmp/dmver-99.0.0.json model validate > /tmp/dmver-out.json; echo "exit=$?"
jq -c '{valid, diagnostics: [.diagnostics[] | {severity, code, summary}]}' /tmp/dmver-out.json

```

```output
exit=1
{"valid":false,"diagnostics":[{"severity":"ERROR","code":"RK_MODEL_VERSION_INCOMPATIBLE","summary":"Version mismatch: Document model version is 99.0.0 and application version is 28.4.0."}]}
```

**No implicit bump** — editing a loaded model writes its version back unchanged (here `28.0.0`, never bumped to the reference `28.4.0`):

```bash
command cp /tmp/dmver-28.0.0.json /tmp/dmver-edit.json
dmtool -m /tmp/dmver-edit.json config modify --decimal-separator "," > /dev/null
echo "modelVersion after write-back: $(jq -r .header.modelVersion /tmp/dmver-edit.json)"

```

```output
modelVersion after write-back: 28.0.0
```
