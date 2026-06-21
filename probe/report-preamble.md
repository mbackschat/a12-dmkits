# Probe report pass — the agent writes its OWN bug-and-findings report (single-sourced)

The main loop appends this as a **final turn** to the probe session, to the SAME agent that ran the task. The
agent holds the full trajectory **and the WHY** (what it tried, what was rejected, how it recovered) — it is
the richest source for the narrative, so it writes the report, not the main loop. The OBJECTIVE layer (the
shim-log `digest-probe.sh` RED-FLAG + the `audit-probe.sh` anti-cheat) is the *independent* backstop that
catches what the agent didn't notice (a false success it believed); the main loop reconciles the agent's
report against it. This preamble carries the report RIGOR (formerly an external skill) so the probe is
self-contained.

---

Now, in this same session, write a **bug-and-findings report** for the dmtool work you just did. You have the
full context — what you attempted and *why* — so you are the best source. The prose template is the easy
part; the judgment below is the point.

## Don't trust the tool's own pass/fail — classify each error
A non-zero exit is usually a **legitimate rejection**, not a bug. Sort every error you hit into three:

- **Legitimate rejection** — `outcome:"rejected"`/`"refused"` + a real `MVK_*`/`RK_*` code and a clear
  summary/fix: the kernel evaluated your input and it's genuinely wrong. **Not a bug** — unless the *message*
  itself misled you.
- **Internal tool error (a real defect — always report)** — `outcome:"error"`, a diagnostic with code
  `RK_INTERNAL_ERROR`, exit code 70, or a raw Java stack trace / `Exception` on stderr. The tool *broke*.
- **Your own mistake** — a usage error or a malformed spec you then fixed. Not a bug; list it honestly so the
  reader can tell it apart from a defect.

## The discipline
1. **Classify every error**, honestly (above). One session surfaces SEVERAL findings + friction — capture
   **all** of them, not just the headline or the last one hit.
2. **Shrink a real defect to a minimal repro** — the smallest `model new` + fewest fields that still fails;
   confirm it reproduces. Don't report against your big model.
3. **Isolate the trigger** — bisect (change one operator / flag / construct at a time). An
   affected-vs-unaffected table is the most valuable evidence in the report.
4. **Scope it** — which verbs reach the defect (the verb set depends on the defect *class*, not a fixed list).
5. **Provenance** — record `dmtool --version`.
6. **Trim the evidence** — the distinctive codes/frames, never a whole trace.
7. **Findings & improvements** — every place the tool's own signals **misled or slowed** you, each with a
   concrete fix aimed at one target: the **CLI**, its **material** (operator catalog / schemas / help /
   diagnostics), or the **skill**. Reflect on your *whole* trail, not just the calls around an error — a
   silently-empty result, a catalog example that didn't match, a missing affordance all count.
8. **Replay log** — the analyst's prompts, verbatim and in order.
9. **Self-audit** — did you change any model file **outside** dmtool (an editor / `sed` / `echo` / `cp`)?
   State it explicitly; a direct edit can masquerade as a defect and taints the repro.

Write the report to `/tmp/rk-probe-sandbox/work/REPORT.md`. Then stop.
