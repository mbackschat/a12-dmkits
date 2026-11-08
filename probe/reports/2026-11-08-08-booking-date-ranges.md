# Probe report — task 08 (booking date ranges), 2026-11-08

**Headline: both tiers CLEAN; sonnet matched the gold on all five turns, haiku on four.** Haiku's overlap rule (T3) is guarded by `FieldFilled(/VenueBooking/VenueName)`, so it never fires while the venue name is empty. The skill taught that guard: its error-field paragraph offers `FieldFilled(<errorField>)` as the remedy for a model-level aggregate, which narrows the rule whenever the aggregate has no cap to compare against.

## Provenance and integrity

| | |
|---|---|
| native binary | `a12-dmkits 0.15.0`, kernel 31.3.1; reflection metadata re-captured (`3be7233be`); `scripts/native-gate.sh` battery green (212 checks, 0 signatures), every demo green after re-recording `cli-discover.md` |
| start | `clean` (from scratch) |
| agents | first runs under [`clean-agent.ts`](../../scripts/typescript/clean-agent.ts): no CLAUDE.md, memory, skill listing, plugin or MCP server; turns sent one at a time; OS sandbox, no refused call. haiku at default effort, sonnet at `xhigh` |
| audit | **CLEAN 7/7** on both |
| shared temp directory | the six sessions ran four at a time with one `TMPDIR` (fixed afterwards in `5d020421b`); both 08 agents wrote `rule_long.json` and `rule_overlap.json` there, but neither call log names the other agent's fields (`Venue`/`ManagerApproval` against `VenueName`/`ManagerApproved`), so no file crossed |
| digest | haiku 49 calls, 4 nonzero, 0 RED FLAGS · sonnet 134 calls, 11 nonzero, 0 RED FLAGS |

## Gold result

| turn | haiku | sonnet |
|---|---|---|
| T1 `DATERANGE` period + `TIME` start in a repeatable group | ✓ | ✓ |
| T2 `StartOfDateRange(Period) < Today`, presence-guarded | ✓ | ✓ |
| T3 cross-row overlap over `Bookings*/BookingPeriod` | ✗ guarded by `FieldFilled(VenueName)` | ✓ `DateRangesOverlap`, unguarded |
| T4 `"09:00:00"` / `"17:00:00"` time literals | ✓ guarded | ✓ unguarded (correct: an empty TIME operand is `NOT_EVALUATED`, per `dmtool operators`) |
| T5 duration over 30 days and no approval | ✓ `> 30`, `FieldNotFilled` | ✓ `> 29` (inclusive reading, flagged to the analyst), `!= True` |

## Observations and disposition

Each row was re-run against the native binary; the agents' own claims were not taken as evidence.

| observation | basis | disposition |
|---|---|---|
| The skill's `FieldFilled(<errorField>)` remedy for an unreferenced error field narrows a model-level rule to documents where that field is filled | haiku's stored T3 condition; [`skill/SKILL.md`](../../skill/SKILL.md) error-field paragraph | skill defect, fixed in `b8fa81768` (the skill now points to the in-row error field; `AggregateErrorFieldLawsTest` locks both placements on the kernel runtime) |
| `[DailyStartTime] < Time(9)` is rejected `MVK_INVALID_ENTITY` with the corrective "use its ABSOLUTE path" | `rule check` | misleading enricher fix: `Time` exists and takes quoted constants (`Time("9")` is accepted), so the unquoted `9` is resolved as a field name; [CG29](../../docs/CLI-CONFORMANCE-GAPS.md#cg29). *Corrected 2026-11-08: this row first said there is no `Time` function; `dmtool operators Time` serves it.* |
| `AllFieldsFilled(BookingPeriod)` with one operand is rejected `MVK_PARAMSIZE_INVALIDN`; the corrective leads with `FieldValuesNotUnique` and then gives the general remedy (two fields, one group, or `FieldFilled` for a single field) | `rule check` | not a defect. *Corrected 2026-11-08: this row first said the corrective names only `FieldValuesNotUnique`.* |
| field kinds are spelled `DATERANGE`, `DATETIME`, `DATEFRAGMENT`, `ENUM` on input and `DATE_RANGE`, `DATE_TIME`, `DATE_FRAGMENT`, `ENUMERATION` on output; the parser silently accepts both | `schema field add`, `field read`, `FieldSpec.java` | hidden alias; choosing one spelling is a contract decision ([CG28](../../docs/CLI-CONFORMANCE-GAPS.md#cg28)) |
| `model describe` lists no rules and no `required` flags; the agents found `model read` and `field read` per field | `model describe` output; raised by both runs | discoverability gap ([CG30](../../docs/CLI-CONFORMANCE-GAPS.md#cg30)) |

## Replay prompts

The five turns of [task 08](../tasks/08-booking-date-ranges.md), verbatim, after [`agent-preamble.md`](../agent-preamble.md).

## Self-audit

Both agents wrote their own `REPORT.md` and classified their own mistakes (a short rule name to `rule read`, an unquoted `Time(9)` argument, which its author classified as an invented function) separately from tool findings. Neither read model JSON directly.
