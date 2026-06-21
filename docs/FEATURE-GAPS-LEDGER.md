# dmkits — BA-workflow gaps ledger

> **What this is.** A record of the **ergonomic gaps** in the BA+agent loop that a 2026-06-14 review (and the later OQ1 probe) surfaced — and what we decided about each. dmkits (rulekit + modelkit + `dmtool`) is driven by a **business analyst working through an LLM agent**: the BA holds a requirement in business terms ("delivery can't precede the order date"), and the agent realizes it through `dmtool` — authoring the **rule or computation** (rulekit), shaping the **model structure** it references (modelkit), and **reading/auditing** the result.
>
> Every gap this review raised has **shipped**; the doc no longer carries a per-gap graduation table (that was status — git + the docs record it). What it keeps is the **review's framing** (§1), the **identity + rationale-pointer** for each gap so code refs stay meaningful (§2), and the **out-of-scope decisions** (§4 — the durable part). Code comments citing `FEATURE-GAPS §N` / `Gn` resolve here by name.

---

## 1. The loop we're improving

A BA does not write DSL. The kernel BA doc frames the job as mapping a business requirement onto a rule that **describes the violation** (not the requirement), carrying a **rule group**, an **error field**, an **error text**, and a **severity** — plus **computation tables** (mutually-exclusive preconditions → calculations). Its recurring pitfalls — *polarity* (the rule is the error scenario), *"no generic negation"* (use the paired predicate), *error-field placement*, *computation-table exclusivity* — are where an agent stumbles.

dmkits serves the **correctness** axis well, and these strengths are the baseline:
- `schema rule add` **teaches polarity inline** ("TRUE on a VIOLATION … to enforce a requirement write its violation").
- `rule test --doc` / `model eval --doc` **confirm polarity by execution** (fired / passed / suppressed) and also return the **interpolated end-user message**.
- `RK_INVERTED_REQUIREDNESS` backstops the #1 trap; diagnostics are coded, located, fix-carrying (`diagnostics <CODE>`).

---

## 2. Shipped gaps — and where their rationale lives

The 2026-06-14 review's **G1–G7**, the OQ1-probe **G8–G11**, the residuals **R1–R4**, and the 2026-06-20 probe gaps **G12–G14** all shipped. Their *identity* (so a `Gn` code ref means something) and *where the durable why now lives*:

| gap | what it is | rationale / contract now in |
|---|---|---|
| **G1** | preview the resolved end-user message (`model eval` interpolates `$Field.value$`) | §4 caveat (native lacks it); the raw-text read is **G10** |
| **G2 / R1** | the validation-idiom catalogue (`patterns`) — rule idioms + the computation + field-constraint idioms | API-FINDINGS finding 32; the catalog |
| **G3** | operator `opposite` — the negation partner | API-FINDINGS finding 29 |
| **G4** | proactive error-field guidance (`rule check --suggest-error-field`) | API-FINDINGS finding 30 |
| **G5** | computation author-time lints | API-FINDINGS finding 31 |
| **G6** | `field modify` (re-type/constrain in place) | CLI-SPEC §5 |
| **G7 / R2** | the field-config ↔ operator `seeAlso` bridge | the operator catalog (`OperatorSeeAlsoTest`) |
| **G8** | rich per-kind `<spec>` in `apply`'s `field add` | CLI-SPEC §7 + the `apply` schema |
| **G9** | the model-wide usage/audit read (`model usage`) | CLI-SPEC §5 |
| **G10** | `rule read` surfaces a rule's stored message text (native-capable) | CLI-SPEC §5 |
| **G11** | `where-used` takes the entity positionally; output rides the result envelope | CLI-SPEC §6 |
| **R3 / R4** | the `workspace roles` lint (model `roles` ↔ `roles.yaml` ↔ `users.yaml`, advisory) | CLI-SPEC §5 ("the workspace roles lint") |
| **G12** | `apply` op-set parity — a `rule remove` op + a `cascade` key on `group remove`, the cascade single-sourced with the standalone verb via `GroupRemoval` so they can't drift (probe `01-de` Finding 8) | CLI-SPEC §6/§7 + the `apply` schema |
| **G13** | `operators <substring>` narrows the catalog (id/keyword/meaning) — the agent's natural reach; composes with `--kind`/`--keyword`, an exact id still gives full semantics (probe 01b, former finding **F1**) | CLI-SPEC §5 (the `operators` verb) |
| **G14** | invalid-on-disk load uniformity — `rule read`/`model describe` **re-throw** `ModelConsistencyException` so the one `Cli` seam renders the structured `rejected` envelope (Finding 7's residual two read verbs) | CLI-SPEC §3 (the result-envelope contract) |
| **G15** | **`group modify`** — change an existing group's repeatability in place (`--repeatable <max>` / `--non-repeatable`), re-bound to the kernel gate. Was settable only at `group add`, forcing a hand-edit of the JSON (real-session feedback, 2026-06-21). The `EditSession.setRepeatable` substrate already existed; this added the missing verb + `setNonRepeatable`. | CLI-SPEC §5 (the group verbs) |

Legacy **`FEATURE-GAPS §3.x`** section refs in code/docs are *pre-restructure* numbers — they resolve by **topic** (the gap rows above, or API-FINDINGS / CLI-SPEC §5–§6), not to a literal `§3.x` section in this doc (e.g. the §3.1 refactor set → CLI-SPEC §6 + API-FINDINGS finding 23). The earlier *structural authoring-coverage* inventory is likewise closed.

---

## 3. Open backlog

Surfaced by a **real authoring session against v0.4.0** (a cold Claude Code agent built a `Person` model end-to-end; write-ups: the user's `skill-fix-proposal.md` + `dmtool-improvement-notes.md`, 2026-06-21). The recurring shape across G15–G20: *the tool **reports** a property it gives no verb to **change**, so the agent falls back to hand-editing the model JSON* — exactly what a typed authoring kit must spare the user.

| gap | what it is | contract home when built |
|---|---|---|
| **G16** | **No way to register a locale on an existing model.** `model new --locale` sets the first; `config modify` has no `--add-locale`. Adding `de_DE` messages/labels does **not** auto-register `header.locales` (it's an explicit registry), so the model silently stays mono-locale until the header is hand-edited. **High.** Fix: `config modify --add-locale <code>` (append to `header.locales`), re-validated. | CLI-SPEC §5 |
| **G17** | **`rule modify --message` is replace-only (destructive).** It replaces the whole message set, so adding one translation drops the rest unless you resend them — a footgun for a localization pass. **Medium.** Fix: additive/merge by default (or an explicit `--replace-messages` vs merge). | CLI-SPEC §5 |
| **G18** | **`batch -m` accepted but not propagated** — the `export -m`/G12 uniformity class. `batch -m model ops.json` accepts `-m` (via `BaseCommand`) but each op needs its own `-m` in `args`, so a whole batch fails identically. **Medium.** Fix: honor the top-level `-m` as the default for ops that omit it, or reject it with a corrective diagnostic (don't silently ignore). | CLI-SPEC §7 |
| **G19** | **Element addressing differs across verbs.** `field add` uses `--group/--name`; `field read`/`rule read`/`rule modify` take a positional slash-path. The "how do I point at an element" model changes per verb. **Medium.** Fix: accept the positional path everywhere (or both) for the field reads/modifies at least. | CLI-SPEC §5 |
| **G20** | **Requiredness/rules not surfaced in friendly reads.** `field read` shows the type but not the friendly required state; `model describe` shows kinds/groups but not requiredness or rules. The stored `requirednessConfig.mode` has no friendly readout, so verifying a requiredness refactor meant grepping JSON. **Low.** Fix: echo the friendly `required` state in `field read`; surface requiredness + rules in `model describe`. | CLI-SPEC §5 |

Two **skill** findings (the session's highest-impact) are tracked separately — they improve the N6 material-under-test, not the tool surface: **(S1)** the skill is rules-only but is what surfaces for "create a document model," so the agent expressed *everything* as a rule; **(S2)** requiredness is a field flag (`required`), but the skill's polarity table teaches `"X must be provided" → FieldNotFilled(X)` as its first canonical row — actively modeling the anti-pattern (11 redundant `*_REQUIRED` rules in one model). `skill-fix-proposal.md` has concrete, tight edits (an "is it even a rule?" gate + a polarity-table caveat). **Doc gaps:** the locale model (registry vs labels vs messages), the roles/users/annotation contract (only in example files), and `apply`/`batch --help` pointing at `schema apply`/`schema batch`.

**Lifecycle — the convention (so this section never carries shipped work).** A gap lives here only while **open**. When it ships, **move its row up into §2** (Shipped gaps) in the *same commit*, with its rationale/contract column pointing at its owning spec — **API-FINDINGS** for a DSL/API gap, the **CLI specs** for a `dmtool` gap. This section is the live to-do list; §2 is the graduation record. Never leave a shipped item in this backlog, and never record a shipped item *only* here as a "shipped" sub-list — it belongs in §2.

---

## 4. Out of scope / won't build

The durable part of this ledger — what we considered and decided *against*, with the reasoning so it isn't re-litigated:

- **Workspace-wide cross-model refactor (OQ1 candidate, dropped).** Composable today by looping a per-model verb — OQ1 E ran `for m in orderA orderB orderC; do field rename …; done` cleanly. A cross-model refactor verb adds real complexity (reference resolution across includes, the per-model safety gate, partial-failure semantics over N files) for a loop that already works. Not worth a verb.
- **Native interpolated-message preview (the G1 caveat).** `model eval` (JVM) interpolates the message; on the **native** binary it's absent because runtime-eval is Groovy-only / hidden ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §4). A native structural-interpolation render isn't worth building — the **raw-text read (G10)** covers the common "what does it say" case. *Re-open only if* a native build needs the *interpolated* preview specifically.
- **`dmtool serve` (warm-kernel daemon / stdin REPL) — won't build (decided 2026-06-15).** Examined and rejected; don't re-litigate without new evidence against these assumptions:
  - **The premise is dead.** "Seconds, not ms" was the *JVM* path (~600 ms+). The shipped CLI is the **native binary at ~37 ms** ([`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md)) — a warm process exists only to amortize JVM startup + JIT + kernel static init, none of which native pays.
  - **The cheap tier doesn't fix the real cost.** A stateless serve (re-passes `-m` per op, like `batch`) re-pays the per-model bootstrap every op. Only a **stateful warm-model** serve removes it — and that drags in the whole cache-coherence problem (staleness over the resolved file set, a `reload` that refuses on conflict, compare-and-swap on commit), the bulk of a ~1–2 week build and the part most able to corrupt work silently.
  - **`batch`/`apply` already occupy the space** ([`CLI-SPEC.md`](CLI-SPEC.md) §7): `batch` amortizes warm-up over a known op-list; `apply` keeps a warm in-memory model across an ordered transaction with one terminal gate + atomic rollback.
  - **It fights the agent-facing thesis** — the CLI is deliberately a self-explaining, **stateless** surface; serve adds session/lifecycle/reload/stale-write semantics, a second mental model that makes agent reasoning harder.
  - **The one narrow, future exception:** the JVM-only runtime-eval verbs (`model eval`/`rule test`/`model compute`) *do* pay Groovy-codegen warm-up every call. **Re-open only if** they ever ship as a JVM artifact *and* an agent runs them in a tight interactive loop.
- **Whole-model NL summary** ("explain every rule in business terms") — composable today by looping `rule explain`; a `model explain` convenience is minor.
- **Model-version migration** — a distinct concern with its own tool (`kernel-md-cli` → `MigratorCli`). dmkits authors/edits and deliberately never bumps a model's version on write.
