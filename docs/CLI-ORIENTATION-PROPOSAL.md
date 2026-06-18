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
| **H1** | **Agents flail finding `manifest`.** The one eval data point (a cold agent called `--help` **8×**) is at least as consistent with "never found `manifest`" as with "`--help` is too thin." Cheap fix: `--help` **points to** `manifest`/`operators` ("for the whole tree in one call: `dmtool manifest`"). | agent | ~one line; possibly the real highest-ROI, cheaper than H2. **Disambiguate before H2.** |
| **H2** | **EXAMPLES in `--help` text.** A worked example **already exists in `manifest`** (`example{intent,command}`, executed by `VerbDemonstrationTest`); the gap is the **human-facing `--help` text**. | agent + direct BA/dev | **Must be single-sourced** from the same `verb-examples.json` (custom picocli `IHelpSectionRenderer`) — a *second* hand-authored copy reintroduces the exact drift the project gates against. |
| **H3** | **Dual-format output** (Design **B**: JSON stays the canonical/default contract; add an explicit `--text`/`--pretty`). | the **secondary** direct-BA/dev path only | Every envelope already has a `summary` field (`jq -r .summary` is a cheap human line *today*), so `--text`'s marginal value is the `data` block. **Not TTY-detection** — an agent in a PTY would silently get text. Conformance/eval/showboat must pin `--json`. |
| **H4** | **A `guide <area>` verb** — one call emits the manifest+schemas+operators slice for an area (e.g. `guide rule`). | agent | **Conditional on §4 proving call-*count* is the bottleneck.** Marginal over `manifest`+`operators`; adds a verb + a new operator-taxonomy to maintain (which operators are "rule" vs "computation") + **over-delivers context** (all condition operators when the task needs two); borderline against arcjet's "don't embed docs in the binary." **Do not lead with it.** |

**`llms.txt` framing — dropped.** It is a convention for *websites* crawled by LLMs; a local binary shipped via a plugin marketplace has **no passive non-executing consumer** to read such a file. Unless one is named, there is no warrant for a static bundle file — the *verb* (H4, if built) is the only sensible form, because it is derived and cannot drift.

## 4. The measurement — cheap first, invest later

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

**Deferred to the §4 signal:** the *priority* among H1/H2/H4, and whether H4 (the `guide` verb) is built at all.

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
