# dmtool CLI — JSON Schema ⇄ model (import-jsonschema · export-jsonschema)

*2026-06-28T15:55:59Z by Showboat 0.6.1*
<!-- showboat-id: 6df8a195-f0d1-4d40-8f2e-be1eee1f5a9e -->

The interop companion to the [verb tour](cli-tour.md): this demo converts **between JSON Schema and the a12 Document Model**. The two directions are deliberately **asymmetric** — *import* aims for **total coverage** (every JSON Schema feature maps to a12 structure or rules, or is *omitted with a corrective to-do*, never silently), while *export* is **best-effort** (structure goes native; rules ride `x-a12-*` carriage; a few field kinds fall back to `string`). Both keep **artifact mode**: the result (DM-JSON / JSON Schema) goes to stdout so it pipes onward, and the **transcoding report** rides stderr. Every command runs from the repo root; re-check the captured output with `uvx showboat@0.6.1 verify examples/cli-jsonschema.md` (exit 0 = it still matches the live CLI). Full mapping tables: [docs/SCHEMAKIT-SPEC.md §4d](../docs/SCHEMAKIT-SPEC.md).

We work in `/tmp` so the repo stays put. First, a small JSON Schema:

```bash
echo '{ "title": "Order", "type": "object", "required": ["id"], "properties": { "id": { "type": "string" }, "quantity": { "type": "integer" }, "status": { "type": "string", "enum": ["NEW","SHIPPED"] } } }' > /tmp/order.schema.json && echo "wrote /tmp/order.schema.json"
```

```output
wrote /tmp/order.schema.json
```

## import-jsonschema — JSON Schema → a kernel-valid model

`import-jsonschema` turns the schema into a model. The model id comes from the schema's `title`; each property becomes a field of the matching kernel type (`string`→StringType, `integer`→NumberType scale 0, `enum`→EnumerationType). Print it to stdout to see the **imported model** — each element carries the kernel type it became, and the enum's values rode along:

```bash
dmtool model import-jsonschema --schema /tmp/order.schema.json 2>/dev/null \
  | jq -c '.content.modelRoot.rootGroups[0].Group.elements[] | {id, type: .Field.fieldType.type, enum: (.Field.fieldType.EnumerationType.values // [] | map(.value))}'
```

```output
{"id":"/Root/id","type":"StringType","enum":[]}
{"id":"/Root/quantity","type":"NumberType","enum":[]}
{"id":"/Root/status","type":"EnumerationType","enum":["NEW","SHIPPED"]}
```

With `-o` the model goes to a file and stdout carries a write-confirmation envelope. The result is **kernel-valid by construction** (and the schema's `required: ["id"]` rode along — you'll see it round-trip on export below):

```bash
dmtool model import-jsonschema --schema /tmp/order.schema.json -o /tmp/order.dm.json 2>/dev/null \
  | jq -c '{outcome, written}'
dmtool -m /tmp/order.dm.json model check | jq -c '{ok, valid}'
```

```output
{"outcome":"applied","written":true}
{"ok":true,"valid":true}
```

## Best-effort by default — import everything, flag every guess

Import is **best-effort**: it maps as much as possible rather than dropping what it's unsure of, and *flags every guess*. This matters most for structural containers — an array with no `maxItems` is **unbounded**, but omitting it would prune its whole nested subtree, so the default caps it and **reaches in**. Take a schema with a bare `number` (no a12 scale) and an unbounded `array` of objects:

```bash
echo '{ "title": "Catalog", "type": "object", "properties": {
        "price": { "type": "number" },
        "tags":  { "type": "array", "items": { "type": "object", "properties": { "label": { "type": "string" } } } } } }' \
  > /tmp/catalog.schema.json
dmtool model import-jsonschema --schema /tmp/catalog.schema.json -o /tmp/catalog.dm.json 2>/dev/null | jq -c '{outcome}'
dmtool -m /tmp/catalog.dm.json model describe | jq -c '.data.fields[] | {path, kind}'
```

```output
{"outcome":"applied"}
{"path":"/Root/price","kind":"NUMBER"}
{"path":"/Root/tags/label","kind":"STRING"}
```

→ The number imported (at a default scale), and the unbounded array imported **capped** — so its nested `label` field was *reached* instead of the whole `tags` subtree being lost. Neither guess is silent; both ride the **stderr** report:

```bash
dmtool model import-jsonschema --schema /tmp/catalog.schema.json 2>&1 1>/dev/null
```

```output
transcoding report: 3 mapped, 0 omitted, 2 note(s)
  - number 'price' has no scale in the schema — imported at the default scale 2 (a guess; set --default-scale, --number-scale, or the field's scale by hand if a different precision is needed)
  - 'tags' has no maxItems — capped at the default 1000 rows (a chosen bound, not from the schema); set maxItems, --default-repeat-cap, or --strict
```

## --strict — omit everything uncertain (the conservative stance)

The opposite stance is one flag. `--strict` omits everything it would have guessed — and because the omissions are an agent's **to-do list**, they're machine-readable in the `-o` envelope's `changed.report` (not only stderr):

```bash
dmtool model import-jsonschema --schema /tmp/catalog.schema.json --strict -o /tmp/catalog-strict.dm.json 2>/dev/null \
  | jq -c '.changed.report'
```

```output
{"mapped":0,"omitted":2,"notes":["property 'price' is type 'number' (unbounded decimal) — omitted: an a12 number is always scale-bounded. TO DO: add the field by hand with a chosen scale, or set the MappingProfile numberScale to DEFAULT_SCALE / INTEGER","array 'tags' has no maxItems and no default cap — omitted: a12 group repeatability is a bounded max ≥ 2. TO DO: set maxItems, raise --default-repeat-cap, or add by hand"]}
```

→ Between the two extremes, per-decision flags tune each choice (`--number-scale`, `--array-of-scalar`, `--default-repeat-cap`, `--union`, `--format`, …) — each value's *implication* is in `dmtool model import-jsonschema --help` and `manifest` (see SCHEMAKIT-SPEC §4b).

## export-jsonschema — model → JSON Schema (and where it loses fidelity)

Export renders a model back as JSON Schema. The structure round-trips exactly — note `required` is preserved:

```bash
dmtool -m /tmp/order.dm.json model export-jsonschema 2>/dev/null \
  | jq -c '{type, properties: (.properties|keys), required}'
```

```output
{"type":"object","properties":["id","quantity","status"],"required":["id"]}
```

But export is **best-effort**, and a real model exposes the limits. Exporting the rule-bearing `order-ruled` fixture: its structure maps natively, its **rules ride `x-a12-rule` carriage** (lossless a12↔a12, opaque to foreign validators), but its **DATE and CONFIRM fields fall back to `string`** — a12 has those kinds, JSON Schema's `type` does not:

```bash
dmtool -m examples/models/order-ruled.dm.json model export-jsonschema 2>/dev/null \
  | jq -c '{type, fields: (.properties|length), carried_rules: (.["x-a12-rule"]|length)}'
```

```output
{"type":"object","fields":13,"carried_rules":3}
```

The report on stderr names every lossy disposition — here, the three field kinds with no native JSON Schema form:

```bash
dmtool -m examples/models/order-ruled.dm.json model export-jsonschema 2>&1 1>/dev/null
```

```output
transcoding report: 22 mapped, 0 omitted, 3 note(s)
  - kind 'CONFIRM' at '/Order/TermsConfirmed' is not mapped yet — string placeholder
  - kind 'DATE' at '/Order/OrderDate' is not mapped yet — string placeholder
  - kind 'DATE' at '/Order/DeliveryDate' is not mapped yet — string placeholder
```

→ The asymmetry in one line: **import** turns rules, strategies, and temporal formats into native a12 constructs; **export** round-trips the structure + Tier-A subset natively and carries the rest as `x-a12-*` (temporal/custom *fields* fall back to `string`). The full feature-by-feature coverage for both directions is tabulated in [docs/SCHEMAKIT-SPEC.md §4d](../docs/SCHEMAKIT-SPEC.md).
