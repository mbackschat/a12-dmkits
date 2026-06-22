# Probe task 02 — a registration form's field constraints (the property-vs-rule judgment, in depth)

**Start:** `clean` (from-scratch — the agent builds the form model with `dmtool model new`).

The suite's rule-heavy tasks (`06` rules-over-repeats, `07` computations) don't stress the **field-property** surface and the **"is this even a rule?"** judgment the skill leads with. This task is constraint-dense and deliberately mixes constraints that ARE field properties (regex, length, value set, plain requiredness) with ones that must become **rules** (conditional requiredness, cross-field) — so the agent has to apply that split repeatedly. It also re-tests the **per-locale `patternMessage`** fix (probe F1) in the wild. Own neutral domain (e-commerce / member registration, no tax/gov).

**What it probes (the field-property cluster the rule tasks miss):**

- **string patterns as a field property** (`pattern`/`violates`), not a rule — and the **per-locale `patternMessage`** (F1 fix).
- **string `Length` / minLength / maxLength** as properties.
- **enum / fixed value set** as a field, not a value-list rule.
- **the property→rule promotion** — a constraint that starts as a property (plain `required`, a format) but gains a dependency and must become a conditional **rule** (the skill's gate, exercised live and repeatedly).
- **suggestions** (per-locale string suggestions) — a rarely-touched field config.

## Anti-overfitting (shared with 05/06/07)

Intent-level prompts; the agent decides property-vs-rule each time (that decision is the probe's target). A clean run is success; the value is a wrong locus (a rule where a property belongs, or vice versa), a silently-dropped config, or a per-locale message that doesn't round-trip. If green-and-boring, rotate the domain or grow toward an un-probed string/enum config (alphabetical sorting, line breaks, categories).

## The session (multi-prompt, in order — do not look ahead)

**Turn 1**
> create a member registration model: a member with a name, an email, a country, and a membership tier (basic, plus, premium). Make the obviously-required ones required.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> the member id must look like M followed by six digits.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> when the member id format is wrong, show the message in both English and German.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> the display name must be between 2 and 50 characters.

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> a referral code is optional in general, but required when the tier is premium.

## Gold (matched SEMANTICALLY — the SHAPE, not exact text)

- **T1** plain fields with `required` set on name/email/etc. as a **property** (not `FieldNotFilled` rules); tier as an **enum** with the three values (not a value-list rule). The skill's gate: unconditional requiredness + a fixed value set are properties.
- **T2** the member-id constraint as a **STRING `pattern`** field property (`M[0-9]{6}` or equivalent) — not a `violates` rule. (`violates` as a *rule* is the agent's likely first reach; the property is the right home for an unconditional format.)
- **T3** a **per-locale `patternMessage`** (en + de) on the member-id field — the F1 fix; it must round-trip (a single-string message that silently can't carry both locales is the old F1 defect).
- **T4** `minLength: 2` / `maxLength: 50` as **properties** on the display name.
- **T5** the referral code's format (if any) is a property, but its **conditional requiredness** is a **rule** — violation `[Tier] == "PREMIUM" And FieldNotFilled(ReferralCode)` (the property→rule promotion: optional-by-default property, conditional-required rule).

## What to mine

- **The property-vs-rule split** — every turn forces it; a misplaced locus (e.g. a `FieldNotFilled` rule for plain requiredness, or a `violates` rule for an unconditional format) is the headline finding.
- **Per-locale `patternMessage` (T3)** — authored as a per-locale map and round-trips? (F1 regression-in-the-wild.) Did `field modify` reach the config block, or did the agent hit the "nothing to modify" trap (F1b — now corrected to a precise hint)?
- **Discoverability** — did the agent find `pattern`/`patternMessage`/`minLength` from `schema field add` / `--help`, or guess? Did anything get parsed-and-dropped?
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR` / `outcome:error` / exit 70.
