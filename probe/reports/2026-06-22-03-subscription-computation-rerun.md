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
| 03-newoutput | `model new … -o <file>` writes the file but emits **0 bytes / exit 0** — vs `field add -o`/`apply`/`computation add` which emit a result envelope | CLI (output contract) | **NOT A DEFECT — by design** | the agent compared to the wrong class: `model new` is an **artifact** verb (`CONTRACT_EXEMPT`, like `export`) — its stdout IS the model JSON, and `-o` redirects that artifact to disk. Its true peer `export --out-dir` is **also** silent on a disk-write (verified). Emitting an envelope on `model new -o` would make it **inconsistent with `export`** — so no change; the artifact-verb contract holds (confirm via the file / exit 0, or run without `-o`) |
| 03-scale | `--kind NUMBER` (flags mode) defaults to `maxFractionalDigits: 0` (integer); decimals only via the rich `<spec>` `number` block — surprising for a monetary fee | CLI/material | minor (confirmed) | recorded — a `--help` nudge candidate; not a defect (decimals are reachable) |
| 03-pathnorm | a computation operation written with absolute cross-branch paths (`Sum(/Subscription/Addons*/…)`) reads back rebased (`Sum(../Addons*/…)`) — correct kernel normalization, not byte-identical to the input | material | not a defect | recorded — a one-line read-back doc note candidate |
| — | the `kind`-vocabulary unification (this session) — `field add` echo + `field read` + `model describe` all spoke one `kind` vocabulary; no `type`/`NumberType` leak | — | **prior fix confirmed in the wild** | — |

## 03-newoutput — reconciled as by-design (verified)

```
dmtool model new --id Probe_DM … -o Probe_DM.json   #  exit 0, 0 bytes stdout/stderr; file written + valid
dmtool -m m.json export --out-dir d/                #  exit 0, also writes files SILENTLY (the artifact-verb peer)
dmtool -m m.json field add … -o out.json            #  emits the {outcome,ok,written,output} envelope (an EDIT verb)
```
`model new` and `export` are the two **artifact** verbs (`SchemaCommand.CONTRACT_EXEMPT`): their stdout is the artifact itself, and a disk-write flag (`-o`/`--out-dir`) sends the artifact to a file, leaving stdout empty. Both behave identically — so `model new -o` is *consistent with its class*, and the apparent "outlier" only looks so when compared to the edit verbs. No change (a confirmation would have to be added to BOTH artifact verbs to stay consistent, and the contract deliberately treats them as artifact producers).

## Replay log (verbatim, in order)
1. Create a document model for a software subscription … effective monthly fee is calculated as twice the base fee.
2. Change the effective-fee calculation so it is twice the base fee plus the total of all the add-ons' monthly fees.

## Self-audit
CLEAN: every model mutation via dmtool (`model new`, `apply`, `computation add`/`modify`); `Write` only for spec inputs; no raw `.dm.json` read. Confirmed by the audit (6/6) and digest (0 RED FLAGS).

## Resolution (2026-06-22)
- **CLEAN re-run on the release binary** — no defect, no crash; the computation surface is solid and the `kind`-vocabulary unification holds in the wild.
- **03-newoutput — reconciled to NOT A DEFECT (by design).** `model new` is an artifact verb like `export`; `export --out-dir` is equally silent on a disk-write, so `model new -o` is consistent with its class — changing it would break that. **03-scale / 03-pathnorm — recorded, below the defect bar** (a `--help` nudge / a read-back doc note).
