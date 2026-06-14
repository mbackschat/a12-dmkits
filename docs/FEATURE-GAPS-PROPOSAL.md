# dmkits — BA-workflow gaps (open proposals)

> **What this is.** In practice dmkits (rulekit + modelkit + `dmtool`) is driven by a **business analyst working through an LLM agent**: the BA holds a requirement in business terms ("delivery can't precede the order date"), and the agent turns it into a rule or computation via `dmtool`. This doc is the **open backlog for that BA+agent loop** — ergonomic capabilities that would make the loop faster or safer — surfaced by a 2026-06-14 review against the kernel team's BA reference (`a12-kernel/documentation/_merged/kernel-ba.md`). Every item here is **proposed, not built**.
>
> **What was here before.** This file used to hold the *structural* authoring-coverage inventory (field config, field/element metadata, group templates, refactors, …). **That backlog is closed — every one of those capabilities shipped**, and each is documented where it is contracted and tested: the [`API-POC-STUDY.md` §9 board](API-POC-STUDY.md) (the single source of truth for what's built), [`CLI-SPEC.md`](CLI-SPEC.md) (verb/gate contracts), and [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) (kernel facts). Code comments that cite `FEATURE-GAPS §3.x` / `§2` name those now-shipped features — find each on the §9 board (it carries the same capabilities by name).

---

## 1. The loop we're improving

A BA does not write DSL. The kernel BA doc frames the BA's job as mapping business requirements onto rules that **describe the violation** (not the requirement), each carrying a **rule group**, an **error field**, an **error text**, and a **severity** — plus **computation tables** (mutually-exclusive preconditions → calculations). Its recurring pitfalls — *polarity* (the rule is the error scenario), *"no generic negation"* (use the paired predicate), *error-field placement*, *computation-table exclusivity* — are exactly where an agent stumbles.

dmkits already serves the **correctness** axis well, and these strengths are the baseline the gaps below build on:

- `schema rule add` **teaches polarity inline** (its `condition` field: *"TRUE on a VIOLATION … to enforce a requirement write its violation"*; its `field` note: *"its parent group is the rule's iteration scope"*).
- `rule test --doc` **confirms polarity by execution** — fired / passed / **suppressed** (it correctly distinguishes a rule the kernel skipped because a referenced field was formally invalid).
- `RK_INVERTED_REQUIREDNESS` backstops the #1 trap; diagnostics are coded, located, and fix-carrying (`diagnostics <CODE>` explains any of them).

The gaps below are the **ergonomic** remainder — what still slows or risks the loop.

---

## 2. The gaps

Each gap: the BA need → what's missing today → the proposal. Ordered by value. The `Gn` label maps to the 2026-06-14 review item.

### G1 — Preview the resolved end-user message  *(small)*

- **BA need.** The BA's actual deliverable is the **error text the end-user reads**. With message interpolation (`$Field$`, `$#Group$`, `$Field.value$`) they cannot tell what it renders to.
- **Today.** `rule test --doc` returns `{rule, fired, verdict}` — **no message text**; neither `rule test` nor `model eval` interpolates the message.
- **Proposal.** Have `rule test` / `model eval` return the **interpolated message** for each fired rule (`data.message`), resolved against the supplied `--doc`. Turns "did it fire" into "here is exactly what the user will read" — closing the loop on the BA's real output.

### G2 — Common validation-pattern templates  *(medium–large; highest leverage)*

- **BA need.** The BA doc is organized around *typical* validation tasks — required-when, date-order, mutually-exclusive, at-least-one-of, sum-of-line-items. The agent re-derives each from scratch, re-risking polarity and error-field choice every time.
- **Today.** Only **group** templates exist (`group multiselect` / `group attachment`); there is no rule/computation pattern catalog.
- **Proposal.** A parameterized **pattern catalog** — `dmtool patterns` lists named idioms with their DSL skeleton + parameters, and an authoring path scaffolds the rule/computation with **correct polarity and a valid error field baked in**. The rule/computation-level peer of the group templates; it attacks the polarity trap *structurally*, not just by lint. The most "BA-shaped" capability still missing.

### G3 — Negation / opposite discoverability  *(small)*

- **BA need.** The doc's core model is "**no generic negation** — use the paired predicate." An agent sitting on `AllFieldsFilled` needs the pointer to `NotAllFieldsFilled` / `NoFieldFilled`.
- **Today.** `operators <id>` gives a `meaning` but **no pairing** (`opposite` / `family` / `seeAlso`).
- **Proposal.** Add an **`opposite` / `family`** field to the operator catalog (`OperatorCatalog` / `operators.json`) linking each predicate to its negative/positive partner(s). "Express the inverse" becomes one hop — directly serving the doc's central negation model.

### G4 — Proactive error-field guidance  *(small–medium)*

- **BA need.** The error field must be **referenced** in the condition AND lie inside **every repeatably-referenced group** — a top rejection class, learned today only by getting rejected.
- **Today.** `schema rule add` teaches polarity + iteration-scope, but not the full error-field law; a bad choice surfaces as `MVK_ERROR_FIELD_NOT_REFERENCED` *after* the kernel round-trip.
- **Proposal.** `rule check --suggest-error-field` (or enrich that reject diagnostic) to list the **valid error-field candidates** from the condition's referenced fields — already computed by `rule deps` / the adapter's `referencedFields`. Cuts the reject→fix round-trip.

### G5 — Computation author-time lints  *(medium)*

- **BA need.** The doc mandates **mutually-exclusive** preconditions and **forbids** `CustomCondition` / `RepetitionNotUnique` in computations.
- **Today.** These surface only as kernel rejects at `computation add`.
- **Proposal.** A proactive lint in `computation add`: warn on (a) overlapping / non-exclusive preconditions and (b) the forbidden constructs (the latter trivial via the AST) — a corrective `RK_*` *before* the kernel round-trip.

---

## 3. Sequencing

- **First, the cheap high-value pair:** **G1** (close the loop on the BA's deliverable) and **G3** (serve the doc's negation model) — both small.
- **Then the lever:** **G2** — the biggest BA-efficiency win and the most BA-shaped feature still missing.
- **Then the round-trip-savers:** **G4**, **G5**.

When one ships it graduates into the **shipped** record — a row on the [`API-POC-STUDY.md` §9 board](API-POC-STUDY.md) and, for a CLI verb, a [`CLI-SPEC.md`](CLI-SPEC.md) contract — with its own test lock, exactly as the former structural inventory did.

---

## 4. Adjacent but out of scope

- **`dmtool serve` (warm-kernel daemon).** Would cut the per-call JVM + kernel warm-up of the *iterative* author loop the most, but it is a larger build (process lifecycle, a request protocol). Noted for a future design pass; not scoped here.
- **Whole-model NL summary.** "Explain every rule in business terms" for a BA inheriting a model — composable today by looping `rule explain`; a `model explain` convenience is minor.
- **Model-version migration.** Bumping a model to a newer kernel format is a distinct concern with its own existing tool (`kernel-md-cli` → `MigratorCli`). dmkits authors and edits; it deliberately never bumps a model's version on write. Not an authoring feature.
