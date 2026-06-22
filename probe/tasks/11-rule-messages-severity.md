# Probe task 11 — rule messages, severities, and token interpolation (the message-authoring surface)

**Start:** `fixture cli/src/test/resources/models/order-ruled.dm.json` — an existing model with a repeatable `/Order/Items` group and rules, so the per-row message token and severity edits have real structure to attach to.

Every task authors *rules*, but none focuses on the **message** side: token **interpolation** (`$<Field>.value$` the runtime value, `$<Field>$` the localized label, `$#<Group>$` the 1-based row index), the literal-`$` escape (`$$`), rule **severities** (`ERROR`/`WARNING`/`INFO`), and **per-locale** message coverage. This task is that surface — the modeler-facing message authoring an agent must get right for the error text a real user sees. Own domain reused from the fixture (an order); no tax/gov.

The seeded model (discover it with `model describe` — never assume): a non-repeatable `/Order` with a repeatable `/Order/Items` (`Sku`, `Count`), `BackorderQuantity`, a `Priority` enum, and existing rules.

**What it probes (the message-authoring surface no other task focuses on):**

- **value/label interpolation** — a message that names the offending field's runtime value (`$.../Count.value$`) and/or its label.
- **the row-index token** — a per-row rule whose message names the 1-based row (`$#/Order/Items$`), so the user knows *which* line item.
- **the literal-`$` escape** — a message with a literal dollar sign (`$$`), e.g. a price text.
- **severities** — a `WARNING` rule (advisory, not a hard error) and an `INFO` rule, distinct from the default `ERROR`.
- **per-locale messages** — every declared locale covered (and the de_DE-localization turn).

## Anti-overfitting (shared with 05/06/07/08/09/10)

Discovery, not regression: the message-token + severity surface is documented in `schema rule add` (the `messages.text` token grammar + the `severity` enum); this task checks whether an agent **finds and uses** it correctly under intent-level prompts. A clean run is fine; the value is any new friction — a mis-spelled token that ships as literal text, a severity the agent can't set, a missing-locale reject it can't recover from, a literal `$` that breaks interpolation. If green-and-boring, rotate the fixture or grow a turn toward an un-probed message feature.

## The session (multi-prompt, in order — do not look ahead; discover the model first)

**Turn 1**
> each line item's count must be greater than zero — and the error must show the entered count in its text.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> when there are several items, the message should say which line number is wrong.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> add a rule: the backorder quantity shouldn't exceed 1000 — but make it a warning, not a hard error.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> add an informational note when express priority is chosen (just info, no blocking).

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> the count message should also mention that orders over $1000 need approval — with a literal dollar sign.

STOP — do not read below until this turn is complete and verified.

**Turn 6**
> all of these messages should also read correctly in German.

## Gold (matched SEMANTICALLY — the SHAPE, not exact text)

- **T1** a per-row `Count <= 0` rule (presence-guarded — empty reads as 0) whose **message interpolates the value** (`$/Order/Items/Count.value$` or the relative form) — the token resolves, not shipped as literal text.
- **T2** the per-row message carries the **row-index token** (`$#/Order/Items$`) so it names the line number.
- **T3** a rule whose violation is `[BackorderQuantity] > 1000`, authored with **`severity: WARNING`** (not ERROR) — the agent must discover the `severity` spec key.
- **T4** an **`INFO`** rule keyed on `[Priority] == "EXPRESS"` (or equivalent) — advisory, non-blocking.
- **T5** a message containing a **literal `$`** via `$$` (e.g. "orders over $$1000 …") — it renders as one `$`, doesn't break the value/index tokens around it.
- **T6** every message covers **de_DE** (the kernel rejects a missing locale — `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING`); the German text is coherent, not a copy of the English.

## What to mine

- **Token interpolation (T1/T2/T5)** — did the tokens resolve, or ship as literal `$…$` text? Did the agent get the path form right (the token uses a model path)? Did `$$` work without breaking the real tokens?
- **Severity (T3/T4)** — did the agent discover `severity: WARNING`/`INFO` (from `schema rule add`), or only know ERROR? Is the distinction reflected in the persisted rule?
- **Per-locale (T6)** — did the agent cover de_DE up front, or hit `MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING` and recover? Did it write real German, or duplicate English?
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR` / `outcome:error` / exit 70.
