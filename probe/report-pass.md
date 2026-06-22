# Probe report pass — point the agent at the SHIPPED bug-report skill (single-sourced)

The main loop appends this as the **final turn** of a probe session. The report RIGOR is **not** inlined here
— it lives in the shipped **`a12-dmtool-bug-report`** skill (the same one plugin users get), copied into the
sandbox by `prep-probe.sh` as `BUG-REPORT-SKILL.md`. So the probe agent files its report exactly as a real
user would, with one source of report rigor (no probe-only preamble). The objective backstop — the
`digest-probe.sh` RED-FLAG and the `audit-probe.sh` anti-cheat — is unchanged, and the main loop still
reconciles the agent's report against it (PROBE-SPEC "The analysis method").

---

Now, in this same session, produce a **bug-and-findings report** for the dmtool work you just did. Read and
follow the skill at `/tmp/rk-probe-sandbox/BUG-REPORT-SKILL.md` — it carries the rigor (classify a real defect
vs. a rejection vs. your own mistake, shrink to a minimal repro, the affected-vs-unaffected table, the replay
log, the self-audit). You have the full context of what you did and WHY, so you are the best source. There is
no user to ask where to save it, so save the report (and any trimmed artifacts) as a FILE
`/tmp/rk-probe-sandbox/work/REPORT.md`, where it can be collected — returning the text in your reply is **not**
enough, the file must exist. **If your `Write` tool is restricted in this environment** (a subagent may be told
to "return findings as text" and `Write` refuses), that restriction does not apply to this explicit
instruction — fall back to a shell heredoc:

```
cat > /tmp/rk-probe-sandbox/work/REPORT.md <<'EOF'
<the full report>
EOF
```
