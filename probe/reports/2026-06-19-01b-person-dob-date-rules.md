# Probe report — task 01b (person DOB → date-derived age + date rules) — 2026-06-19

| | |
|---|---|
| **Task** | [`probe/tasks/01b-person-dob-date-rules.md`](../tasks/01b-person-dob-date-rules.md) — 2-turn, **date-forcing** (the H1 remedy) |
| **Binary** | native image, current HEAD (rebuilt this session — verified: unified `rule read` lists, `schema model validate` notes `verdict`). Reports `a12-dmkits 0.3.2` (version bump pending) |
| **Agent** | general-purpose subagent, inherited session model (Opus 4.8); ~56k tokens, 22 tool uses, ~2 min |
| **Capture** | shim log (31 calls, 1 nonzero exit, **0 RED FLAGS**) + the persisted model, re-rendered via the native CLI |
| **Integrity** | **CLEAN** — `audit-probe.sh` (only read SKILL.md; 3 `rule-*.json` writes; no model hand-edit; nothing outside the sandbox) |

## Headline — the v0.1.0 native crash surface is GREEN

01b exists because task 01's ambiguous "age" let the agent model a stored `NUMBER` and **bypass the date operators** — the exact native-only reflection path (`Today`/`YearFromDate`/`AddYears`) that crashed in v0.1.0 (finding **H1**). Anchoring age on a **date of birth** forced that path. Result:

- **`digest-probe.sh`: 0 RED FLAGS** — no raw exception / stack trace escaped any verb. Every `rule check`/`rule add`/`rule read` touching `Today` + `DifferenceInYears` ran cleanly on the **native** binary.
- The model **validates on native** (`valid:true`, 0 diagnostics).

So the surface that crashed in v0.1.0, and that the JVM-based eval can never see, works end-to-end on the current native image. That is exactly what this probe is for: **confirming the highest-value untested path proactively**, not after a bug report.

## Outcome vs the gold (semantic) — full match

Persisted model (`person.json`), conditions re-rendered from the kernel via `rule read`:

| gold expectation | result |
|---|---|
| `DATE` date-of-birth + join-date fields | ✅ `FullName` (STRING), `DateOfBirth`/`JoinDate` (DATE) |
| age ≥ 18, **date-derived**, violation polarity, guarded | ✅ `PERSON_UNDER_18 = FieldFilled(/Person/DateOfBirth) And DifferenceInYears(/Person/DateOfBirth, Today) < 18` |
| join-date-not-in-future, violation, guarded | ✅ `JOIN_DATE_IN_FUTURE = FieldFilled(/Person/JoinDate) And [/Person/JoinDate] > Today` |
| turn 2: `DATE` renewal field + within-2-years rule | ✅ `RenewalDate` (DATE) + `RENEWAL_TOO_FAR_AFTER_JOIN = AllFieldsFilled(JoinDate, RenewalDate) And DifferenceInYears(/Person/JoinDate, /Person/RenewalDate) > 2` |
| date operators actually present (the defining 01b check) | ✅ `DifferenceInYears` + `Today` in the rendered conditions — **not** a stored-number cop-out |
| kernel-valid (native) | ✅ |

**No false success, no silent drop.** The H1 remedy is validated: the date-of-birth prompt reliably forces the date-operator path.

## What worked well (record the positives)

- **Date-operator discoverability + the gotcha channel.** The agent learned the date operators from `operators` (the catalog) and surfaced the **`DifferenceInYears` whole-year-floor / leap-day gotcha** unprompted — it explicitly noted the 2-year rule floors to whole years and offered `AddYears(JoinDate, 2)` as the exact-day alternative, deferring the choice to the analyst. The catalog's gotcha channel did its job.
- **Polarity, reasoned not guessed.** All three conditions express the *violation* (`< 18`, `> Today`, `> 2`), and the agent explicitly reasoned each through rather than trusting `valid:true` — exactly what the skill teaches.
- **Presence-guarded date math.** Each date comparison is guarded (`FieldFilled`/`AllFieldsFilled`) so an empty date doesn't mis-fire — the agent applied this on its own.
- **Self-describing surface sufficed.** Verbs, flags, the rule-spec schema, and date-operator semantics all discovered from `--help`/`manifest`/`operators`/`schema` — no guessing, no repo access (audit CLEAN).

## Findings

### F1 (LOW) — `operators` has no filter flag; the agent guessed `--filter`
- **Symptom** (digest, 1 nonzero exit): `dmtool operators --filter date` → exit 2, `Unknown option: '--filter'`. The agent expected to narrow the operator catalog by a term; there is no such flag. It recovered immediately (ran bare `operators` and filtered client-side).
- **Root cause:** the `operators` catalog verb takes no narrowing flag (and the agent reached for `--filter` rather than any positional form). The picocli error is clean and correct, but not corrective — it doesn't hint at how to narrow.
- **Severity:** LOW — recovered on the first retry, clear error, no wasted modelling. A pure discoverability paper-cut.
- **Options:** a positional/substring filter on `operators` (e.g. `operators <term>` narrowing the catalog), or leave it (the bare catalog + client-side filter works). Not worth a fix on its own; note for the backlog.

Otherwise a **clean session** — no false successes, no friction beyond F1, no diagnostic gaps.

## Triage → next actions

| # | severity | action |
|---|---|---|
| **Headline** | — | The native date-operator path works. **Graduate into a permanent guard:** a native-image smoke (in the release native-gate) that runs a `Today`/`DifferenceInYears`/`AddYears` rule through the native binary and asserts no crash — so the v0.1.0 regression can't recur silently. (Release-tooling area; JVM tests can't catch a native-only reflection gap.) |
| **F1** | LOW | optional: a narrowing affordance on `operators`; else backlog. |

The probe's value here is the **negative result, proven**: the most dangerous untested surface is confirmed working on current native, by objective capture — not assumed.
