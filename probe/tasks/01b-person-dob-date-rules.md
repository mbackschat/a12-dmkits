# Probe task 01b — person + date-of-birth → age≥18 (date-derived) + date rules — forces the native date operators

**Source / gold:** hand-built (own neutral domain — a person onboarding record, insurance-flavoured; no tax/gov).
**Probes:** the **DATE-operator path** — `Today`, `DifferenceInYears`/`AddYears`/`YearFromDate`, and date-vs-date comparisons. This is the **native-only reflection surface that crashed in v0.1.0** (`Today`/`YearFromDate`/`AddYears`), and the exact path probe **finding H1** showed task 01 bypassed (its ambiguous "age" let the agent model a stored `NUMBER` instead). 01b makes the date path unavoidable by anchoring age on a **date of birth**. Run on the **native** image (the whole point — the JVM never saw the v0.1.0 crash).

Run as ONE session, prompts in order — the agent must NOT look ahead or optimise across turns.

## The session (multi-prompt, in order)

**Turn 1**
> create a document model for a person: their full name, date of birth, and the date they joined. Add validation that the person is at least 18 years old (based on their date of birth), and that the join date is not in the future.

**Turn 2**
> Add a renewal date field, and a rule that the renewal must be within 2 years after the join date.

## Gold reference (what a correct end-state enforces — matched SEMANTICALLY, not literally)

After **turn 1**, regardless of exact spelling/operators:
- a root `Person` group with a name field, a **`DATE` date-of-birth** field, and a **`DATE` join-date** field;
- an **age ≥ 18** validation rule — a *violation* condition (true when under 18), **derived from the date of birth relative to today**: e.g. `DifferenceInYears(BirthDate, Today) < 18`, or `AddYears(BirthDate, 18) > Today`, or a `YearFromDate(...)` formulation — **any kernel-valid equivalent that uses a date operator + the current date** (NOT a stored age number — that would miss the point of 01b);
- a **join-date-not-in-the-future** rule — a *violation* when the join date is after today (e.g. `[JoinDate] > Today`), guarded for presence;
- the model is **kernel-valid** (the kernel is the oracle).

After **turn 2**:
- a **`DATE` renewal-date** field;
- a **renewal-within-2-years** rule — a *violation* when the renewal date is more than 2 years after the join date: e.g. `[RenewalDate] > AddYears(JoinDate, 2)`, or `DifferenceInYears(JoinDate, RenewalDate) > 2` — any kernel-valid equivalent;
- still kernel-valid, earlier rules still hold.

## Objective correctness check (vs gold)
Re-read the agent's final model with the (real, **native**) CLI and verify each gold bullet semantically (read→re-render→compare; polarity included). **In addition — the defining check for 01b:** confirm the rendered conditions actually contain **date operators** (`Today`/`DifferenceInYears`/`AddYears`/`YearFromDate`/date comparison). If the agent avoided them (e.g. modelled age as a stored number despite the date-of-birth prompt), that itself is a finding — the prompt didn't force the path.

## What to mine in the trajectory (from the objective shim log)
- **The native date-operator path works** — the v0.1.0 crash surface. Every `rule check`/`rule add`/`rule read` touching `Today`/`AddYears`/`YearFromDate`/`DifferenceInYears` must run cleanly on the **native** binary (exit 0, structured envelope, **no Java stack trace** — `digest-probe.sh` RED-flags a leak). This is the headline assertion of 01b.
- **Date-operator discoverability** — did the agent find the date operators via `operators` (the catalog)? Did their `meaning`/`gotchas` (e.g. the `AddYears` leap-day note) steer it right?
- **Correctness vs gold** (above) — the anchor; catches a stored-number cop-out.
- **Friction / misconceptions / diagnostic quality / self-describing-surface gaps** — as in task 01.
- **Concrete tool-defect findings** — ranked, actionable → root-cause fixes + graduate into deterministic guards.
