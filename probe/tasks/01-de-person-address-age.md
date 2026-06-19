# Probe task 01-de — task 01, prompted in German (locale + condition-language stress)

**Source / gold:** task `01`, re-expressed with **German prompts** (own neutral domain — a person record, insurance/onboarding-flavoured; no tax/gov).
**Probes:** everything `01` probes (groups · field metadata · a date-derived/age rule · `extract`/`include` reuse on prior output) **plus the German-locale path the eval never exercises** — because the eval's default render target is English (`DslRenderer` defaults to `ConditionLanguage.EN`), a German-prompted session is the only thing that drives `--locale de_DE`, German labels/messages covering `de_DE`, and any English-only assumption that might fight a German model.

The headline question is **self-direction**: the prompts are German but **never tell the agent to use `de_DE` or to write German**. A coherent result — a `de_DE` model with German names, labels, descriptions, and messages — must be the agent's own inference from the language it was addressed in. Run it as ONE session, prompts arriving in order — the agent must NOT look ahead or optimize across turns.

## The session (multi-prompt, in order — verbatim German, do not translate when prompting)

**Turn 1**
> Erstelle ein Dokumentmodell für eine Person mit einer Privatadresse und einer Geschäftsadresse, füge passende Bedingungen hinzu. Und stelle sicher, dass die Person mindestens 18 Jahre alt ist.

**Turn 2**
> Verschiebe die Felder der Adressgruppe in ein separates Dokumentmodell und binde es dann für die Privat- und die Geschäftsadresse ein.

## Gold reference (what a correct end-state enforces — matched SEMANTICALLY, not literally)

After **turn 1** the model should have, regardless of exact spelling/operators:
- a root person group with at least a name + an age signal (see the next bullet);
- **two address groups** (private/home + business/work), each with the usual address fields (street, house no., postal code, city, country);
- an **age ≥ 18** validation rule that is a *violation* condition (true when the person is under 18), guarded for presence — same two acceptable readings as `01` (stored-number `[Age] < 18`, or a birth-date-derived `DifferenceInYears(…) < 18`; only the birth-date reading exercises the native date operators — `01b` owns that coverage, so do not "fix" this prompt to force it);
- the model is **kernel-valid** (the kernel is the oracle).

**German-specific gold (the point of this task) — all self-directed:**
- the model **declares `de_DE`** (the agent chose it from the German prompt; `model new` requires a locale). An `en_US` model from a German prompt is itself a **finding** — does the surface nudge the locale choice, or did the agent default blindly?
- **German element names** (e.g. `Person`, `Privatadresse`/`Geschäftsadresse` or `Wohnadresse`/`Arbeitsadresse`, `Strasse`, `PLZ`, `Ort`, `Geburtsdatum`/`Alter`) — not English identifiers under a German model;
- any **labels / descriptions / helper text** the agent set are German and **cover the declared `de_DE`** (a label set must cover every declared locale — the kernel re-validates this);
- **German rule messages** covering `de_DE`.
- *(metadata the agent set — labels/required/descriptions — must actually be present on the fields, not silently dropped. The value-shape gate now refuses a wrong-shaped metadata value with `RK_INVALID_VALUE`; a German session should never hit that on well-formed input.)*

> **Not a defect — the DSL keyword language.** The condition DSL is language-independent at author time: the kernel parses both `en_US` and `de_DE` keyword languages, and rulekit's catalog/skill present the **English** operator names (`FieldFilled`, `DifferenceInYears`). An agent that writes English DSL operators inside a `de_DE` model is **correct** — the user's "German" intent targets the **locale, names, descriptions, and messages**, not the operator keywords. Do **not** score English DSL keywords as a miss.

After **turn 2**:
- the address fields live in a **separate** address model (also coherent under the German naming);
- the person model **includes** it **twice** — once mounted as private, once as business (an `include`, or a shared type-definition reused for both);
- still kernel-valid, and the age rule still holds.

## Objective correctness check (vs gold)
Re-read the agent's final model(s) with the (real, JVM or native) CLI and verify each gold bullet semantically — read→re-render→compare for the rule (polarity included). Confirm the **declared locale is `de_DE`** and that labels/messages cover it. A gold bullet the agent *claimed* but the model doesn't contain = a **false success / silent drop** finding.

## What to mine in the trajectory (from the objective shim log)
- **Self-direction to German** — did the agent pick `de_DE` and German names/labels/messages unprompted, or did it default to English and only the prompts were German? Whichever it did, **why** — what in the surface drove it (or failed to)?
- **German-locale friction** — did any verb/diagnostic assume English, mis-handle `de_DE` coverage, or render confusingly? (The attachment-group template only supports `en_US`/`de_DE`, so `de_DE` is in range — note if anything else narrows the locale.)
- **Correctness vs gold** (above) — the anchor; catches false successes.
- **Friction · Misconceptions · Diagnostic quality · Self-describing-surface gaps** — as in `01`.
- **Concrete tool-defect findings** — ranked, actionable → root-cause fixes + graduate into deterministic guards.
