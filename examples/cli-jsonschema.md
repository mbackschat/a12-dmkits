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

## The transcoding report — omissions are loud, never silent

Some JSON Schema features have no faithful a12 form — a bare `number` (no `multipleOf`) has no a12 scale, so it is **omitted by default** rather than silently coerced to an integer. The omission is reported on **stderr** with a corrective to-do (the model still imports — import never blocks):

```bash
echo '{ "title": "Pricing", "type": "object", "properties": { "price": { "type": "number" } } }' > /tmp/pricing.schema.json
dmtool model import-jsonschema --schema /tmp/pricing.schema.json 2>&1 1>/dev/null
```

```output
transcoding report: 0 mapped, 1 omitted, 1 note(s)
  - property 'price' is type 'number' (unbounded decimal) — omitted: an a12 number is always scale-bounded. TO DO: add the field by hand with a chosen scale, or set the MappingProfile numberScale to DEFAULT_SCALE / INTEGER
```

For a program (or an LLM agent) the omissions are the **to-do list** — what to complete by hand — so they are also **machine-readable**: with `-o`, the same report rides the write-envelope's `changed.report` (counts + the `notes` to-dos), in the structured channel an agent reads from stdout. No scraping stderr:

```bash
dmtool model import-jsonschema --schema /tmp/pricing.schema.json -o /tmp/pricing-raw.dm.json 2>/dev/null \
  | jq -c '.changed.report'
```

```output
{"mapped":0,"omitted":1,"notes":["property 'price' is type 'number' (unbounded decimal) — omitted: an a12 number is always scale-bounded. TO DO: add the field by hand with a chosen scale, or set the MappingProfile numberScale to DEFAULT_SCALE / INTEGER"]}
```

## Tuning the mapping — the MappingProfile flags

The choice-driven decisions are flags. Re-run the same schema with `--number-scale DEFAULT_SCALE` and the bare `number` now imports as a scale-2 NUMBER:

```bash
dmtool model import-jsonschema --schema /tmp/pricing.schema.json --number-scale DEFAULT_SCALE --default-scale 2 -o /tmp/pricing.dm.json 2>/dev/null \
  | jq -c '{outcome}'
dmtool -m /tmp/pricing.dm.json model describe | jq -c '.data.fields[] | {path, kind}'
```

```output
{"outcome":"applied"}
{"path":"/Root/price","kind":"NUMBER"}
```

→ Other flags tune the rest: `--array-of-scalar`, `--map`, `--union`, `--format`, `--boolean`, `--recursion-depth` (see SCHEMAKIT-SPEC §4b).

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
