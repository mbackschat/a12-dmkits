# Probe report — task 04 (multi-model workspace), 2026-06-22

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN.** Two models, an include, a shared type, a cross-include rule, and a rename all worked; every non-zero exit was a legitimate, well-diagnosed rejection (notably `RK_MOUNT_NOT_EDITABLE`, exemplary). It surfaced **one capability gap** (deferred) and **two output-contract DX items, both now fixed**.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) |
| start mode | **clean** (two from-scratch models + an include) |
| anti-cheat audit | **CLEAN** — every model mutation via dmtool; hand-authored files were input spec JSONs; no raw `.dm.json` read |
| digest | **CLEAN 6/6 · 0 RED FLAGS** (the typedef-read config gap drove an *indirect* check, not a raw read — the SUSPECT breach class is closed) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 04-tdm | no verb/flag creates a `purpose=typeDefinitions` model, so `typedef import` (which requires one) has no producible input — sharing a type across *non-including* models isn't expressible (a working alternative exists via `typedef extract` + an include) | CLI (capability) | **GAP (deferred feature)** | **deferred** → FEATURE-GAPS-LEDGER (Capability F); `typedef extract`+include covers the include case |
| 04-tdread | `typedef read <id>` echoed only `type:NumberType`, not the per-kind config (`unit`/`min`/`max`/scale) — the structured read fell short of what it should surface | material | **DEFECT (read-gap, fix-B sibling)** | **FIXED** — `858094f` |
| 04-missing | `field read <missing-path>` returned a plain-text stderr line + exit 2, breaking the JSON-in/JSON-out contract | CLI | **DEFECT (contract break)** | **FIXED** — `92a9300` |
| — | `RK_MOUNT_NOT_EDITABLE` caught the wrong instinct (a rule's error field on an included `/Order/Product/Sku`) and its `fix` named the exact correct design (anchor native, reference the include absolutely) | — | **expected / positive** | — |

## Replay log (verbatim, in order)
1. create a shared catalog model: a product with a sku, a name, and a price. Then create a separate order model with a customer and an order date.
2. in the order model, include the catalog so each order carries the product details.
3. both models' money amounts should use one shared 'Money' type defined once.
4. add a rule on the order: when the order is placed, the product's sku must be filled.
5. rename the order's customer field to 'buyer' everywhere.

## Self-audit
CLEAN: all model mutations via dmtool verbs; hand-authored files were input spec JSONs; the failed-approach `Money_TDM.json` (a dead end for 04-tdm) was deleted, not a committed model. No raw `.dm.json` read — `typedef read`'s config omission (04-tdread) was worked around via a consuming field's `field read`, not a raw read, so it is filed as a `[material]` gap (now fixed).

## Resolution (2026-06-22)
- **04-tdread — FIXED (`858094f`).** `typedef read <id>` now echoes the per-kind config in the same block shape `typedef add` accepts (`ModelDocument.typeDefinitionConfig` over `readTypeConfig`), so a constraint on a reusable type is verifiable through `read` alone. Locked by `TypedefVerbTest.readWithAnIdSurfacesThePerKindConfig`; CLI-SPEC typedef row updated.
- **04-missing — FIXED (`92a9300`).** A read of a missing target now returns a structured `refused` envelope (`RK_NO_SUCH_*` + a corrective `fix`) across **every** read verb (field/group/rule/computation/typedef), single-sourced via `BaseCommand.refuseNotFound` and locked by `ReadMissingTargetEnvelopeTest`. The agent's "`rule read` should be checked for the same" was the generalization trigger — all read verbs were swept.
- **04-tdm — DEFERRED (capability).** Creating a `purpose=typeDefinitions` provider model is an unbuilt feature (modelkit has `DocumentModelDraft.typeDefModel` + `addTypeDefinition`, but the empty-model "needs ≥1 typedef" rule complicates a clean `model new --type-definitions`); it lives as a capability gap in FEATURE-GAPS-LEDGER, not a dated-report-only note. The include-based share (`typedef extract`) covers the common case.
