# Probe report — task 02 (registration field constraints), 2026-06-22 (release binary)

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A re-run on the release binary (post kind-vocab + the locator/field-read/group-read/summary fixes). The field-property surface — required fields, an enum tier (bilingual labels), a `M[0-9]{6}` pattern with a bilingual `patternMessage`, a 2–50 length, and a *conditional* requiredness rule (referral required when tier=PREMIUM) — authored kernel-valid. **This session's bilingual-cascade skill edit confirmed in the wild:** the agent used `config modify --add-locale de_DE --dry-run` to scope the gaps (the exact move the skill now recommends), and the per-locale `patternMessages` coverage worked. Three minor findings, all by-design / kernel-naming.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1) — release candidate, gate GREEN |
| start | **clean** (from-scratch member-registration model) |
| audit | **CLEAN 6/6** — `MemberRegistration_DM.json`/`Repro_DM.json` mutated only by dmtool; no raw read |
| digest | **0 RED FLAGS** — 5 nonzero exits, all legitimate (a `field read --field` locator slip, the `--add-locale --dry-run` + bilingual `apply --dry-run` cascade probes, the `field modify` re-type corrective ×2) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 02-modify-retype | `field modify` frames adding a same-kind constraint as a destructive "re-type" (needs `kind` repeated + the full config block) | CLI/material | **by-design** | modify **replaces** the type; the explicit `kind` + full block prevents the silent partial-config drop (probe 05-modify-drop). The diagnostic gives the precise corrective; softening it reintroduces the drop ambiguity |
| 02-enum-code-naming | the enum locale-coverage reject `MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES` *reads* like an internal fault | material | by-design (kernel name) | the code is the kernel's own; the enricher already attaches a clear per-locale-label fix (the enum-locale cascade hint). No rename possible; the fix is the corrective, which is present |
| 02-apply-written | `apply`'s persistence truth is the wrapper's top-level `written:true`; inner per-ops stay `written:false` | CLI | by-design (cosmetic) | the documented staging contract (per-op staged → one terminal commit); a tail-read should key off the wrapper's `committed`/`written` |
| — | the bilingual cascade via `--add-locale --dry-run` scoping (this session's skill edit), the per-locale `patternMessages`, the enum bilingual labels, and the conditional-requiredness rule all worked first-try | — | **this session's work confirmed in the wild** | — |

## Replay log (verbatim, in order)
1. create a member registration model … name, email, country, membership tier (basic, plus, premium). Make the obviously-required ones required.
2. the member id must look like M followed by six digits.
3. when the member id format is wrong, show the message in both English and German.
4. the display name must be between 2 and 50 characters.
5. a referral code is optional in general, but required when the tier is premium.

## Self-audit
CLEAN: every model mutation via dmtool verbs; spec inputs were files; no raw `.dm.json` read (inspection via `model describe`/`field read`/`rule read`/`model validate`). Audit 6/6, digest 0 RED FLAGS.

## Resolution (2026-06-22)
- **CLEAN re-run on the release binary** — the field-property + bilingual + conditional-requiredness surfaces behaved as specified; the prior `field-add` locale crash + read-gap stay gone.
- **02-modify-retype / 02-enum-code-naming / 02-apply-written — recorded, by-design / kernel-naming / cosmetic** (no change). The bilingual-cascade `--add-locale --dry-run` scoping (this session's skill edit) is confirmed working in the wild.
