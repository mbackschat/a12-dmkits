# Probe task 12 — validating a document with a COMPUTED field (the form-engine flow)

**Start:** fixture `cli/src/test/resources/models/subscription-computed.dm.json`

**Probes:** the **compute-then-validate default** of `model eval` / `rule eval` — the runtime behavior the eval suite (authoring/comprehension) never exercises. The kernel keeps `validateFull` and `compute` *separate*, so a bare validation of a document whose **computed** field is empty would false-fail; `model eval` **defaults to the form-engine flow** (compute → apply → validate) so the computed value is filled before validation, and `--no-computations` opts down to the raw `validateFull` over stored values. The headline question is whether a cold agent produces the **app-realistic** answer (valid) and understands the two modes — without being told the verb runs computations first.

The fixture's `EffectiveFeeComp` computes `/Subscription/Billing/EffectiveFee` as `[BaseFee]` (the effective fee equals the base fee). Run as ONE session, prompts in order — no look-ahead.

## The session (multi-prompt, in order)

**Turn 1**
> In the subscription model, the effective fee must always be present.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> Here's a subscription to check: the base fee is 49.90 and nothing else is filled in. Is it valid? Explain.

STOP.

**Turn 3**
> Now check the *stored data as-is*, without running the model's computations. Does that change the answer, and why?

## Gold reference (matched SEMANTICALLY, not literally)

- **Turn 1** — `EffectiveFee` is made **required** (the field's `required` property — *not* a `FieldNotFilled` rule; "must always be present" on one field is a field property, per the "is it even a rule?" gate). The model stays kernel-valid (a required computed field is authorable — `model check` → valid).
- **Turn 2** — the agent runs `model eval --instance` with `{BaseFee: "49.90"}` (EffectiveFee empty) and reports **valid**: the default form-engine flow **computes** `EffectiveFee = 49.90` and applies it *before* the required check, so `mandatoryField` does **not** fire — matching what the running app would do. A wrong answer is reporting it **invalid** (treating the stored-empty EffectiveFee as a missing required field — the bare-`validateFull` mistake the default exists to avoid).
- **Turn 3** — the agent reaches for **`--no-computations`** and reports it now **fires `mandatoryField`** on the empty `EffectiveFee`, and explains *why*: that mode validates the stored values as-is (no compute), so a computed field that the instance didn't fill reads empty. The agent should frame the default as "as the app sees it" and `--no-computations` as "the stored values literally."

## What to mine

- Does the agent **discover** the form-engine default (from `model eval --help` / the verb's manifest description / `--no-computations`), or does it mis-report the computed-field document as invalid?
- Is the `model eval` vs `--no-computations` distinction **legible** from the self-describing surface alone (the cold-agent boundary), or did the agent need to guess?
- Any friction setting a **required** computed field, or confusion between `model check` (static) / `model eval` (runtime, applies computations) / `model compute` (values only)?
