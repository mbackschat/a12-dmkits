# `probe/` — proactive, deep DX analysis of the shipped product

A **different instrument from [`eval/`](../eval/)**: the eval measures whether the *self-describing CLI surface* lets a cold agent author/read rules (bare-tool, scored, pass@k); the probe asks how a real agent uses the **shipped product** — the **native binary** + the **skill** — over a realistic **multi-prompt** session, to find DX defects *proactively*, before a bug report.

> **Method & rationale — why this exists, what it captures, how the analysis works: [`docs/PROBE-SPEC.md`](../docs/PROBE-SPEC.md).**
> This README is the operational how-to: layout + run steps. (`PROBE-SPEC` ↔ `probe/` mirrors the `EVAL-SPEC` ↔ `eval/` split.)

## Layout

```
probe/
  README.md          # this file — how to run
  prep-probe.sh      # isolated sandbox + the logging shim + the skill copy (run this first)
  agent-preamble.md  # single-sourced subagent framing (boundary, env, no-look-ahead) — only the task turns vary
  audit-probe.sh     # integrity gate — fails loud if the agent left its material boundary
  digest-probe.sh    # deterministic trajectory digest + auto RED-FLAG for an exception escaping a verb
  tasks/             # multi-prompt scenarios: the prompts + the gold + what to mine
  reports/           # dated deep session reports — the proactive findings record
```

The method doc is [`docs/PROBE-SPEC.md`](../docs/PROBE-SPEC.md); the scenario inventory is the markdown under `tasks/`; the findings are the dated reports under `reports/` (the `eval/RESULTS.md` analog).

## Task backlog

| task | covers | status |
|---|---|---|
| `01` person + home/work address + extract | groups · metadata · extract→include | **probed** (report 2026-06-19; found P1–P4, H1) |
| `01b` person DOB → date-derived age + date rules | the native **date operators** (the v0.1.0 crash surface) | **probed** (report 2026-06-19; clean — surface confirmed green) |
| `01-de` task 01 in **German prompting** | a German-prompted session must produce a `de_DE` locale + German names/descriptions/messages on its own (locale + condition-language stress) | **authored** (`tasks/01-de-person-address-age.md`); probe pending |
| `02` order date/pattern | **pattern** rules (`violates "[0-9]{5}"`) — date now covered by 01b | TODO |
| `03` subscription computation | **computations** (add/modify) — least-probed surface | **probed** (report 2026-06-20; correctness MET, efficient; 1 discoverability finding P03-1) |
| `04` multifile includes | includes + `-w` from scratch — partially covered by 01 turn 2 | TODO (lower) |

Standing low finding **F1** (from 01b): `operators` has no narrowing flag (an agent guessed `operators --filter`); a positional/substring filter would help. Low priority.

## The keystone, in one line

`prep-probe.sh` installs a **logging `dmtool` shim** on the sandbox PATH that records every invocation's command + stdout + stderr + exit, then runs the real native binary. The analysis reads that **objective** log + the **resulting model files**, so a silently-dropped edit surfaces regardless of what the agent believed. (Full rationale: PROBE-SPEC "The keystone".)

## Run

```sh
# 1. native binary must exist (build once; GraalVM 25 auto-discovered):
GRADLE_USER_HOME="$(cd .. && pwd)/.gradle-isolated" \
GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME

# 2. set up the isolated sandbox + logging shim
./probe/prep-probe.sh

# 3. (main loop) drive a general-purpose subagent through a task's prompts IN ORDER, as one session.
#    FIRST clear the main-loop TaskList — the subagent INHERITS it via system-reminders and a revealing task
#    name biases the probe (PROBE-SPEC, audit check 5). Build its prompt as: agent-preamble.md (single-sourced
#    framing) + the task's TURN blocks (verbatim). cwd = <sandbox>/work, PATH = <sandbox>/bin:$PATH (bare
#    `dmtool` → the shim), skill at <sandbox>/SKILL.md.

# 4. INTEGRITY GATE — confirm the agent stayed within its material boundary (skill + dmtool + own work files).
#    Pass the agentId the Agent tool reported (or the transcript path). STOP and discard if it says SUSPECT:
./probe/audit-probe.sh <agentId>

# 5. DETERMINISTIC DIGEST — the mechanical trajectory facts + auto RED-FLAGS (exits 1 on an escaped exception):
./probe/digest-probe.sh

# 6. (main loop, judgment) analyze vs the gold; record the audit verdict + the digest; investigate every
#    finding to its ROOT CAUSE; EMBED the produced model JSON (sandbox output) for objective review;
#    write probe/reports/<date>-<task>.md
```

The "runner" is the main assistant loop, not a script: setup + the deep analysis-and-report are judgment, the subagent is the agent-under-observation. The **anti-cheat audit** (`audit-probe.sh`) is deterministic and non-negotiable — a probe's findings are only trustworthy if the agent didn't read the repo's gold/source or hand-edit a model file; see [`docs/PROBE-SPEC.md`](../docs/PROBE-SPEC.md) "Material boundary & the anti-cheat audit". The analysis dimensions and the discover→fix→graduate-into-a-guard loop are in the same spec.
