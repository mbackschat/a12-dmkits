# Probe report — task 09 (refactor a live model), re-run 2026-06-22 (release binary)

**Headline: CLEAN — 5 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** A maintain/refactor session on the seeded `order-ruled` model. Every refactor gate fired correctly — the no-op move ("nothing to move"), the safe-delete `RK_REFERENCED` then `--cascade`, the genuine `Count→Sku` rename collision — and the cross-cardinality `group move` re-homed the subtree cleanly. The agent halted on the two contradictory requests rather than fabricating. Skill/borderline notes only.

## Provenance & integrity

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1) — release candidate, gate GREEN |
| start | **fixture** — seeded from `cli/src/test/resources/models/order-ruled.dm.json` |
| audit | **CLEAN 6/6** — model mutated only by dmtool; no raw read |
| digest | **CLEAN · 0 RED FLAGS** — 5 nonzero exits, all legitimate (no-op move, `RK_REFERENCED`, the `Count→Sku` collision, post-removal not-found, a `field read --path` slip the corrector fixed) |

## Findings

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 09-skill-scope | the skill's *frontmatter* lists "structure edits and refactors (extract/move/rename)" but the body is almost entirely rules/conditions; the agent discovered every refactor verb from `--help`/`manifest` (which worked) | skill | minor / borderline | recorded — consistent with the skill philosophy (verbs are discoverable; the skill carries judgment, and refactor correctives are diagnostic-carried), so likely no change; at most a one-line "refactors are `--help`-discoverable; the gates protect you" pointer |
| — | the **no-op move now self-names** ("'X' is already directly under 'G' — nothing to move"); `RK_REFERENCED` named the referrer + the `--cascade` path; `field rename --dry-run` + `where-used` made the rename safe | — | **prior fixes confirmed in the wild** | — |

## Replay log (verbatim, in order)
1. move the backorder quantity field out to sit directly under the order …
2. add a per-line-item "line note" field …
3. remove the delivery date field …
4. rename the line-item Count field to "Sku" …
5. move the whole Items group so it sits inside the shipping address.

## Self-audit
CLEAN: every mutation via dmtool verbs; no raw `.dm.json` read. Audit 6/6, digest 0 RED FLAGS.

## Resolution (2026-06-22)
- **CLEAN re-run** — the Refactor class (move / cascade-delete / rename-collision / cross-cardinality move) behaved exactly as specified; the no-op-move self-naming (last session) holds.
- **09-skill-scope — recorded, borderline** (the refactor surface is self-describing; no skill body change indicated).
