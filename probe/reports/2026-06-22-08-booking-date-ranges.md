# Probe report — task 08 (venue booking, date ranges + time), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS, 0 nonzero exits.** Every condition was kernel-valid on the first composed attempt. The date-range/time surface — `DATERANGE`/`TIME` field kinds, `StartOfDateRange < Today`, model-level `DateRangesOverlap(/Bookings*/Period)`, a `Time(...)` window, and a `DifferenceInDays > 30` duration rule — built and validated clean. One low-severity DX finding about `rule explain` coverage.

## Provenance & integrity

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1) — release candidate, gate GREEN |
| start | **clean** (from-scratch venue-booking model) |
| audit | **CLEAN 6/6** — `VenueBooking_DM.json` mutated only by dmtool; no raw read |
| digest | **CLEAN · 0 RED FLAGS · 0 nonzero exits** — every edit accepted first try |

## Findings

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 08-explain-lhs | a comparison whose **left** operand is a function call (`StartOfDateRange(Period) < Today`) is **valid** and round-trips via `rule read`/`format`, but the N8 typed-AST converter can't model it → `rule explain` returns `explained:false` / Opaque with a developer `reason` citing `IComparisonCondition`. Bisected: a function call on the **RHS** (`[X] < Time(...)`) and inside aggregates both convert fine — only the comparison-LHS position is uncovered. | CLI (converter coverage) | **low DX — safe by design** | the Opaque passthrough is the *designed* safe fallback (the rule is preserved + valid); candidates — (a) phrase `explained:false` on a valid rule as "valid, no structured explanation yet", (b) extend the converter to the function-call-LHS shape (a feature) |
| — | the operator catalog's `DateRangesOverlap` gotcha steered to the correct model-level form (avoiding the self-membership trap); the verified `DATERANGE`/`TIME` config notes in `schema field add` made structure-building first-try | — | **positive** | — |

## Replay log (verbatim, in order)
1. create a venue booking model … a booking period (a date range) and a daily start time.
2. a booking can't start in the past …
3. no two bookings may have overlapping periods.
4. the daily start time must be between 9 in the morning and 5 in the afternoon.
5. a booking longer than 30 days needs manager approval.

## Self-audit
CLEAN: every mutation via dmtool; no raw `.dm.json` read (inspection via describe/read/validate + rule read/explain/format). Audit 6/6, digest 0 RED FLAGS.

## Resolution (2026-06-22)
- **CLEAN re-run** — the date-range/time surface is solid; no defect.
- **08-explain-lhs — recorded, low / safe-by-design.** The converter's coverage boundary returns a safe Opaque (valid, verbatim); extending it (function-call-LHS) is a feature, not a fix. A wording nicety (`explained:false` vs "valid, unmodeled") is the only quick candidate.
