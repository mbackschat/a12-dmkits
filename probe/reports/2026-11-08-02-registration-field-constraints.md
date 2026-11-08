# Probe report — task 02 (registration field constraints), 2026-11-08

**Headline: both tiers CLEAN and matched the gold on all five turns, choosing properties over rules exactly where the skill's gate says to; both hit the `field modify` replace behaviour.** A spec that names the field's current `kind` replaces the whole per-kind config, so a partial edit silently removes `maxLength`, `pattern` and the pattern messages.

## Provenance and integrity

| | |
|---|---|
| native binary | as in [the task 08 report](2026-11-08-08-booking-date-ranges.md) |
| start | `clean` (from scratch) |
| agents | first runs under [`clean-agent.ts`](../../scripts/typescript/clean-agent.ts); haiku at default effort, sonnet at `xhigh` |
| audit | **CLEAN 7/7** on both |
| shared temp directory | both agents built a `Repro_DM` model in the same `$TMPDIR/repro` (one `TMPDIR` per user until `5d020421b`), but haiku last used it at 00:16:55 UTC and sonnet first at 00:17:24 UTC, so neither read the other's file |
| digest | haiku 71 calls, 10 nonzero, 0 RED FLAGS · sonnet 101 calls, 7 nonzero, 0 RED FLAGS; most nonzero exits are the agents' own `field modify` bisects |

## Gold result

| turn | haiku | sonnet |
|---|---|---|
| T1 `required` properties, tier as an enum | ✓ | ✓ (enum labels in both locales) |
| T2 member id as a STRING `pattern` property | ✓ `M[0-9]{6}` | ✓ |
| T3 per-locale `patternMessages` (en, de) | ✓ | ✓ |
| T4 `minLength` 2 / `maxLength` 50 properties | ✓ | ✓ |
| T5 conditional requiredness as a rule | ✓ | ✓ |

## Observations and disposition

| observation | basis | disposition |
|---|---|---|
| `field modify` with `kind` and a partial `string` block stores only that block; `kind` alone clears the string config; with neither, the config is kept; all `applied`, no diagnostic | reproduced with the native binary | defect class shared with `typedef modify`; the schema's "absent ones are left untouched" contradicts it. Fixed in `546ae2cf9`: the schema states the replacement and every lost key is reported as `RK_MODIFY_REMOVES_CONFIG` |
| `config modify --add-locale de_DE` is refused until every message and enum label has a German text, and lists each gap | dry-run | works as intended; both agents completed T3 from the listed gaps |

## Replay prompts

The five turns of [task 02](../tasks/02-registration-field-constraints.md), verbatim, after [`agent-preamble.md`](../agent-preamble.md).

## Self-audit

Haiku corrected three of its own earlier in-session claims in its report (an untested locale rejection, a German runtime message, a `field read` omission) and marked them unverified rather than restating them.
