# Probe report — 01-de (person + addresses + age≥18, prompted in German) — 2026-06-20

**Task:** [`probe/tasks/01-de-person-address-age.md`](../tasks/01-de-person-address-age.md) — task 01 re-expressed with **German prompts**; the agent must produce a `de_DE` locale + German names/labels/messages **on its own**.
**Material:** shipped native binary (rebuilt to HEAD, `0.3.2`) + `skill/SKILL.md`. Driver: a clean general-purpose subagent in the isolated sandbox; every `dmtool` call captured by the logging shim.

## Integrity & trajectory (objective)

- **Anti-cheat audit — CLEAN** (`audit-probe.sh`): no repo/workspace/kernel path touched; every file op under the sandbox; the model files were only ever mutated by `dmtool`. Findings trustworthy.
- **Digest** (`digest-probe.sh`): 72 invocations (61 distinct), **0 RED FLAGS** (no raw stack trace escaped a verb — every error a structured envelope). 7 nonzero exits, clustering on the two findings below.
- **Self-direction to German — strong.** Unprompted, the agent chose `--locale de_DE`, German element names (`Person`/`Privatadresse`/`Geschäftsadresse`/`Strasse`/`PLZ`/`Ort`/`Geburtsdatum`), German labels, and German rule messages. The German→de_DE inference worked with no nudge needed.
- **Correctness vs gold — met.** Turn 1: a `de_DE` person model, two address groups, an age≥18 *violation* rule (birth-date-derived, exercising the native date operators). Turn 2: address fields extracted to a shared sub-model, included twice (private + business). Both models kernel-valid.

## Finding 1 (CRITICAL, root-caused + FIXED) — a German-locale model rejected English DSL keywords

**Symptom.** The agent's first age rule used the skill's English keywords on its `de_DE` model and was rejected:
`MVK_UNEXPECTED_TOKEN: Unexpected found 'FieldFilled'`. It recovered only by discovering (via `operators`) that each operator carries a German keyword and re-authoring in German (`FeldAngegeben`/`Und`/`DifferenzInJahren`/`Heute`).

**Root cause (verified against the kernel-truth oracles).** A model's **condition keyword language** (`modelConfig.conditionLanguage.code`) is **independent** of its **display locale** (`locales[]`, which governs labels + messages). `DocumentModelDraft` derived the condition language from the *first display locale* (`locales.get(0)`), so a `de_DE` model got `conditionLanguage=de_DE` and the kernel then parsed conditions in German — rejecting the English DSL the renderer + skill + `operators` all emit.

Confirmed the user's correction ("English rules are a must; locale is only for messages") against the source material:
- **a12-kernel-mm corpus:** all 59 models declare `de_DE` as their first display locale yet set `conditionLanguage=en_US` and author **English**-keyword rules (`FieldFilled`/`And`/`PatternViolated`/`RepetitionNotUnique`). English is the convention.
- **Experiment:** a `de_DE`-only model with `conditionLanguage=en_US` + English conditions is **kernel-valid** — the condition language need not be a declared display locale.
- Recorded: KERNEL-FINDINGS "A model's condition language is independent of its display locale"; treatment in KERNEL-INTEGRATION-NOTES.

**Fix (commit `951377f`).** A from-scratch model now defaults `conditionLanguage` to **en_US regardless of display locale**, single-sourced (`DEFAULT_CONDITION_LANGUAGE`) so the model-config write and the attachment-template authoring can't drift. The `model new --locale` help no longer mis-describes itself as "the condition + default locale". Locked: `DocumentModelDraftTest` (draft keeps en_US + kernel accepts) + `ModelNewVerbTest` (English DSL validates end-to-end on a de_DE model).

**Generalized (root-cause-generalize sweep — two sibling sites found):**
1. **Attachment template.** `Builder.attachmentGroup` passed `locales.get(0)` as the attachment condition language — a *second* derivation site; left unfixed it emitted German attachment conditions against an en_US config (kernel-rejected; caught by `AttachmentTemplateTest`). Now uses the one constant.
2. **Computations + preconditions.** Because the fix is at the **model level** (`conditionLanguage` gates *all* DSL the kernel parses — rule conditions, computation **operations**, and **preconditions** alike), it covers computations automatically. Locked explicitly by `ModelNewVerbTest.aGermanLocaleModelAlsoAcceptsEnglishKeywordComputationsAndPreconditions` — a de_DE model accepts an English-keyword computation whose alternative carries an English precondition.

## Finding 2 (DX, root-caused — DECISION PENDING) — `export` can't be driven with the universal `-m`

**Symptom.** The agent (having learned `-m <model>` works on every verb) tried `dmtool -m person.json export …` repeatedly → `Missing required parameter: '<model.json>'`; and `dmtool -m person.json export rules` → `cannot read model: Is a directory`. 5 wasted calls before it matched the help. Low severity (it recovered), but pure friction.

**Root cause.** `export` is the **only** verb that takes the model as a **required positional** `<model.json>` instead of the universal `-m`. It *also* inherits `-m` (a root option) but ignores it — so an agent that supplies `-m` gets a confusing "missing positional" with no hint that `-m` won't work here, and the model positional is ambiguous with the `[<artifact>]` positional (`export rules` reads "rules" as the model). This positional-model design is **documented as intentional** ("the odd one out", `examples/cli-structure-edit.md`), and **5 demos + the API guide depend on the positional form** — so aligning it to `-m` reverses a documented decision and churns those demos.

**Recommended fix (for the owner to confirm).** Make `export` use the universal `-m` like every other verb (drop the positional model; keep `[<artifact>]` positional) — removing both the inconsistency and the silently-ignored-`-m` redundancy, at the cost of re-capturing 5 `examples/` demos + updating the docs and the "odd one out" note. Deferred to an explicit decision because it overturns a documented design, not an accidental bug.

## Standing low finding (carried from 01b)
**F1:** `operators` has no narrowing flag; low priority.
