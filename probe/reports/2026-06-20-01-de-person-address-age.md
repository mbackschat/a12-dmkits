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

## Finding 4 (DX, root-caused + FIXED) — a local field added to an include-mount group silently broke the include

**Symptom.** The agent added `CompanyName` directly to `/Person/BusinessAddress` (a group that mounts the `address` include). The mount kept its `modelAlias` but **stopped resolving the included fields** (`/Person/BusinessAddress/Street` became "not a field") — no error at the `field add`. It recovered by moving `CompanyName` to the Person level.

**Root cause (confirmed by experiment).** A mount group (`modelAlias` set) that *also* has local `elements`: the **kernel uses the local elements and does not expand the include** — and the model stays **valid**. Reproduced cleanly — mounting `address` (resolving `/Person/Addr/{Street,City}`), then `field add --group /Person/Addr --name Extra` returned `outcome:applied, written:true`, after which the model resolved **only** `/Person/Addr/Extra` and `model validate` was still `valid:true`. So it is **kernel silent-override semantics** (recorded in KERNEL-FINDINGS), and rulekit's `field add` was blindly writing the local element. The exact silent-divergence class (learning #4).

**Fix.** rulekit now **refuses** an element-add into a mount group: a structured `RK_MOUNT_NOT_EDITABLE` on `field add` (`SafetyChecks.notAnIncludeMount`), and a loud backstop in `ModelDocument.containerElements` (throws) covering `group add`/`move`/the `apply` ops — generalized at the shared root, not patched per-verb. Detection: `ModelDocument.mountAliasAt`. Locked by `MountEditRefusalTest` (refusal fires **and** the mounted fields survive; the apply op rolls back). This was the biggest single time-sink in the re-run (~20 calls), now a one-call corrective.

## Finding 5 (DX, root-caused + FIXED) — `workspace` couldn't be driven with `-w`

**Symptom.** Having set `-w <dir>` for the session, the agent ran `dmtool -w <dir> workspace validate` and `… workspace list` → `Missing required parameter: '<dir>'` ×4. `workspace <op>` took the directory **only positionally** and ignored the inherited `-w`.

**Root cause + relation.** The **same class as Finding 2** (`export`'s positional vs the universal option): a verb-specific positional the agent expects to be the universal option it already set.

**Fix (additive, the class closed).** All four `workspace` verbs (`list`/`graph`/`validate`/`roles`) now resolve the dir as **positional `<dir>` → `-w/--workspace` → `$DMTOOL_WORKSPACE`** (shared `BaseCommand.workspaceDir`). Unlike `export` (where the positional model was *removed* — a model is always `-m`), the positional `<dir>` **stays** — it's the verb's natural scan target and the documented "ls `<dir>`" idiom (5 cli-tour blocks + all workspace tests use it) — and `-w` is the session-default fallback. So `dmtool -w <dir> workspace list` now works *and* nothing churns. Locked by `WorkspaceListCommandTest.theDirComesFromTheUniversalWorkspaceOptionWhenThePositionalIsOmitted` (+ a no-dir corrective). The sibling audit confirmed these 4 were the **only** remaining verbs deviating from the `-m`/`-w` universal-option shape after the `export` fix.

## Re-run #2 (final binary — all fixes + fresh metadata) — condition-language still good; TWO new serious findings

Re-ran on the binary rebuilt with **re-captured** reflection metadata + every fix (condition-language, export `-m`, workspace `-w`, mount-edit, P03-1). Audit **CLEAN** (incl. the new Bash-edit check #4 — see below). The condition-language fix held again: `de_DE` model, German names/labels, **English-keyword** conditions (`DifferenceInYears(/Person/Geburtsdatum, Today) < 18`), no detour. **But this run took the `group extract` path on a rule-bearing group and surfaced two real defects** (the earlier 01-de runs used `include add`, so they never hit it):

### Finding 6 (refactor-safety, HIGH, FIXED) — `group extract` didn't rewrite a carried rule's ABSOLUTE paths → silently broken sub-model
**Reproduced** (`scratchpad/extract-exp.sh`): a group with rule `NotAllFieldsFilled(/Person/Privatadresse/Strasse, /Person/Privatadresse/Plz)` (absolute operands), then `group extract /Person/Privatadresse --reference adresse` → the rule is copied **verbatim** into the sub-model (root `/Privatadresse`), `/Person/…` operands un-rewritten → the sub-model is **invalid standalone** (`MVK_INVALID_ROOT_GROUP`: "Rootgroup '/Person' is not valid") — yet extract reported `outcome:applied, written:true`. The *base* still validates (re-mounted, the paths line up under `/Person` again), which is why it's silent; but the sub-model can't be reused or edited on its own. **Root cause:** extract re-homes the subtree and rewrites *structure* ids (`reprefixIds`) but did **not** rewrite a carried rule's **absolute** condition operands to the sub-model root; and it validated only the *composed pair*, never the sub-model standalone. **Violated the §6 gate** (rewrite-or-refuse, never silently degrade). **Fix (done):** `reprefixIds` now also re-bases the absolute operands in a carried rule's `errorCondition` (and a computation's `operation`/`precondition`/`commonPrecondition`) from the old `/Parent/Group/…` prefix to the sub-model root, at path-segment boundaries (relative `../X` rides along unchanged); and `GroupExtractCommand` now **kernel-validates the sub-model standalone** before writing, refusing rather than emitting a broken artifact. Locked by `GroupExtractVerbTest.extractedSubModelIsValidStandalone_carriedRuleAbsolutePathsAreReBased`. (The 01 regression's extract worked only because that rule used *bare* relative names.)

### Finding 7 (diagnostic contract, HIGH, FIXED) — a verb loading an invalid-on-disk model dumps a RAW STACK TRACE (P1 class, load-path)
`rule modify` on the broken sub-model → an uncaught `ModelConsistencyException` (`RuleModifyCommand.java:103` → `KernelAdapter.ofJson` → `BoundModel.ofJson` → `ModelLoader.load`) printed as a Java stack trace, exit 1 — no structured envelope, no `fix`. This is the **same class P1 fixed** ("no raw exception escapes a verb"), but P1 guarded the *candidate-validation* path (`RuleValidator.check` on a proposed condition); the **model-LOAD** path (`ofJson` on an already-invalid stored model) is unguarded, and it is shared by **every** `-m` verb (`rule modify`/`read`, `model describe`/`validate`, …). So any model that is invalid on disk makes the whole verb set leak a stack trace. **Fix (done):** an `IExecutionExceptionHandler` in `Cli.run` catches an escaped `ModelConsistencyException` at the **one seam covering every verb** and maps it to the same structured `rejected` envelope (`MVK_*` code + message) the write path produces — generalized to the load path, not patched per-verb. Locked by `InvalidModelLoadDiagnosticTest` (`rule modify` → structured rejection + no trace; `model describe` trace-free).

**Why this matters beyond the two bugs (the integrity near-miss):** stuck on Finding 7's stack-trace wall, the agent **reasoned about hand-editing the JSON** (*"let me use the tool's own verbs rather than hand-editing JSON"*) — it stayed disciplined, but the audit would **not** have caught a Bash hand-edit (it only checked `Write`/`Edit`). Closed: `audit-probe.sh` **check #4** now flags a non-`dmtool` Bash write to a model file (commit `cb4611d`). Bad DX nearly drove a cheat the harness couldn't see — both are now addressed (the audit) or recorded for fix (Findings 6 + 7).

## Finding 8 (DX, recorded) — `apply`'s op-set is asymmetric with the standalone verbs (`group remove --cascade`, `rule remove`)
A confirmation run tried to delete a referenced group inside `apply` and tripped twice: the `apply` `group remove` op rejects `cascade` (`RK_UNKNOWN_ARG`, "valid args: groupPathInModel"), and `apply` has **no `rule remove` op** (`RK_UNKNOWN_OP` — its rule ops are add/modify/rename only). Standalone has **both** (`group remove --cascade`, the `rule remove` verb). So a "delete a referenced group/rule" refactor **can't be expressed atomically in `apply`** — it forces a drop to standalone calls. Same uniformity class as the export-`-m` / workspace-`-w` findings, between the standalone surface and the `apply` op-set. **Recorded as a capability gap → FEATURE-GAPS-LEDGER G12** (its durable home; this report is the session finding). Fix: add the `cascade` key to the `apply` `group remove` op + a `rule remove` `Op`.

## Harness finding (CRITICAL, FIXED) — the probe subagent inherited the runner's TaskList (context leak + bias)
The Agent-tool subagent **inherits the main-loop session's TaskList** via system-reminders. A task named *"01-de confirmation run (findings 6+7)"* leaked into this run's context — the agent referenced *"findings 6+7"* and then **avoided the `group extract` path (Finding 6)**, `--dry-run`'ing it and choosing `include add` as "cleaner". So **this confirmation run is contaminated** (biased away from the path under test) and is **discarded** for Finding-6 coverage; 6 & 7 are instead confirmed by the deterministic CLI repro (`scratchpad/extract-exp.sh` → sub-model valid standalone, `rule modify` → structured refusal) + the JUnit guards. **Verified clean:** the project `CLAUDE.md`, the memory, and git status do **not** leak — only the TaskList does. **Fix:** the runner now MUST clear its TaskList before spawning (the Agent tool can't suppress the inheritance), and `audit-probe.sh` **check 5** flags a leaked `task #<n>` → SUSPECT (does-fire verified; this run yields SUSPECT). The append-only-log / sandbox-not-reset worry the agent raised is a non-issue — `prep-probe.sh` wipes the sandbox + truncates the log at setup.

## Standing low finding (carried from 01b)
**F1:** `operators` has no narrowing flag; low priority → recorded as **FEATURE-GAPS-LEDGER G13**.
