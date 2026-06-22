# Probe report — task 07 (price a policy), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A re-run on the release binary (post kind-vocab + the locator/field-read fixes). The full calculation cluster — `Sum` aggregate, a tiered/`FirstFilledValue` discount, a `Having`-filtered cap rule, decimals/scale + units, and computed-field chaining (`NetPremium = TotalPremium − AppliedDiscount`) — authored kernel-valid. One genuine **cosmetic** `[CLI]` defect (07-summary-mislabel); prior fixes (dec-scale enricher, the kind vocabulary) confirmed in the wild.

## Provenance & integrity

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1) — release candidate, gate GREEN |
| start | **clean** (from-scratch policy model) |
| audit | **CLEAN 6/6** — `Policy_DM.json`/`Repro_DM.json` mutated only by dmtool; no raw `.dm.json` read |
| digest | **0 RED FLAGS** — 7 nonzero exits, all legitimate `field modify` rejections (the add-`kind` corrective, the array/bad-key repro probes) |

## Findings

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 07-summary-mislabel | `field modify` handed a JSON **array** rejects with `RK_INVALID_VALUE` ("expected a value of type object") — correct — but the envelope `summary` says **"1 unrecognized key in the spec"** (a hardcoded summary); the genuine unknown-key case shows the same summary (correctly). The summary doesn't track the actual diagnostic kind. | CLI (envelope) | **DEFECT (cosmetic, confirmed)** | reproduced (array → `RK_INVALID_VALUE` but summary "unrecognized key"; bad-key → `RK_UNKNOWN_ARG` + same summary); fix candidate — summary should reflect the diagnostics (wrong-type vs unknown-key), in the shared spec-verb path |
| — | the `MVK_INVALID_COMPARE_DEC_PLACES` enricher hint named the `Premium`↔`Sum`-computed-`TotalPremium` decimal-scale coupling → re-scaled both in one apply; the `field modify` "add `kind`/modify REPLACES the type" corrective; the `kind` vocabulary on read/echo | — | **prior fixes confirmed in the wild** | — |

## 07-summary-mislabel — minimal repro (verified)

```
field modify <a JSON ARRAY>   → outcome refused, diag RK_INVALID_VALUE "expected … object", summary "1 unrecognized key in the spec"  ← mislabel
field modify {…,"bogusKey":1} → outcome refused, diag RK_UNKNOWN_ARG "unknown key 'bogusKey'",  summary "1 unrecognized key in the spec"  ← correct
```

## Self-audit
CLEAN: every mutation via dmtool; spec inputs were files fed to the verbs; no raw `.dm.json` read. Audit 6/6, digest 0 RED FLAGS.

## Resolution (2026-06-22)
- **CLEAN re-run** — no crash, no real behavioral defect; the computation depth + dec-scale handling are solid.
- **07-summary-mislabel — confirmed, fix candidate** (the shared spec-key rejection summary should track the diagnostic kind, not hardcode "unrecognized key").
