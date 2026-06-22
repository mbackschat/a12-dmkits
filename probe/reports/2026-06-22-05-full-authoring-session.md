# Probe report — task 05 (full authoring session), re-run 2026-06-22 (release binary)

**Headline: the agent's reported defect did NOT reproduce — the tool is CONSISTENT.** The agent filed a genuine-looking `[CLI]` defect: that standalone `field add` and the `apply` field-add op persist a string `patternMessage` *differently* (standalone expands to all locales, `apply` stores single-locale), so a later `--add-locale` succeeds for one and is rejected for the other. **The audit returned SUSPECT, so the run was re-verified — and a clean, dmtool-only repro shows BOTH paths produce the identical `{en_US}` state and `--add-locale de_DE` is rejected for BOTH.** There is no apply/standalone divergence. This is the objective backstop working exactly as designed: a SUSPECT run produced an unreliable headline, and independent re-verification corrected it. All five turns were authored kernel-valid; no internal error / exit 70 anywhere.

## Provenance & integrity (the objective backstop)

| | |
|---|---|
| native binary | `a12-dmkits 0.6.0` (kernel 30.8.1; A12 Tools 2025.06-ext5) — release candidate, native gate GREEN |
| start mode | **clean** (from-scratch person + address model) |
| anti-cheat audit | **SUSPECT** — check 4 flagged a `cp` of a model file. **Maintainer reconciliation:** the `cp` copied the agent's *own dmtool-created* repro models into a `repro-artifacts/` archive folder (preservation), **not** a hand-edit of a model under test; checks 1/2/3/6 are CLEAN (the models under test — `Person_DM.json`, `Address_DM.dm.json` — were mutated only by dmtool, no raw read). The verdict is a conservative tripwire firing on artifact-archiving, not fabrication — but per protocol the findings were treated as untrusted and **independently re-verified** (below). |
| digest | **0 RED FLAGS** — 13 nonzero exits, all legitimate (self-caught re-applied `apply`, locator-flag slips, a roles-yaml shape reject, an unguarded-iteration reject, the headline-repro commands incl. an **inline-JSON `field add` that the tool correctly rejects**, the `--add-locale` reject) |

## The headline finding — re-verified and DISPROVEN

The agent's minimal standalone repro used **inline JSON** for `field add` (`field add {…}`), which the tool rejects (`cannot read field spec` — `field add` takes a spec *file* or flags). So the agent never had a working standalone field to compare; its "standalone expands to all locales" claim is unsubstantiated. A clean repro with a spec **file** on both paths:

```
# A) STANDALONE (spec FILE)            # B) APPLY (same spec as an op)
model new RA (en_US); field add spec.json     model new RB (en_US); apply ops.json
field read /R/Zip → patternMessages {en_US}   field read /R/Zip → patternMessages {en_US}   ← IDENTICAL
config modify --add-locale de_DE → exit 1      config modify --add-locale de_DE → exit 1     ← IDENTICAL (both rejected)
```

Both front doors produce the **same** single-locale `{en_US}` state, and `--add-locale de_DE` is rejected for **both** with `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`. **No divergence.** The standing "always check apply/batch compatibility" invariant holds: the op and the standalone verb behave identically here.

## Findings (classified)

| # | finding | target | class | resolution |
|---|---|---|---|---|
| 05-divergence | **(the agent's headline)** standalone vs `apply` `patternMessage` divergence → `--add-locale` asymmetry | CLI | **NOT A DEFECT — disproven** | re-verified clean: both paths identical (`{en_US}`, both reject `--add-locale`); the SUSPECT audit correctly flagged an unreliable run |
| 05-cascade | a string field's base `patternMessage` is stored single-locale, so a later `--add-locale` is rejected (`MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`) — correct kernel behavior, but the skill's "going bilingual" cascade lists enum labels / field labels / rule+computation messages and **omits string pattern messages** | skill | **DX (real, minor)** | candidate — add string `patternMessage`s to the bilingual cascade (the consistent behavior, just an incomplete coverage list) |
| 05-inline-json | `field add {…inline JSON…}` fails with `cannot read field spec: {…}` (it treats the JSON as a filename); the spec must be a **file** (or flags) | material | minor DX | candidate — the `--help`/error could state "pass a spec FILE path (or flags), not inline JSON" |
| — | the whole authoring breadth — groups, `de_DE` locale + German labels, roles/users attach (0 lint warnings), repeatability max+flag, `at most one main work address` cross-row rule, address extract+include ×2 — all kernel-valid | — | **positive** | — |

## Replay log (verbatim, in order)
1. create a document model for a person with a home and work address … ensure the person's age is >= 18.
2. add a German locale to the person model.
3. create some users and roles and attach them to the model.
4. change the repeatability of the work address to 10, and somehow flag the main work address.
5. move the fields of the address group to a separate dm and include it for both home and work.

## Self-audit (maintainer)
The agent's self-audit claimed clean writes/reads; the **audit caught the `cp`** the agent's prose didn't flag as boundary-relevant — which is precisely why the audit exists. The headline was then re-derived from scratch on the release binary (dmtool-only, no `cp`), disproving it. Net: the tool is correct; the report's value is the two minor DX notes + the demonstration that the backstop catches an over-confident finding.

## Resolution (2026-06-22)
- **05-divergence — NOT A DEFECT (disproven on clean re-verify).** Standalone and `apply` are consistent; the SUSPECT audit + independent repro did their job.
- **05-cascade — confirmed, candidate** (skill: add string pattern messages to the bilingual cascade). **05-inline-json — confirmed, minor candidate** (clearer "pass a file" error/help).
