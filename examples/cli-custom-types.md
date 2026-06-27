# dmtool CLI — custom field types & custom conditions

*2026-06-27T19:58:28Z by Showboat 0.6.1*
<!-- showboat-id: 9b719c58-0b02-43de-b498-97163200a46f -->

Real document models lean on **custom constructs** — a `CustomFieldType` (an IBAN, an ISIN, a policy number) and a `CustomCondition` (logic the rule DSL can't express). Their validation logic is *project code* that lives in the deploying application, not in the model JSON, so a native, kernel-free `dmtool` cannot *run* it. Full-fidelity evaluation of arbitrary custom logic is the **library's** job (a JVM/JS consumer registers the real impl). What `dmtool` does instead is **author** such models (it's just text) and **test-run** them honestly — degrading visibly, and accepting *declarative* custom types so the common cases still validate. These runtime verbs default to the native-safe interpreter; output is captured by [showboat](https://github.com/simonw/showboat) (re-check with `uvx showboat@0.6.1 verify examples/cli-custom-types.md`).

## The model

`payment-customtype` is a tiny checkout-payment model: an `AccountHolder` string and an **`Iban` field of custom type `Iban`**. `model describe` shows the field's kind is `CUSTOM` — its values are validated by a project-supplied type, not a built-in.

```bash
dmtool -m examples/models/payment-customtype.dm.json model describe \
  | jq -c '.data.fields[] | {path, kind}'

```

```output
{"path":"/Payment/AccountHolder","kind":"STRING"}
{"path":"/Payment/Iban","kind":"CUSTOM"}
```

## Without the type: a bad value slips through — but is SURFACED

Here is a clearly-malformed IBAN. With no definition for the `Iban` type, the engine can't validate it — but instead of silently passing (which would read as "all good"), it reports the cell under **`data.unsupported`**, so you know it was *not* checked.

```bash
cat > /tmp/ct-bad.json <<'JSON'
{ "fields": { "Payment": { "AccountHolder": "Acme", "Iban": "NOT-AN-IBAN" } } }
JSON
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-bad.json \
  | jq -c '{fired: .data.fired, unsupported: .data.unsupported}'

```

```output
{"fired":[],"unsupported":[{"name":"/Payment[1]/Iban","reason":"custom field type 'Iban' has no registered validator"}]}
```

## Supply the type: now it validates

`--predefined-types <file>` supplies a declarative registry — length bounds and a regex — for the custom type. The format constraint is now enforced: the bad IBAN fires `customFieldTypeInvalid`, attributed to the offending cell.

```bash
cat > /tmp/ct-types.json <<'JSON'
{ "Iban": { "minLength": 15, "maxLength": 34, "pattern": "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}" } }
JSON
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-bad.json \
  --predefined-types /tmp/ct-types.json \
  | jq -c '{fired: .data.fired, msg: [.data.messages[] | {code, field}]}'

```

```output
{"fired":["customFieldTypeInvalid"],"msg":[{"code":"customFieldTypeInvalid","field":"/Payment[1]/Iban"}]}
```

A well-formed IBAN passes with the same registry — nothing fired, nothing unsupported:

```bash
cat > /tmp/ct-ok.json <<'JSON'
{ "fields": { "Payment": { "AccountHolder": "Acme", "Iban": "DE89370400440532013000" } } }
JSON
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-ok.json \
  --predefined-types /tmp/ct-types.json \
  | jq -c '{fired: .data.fired, unsupported: .data.unsupported}'

```

```output
{"fired":[],"unsupported":null}
```

## `--strict-custom`: fail instead of degrade

The lenient default lets you test-run a model that uses constructs the engine can't evaluate. When you instead want kernel fidelity — *fail* if anything couldn't be validated — add `--strict-custom`: a non-empty `unsupported` becomes a **refusal** (exit 2), nothing read.

```bash
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-bad.json --strict-custom \
  > /tmp/ct-strict.json 2>&1; echo "(exit $?)"
jq -c '{outcome, ok, summary}' /tmp/ct-strict.json

```

```output
(exit 2)
{"outcome":"refused","ok":false,"summary":"--strict-custom: 1 construct(s) could not be evaluated (a custom field type with no --predefined-types entry, or a CustomCondition with no impl); supply the missing handler or drop --strict-custom"}
```

## Custom conditions

A `CustomCondition` is, by definition, logic the DSL can't express — so it's genuinely project code, with no declarative substitute. The `order-ruled` model's `/Order/EligibilityCheck` rule uses `CustomCondition ExternalEligibility`. `rule test` reports it with a fourth verdict — **`unsupported`** — rather than a misleading pass or a crash:

```bash
cat > /tmp/ct-order.json <<'JSON'
{ "fields": { "Order": { "CustomerName": "Acme" } } }
JSON
dmtool -m examples/models/order-ruled.dm.json \
  rule test /Order/EligibilityCheck --instance /tmp/ct-order.json \
  | jq -c '{rule: .data.rule, verdict: .data.verdict, reason: .data.unsupported[0].reason}'

```

```output
{"rule":"/Order/EligibilityCheck","verdict":"unsupported","reason":"unregistered custom condition \"ExternalEligibility\" — register it in the custom-condition registry"}
```

So `dmtool` stays useful on models full of custom constructs: it **authors** them, **validates** the declarative custom types (`--predefined-types`), and is **honest** about the rest — surfacing what it couldn't evaluate (and failing on demand with `--strict-custom`) rather than reporting a false clean pass. For the imperative tail (checksums, real `CustomCondition` code), register the impl on the `:interpreter` library directly, or evaluate with `--kernel` on the JVM.

