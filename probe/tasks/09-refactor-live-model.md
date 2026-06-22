# Probe task 09 — refactor a live model (move · cascade-delete · cross-cardinality · collisions)

**Start:** `fixture cli/src/test/resources/models/order-ruled.dm.json` — a maintain/**refactor** session on a model that already has structure, a repeatable group, and rules. (A clean start can't reach the refactor surface: there are no references to rewrite, no existing names to collide with, no repeatable group to move a field into/out of.)

The suite probes *authoring* (01b/02/03/05/07/08) and *rule evolution* (06), but **not the structural Refactor class** — `field move` / `group move`, `remove --cascade`, the safety gates that refuse on unsafe input. `04` does a rename with includes present and `06` a rename-rewrite, but neither **moves** a node, **cascades** a delete, or trips the **cross-cardinality** split. This task is that surface: the refactor-safety gate (CLI-SPEC §6) end to end. Own domain reused from the fixture (an order); no tax/gov.

The seeded model (discover it with `model describe` — never assume): a non-repeatable `/Order` with `ShippingAddress`/`BillingAddress`, a **repeatable `/Order/Items`** carrying `Sku` + `Count`, an `ExpressShipping` flag, a `Priority` enum, `BackorderQuantity`, `DeliveryDate`, and existing rules (one references `DeliveryDate`).

**What it probes (the refactor surface no other task reaches):**

- **field move across groups** — relocate a field to a different group; references rewrite to the new path.
- **cross-cardinality move** — move a field INTO (or OUT of) the repeatable `/Order/Items`; the kernel scopes a rule by the repeatable fields it *references*, so a move that would **split** a bound rule/computation across cardinalities is refused (`MVK_ERROR_FIELD_NOT_IN_RULEGROUP`), a benign one succeeds (KERNEL-FINDINGS §9).
- **cascade-delete a referenced field** — `field remove` of a field a rule reads is **refused** (`RK_REFERENCED`) until `--cascade`, which removes the field *and* its referrers.
- **name collision on rename** — renaming to a name already taken in the group is refused (`RK_NAME_EXISTS`).
- **move-into-self** — moving a group under its own subtree is refused (`RK_MOVE_INTO_SELF`).

## Anti-overfitting (shared with 05/06/07/08)

Discovery, not regression: the refactor gates are guarded by the `adapter/laws` does-fire tier; this task hunts **new** refactor-DX friction — a misleading refusal, a missed reference rewrite, a dangling path, an unsafe edit that should have refused but didn't. Prompts are **intent-level** (the agent picks `move`/`remove --cascade`/`rename` and discovers the gates from the tool's diagnostics). A clean run is fine; the value is any new false-success/misconception/dead-end. If green-and-boring, rotate the fixture (`subscription-computed`) or grow a turn toward an un-probed refactor (`group extract`'s inverse, `typedef inline`).

## The session (multi-prompt, in order — do not look ahead; discover the model first)

**Turn 1**
> move the backorder quantity field out to sit directly under the order, not wherever it is now.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> actually, add a per-line-item "line note" field inside the line items.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> remove the delivery date field — we don't track it anymore.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> rename the line-item Count field to "Sku" to match the spec.

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> move the whole Items group so it sits inside the shipping address.

## Gold (matched SEMANTICALLY — the SHAPE + the gate outcome, not exact text)

- **T1** `BackorderQuantity` relocated directly under `/Order` (a `field move`); if any rule referenced it, the reference is rewritten; model still valid. (If it was already directly under `/Order`, a no-op/clarification is correct — not a failure.)
- **T2** a new `LineNote` field added INSIDE `/Order/Items` (a per-row field add into the repeatable group) — straightforward; sets up T5's move target.
- **T3** the agent first hits the **safe-delete refusal** (`DeliveryDate` is referenced by a rule → `RK_REFERENCED`, nothing removed), then either uses `--cascade` (removing the field + its referencing rule) or reconsiders. The interesting signal: did the refusal's diagnostic name the referrers and the `--cascade` path clearly?
- **T4** renaming `Count` → `Sku` is **refused** (`RK_NAME_EXISTS` — `/Order/Items` already has a `Sku`); nothing renamed. The agent should recognize the collision (not retry blindly).
- **T5** moving `/Order/Items` (repeatable) under `/Order/ShippingAddress` — the gate's verdict is the finding: if it splits a bound rule/computation across cardinalities it's **refused** with a corrective; otherwise it relocates with reference rewrite. Either way **nothing half-done**.

## What to mine

- **Gate quality** — every refusal (T3 referenced, T4 collision, T5 move) must be a clean, corrective diagnostic (names the cause + the path forward), never a stack trace and never a silent half-edit.
- **Reference rewrite (T1)** — a moved field's referrers rewrite to the new path; nothing dangles.
- **Cross-cardinality (T5)** — did the agent understand the repeatable-scope split, or fight an opaque `MVK_ERROR_FIELD_NOT_IN_RULEGROUP`? Did the enriched `StructuralKernelFix` hint help?
- **Cascade judgment (T3)** — did the agent reach for `--cascade` deliberately (understanding it deletes referrers too), or thrash?
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR` / `outcome:error` / exit 70.
