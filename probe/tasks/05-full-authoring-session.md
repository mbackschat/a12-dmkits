# Probe task 05 — a full, realistic authoring session (groups · metadata · locale · roles · repeatability · extract)

**Start:** `clean` (from-scratch — the agent builds the model with `dmtool model new`).

**Source / gold:** a real Claude-Code session a maintainer ran by hand (Opus, high effort) that surfaced a native-only crash a narrower probe had missed. This task is that session, **promoted to a repeatable probe** — but as a *realistic multi-feature session*, not a scripted replay (see "Anti-overfitting" below). Own neutral domain (a person record, insurance/onboarding-flavoured; no tax/gov).

**What it probes (the surface `01`/`01-de` did NOT reach):**

- **header annotations** — a `roles` access-control annotation on the model (turn 3). This is the surface that crashed natively on serialize/expand: an annotated model that is later **extracted/included** walks `AnnotationImpl.getName()` during kernel copy. `01`/`01-de` did the extract but never *annotated* the model, so they never drove the combination.
- **repeatability with a numeric max + a row flag** (turn 4) — change a group's max rows and designate a "main" row.
- plus everything `01` covers: groups · field metadata · an age≥18 violation rule · `extract`→`include` reuse on prior output; and the `de_DE` locale path (turn 2).

## Anti-overfitting — this task must keep *discovering*, not memorize a fixed bug

The known annotation crash is now closed and its **regression lives in a deterministic guard** (`scripts/native-battery.sh`, the annotated-include/extract block) — *not* here. So this probe's job is **discovery on a broad, realistic surface**, never re-confirming that one fixed crash. Three rules keep it from overfitting:

1. **Prompts stay INTENT-level, never verb-level.** "create some users and roles and attach them to model" — *not* "run `config modify --role admin`". The agent must choose the verbs; that choice is where DX friction surfaces. A prompt that names the commands tests a scripted replay, not a real session.
2. **The crash is incidental to realism, not the target.** A clean run here is success (the regression guard already proves the class stays dead); the *value* is any **new** friction/false-success/misconception the session turns up. If this task ever goes "green and boring," rotate the domain/op-mix or grow a new task toward an **un-probed** surface — don't deepen on covered ground.
3. **Vary it.** Re-run occasionally with a different domain (an order, a claim, a subscription) and let agent non-determinism explore different verb paths. One run is a sample, not a verdict.

## The session (multi-prompt, in order — verbatim; do not look ahead)

**Turn 1**
> create a document model for a person with a home and work address, add proper conditions. And ensure the person's age is >= 18.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> add a German locale to the person model.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> create some users and roles and attach them to the model.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> change the repeatability of the work address to 10, and somehow flag the main work address.

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> move the fields of the address group to a separate dm and include it for both home and work.

## Gold (the coherent end-state, matched SEMANTICALLY — multiple valid expressions)

After the session the model(s) should, regardless of exact spelling/operators:

- a person with **two address groups** (home + work), each with the usual address fields; an **age ≥ 18 violation** rule (true when under 18), presence-guarded — either a stored-number `[Age] < 18` or a birth-date-derived `DifferenceInYears(…) < 18` (`01b` owns the date reading; don't force it);
- the model **declares `de_DE`** (the agent's own inference) and any labels/messages cover it;
- a **`roles` header annotation** attached to the model (and any users/roles the agent created alongside);
- the **work address group repeatable with max 10**, with a designated "main"/index row (an index-field row-key, or a boolean flag field — either is coherent);
- the address fields factored into a **separate sub-model**, **included** for both home and work (an `include`, or a shared type-definition reused twice). The base + the annotated content must still **load/validate/describe/export cleanly** (the path that crashed natively).

## What to mine (the deep analysis — read PROBE-SPEC for the dimensions)

- **Correctness vs the gold** (re-read the produced model(s); a claimed-but-absent bullet = a false-success/silent-drop finding).
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR`/`outcome:error`/exit 70 (the annotation crash would show here on a regressed binary).
- **Friction / misconceptions** — retries, dead-ends, reasoning about non-existent constraints; did the surface nudge the locale, the roles-attach, the repeatability-max, the flag choice?
- **NEW findings first** — anything not already guarded. The annotation class is guarded by the battery; flag it if it recurs, but spend the analysis on what's *new*.
