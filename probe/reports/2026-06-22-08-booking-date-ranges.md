# Probe report — task 08 (venue booking, date ranges), 2026-06-22

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN, the v0.4-era date-format thrash gone.** A venue-booking model (a venue + a repeatable booking list with a `DATERANGE` period and a `TIME` start, plus four rules) was authored kernel-valid end to end on the **fix-E** binary — `dd.MM.yyyy`+`-` and `HH:mm:ss` were used first-try, no field-add thrash (the pre-fix run had 32% nonzero + 23 field-add failures; this one 6%). Four DX findings, all now fixed.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) |
| start mode | **clean** (from-scratch booking model) |
| anti-cheat audit | **CLEAN** — `VenueBooking_DM.json` mutated only by dmtool; no raw `.dm.json` read |
| digest | **CLEAN 6/6 · 0 RED FLAGS · 6% nonzero** (was 32% + 23 field-add fails on the pre-fix-E binary — the date/time format schema fix removed the thrash) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 08-overlap | per-row `AtLeastOneDateRangeOverlaps(Period IN Group*/Period)` is `valid:true` but the current row's range is in its own list, so it overlaps itself and fires on every row — no self-exclusion, and native has no sample-doc run to test it | material (catalog) | **DEFECT (semantic trap)** | **FIXED** — self-membership gotcha `9b34eea` |
| 08-entity-hint | a same-group/relative wildcard fails `MVK_INVALID_ENTITY` ("the group or field 'X' is not valid") with no hint to use the absolute starred path | CLI (diagnostics) | **DEFECT (no corrective)** | **FIXED** — `1ff5197` |
| 08-bracket | `Approved != True` (a field-as-value operand without `[…]`) fails `MVK_UNEXPECTED_TOKEN`, but the hint listed booleans/strings/PascalCase, not the bracket-the-operand cause | CLI (diagnostics) | **DEFECT (incomplete hint)** | **FIXED** — this commit |
| 08-kind | `field add --kind` help listed only 5 of 11 kinds (TIME/DATERANGE/DATETIME/DATEFRAGMENT/ENUM/CUSTOM absent — reachable only via the rich spec) | CLI | **DEFECT (under-described)** | **FIXED** — `6529778` |
| — | the rich `field add` DATERANGE `(format,separator)` pairs + the "TIME needs HH:mm:ss" note + the date/time operator catalog (`StartOfDateRange`, `DifferenceInDays`, `Today`, closed-interval overlap) made every date/time rule first-try | — | **positive** | fix E + catalog |

## The agent's bisect (one construct at a time)

| Construct | Verb | Result |
|---|---|---|
| `AtLeastOneDateRangeOverlaps(Period IN Bookings*/Period)` (relative, from inside the group) | `rule check` | rejected `MVK_INVALID_ENTITY` |
| same, absolute `…IN /…/Bookings*/Period` | `rule check` | `valid:true` (but self-membership — see 08-overlap) |
| `DateRangesOverlap(/…/Bookings*/Period)` + `FieldFilled(Venue) And …` (model-level, error field referenced) | `rule check`/`rule add` | `valid:true` — **the form shipped** (no self-membership question) |
| `[DailyStartTime] < "09:00:00" Or > "17:00:00"` (TIME literal) | `rule check` | `valid:true` first try |
| `DifferenceInDays(StartOfDateRange(P), EndOfDateRange(P)) > 30` (range length) | `rule check` | `valid:true` first try |
| `DATERANGE`/`TIME` via the rich `<spec.json>` | `field add` | accepted, persisted (`field read` confirms) |

## Replay log (verbatim, in order)
1. create a venue booking model: a venue, and a repeatable list of bookings, each with a booking period (a date range) and a daily start time.
2. a booking can't start in the past — its period must not begin before today.
3. no two bookings may have overlapping periods.
4. the daily start time must be between 9 in the morning and 5 in the afternoon.
5. a booking longer than 30 days needs manager approval.

## Self-audit
CLEAN: every mutation via dmtool verbs (`model new -o`, one `apply`, one `field add`, four `rule add`); no editor/`sed`/`cp`/raw-edit touched the model; no raw `.dm.json` read (inspection via `model describe`/`field read`).

## Resolution (2026-06-22)
- **08-overlap — FIXED (`9b34eea`).** `AtLeastOneDateRangeOverlaps` gains a self-membership gotcha: a per-row `P IN Group*/P` includes the current row, so it always overlaps itself and fires on every row; for "no two rows overlap" use a model-level `DateRangesOverlap(/Group*/Period)` over the single flattened list (fires once for the document). The catalog (per-operator) is the home.
- **08-entity-hint — FIXED (`1ff5197`).** `MVK_INVALID_ENTITY` is enriched to lead with the absolute-path form (then the iteration-scope alternative) — the corrective the agent had to get from the skill, not the tool.
- **08-bracket — FIXED (this commit).** The `MVK_UNEXPECTED_TOKEN` hint now lists cause (4): a field used as a comparison VALUE must be `[Approved] != True`, not `Approved != True`. Verified against the fresh binary; locked by `DiagnosticEnricherTest`.
- **08-kind — FIXED (`6529778`).** `field add --kind` help now names the config-bearing kinds (TIME/DATETIME/DATERANGE/DATEFRAGMENT/ENUM/CUSTOM) and points at the rich `<spec.json>`; locked by `CliHelpTest`.
- **(prior fix confirmed in the wild)** the date/time field-format schema fix (fix E) removed the 23-failure field-add thrash — `dd.MM.yyyy`+`-` and `HH:mm:ss` were used first-try.

## Wave-2 re-run (2026-06-22, on the rebuilt binary carrying every 08 fix) — fixes validated in the wild

Re-ran 08 from scratch on the fully-rebuilt native binary (native gate GREEN, 168 checks). **CLEAN — 0 RED FLAGS, anti-cheat audit CLEAN 6/6, no internal errors**; all four rules `valid:true`. The fixes landed where it counts — in a live agent session, not just a unit test:

- **08-overlap — VALIDATED.** Faced with "no two bookings may overlap" (turn 3), the agent chose the **model-level `DateRangesOverlap(/…/Bookings*/BookingPeriod)`** over the per-row `AtLeastOneDateRangeOverlaps` — *and named the reason*: the operator's gotcha "warns self-matches the current row and fires on every row (a `valid:true` semantic trap)." The #81 gotcha steered it past the exact trap the wave-1 agent could only flag as unverifiable.
- **08-entity-hint / 08-bracket — not re-triggered** (the agent went absolute + bracketed from the skill up front), so no regression; both remain unit-locked.
- **Locator corrector (#80) — VALIDATED.** The agent tried `rule read --rule <path>` (the wrong locator flag); the shim log shows the corrective fired verbatim — *"→ dmtool rule read locates its target by a POSITIONAL `<rulePathInModel>`, not a flag … (you passed --rule)"* — and the agent recovered to the positional. The cross-verb locator inconsistency now self-corrects in the wild.
- **New finding (W2-1) — graceful, not a defect.** `rule read` returns `converted:false` (Opaque) *with exact `canonicalText`* when a comparison's **left** operand is a bare function (`StartOfDateRange(BookingPeriod) < Today`); the symmetric `value > function` form (turns 4/5) converts cleanly. An AST-converter asymmetry, tolerantly handled (no data loss, the rule is valid and persists; `rule read` shows the canonical text) — the designed Opaque fallback, below the fix bar. Recorded as a converter-coverage note, not a blocking gap.
- **Two skill/material notes** (the skill lacks the model-level overlap idiom; the field-property gate's min/max doesn't apply to TIME) — minor; the catalog gotcha already carries the overlap idiom, and the skill stays minimal per the placement rule.
