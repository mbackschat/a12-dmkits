# Probe task 14 — repeated computation outcomes keep their row identity

**Start:** fixture `interpreter/src/commonTest/resources/workspace-preparation/self-contained/permit-basic.dm.json`

**Probes:** whether a cold agent can use the shipped runtime surface to distinguish three outcomes of the same computation declaration across repeated rows. The output must identify each concrete row even though all results declare `/Permit/SiteSections/SurveyCharge`; collapsing them to the declaration path makes VALUE, CLEARED, and ERROR ambiguous. The fixture is authored through the typed workspace-preparation dogfood surface and is also the interpreter browser showcase model.

Run as ONE session, prompts in order — no look-ahead.

## The session (multi-prompt, in order)

**Turn 1**
> Survey charges in this permit model must be whole numbers with at most four integer digits. Make that field constraint explicit and verify that the model remains valid.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> Now calculate the model's computed fields for this permit application. Explain what happened to the survey charge in each site-section row, using the result itself to identify the row. Do not modify the document.
>
> ```json
> {"Permit":{"SiteSections":[{"Area":75},{"SurveyCharge":50},{"Area":700}]}}
> ```

## Gold reference (matched SEMANTICALLY, not literally)

- **Turn 1** — `/Permit/SiteSections/SurveyCharge` remains a `NUMBER`, with `maxFractionalDigits: 0` and `maxIntegerDigits: 4`; the model is kernel-valid. The agent must author the change through `dmtool`, not hand-edit the model.
- **Turn 2** — the agent discovers and runs the read-only computation projection. All three results have declaration `Permit/SiteSections/SurveyCharge`, but their concrete `field` values and outcomes are distinct:
  - **VALUE** — `Permit/SiteSections/SurveyCharge`, value `1875` (`75 * 25`).
  - **CLEARED** — `Permit/SiteSections[2]/SurveyCharge`; `Area` is absent, so the computation clears the stored `50`.
  - **ERROR** — `Permit/SiteSections[3]/SurveyCharge`, attempted value `17500`; it exceeds the field's four-integer-digit constraint.
- The result is read-only (`written:false`) and the input document remains byte-identical. Row 1's omitted `[1]` is the canonical exact-pointer spelling, not a missing row identity.

## What to mine

- Does the agent discover `model compute` and understand that its output is read-only, or confuse it with `model eval` or document mutation?
- Does it use each result's concrete `field` to distinguish rows, or group by the shared `declaration` and lose identity?
- Does it correctly explain VALUE, CLEARED, and ERROR from the output, including the attempted value on ERROR?
- Is row 1's canonical pointer spelling legible, or does omission of `[1]` look like a missing repetition?
