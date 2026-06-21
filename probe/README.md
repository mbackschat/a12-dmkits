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
| `01` person + home/work address + extract | groups · metadata · extract→include | **probed + fixed** (P1–P4 all resolved + regression-confirmed; the dated report was cleaned once superseded — fixes live in git + the specs) |
| `01b` person DOB → date-derived age + date rules | the native **date operators** (the v0.1.0 crash surface) | **probed** (clean — surface confirmed green; the dated report was cleaned once superseded) |
| `01-de` task 01 in **German prompting** | a German-prompted session must produce a `de_DE` locale + German names/descriptions/messages on its own (locale + condition-language stress) | **probed** (report 2026-06-20; drove findings 1–8 — condition-language, export `-m`, mount-edit, extract path-rebase, load-path stack trace; all fixed except G12/G13/G14 in the gaps ledger) |
| `02` order date/pattern | **pattern** rules (`violates "[0-9]{5}"`) — date now covered by 01b | TODO |
| `03` subscription computation | **computations** (add/modify) — least-probed surface | **probed** (report 2026-06-20; correctness MET, efficient; 1 discoverability finding P03-1, fixed) |
| `04` multifile includes | includes + `-w` from scratch — partially covered by 01 turn 2 | TODO (lower) |
| `05` full realistic authoring session (**clean**) | groups · metadata · `de_DE` locale · **roles annotation** · repeatability max+flag · extract — the broad surface `01` missed | **task added** — the maintainer session that surfaced the v0.6.0 annotation crash, promoted as a DISCOVERY task (its regression lives in native-battery, not here) |
| `06` evolve an EXISTING ruled model (**fixture**) | **rules over repeats** · **cross-row correlation / parallel indices** · **flag→rule promotion** (conditional requiredness with deps) · rename-rewrite — the maintain/refactor surface a clean start can't reach | **task added** |

**Start mode — clean vs fixture (the family extends along this axis).** A task declares `**Start:** clean` (empty `work/` → a from-scratch session) or `**Start:** fixture <repo-relative-path>` (a committed model/workspace seeded into `work/` → a maintain/refactor session). For a fixture task, pass the path to prep: `./probe/prep-probe.sh cli/src/test/resources/models/order-ruled.dm.json`. The seeded model is **given** material (read + edited freely); the task's **gold** stays out of the sandbox, so the anti-cheat audit is unaffected. Add a new sequence by dropping a task file with its `Start:` line + `## The session` turns — nothing else to wire.

Former standing finding **F1** (from 01b): an agent guessed `operators --filter` to narrow the catalog. **Resolved 2026-06-20 (G13):** `operators <substring>` now filters the list (matched over id/keyword/meaning), composing with `--kind`/`--keyword`; an exact id still gives full semantics.

## The keystone, in one line

`prep-probe.sh` installs a **logging `dmtool` shim** on the sandbox PATH that records every invocation's command + stdout + stderr + exit, then runs the real native binary. The analysis reads that **objective** log + the **resulting model files**, so a silently-dropped edit surfaces regardless of what the agent believed. (Full rationale: PROBE-SPEC "The keystone".)

## Run

```sh
# 1. native binary must exist (build once; GraalVM 25 auto-discovered):
GRADLE_USER_HOME="$(cd .. && pwd)/.gradle-isolated" \
GRAALVM_HOME=/Library/Java/JavaVirtualMachines/graalvm-25.jdk/Contents/Home \
JAVA_HOME="$(/usr/libexec/java_home -v 21)" ./gradlew :cli:nativeCompile -Porg.gradle.java.installations.fromEnv=GRAALVM_HOME

# 2. set up the isolated sandbox + logging shim — per the task's `Start:` line:
./probe/prep-probe.sh                                                   #   clean start (from-scratch)
./probe/prep-probe.sh cli/src/test/resources/models/order-ruled.dm.json #   fixture start (seed work/)

# 3. (main loop) drive a general-purpose subagent through a task's prompts IN ORDER, as one session.
#    FIRST clear the main-loop TaskList — the subagent INHERITS it via system-reminders and a revealing task
#    name biases the probe (PROBE-SPEC, audit check 5). Build its prompt as: agent-preamble.md (single-sourced
#    framing) + the task's TURN blocks (verbatim). cwd = <sandbox>/work, PATH = <sandbox>/bin:$PATH (bare
#    `dmtool` → the shim), skill at <sandbox>/SKILL.md. The subagent prompt = `agent-preamble.md` + the task
#    turns + `report-pass.md` as a FINAL turn (it points the agent at the SHIPPED a12-dmtool-bug-report skill,
#    copied into the sandbox) — so the agent writes its OWN bug-and-findings report
#    (`<sandbox>/work/REPORT.md`) from the full context it holds (what it did and WHY). The report narrative
#    is the AGENT's; the main loop verifies it (step 6), it does not author it.

# 4. INTEGRITY GATE — confirm the agent stayed within its material boundary (skill + dmtool + own work files).
#    Pass the agentId the Agent tool reported (or the transcript path). STOP and discard if it says SUSPECT:
./probe/audit-probe.sh <agentId>

# 5. DETERMINISTIC DIGEST — the mechanical trajectory facts + auto RED-FLAGS (an internal tool error:
#    an escaped exception OR a structured RK_INTERNAL_ERROR/outcome:error/exit 70 — exits 1 if any):
./probe/digest-probe.sh

# 6. (main loop, RECONCILE — do not re-author) read the agent's <sandbox>/work/REPORT.md (the narrative + the
#    WHY), cross-check it against the OBJECTIVE backstop (the audit verdict + the digest), and confirm/correct
#    each claimed finding with a minimal repro. The agent won't honestly report a false success it didn't
#    notice — that gap is exactly what the digest/shim-log catch. Commit probe/reports/<date>-<task>.md =
#    the agent's report + the objective backstop + the maintainer's repro verdicts.
```

The "runner" is the main assistant loop, not a script — but the **report narrative is the agent's, not the main loop's**: the agent holds the *why* (its own attempts/rejections/recoveries), so re-deriving the narrative post-hoc both loses that reasoning and duplicates context. The main loop's job is the **objective backstop** — the **anti-cheat audit** (`audit-probe.sh`, deterministic + non-negotiable: a probe's findings are only trustworthy if the agent didn't read the repo's gold/source or hand-edit a model) and the **digest** (catches the false success the agent didn't notice) — plus minimal-repro verification of each claimed finding. Division of labor + the discover→fix→graduate loop: [`docs/PROBE-SPEC.md`](../docs/PROBE-SPEC.md).
