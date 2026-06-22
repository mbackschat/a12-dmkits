# Probe report — task 03 (subscription computation), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 2 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A re-run on the freshly-rebuilt native binary (post kind-vocab unification + the secondary read/echo fixes). The full computation cluster — `computation add` (value expression, computed-field self-reference ban, message coverage), `computation modify` (re-express in place), and an aggregate-over-repeat (`Sum(Addons*/MonthlyFee)`) — authored kernel-valid end to end, first try every time. **The new `kind` vocabulary confirmed in the wild:** `field add` echoed `kind: STRING`, `field read` returned `kind: NUMBER` + `number` config, `model describe` reported `kind`; zero `type`/`kind` confusion. One low-severity output-contract finding (03-newoutput) + two doc/material notes.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) — the release-candidate binary, native gate GREEN |
| start mode | **clean** (from-scratch subscription model) |
| anti-cheat audit | **CLEAN 6/6** — `Subscription_DM.json` mutated only by dmtool; `Write` used only for spec *input* files; no raw `.dm.json` read (inspection via `model describe`/`computation read`/dry-run) |
| digest | **CLEAN · 0 RED FLAGS · 0 nonzero exits** — every kernel-checked edit accepted first try |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 03-newoutput | `model new … -o <file>` writes the file but emits **0 bytes / exit 0** — the only write verb with no result envelope (`field add -o`, `apply`, `computation add/modify` all emit one); a machine consumer can't confirm the create from the tool's own signal | CLI (output contract) | **minor DX (confirmed)** | reproduced (0 bytes stdout+stderr, file written); fix candidate — emit the standard envelope on the `-o` branch |
| 03-scale | `--kind NUMBER` (flags mode) defaults to `maxFractionalDigits: 0` (integer); decimals only via the rich `<spec>` `number` block — surprising for a monetary fee | CLI/material | minor (confirmed) | recorded — a `--help` nudge candidate; not a defect (decimals are reachable) |
| 03-pathnorm | a computation operation written with absolute cross-branch paths (`Sum(/Subscription/Addons*/…)`) reads back rebased (`Sum(../Addons*/…)`) — correct kernel normalization, not byte-identical to the input | material | not a defect | recorded — a one-line read-back doc note candidate |
| — | the `kind`-vocabulary unification (this session) — `field add` echo + `field read` + `model describe` all spoke one `kind` vocabulary; no `type`/`NumberType` leak | — | **prior fix confirmed in the wild** | — |

## 03-newoutput — minimal repro (verified)

```
dmtool model new --id Probe_DM --locale en_US --root Probe -o Probe_DM.json
#  -> exit 0, stdout 0 bytes, stderr 0 bytes; Probe_DM.json IS written + kernel-valid
dmtool -m Probe_DM.json field add --group /Probe --name X --kind STRING -o out.json
#  -> exit 0, the standard {"outcome":"applied","ok":true,"written":…,"output":…} envelope
```
The inconsistency is isolated to `model new`'s `-o` path (every other write verb emits the envelope with or without `-o`).

## Replay log (verbatim, in order)
1. Create a document model for a software subscription … effective monthly fee is calculated as twice the base fee.
2. Change the effective-fee calculation so it is twice the base fee plus the total of all the add-ons' monthly fees.

## Self-audit
CLEAN: every model mutation via dmtool (`model new`, `apply`, `computation add`/`modify`); `Write` only for spec inputs; no raw `.dm.json` read. Confirmed by the audit (6/6) and digest (0 RED FLAGS).

## Resolution (2026-06-22)
- **CLEAN re-run on the release binary** — no defect, no crash; the computation surface is solid and the `kind`-vocabulary unification holds in the wild.
- **03-newoutput — confirmed, fix candidate** (emit the result envelope on `model new -o`); **03-scale / 03-pathnorm — recorded, below the defect bar** (a `--help` nudge / a read-back doc note).
