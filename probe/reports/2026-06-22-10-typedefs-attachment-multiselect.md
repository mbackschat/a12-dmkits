# Probe report — task 10 (typedefs + attachment/multiselect groups), 2026-06-22

**Headline: CLEAN — 6 turns, 0 internal errors, audit CLEAN 6/6, 0 RED FLAGS.** The un-probed structure surface — authoring a shared type definition and typing two fields with it, an `attachment` group, a `multiselect` group, plus structured read-back — all worked; the model is kernel-valid throughout. The headline is a real **capability gap: there is no `typedef modify`**, so "change the shared type in one place" forced an `inline → field modify (each using field) → extract` round-trip. Two smaller DX notes.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) |
| start mode | **clean** (from-scratch support-case model) |
| anti-cheat audit | **CLEAN 6/6** — `SupportCase_DM.json` mutated only by dmtool; hand-authored files were input specs; no raw `.dm.json` read (inspection via `model describe`/`typedef read`/`group read`/`field read`) |
| digest | **CLEAN · 0 RED FLAGS** — 5 nonzero exits, all legitimate (a `field read --field` locator slip, the typedef-modify workaround probes, the `typedef modify` non-verb) |

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 10-typedef-modify | **no `typedef modify`** (standalone or as an `apply` op) — every other element family has a `modify`. Changing a shared type's config in one place requires `typedef inline → field modify each using field → typedef extract` | CLI (capability) | **GAP (verb candidate)** | recorded — a **`typedef modify` verb candidate** under the earns-a-verb bar (CLI-SPEC §6): it trips **reference fan-out** (N typed fields) + **atomicity** (the round-trip momentarily inlines the type) — a user decision; the round-trip is a working interim route |
| 10-modify-help | `typedef modify --help` prints the **parent `typedef` help with exit 0**, hiding that `modify` isn't a subcommand (bare `typedef modify` gives the clear `Unmatched argument … Did you mean: typedef remove?`) | CLI | minor (picocli help) | recorded — a candidate (surface the unmatched-arg error even with `--help`) |
| 10-field-read-typedef | `field read` on a typedef-typed field returns only `{type:TypeDefType, typedef:"Money_amount"}` — not the resolved scale/unit — whereas `model describe` resolves it; the skill's "`field read` echoes per-kind config" doesn't hold for typedef-typed fields | material | minor (read-gap) | recorded — a candidate (`field read` inline the resolved typedef config / a `resolvedType` block), or soften the skill wording |
| — | the dedicated `group attachment` / `group multiselect` templates made T3/T4 one call each; `schema typedef add`/`schema apply` were accurate; `rule check` confirmed polarity before `rule add`; the `field read --field` slip got the exact positional correction | — | **positive** | — |

## 10-typedef-modify — the gap + the working round-trip (agent-verified)

| element family | has an in-place `modify`? |
|---|---|
| field / config / rule / computation / group | **yes** |
| **typedef** | **no** — add / read / remove / rename / extract / inline / import / unimport |

```
# no standalone verb:   dmtool typedef modify         -> "Unmatched argument 'modify'  / Did you mean: typedef remove?"
# no apply op:          apply [{target:typedef,op:modify,…}] -> RK_UNKNOWN_OP "apply does not support op 'typedef modify'"
# the working route (3 calls, momentarily inlines the type into its using fields):
dmtool -m m.json typedef inline Money
dmtool -m m.json apply [ field modify A→3dp, field modify B→3dp ]
dmtool -m m.json typedef extract --id Money --from /R/A,/R/B
```

## Replay log (verbatim, in order)
1. create a support-case model: a case with a customer name and a priority. Define one shared "Money amount" type (a euro amount, 2 decimals) and use it for both an estimated cost and an approved cost.
2. the money amounts should allow 3 decimals now — change it in one place.
3. let the customer attach supporting documents to the case.
4. add a place to pick one or more affected products (a multi-select).
5. the approved cost must not exceed the estimated cost.
6. show me the money type and the two new groups, so I can confirm they're right.

## Self-audit
CLEAN: every mutation via dmtool verbs (`model new`, `apply`, `typedef inline`/`extract`, `group attachment`/`multiselect`, `rule add`); hand-authored files were input specs; no raw `.dm.json` read (the structured-read shortfall, 10-field-read-typedef, was filed as a finding, not papered over). Confirmed by the audit (6/6) and digest (0 RED FLAGS).

## Resolution (2026-06-22)
- **10-typedef-modify — recorded as a verb candidate (user decision).** It plausibly earns a verb (reference fan-out across N typed fields + the round-trip's atomicity cost — exactly the earns-a-verb triggers), but a new refactor verb goes through the safety-gate discipline (CLI-SPEC §6: gate-before-mutate, refusal test, documented contract). Deferred to a decision rather than added reflexively; the `inline → modify → extract` route works in the interim. If deferred long-term, the `RK_UNKNOWN_OP` fix-text / `typedef --help` should name that recipe.
- **10-modify-help / 10-field-read-typedef — recorded, below the immediate bar.** Both minor; `field read` resolving the typedef config is the more actionable (a read-gap sibling) if it recurs.
- **(positive)** the attachment/multiselect template surface and the self-describing schema were sufficient everywhere else.
