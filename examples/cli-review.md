# dmtool CLI — review & comprehend a model

*2026-06-30T00:06:51Z by Showboat 0.6.1*
<!-- showboat-id: c3ef0506-da6e-4eba-80d2-5ef7a81fe324 -->

The **review** surface of `dmtool`: comprehend a whole model in one read (`model report`), and review what an edit changed (`model diff`). Both are JSON-in / JSON-out and **tool-computed** — every fact is read from the model, never the agent's narration. Commands run through `dmtool` from the repo root; some steps use `jq`. Re-check with `uvx showboat@0.6.1 verify examples/cli-review.md` (exit 0 = output still matches the live CLI).

## model report — understand the whole model

`model report` assembles, in one read, a model's identity, a lean structure outline, the dead-field (unreferenced) set, and — the centerpiece — a **glossed catalog** of every rule and computation in plain language (next to its stored message). It's the comprehension companion to `model diff`. Polarity (an A12 rule fires on the *violation*) is the same fact for every rule, so it's carried **once** at the top. Projected here to the headline plus each rule's gloss.

```bash
dmtool -m examples/models/order-ruled.dm.json model report \
  | jq '{ summary, model: .data.identity.id,
          fields: (.data.structure.fields | length),
          polarity: .data.polarity.firesOn,
          rules: (.data.rules | map({rule, gloss})) }'
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

### The structure/comprehension tree — flat for agents, drawn for humans

`--tree` reprojects the report into one deterministic, document-ordered `nodes[]` list. `--root` and `--depth` keep the payload focused without inventing filter flags; every record carries its canonical path and depth, so an agent can query it directly and feed the same paths back to edit verbs. Here the depth-one projection shows the four groups and makes the repeatable iteration boundary explicit:

```bash
dmtool -m examples/models/order-ruled.dm.json model report --tree --root /Order --depth 1 \
  | jq '{summary, root:.data.root, counts:.data.summary, groups:[.data.nodes[] | select(.kind=="group") | {path, depth, repeatable, maxReps}]}'
```

```output
{
  "summary": "10 field(s), 4 group(s), 2 rule(s), 0 computation(s)",
  "root": "/Order",
  "counts": {
    "fields": 10,
    "groups": 4,
    "rules": 2,
    "computations": 0,
    "maxDepth": 1,
    "repeatableGroups": 1
  },
  "groups": [
    {
      "path": "/Order",
      "depth": 0,
      "repeatable": false,
      "maxReps": 1
    },
    {
      "path": "/Order/ShippingAddress",
      "depth": 1,
      "repeatable": false,
      "maxReps": 1
    },
    {
      "path": "/Order/BillingAddress",
      "depth": 1,
      "repeatable": false,
      "maxReps": 1
    },
    {
      "path": "/Order/Items",
      "depth": 1,
      "repeatable": true,
      "maxReps": 50
    }
  ]
}
```

The rich facets are opt-in. `--include rules,computations` anchors each rule once at its error entity and each computation once at its target; `--text` draws those exact flat records rather than switching to a second data model:

```bash
dmtool --text -m examples/models/order-ruled.dm.json model report \
  --tree --root /Order --depth 2 --include rules,computations
```

```output
15 field(s), 4 group(s), 3 rule(s), 0 computation(s)
  (read)

/Order  (group)
├─ CustomerName  STRING
│  └─ rule EligibilityCheck :  FieldFilled(CustomerName) And CustomCondition ExternalEligibility
├─ ProductCode  STRING
├─ Quantity  NUMBER scale0
├─ UnitPrice  NUMBER scale2
├─ BackorderQuantity  NUMBER scale0
├─ ExpressShipping  BOOLEAN
├─ TermsConfirmed  CONFIRM
├─ ShippingAddress  (group)
│  ├─ Street  STRING
│  └─ City  STRING
├─ BillingAddress  (group)
│  └─ PostalCode  STRING
│     └─ rule PostalCodeFormat :  FieldFilled(PostalCode) And [PostalCode] PatternViolated "[0-9]{5}"
├─ Items  (group · repeatable · max 50)
│  ├─ Sku  STRING
│  └─ Count  NUMBER scale0
├─ Priority  ENUMERATION {STANDARD, EXPRESS, OVERNIGHT} →SpeedClass{NORMAL,FAST}
├─ OrderDate  DATE
└─ DeliveryDate  DATE
   └─ rule DeliveryNotBeforeOrder :  AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < 0
fields 15 · groups 4 · rules 3 · computations 0 · repeatable 1
```

→ JSON stays flat and jq-able; only the boundary renderer spends characters on `├─/└─`. Large enum domains report a visible preview count and `truncated:true`; add `--include enum` for every value, or `descriptions,annotations` for the token-heavier authoring context.

## model diff — review what changed

When an agent edits a model on your behalf, raw JSON diffs are unreadable. `model diff <base> <head>` compares two model files **structurally** (it parses both into the model, so a pure reformat is never reported) and reports field, group, rule, computation, typedef, include/import, and model-configuration changes with a `risk` **review-impact tier**. Every delta preserves deterministic `before`/`after` strings and adds tagged `beforeValue`/`afterValue` data for services that need booleans, numbers, or string lists without reparsing. Review impact follows *loosening > tightening*: a removed or loosened constraint silently accepts more data (HIGH), while a tightened or added one fails loudly (MEDIUM). Projected here to the headline plus the high-impact changes.

```bash
dmtool model diff examples/models/order-ruled.dm.json examples/models/order.dm.json \
  | jq '{ summary, highRisk: (.data.changes | map(select(.risk=="HIGH")) | map({id, change, riskReasons})) }'
```

```output
{
  "summary": "11 added, 3 removed, 1 modified (3 high-risk)",
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

→ The headline is `11 added, 3 removed, 1 modified (3 high-risk)`: the one model-level modification is the head model's added base year, while the three high-impact changes are exactly the **deleted validation rules** (`RULE_REMOVED`) — the dangerous, silent case a reviewer must see first. Unlike `where-used`/`model usage` (which read *one* model), this compares *two* and is the **review** surface: what did this edit change, and what should I look at first? It's a *structural* semantic diff — the full contract is in [MODEL-REVIEW-SPEC](../docs/MODEL-REVIEW-SPEC.md).

### Multi-model states bind independently

An includes-bearing diff must bind each side against the dependency snapshot that belongs to it. `--base-workspace` and `--head-workspace` are repeatable and remain distinct even when both workspaces contain different revisions under the same model id; shared `-w` is the shorter form when dependencies are common. This no-change check uses the published multi-file fixture and proves that the referenced `catalog` model is resolved rather than treating `storefront` as self-contained:

```bash
dmtool model diff \
  --base-workspace examples/models/multifile --head-workspace examples/models/multifile \
  examples/models/multifile/app/storefront.dm.json examples/models/multifile/app/storefront.dm.json \
  | jq '{summary, changes: (.data.changes | length)}'
```

```output
{
  "summary": "0 added, 0 removed, 0 modified",
  "changes": 0
}
```

→ In an actual old-vs-new review, point the two options at the corresponding workspace roots. If each model and all of its dependencies live in its own folder, omit them; folder fallback remains per side.

### Computation behavior changes are first-class

A computation is not “just another stored rule” in the review output. Its target, preconditions, ordered operations, tolerances, suppressions, messages, and opaque DSL are compared as computation aspects. Here the effective fee changes from a pass-through to base fee plus one:

```bash
cat > /tmp/review-computation.json <<'EOF'
{
  "computedField": "/Subscription/Billing/EffectiveFee",
  "alternatives": [{ "operation": "[BaseFee] + 1" }],
  "messages": [
    { "locale": "en_US", "text": "Effective fee is base fee plus one." },
    { "locale": "de_DE", "text": "Effektivgebuehr ist Basisgebuehr plus eins." }
  ]
}
EOF
cp examples/models/subscription-computed.dm.json /tmp/review-computation.dm.json
dmtool -m /tmp/review-computation.dm.json computation modify \
  /Subscription/Billing/EffectiveFeeComp --spec /tmp/review-computation.json >/dev/null
dmtool model diff examples/models/subscription-computed.dm.json /tmp/review-computation.dm.json \
  | jq '.data.changes | map(select(.element=="COMPUTATION")) | map({id, risk, riskReasons, operation: (.deltas[] | select(.aspect=="alternative[0].operation"))})'
```

```output
[
  {
    "id": "/Subscription/Billing/EffectiveFeeComp",
    "risk": "MEDIUM",
    "riskReasons": [
      "COMPUTATION_CHANGED",
      "MESSAGE_CHANGED"
    ],
    "operation": {
      "aspect": "alternative[0].operation",
      "before": "[BaseFee]",
      "beforeValue": {
        "type": "TEXT",
        "value": "[BaseFee]"
      },
      "after": "[BaseFee] + 1",
      "afterValue": {
        "type": "TEXT",
        "value": "[BaseFee] + 1"
      }
    }
  }
]
```

→ The changed operation is explicit and `COMPUTATION_CHANGED` is MEDIUM. A message-only edit is LOW `MESSAGE_CHANGED`; it cannot hide a functional computation change.

### Group constraints and authored order are semantic

Groups carry behavior and review-relevant authoring state of their own: repeatability, index and ordered sort fields, include mount options, labels/descriptions/annotations, and position among every sibling kind. Child additions/removals remain their own element changes rather than producing a duplicate `children` delta on the parent. Here the `Items` repetition cap increases from 50 to 75, widening what the model accepts:

```bash
cp examples/models/order.dm.json /tmp/review-group.dm.json
dmtool -m /tmp/review-group.dm.json group modify /Order/Items --repeatable 75 >/dev/null
dmtool model diff examples/models/order.dm.json /tmp/review-group.dm.json \
  | jq '.data.changes | map(select(.element=="GROUP")) | map({id, risk, riskReasons, repeatability: (.deltas[] | select(.aspect=="repeatability"))})'
```

```output
[
  {
    "id": "/Order/Items",
    "risk": "HIGH",
    "riskReasons": [
      "REPEATABILITY_CHANGED",
      "CONSTRAINT_LOOSENED"
    ],
    "repeatability": {
      "aspect": "repeatability",
      "before": "50",
      "beforeValue": {
        "type": "INTEGER",
        "value": 50
      },
      "after": "75",
      "afterValue": {
        "type": "INTEGER",
        "value": 75
      }
    }
  }
]
```

→ The exact cap change is visible and HIGH `CONSTRAINT_LOOSENED`; decreasing it is MEDIUM `CONSTRAINT_TIGHTENED`. Reordering is computed over one mixed authored sequence, so moving a group relative to a field is reported as `REORDERED`/`POSITION_CHANGED`, while inserting or removing a sibling never fabricates a reorder.

### Reusable type definitions are first-class

A typedef is a shared declaration, not merely a property copied onto each using field. The diff therefore reports its complete typed state under one `TYPEDEF` element; an exact content-preserving id change can be paired as a rename, while a rename plus edit stays the conservative remove+add.

```bash
cat > /tmp/review-tier.json <<'EOF'
{ "kind": "ENUM", "enum": { "values": [ {"value":"STANDARD"}, {"value":"PREMIUM"} ] } }
EOF
cp examples/models/order-ruled.dm.json /tmp/review-typedef.dm.json
dmtool -m /tmp/review-typedef.dm.json typedef add --id CustomerTier /tmp/review-tier.json >/dev/null
dmtool model diff examples/models/order-ruled.dm.json /tmp/review-typedef.dm.json \
  | jq '.data.changes | map(select(.element=="TYPEDEF")) | map({id, change, risk, riskReasons, values: [.deltas[] | select(.aspect | startswith("enum.value.")) | .aspect]})'
```

```output
[
  {
    "id": "CustomerTier",
    "change": "ADDED",
    "risk": "MEDIUM",
    "riskReasons": [
      "TYPEDEF_ADDED"
    ],
    "values": [
      "enum.value.STANDARD",
      "enum.value.PREMIUM"
    ]
  }
]
```

→ The reusable enum appears once with both values, rather than as duplicated field changes. Headless resolver-backed diffs also expose direct includes and type-definition imports by alias, purpose, and reference; mount rewires remain visible on the authored group, distinct from expanded provider content.

### Model configuration is one stable element

Configuration changes should not disappear merely because no field or rule changed. Identity/version, locales, scalar model settings, supported characters, base year, labels, comment, annotations, and roles are compared under the stable `CONFIG model` element. Set-like values are sorted before comparison, so harmless source ordering does not create review noise.

```bash
cp examples/models/order-ruled.dm.json /tmp/review-config.dm.json
dmtool -m /tmp/review-config.dm.json config modify --base-year 1995 --label en_US=Purchasing >/dev/null
dmtool model diff examples/models/order-ruled.dm.json /tmp/review-config.dm.json \
  | jq '.data.changes | map(select(.element=="CONFIG")) | map({id, risk, riskReasons, deltas})'
```

```output
[
  {
    "id": "model",
    "risk": "MEDIUM",
    "riskReasons": [
      "CONFIG_CHANGED",
      "METADATA_CHANGED"
    ],
    "deltas": [
      {
        "aspect": "baseYear",
        "after": "1995",
        "afterValue": {
          "type": "INTEGER",
          "value": 1995
        }
      },
      {
        "aspect": "label.en_US",
        "after": "Purchasing",
        "afterValue": {
          "type": "TEXT",
          "value": "Purchasing"
        }
      }
    ]
  }
]
```

→ One model-level edit produces one review item with exact per-aspect deltas. Adding a locale is LOW `LOCALE_ADDED`; removing one is MEDIUM `LOCALE_REMOVED`; expanding the allowed character set is HIGH `CONSTRAINT_LOOSENED`.

### Reader-sensitive removals stay high impact

A cascading edit can remove a field and its reader together, leaving a valid head model. The diff still consults the base model's inverse rule/computation reference map: the field must not look like an ordinary unreferenced structure removal merely because its reader disappeared in the same edit.

```bash
cp examples/models/order-ruled.dm.json /tmp/review-reader.dm.json
dmtool -m /tmp/review-reader.dm.json field remove /Order/OrderDate --cascade >/dev/null
dmtool model diff examples/models/order-ruled.dm.json /tmp/review-reader.dm.json \
  | jq '.data.changes | map(select(.id=="/Order/OrderDate")) | map({id, risk, riskReasons})'
```

```output
[
  {
    "id": "/Order/OrderDate",
    "risk": "HIGH",
    "riskReasons": [
      "STRUCTURE_REMOVED",
      "REFERENCED_ELEMENT_REMOVED"
    ]
  }
]
```

→ The head is consistent because `--cascade` also removed `DeliveryNotBeforeOrder`, but the field removal remains HIGH `REFERENCED_ELEMENT_REMOVED`: it changed a location active logic depended on. A kind/typedef retype with readers similarly adds `RETYPE_AFFECTS_READERS`.

### A silent polarity flip — the most dangerous edit

Deleting a rule fails loudly (the data it guarded is now unchecked, and `RULE_REMOVED` is HIGH). The *quieter* danger is **inverting** a rule: it stays, guards the same field, but now fires on the **complementary** set — silently accepting exactly what it used to reject. `model diff` reads both conditions as typed ASTs and proves the flip structurally, so it grades **HIGH `POLARITY_INVERTED`** — not a generic `CONDITION_CHANGED`. Here we flip `PostalCodeFormat` to its logical complement (`FieldFilled … PatternViolated` → `FieldNotFilled … PatternMatched`) and diff:

```bash
cp examples/models/order-ruled.dm.json /tmp/postalcode-flipped.dm.json
dmtool -m /tmp/postalcode-flipped.dm.json rule modify /Order/BillingAddress/PostalCodeFormat \
  --condition 'FieldNotFilled(PostalCode) Or [PostalCode] PatternMatched "[0-9]{5}"' >/dev/null 2>&1
dmtool model diff examples/models/order-ruled.dm.json /tmp/postalcode-flipped.dm.json \
  | jq '{ summary, inverted: (.data.changes | map(select(.risk=="HIGH")) | map({id, change, riskReasons})) }'

```

```output
{
  "summary": "0 added, 0 removed, 1 modified (1 high-risk)",
  "inverted": [
    {
      "id": "/Order/BillingAddress/PostalCodeFormat",
      "change": "MODIFIED",
      "riskReasons": [
        "POLARITY_INVERTED"
      ]
    }
  ]
}
```

→ The rule wasn't added or removed — it was **modified**, and the diff calls it out as `POLARITY_INVERTED` (HIGH) because the new condition is the exact logical complement of the old one. This is the signal the trust principle is built for: a tool-computed fact, derived from the ASTs (not the agent's narration), that surfaces the one edit a line-based diff would bury. A condition change the tool *can't* prove is a clean flip stays the honest `CONDITION_CHANGED` (MEDIUM); proving behavioral *sameness* is the deferred `--deep` path (MODEL-REVIEW-SPEC §5c).

### Not every condition change is dangerous — the tool classifies it

A flip is HIGH, but most condition edits are routine. `model diff` reads the two ASTs and **classifies** a non-inverted change so a reviewer can triage: `CONDITION_RESTRUCTURED` (a clause was added/removed), `REFERENCE_CHANGED` (it now reads different fields), `OPERATOR_CHANGED` (an operator swapped in place), or `THRESHOLD_CHANGED` (only a literal moved) — all MEDIUM. On top of the coarse code it also emits a **located edit-script**: *which* clause/operand moved. Here we bump a date rule's threshold from `< 0` to `< -1` (same shape, same fields, one literal):

```bash
cp examples/models/order-ruled.dm.json /tmp/threshold-bumped.dm.json
dmtool -m /tmp/threshold-bumped.dm.json rule modify /Order/DeliveryNotBeforeOrder \
  --condition 'AllFieldsFilled(OrderDate, DeliveryDate) And DifferenceInDays(OrderDate, DeliveryDate) < -1' >/dev/null 2>&1
dmtool model diff examples/models/order-ruled.dm.json /tmp/threshold-bumped.dm.json \
  | jq '.data.changes | map(select(.change=="MODIFIED")) | map({id, riskReasons, located: [.deltas[] | select(.aspect=="condition") | .conditionEdits[].summary]})'

```

```output
[
  {
    "id": "/Order/DeliveryNotBeforeOrder",
    "riskReasons": [
      "THRESHOLD_CHANGED"
    ],
    "located": [
      "threshold 0→-1 on DifferenceInDays(/Order/OrderDate, /Order/DeliveryDate)"
    ]
  }
]
```

→ `THRESHOLD_CHANGED` (MEDIUM) says *what kind* of change; the `located` edit says *exactly which* operand moved — `threshold 0→-1` on the `DifferenceInDays` clause, not just "the condition changed". The tool flattens `And`/`Or` chains and matches operands by content, so a clause **inserted** into a chain is reported as one add (a downstream clause is never mislocated), and a pure re-association is no edit at all; a genuinely ambiguous restructure degrades to the coarse kind. So a reviewer scanning a large diff sees the HIGH flip first, then triages each routine edit by its one-line location.

### A refactor is one operation, not N edits

When an agent renames a field, the reference rewrite fans out: the field moves *and* every rule that read it is re-pointed. A naive diff shows that as N+1 unrelated changes — a scary pile. `model diff` folds them: it re-labels the removed+added field as one `RENAMED` (a content-preserving move — its refs were rewritten, so it's LOW, not a deletion), and groups the rewritten referrers under **one** `refactors` entry. Here we rename `/Order/OrderDate`, which the delivery-date rule reads:

```bash
cp examples/models/order-ruled.dm.json /tmp/order-renamed.dm.json
dmtool -m /tmp/order-renamed.dm.json field rename /Order/OrderDate --to PlacedDate >/dev/null 2>&1
dmtool model diff examples/models/order-ruled.dm.json /tmp/order-renamed.dm.json \
  | jq '{ summary, refactors: .data.refactors, folded: (.data.changes | map({change, id, partOfRefactor})) }'

```

```output
{
  "summary": "0 added, 0 removed, 1 modified, 1 renamed/moved",
  "refactors": [
    {
      "kind": "RENAME",
      "from": "/Order/OrderDate",
      "to": "/Order/PlacedDate",
      "referrers": [
        "/Order/DeliveryNotBeforeOrder"
      ]
    }
  ],
  "folded": [
    {
      "change": "RENAMED",
      "id": "/Order/OrderDate",
      "partOfRefactor": 0
    },
    {
      "change": "MODIFIED",
      "id": "/Order/DeliveryNotBeforeOrder",
      "partOfRefactor": 0
    }
  ]
}
```

→ One `refactors` entry captures the whole operation: *`/Order/OrderDate` renamed to `/Order/PlacedDate`, 1 referrer rewritten*. The overlay is **non-destructive** — the raw `RENAMED` field change and the referrer's `MODIFIED` change both still ride `data.changes` (each carrying `partOfRefactor: 0`), so a reviewer can verify the fold from the change-set itself (the trust principle, applied to the tool's *own* output). The pairing is conservative: a removed+added pair is only re-labelled when it's an unambiguous, content-identical relocation (a rename+edit — where the content also changed — is deferred to the similarity matcher, MODEL-REVIEW-SPEC §5). In `--text` the whole refactor collapses to one line:

```bash
dmtool model diff examples/models/order-ruled.dm.json /tmp/order-renamed.dm.json --text

```

```output
0 added, 0 removed, 1 modified, 1 renamed/moved
  (read)

REFACTORS (1)
  ↻ RENAME /Order/OrderDate → /Order/PlacedDate  (1 referrer rewritten)
```

→ The `↻` headline says *what happened* as one operation; the referrer's individual `MODIFIED` line is folded under it (still whole in the JSON). A reviewer sees "a rename, one referrer" instead of hunting a field remove/add plus a scattered condition edit.

### The human view — `--text`

JSON is the default and the machine contract, but a reviewer at the terminal wants to scan. The global `--text` flag renders the change-set **impact-sorted** — HIGH first, one line per change, LOW collapsed to a count — so the dangerous changes are read first:

```bash
dmtool model diff examples/models/order-ruled.dm.json examples/models/order.dm.json --text

```

```output
11 added, 3 removed, 1 modified (3 high-risk)
  (read)

HIGH (3)
  REMOVED  rule  /Order/BillingAddress/PostalCodeFormat  RULE_REMOVED
  REMOVED  rule  /Order/DeliveryNotBeforeOrder  RULE_REMOVED
  REMOVED  rule  /Order/EligibilityCheck  RULE_REMOVED
MEDIUM (12)
  ADDED    field /Order/Items/UnitWeight  STRUCTURE_ADDED
  ADDED    field /Order/Items/ItemCoverage  STRUCTURE_ADDED
  ADDED    field /Order/PickupTime  STRUCTURE_ADDED
  ADDED    field /Order/ScheduledAt  STRUCTURE_ADDED
  ADDED    field /Order/CompletedAt  STRUCTURE_ADDED
  ADDED    field /Order/RushLevel  STRUCTURE_ADDED
  ADDED    field /Order/ApproxDate  STRUCTURE_ADDED
  ADDED    field /Order/BirthDay  STRUCTURE_ADDED
  ADDED    field /Order/BirthMonth  STRUCTURE_ADDED
  ADDED    field /Order/BirthYear  STRUCTURE_ADDED
  ADDED    field /Order/CoverageWindow  STRUCTURE_ADDED
  MODIFIED config model  CONFIG_CHANGED
```

→ The three deleted rules surface at the top under **HIGH**, the eleven added fields group under **MEDIUM** — the same change-set as the JSON, shaped for a human to triage top-down. `model report --text` likewise renders the glossed rule/computation catalog and the dead-field set as a scannable list. (`--text` is a boundary view; the JSON remains the contract every guard and agent reads.)

### The review entrypoint — `--since <ref>`

The two-file form is the testable core, but the everyday review question is "what did the agent change since the last commit?" In a git repo that's one command — `dmtool -m order.dm.json model diff --since HEAD` — which materializes the BASE model and its referenced workspace models from `HEAD`, then diffs them against the working model and dependencies. A dependency edit therefore cannot make the historical base bind against the wrong revision, and you never hand-roll `git show` into temp files. (It's the one deliberate git touch; the diff engine itself stays git-free.)
