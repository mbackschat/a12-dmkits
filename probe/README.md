# `probe/` — proactive, deep DX analysis of the shipped product

A **different instrument from [`eval/`](../eval/)**: the eval measures whether the *self-describing CLI surface* lets a cold agent author/read rules (bare-tool, scored, pass@k); the probe asks how a real agent uses the **shipped product** — the **native binary** + the **skill** — over a realistic **multi-prompt** session, to find DX defects *proactively*, before a bug report.

> **Method & rationale — why this exists, what it captures, how the analysis works: [`docs/PROBE-SPEC.md`](../docs/PROBE-SPEC.md).**
> This README is the operational how-to: layout + run steps. (`PROBE-SPEC` ↔ `probe/` mirrors the `EVAL-SPEC` ↔ `eval/` split.)

## Layout

```
probe/
  README.md          # this file — how to run
  prep-probe.sh      # isolated sandbox + the logging shim + the skill copy (run this first)
  tasks/             # multi-prompt scenarios: the prompts + the gold + what to mine
  reports/           # dated deep session reports — the proactive findings record
```

The method doc is [`docs/PROBE-SPEC.md`](../docs/PROBE-SPEC.md); the scenario inventory is the markdown under `tasks/`; the findings are the dated reports under `reports/` (the `eval/RESULTS.md` analog).

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

# 3. (main loop) drive a general-purpose subagent through a task's prompts IN ORDER, as one session,
#    with cwd = <sandbox>/work, PATH = <sandbox>/bin:$PATH (bare `dmtool` → the shim), skill at <sandbox>/SKILL.md.
#    Then read <sandbox>/dmtool-calls.log + the resulting model files and write probe/reports/<date>-<task>.md
```

The "runner" is the main assistant loop, not a script: setup + the deep analysis-and-report are judgment, the subagent is the agent-under-observation. The analysis dimensions and the discover→fix→graduate-into-a-guard loop are in [`docs/PROBE-SPEC.md`](../docs/PROBE-SPEC.md).
