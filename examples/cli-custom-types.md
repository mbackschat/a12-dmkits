# dmtool CLI — custom field types & custom conditions

*2026-06-27T21:13:11Z by Showboat 0.6.1*
<!-- showboat-id: 0d9765d1-2b87-406e-80d5-141de904cc63 -->

Custom field types and conditions carry project logic outside the model JSON. `dmtool` accepts declarative custom types through `--predefined-types`, or project JavaScript through `--custom-field-types-js` and `--custom-conditions-js`. Validation and computation run on the kernel-free interpreter; missing handlers are visible under `data.unsupported`, and `--strict-custom` turns them into a refusal. JVM/JS library consumers can register callbacks directly. Output is captured by [showboat](https://github.com/simonw/showboat) (re-check with `uvx showboat@0.6.1 verify examples/cli-custom-types.md`).

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

Here is a clearly-malformed IBAN. With no definition for the `Iban` type, the engine can't validate it — but instead of silently passing (which would read as "all good"), it reports the value under **`data.unsupported`**, so you know it was *not* checked.

```bash
cat > /tmp/ct-bad.json <<'JSON'
{ "Payment": { "AccountHolder": "Acme", "Iban": "NOT-AN-IBAN" } }
JSON
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-bad.json \
  | jq '{fired: .data.fired, unsupported: .data.unsupported}'

```

```output
{
  "fired": [],
  "unsupported": [
    {
      "name": "Payment/Iban",
      "subject": "cell",
      "reason": "custom field type 'Iban' has no registered validator"
    }
  ]
}
```

## Supply the type: now it validates

`--predefined-types <file>` supplies a declarative registry — length bounds and a regex — for the custom type. The format constraint is now enforced: the bad IBAN fires `customFieldTypeInvalid`, attributed to the offending value.

```bash
cat > /tmp/ct-types.json <<'JSON'
{ "Iban": { "minLength": 15, "maxLength": 34, "pattern": "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}" } }
JSON
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-bad.json \
  --predefined-types /tmp/ct-types.json \
  | jq '{fired: .data.fired, msg: [.data.messages[] | {code, field}]}'

```

```output
{
  "fired": [
    "customFieldTypeInvalid"
  ],
  "msg": [
    {
      "code": "customFieldTypeInvalid",
      "field": "Payment/Iban"
    }
  ]
}
```

A well-formed IBAN passes with the same registry — nothing fired, nothing unsupported:

```bash
cat > /tmp/ct-ok.json <<'JSON'
{ "Payment": { "AccountHolder": "Acme", "Iban": "DE89370400440532013000" } }
JSON
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-ok.json \
  --predefined-types /tmp/ct-types.json \
  | jq '{fired: .data.fired, unsupported: .data.unsupported}'

```

```output
{
  "fired": [],
  "unsupported": null
}
```

## `--strict-custom`: fail instead of degrade

The lenient default lets you test-run a model that uses constructs the engine can't evaluate. To refuse a result when a custom construct could not be evaluated, add `--strict-custom`: a non-empty `unsupported` becomes a **refusal** (exit 2), retaining the evaluation data so the missing support can be inspected.

```bash
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-bad.json --strict-custom \
  > /tmp/ct-strict.json 2>&1; echo "(exit $?)"
jq '{outcome, ok, summary}' /tmp/ct-strict.json

```

```output
(exit 2)
{
  "outcome": "refused",
  "ok": false,
  "summary": "--strict-custom: 1 construct(s) could not be evaluated (a custom field type with no --predefined-types entry, or a CustomCondition with no impl); supply the missing handler or drop --strict-custom"
}
```

## Custom conditions

A `CustomCondition` is, by definition, logic the DSL can't express — so it's genuinely project code, with no declarative substitute. The `order-ruled` model's `/Order/EligibilityCheck` rule uses `CustomCondition ExternalEligibility`. `rule eval` reports it with a fourth verdict — **`unsupported`** — rather than a misleading pass or a crash:

```bash
cat > /tmp/ct-order.json <<'JSON'
{ "Order": { "CustomerName": "Acme" } }
JSON
dmtool -m examples/models/order-ruled.dm.json \
  rule eval /Order/EligibilityCheck --instance /tmp/ct-order.json \
  | jq '{rule: .data.rule, verdict: .data.verdict, reason: .data.unsupported[0].reason}'

```

```output
{
  "rule": "/Order/EligibilityCheck",
  "verdict": "unsupported",
  "reason": "unregistered custom condition \"ExternalEligibility\" — register it in the custom-condition registry"
}
```

## The imperative tail — the JS escape

`--predefined-types` covers a custom field type whose validity is a *format* (length/pattern). But a real IBAN also has a **mod-97 checksum**, and a `CustomCondition` is by definition logic the DSL can't express — neither has a declarative form. For those, point `dmtool` at the project's **own JS** with `--custom-field-types-js <dir>` / `--custom-conditions-js <dir>`: each field-type `<Name>.js` exports `validate(value, context)`, where `context` contains the message locale, effective bounds, and stored/display mode; each condition exports `check(data, …)`. Both run through a single persistent **Node worker**. Here a project `Iban.js` verifies the omitted-bound defaults and stored-value mode, then rejects a value the pattern would have accepted with its own code and message template:

```bash
mkdir -p /tmp/ct-js
cat > /tmp/ct-js/Iban.js <<'JS'
// The project's real validator — here a stand-in for IBAN's mod-97: must start with a DE country code.
export function validate(value, context) {
  if (context.locale !== 'en_US' || context.minLength !== 1 ||
      context.maxLength !== 999 || context.isDisplayValue !== false) {
    throw new Error('unexpected custom-field context');
  }
  return value.startsWith('DE') && value.length >= 15
    ? { valid: true }
    : {
        valid: false,
        errorKey: 'invalidIban',
        errorMessage: 'Invalid $<fieldName>$',
      };
}
JS
cat > /tmp/ct-fr.json <<'JSON'
{ "Payment": { "AccountHolder": "Acme", "Iban": "FR7630006000011234567890189" } }
JSON
dmtool -m examples/models/payment-customtype.dm.json \
  model eval --instance /tmp/ct-fr.json \
  --custom-field-types-js /tmp/ct-js \
  | jq '{fired: .data.fired, msg: [.data.messages[] | {code, field, message}]}'

```

```output
{
  "fired": [
    "invalidIban"
  ],
  "msg": [
    {
      "code": "invalidIban",
      "field": "Payment/Iban",
      "message": "Invalid Iban"
    }
  ]
}
```

The object vocabulary is exactly `{ valid, errorKey?, errorMessage? }`; alias-only or wrongly typed objects are rejected rather than guessed. The worker **never outlives `dmtool`** (it self-exits when the parent's pipe closes — even on a hard kill — plus an idle timeout and a JVM shutdown hook). No `node` on PATH, a `.js` exception, or a malformed result leaves the construct visibly `unsupported` rather than crashing or reporting a false clean result.

So `dmtool` stays useful on models full of custom constructs: it **authors** them, **validates** the declarative custom types (`--predefined-types`), runs the **imperative** ones from the project's own JS (`--custom-field-types-js` / `--custom-conditions-js`), and is **honest** about anything still unhandled — surfacing it (and failing on demand with `--strict-custom`) rather than reporting a false clean pass. For full in-process fidelity, register the impls on the `:interpreter` library directly.

## Compute a custom target

A payment can compute its settlement account from a configured IBAN. Author the computation on a temporary model; its CUSTOM target uses the same validator registry as stored values. `model compute` reports the result without writing the Document.

```bash
cp examples/models/payment-customtype.dm.json /tmp/ct-computed.dm.json
cat > /tmp/ct-computation.json <<'JSON'
{
  "computedField": "/Payment/Iban",
  "alternatives": [{"operation": "\"DE89370400440532013000\""}],
  "messages": [{"locale": "en_US", "text": "Check the settlement account."}]
}
JSON
dmtool -m /tmp/ct-computed.dm.json computation add /tmp/ct-computation.json > /tmp/ct-added.json
dmtool -m /tmp/ct-computed.dm.json model compute --instance /tmp/ct-fr.json \
  --custom-field-types-js /tmp/ct-js --strict-custom \
  | jq '{computed: .data.computed, unsupported: .data.unsupported}'

```

```output
{
  "computed": [
    {
      "field": "Payment/Iban",
      "declaration": "Payment/Iban",
      "outcome": "value",
      "value": "DE89370400440532013000"
    }
  ],
  "unsupported": null
}
```

A computed target is checked even when its old stored value is absent. Missing target handlers therefore appear in `data.unsupported` and are refused by `--strict-custom`. A validator rejection remains a computation error; the exact reserved `testIDNummer` result preserves the attempted value for dependent computations, as measured in [KF541](../docs/KERNEL-FINDINGS.md#kf541).

```bash
dmtool -m /tmp/ct-computed.dm.json model compute --instance /tmp/ct-fr.json --strict-custom \
  > /tmp/ct-compute-strict.json; echo "(exit $?)"
jq '{outcome, unsupported: .data.unsupported}' /tmp/ct-compute-strict.json

```

```output
(exit 2)
{
  "outcome": "refused",
  "unsupported": [
    {
      "name": "Payment/Iban",
      "subject": "cell",
      "reason": "custom field type 'Iban' has no registered validator"
    }
  ]
}
```
