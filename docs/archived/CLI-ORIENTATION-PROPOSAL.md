# CLI orientation — efficient cold-start for the agent (and the direct BA)

> **Archived / resolved (2026-06-19).** This proposal is complete: its two surviving recommendations — **R1** (the `--help` orientation footer) and **R2** (`--text` dual-format) — shipped, and the rest were measured-and-shelved. The durable design (the audience model + the read-when layer map) and the `manifest`-positioning finding now live in [`../CLI-SPEC.md`](../CLI-SPEC.md) §2. This file is kept as the **measurement record** (the §4 probe, the H1–H4 dispositioning); it is not a live contract.

How a cold consumer **orients** to `dmtool` — finds what it can do, how to call it, and what comes back — with the fewest wasted tool calls and the fewest preventable errors, and what the **plugin/skill** should carry to support that. This is a **proposal** (design rationale + forward decisions), not a contract: the settled pieces already live in specs and this doc **references, never restates** them. Each decision **graduates** into its owning spec as it ships (§7).

**Status (2026-06-19):** the premise was tested cheaply (§4) and **largely did not hold** — the help/guide system is *not* the bottleneck this doc first hypothesized. Two small recommendations survive, **both now shipped**: **R1 (the `--help` orientation footer)** and **R2 (dual-format `--text`)**. The rest is shelved or routed out of scope. Read §3 first.

**Defers to (the contracts this builds on):**

- [`CLI-SPEC.md`](../CLI-SPEC.md) §2 — the self-explaining design *principle* + the "what an agent needs → where" table (the home this proposal's orientation decisions graduate into).
- [`CLI-CONFORMANCE-SPEC.md`](../CLI-CONFORMANCE-SPEC.md) §2 — the self-explain *contract* + the gates (`CliConformanceMatrixTest`/`VerbDemonstrationTest`) that prove every verb is surfaced and demonstrated.
- [`RELEASE-SPEC.md`](../RELEASE-SPEC.md) §6 — the *packaging* contract (the plugin, the COPY-synced skill, binary delivery). This proposal adds only the **orientation angle** on packaging (§6), not the mechanics.

## 1. The problem, and who actually consumes the surface

A cold agent pays an **orientation tax** before it does any work: calls spent discovering verbs, parameters, I/O shapes, and the DSL vocabulary. The original goal was to cut that tax — and to prevent the known recurring errors up front rather than only catching them in a diagnostic afterward. (The measurement, §4, then found the tax is mostly *productive*, not waste — which is why most of §3 is shelved.)

"Who reads the CLI surface" is not symmetric, and an earlier framing got it wrong. The audience model:

```
BA → (talks to) Agent → (runs) dmtool      [#1 path — RELEASE-SPEC §6]
       the AGENT renders results in NL for the BA
BA → (runs) dmtool directly                [secondary — the N10 "BA front-end" path]
       ← the human-readable surface (§3 R2 dual-format) serves THIS path
```

**The CLI orients the *agent*; the agent orients the BA.** So orientation work targets the agent first. The *human* surface (a readable, non-JSON rendering) serves only the **secondary, direct-BA/developer path** — real, but not the #1 use, and sized accordingly. (This reconciles CLI-SPEC's "modeller/BA front-end (N10)" vision with RELEASE-SPEC's measured "#1 use is inside a coding-agent session": both are true; the BA is a *direct* consumer occasionally and an *indirect* one mostly.)

**In scope:** the orientation *design* (§2), the candidate improvements + what the measurement did to them (§3), the experiment itself (§4), packaging for orientation (§6). **Out of scope:** the discoverability *contract* + gates (→ CLI-CONFORMANCE-SPEC §2), the packaging *mechanics* (→ RELEASE-SPEC §6), and the **judgment/prevention** lever the probe surfaced (→ the skill/catalog/lint work).

## 2. The orientation model — the read-when map (the spine)

Most of this **already exists**; the spine's job is to make the layering explicit so we *reduce* friction rather than add surfaces. Three layers, by precision and audience:

| Layer | Surface(s) | Answers | Audience | Format |
|---|---|---|---|---|
| **Orientation** (cheap, universal, first contact) | `--help` (tool + per-verb) | "what is this, what can it do, how do I roughly call it" | agent **+** direct BA/dev | text |
| **Precision contract** | `manifest` · `schema <t> <op>` · `operators` · `patterns` · `diagnostics` | "exact verb tree, params, I/O shape, DSL vocabulary + gotchas, error codes" | agent (+ conformance machinery) | JSON |
| **Judgment** | `skill/SKILL.md` | "the traps — polarity, the iteration guard — and *when* to reach for what" | agent | markdown |

The **standing rule** (CLAUDE.md "Self-explaining surface"; arcjet's *"do not embed every piece of documentation into the binary"*): discoverability lives in the **tool** (the precision layer, derived + gated); the **skill stays minimal** (judgment, not catalog). This proposal does **not** move the catalog into the skill — it sharpens the *orientation* layer (§3 R1) and the *pointers between* layers.

**Measured navigation pattern (§4):** agents reach top-level `--help` **once**, drill to the 1–2 verbs they need via per-verb `--help`, and pull DSL knowledge from `operators`/`patterns`/`diagnostics` directly. They **do not** call `manifest` — see the positioning finding (§3).

## 3. Candidate improvements — measured and dispositioned

Four candidates were considered; the §4 probe (2026-06-19) dispositioned each. Summary, then the two that survive.

| Candidate (orig. label) | Idea | Disposition |
|---|---|---|
| Manifest-discovery pointer (~~H1~~) | `--help` points at `manifest` | **Dropped — refuted.** Agents never call `manifest` and don't flail; the motivating "`--help` ×8" was a **grep artifact** (prompt text + operator-rich output), not real calls. |
| Per-verb EXAMPLES in `--help` (~~H2~~) | a worked example in each verb's help text | **Shelved — weakly supported.** Agents already pull semantics from per-verb `--help` + `patterns <id>` + `operators <id>`; the call volume is operator-*semantics*, not "how do I call this verb." |
| `guide <area>` bundle (~~H4~~) | one call emits the manifest+schemas+operators slice for an area | **Shelved.** Only call-count support; **over-delivers** (~41 operators when 3–4 wanted); wouldn't have prevented the one observed failure; borderline against "don't embed docs in the binary." |
| **Top-level orientation footer** (new) | a few lines on the front `--help` screen | **Kept → R1.** The cheap middle ground the data points to. |
| **Dual-format output** (~~H3~~) | `--text` alongside the JSON default | **Kept → R2.** Orthogonal to the orientation tax; independently justified for the secondary direct-BA/dev path. |

### R1 — a top-level `--help` orientation footer (the cheap middle) — ✅ SHIPPED 2026-06-19

Graduated into [`CLI-SPEC.md`](../CLI-SPEC.md) §2; locked by `CliHelpTest`; single-sourced on `Cli.TOOL_NAME`; no new gate (the existing `CommandReferencesResolveTest` covers its verb refs). The shipped footer:

```
Getting started (the tool describes itself):
  Whole verb tree as JSON, one call:     dmtool manifest
  A verb's params + a worked example:    dmtool <target> <op> --help
  DSL vocabulary (all, or one by <id>):  dmtool operators
  Rule/computation idioms (scaffolds):   dmtool patterns
  Decode an error code:                  dmtool diagnostics <CODE>
```

The design (the cheap middle ground): not per-verb examples (shelved) and not the 66 KB `manifest` (agents rationally skip it for focused work) — a **short footer on the *top-level* `--help`**, the one screen every agent hits first, exactly once.

**Honest sizing:** for the *diligent* agents measured this is **marginal** — they already found that path unaided. Its value is (1) **legibility** for a less-thorough agent, (2) it **positions `manifest` correctly** at first contact ("the whole tree in one call" — the bulk option, not the only door — see the finding below), and (3) it is the **read-when map (§2) in miniature**, delivered in the binary. Cost is ~5 static lines referencing only verb names, so the existing `CommandReferencesResolveTest` already guards it against drift — no new gate. It is the cheapest defensible change.

### R2 — dual-format output (Design B) — ✅ SHIPPED 2026-06-19

A global `--text` flag (graduated into [`CLI-SPEC.md`](../CLI-SPEC.md) §2; locked by `CliTextFormatTest`). JSON stays the **canonical/default** contract; `--text` renders the result envelope as human text for the **secondary** direct-BA/dev path. **Not TTY-detection** — an explicit flag, so an agent in a PTY never silently gets text; JSON being the default means conformance/eval/showboat are unchanged (they never pass `--text`). Implementation: a gated boundary view-transform in `Cli.run` (`TextRenderer`) re-renders only an envelope and passes catalogs/artifacts (`operators`/`manifest`/`export`/…) through as JSON — so it needed no change to the ~88 emit sites. (The original sketch also mentioned `--pretty`; shipped as the single `--text`.)

### The positioning finding — `manifest` is the bulk/machine door, not the agent's front door

Measured across every run (read **and** authoring), produce agents **never call `manifest`** — and that is **rational, not broken**:

- `--help` is the **universal convention** agents are trained on; `manifest` is a bespoke verb with no prior.
- For a **focused** task, walking `--help` to the 1–2 needed verbs is **cheaper** than pulling the **~66 KB** whole-tree `manifest`. Manifest's "one call for everything" only pays off when you need *everything*.

`manifest` is **not wasted** — it is the **conformance backbone** (`ManifestVerbTest`, the conformance matrix, the schema pointers all *derive* from it), so it earns its keep regardless of agent use. The correction is one of **positioning**: CLI-SPEC §2 lists `manifest` as a co-equal "what can this tool do?" mechanism *beside* `--help`; the data says the **primary agent-orientation path is `--help` + the catalog verbs**, and `manifest` is the **bulk/machine + conformance** variant. (This finding graduates into CLI-SPEC §2, §7.)

**The one place this could have bitten — verified CLOSED (2026-06-19).** Composing a multi-verb `apply`/`batch` op-list needs each op's `key` (the `--`-stripped param name), which `manifest` carries — the hypothesized place an agent skipping `manifest` might guess wrong. A `session`/`apply` probe (mixed 01+02) settled it: both **passed 2/2** and **neither used `manifest`** — they composed correct op-lists from `schema apply` + **per-verb `--help`** (whose flag names *are* the op-keys) + `schema rule add`. `--help` fully substitutes for `manifest` even here. **No residual risk; the finding stands unqualified.** (RESULTS 2026-06-19 00:20.)

**`llms.txt` framing — dropped.** It is a convention for *websites* crawled by LLMs; a local binary shipped via a plugin marketplace has **no passive non-executing consumer** to read such a file. Unless one is named, there is no warrant for a static bundle file.

**The genuine lever the probe surfaced — out of scope here.** The one observed failure (task 01 over-constrained with `FieldFilled(Name)` while *holding* the correct `patterns` idiom) was a **judgment** miss, not an orientation gap. That belongs to the **skill + catalog + lints**, not the help/guide system — recorded here so it is not lost, routed there to act on.

## 4. The measurement — cheap first (executed 2026-06-19)

**Done.** Step 3 (a dedicated metric / larger run) was **not triggered** — the cheap signal already settled the question (data in [`../eval/RESULTS.md`](../../eval/RESULTS.md)). The tiering below stays as the record of *how* it was measured and the bar for any re-measurement.

The premise ("reduce calls / prevent errors") is empirical, and this project ranks by measured lift — but **the full N6 suite is too costly to spend on an efficiency question.** So the measurement was tiered by cost-vs-value:

1. **Free signal (zero marginal cost).** Every Layer-2 run already reports `tool_uses`/`agent_count`/`subagent_tokens`. The existing read-task transcripts were parsed clean (actual Bash tool-calls, not raw-token grep — the grep over-counted via the prompt + operator-rich output). Recorded alongside pass@k in `RESULTS.md`.
2. **One cheap targeted baseline.** Two authoring tasks (01+02, sonnet, k=1, ~6 agents) with the produce transcript parsed for orientation-vs-work calls + error→`fix` loops. (A `session`/`apply` probe follows to close the batch-op-key question, §3.)
3. **Invest further only if warranted** — not triggered.

The signal distinguished the candidates cleanly: many `--help` but zero `manifest` ⇒ no discovery gap (refuted the manifest pointer); calls are operator-*semantics* + a working fix-loop ⇒ per-verb examples weakly supported; the failure was *judgment* ⇒ route to the skill/lint, not help.

## 5. Decisions

**Decided (2026-06-19):**

- **Audience model** — indirect-primary / direct-secondary (§1). The agent is the orientation target; human-format serves the secondary path.
- **R1 — top-level `--help` orientation footer** — ✅ **SHIPPED 2026-06-19** (graduated into CLI-SPEC §2; locked by `CliHelpTest`; no new gate; delivers the positioning correction at first contact).
- **R2 — dual-format, Design B** — ✅ **SHIPPED 2026-06-19** (the `--text` flag; graduated into CLI-SPEC §2; locked by `CliTextFormatTest`). JSON canonical default + explicit `--text`; never TTY-detection.
- **`manifest` positioning** — the bulk/machine + conformance surface, not the agent's primary door (graduates into CLI-SPEC §2).
- **`llms.txt` / static bundle file = dropped** unless a non-executing consumer is named.

**Shelved (pending a real signal):** per-verb EXAMPLES-in-help (weakly supported); the `guide` verb (over-delivers, no call-count win that matters); the manifest-discovery pointer (refuted).

**Out of scope (routed elsewhere):** judgment/over-constraint prevention + `patterns` trust → the skill/catalog/lint work.

## 6. Packaging — what the plugin/skill carries for orientation

The packaging *contract* is [`RELEASE-SPEC.md`](../RELEASE-SPEC.md) §6 (the plugin vehicle, the COPY-synced skill, the `SessionStart` binary delivery). The only **orientation** addition: the skill should carry, beyond its judgment content, a **thin "read-when" index** pointing at the binary's self-describing surfaces (the §2 map) — *not* a catalog dump. R1 (the `--help` footer) is the **binary-side twin** of that skill index: the same read-when routing, delivered at first contact for an agent that never reads the skill. This keeps the skill minimal (the standing requirement) and honors arcjet's "docs in the skill, not the binary." Version-skew caveat: the skill is COPY-synced across plugin versions, so it must not reference a verb an installed older binary lacks.

## 7. Graduation — where each piece lands when it ships

This proposal shrinks to a pointer as items graduate (the FEATURE-GAPS pattern):

| Item | Graduates into | Form |
|---|---|---|
| The read-when model (§2) | [`CLI-SPEC.md`](../CLI-SPEC.md) §2 | extend the "what an agent needs → where" table with the layer/audience framing |
| The `manifest` positioning finding (§3) | CLI-SPEC §2 | reframe `manifest` as the bulk/machine + conformance surface; the agent's front door is `--help` + catalog verbs |
| **R1** — `--help` orientation footer ✅ **shipped** | CLI-SPEC §2 (done) | the footer content (verb-name refs only → already drift-guarded); locked by `CliHelpTest` |
| **R2** — dual-format (Design B) ✅ **shipped** | CLI-SPEC §2 (done) | the `--text` flag; locked by `CliTextFormatTest` |
| Orientation index in the skill (§6) | [`RELEASE-SPEC.md`](../RELEASE-SPEC.md) §6 + `skill/SKILL.md` | the read-when section |
| *(shelved)* per-verb examples, `guide` verb | — | not graduating; re-open only on a real signal |

## 8. Open questions & risks

- **Prevention vs. the valued self-correction loop.** The project deliberately *measures* error→`fix`→recover as a diagnostic-quality signal (the `self-correction` eval family). Any future "prevent errors up front" work must be **scoped to the known recurring traps** (inverted polarity, ISO-vs-German date) — not a blanket goal, or it undercuts the "good diagnostics let an agent recover" thesis. The probe's one failure (over-constraint) is exactly such a judgment trap → skill/lint, not help.
- **Surface sprawl.** Orientation surfaces already number several (`--help`, `manifest`, `schema`, `operators`, `patterns`, `diagnostics`, skill). The spine (§2) is the antidote *only if* enforced; R1 reinforces it (a router), the shelved `guide` verb would have worsened it (another surface).
- **Each new surface owes a gate.** R2 needs conformance to pin `--json`; R1 needs none (verb-name refs are already guarded). The cost of any future surface is the *gate*, not just the feature.
- ~~**The batch/apply manifest-skip case (§3) is open**~~ **CLOSED (2026-06-19)** — the `session` probe showed `apply` agents compose correct op-lists from `schema apply` + per-verb `--help` without `manifest`; no residual risk.
