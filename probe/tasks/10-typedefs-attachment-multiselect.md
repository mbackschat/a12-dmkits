# Probe task 10 — type definitions + attachment & multiselect groups (the un-probed structure surface)

**Start:** `clean` (from-scratch — the agent builds the model with `dmtool model new`).

The suite probes plain groups/fields (01b/02/05), rules (06), computations (03/07), and the multi-**file** include surface (04) — but **not** three structure surfaces no task touches: authoring **type definitions** within a model and typing several fields with them, an **attachment** group (file-upload, with its kernel-generated rules), and a **multiselect** group. These are whole verb families (`typedef add`/`typedef modify`/`typedef read`, `group attachment`, `group multiselect`) the agent must discover and use. Own neutral domain (e-commerce / a support case; no tax/gov).

**What it probes (verb families no other task drives):**

- **`typedef` authoring + reuse within one model** — define a reusable type once, type *several* fields with it (`field add --typedef`), so the shape is single-sourced. (04 *imports* a typedef across files; this *authors + reuses* one inside a model.)
- **`typedef modify` propagation** — change the type definition's config (scale, bounds); every field typed by it should see the change (or the kernel's verdict on re-typing a shared def — a discovery).
- **`group attachment`** — an attachment (file-upload) group; the kernel generates its housekeeping rules. Un-probed.
- **`group multiselect`** — a multiselect group (pick several values). Un-probed.
- **structured read-back** — `typedef read` + `group read` to confirm what was authored.

## Anti-overfitting (shared with 05/06/07/08/09)

Discovery, not regression. Prompts are **intent-level** — the agent discovers `typedef add`/`--typedef`, `group attachment`, `group multiselect` from `manifest`/`--help`/`schema`, which is exactly where structure-DX friction shows. A clean run is fine; the value is any new false-success/misconception/dead-end — a typedef that doesn't propagate on modify, an attachment/multiselect group authored wrong, a config silently dropped, a read that can't confirm it. If green-and-boring, rotate the domain or grow a turn toward an un-probed structure op (`typedef inline`, `group attachment` templates).

## The session (multi-prompt, in order — do not look ahead)

**Turn 1**
> create a support-case model: a case with a customer name and a priority. Define one shared "Money amount" type (a euro amount, 2 decimals) and use it for both an estimated cost and an approved cost.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> the money amounts should allow 3 decimals now — change it in one place.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> let the customer attach supporting documents to the case.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> add a place to pick one or more affected products (a multi-select).

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> the approved cost must not exceed the estimated cost.

STOP — do not read below until this turn is complete and verified.

**Turn 6**
> show me the money type and the two new groups, so I can confirm they're right.

## Gold (matched SEMANTICALLY — the SHAPE, not exact text/flags)

- **T1** a **type definition** (NUMBER, scale 2, euro/AMOUNT) authored once (`typedef add`), and **two fields** (`EstimatedCost`, `ApprovedCost`) typed by it (`field add --typedef`, typed by reference — `field read` reports the resolved kind `NUMBER` + the `typedef` pointer) — not two independently-configured NUMBER fields. The agent should reach for a typedef for the "one shared type" intent.
- **T2** the typedef's scale changed to 3 (`typedef modify`) **in one place**; both fields inherit it (the propagation is the signal — did the agent change the def, or wrongly re-type each field?).
- **T3** an **attachment** group (`group attachment`) on the case — the kernel's file-upload group + its generated housekeeping rules; the model still validates.
- **T4** a **multiselect** group (`group multiselect`) for the affected products.
- **T5** a rule whose violation is `[ApprovedCost] > [EstimatedCost]` (both are the shared type) — a field-vs-field compare across the same scale (no decimal-mismatch surprise since they share the typedef).
- **T6** `typedef read` shows the Money type's config; `group read` shows the attachment + multiselect groups — confirming T1–T4 through structured reads, **not** the raw model JSON.

## What to mine

- **typedef reuse (T1)** — did the agent use ONE typedef for two fields, or duplicate the config? Discoverability of `typedef add` + `field add --typedef`.
- **propagation (T2)** — did `typedef modify` change both fields' scale, or did the agent fumble (re-type each, or not understand propagation)? Any surprise (does the kernel allow modifying a shared def in place)?
- **attachment / multiselect (T3/T4)** — discovered, or guessed? Did the generated attachment rules confuse the agent (extra rules they didn't author)? Any config dropped?
- **read-back (T6)** — could the agent confirm the typedef + groups through `typedef read`/`group read` alone, without `export` or the raw JSON?
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR` / `outcome:error` / exit 70.
