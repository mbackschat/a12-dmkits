# Probe task 01 — person + home/work address + age≥18, then extract the address

**Source / gold:** hand-built (own neutral domain — a person record, insurance/onboarding-flavoured; no tax/gov).
**Probes:** groups · field metadata (the `field modify` path) · a date-derived rule (the native-only operator path) · then `extract`/`include`/`typedef` reuse **on prior output** — the collision the eval never makes (it is single-shot + JVM).

This is the exact prompt that surfaced the whole silent-drop class. Run it as ONE session, prompts arriving in order — the agent must NOT look ahead or optimize across turns.

## The session (multi-prompt, in order)

**Turn 1**
> create a document model for a person with a home and work address, add proper conditions. And ensure the person's age is >=18

**Turn 2**
> Move the fields of the address group to a separate dm and include it then for home and work

## Gold reference (what a correct end-state enforces — matched SEMANTICALLY, not literally)

After **turn 1** the model should have, regardless of exact spelling/operators:
- a root `Person` group with at least a name + a **date** birth-date field;
- **two address groups** (home + work), each with the usual address fields (street, house no., postal code, city, country);
- an **age ≥ 18** validation rule that is a *violation* condition (true when age < 18), guarded for presence, computed from the birth date relative to the current date (e.g. `DifferenceInYears(BirthDate, Today) < 18`, or an `AddYears`/`YearFromDate` formulation — any kernel-valid equivalent);
- the model is **kernel-valid** (the kernel is the oracle).
- *(metadata, if the agent set any — labels/required — must actually be present on the fields, not silently dropped.)*

After **turn 2**:
- the address fields live in a **separate** address model;
- the person model **includes** it **twice** — once mounted as home, once as work (an `include`, or a shared type-definition reused for both);
- still kernel-valid, and the age rule still holds.

## Objective correctness check (vs gold)
Re-read the agent's final model(s) with the (real, JVM or native) CLI and verify each gold bullet semantically — read→re-render→compare for the rule (polarity included). A gold bullet the agent *claimed* but the model doesn't contain = a **false success / silent drop** finding.

## What to mine in the trajectory (from the objective shim log)
- **Correctness vs gold** (above) — the anchor; catches false successes.
- **Friction** — calls per sub-goal, retries, rejections hit + whether recovered, dead-ends.
- **Misconceptions** — did it reason about non-existent constraints (e.g. JVM startup → over-batching)?
- **Diagnostic quality** — on an error, did the `fix` text get it unstuck?
- **Self-describing-surface gaps** — did it guess? Did `manifest`/`schema`/`operators` mislead it?
- **Concrete tool-defect findings** — ranked, actionable → root-cause fixes + graduate into deterministic guards.
