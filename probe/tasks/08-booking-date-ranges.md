# Probe task 08 — a venue booking (date RANGES · time-of-day · range operators)

**Start:** `clean` (from-scratch — the agent builds the booking model with `dmtool model new`).

`01b` covers *point* dates (`Today`/`DifferenceInDays`/date ordering); **nothing in the suite reaches the date-RANGE / time field families or their operators** — `DATERANGE` / `TIME` field kinds, `StartOfDateRange`/`EndOfDateRange`, `DateRangesOverlap`/`AtLeastOneDateRangeOverlaps`, time-of-day literals. This task is a booking/scheduling session built around those. Own neutral domain (e-commerce venue booking, no tax/gov; distinct from 07's insurance, 06's order, 05's person, 03's subscription).

**What it probes (the date-range/time surface 01b doesn't reach):**

- **`DATERANGE` and `TIME` field kinds** — a booking period (a single date-range field, start..end) and a daily start time.
- **range-endpoint operators** — `StartOfDateRange(Period)` / `EndOfDateRange(Period)` to compare or measure a range.
- **range-overlap over a repeatable group** — "no two periods overlap" (`DateRangesOverlap` / `AtLeastOneDateRangeOverlaps`), a cross-row range check distinct from `RepetitionNotUnique`/`FieldValuesNotUnique`.
- **time-of-day literals + comparison** — German-format time constants (`"09:00:00"`) compared against a `TIME` field.
- **range duration** — `DifferenceInDays(StartOfDateRange(Period), EndOfDateRange(Period))` for a conditional rule.

## Anti-overfitting (shared with 05/06/07)

Intent-level prompts; the agent discovers the range/time operators from `dmtool operators` (their meaning + the German time/date literal format), and that discovery is where friction shows. A clean run is success; the value is a wrong operator (point-date op on a range, or a rule where a field property fits), a literal-format slip, or a cross-row overlap the agent can't express. If green-and-boring, rotate the domain (a shipping window, a subscription billing period) or grow toward an un-probed construct (`DATEFRAGMENT`, `RangeAsNumber`).

## The session (multi-prompt, in order — do not look ahead)

**Turn 1**
> create a venue booking model: a venue, and a repeatable list of bookings, each with a booking period (a date range) and a daily start time.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> a booking can't start in the past — its period must not begin before today.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> no two bookings may have overlapping periods.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> the daily start time must be between 9 in the morning and 5 in the afternoon.

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> a booking longer than 30 days needs manager approval.

## Gold (matched SEMANTICALLY — the SHAPE, not exact text/operators)

- **T1** a repeatable `Bookings` group with a `Period` field of kind **DATERANGE** and a `StartTime` of kind **TIME** (field properties, not rules — the skill's gate).
- **T2** a per-row rule whose violation is `StartOfDateRange(Period)` before `Today` — e.g. `DifferenceInDays(Today, StartOfDateRange(Period)) < 0` (or the equivalent ordering), presence-guarded (`GroupFilled(/.../Bookings) And …` since it iterates). Exercises **`StartOfDateRange` + `Today`**; the agent must reach for a range-endpoint op, not treat the range as a point date.
- **T3** a cross-row **overlap** check over `Bookings*/Period` (`DateRangesOverlap` / `AtLeastOneDateRangeOverlaps`, whichever the catalog supports) — distinct from a uniqueness check. The interesting signal: does the agent discover a range-overlap operator, and get its scope/wildcard right?
- **T4** a per-row rule on `StartTime` (TIME) comparing against German-format time literals `"09:00:00"` / `"17:00:00"` — violation = outside the window. Exercises the **time literal format** (the date/time-constant trap, like 01b's date literals).
- **T5** a conditional rule: a long booking (`DifferenceInDays(StartOfDateRange(Period), EndOfDateRange(Period)) > 30`) **and** `FieldNotFilled(ManagerApproval)` — range **duration** + conditional requiredness (so a `ManagerApproval` field is added and gated by the duration).

## What to mine

- **Range vs point-date confusion** — did the agent treat the `DATERANGE` as a point date, or discover `StartOfDateRange`/`EndOfDateRange`? Any `MVK_INVALID_TYPE_FOR_COMPARISON` from comparing a range directly?
- **Overlap operator (T3)** — discovered, or fumbled? Scope/wildcard right? Any contradictory wildcard rejection (like the `RepetitionNotUnique` family)?
- **Time literal (T4)** — German `"HH:mm:ss"` format found, or an ISO/`9:00` slip rejected? Did the diagnostic's fix steer right?
- **Field-property-vs-rule (T1, T5)** — `Period`/`StartTime` declared as field properties; the duration constraint as a conditional rule (the promotion).
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR` / `outcome:error` / exit 70.
