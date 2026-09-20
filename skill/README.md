# The canonical skills

## The problem

`dmtool` describes itself well — `manifest`, `operators`, `schema` and `diagnostics` tell an agent the verbs, the parameters and every output shape. None of that can teach **judgment**, and judgment is where an agent actually fails: writing a condition with the polarity inverted, choosing an error field outside the condition's scope, misjudging what one rule iterates over. Each of those produces a model the kernel *accepts*, so nothing contradicts the mistake.

A skill is what covers that gap. And because a skill is prose rather than a program, it needs a single home and a discipline — two copies of it ship, and a wrong sentence in it misleads every agent that reads it.

## What is here

The two skills shipped to coding agents, authored **here** and copied everywhere else from here.

| file | ships as | what it teaches |
|---|---|---|
| [`SKILL.md`](SKILL.md) | `a12-dmtool` | the judgment `--help` cannot reach — condition **polarity**, error-field paths, iteration scope, the bilingual locale cascade, and *"is this even a rule?"* |
| [`BUG-REPORT-SKILL.md`](BUG-REPORT-SKILL.md) | `a12-dmtool-bug-report` | how to file a crisp, minimal-repro bug-and-findings report against `dmtool`, and how to tell a genuine defect from one's own input mistake |

**Copies are generated, never edited.** [`../scripts/sync-plugin-assets.sh`](../scripts/sync-plugin-assets.sh) materializes both into [`../plugin`](../plugin) and [`../plugin-codex`](../plugin-codex), and `--check` fails on drift. The probe's sandbox takes them by explicit path too, so nothing here copies a directory wholesale.

## `SKILL.md` is measured, so it is governed

It is the **material under test** for the self-sufficiency eval, which means a wrong or stale claim in it is the worst class of defect here: it actively misleads every agent that reads it, and only an expensive probe run catches it — no JUnit guard can. Two things are therefore mandatory before any edit, in order:

1. **Re-read the whole file**, not just the section being changed. Its sections interlock, and editing one paragraph in isolation silently contradicts another.
2. **Execute every factual claim against the current binary** — every DSL snippet, operator name, error code, path form and "X is rejected / Y is valid" assertion, through `rule check`, `dmtool operators <id>`, `dmtool schema …` or `dmtool manifest`. If you cannot run it, you cannot write it. A **runtime-semantics** claim needs a stronger oracle still: `model eval` runs the interpreter, so what fires and where an error attaches must be verified against the real kernel.

**And most facts do not belong here at all.** A fact about one operator goes in the operator catalog, a fact about one spec field goes in the published JSON schema, and a fact about one kernel rejection goes in the diagnostic enricher — each of those surfaces is self-describing and guard-checked. Only cross-cutting judgment, or a trap that spans constructs, earns a place in the skill, because the skill is measured and a fact any other surface could carry is cost without reach.

Method and rationale: [`docs/EVAL-SPEC.md`](../docs/EVAL-SPEC.md) · measured results: [`eval/RESULTS.md`](../eval/RESULTS.md) · the placement rule in full: [`CLAUDE.md`](../CLAUDE.md).
