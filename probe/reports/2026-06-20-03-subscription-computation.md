# Probe report — 03 (subscription billing computation: add → re-express via modify) — 2026-06-20

**Task:** [`probe/tasks/03-subscription-computation.md`](../tasks/03-subscription-computation.md) — a 2-turn computation session (add a `EffectiveFee = 2×BaseFee` computation, then modify it to `+ Sum(addons)`).
**Material:** shipped native binary (rebuilt to HEAD, `0.3.2`) + `skill/SKILL.md`. Clean general-purpose subagent in the isolated sandbox; every `dmtool` call captured by the shim.

## Integrity & trajectory (objective)

- **Anti-cheat audit — CLEAN** (`audit-probe.sh`): no repo/workspace/kernel path; all file ops under the sandbox; the model touched only by `dmtool`.
- **Digest — 0 RED FLAGS.** **28 invocations** (26 distinct) · ~65k agent tokens · ~2.5 min. Purpose buckets **author 11 · verify 2 · orient 15**.
- **Much tighter than 01-de** (88 / 101k / 10 min). The agent **batched the turn-1 build into one `apply`** (8 ops, dry-run then commit) and used **`computation modify`** (dry-run diff, then apply) for turn 2 — exactly the "plan then batch known edits" shape the skill now teaches. *(Suggestive that the skill batching reframe helps; not isolable from task/agent variance — the eval will measure it.)*

## Correctness vs gold — MET

| gold expectation | result |
|---|---|
| `Subscription` with `Tier`, `Billing`(BaseFee, EffectiveFee), repeatable `Addons`(Name, MonthlyFee) | ✅ all present (Tier modeled as ENUM Basic/Pro/Enterprise — a sound own choice) |
| turn 1: computation `EffectiveFee = [BaseFee] * 2` | ✅ added via `apply` |
| turn 2: **modify** to `[BaseFee]*2 + Sum(addons fees)` | ✅ `2 * [/Subscription/Billing/BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)` — in place via `computation modify`, the computation kept its identity (not drop-and-re-add) |
| no self-reference; message covers locale; kernel-valid | ✅ `valid:true`, message present, computed-field not self-referenced |

The aggregate-over-repeatable (`Sum(Addons*/MonthlyFee)`) and the modify/re-express path — the least-probed computation surface — both worked, on the recently-changed `computation add`/`modify` paths (en_US-keyword default + value-shape gate). No false success, no silent drop.

## Finding P03-1 (discoverability, FIXED) — `model --help` advertised `eval`/`compute`, hidden on the native binary

**Symptom.** The agent wanted to numerically check the formula and reached for `model compute`. On the **native** binary the runtime verbs (`model eval`/`model compute`/`rule test`) are hidden (they need kernel Groovy codegen, not in the native image) — but `model --help`'s description string still reads *"Whole-model operations: new, info, validate, describe, read, usage, **eval, compute** …"*, and `dmtool model compute --help` falls back to printing the **`model` parent help** rather than a clear "not available". So the self-description **advertises verbs that aren't executable here**, and the agent gets a confusing non-answer instead of "this verb needs the JVM build."

**Root cause.** The `@Command(name="model", description=…)` prose hard-codes the full subcommand list including `eval, compute`; that string is static, so it ships on the native binary even though `NativeImageVerbHiding` removes those subcommands from the tree. The self-describing surface is native-aware for the *tree* (`manifest`/the hidden subcommands) but not for this hard-coded *description prose*.

**Fix (done).** Dropped the runtime-verb names (`eval`/`compute`, and `rule`'s `test`) from the parent `@Command` descriptions — the *live* subcommand list (already native-aware) does the enumerating — and added a build-independent clause: *"Running a model/rule against a sample document is a JVM-only capability — absent from the native binary."* Accurate on both builds; a native agent no longer reads a verb it can't run, and learns the capability needs the JVM. Locked by `NativeImageVerbHidingTest` (native help omits the op names). The recurring "self-description must be accurate" class, native-subset corner.

## What worked well (the positives)
- **`apply` for the build + `computation modify` for the change** — efficient and correct; the dry-run-then-commit discipline was used on both.
- **The computation-name diagnostic is excellent.** Reading by the field path (`computation read /Subscription/Billing/EffectiveFee`) → `'…/EffectiveFee' is not a computation in this model — computations: [/Subscription/Billing/EffectiveFeeComp]` — a corrective that lists the actual name; the agent recovered in one step. (A reasonable confusion — the computation's name ≠ its computed field — handled by the surface, not a defect.)

## Produced model (verbatim, for objective review)

```json
{
  "header": { "id": "subscription", "modelType": "document", "modelVersion": "28.4.0",
    "locales": [ { "code": "en_US" } ], "modelReferences": [ ] },
  "content": {
    "modelInfo": { "name": "subscription", "variant": "cockpit", "revision": "Arbeitsversion", "immutable": false },
    "modelConfig": { "decimalSeparator": ".", "timeZone": "Europe/Berlin",
      "conditionLanguage": { "code": "en_US" }, "fieldRefByShortNameAllowed": true },
    "modelRoot": { "rootGroups": [ { "type": "Group", "id": "/Subscription", "name": "Subscription", "Group": {
      "repeatability": 1, "elements": [
        { "type": "Field", "id": "/Subscription/Tier", "name": "Tier", "Field": {
          "fieldType": { "type": "EnumerationType", "EnumerationType": { "values": [
            { "value": "BASIC", "label": [ { "locale": "en_US", "text": "Basic" } ] },
            { "value": "PRO", "label": [ { "locale": "en_US", "text": "Pro" } ] },
            { "value": "ENTERPRISE", "label": [ { "locale": "en_US", "text": "Enterprise" } ] } ] } },
          "label": [ { "locale": "en_US", "text": "Subscription tier" } ] } },
        { "type": "Group", "id": "/Subscription/Billing", "name": "Billing", "Group": { "repeatability": 1, "elements": [
          { "type": "Field", "id": "/Subscription/Billing/BaseFee", "name": "BaseFee", "Field": {
            "fieldType": { "type": "NumberType", "NumberType": { "maxFractionalDigits": 2, "minFractionalDigits": 2, "minValue": 0, "trait": "amount" } },
            "label": [ { "locale": "en_US", "text": "Base monthly fee" } ] } },
          { "type": "Field", "id": "/Subscription/Billing/EffectiveFee", "name": "EffectiveFee", "Field": {
            "fieldType": { "type": "NumberType", "NumberType": { "maxFractionalDigits": 2, "minFractionalDigits": 2, "minValue": 0, "trait": "amount" } },
            "label": [ { "locale": "en_US", "text": "Effective monthly fee" } ] } },
          { "type": "Computation", "id": "/Subscription/Billing/EffectiveFeeComp", "name": "EffectiveFeeComp", "Computation": {
            "computedFieldRelPath": "../EffectiveFee",
            "computationAlternatives": [ { "operation": ";; effective fee is twice the base fee plus the total of all add-on monthly fees\n2 * [/Subscription/Billing/BaseFee] + Sum(/Subscription/Addons*/MonthlyFee)" } ],
            "errorMessage": [ { "locale": "en_US", "text": "Effective monthly fee is twice the base monthly fee plus the total of all add-on monthly fees." } ] } } ] } },
        { "type": "Group", "id": "/Subscription/Addons", "name": "Addons", "Group": { "repeatability": 99, "elements": [
          { "type": "Field", "id": "/Subscription/Addons/Name", "name": "Name", "Field": {
            "fieldType": { "type": "StringType" }, "label": [ { "locale": "en_US", "text": "Add-on name" } ] } },
          { "type": "Field", "id": "/Subscription/Addons/MonthlyFee", "name": "MonthlyFee", "Field": {
            "fieldType": { "type": "NumberType", "NumberType": { "maxFractionalDigits": 2, "minFractionalDigits": 2, "minValue": 0, "trait": "amount" } },
            "label": [ { "locale": "en_US", "text": "Add-on monthly fee" } ] } } ] } } ] } } ] } } }
```
