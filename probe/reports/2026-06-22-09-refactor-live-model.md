# Probe report — task 09 (refactor a live model), 2026-06-22

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A maintain/refactor session on the seeded `order-ruled` model exercised the whole Refactor-safety class — `field move`, `field remove --cascade`, `field rename`, `group move` — and every gate fired correctly: the safe-delete `RK_REFERENCED` (overridable with `--cascade`), the name-collision `RK_NAME_EXISTS` on both move and rename, and a benign cross-cardinality `group move`. Every refusal wrote nothing. Two of five prompts contradicted the model's actual state (a field already in place, a rename target already taken); the agent recognized the conflicts and **did not fabricate a change**. One minor DX finding (a no-op move reads as a name collision); two prior fixes confirmed in the wild.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) — freshly rebuilt (native gate GREEN, 168 checks) |
| start mode | **fixture** — seeded from `cli/src/test/resources/models/order-ruled.dm.json` |
| anti-cheat audit | **CLEAN 6/6** — `order-ruled.dm.json` mutated only by dmtool; no hand-edit; no raw `.dm.json` read (inspection via `model describe`/`field read`/`rule read`/`group read`/`where-used`) |
| digest | **CLEAN · 0 RED FLAGS** — 4 nonzero exits, all legitimate (one `--path` call-shape slip + three `--dry-run` refusals) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 09-noop-move | `field move X --to G` when `X` is **already in** `G` refuses with `RK_NAME_EXISTS` ("…already exists") + a fix ("choose a name not already used by a sibling") that reads oddly for the *already-there* case — safe and correct (`written:false`), but masks that the request was simply already satisfied | CLI (diagnostics) | **minor DX** (safe; wording) | recorded — a candidate enhancement (a `current-parent == --to` check → a distinct "already under the target — nothing to move" signal, across `field`/`group move`); below the immediate fix bar |
| 09-skill-move | the skill doesn't say to confirm an element's *current* path before a move/rename, nor that a move-to-current-parent is a no-op refused as a collision | skill | minor (judgment) | **RESOLVED at the diagnostic** — the no-op move now *names itself* (09-noop-move FIXED); per the placement rule a diagnostic-carried corrective doesn't belong in the measured skill, so no skill note |
| — | the refactor-safety gates are exemplary: `RK_REFERENCED` named the exact referrer (`/Order/DeliveryNotBeforeOrder`) + the `--cascade` path; `--dry-run` previewed the cascade before writing; `group move` re-homed the whole subtree cleanly | — | **positive** | — |
| — | the agent's `field read --path` slip drew the **locator corrector** ("locates its target by a POSITIONAL `<fieldPathInModel>`, not a flag … you passed `--path`") — the generalized #80 fix firing on `field read`, in the wild | — | **prior fix confirmed** | — |

## The gate bisect (the agent's affected/unaffected table)

| Operation | Destination name free? | Outcome |
|---|---|---|
| `field move BackorderQuantity --to /Order` | no — already there | refused `RK_NAME_EXISTS` |
| `field rename /Order/Items/Count --to Sku` | no — sibling `Sku` exists (the index field) | refused `RK_NAME_EXISTS` |
| `field add --group /Order/Items --name LineNote` | yes | applied |
| `group move /Order/Items --to /Order/ShippingAddress` | yes | applied (subtree re-homed; no refs to rewrite) |
| `field remove /Order/DeliveryDate` (no flag) | n/a — blocked by *reference* | refused `RK_REFERENCED` |
| `field remove /Order/DeliveryDate --cascade` | n/a | applied (+ cascaded the obsolete rule) |

**name-collision** gates `move`/`rename` consistently (same code, `written:false`); **reference fan-out** gates `remove` separately (`RK_REFERENCED`, overridable with `--cascade`). Both fire before any write — the documented refactor-safety behavior (CLI-SPEC §6).

## 09-noop-move — minimal repro (verified on a fresh fixture copy)

```
dmtool -m order-ruled.dm.json field move /Order/BackorderQuantity --to /Order --dry-run
#  -> outcome:"refused", written:false,
#     RK_NAME_EXISTS "a field or group named '/Order/BackorderQuantity' already exists"
#     fix: "choose a name not already used by a sibling, or rename/remove the existing one first"
```
`BackorderQuantity` is already directly under `/Order`, so the move's destination name collides with itself. The outcome is correct (nothing written); only the *wording* doesn't name the no-op.

## Replay log (verbatim, in order)
1. move the backorder quantity field out to sit directly under the order, not wherever it is now.
2. actually, add a per-line-item "line note" field inside the line items.
3. remove the delivery date field — we don't track it anymore.
4. rename the line-item Count field to "Sku" to match the spec.
5. move the whole Items group so it sits inside the shipping address.

## Self-audit
CLEAN: every mutation via dmtool verbs (`field add`, `field remove --cascade`, `group move`); no editor/`sed`/`cp`/raw-edit touched the model; no raw `.dm.json` read (the two shell filters consumed `model describe`'s piped stdout, not the file). Confirmed by the anti-cheat audit (6/6) and the digest (0 RED FLAGS).

## Resolution (2026-06-22)
- **09-noop-move — recorded, below the immediate fix bar.** The refusal is safe and correct; the only gap is the diagnostic *wording* for the move-onto-self sub-case. A clean enhancement (a `current-parent == --to` short-circuit in the shared move precheck → a distinct "already under the target parent — nothing to move" outcome, covering `field move` and `group move`) is a candidate if the no-op case proves common. Not actioned now (polish, not a defect).
- **09-skill-move — RESOLVED at the diagnostic, not the skill.** The concrete trap (a move to the current parent reading as a name clash) is now fixed where it surfaces: the move *names itself* ("'X' is already directly under 'G' — nothing to move"). Per the four-lane placement rule, a corrective a diagnostic carries must NOT go in the measured skill, so no skill note was added — the tool now teaches it at the point of use.
- **(prior fixes confirmed in the wild)** the refactor-safety gates (CLI-SPEC §6) behaved exactly as specified; the generalized locator corrector (#80) fired on `field read --path`. No regression.
