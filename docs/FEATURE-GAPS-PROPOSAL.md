# dmkits — BA-workflow gaps

> **What this is.** dmkits (rulekit + modelkit + `dmtool`) is driven by a **business analyst working through an LLM agent**: the BA holds a requirement in business terms ("delivery can't precede the order date"), the agent turns it into a rule or computation via `dmtool`. This doc tracks **ergonomic gaps in that BA+agent loop**.
>
> **Status (re-verified 2026-06-17): the 2026-06-14 review's gaps G1–G7 have all shipped** — like the *structural* authoring-coverage inventory that preceded them (also long closed). Each is now contracted + test-locked where it lives: the [status board](API-STATUS.md) (the single source of truth for what's built), [`CLI-SPEC.md`](CLI-SPEC.md) (verb/gate contracts), [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) (kernel facts), or the operator catalog. **Code comments citing `FEATURE-GAPS §N` / `Gn` resolve via the graduation table (§2).** Two OQ1 gaps shipped 2026-06-17: **G8** (the rich per-kind field spec in `apply`'s `field add`) and **G10** (`rule read` surfaces a rule's stored message text). The **open backlog (§3)** is **G9** (the highest-leverage model-wide usage read) and **G11** (positional `where-used`) plus two thin residuals **R1/R2**; **§4** records what's out of scope or parked.

---

## 1. The loop we're improving

A BA does not write DSL. The kernel BA doc frames the job as mapping a business requirement onto a rule that **describes the violation** (not the requirement), carrying a **rule group**, an **error field**, an **error text**, and a **severity** — plus **computation tables** (mutually-exclusive preconditions → calculations). Its recurring pitfalls — *polarity* (the rule is the error scenario), *"no generic negation"* (use the paired predicate), *error-field placement*, *computation-table exclusivity* — are where an agent stumbles.

dmkits serves the **correctness** axis well, and these strengths are the baseline:
- `schema rule add` **teaches polarity inline** ("TRUE on a VIOLATION … to enforce a requirement write its violation").
- `rule test --doc` / `model eval --doc` **confirm polarity by execution** (fired / passed / suppressed) and now also return the **interpolated end-user message** (G1).
- `RK_INVERTED_REQUIREDNESS` backstops the #1 trap; diagnostics are coded, located, fix-carrying (`diagnostics <CODE>`).

---

## 2. Graduated gaps (G1–G7 from the 2026-06-14 review; G8 from the OQ1 probe — all verified shipped 2026-06-17)

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
| **G8** *(OQ1)* | add a *constrained* field in one atomic op | `apply`'s **`field add`** op-record now takes the same rich `<spec>` as the standalone verb (kind + per-kind config + metadata), single-sourced via `FieldSpec.parse` (the `Op` enum's field keys come from `FieldSpec.{ADD,MODIFY}_KEYS`) — no add-then-modify dance | an `apply` op with `string.pattern` + `number.maxFractionalDigits` commits + validates (`ApplyVerbTest.addsAConstrainedFieldInOneRichOp` + the CI-executed `apply.schema` example). Scope note: `batch` (standalone dispatch) and `apply`'s `field modify` (G6) already accepted the rich block — the only gap was apply's `field add` |
| **G10** *(OQ1)* | read a rule's stored message text | **`rule read`** (and `model read --rule`) now surface **`messages[]`** (locale → text), **raw + uninterpolated** — read straight from the model via `KernelAdapter.ruleMessages`, **native-capable** (no `model eval`) | `rule read <path>` → `data.messages[]`; `read.schema.json` declares it; locked by `ReadVerbTest.surfacesTheRulesStoredMessages` + `ruleReadSubcommandSurfacesMessagesInData` + the cli-tour demo. The *interpolated* preview stays the parked native case (§4) |

When a residual or a §4 candidate ships, it graduates the same way — a status-board row + (for a CLI verb) a CLI-SPEC contract, with its own test lock.

---

## 3. Open backlog

**New — from the OQ1 probe** ([`archived/OQ1-INVESTIGATION.md`](archived/OQ1-INVESTIGATION.md) §5), each grounded in a friction agents had to *script around*. **G8 and G10 have shipped → §2** (apply's rich `field add`; `rule read` message text). **G9** (the highest-leverage gap) and **G11** remain.

### G9 — a model-wide usage / audit read  *(medium; highest leverage)*

- **Need.** Cross-cutting questions over the whole model: *"which fields does no rule reference?"*, *"for every field, which rules constrain it?"* — a maintainer/audit staple.
- **Today.** Reads are **per-target** (`where-used --entity=X`, one entity). The only path is to **loop `where-used` over every field and aggregate by hand** — OQ1 **A and D both did exactly this**. No predicate / aggregate read exists.
- **Proposal.** A model-wide usage read returning, in one call, the **field → referencing-rules/computations map** plus the **unreferenced set** (derived from the same `getDirectlyReferencedEntities` primitive `where-used` / `rule deps` already use). **Contract to decide:** a new verb (`model usage` / `model audit`) vs a flag on an existing read (`field read --all --with-usage`). Closes the aggregate-query gap — the CLI's one structural weak spot (= OQ1 task **A**'s axis).

### G11 — accept a positional entity for `where-used`  *(trivial tag-along)*

- **Today.** `where-used` requires `--entity=<path>`; a positional path errors (*"Missing required option: --entity"*) — OQ1 E-k1 fumbled this.
- **Proposal.** Accept the entity **positionally** (keep `--entity` as the explicit alias), for consistency with `rule read <path>` / `field read <path>`.

**Residuals of shipped features (§2) — incremental side-tracks.**

- **R1 (was G2) — pattern idioms beyond `kind:rule`.** `patterns` ships 5 *rule* idioms. Not covered: **computation idioms** (mutually-exclusive precondition tables — the BA doc's other half) and **field-constraint idioms**. *(medium; separate track)*
- **R2 (was G7) — extend the `seeAlso` constraint↔operator bridge.** The mechanism ships + renders, but only the **7 NUMBER comparison ops** carry a `seeAlso` (→ `number.min/max`). Still unbridged: **STRING** (`pattern` ↔ `PatternMatched`/`PatternViolated`; `min/maxLength` ↔ `Length`), **ENUM** (value set ↔ `FieldValueIncludedInValueList` / `EnumCategory`), the **date-relational** families. Extend the single-sourced map (keep honest "none / see typedef" entries). *(small, incremental)*

**Sequencing.** **G8 + G10 shipped** (§2). Remaining first wave — **G9** (the highest-leverage gap). **G11** rides along (trivial). **R2** incremental; **R1** a separate computation-idiom track.

### Building these — start-work pointers

All three remaining (G9–G11) are **tool** improvements (capability/discoverability in the CLI, not skill text). Each follows the **schema/conformance discipline** (`CLAUDE.md` "Schema discipline — no drift, no rot"; [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md)): a new verb / changed op-record / new result key **updates its schema *and* flips the matching guard in the same commit**. Evidence + re-test: [`archived/OQ1-INVESTIGATION.md`](archived/OQ1-INVESTIGATION.md) §3 (the verbatim scripted-around calls) + §5 (the improvement write-ups); re-run the relevant fixture from [`archived/oq1-fixtures.zip`](archived/oq1-fixtures.zip) to reproduce the friction, then to confirm the fix. The "Today" facts below were **live-verified 2026-06-17**. *(G8's pattern, now shipped: reuse `FieldSpec.parse` in `ApplyCommand`'s field-op dispatch + update `apply.schema.json`'s CI-executed example — the template for the rest.)*

- **G9** — **first decide the contract: a new verb (`model usage` / `model audit`) vs a flag (`field read --all --with-usage`).** Aggregate the adapter's where-used primitive (**`KernelAdapter.getDirectlyReferencedEntities`**, already behind `where-used` / `rule deps`) over every field. A new verb ⇒ flip **`ManifestVerbTest.EXPECTED_VERBS`**, add a read-only **`verb-examples.json`** demonstration, satisfy **`CliConformanceMatrixTest`** (described · params-described · schema-resolves · demonstrated) + **`CommandReferencesResolveTest`**; new result keys ⇒ **`result.schema`** + **`SchemaVerbTest`**. *Done-when:* one call returns the **field → referencing-rules/computations map** + the **unreferenced set**, locked by a golden test.
- **G11** — add a **positional `<entity>`** param to **`WhereUsedCommand`** aliasing `--entity` (picocli params auto-derive into `manifest`/`--help` — no schema change). *Done-when:* `where-used /Order/X <model>` ≡ `where-used --entity=/Order/X <model>`.

When one ships it graduates into §2 (a status-board row + a CLI-SPEC contract + its test lock), exactly as G1–G7 did.

---

## 4. Out of scope / won't build

- **Workspace-wide cross-model refactor (OQ1 candidate, dropped).** Composable today by looping a per-model verb — OQ1 E ran `for m in orderA orderB orderC; do field rename …; done` cleanly. A cross-model refactor verb adds real complexity (reference resolution across includes, the per-model safety gate, partial-failure semantics over N files) for a loop that already works. Not worth a verb.
- **Native interpolated-message preview (was R3, parked).** `model eval` (JVM) interpolates the message; on the shipped **native** binary it's absent because runtime-eval is Groovy-only / hidden ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §4). A native structural-interpolation render isn't worth building unless a real need appears — the **raw-text read (G10)** covers the common "what does it say" case.
- **`dmtool serve` (warm-kernel daemon / stdin REPL) — won't build (decided 2026-06-15).** Examined and **rejected**; the premise collapsed once checked. Don't re-litigate without new evidence against these assumptions:
  - **The premise is dead.** "Seconds, not ms" was the *JVM* path (~600 ms+). The shipped CLI is the **native binary at ~37 ms** ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §6) — a warm process exists only to amortize JVM startup + JIT + kernel static init, none of which native pays.
  - **The cheap tier doesn't fix the real cost.** A stateless serve (re-passes `-m` per op, like `batch`) re-pays the per-model bootstrap every op. Only a **stateful warm-model** serve removes it — and that drags in the whole cache-coherence problem (staleness over the resolved file set, a `reload` that refuses on conflict, compare-and-swap on commit), the bulk of a ~1–2 week build and the part most able to corrupt work silently.
  - **`batch`/`apply` already occupy the space** ([`CLI-SPEC.md`](CLI-SPEC.md) §7): `batch` amortizes warm-up over a known op-list; `apply` keeps a warm in-memory model across an ordered transaction with one terminal gate + atomic rollback.
  - **It fights the agent-facing thesis** — the CLI is deliberately a self-explaining, **stateless** surface; serve adds session/lifecycle/reload/stale-write semantics, a second mental model that makes agent reasoning harder.
  - **The one narrow, future exception:** the JVM-only runtime-eval verbs (`model eval`/`rule test`/`model compute`) *do* pay Groovy-codegen warm-up every call. *If* they ever ship as a JVM artifact *and* an agent runs them in a tight interactive loop, a warm process becomes the only way to make them fast. Re-open only then.
- **Whole-model NL summary** ("explain every rule in business terms") — composable today by looping `rule explain`; a `model explain` convenience is minor.
- **Model-version migration** — a distinct concern with its own tool (`kernel-md-cli` → `MigratorCli`). dmkits authors/edits; it deliberately never bumps a model's version on write.
