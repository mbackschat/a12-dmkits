# Probe task 13 — filtered claim rules (the `Having`/`$` binding surface)

**Start:** fixture `cli/src/test/resources/models/policy-claims.dm.json`

**Probes:** the **filter/correlation authoring surface in validation rules** — `Having` over a starred list, the `$` outer-row correlation, and the **binding/scope comprehension** those constructs demand (task `07` grazes `$`/`Having` inside a *computation*; no task probes them as *rules*, where the iteration-scope consequences show). Three traps, all kernel-pinned by the F7 conformance-corpus families: (1) a filtered model-level aggregate fires **once at the pinned first-row anchor**, not per matching row — does the agent understand where the message lands? (2) a value-correlated filter **includes the outer row itself** unless the author self-excludes with `CurrentRepetition(…) != CurrentRepetition($…)` — the un-excluded variant of turn 3 is a *silently-dead rule* (`x > x + rest` can never hold), the worst authoring failure because nothing diagnoses it; (3) the error field must be **referenced by the condition** — the BA-natural pick (`PolicyNumber` as "the policy is flagged") draws `MVK_ERROR_FIELD_NOT_REFERENCED`.

The fixture is a bare structure (no rules): `/Policy` with `PolicyNumber` (string), `CoverageLimit` (number, scale 2), and a repeatable `Claims` group (max 100, index `ClaimId`) carrying `Amount` (number, scale 2) and `Status` (enum `OPEN`/`APPROVED`/`REJECTED`). Run as ONE session, prompts in order — no look-ahead.

## The session (multi-prompt, in order)

**Turn 1**
> A policy with three or more claims over 1000 needs a fraud review — flag it.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> The approved claims must never add up to more than the policy's coverage limit.

STOP.

**Turn 3**
> Flag a claim when it is bigger than all the other claims on the policy combined.

STOP.

**Turn 4**
> Check this policy: coverage limit 3500, claim C1 1400 approved, claim C2 2600 approved, claim C3 1100 open. What does the model report — and why does each message sit where it sits?

## Gold reference (matched SEMANTICALLY, not literally)

- **Turn 1** — a policy-level rule at `/Policy`: `NumberOfFilledFields(Claims*/Amount Having [Claims/Amount] > 1000) >= 3`, error field `/Policy/Claims/Amount` (kernel-verified valid). The error field must be a field the condition references — picking the "natural" `PolicyNumber` is rejected with `MVK_ERROR_FIELD_NOT_REFERENCED` (kernel-verified). The count consumes **only filter-kept rows** (corpus `having/having-sum-keeps-only-matching-rows` + `having/having-sum-excluded-rows-clean` pin the keep-set law), and the star-only condition makes this a **once-per-policy** check, not a per-claim one.
- **Turn 2** — `Sum(Claims*/Amount Having [Claims/Status] == "APPROVED") > [CoverageLimit]` at `/Policy`; error field `/Policy/CoverageLimit` (referenced, and where a BA reads the failure) or `/Policy/Claims/Amount` — both kernel-verified valid. `rule check` raises the `RK_UNGUARDED_NUMBER_COMPARISON` lint (an empty `CoverageLimit` reads as 0); guarding with `FieldFilled(CoverageLimit) And …` is the equally-correct lint-following variant. Runtime edges the gold must survive: with **zero approved claims the keep-set is empty and the Sum folds to the identity 0** — no fire, no suppression (corpus `having/having-all-filtered-out-sums-zero`); a fired filtered construct types **OMISSION** (corpus `having/having-quantifier-fired-omission`).
- **Turn 3** — `FieldFilled(Claims/Amount) And [Claims/Amount] > Sum(Claims*/Amount Having CurrentRepetition(Claims) != CurrentRepetition($Claims))` at `/Policy`, error field `/Policy/Claims/Amount` (kernel-verified valid). The `$` marks the *outer* iterating claim; the filter's bare side spans **all** rows *including the outer row itself* (corpus `filter/filter-self-match-fires-every-row`), so **without** the `CurrentRepetition` self-exclusion the own amount sits inside the sum and the rule is silently dead. The correct rule fires **exactly on the dominant row** (corpus `filter/filter-dominant-row-fires`: counts 3, 9, 2 → only row 2), and the self-exclusion leaves a single-claim policy clean (corpus `filter/filter-single-row-self-excluded-clean`).
- **Turn 4** — three messages, all `OMISSION_ERROR` (each polarity corpus-pinned as cited):
  - `FRAUD_REVIEW` **once** at `/Policy[1]/Claims[1]/Amount` — three claims exceed 1000, and a star-only rule fires once at the **pinned first-row anchor** (corpus `having/having-sum-keeps-only-matching-rows` pins the once-fire + anchor pointer). The comprehension gold: `Claims[1]` is the *anchor of a policy-level finding*, **not** "claim C1 is the offender."
  - `APPROVED_OVER_LIMIT` once at `/Policy[1]/CoverageLimit` — the approved keep-set sums 1400 + 2600 = 4000 > 3500 (keep-set law as in turn 1; the open C3 stays outside the sum).
  - `DOMINANT_CLAIM` at `/Policy[1]/Claims[2]/Amount` — C2's 2600 exceeds the *others'* 1400 + 1100 = 2500; this one genuinely names the offending row (corpus `filter/filter-dominant-row-fires`).

## What to mine

- Does the agent **discover** `Having`/`$` from the self-describing surface (`dmtool operators`, the schema examples), or hand-roll per-row workarounds / invalid shapes first?
- **The dead-rule trap:** does turn 3 get authored *without* the `CurrentRepetition($…)` self-exclusion? It is kernel-valid and silently never fires — does anything on the surface (lint, skill, eval probing) catch it, or does a false success ship?
- **Scope comprehension:** does the agent expect `FRAUD_REVIEW` per matching claim, and how does it explain the `Claims[1]` anchor in turn 4? Any confusion between the once-per-policy check and `DOMINANT_CLAIM`'s genuine per-row attach?
- Error-field friction: does the agent first pick an unreferenced field (`PolicyNumber`) and how corrective is the `MVK_ERROR_FIELD_NOT_REFERENCED` diagnostic? Does the `RK_UNGUARDED_NUMBER_COMPARISON` lint on turn 2 lead to a clean guard or to thrash?
