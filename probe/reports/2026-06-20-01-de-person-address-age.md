# Probe report — 01-de (person + addresses + age≥18, prompted in German) — 2026-06-20

**Task:** [`probe/tasks/01-de-person-address-age.md`](../tasks/01-de-person-address-age.md) — task 01 re-expressed with **German prompts**; the agent must produce a `de_DE` locale + German names/labels/messages **on its own**.
**Material:** shipped native binary (rebuilt to HEAD, `0.3.2`) + `skill/SKILL.md`. Driver: a clean general-purpose subagent in the isolated sandbox; every `dmtool` call captured by the logging shim.

## Integrity & trajectory (objective)

- **Anti-cheat audit — CLEAN** (`audit-probe.sh`): no repo/workspace/kernel path touched; every file op under the sandbox; the model files were only ever mutated by `dmtool`. Findings trustworthy.
- **Digest** (`digest-probe.sh`): 72 invocations (61 distinct), **0 RED FLAGS** (no raw stack trace escaped a verb — every error a structured envelope). 7 nonzero exits, clustering on the two findings below.
- **Self-direction to German — strong.** Unprompted, the agent chose `--locale de_DE`, German element names (`Person`/`Privatadresse`/`Geschäftsadresse`/`Strasse`/`PLZ`/`Ort`/`Geburtsdatum`), German labels, and German rule messages. The German→de_DE inference worked with no nudge needed.
- **Correctness vs gold — met.** Turn 1: a `de_DE` person model, two address groups, an age≥18 *violation* rule (birth-date-derived, exercising the native date operators). Turn 2: address fields extracted to a shared sub-model, included twice (private + business). Both models kernel-valid.

## Finding 1 (CRITICAL, root-caused + FIXED) — a German-locale model rejected English DSL keywords

**Symptom.** The agent's first age rule used the skill's English keywords on its `de_DE` model and was rejected:
`MVK_UNEXPECTED_TOKEN: Unexpected found 'FieldFilled'`. It recovered only by discovering (via `operators`) that each operator carries a German keyword and re-authoring in German (`FeldAngegeben`/`Und`/`DifferenzInJahren`/`Heute`).

**Root cause (verified against the kernel-truth oracles).** A model's **condition keyword language** (`modelConfig.conditionLanguage.code`) is **independent** of its **display locale** (`locales[]`, which governs labels + messages). `DocumentModelDraft` derived the condition language from the *first display locale* (`locales.get(0)`), so a `de_DE` model got `conditionLanguage=de_DE` and the kernel then parsed conditions in German — rejecting the English DSL the renderer + skill + `operators` all emit.

Confirmed the user's correction ("English rules are a must; locale is only for messages") against the source material:
- **a12-kernel-mm corpus:** all 59 models declare `de_DE` as their first display locale yet set `conditionLanguage=en_US` and author **English**-keyword rules (`FieldFilled`/`And`/`PatternViolated`/`RepetitionNotUnique`). English is the convention.
- **Experiment:** a `de_DE`-only model with `conditionLanguage=en_US` + English conditions is **kernel-valid** — the condition language need not be a declared display locale.
- Recorded: KERNEL-FINDINGS "A model's condition language is independent of its display locale"; treatment in KERNEL-INTEGRATION-NOTES.

**Fix (commit `951377f`).** A from-scratch model now defaults `conditionLanguage` to **en_US regardless of display locale**, single-sourced (`DEFAULT_CONDITION_LANGUAGE`) so the model-config write and the attachment-template authoring can't drift. The `model new --locale` help no longer mis-describes itself as "the condition + default locale". Locked: `DocumentModelDraftTest` (draft keeps en_US + kernel accepts) + `ModelNewVerbTest` (English DSL validates end-to-end on a de_DE model).

**Generalized (root-cause-generalize sweep — two sibling sites found):**
1. **Attachment template.** `Builder.attachmentGroup` passed `locales.get(0)` as the attachment condition language — a *second* derivation site; left unfixed it emitted German attachment conditions against an en_US config (kernel-rejected; caught by `AttachmentTemplateTest`). Now uses the one constant.
2. **Computations + preconditions.** Because the fix is at the **model level** (`conditionLanguage` gates *all* DSL the kernel parses — rule conditions, computation **operations**, and **preconditions** alike), it covers computations automatically. Locked explicitly by `ModelNewVerbTest.aGermanLocaleModelAlsoAcceptsEnglishKeywordComputationsAndPreconditions` — a de_DE model accepts an English-keyword computation whose alternative carries an English precondition.

## Finding 2 (DX, root-caused + FIXED) — `export` couldn't be driven with the universal `-m`

**Symptom.** The agent (having learned `-m <model>` works on every verb) tried `dmtool -m person.json export …` repeatedly → `Missing required parameter: '<model.json>'`; and `dmtool -m person.json export rules` → `cannot read model: Is a directory`. 5 wasted calls before it matched the help.

**Root cause.** `export` was the **only** verb taking the model as a **required positional** `<model.json>` instead of the universal `-m`. It *also* inherited `-m` but ignored it — so an agent supplying `-m` got a confusing "missing positional" with no hint, and the model positional was ambiguous with the `[<artifact>]` positional (`export rules` read "rules" as the model). Documented as intentional ("the odd one out"), with 5 demos depending on the positional form.

**Fix (owner-decided → commit `3feda47`).** Aligned `export` to the universal `-m` like every verb (dropped the positional model; `[<artifact>]` stays positional) — removing the inconsistency and the silently-ignored-`-m` redundancy. Updated the 3 export demos + API-GUIDE/ARCHITECTURE + the skill (and synced plugin copies) + the verb-examples demonstration; native gate + showboat re-verified. Locked by `ExportVerbTest.exportIsDrivenByTheUniversalModelOption` (+ a no-model corrective). The "odd one out" note in `cli-structure-edit.md` flipped to "consistent with every verb".

## Re-run validation (post-fix binary, same prompts)

Re-ran 01-de against the binary rebuilt with all three fixes (`951377f` condition-language, `3feda47` export `-m`, `7f1fc03` value-shape gate). Audit **CLEAN**, **0 RED FLAGS**.

- **Finding 1 confirmed fixed.** Unprompted German session → `conditionLanguage: en_US` and **every** condition in English keywords (`FieldFilled` / `And` / `DifferenceInYears` / `GroupNotFilled` / `FieldsNotCollectivelyFilled`) — **no `MVK_UNEXPECTED_TOKEN`, no keyword-language detour**. German display locale, German labels/messages, English keyword conditions: exactly the kernel-mm convention. (The one age-rule `exit 1` was `AllFieldsFilled` *arity* — needs ≥2 fields — not a language rejection; the `fix` hint pointed to `FieldFilled`.)

The produced models (embedded verbatim for objective review):

**`person.json`**
```json
{
  "header": { "id": "person", "modelType": "document", "modelVersion": "28.4.0",
    "locales": [ { "code": "de_DE" } ],
    "modelReferences": [
      { "alias": "privateAddress", "modelType": "document", "purpose": "include", "reference": "address" },
      { "alias": "businessAddress", "modelType": "document", "purpose": "include", "reference": "address" } ] },
  "content": {
    "modelInfo": { "name": "person", "variant": "cockpit", "revision": "Arbeitsversion", "immutable": false },
    "modelConfig": { "decimalSeparator": ".", "timeZone": "Europe/Berlin",
      "conditionLanguage": { "code": "en_US" }, "fieldRefByShortNameAllowed": true },
    "modelRoot": { "rootGroups": [ { "type": "Group", "id": "/Person", "name": "Person", "Group": {
      "repeatability": 1, "elements": [
        { "type": "Field", "id": "/Person/FirstName", "name": "FirstName", "Field": {
          "fieldType": { "type": "StringType" }, "label": [ { "locale": "de_DE", "text": "Vorname" } ] } },
        { "type": "Field", "id": "/Person/LastName", "name": "LastName", "Field": {
          "fieldType": { "type": "StringType" }, "label": [ { "locale": "de_DE", "text": "Nachname" } ] } },
        { "type": "Field", "id": "/Person/BirthDate", "name": "BirthDate", "Field": {
          "fieldType": { "type": "DateType", "DateType": { "format": "yyyy-MM-dd" } },
          "label": [ { "locale": "de_DE", "text": "Geburtsdatum" } ] } },
        { "type": "Rule", "id": "/Person/PERSON_UNDER_18", "name": "PERSON_UNDER_18", "Rule": {
          "errorEntityRelPath": "../BirthDate", "errorCode": "PERSON_UNDER_18",
          "errorCondition": "FieldFilled(BirthDate) And DifferenceInYears(BirthDate, Today) < 18",
          "severity": "ERROR",
          "errorMessage": [ { "locale": "de_DE", "text": "Die Person muss mindestens 18 Jahre alt sein." } ] } },
        { "type": "Group", "id": "/Person/PrivateAddress", "name": "PrivateAddress",
          "Group": { "repeatability": 1, "modelAlias": "privateAddress" } },
        { "type": "Group", "id": "/Person/BusinessAddress", "name": "BusinessAddress",
          "Group": { "repeatability": 1, "modelAlias": "businessAddress", "elements": [ ] } },
        { "type": "Field", "id": "/Person/CompanyName", "name": "CompanyName",
          "Field": { "fieldType": { "type": "StringType" } } },
        { "type": "Rule", "id": "/Person/COMPANY_NEEDS_BUSINESS_ADDRESS", "name": "COMPANY_NEEDS_BUSINESS_ADDRESS", "Rule": {
          "errorEntityRelPath": "../CompanyName", "errorCode": "COMPANY_NEEDS_BUSINESS_ADDRESS",
          "errorCondition": "FieldFilled(CompanyName) And GroupNotFilled(/Person/BusinessAddress)",
          "severity": "ERROR",
          "errorMessage": [ { "locale": "de_DE", "text": "Wenn ein Firmenname angegeben ist, muss auch eine Geschäftsadresse erfasst werden." } ] } } ] } } ] } } }
```

**`address.json`** (included twice by `person`)
```json
{
  "header": { "id": "address", "modelType": "document", "modelVersion": "28.4.0",
    "locales": [ { "code": "de_DE" } ], "modelReferences": [ ] },
  "content": {
    "modelInfo": { "name": "address", "variant": "cockpit", "revision": "Arbeitsversion", "immutable": false },
    "modelConfig": { "decimalSeparator": ".", "timeZone": "Europe/Berlin",
      "conditionLanguage": { "code": "en_US" }, "fieldRefByShortNameAllowed": true },
    "modelRoot": { "rootGroups": [ { "type": "Group", "id": "/Address", "name": "Address", "Group": {
      "repeatability": 1, "elements": [
        { "type": "Field", "id": "/Address/Street", "name": "Street", "Field": {
          "fieldType": { "type": "StringType" }, "label": [ { "locale": "de_DE", "text": "Straße" } ] } },
        { "type": "Field", "id": "/Address/PostalCode", "name": "PostalCode", "Field": {
          "fieldType": { "type": "StringType", "StringType": {
            "errorMessage": [ { "locale": "de_DE", "text": "Die Postleitzahl muss aus fünf Ziffern bestehen." } ],
            "pattern": "[0-9]{5}" } },
          "label": [ { "locale": "de_DE", "text": "Postleitzahl" } ] } },
        { "type": "Field", "id": "/Address/City", "name": "City", "Field": {
          "fieldType": { "type": "StringType" }, "label": [ { "locale": "de_DE", "text": "Ort" } ] } },
        { "type": "Field", "id": "/Address/Country", "name": "Country", "Field": {
          "fieldType": { "type": "StringType" }, "label": [ { "locale": "de_DE", "text": "Land" } ] } },
        { "type": "Rule", "id": "/Address/ADDRESS_INCOMPLETE", "name": "ADDRESS_INCOMPLETE", "Rule": {
          "errorEntityRelPath": "../Street", "errorCode": "ADDRESS_INCOMPLETE",
          "errorCondition": "FieldsNotCollectivelyFilled(Street, PostalCode, City)", "severity": "ERROR",
          "errorMessage": [ { "locale": "de_DE", "text": "Die Adresse ist unvollständig: Straße, Postleitzahl und Ort müssen gemeinsam angegeben werden." } ] } } ] } } ] } } }
```

## Efficiency & trajectory breakdown — 88 calls / ~101k tokens / ~10 min for a 2-turn task

This was **far too long**. The deterministic attribution (from the shim log) shows *where* it went, so the fix targets the cause, not the symptom.

**Headline.** 88 invocations (74 distinct) · ~101k agent tokens · ~10 min · **13 nonzero exits** · 0 RED FLAGS. `apply` was *executed* 3× (build-person, add-rules, build-address); `batch` was only *explored* (`batch --help`, `schema batch`) — so the agent knew batching existed and used it for the bulk build, but not for the tail.

**By purpose** (every call bucketed):

| bucket | calls | % | what |
|---|---:|---:|---|
| **orient / discover** | 39 | 44% | `model describe` ×10, `operators` ×7, `rule read` ×7, `include read` ×3, `group read` ×2, `export` ×2, `schema` ×4, `--help` |
| **author / mutate** | 28 | 32% | `apply` ×3, `field add` ×3, `include add` ×3, `rule add` ×2, `group add` ×1, `model new` ×3 — **but ~10 of these were UNDO/RETRY**: `group remove` ×3 + `rule remove` ×3 + `field remove` ×1 + `group extract` ×4 (1 refused) |
| **verify** | 21 | 24% | `rule check` ×13 (**8 failed**), `model validate` ×5, `workspace validate` ×3 (2 failed) |

**Where the waste was** (the 13 failures + the retries map to four distinct causes):

1. **Mount-field thrash (~20 calls)** — adding `CompanyName` to the `BusinessAddress` mount silently broke the include (Finding 4), then a cascade of `describe`/`rule check`(failed)/`remove` to diagnose and undo. The single biggest sink.
2. **Cross-group-rule refusal (~10 calls)** — the turn-1 "at least one address" rule referenced across groups → `group extract` refused (`RK_OUTWARD_REFERENCE`, the §6 safety gate firing **correctly**) → `rule remove` + redesign into `COMPANY_NEEDS_BUSINESS_ADDRESS`. Friction, but the gate did its job.
3. **`workspace` `-w` vs positional `<dir>` (4 failed calls)** — the agent tried `dmtool -w <dir> workspace validate/list`; `workspace` takes the dir **positionally** → `Missing required parameter: '<dir>'` ×4. The *same class as the `export` `-m` inconsistency* (Finding 2) one verb over — a candidate for the same alignment treatment (recorded below).
4. **Over-re-orientation (~10 calls)** — `model describe` ×10 + `rule read` ×7 is a lot of re-reading state for a model this small; partly forced by (1) (re-checking what the broken mount resolved to), partly the agent being cautious.

**An efficient run** would be ≈ `model new` ×2 → a little orientation → **2 `apply` calls** (each model built fields+rules+include-mounts in one shot) → 2 `validate` ≈ **~12–15 calls**. The gap to 88 is the four causes above.

## Finding 3 (efficiency, root-caused + FIXED) — the skill de-prioritized batching

**Root cause.** The skill framed cost as **binary startup** ("sub-100 ms … one invocation per step is fine … reach for apply/batch *not to avoid startup cost*") — true for the *binary*, but it ignores that **each call is an agent round-trip** (read output → reason → call again), the real expense. So the agent batched the obvious bulk build but had no cue to collapse the rest, and the recovery tail ran one-call-at-a-time.

**Fix.** Reframed the skill's cost guidance: *batch the edits you already know into one `apply`/`batch` (fewer round-trips **and** atomic); keep single calls for exploring/checking.* Corrects the under-batching without re-introducing the over-batching-to-avoid-startup misconception. Synced to the plugin copies; this is the N6 material-under-test, so the eval will measure the effect. (This addresses the *batching* lever; the bigger sinks — causes 1–3 above — are Findings 4–5.)

## Finding 4 (DX, recorded — candidate, root-cause TBD) — a local field added to an included mount group silently breaks the include

**Symptom.** The agent added `CompanyName` directly to `/Person/BusinessAddress` (a group that mounts the `address` include). The mount kept its `modelAlias` but **stopped resolving the included fields** (`/Person/BusinessAddress/Street` became "not a field"), and the agent only noticed via a later `rule check`/`describe` — no error at the `field add`. It recovered by moving `CompanyName` to the Person level.

**Likely root cause (to confirm).** A mount group (`modelAlias` set) with its own non-empty `elements` is no longer expanded from the include — the local elements shadow/replace the mounted content. Whether this is kernel expand semantics or a rulekit authoring gap needs an experiment; if rulekit lets `field add` write into a mount group without warning, that is a silent-drop-class sibling worth a refusal or advisory. **Queued for a focused probe/experiment** (not yet fixed).

## Finding 5 (DX, recorded — candidate, same class as Finding 2) — `workspace` can't be driven with `-w`

**Symptom.** Having set `-w <dir>` for the session, the agent ran `dmtool -w <dir> workspace validate` and `… workspace list` → `Missing required parameter: '<dir>'` ×4. `workspace <op>` takes the directory **positionally**, and the inherited `-w` (the include-resolution root) is ignored for it.

**Root cause + relation.** This is the **same class as Finding 2** (`export`'s positional model vs the universal `-m`): a verb-specific positional that the agent expects to be the universal option it already set. Here it's `-w` (resolve-roots) vs `workspace <dir>` (scan target) — conceptually the *same directory*, so `workspace <op>` with no positional could sensibly fall back to `-w`. **Recommended (owner decision, like Finding 2):** let `workspace list`/`validate` accept the dir from `-w` when the positional is omitted (or align it the way `export` was). Recorded, not yet fixed — it overturns the current positional-only shape and wants the same explicit go-ahead `export` got.

## Standing low finding (carried from 01b)
**F1:** `operators` has no narrowing flag; low priority.
