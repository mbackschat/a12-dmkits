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

### G6 — Edit an existing field's config without delete + re-create  *(medium; surfaced by a real agent session)*

- **BA need.** "Change the postal-code field to enforce a 5-digit pattern" — i.e. *edit an existing field's type spec* (add/change a `pattern`, `fractionDigits`, length bounds, …).
- **Today.** `field` has add / read / remove / rename / move but **no config-edit**; `config modify` is document-level only. So the only path is **delete the field and re-create it** with a rich spec. A real agent session did exactly this ("No field edit — I'll re-create PLZ"), and because delete-then-recreate isn't atomic, a failure mid-sequence left the model in an uncertain state (the field gone, the re-add not landed).
- **Proposal.** A `field modify <spec.json>` (or `field config`) that edits a field's type spec in place — the CLI peer of the library's typed per-type field config (the DG1 `GroupEditor` builders). Removes the destructive workaround and the non-atomic-state trap.

### G7 — Signpost "how do I constrain a value?" across the two mechanisms  *(small)*

- **BA need.** "Constrain this string to a pattern" has **two** valid expressions — a **field-level** `string.pattern` + `patternMessage`, or a **rule-level** `PatternViolated` operator — with different trade-offs (intrinsic field constraint vs. a standalone rule with its own message/severity).
- **Today.** Both are discoverable separately (`schema field add` shows `pattern`; `operators PatternViolated` shows the operator) but **nothing connects them or guides the choice**. The same agent session had to explore to learn both existed ("How to learn about string pattern?").
- **Proposal.** Cross-link them in the self-describing surface: a `seeAlso` from the operator catalog's `PatternViolated`/`PatternMatched` to the field-level `string.pattern` (and back from `schema field add`), with a one-line "field-level vs rule-level" note. Pairs naturally with G3's operator-catalog cross-references.

---

## 3. Sequencing

- **First, the cheap high-value pair:** **G1** (close the loop on the BA's deliverable) and **G3** (serve the doc's negation model) — both small.
- **Then the lever:** **G2** — the biggest BA-efficiency win and the most BA-shaped feature still missing.
- **Then the round-trip-savers:** **G4**, **G5**.
- **G6** (field-config edit) is the highest-value of the agent-surfaced pair — it removes a destructive workaround; **G7** (pattern signpost) is cheap and rides G3's cross-reference work.

When one ships it graduates into the **shipped** record — a row on the [`API-POC-STUDY.md` §9 board](API-POC-STUDY.md) and, for a CLI verb, a [`CLI-SPEC.md`](CLI-SPEC.md) contract — with its own test lock, exactly as the former structural inventory did.

---

## 4. Adjacent but out of scope

- **`dmtool serve` (warm-kernel daemon / stdin REPL) — won't build (decided 2026-06-15).** A long-running process the agent streams ops to, with a warm kernel. Examined in detail and **rejected**; the case collapsed once the premise was checked. Don't re-litigate without new evidence against the assumptions below.
  - **Why the premise is dead.** "Seconds, not git-ms" was the *JVM* path (~600 ms+). The shipped CLI is the **native binary at ~37 ms** ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §6) — and a "warm process" exists only to amortize JVM startup + JIT + kernel static init, **none of which native has to amortize.** A stateless warm-process serve over the native binary buys ≈ nothing.
  - **The cheap tier doesn't fix the loop's real cost.** A stateless serve (re-passes `-m` per op, like `batch`) re-pays the per-model bootstrap (deserialize + expand + MVK service build) every op — so the thing a tight try→check→adjust loop most wants gone is *not* removed. Only a **stateful warm-model** serve removes it, and that drags in the full cache-coherence problem: an external app (A12 Tools, `git checkout`, a human editor) changes the model file mid-session, so it needs staleness detection over the **whole resolved file set** (root + includes + typedef imports, not just `-m`), a `reload` verb that **refuses on conflict** (in-memory edits × on-disk change) rather than silently clobbering, and an optimistic **compare-and-swap on `commit`** (refuse `RK_STALE_WRITE` when the file changed since `open`). That is the bulk of the ~1–2 week build and the part most able to corrupt work silently.
  - **`batch`/`apply` already occupy the space.** [`CLI-SPEC.md`](CLI-SPEC.md) §7: `batch` amortizes warm-up across a known op-list; `apply` keeps a warm in-memory model across an ordered transaction with one terminal gate + atomic rollback. Those are the two legitimate "warm" scenarios, already purpose-built.
  - **It fights the agent-facing thesis.** The CLI is deliberately a **self-explaining, stateless** surface (each verb = `{target, op, args}` in, one envelope out — the *Self-explaining surface* standing requirement). serve adds session handles, lifecycle, reload/conflict semantics, stale-write gates — a second, *stateful* mental model that makes the agent's reasoning **harder** to save milliseconds it isn't spending. The skill must stay simple; serve cuts against that.
  - **Assumptions this rests on (revisit only if one breaks):** (1) the shipped CLI stays the **native** binary at ~tens of ms cold start; (2) the per-model load (deserialize + expand + MVK build) is **fast** relative to a human/agent author step — *not separately measured*, but the verdict holds either way: if load were slow it would argue for the warm-*model* tier, which the reload/coherence cost makes too expensive anyway. (3) `batch`/`apply` continue to cover the known-op-list and one-model-transaction cases.
  - **The one narrow exception, conditional and future.** The JVM-only runtime-eval verbs (`model eval` / `rule test` / `model compute`) *do* pay real Groovy-codegen warm-up every call, can't be native, and aren't shipped in v1. *If* they ever ship as a JVM artifact *and* an agent runs them in a tight **interactive** loop (where `batch`'s known-list amortization doesn't apply), a warm process becomes the only way to make them fast. Re-open only under those conditions.
- **Whole-model NL summary.** "Explain every rule in business terms" for a BA inheriting a model — composable today by looping `rule explain`; a `model explain` convenience is minor.
- **Model-version migration.** Bumping a model to a newer kernel format is a distinct concern with its own existing tool (`kernel-md-cli` → `MigratorCli`). dmkits authors and edits; it deliberately never bumps a model's version on write. Not an authoring feature.
