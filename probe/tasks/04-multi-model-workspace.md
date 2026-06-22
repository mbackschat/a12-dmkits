# Probe task 04 — a multi-model workspace (includes · typedef import · `-w` discovery · cross-model refs)

**Start:** `clean` (from-scratch — the agent authors **several** models in one workspace).

The extract-based tasks (`01`, `05`) produce an include by *lifting* a subtree; none **author an include from scratch** against a *pre-existing sibling* model, nor exercise **typedef import** (`purpose=typeDefinitions`) or the **`-w` workspace** resolution an agent must discover when references span files. This task is the multi-file surface: build a base + a shared sibling, mount one in the other, share a type across both, and reference across the boundary. Own neutral domain (e-commerce storefront, no tax/gov).

**What it probes (the multi-file surface the extract tasks miss):**

- **`include add` from scratch** — mount a *separately-authored* sibling model (not an extracted subtree).
- **`-w` / workspace resolution** — references resolve by header `id` across files; the agent must discover it needs `-w <dir>` (or `$DMTOOL_WORKSPACE`) when a cross-file reference fails to resolve.
- **typedef `import`** (`purpose=typeDefinitions`) — another model's type-defs merge in by id (not mounted), shared across both models.
- **cross-model reference** — a rule on the base that references a field inside the included sibling.
- **rename with includes present** — a reference rewrite while a mount is in place.

## Anti-overfitting (shared with 05/06/07)

Intent-level prompts; the agent discovers `-w`, `include add`, and `typedef import` from the tool's own errors + `--help`/`schema`, which is exactly where multi-file DX friction shows. A clean run is success; the value is a confusing resolution failure, a missing `-w` hint, an opaque import path, or a dangling cross-model reference after the rename.

## The session (multi-prompt, in order — do not look ahead)

**Turn 1**
> create a shared catalog model: a product with a sku, a name, and a price. Then create a separate order model with a customer and an order date.

STOP — do not read below until this turn is complete and verified.

**Turn 2**
> in the order model, include the catalog so each order carries the product details.

STOP — do not read below until this turn is complete and verified.

**Turn 3**
> both models' money amounts should use one shared "Money" type defined once.

STOP — do not read below until this turn is complete and verified.

**Turn 4**
> add a rule on the order: when the order is placed, the product's sku must be filled.

STOP — do not read below until this turn is complete and verified.

**Turn 5**
> rename the order's customer field to "buyer" everywhere.

## Gold (matched SEMANTICALLY — the SHAPE, not exact text)

- **T1** two valid models in one workspace dir — `catalog` (a `Product` group: `Sku`, `Name`, `Price`) and `order` (`Customer`, `OrderDate`) — each resolvable by header `id`.
- **T2** the order model gains an **`include`** mounting the catalog (`include add --reference catalog …`), resolved via **`-w <dir>`** (or the model's own folder) — the composed pair validates. The agent discovering it must pass `-w` (from a resolution error) is the key signal.
- **T3** a shared `Money` **type definition** authored once (a small types model or one model's typedef) and **imported** into both via `typedef import` (`purpose=typeDefinitions`) — the price fields reference the shared type, not a duplicated inline NUMBER config.
- **T4** a rule on the order referencing a field **inside the included catalog** (e.g. the mounted product's `Sku`) — the path resolves across the mount; the composed model validates.
- **T5** the `Customer` field renamed to `Buyer` with any referencing rule rewritten, valid→valid, with the include still mounted.

## What to mine

- **`-w` discovery (T2)** — did the agent discover the workspace flag from the resolution error, or thrash? Was the "cannot resolve reference" diagnostic corrective (did it point at `-w`)?
- **`include add` from scratch (T2)** — friction vs the extract path; did the agent know `--reference`/`--alias`/`--parent`/`--name`?
- **typedef `import` (T3)** — discovered (`purpose=typeDefinitions`) or conflated with `include`? Did the agent duplicate the type instead of sharing it (a wrong-but-valid shape = a finding on discoverability)?
- **cross-model reference (T4)** — does a rule referencing into the mount resolve and validate? Any path-form confusion at the boundary.
- **rename with includes (T5)** — references rewritten, nothing dangling.
- **Internal tool errors** — the digest auto-RED-FLAGs an escaped exception OR a structured `RK_INTERNAL_ERROR` / `outcome:error` / exit 70.
