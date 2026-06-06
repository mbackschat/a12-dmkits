---
name: run-rulekit-eval
description: Run the a12-dmkits self-sufficiency eval / Layer-2 benchmark correctly. Use whenever you are about to run, resume, or score the N6 eval in this repo (e.g. "run the benchmark", "run the eval", "re-run T4"). It enforces the protocol guardrails (clean subagent, material boundary, neutral prompts, pinned model, cost model, targeted re-runs). The full procedure is maintained once, in the Claude skill — this file points to it.
---

# Running the a12-dmkits self-sufficiency eval (N6)

This skill is intentionally a **pointer**, to avoid drift between agents. The canonical, maintained procedure — guardrails, run steps, cost model, and how to read results honestly — lives in **one place**:

> **`.claude/skills/run-rulekit-eval/SKILL.md`** (path from the repo root).

**Read and follow that file before running, resuming, or scoring the eval.** Do not run the benchmark from memory — its guardrails are easy to violate silently (a leaked technique in the prompt, an unpinned model, a broken material boundary, a wasteful full re-run).

The two things you must not get wrong (full detail in the canonical file):
- This is the **operator's** playbook — **never** give this skill, `docs/EVAL-SPEC.md`, source, or `docs/` to the eval's subagents. The subagents get only the CLI + the operator catalog + the N1 skill (`skill/SKILL.md`).
- The method/rationale reference is [`docs/EVAL-SPEC.md`](../../../docs/EVAL-SPEC.md); measured results go in [`eval/RESULTS.md`](../../../eval/RESULTS.md).
