---
name: run-master-session
description: Run a multi-session work program with the hybrid model policy and review gate — use when orchestrating work that spans multiple sessions (master session duties, worker-prompt minting, per-set tier choice, gating a worker session's landing). The full protocol is maintained once, in the Claude skill — this file points to it.
---

# Running a multi-session work program

This skill is intentionally a **pointer**, to avoid drift between agents. The canonical, maintained protocol — the master/worker structure, the per-set tier choice, the hardened drill, the review-gate checklist, and the gate-proven drill criteria — lives in **one place**:

> **`.claude/skills/run-master-session/SKILL.md`** (path from the repo root).

**Read and follow that file before starting or gating a program session.** Do not run the protocol from memory — its gate checklist and drill criteria are easy to violate silently. One rule holds even before reading it: **one active session per worktree, always.**
