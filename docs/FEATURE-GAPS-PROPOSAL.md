# dmkits — BA-workflow gaps

> **What this is.** dmkits (rulekit + modelkit + `dmtool`) is driven by a **business analyst working through an LLM agent**: the BA holds a requirement in business terms ("delivery can't precede the order date"), the agent turns it into a rule or computation via `dmtool`. This doc tracks **ergonomic gaps in that BA+agent loop**.
>
> **Status (re-verified 2026-06-17): the 2026-06-14 review's gaps G1–G7 have all shipped** — like the *structural* authoring-coverage inventory that preceded them (also long closed). Each is now contracted + test-locked where it lives: the [status board](API-STATUS.md) (the single source of truth for what's built), [`CLI-SPEC.md`](CLI-SPEC.md) (verb/gate contracts), [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) (kernel facts), or the operator catalog. **Code comments citing `FEATURE-GAPS §N` / `Gn` resolve via the graduation table (§2).** Only **two thin residuals** remain open (§3). A fresh set of CLI-improvement candidates surfaced by the OQ1 probe is queued for triage (§4).

---

## 1. The loop we're improving

A BA does not write DSL. The kernel BA doc frames the job as mapping a business requirement onto a rule that **describes the violation** (not the requirement), carrying a **rule group**, an **error field**, an **error text**, and a **severity** — plus **computation tables** (mutually-exclusive preconditions → calculations). Its recurring pitfalls — *polarity* (the rule is the error scenario), *"no generic negation"* (use the paired predicate), *error-field placement*, *computation-table exclusivity* — are where an agent stumbles.

dmkits serves the **correctness** axis well, and these strengths are the baseline:
- `schema rule add` **teaches polarity inline** ("TRUE on a VIOLATION … to enforce a requirement write its violation").
- `rule test --doc` / `model eval --doc` **confirm polarity by execution** (fired / passed / suppressed) and now also return the **interpolated end-user message** (G1).
- `RK_INVERTED_REQUIREDNESS` backstops the #1 trap; diagnostics are coded, located, fix-carrying (`diagnostics <CODE>`).

---

## 2. Graduated since the 2026-06-14 review (verified shipped 2026-06-17)

Each `Gn` below is **built, contracted, and test-locked**. The "verified" column is the live check run on 2026-06-17.

| gap | capability | shipped as | verified |
|---|---|---|---|
| **G1** | preview the resolved end-user message | `model eval --doc` returns the **interpolated** message per fired rule (`$Field.value$` → the value) — closes the loop on the BA's real deliverable | `model eval` → `data.messages[].message` interpolated. **Caveat → R3:** `model eval` is a runtime-eval verb (Groovy), **hidden on the native binary** — so this is a JVM-dev affordance, not on the shipped CLI |
| **G2** | common validation-pattern templates | **`dmtool patterns`** — named rule idioms (`date-order`, `mutually-exclusive`, `at-least-one-of`, `required-when`, `sum-of-line-items`) + `--apply` (correct polarity + a valid error field baked in) | `patterns` = 5 idioms, all `kind:rule` (**→ R1**) |
| **G3** | negation / opposite discoverability | operator-catalog **`opposite`**, rendered by `operators <id>` — "express the inverse" is one hop | 30 entries carry `opposite`; rendered as a top-level key |
| **G4** | proactive error-field guidance | **`rule check --suggest-error-field`** lists the valid error-field candidates (the condition's referenced fields in scope) — cuts the `MVK_ERROR_FIELD_NOT_REFERENCED` reject→fix round-trip | the flag is live in `rule check --help` |
| **G5** | computation author-time lints | **`computation add`** emits `RK_COMPUTATION_FORBIDDEN_CONSTRUCT` (CustomCondition / RepetitionNotUnique in a precondition) and `RK_COMPUTATION_OVERLAPPING_PRECONDITIONS` *before* the kernel round-trip | both codes in `ComputationAddCommand` |
| **G6** | edit a field's config in place | **`field modify <spec>`** re-types/constrains a field (e.g. give a STRING a `pattern`) — no destructive delete+recreate | `field modify` in the manifest; CLI-SPEC §5 (G6) |
| **G7** | bridge field-config constraints ↔ rule operators | operator-catalog **`seeAlso`** (single-sourced, factual, neutral), rendered by `operators <id>` | mechanism shipped + rendered, but coverage is **NUMBER value-bounds only** (**→ R2**) |

When a residual or a §4 candidate ships, it graduates the same way — a status-board row + (for a CLI verb) a CLI-SPEC contract, with its own test lock.

---

## 3. Open residuals

- **R1 (was G2) — pattern idioms beyond `kind:rule`.** `patterns` ships 5 *rule* idioms. Not covered: **computation idioms** (mutually-exclusive precondition tables — the BA doc's other half) and **field-constraint idioms**. *(medium)*
- **R2 (was G7) — extend the `seeAlso` constraint↔operator bridge.** The mechanism ships and renders, but only the **7 NUMBER comparison ops** carry a `seeAlso` (→ `number.min/max` field config). Still unbridged: **STRING** (`pattern` ↔ `PatternMatched`/`PatternViolated`; `min/maxLength` ↔ `Length`), **ENUM** (value set ↔ `FieldValueIncludedInValueList` / `EnumCategory`), and the **date-relational** families. Extend the single-sourced map (and keep the honest "none / see typedef" entries). *(small, incremental)*
- **R3 (from G1) — a message preview on the shipped *native* CLI.** G1's interpolated-message preview rides `model eval`, a runtime-eval verb that is **Groovy-only → hidden on the native binary** ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §4). So on the shipped artifact the BA cannot preview the rendered message. Either accept it as a JVM-dev affordance, or find a native-capable render path (structural message interpolation without the runtime). *(open question — not obviously worth building)*

---

## 4. Next: CLI-improvement candidates from the OQ1 probe (to triage)

The OQ1 code-mode-vs-CLI probe ([`archived/OQ1-INVESTIGATION.md`](archived/OQ1-INVESTIGATION.md) §5) surfaced a fresh set of CLI improvements — grounded in what agents had to *script around*. They are **candidates, not yet adopted**; triage them into this backlog (or reject) in the upcoming discussion. In brief: let `apply`/`batch` field ops take the rich per-kind spec; a model-wide usage/audit read (replacing the `where-used`×N loop); a positional entity for `where-used`; and a workspace-wide refactor. (R2/R3 above also originate partly from that probe's findings.)

---

## 5. Adjacent but out of scope

- **`dmtool serve` (warm-kernel daemon / stdin REPL) — won't build (decided 2026-06-15).** Examined and **rejected**; the premise collapsed once checked. Don't re-litigate without new evidence against these assumptions:
  - **The premise is dead.** "Seconds, not ms" was the *JVM* path (~600 ms+). The shipped CLI is the **native binary at ~37 ms** ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §6) — a warm process exists only to amortize JVM startup + JIT + kernel static init, none of which native pays.
  - **The cheap tier doesn't fix the real cost.** A stateless serve (re-passes `-m` per op, like `batch`) re-pays the per-model bootstrap every op. Only a **stateful warm-model** serve removes it — and that drags in the whole cache-coherence problem (staleness over the resolved file set, a `reload` that refuses on conflict, compare-and-swap on commit), the bulk of a ~1–2 week build and the part most able to corrupt work silently.
  - **`batch`/`apply` already occupy the space** ([`CLI-SPEC.md`](CLI-SPEC.md) §7): `batch` amortizes warm-up over a known op-list; `apply` keeps a warm in-memory model across an ordered transaction with one terminal gate + atomic rollback.
  - **It fights the agent-facing thesis** — the CLI is deliberately a self-explaining, **stateless** surface; serve adds session/lifecycle/reload/stale-write semantics, a second mental model that makes agent reasoning harder.
  - **The one narrow, future exception:** the JVM-only runtime-eval verbs (`model eval`/`rule test`/`model compute`) *do* pay Groovy-codegen warm-up every call. *If* they ever ship as a JVM artifact *and* an agent runs them in a tight interactive loop, a warm process becomes the only way to make them fast. Re-open only then.
- **Whole-model NL summary** ("explain every rule in business terms") — composable today by looping `rule explain`; a `model explain` convenience is minor.
- **Model-version migration** — a distinct concern with its own tool (`kernel-md-cli` → `MigratorCli`). dmkits authors/edits; it deliberately never bumps a model's version on write.
