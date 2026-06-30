# dmtool CLI — review & comprehend a model

The **review** surface of `dmtool`: comprehend a whole model in one read (`model report`), and review what an edit changed (`model diff`). Both are JSON-in / JSON-out and **tool-computed** — every fact is read from the model, never the agent's narration. Commands run through `dmtool` from the repo root; some steps use `jq`. Re-check with `uvx showboat@0.6.1 verify examples/cli-review.md` (exit 0 = output still matches the live CLI).

## model report — understand the whole model

`model report` assembles, in one read, a model's identity, a lean structure outline, the dead-field (unreferenced) set, and — the centerpiece — a **glossed catalog** of every rule and computation in plain language (next to its stored message). It's the comprehension companion to `model diff`. Polarity (an A12 rule fires on the *violation*) is the same fact for every rule, so it's carried **once** at the top. Projected here to the headline plus each rule's gloss.

```bash
dmtool -m examples/models/order-ruled.dm.json model report \
  | jq '{ summary, model: .data.identity.id, fields: (.data.structure.fields | length), polarity: .data.polarity.firesOn, rules: (.data.rules | map({rule, gloss})) }'
```

```output
{
  "summary": "3 rule(s), 0 computation(s); 11 unreferenced field(s)",
  "model": "order",
  "fields": 15,
  "polarity": "violation",
  "rules": [
    {
      "rule": "/Order/BillingAddress/PostalCodeFormat",
      "gloss": "Uses And, FieldFilled, PatternViolated over /Order/BillingAddress/PostalCode."
    },
    {
      "rule": "/Order/DeliveryNotBeforeOrder",
      "gloss": "Uses And, AllFieldsFilled, <, DifferenceInDays over /Order/DeliveryDate, /Order/OrderDate."
    },
    {
      "rule": "/Order/EligibilityCheck",
      "gloss": "Uses And, FieldFilled, CustomCondition over /Order/CustomerName."
    }
  ]
}
```

→ One read gives the whole picture: the model id, 15 fields, and all three rules in **plain language** — a postal-code pattern, the delivery-date order, an eligibility custom condition. `polarity: "violation"` is carried once (a rule's condition is true on the state to *reject*, not the requirement — the same fact for every rule, so it isn't repeated per entry). The full payload also carries the structure outline, the dead-field set, and each rule's **stored message** — so a reviewer sees what a rule *does* (the gloss) next to what it *claims* (the message). The full field→referrers map is `model usage` (report doesn't duplicate it).

## model diff — review what changed

When an agent edits a model on your behalf, raw JSON diffs are unreadable. `model diff <base> <head>` compares two model files **structurally** (it parses both into the model, so a pure reformat is never reported) and reports the fields and rules **added / removed / modified** — each with a **risk** tier. Risk follows *loosening > tightening*: a removed or loosened rule silently accepts bad data (HIGH), while a tightened or added one fails loudly (MEDIUM). Projected here to the headline plus the high-risk changes.

```bash
dmtool model diff examples/models/order-ruled.dm.json examples/models/order.dm.json \
  | jq '{ summary, highRisk: (.data.changes | map(select(.risk=="HIGH")) | map({id, change, riskReasons})) }'
```

```output
{
  "summary": "11 added, 3 removed, 0 modified (3 high-risk)",
  "highRisk": [
    {
      "id": "/Order/BillingAddress/PostalCodeFormat",
      "change": "REMOVED",
      "riskReasons": [
        "RULE_REMOVED"
      ]
    },
    {
      "id": "/Order/DeliveryNotBeforeOrder",
      "change": "REMOVED",
      "riskReasons": [
        "RULE_REMOVED"
      ]
    },
    {
      "id": "/Order/EligibilityCheck",
      "change": "REMOVED",
      "riskReasons": [
        "RULE_REMOVED"
      ]
    }
  ]
}
```

→ The headline is `11 added, 3 removed, 0 modified (3 high-risk)`, and the three high-risk changes are exactly the **deleted validation rules** (`RULE_REMOVED`) — the dangerous, silent case a reviewer must see first. Unlike `where-used`/`model usage` (which read *one* model), this compares *two* and is the **review** surface: what did this edit change, and what should I look at first? It's a *structural* semantic diff — the full contract is in [MODEL-REVIEW-SPEC](../docs/MODEL-REVIEW-SPEC.md).
