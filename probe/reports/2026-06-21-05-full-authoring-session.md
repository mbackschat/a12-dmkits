# Probe report — task 05 (full authoring session), 2026-06-21

**Headline (the proactive win): the v0.6.0 annotation-crash class is confirmed CLEARED through a realistic agent session.** Turn 3 attached a `roles` header annotation and turn 5 extracted/included an address sub-model — the exact serialize/expand path that crashed natively — and the digest shows **0 RED FLAGS**: every one of the 21 non-zero exits surfaced as a **structured envelope**, never a raw trace. Combined with the deterministic native-battery (which still FAILs this path on a regressed binary), this is full catches→clears coverage.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (fixed: annotation getters re-captured, `RK_INTERNAL_ERROR` boundary) |
| anti-cheat audit | **clean** 4/4 — material boundary respected; model files mutated only by `dmtool` (no hand-edits → repro is trustworthy) |
| digest | **0 RED FLAGS**; 21 structured rejections; retry signals on `workspace roles` (×7) and the wildcard rules |
| report authorship | narrative = the **probe agent's own** end-of-session report (it holds the *why*); verification = the harness audit/digest + maintainer minimal-repro. *(SendMessage to continue a finished agent was unavailable this run; future runs append `report-pass.md` as a final turn — pointing the agent at the shipped `a12-dmtool-bug-report` skill — so the agent writes the full report itself.)* |

## Findings (classified — defect vs friction vs expected)

| # | finding | target | class | verified |
|---|---|---|---|---|
| F1 | **`string.patternMessage` is a single string, not per-locale** — a patterned STRING field can't carry a localized validation message on a bilingual model; and `field modify` can't edit a field's per-kind config block to fix it after the fact | CLI / material | **DEFECT (gap)** | minimal repro ✓ |
| F4 | **a bare-string `roles:` list in `roles.yaml` silently parses to `definedRoles: []`** (`outcome:read`, exit 0) — looks like success, lints nothing | CLI | **DEFECT (silent mis-parse)** | minimal repro ✓ |
| F2 | `apply` op-record keys diverge from CLI flag names (`rule`/`condition` not `rulePathInModel`; `fieldPathInModel`; rule messages aren't an apply arg) — recovered via the corrective `RK_UNKNOWN_ARG` | CLI / material | friction | digest |
| F3 | wildcard/iteration rule placement is hard: `GroupFilled` rejects `*`, counts need the **absolute** wildcard path, a relative one gives `MVK_INVALID_ENTITY` (~6 retries) | material / skill | friction | digest |
| F5 | `group extract` writes `<reference>.dm.json` but the agent guessed `<reference>.json` → "cannot read model" (the result envelope already reports the real `subModel` path) | skill | friction | digest |
| — | label must cover all declared locales; the §6 `RK_OUTWARD_REFERENCE` extract refusal (gate fired, mutated nothing); age≥18 as the field's `min` | — | **expected / correct** | — |

## The two confirmed defects (minimal repros)

**F1 — localized pattern message is unauthorable.** On a bilingual (`en_US`+`de_DE`) model:
```
field add {string:{pattern:"[0-9]{5}", patternMessage:{en_US:"…", de_DE:"…"}}}
  → rejected  RK_INVALID_VALUE: invalid value for 'string/patternMessage' — expected a value of type string
field modify <a spec carrying only a string config block>
  → exit 2  "nothing to modify — give a kind/typedef to re-type, or a metadata key (…)"
```
So `patternMessage` accepts only a single string (inconsistent with `enum.errorMessages` / `requiredMessages`, which ARE per-locale maps — G23), and `field modify` can't reach the per-kind config block to amend it. A bilingual model with a custom pattern message is therefore unauthorable without delete-and-recreate. **Fix:** accept a per-locale map for `string.patternMessage` (mirror `enum.errorMessages`), and let `field modify` edit the per-kind config block.

**F4 — silent empty roles.** A plausible roles file shape parses to nothing, with no warning:
```
roles.yaml:  roles:\n  - admin\n  - editor      (a list of bare strings)
workspace roles <ws>  → outcome:read, exit 0, definedRoles: []
```
The required shape is `roles: [{name: admin}, …]`; the bare-string list is accepted and silently yields zero defined roles (so the RBAC lint then checks nothing). **Fix:** reject/warn when a roles file parses to zero defined roles, or accept the bare-string form. *(The top-level-array shape IS corrective — "must be a mapping with a `roles:` list" — it's the bare-string-in-a-mapping case that's silent.)*

## Replay log (verbatim, in order)

1. create a document model for a person with a home and work address, add proper conditions. And ensure the person's age is >= 18.
2. add a German locale to the person model.
3. create some users and roles and attach them to the model.
4. change the repeatability of the work address to 10, and somehow flag the main work address.
5. move the fields of the address group to a separate dm and include it for both home and work.

## Self-audit

The anti-cheat audit confirms **tool-only mutations** — no model file was hand-edited (no `sed`/`echo`/editor write), and no repo/gold/source path was read. The repro is clean; the findings are not artifacts of out-of-band edits.

## Next

F1 and F4 are confirmed tool defects (not the agent's mistakes). Per the discover→fix→graduate loop, each should be fixed at root and locked with a guard (F1: a `string.patternMessage` per-locale-map test + `field modify` reaching the config block; F4: a `workspace roles` test that a zero-defined-roles parse warns/refuses). The friction items (F2/F3/F5) are skill/material refinements.

## Resolution (2026-06-22)

All five findings are fixed at root and guarded — the discover→fix→graduate loop is closed for this run.

| # | fix | graduated guard |
|---|---|---|
| F1(a) | `string.patternMessages` is a per-locale map (mirrors `enum.errorMessages`) | `FieldAddSpecTest.stringSpecWithPerLocalePatternMessages` |
| F1(b) | `field modify` with a config block but no `kind` now gets a precise corrective (name the kind, warn the re-type replaces) — same for the apply op | `FieldModifyCommandTest.aConfigBlockWithoutAKindGetsAPreciseCorrective` + `ApplyVerbTest.aFieldModifyOpWithAConfigBlockButNoKindGetsTheCorrective` |
| F2 | apply op-record locator keys aligned to the manifest/standalone (`rulePathInModel`/`computationPathInModel`); apply `rule modify` reaches condition/severity/messages | `ApplyVerbTest.everyPathLocatedApplyOpAcceptsTheLocatorKeyTheManifestAdvertises` (class-guard) + the rule/computation modify/rename tests + the CI-executed `apply.schema` example |
| F3 | skill: `*` flattens a field inside an aggregate (never a group ref); absolute wildcard path across branches; row-count is `NumberOfFilledGroups` | skill judgment (N6 material) |
| F4 | a nameless `roles:` entry is rejected, not silently dropped | `RolesFileTest.aRolesEntryWithoutANameIsRejectedNotSilentlyDropped` |
| F5 | `group extract`'s `--reference` help + skill point at the result's `subModel` path (`<reference>.dm.json`) | self-describing surface (help text) + skill note |
