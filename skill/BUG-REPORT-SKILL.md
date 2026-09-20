---
name: a12-dmtool-bug-report
description: Produce a well-isolated bug-and-findings report for the dmtool CLI (a12-dmkits / A12 kernel) and save it to a folder the user picks. Use when a user hits an error, crash, internal error, or unexpected behaviour in dmtool, when they want to capture findings / DX friction / improvement notes, or when they ask to write or file a bug report against dmtool. Covers reading the result envelope (telling an internal tool error apart from a rejected input), shrinking to a minimal repro, an affected-vs-unaffected table, classifying genuine defects vs. your own input mistakes, a verbatim replay log, and a self-audit — then writing the report to a folder the user can upload or send however they like.
---

# Filing a bug-and-findings report against dmtool

You turn a "something broke in dmtool" moment into a **crisp, reproducible, correctly-scoped bug-and-findings report**, and save it to a folder the user can hand on. The prose template is the easy part; the judgment below — separating a real defect from your own mistake, shrinking the repro, not trusting the tool's own pass/fail, and capturing *all* of it — is the point.

## Don't trust the tool's own pass/fail — classify each error
A non-zero exit is usually a **legitimate rejection**, not a bug. Sort every error you hit into three:

| What you see | What it is | Report it? |
|---|---|---|
| `outcome:"rejected"`/`"refused"` + a real `MVK_*`/`RK_*` code + a clear summary/fix | the kernel evaluated your input and it's genuinely wrong | **No** — unless the *message itself* misled you |
| **`outcome:"error"`**, a diagnostic with code **`RK_INTERNAL_ERROR`**, **exit 70**, or a raw Java stack trace / `Exception` on stderr | an **internal tool error** — the tool *broke* | **Yes, always** |
| `Unknown option` / usage text, or a malformed spec you then fixed | your own call/spec mistake | **No** — fix the call; list it honestly so the reader can tell it apart |

> The tool tells you which it is: an internal failure is a structured **`error`** envelope (exit 70) with the real cause in its `summary` and the trace on stderr — it does **not** masquerade as a `valid:false` rejection. Run `dmtool diagnostics RK_INTERNAL_ERROR` for what it means.

## The discipline
1. **Classify every error**, honestly (above). One session surfaces SEVERAL findings + friction — capture **all** of them, not just the headline or the last one hit.
2. **Shrink a real defect to a minimal repro** — the smallest `dmtool model new` + fewest fields that still fails; confirm it reproduces. Don't report against the user's big model.
3. **Isolate the trigger** — bisect (change one operator / flag / construct at a time). An affected-vs-unaffected table is the most valuable evidence in the report.
4. **Scope it** — which verbs reach the defect (the verb set depends on the defect *class*, not a fixed list).
5. **Provenance** — record `dmtool --version` (and the plugin/package version if known).
6. **Trim the evidence** — the distinctive codes/frames, never a whole multi-hundred-line trace.
7. **Findings & improvements** — every place the tool's own signals **misled or slowed** the work, each with a concrete suggested fix aimed at one target: the **CLI**, its **material** (operator catalog / schemas / `--help` / diagnostics), or the **skill**. Reflect on the *whole* tool-call trail, not just the calls around an error.
8. **Replay log** — the user's prompts that drove model changes, verbatim and in order, so the session is replayable.
9. **Self-audit** — did you stay inside dmtool's surface? Two checks, state both: **(a) writes** — was any model file *changed* **outside** dmtool (an editor / `sed` / `echo` / `cp`)? A direct edit can masquerade as a defect and taints the repro. **(b) reads** — did you *read* a model's raw `.dm.json` (via `cat` / `jq` / `python` / an editor) instead of `model describe` / `field read` / `rule read`? You shouldn't have needed to — and if you did, say so **and file it as a `[material]` finding**: the structured read that should have shown what you needed is incomplete (exactly the gap a raw read papers over).

## Where to save it
**Ask the user where to save the report** (e.g. *"Which folder should I write the bug report to?"*). With no answer — or when you're running non-interactively — default to a **`dmtool-bug-reports/`** folder in the current working directory.

Create that folder and write:
- `report.md` — the report (use the template below);
- any supporting artifacts you trimmed (full traces, the minimal repro model JSON) as sibling files, referenced from the report.

Then tell the user the folder path and that they can **upload it, attach it to an issue, or send it** however they like — this skill does not transmit anything itself.

## Report template
```
# Bug report: <one-line title>
## Summary           — what breaks, in one paragraph
## Environment       — dmtool --version; OS; kernel/distribution if shown
## Severity / impact — who it blocks, and any workaround
## Steps to reproduce — the minimal, from-scratch commands
## Expected vs. actual
## Affected vs. unaffected — the bisect table (the key evidence)
## Exception (trimmed) — only the distinctive frames/codes
## Findings & improvements — [CLI] / [material] / [skill], each with a suggested fix
## Replay log         — the driving prompts, verbatim, in order
## Self-audit         — out-of-dmtool model writes (taint check) AND raw-JSON reads (→ a [material] finding)
```
