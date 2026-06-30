# dmtool CLI — JSON Schema ⇄ model (import-jsonschema · export-jsonschema)

*2026-06-28T15:55:59Z by Showboat 0.6.1*
<!-- showboat-id: 6df8a195-f0d1-4d40-8f2e-be1eee1f5a9e -->

The interop companion to the [verb tour](cli-tour.md): this demo converts **between JSON Schema and the a12 Document Model**. The two directions are deliberately **asymmetric** — *import* aims for **total coverage** (every JSON Schema feature maps to a12 structure or rules, or is *omitted with a corrective to-do*, never silently), while *export* is **best-effort** (structure goes native; rules ride `x-a12-*` carriage; a few field kinds fall back to `string`). Both keep **artifact mode**: the result (DM-JSON / JSON Schema) goes to stdout so it pipes onward, and the **transcoding report** rides stderr. Every command runs from the repo root; re-check the captured output with `uvx showboat@0.6.1 verify examples/cli-jsonschema.md` (exit 0 = it still matches the live CLI). Full mapping tables: [docs/SCHEMAKIT-SPEC.md §4d](../docs/SCHEMAKIT-SPEC.md).

We work in `/tmp` so the repo stays put. First, a small JSON Schema:

```bash
echo '{ "title": "Order", "type": "object", "required": ["id"],
        "properties": {
          "id":       { "type": "string" },
          "quantity": { "type": "integer" },
          "status":   { "type": "string", "enum": ["NEW","SHIPPED"] } } }' \
  > /tmp/order.schema.json && echo "wrote /tmp/order.schema.json"
```

```output
wrote /tmp/order.schema.json
```

## import-jsonschema — JSON Schema → a kernel-valid model

`import-jsonschema` turns the schema into a model. The model id comes from the schema's `title`; each property becomes a field of the matching kernel type (`string`→StringType, `integer`→NumberType scale 0, `enum`→EnumerationType). Print it to stdout to see the **imported model** — each element carries the kernel type it became, and the enum's values rode along:

```bash
dmtool model import-jsonschema --schema /tmp/order.schema.json 2>/dev/null \
  | jq -c '.content.modelRoot.rootGroups[0].Group.elements[]
           | {id, type: .Field.fieldType.type,
              enum: (.Field.fieldType.EnumerationType.values // [] | map(.value))}'
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
  - number 'price' has no scale in the schema — imported at the default scale 2 (a guess; set
    --default-scale, --number-scale, or the field's scale by hand if a different precision is
    needed)
  - 'tags' has no maxItems — capped at the default 1000 rows (a chosen bound, not from the
    schema); set maxItems, --default-repeat-cap, or --strict
```

## --strict — omit everything uncertain (the conservative stance)

The opposite stance is one flag. `--strict` omits everything it would have guessed — and because the omissions are an agent's **to-do list**, they're machine-readable in the `-o` envelope's `changed.report` (not only stderr):

```bash
dmtool model import-jsonschema --schema /tmp/catalog.schema.json --strict -o /tmp/catalog-strict.dm.json 2>/dev/null \
  | jq '.changed.report'
```

```output
{
  "mapped": 0,
  "omitted": 2,
  "notes": [
    "property 'price' is type 'number' (unbounded decimal) — omitted: an a12 number is always scale-bounded. TO DO: add the field by hand with a chosen scale, or set the MappingProfile numberScale to DEFAULT_SCALE / INTEGER",
    "array 'tags' has no maxItems and no default cap — omitted: a12 group repeatability is a bounded max ≥ 2. TO DO: set maxItems, raise --default-repeat-cap, or add by hand"
  ]
}
```

→ Between the two extremes, per-decision flags tune each choice (`--number-scale`, `--array-of-scalar`, `--default-repeat-cap`, `--union`, `--format`, …) — each value's *implication* is in `dmtool model import-jsonschema --help` and `manifest` (see SCHEMAKIT-SPEC §4b).

## Constraints become a12 rules

The blocks above mapped *structure* + field config. Import also turns **constraints with no field-config home into real a12 rules** — exclusive bounds, `const`, `dependentRequired`, `oneOf`/`anyOf` discriminators, `if`/`then`/`else`, `not`, and `contains`. A checkout schema mixing several:

```bash
echo '{ "title": "Checkout", "type": "object", "required": ["total"],
        "properties": {
          "total":    { "type": "number", "exclusiveMinimum": 0 },
          "currency": { "type": "string", "const": "EUR" },
          "tier":     { "type": "string", "enum": ["STANDARD","PREMIUM"] },
          "vatId":    { "type": "string" } },
        "if":   { "properties": { "tier": { "const": "PREMIUM" } } },
        "then": { "required": ["vatId"] } }' > /tmp/checkout.schema.json
dmtool model import-jsonschema --schema /tmp/checkout.schema.json -o /tmp/checkout.dm.json 2>/dev/null \
  | jq -c '{outcome}'
dmtool -m /tmp/checkout.dm.json rule read 2>/dev/null | jq -c '.data.rules'
```

```output
{"outcome":"applied"}
["/Root/totalExclusiveMin","/Root/currencyConst","/Root/vatIdRequiredWhenTierIsPREMIUM"]
```

Three constraints with no field-config home became **kernel-valid rules**. A synthesized rule **fires on the violation** — render two to see the polarity (the strict bound, and the conditional requiredness):

```bash
dmtool -m /tmp/checkout.dm.json rule format /Root/totalExclusiveMin 2>/dev/null | jq -r '.data.canonical'
dmtool -m /tmp/checkout.dm.json rule format /Root/vatIdRequiredWhenTierIsPREMIUM 2>/dev/null | jq -r '.data.canonical'
```

```output
FieldFilled(total)
And [total] <= 0
[tier] == "PREMIUM"
And FieldNotFilled(vatId)
```

→ `total` is in error when it's filled **and** `≤ 0` (the negation of `exclusiveMinimum: 0`); the `if/then` fires when `tier = PREMIUM` **and** `vatId` is absent. Each is authored through the typed rulekit DSL and kernel-gated, so it's well-formed by construction.

## OpenAPI dialects & whole-document bundles

Most schemas ship inside **OpenAPI**, where definitions live under `components/schemas` and a few keywords differ by version. The importer **auto-detects the dialect** and can import the **whole document** as many models — `--out-dir` writes one per component, wiring each object `$ref` as an a12 **mount** (single-sourced, not inlined):

```bash
echo '{ "openapi": "3.0.3", "info": { "title": "Shop", "version": "1.0" },
        "components": { "schemas": {
          "Address":  { "type": "object", "properties": {
            "city": { "type": "string" }, "zip": { "type": "string" } } },
          "Customer": { "type": "object", "required": ["id"], "properties": {
            "id":       { "type": "string" },
            "discount": { "type": "number", "minimum": 0, "exclusiveMinimum": true },
            "note":     { "type": "string", "nullable": true },
            "address":  { "$ref": "#/components/schemas/Address" } } } } } }' > /tmp/shop.openapi.json
dmtool model import-jsonschema --schema /tmp/shop.openapi.json --out-dir /tmp/shop-models 2>/dev/null \
  | jq -c '{outcome, models: [.changed.models[].id]}'
ls /tmp/shop-models
```

```output
{"outcome":"applied","models":["Address","Customer"]}
Address.dm.json
Customer.dm.json
```

The dialect (`OPENAPI_30`) was sniffed from the `openapi:` field, and its 3.0-isms were honored: `nullable: true` made `note` optional, and the **boolean** `exclusiveMinimum: true` (a 3.0 spelling — 3.1 uses a numeric value) became a strict-bound rule on `discount`. The per-component reports ride stderr; the Customer rule is real:

```bash
dmtool model import-jsonschema --schema /tmp/shop.openapi.json --out-dir /tmp/shop-models 2>&1 1>/dev/null
dmtool -m /tmp/shop-models/Customer.dm.json -w /tmp/shop-models rule read 2>/dev/null | jq -c '.data.rules'
```

```output
transcoding report: 2 mapped, 0 omitted, 1 note(s)
  - dialect: OPENAPI_30 (auto-detected)
transcoding report: 5 mapped, 0 omitted, 2 note(s)
  - dialect: OPENAPI_30 (auto-detected)
  - number 'discount' has no scale in the schema — imported at the default scale 2 (a guess; set
    --default-scale, --number-scale, or the field's scale by hand if a different precision is
    needed)
["/Root/discountExclusiveMin"]
```

→ `--dialect` forces a reading (`json-schema` · `openapi-30` · `openapi-31`, the last covering 3.2), and `--component <name>` picks a single entry instead of the whole bundle — both discoverable via `dmtool model import-jsonschema --help` and `dmtool manifest`.

## YAML in, model out

A schema often arrives as **YAML**; the importer tries JSON, then YAML:

```bash
printf 'title: Ticket\ntype: object\nproperties:\n  id: { type: string }\n  priority: { type: integer, minimum: 1, maximum: 5 }\n' > /tmp/ticket.yaml
dmtool model import-jsonschema --schema /tmp/ticket.yaml 2>/dev/null \
  | jq -c '.content.modelRoot.rootGroups[0].Group.elements[] | {id, kind: .Field.fieldType.type}'
```

```output
{"id":"/Root/id","kind":"StringType"}
{"id":"/Root/priority","kind":"NumberType"}
```

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

For an OpenAPI consumer, `--wrap-openapi` wraps the export as a `components/schemas` fragment ready to paste into an API document:

```bash
dmtool -m /tmp/order.dm.json model export-jsonschema --wrap-openapi 2>/dev/null \
  | jq -c '{keys: keys, schemas: (.components.schemas | keys)}'
```

```output
{"keys":["components","info","openapi","paths"],"schemas":["Order"]}
```

→ The asymmetry in one line: **import** turns rules, strategies, and temporal formats into native a12 constructs; **export** round-trips the structure + Tier-A subset natively and carries the rest as `x-a12-*` (temporal/custom *fields* fall back to `string`). The full feature-by-feature coverage for both directions is tabulated in [docs/SCHEMAKIT-SPEC.md §4d](../docs/SCHEMAKIT-SPEC.md).
