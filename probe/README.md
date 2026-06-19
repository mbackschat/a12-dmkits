# `probe/` — proactive, deep DX analysis of the shipped product

A **different instrument from [`eval/`](../eval/)**. The eval measures whether the *self-describing CLI surface* lets a cold agent author/read rules (bare-tool, scored, pass@k). This probe asks a different question:

> When a real agent uses the **shipped product** — the **native binary** + the **skill** — over a realistic **multi-prompt** session, how does it actually go? Where does it get a **false success**, hit **friction**, or get **misled**?

It exists because the defects that kept biting us were invisible to the eval: they lived in the **native** image (a reflection crash the JVM eval never ran), in **multi-step** flows (the eval is single-shot), and in the **trajectory** the eval discards by scoring only the final artifact. The probe is **proactive**: find DX defects here, before a bug report.

## How it differs from the eval

| | `eval/` | `probe/` |
|---|---|---|
| Goal | CLI self-sufficiency (does the surface teach the agent?) | shipped-product DX (how does a real session go?) |
| Binary | freshly-built **JVM** CLI | the **native** image (`cli/build/native/nativeCompile/dmtool`) |
| Tasks | single-shot (one rule/field/edit) | **multi-prompt** single sessions (a BA iterating) |
| Repetition | **pass@k** | **no k** — one **deep** session per task |
| Output | a score (kernel-valid + gold-equivalent) | a **diagnostic report** — correctness-vs-gold + trajectory mining |

## The keystone: objective capture

The agent will not self-report a false success it didn't notice — so we don't ask it to. `prep-probe.sh` installs a **logging `dmtool` shim** on the sandbox PATH that records every invocation's command + stdout + stderr + exit, then runs the real native binary. The analysis reads that **objective** log + the **resulting model files** (+ the agent's narrative), so a silently-dropped edit shows up regardless of what the agent believed.

## Gold from existing models

Each task is reverse-engineered from a model that **already encodes a correct end-state** — a committed fixture under `cli/src/test/resources/models/`, or a domain-transferred workspace model (license-safe: own domain, no tax/gov). That source **is the answer key**, so the deep analysis has an **objective correctness anchor** ("did the agent build the right thing?") *under* the qualitative trajectory mining. Gold is matched **semantically** (read→re-render→compare), not literally — many valid expressions enforce the same intent.

## Layout

```
probe/
  README.md          # this file
  prep-probe.sh      # isolated sandbox + the logging shim + the skill copy (run this first)
  tasks/             # multi-prompt scenarios: the prompts + the gold + what to mine
  reports/           # dated deep session reports — the proactive findings record
```

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

The "runner" is the main assistant loop, not a script: setup + the deep analysis-and-report are judgment, the subagent is the agent-under-observation.

## The proactive loop

A probe **discovers** a defect class → fix it at root → **graduate the class into a deterministic guard** (e.g. an "applied ⇒ effected" round-trip test) so it can't regress. The probe finds unknowns; the guards enforce knowns.
