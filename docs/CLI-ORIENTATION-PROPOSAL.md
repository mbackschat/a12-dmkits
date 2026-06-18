# CLI orientation — efficient cold-start for the agent (and the direct BA)

How a cold consumer **orients** to `dmtool` — finds what it can do, how to call it, and what comes back — with the fewest wasted tool calls and the fewest preventable errors, and what the **plugin/skill** should carry to support that. This is a **proposal** (design rationale + forward decisions), not a contract: the settled pieces already live in specs and this doc **references, never restates** them. Each decision **graduates** into its owning spec as it ships (§7).

**Defers to (the contracts this builds on):**

- [`CLI-SPEC.md`](CLI-SPEC.md) §2 — the self-explaining design *principle* + the "what an agent needs → where" table (the home this proposal's orientation decisions graduate into).
- [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md) §2 — the self-explain *contract* + the gates (`CliConformanceMatrixTest`/`VerbDemonstrationTest`) that prove every verb is surfaced and demonstrated.
- [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §6 — the *packaging* contract (the plugin, the COPY-synced skill, binary delivery). This proposal adds only the **orientation angle** on packaging (§6), not the mechanics.

## 1. The problem, and who actually consumes the surface

A cold agent pays an **orientation tax** before it does any work: calls spent discovering verbs, parameters, I/O shapes, and the DSL vocabulary. The goal is to cut that tax — and to **prevent the known recurring errors up front** rather than only catching them in a diagnostic afterward.

But "who reads the CLI surface" is not symmetric, and an earlier framing got it wrong. The audience model:

```
BA → (talks to) Agent → (runs) dmtool      [#1 path — RELEASE-SPEC §6]
       the AGENT renders results in NL for the BA
BA → (runs) dmtool directly                [secondary — the N10 "BA front-end" path]
       ← the human-readable surface (§5 dual-format) serves THIS path
```

**The CLI orients the *agent*; the agent orients the BA.** So orientation work targets the agent first. The *human* surface (a readable, non-JSON rendering) serves only the **secondary, direct-BA/developer path** — real, but not the #1 use, and sized accordingly. (This reconciles CLI-SPEC's "modeller/BA front-end (N10)" vision with RELEASE-SPEC's measured "#1 use is inside a coding-agent session": both are true; the BA is a *direct* consumer occasionally and an *indirect* one mostly.)

**In scope:** the orientation *design* (the read-when model, §2), the candidate improvements (§3), the cheap experiment that ranks them (§4), and what packaging carries for orientation (§6). **Out of scope:** the discoverability *contract* + gates (→ CLI-CONFORMANCE-SPEC §2) and the packaging *mechanics* (→ RELEASE-SPEC §6).

## 2. The orientation model — the read-when map (the spine)

Most of this **already exists**; the spine's job is to make the layering explicit so we *reduce* friction rather than add surfaces. Three layers, by precision and audience:

| Layer | Surface(s) | Answers | Audience | Format | Status |
|---|---|---|---|---|---|
| **Orientation** (cheap, universal) | `--help` (tool + per-verb) | "what is this, what can it do, how do I roughly call it" | agent **+** direct BA/dev | text | ✅ exists; **thin** (§3) |
| **Precision contract** | `manifest` · `schema <t> <op>` · `operators` · `patterns` · `diagnostics` | "exact verb tree, params, I/O shape, DSL vocabulary + gotchas, error codes" | agent | JSON | ✅ exists, gated (CLI-CONFORMANCE §2) |
| **Judgment** | `skill/SKILL.md` | "the traps — polarity, the iteration guard — and *when* to reach for what" | agent | markdown | ✅ exists; deliberately minimal |

The **standing rule** (CLAUDE.md "Self-explaining surface"; arcjet's *"do not embed every piece of documentation into the binary"*): discoverability lives in the **tool** (the precision layer, derived + gated); the **skill stays minimal** (judgment, not catalog). This proposal does **not** move the catalog into the skill — it sharpens the *orientation* layer and the *pointers between* layers.

## 3. Candidate improvements — hypotheses, not yet decided builds

Framed as hypotheses because the evidence is thin (§4). Each names who it serves, the no-drift constraint, and the gate it owes.

| # | Hypothesis | Serves | Cost / constraint |
|---|---|---|---|
| ~~**H1**~~ **REFUTED** | ~~Agents flail finding `manifest`.~~ The motivating "`--help` ×8" was a **grep artifact** (prompt text + operator-rich output), not real calls. The clean parse (§4 measured) shows agents **never call `manifest`** — they navigate via `--help` + the catalog verbs directly and efficiently. **No discovery gap; do not build.** | — | — |
| **H2** | **EXAMPLES in `--help` text.** A worked example **already exists in `manifest`** (`example{intent,command}`, executed by `VerbDemonstrationTest`); the gap is the **human-facing `--help` text**. | agent + direct BA/dev | **Must be single-sourced** from the same `verb-examples.json` (custom picocli `IHelpSectionRenderer`) — a *second* hand-authored copy reintroduces the exact drift the project gates against. |
| **H3** | **Dual-format output** (Design **B**: JSON stays the canonical/default contract; add an explicit `--text`/`--pretty`). | the **secondary** direct-BA/dev path only | Every envelope already has a `summary` field (`jq -r .summary` is a cheap human line *today*), so `--text`'s marginal value is the `data` block. **Not TTY-detection** — an agent in a PTY would silently get text. Conformance/eval/showboat must pin `--json`. |
| **H4** | **A `guide <area>` verb** — one call emits the manifest+schemas+operators slice for an area (e.g. `guide rule`). | agent | **Conditional on §4 proving call-*count* is the bottleneck.** Marginal over `manifest`+`operators`; adds a verb + a new operator-taxonomy to maintain (which operators are "rule" vs "computation") + **over-delivers context** (all condition operators when the task needs two); borderline against arcjet's "don't embed docs in the binary." **Do not lead with it.** |

**`llms.txt` framing — dropped.** It is a convention for *websites* crawled by LLMs; a local binary shipped via a plugin marketplace has **no passive non-executing consumer** to read such a file. Unless one is named, there is no warrant for a static bundle file — the *verb* (H4, if built) is the only sensible form, because it is derived and cannot drift.

### Measured verdict (2026-06-19 — the cheap probe deflated most of this)

The §4 cheap probe ran (read transcripts already on disk + one authoring run, 01+02, sonnet k=1; full data in [`../eval/RESULTS.md`](../eval/RESULTS.md)). What it found, and what it does to each hypothesis:

- **The orientation tax is real for authoring (10–18 produce calls vs 3–5 for reads) but *productive, not flailing*** — operator-semantics lookups, `patterns`, `model describe`, and a working **error→`fix` loop** (task 01 hit `MVK_NEG_CONDITION_IN_ITERATION` and looked up `diagnostics <code>` — the self-correction surface working). These calls are the agent *learning the DSL per-task*, not waste a help-blurb removes.
- **The one failure was a *judgment* miss** (task 01 over-constrained with `FieldFilled(Name)` — kernel-valid, narrower than intent — while it *had* the correct `patterns` guard idiom in hand). The real gap is **judgment/prevention**, which belongs to the **skill + catalog + lints**, *not* the help/guide system this proposal scopes.
- **Hypothesis ledger after the probe:** **H1 — dead** (refuted, above). **H2** (EXAMPLES-in-help) — **weakly supported**: agents already pull examples/semantics from per-verb `--help` + `patterns <id>` + `operators <id>`; the call volume is operator-*semantics*, not "how do I call this verb." **H3** (dual-format) — **orthogonal & unaffected**; still reasonable for the secondary direct-BA/dev path. **H4** (`guide` bundle) — the **only** one with call-count support (01's ~6 `operators` round-trips would collapse to one), but it **over-delivers** (~41 operators when 3–4 were wanted) and would not have prevented the miss.

**Net:** the help/guide system is not the bottleneck the proposal hypothesized. **Recommendation:** keep only **H3** as a small, independently-justified human-surface improvement; **shelve H1/H2/H4** pending a real signal; and route the genuine lever (judgment/over-constraint prevention, `patterns` trust) to the skill/catalog/lint work, outside this proposal's scope.

## 4. The measurement — cheap first, invest later

**Status: executed 2026-06-19** (steps 1–2 below). Step 3 was **not triggered** — the cheap signal already deflated the premise (see *Measured verdict*, §3, and [`../eval/RESULTS.md`](../eval/RESULTS.md)). The method below stays as the record of *how* it was measured and the bar for any re-measurement.

The premise ("reduce calls / prevent errors") is an empirical claim, and this project ranks by measured lift — but **the full N6 suite is too costly to spend on an efficiency question.** So the measurement is tiered by cost-vs-value:

1. **Free signal (do now, zero marginal cost).** Every Layer-2 run already reports `tool_uses`, `agent_count`, `subagent_tokens` in its result. **Start recording `tool_uses` alongside pass@k** in [`../eval/RESULTS.md`](../eval/RESULTS.md) for runs we already do. No new run needed.
2. **One cheap targeted baseline (only if step 1 is suggestive).** The existing **`simple` suite** (~6 tasks, k=1, sonnet — already the cheap tier) with the produce transcript inspected for: how many calls are *orientation* (`--help`/`manifest`/`schema`/`operators`) vs *work*, and whether an error→`fix`→retry loop occurred. This ranks H1 vs H2 vs H4.
3. **Invest in a dedicated efficiency metric / larger run only if** the cheap signal shows orientation is a material fraction of calls. **Do not run the full suite at k=3 to measure efficiency.**

The signal distinguishes the hypotheses: many `--help` calls but few `manifest` ⇒ **H1** (discovery gap); calls land but the *first attempt errors on a known trap* ⇒ prevention (H2/`patterns`); high total call count with correct content ⇒ **H4** (bundling). Prevention scope is bounded — see §8.

## 5. Decisions (now) vs. deferred (to data)

**Decided now:**

- **Audience model** — indirect-primary / direct-secondary (§1). The agent is the orientation target; human-format serves the secondary path.
- **Dual-format = Design B** *when built* — JSON canonical default + explicit `--text`; never TTY-detection.
- **EXAMPLES-in-help = single-sourced** from `verb-examples.json` if built — no second copy.
- **`llms.txt` / static bundle file = dropped** unless a non-executing consumer is named.

**Settled by the §4 probe (2026-06-19):** **H1 dead** (no manifest-discovery gap); **H1/H2/H4 shelved** pending a real signal (the orientation cost is productive, not flailing); **H3 kept** as a small independently-justified human-surface item; the genuine lever (judgment/over-constraint prevention, `patterns` trust) is **routed out of scope** to the skill/catalog/lint work. See *Measured verdict* (§3).

**Still deferred:** if a future, fuller authoring signal (more tasks, k≥3) shows a real reducible tax, re-open H2/H4 against it — the §4 method is the bar.

## 6. Packaging — what the plugin/skill carries for orientation

The packaging *contract* is [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §6 (the plugin vehicle, the COPY-synced skill, the `SessionStart` binary delivery). The only **orientation** addition here: the skill should carry, beyond its judgment content, a **thin "read-when" index** that points at the binary's self-describing surfaces (the §2 map) — *not* a catalog dump. The binary self-describes; the skill teaches judgment and **routes** the agent to the right surface. This keeps the skill minimal (the standing requirement) and honors arcjet's "docs in the skill, not the binary." Version-skew caveat: the skill is COPY-synced across plugin versions, so it must not reference a verb (e.g. a future `guide`) that an installed older binary lacks — route only to surfaces guaranteed present, or gate the mention on a version.

## 7. Graduation — where each piece lands when it ships

This proposal shrinks to a pointer as items graduate (the FEATURE-GAPS pattern):

| Item | Graduates into | Form |
|---|---|---|
| The read-when model (§2) | [`CLI-SPEC.md`](CLI-SPEC.md) §2 | extend the "what an agent needs → where" table with the layer/audience framing |
| H1 (`--help` → `manifest` pointer) | CLI-SPEC §2 + the conformance gate | `--help` content + a guard |
| H2 (EXAMPLES-in-help, single-sourced) | CLI-SPEC §2 + [`CLI-CONFORMANCE-SPEC.md`](CLI-CONFORMANCE-SPEC.md) §2 | the help-section render + a no-drift gate over the shared source |
| H3 (dual-format, Design B) | CLI-SPEC §2 (a format section) | `--text` + conformance pinning `--json` |
| H4 (`guide` verb, *if* built) | CLI-SPEC §5 (verb surface) + CLI-CONFORMANCE §2 (matrix row) | a new verb + its slicing taxonomy + gates |
| Orientation index in the skill (§6) | [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §6 + `skill/SKILL.md` | the read-when section |

## 8. Open questions & risks (the red-team residue)

- **Prevention vs. the valued self-correction loop.** The project deliberately *measures* error→`fix`→recover as a diagnostic-quality signal (the `self-correction` eval family). "Prevent errors up front" must be **scoped to the known recurring traps** (inverted polarity, ISO-vs-German date) — not a blanket goal, or it undercuts the "good diagnostics let an agent recover" thesis. Prevent the preventable-and-recurring; keep recover-from-the-rest.
- **Surface sprawl.** Layers already number five-plus (`--help`, `manifest`, `schema`, `operators`, `patterns`, `diagnostics`, skill). The spine (§2) is the antidote *only if* enforced — adding H4 without it makes the sprawl worse, not better.
- **Each new surface owes a gate.** H2/H3/H4 each need a no-drift guard + tests; the cost is the *gate*, not just the feature (CLI-CONFORMANCE discipline).
- **The H1/H2 disambiguation is load-bearing.** If the bottleneck is discovery (H1), enriching `--help` content (H2) is the wrong, more expensive fix. §4 step 2 must separate them before committing.
