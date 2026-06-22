# Probe report — task 02 (registration field constraints), 2026-06-22

**Headline: the run on the F1/F4/A–E-fixed binary is CLEAN — 6/6 turns, 0 RED FLAGS, audit CLEAN — no internal errors.** Authoring a member-registration model (six fields, one enum, one conditional-requiredness rule) and making it bilingual produced a kernel-valid model end to end; every rejection was a legitimate verdict. It surfaced **three DX findings, all in the "going bilingual" area**, each now fixed: a schema-vs-validator inconsistency on `patternMessages`, the asymmetric locale-coverage cascade, and the opaque `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` summary.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) |
| start mode | **clean** (from-scratch authoring) |
| anti-cheat audit | **CLEAN** — `MemberRegistration_DM.json` mutated only by dmtool; hand-authored files were input spec JSONs the tool consumes; no raw `.dm.json` read |
| digest | **CLEAN 6/6 · 0 RED FLAGS** (was 4 RED FLAGS + a SUSPECT raw-read on the pre-fix binary — the read-gap that drove the python read is closed) |
| vs. the pre-fix run | the original run hit the field-add **exit-70 crash** (fix A) and a **SUSPECT raw read** (no per-kind read-back, fix B); both are gone — the crash rejects cleanly, the constraint is confirmable via `field read` |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 02-patmsg | `pattern` + `patternMessages` (no singular `patternMessage`) is rejected (*"a pattern and its error message must be set together"*), but the schema sold `patternMessages` as a standalone override | material (schema) | **DEFECT (misleading contract)** | **FIXED** — `9b34eea` |
| 02-enum-locale | the opaque `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` (*"display value and XML value were not specified together"*) hides a per-locale enum-label gap after `--add-locale` | CLI (diagnostics) | **DEFECT (opaque message)** | **FIXED** — `1ff5197` |
| 02-cascade | the locale-coverage cascade is asymmetric + undocumented: a 2nd locale forces every enum value's labels + every NEW field's label to cover it, while pre-existing single-locale field labels are tolerated | skill | DX gap | **FIXED** — skill "going bilingual" note `fffc1f7` |
| — | `field modify`'s type-replacement diagnostic (*"a string block alone can't re-type — add kind"*) and apply's atomic rollback worked correctly | — | **expected / positive** | — |

## The bisect (the locale cascade) — the agent's affected/unaffected table

| Per-locale text set on… | only `en_US` | after a 2nd locale is declared |
|---|---|---|
| **enum value labels** | yes | **REJECTED** at the terminal gate — `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` |
| **a NEW field's label** | yes | **REJECTED** by the field-add precheck — "label is missing declared locale 'de_DE'" |
| **an EXISTING field's label** | yes | **TOLERATED** — model still validates |

And for the `patternMessages` finding (toggling only the message keys): `pattern`+`patternMessage` → accepted; `pattern`+`patternMessage`+`patternMessages` → accepted; `pattern`+`patternMessages` (no singular) → **rejected**; `pattern` only → rejected.

## Replay log (verbatim, in order)
1. create a member registration model: a member with a name, an email, a country, and a membership tier (basic, plus, premium). Make the obviously-required ones required.
2. the member id must look like M followed by six digits.
3. when the member id format is wrong, show the message in both English and German.
4. the display name must be between 2 and 50 characters.
5. a referral code is optional in general, but required when the tier is premium.

## Self-audit
CLEAN: the model was mutated only through dmtool verbs; hand-authored files were input spec JSONs the tool consumes; no `cat`/`jq`/raw `.dm.json` read (all inspection via `model describe`/`field read`/`rule read`/`config read`/`model validate`) — so no `[material]` read-gap is owed (the read-gap that the pre-fix run papered over with a python read is closed).

## Resolution (2026-06-22)
- **02-patmsg — FIXED (`9b34eea`).** The `field-add`/`typedef-add` schemas now state plainly that a `pattern` REQUIRES the singular `patternMessage` (the base), and `patternMessages` is an OPTIONAL additive per-locale override that does NOT stand alone. Verified against the binary (`pattern`+`patternMessages` alone → rejected; with `patternMessage` → applied).
- **02-enum-locale — FIXED (`1ff5197`).** `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` is enriched (`StructuralKernelFix`) to name the per-locale enum-label gap + the `--add-locale`-in-one-`apply` remedy. Locked by `DiagnosticEnricherTest` + the real-kernel `ConfigVerbTest.addingALocaleRejectsAnEnumWithSingleLocaleLabelsAndNamesTheCause`; the kernel fact is recorded in KERNEL-FINDINGS.
- **02-cascade — FIXED (`fffc1f7`).** The skill gains a concise "going bilingual" note (the asymmetric cascade + do it in one `apply`), verified against the binary.
- **(prior fixes confirmed in the wild)** the field-add exit-70 crash on incomplete locale coverage (fix A) now rejects cleanly; the per-kind config read-back (fix B) lets a constraint be confirmed via `field read` — the two breaches the pre-fix run showed are gone.
