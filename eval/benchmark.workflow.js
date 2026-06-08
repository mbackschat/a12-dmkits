// Layer-2 of the self-sufficiency benchmark (EVAL-SPEC §8). RUNNABLE, but RUN ON DEMAND — it is
// token-heavy (model × task × k clean agents + judges) and so is opt-in, never per-commit. The
// deterministic Layer-1 backbone (golds validate / comprehension rules read) is EvalGoldOracleTest.
//
// What it measures: given ONLY the shipped material (the self-describing CLI launcher — verbs, --help,
// `cli operators`; no raw catalog file, no skill — this is BARE-TOOL mode, EVAL-SPEC §2a), can a
// clean agent (a) AUTHOR a rule the kernel accepts AND that is semantically right, and (b) READ BACK
// foreign rules correctly? Kernel-accepts is necessary but NOT sufficient — the kernel accepts an
// inverted-polarity rule too, so a judge re-scores semantic correctness against the task gold/rubric.
//
// Prereq: build the launcher once — `./gradlew :cli:installDist` → cli/build/install/cli/bin/cli.
// Invoke:
//   Workflow({ scriptPath: "eval/benchmark.workflow.js",
//     args: { models: ["opus","sonnet"], k: 3, tasks: [ /* contents of eval/tasks/*.json */ ] } })
// (The script has no filesystem access, so the caller reads eval/tasks/*.json and passes them in.)

export const meta = {
  name: 'rulekit-self-sufficiency-benchmark',
  description: 'N6 self-sufficiency benchmark — clean agents author/read rules from only the shipped material; pass@k per model',
  phases: [
    { title: 'Produce', detail: 'clean agent per (model,task,run) — author or explain' },
    { title: 'Recheck', detail: 'one warm-JVM `cli batch` re-validates every authored candidate' },
    { title: 'Judge', detail: 'semantic correctness vs the gold / rubric' },
  ],
}

// Defensive: args may arrive as an object or (per the Workflow docs' caveat) as a JSON string —
// handle both so the run can never silently no-op on an empty TASKS again.
const A = (typeof args === 'string') ? JSON.parse(args) : (args || {})
const MODELS = A.models || ['opus']
const K = A.k || 3
const TASKS = A.tasks || []
log(`benchmark inputs: ${MODELS.length} model(s) [${MODELS}], k=${K}, ${TASKS.length} task(s)`)
if (TASKS.length === 0) throw new Error('no tasks passed in args.tasks — nothing to benchmark')
const CLI = 'JAVA_HOME="$(/usr/libexec/java_home -v 21)" cli/build/install/cli/bin/cli'
// A task names its model by stem (cli/src/test/resources/models/<model>.dm.json) OR carries an explicit
// `modelPath` (repo-relative) for nested multi-file layouts. `includeDir` (repo-relative) is threaded to
// every verb as `--include-dir` so the recheck/judge can resolve a multi-file model's includes/typedefs.
const MODEL_FILE = t => t.modelPath || `cli/src/test/resources/models/${t.model}.dm.json`
const INCLUDE_ARGS = t => t.includeDir ? ['--include-dir', t.includeDir] : []
const INCLUDE_HINT = t => t.includeDir ? ` --include-dir ${t.includeDir}` : ''

// Run-mode (EVAL-SPEC §2a). Bare-tool is the DEFAULT instrument (and the only one CI/historical runs use).
// PRODUCTION mode is opt-in and gives every PRODUCER agent the N1 skill, two ways:
//   • args.skillPath (PREFERRED) — a repo-relative path; the produce prompt tells the agent to READ that file
//     itself. The agent already runs in the repo with shell access, so the VERBATIM skill needs no inline
//     transcription by the main loop (the script has no filesystem access) and can't drift from the committed
//     file. The prompt names that file as the ONLY extra material the agent may read.
//   • args.skill — the skill's CONTENTS inlined (the main loop pastes them). Kept for callers that prefer
//     inlining; for a definitive run prefer skillPath so "verbatim" is trustworthy.
// Either way the skill goes to PRODUCERS only; judges stay skill-free (they score against the gold). After a
// production run, AUDIT the producer transcripts to confirm the agents read ONLY the skill — nothing else
// (the material boundary IS the measurement): see EVAL-SPEC §2a / the run-rulekit-eval skill for the grep.
// Label every result as production — it is NOT comparable to the bare-tool trend line.
const SKILL = (typeof A.skill === 'string' && A.skill.trim()) ? A.skill.trim() : null
const SKILL_PATH = (typeof A.skillPath === 'string' && A.skillPath.trim()) ? A.skillPath.trim() : null
log(`run mode: ${(SKILL || SKILL_PATH)
  ? `PRODUCTION (N1 skill ${SKILL_PATH ? 'read by the agent from ' + SKILL_PATH : 'inlined'})`
  : 'bare-tool (no skill)'}`)
function skillBlock() {
  if (SKILL_PATH) {
    return `
  - the A12 authoring SKILL (expert-distilled judgment — polarity, the traps, iteration): READ IT IN FULL from
    the file \`${SKILL_PATH}\` before composing, and APPLY it. That file is the ONLY extra thing you may read —
    do NOT read any other file (no docs, source, tests, other *.md, or test/eval fixtures).`
  }
  if (SKILL) {
    return `
  - the A12 authoring SKILL below — expert-distilled judgment (polarity, the traps, iteration). Treat it as
    trusted background and APPLY it; this is the only extra material you get — do not seek other docs.
<<<SKILL
${SKILL}
SKILL>>>`
  }
  return ''
}

const AUTHORING = TASKS.filter(t => t.family === 'authoring')
const AUTHORING_COMP = TASKS.filter(t => t.family === 'authoring-computation')
const MODIFY_COMP = TASKS.filter(t => t.family === 'modify-computation')
const MODIFY_RULE = TASKS.filter(t => t.family === 'modify-rule')
const COMPREHENSION = TASKS.filter(t => t.family === 'comprehension')

// ---- prompts (EVAL-SPEC §5: neutral, material-only, no leaking the technique/polarity) ------------

function authorPrompt(task) {
  // For a multi-file model, the boundary permits resolving the models it references (neutral — it does
  // NOT name the flag; the agent discovers `--include-dir` from the CLI error + `<verb> --help`).
  const multifile = task.includeDir
    ? `\n  - this model is part of a multi-file workspace: it REFERENCES other model files located elsewhere in the workspace, which you may read/resolve as needed to validate it.`
    : ''
  return `You help a business analyst author an A12 validation rule. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (it is self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <verb> --help\` shows a verb's parameters, \`${CLI} operators\` lists the DSL operators and \`${CLI} operators <id>\` gives one in full)
  - the model file: ${MODEL_FILE(task)}${multifile}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement to enforce: "${task.intent}".
Author the rule and CONFIRM the kernel accepts it via the CLI. Return ONLY JSON — and note that "field" is
the error field's path INSIDE the model (e.g. /Group/Field), NOT the model FILE path you pass on the command line:
  {"field":"<the error field's path inside the model>","condition":"<DSL>","code":"<ID>","kernelAccepted":true|false}`
}

function modifyComputationPrompt(task) {
  return `You help a business analyst CHANGE an EXISTING A12 COMPUTATION on a model. A computation COMPUTES
(fills in) a field's value from other fields. The model ALREADY has a computation; re-express it to meet a
new requirement, KEEPING THE SAME computed field. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <verb> --help\` a verb's parameters; \`${CLI} explain ${MODEL_FILE(task)} --computation ${task.computation}\` shows the existing computation, \`${CLI} schema add\` the computation-spec shape, \`${CLI} modify --help\` (its \`--dry-run\` PREVIEWS a re-expression read-only), \`${CLI} operators\` the DSL operators)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). The existing computation to change: ${task.computation}.
New requirement: "${task.intent}".
Re-express the computation and CONFIRM the kernel accepts the CHANGE by PREVIEWING it (read-only) — do NOT write the model file. Return ONLY JSON:
  {"computedField":"<the computed field's path inside the model, e.g. /Group/Field — NOT the model FILE path>","commonPrecondition":"<DSL or omit>","alternatives":[{"precondition":"<DSL or omit>","operation":"<DSL value expression>"}],"messages":[{"locale":"<code>","text":"..."}],"kernelAccepted":true|false}`
}

function modifyRulePrompt(task) {
  return `You help a business analyst CHANGE an EXISTING A12 validation RULE on a model. A validation rule's
condition is TRUE exactly when a document is INVALID (it describes the VIOLATION, not the requirement). The
model ALREADY has a rule; re-express its CONDITION to meet a new requirement, KEEPING THE SAME rule (same
error field). Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <verb> --help\` a verb's parameters; \`${CLI} explain ${MODEL_FILE(task)} --rule ${task.rule}\` shows the existing rule, \`${CLI} modify --help\` (its \`--dry-run\` PREVIEWS a re-expression read-only), \`${CLI} operators\` the DSL operators)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). The existing rule to change: ${task.rule}.
New requirement: "${task.intent}".
Re-express the rule's CONDITION (true on a VIOLATION) and CONFIRM the kernel accepts the CHANGE by PREVIEWING it
(read-only, e.g. \`${CLI} modify ${MODEL_FILE(task)} --rule ${task.rule} --condition "<DSL>" --dry-run\`) — do NOT
write the model file. Return ONLY JSON ("condition" = the new DSL condition that is true on a violation):
  {"condition":"<DSL>","kernelAccepted":true|false}`
}

function authorComputationPrompt(task) {
  return `You help a business analyst author an A12 COMPUTATION — a rule that COMPUTES (fills in) a field's value
from other fields, rather than validating one. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (it is self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <verb> --help\` shows a verb's parameters, \`${CLI} schema add\` shows the input shapes, \`${CLI} operators\` lists the DSL operators and \`${CLI} operators <id>\` gives one in full)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement to implement: "${task.intent}".
Author the computation and CONFIRM the kernel accepts it via the CLI. Return ONLY JSON:
  {"computedField":"<the computed field's path inside the model, e.g. /Group/Field — NOT the model FILE path>","commonPrecondition":"<DSL or omit>","alternatives":[{"precondition":"<DSL or omit>","operation":"<DSL value expression>"}],"messages":[{"locale":"<code>","text":"..."}],"kernelAccepted":true|false}`
}

function explainPrompt(task) {
  return `You help a business analyst understand the validation rules already on an A12 model. Assume NO prior
A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (it is self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <verb> --help\` shows a verb's parameters, \`${CLI} operators\` lists the DSL operators and \`${CLI} operators <id>\` gives one in full)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). For each of these rules: ${JSON.stringify(task.rules)}
explain exactly what makes a document INVALID (the violation the rule fires on) and which field it guards.
Return ONLY JSON: {"explanations": { "<ruleName>": "<your explanation>", ... }}`
}

const AUTHOR_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { field: { type: 'string' }, condition: { type: 'string' },
    code: { type: 'string' }, kernelAccepted: { type: 'boolean' } },
  required: ['field', 'condition'],
}
const MODIFY_RULE_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { condition: { type: 'string' }, kernelAccepted: { type: 'boolean' } },
  required: ['condition'],
}
const AUTHOR_COMP_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: {
    computedField: { type: 'string' },
    commonPrecondition: { type: 'string' },
    alternatives: { type: 'array', items: {
      type: 'object', additionalProperties: true,
      properties: { precondition: { type: 'string' }, operation: { type: 'string' } },
      required: ['operation'] } },
    messages: { type: 'array', items: {
      type: 'object', additionalProperties: true,
      properties: { locale: { type: 'string' }, text: { type: 'string' } } } },
    kernelAccepted: { type: 'boolean' },
  },
  required: ['computedField', 'alternatives'],
}
const EXPLAIN_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { explanations: { type: 'object', additionalProperties: true } },
  required: ['explanations'],
}

// ---- Produce: one clean agent per (model, task, run); model pinned for reproducibility ------------

phase('Produce')
const authorRuns = []
for (const model of MODELS)
  for (const task of AUTHORING)
    for (let i = 0; i < K; i++) authorRuns.push({ model, task, i })

const explainRuns = []
for (const model of MODELS)
  for (const task of COMPREHENSION)
    for (let i = 0; i < K; i++) explainRuns.push({ model, task, i })

const authored = await parallel(authorRuns.map(r => () =>
  agent(authorPrompt(r.task), { label: `author ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: AUTHOR_SCHEMA })
    .then(out => ({ kind: 'authoring', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'authoring', model: r.model, task: r.task, i: r.i, out: null }))))

const explained = await parallel(explainRuns.map(r => () =>
  agent(explainPrompt(r.task), { label: `explain ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: EXPLAIN_SCHEMA })
    .then(out => ({ kind: 'comprehension', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'comprehension', model: r.model, task: r.task, i: r.i, out: null }))))

const compRuns = []
for (const model of MODELS)
  for (const task of AUTHORING_COMP)
    for (let i = 0; i < K; i++) compRuns.push({ model, task, i })

const authoredComp = await parallel(compRuns.map(r => () =>
  agent(authorComputationPrompt(r.task), { label: `comp ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: AUTHOR_COMP_SCHEMA })
    .then(out => ({ kind: 'authoring-computation', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'authoring-computation', model: r.model, task: r.task, i: r.i, out: null }))))

const modRuns = []
for (const model of MODELS)
  for (const task of MODIFY_COMP)
    for (let i = 0; i < K; i++) modRuns.push({ model, task, i })

const authoredMod = await parallel(modRuns.map(r => () =>
  agent(modifyComputationPrompt(r.task), { label: `modify ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: AUTHOR_COMP_SCHEMA })
    .then(out => ({ kind: 'modify-computation', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'modify-computation', model: r.model, task: r.task, i: r.i, out: null }))))

const modRuleRuns = []
for (const model of MODELS)
  for (const task of MODIFY_RULE)
    for (let i = 0; i < K; i++) modRuleRuns.push({ model, task, i })

const authoredModRule = await parallel(modRuleRuns.map(r => () =>
  agent(modifyRulePrompt(r.task), { label: `modrule ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: MODIFY_RULE_SCHEMA })
    .then(out => ({ kind: 'modify-rule', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'modify-rule', model: r.model, task: r.task, i: r.i, out: null }))))

// ---- Recheck: ONE warm-JVM `cli batch` authoritatively re-validates every authored candidate -------
// (Don't trust the agents' self-reported kernelAccepted.) This is the batch verb's reason to exist:
// N checks in one process, paying the kernel's StringTemplate warm-up once.

const candidates = authored.filter(r => r && r.out && r.out.condition)
let authoritative = {}   // "model/taskId/i" -> bool valid
if (candidates.length) {
  const ops = candidates.map(r => ({
    id: `${r.model}/${r.task.id}/${r.i}`,
    verb: 'check',
    args: [MODEL_FILE(r.task), '--field', r.out.field, '--condition', r.out.condition, '--code', r.out.code || 'EVAL',
      ...INCLUDE_ARGS(r.task)],
  }))
  const selfReport = () => {
    const m = {}
    for (const r of candidates) m[`${r.model}/${r.task.id}/${r.i}`] = !!(r.out && r.out.kernelAccepted)
    return m
  }
  // The authoritative re-check is best-effort: if the rescore agent fails (e.g. doesn't emit the
  // structured verdict), fall back to the agents' own kernelAccepted self-reports rather than
  // aborting the whole run. (A schema list is easier to emit reliably than a dynamic-keyed map.)
  try {
    const rescore = await agent(
      `Re-validate candidate rules against the real kernel in ONE process using the batch verb.
Steps: (1) write this JSON array verbatim to /tmp/rk-eval-ops.json:
${JSON.stringify(ops)}
(2) run:  ${CLI} batch /tmp/rk-eval-ops.json
(3) the output is a JSON array; each element echoes its "id" and carries result.valid (the kernel verdict).
Report every op via the structured-output tool: one {id, valid} per op.`,
      { label: 'batch recheck', phase: 'Recheck',
        schema: { type: 'object', additionalProperties: false, required: ['verdicts'],
          properties: { verdicts: { type: 'array', items: {
            type: 'object', additionalProperties: false, required: ['id', 'valid'],
            properties: { id: { type: 'string' }, valid: { type: 'boolean' } } } } } } })
    for (const v of (rescore && rescore.verdicts) || []) authoritative[v.id] = v.valid
    if (Object.keys(authoritative).length === 0) authoritative = selfReport()
  } catch (e) {
    log('batch recheck failed (' + e.message + ') — falling back to agent self-report')
    authoritative = selfReport()
  }
}

// ---- Recheck (computations): authoritatively persist each via `cli add` (which validates first) ------
// A computation isn't a rule, so it can't ride the `check` batch above; `add` is the kernel-authoritative
// gate (it rejects with {persisted:false} + exit 1 before writing). Few comp candidates → one agent does
// all the adds; falls back to the agents' self-reported kernelAccepted if it can't emit the verdicts.

const compSpec = (out) => {
  const s = { computedField: out.computedField,
    alternatives: (out.alternatives || []).map(a => a.precondition
      ? { precondition: a.precondition, operation: a.operation } : { operation: a.operation }) }
  if (out.commonPrecondition) s.commonPrecondition = out.commonPrecondition
  if (out.messages) s.messages = out.messages
  return s
}
const compCandidates = authoredComp.filter(r => r && r.out && r.out.computedField && (r.out.alternatives || []).length)
let compAuthoritative = {}
if (compCandidates.length) {
  const entries = compCandidates.map(r => {
    const id = `${r.model}/${r.task.id}/${r.i}`
    return { id, file: `/tmp/rk-comp-${id.replace(/[^a-zA-Z0-9]/g, '_')}.json`,
      model: MODEL_FILE(r.task), spec: compSpec(r.out) }
  })
  const compSelfReport = () => {
    const m = {}
    for (const r of compCandidates) m[`${r.model}/${r.task.id}/${r.i}`] = !!(r.out && r.out.kernelAccepted)
    return m
  }
  try {
    const rescore = await agent(
      `Authoritatively re-validate candidate A12 COMPUTATIONS against the real kernel. For EACH entry below:
write its \`spec\` (verbatim JSON) to its \`file\`, then run:  ${CLI} add <entry.model> <entry.file>
The kernel validates before writing: on ACCEPT it prints the new model JSON and exits 0; on REJECT it
prints {"persisted":false,...} and exits non-zero. Entries:
${JSON.stringify(entries)}
Report every entry via the structured-output tool: one {id, valid} per entry (valid = accepted/persisted).`,
      { label: 'comp recheck', phase: 'Recheck',
        schema: { type: 'object', additionalProperties: false, required: ['verdicts'],
          properties: { verdicts: { type: 'array', items: {
            type: 'object', additionalProperties: false, required: ['id', 'valid'],
            properties: { id: { type: 'string' }, valid: { type: 'boolean' } } } } } } })
    for (const v of (rescore && rescore.verdicts) || []) compAuthoritative[v.id] = v.valid
    if (Object.keys(compAuthoritative).length === 0) compAuthoritative = compSelfReport()
  } catch (e) {
    log('comp recheck failed (' + e.message + ') — falling back to agent self-report')
    compAuthoritative = compSelfReport()
  }
}

// ---- Recheck (modify-computation): authoritatively re-express each via `cli modify … --dry-run` -------
// A MODIFY re-expresses an EXISTING computation in place. `modify --computation <name> --spec <file>
// --dry-run` is the kernel-authoritative gate run READ-ONLY: it `replaceComputation`s, re-binds (rejecting
// an invalid re-expression with {valid:false} + exit 1), emits the before/after with `changed`, and writes
// NOTHING (so the committed fixture is never mutated by the ~N parallel agents). A modify "passes" recheck
// only when the kernel ACCEPTS it AND it is an actual change (changed:true) — a no-op isn't a modification.
// (We drive the `modify` verb under test, read-only via --dry-run.)
// Falls back to the agents' self-report if the rescore agent fails.

const modCandidates = authoredMod.filter(r => r && r.out && r.out.computedField && (r.out.alternatives || []).length)
let modAuthoritative = {}
if (modCandidates.length) {
  const entries = modCandidates.map(r => {
    const id = `${r.model}/${r.task.id}/${r.i}`
    return { id, file: `/tmp/rk-mod-${id.replace(/[^a-zA-Z0-9]/g, '_')}.json`,
      model: MODEL_FILE(r.task), computation: r.task.computation, spec: compSpec(r.out) }
  })
  const modSelfReport = () => {
    const m = {}
    for (const r of modCandidates) m[`${r.model}/${r.task.id}/${r.i}`] = !!(r.out && r.out.kernelAccepted)
    return m
  }
  try {
    const rescore = await agent(
      `Authoritatively re-validate candidate A12 COMPUTATION MODIFICATIONS against the real kernel. For EACH
entry below: write its \`spec\` (verbatim JSON) to its \`file\`, then run (READ-ONLY — \`--dry-run\` writes nothing):
  ${CLI} modify <entry.model> --computation <entry.computation> --spec <entry.file> --dry-run
This re-expresses the named existing computation. On ACCEPT it prints a diff JSON {"before","after","changed",…}
and exits 0; on REJECT it prints {"valid":false,…} and exits non-zero. Entries:
${JSON.stringify(entries)}
Report every entry via the structured-output tool: one {id, valid, changed} per entry — valid = the kernel
ACCEPTED the re-expression (exit 0, no "valid":false), changed = the diff's "changed" field (true if it
actually differs from the current computation).`,
      { label: 'modify recheck', phase: 'Recheck',
        schema: { type: 'object', additionalProperties: false, required: ['verdicts'],
          properties: { verdicts: { type: 'array', items: {
            type: 'object', additionalProperties: false, required: ['id', 'valid', 'changed'],
            properties: { id: { type: 'string' }, valid: { type: 'boolean' }, changed: { type: 'boolean' } } } } } } })
    // A modify passes the kernel gate only if accepted AND an actual change.
    for (const v of (rescore && rescore.verdicts) || []) modAuthoritative[v.id] = v.valid && v.changed
    if (Object.keys(modAuthoritative).length === 0) modAuthoritative = modSelfReport()
  } catch (e) {
    log('modify recheck failed (' + e.message + ') — falling back to agent self-report')
    modAuthoritative = modSelfReport()
  }
}

// ---- Recheck (modify-rule): authoritatively re-express each RULE via `cli modify --rule … --dry-run` ---
// The rule-side counterpart of the modify-computation recheck. We BATCH the `modify … --dry-run` ops
// (JSON args, so the DSL condition needs no shell-quoting) — READ-ONLY, so the committed fixture is never
// mutated by the parallel agents. A modify passes the kernel gate only when ACCEPTED (exit 0, no
// "valid":false) AND an actual change (changed:true). Falls back to the agents' self-report on failure.

const modRuleCandidates = authoredModRule.filter(r => r && r.out && r.out.condition)
let modRuleAuthoritative = {}
if (modRuleCandidates.length) {
  const ops = modRuleCandidates.map(r => ({
    id: `${r.model}/${r.task.id}/${r.i}`,
    verb: 'modify',
    args: [MODEL_FILE(r.task), '--rule', r.task.rule, '--condition', r.out.condition, '--dry-run', ...INCLUDE_ARGS(r.task)],
  }))
  const modRuleSelfReport = () => {
    const m = {}
    for (const r of modRuleCandidates) m[`${r.model}/${r.task.id}/${r.i}`] = !!(r.out && r.out.kernelAccepted)
    return m
  }
  try {
    const rescore = await agent(
      `Re-validate candidate A12 RULE re-expressions against the real kernel in ONE process using the batch verb.
Each op runs \`modify <model> --rule <name> --condition <DSL> --dry-run\` (READ-ONLY — writes nothing): on ACCEPT
it returns a diff {"before","after","changed",…} with exit 0; on REJECT it returns {"valid":false,…} with a
non-zero exit. Steps: (1) write this JSON array verbatim to /tmp/rk-modrule-ops.json:
${JSON.stringify(ops)}
(2) run:  ${CLI} batch /tmp/rk-modrule-ops.json
(3) the output is a JSON array; each element echoes its "id" and carries "exit" and (on accept) result.changed.
Report every op via the structured-output tool: one {id, valid, changed} per op — valid = (exit is 0 AND the
result does NOT have "valid":false), changed = (result.changed === true).`,
      { label: 'modify-rule recheck', phase: 'Recheck',
        schema: { type: 'object', additionalProperties: false, required: ['verdicts'],
          properties: { verdicts: { type: 'array', items: {
            type: 'object', additionalProperties: false, required: ['id', 'valid', 'changed'],
            properties: { id: { type: 'string' }, valid: { type: 'boolean' }, changed: { type: 'boolean' } } } } } } })
    for (const v of (rescore && rescore.verdicts) || []) modRuleAuthoritative[v.id] = v.valid && v.changed
    if (Object.keys(modRuleAuthoritative).length === 0) modRuleAuthoritative = modRuleSelfReport()
  } catch (e) {
    log('modify-rule recheck failed (' + e.message + ') — falling back to agent self-report')
    modRuleAuthoritative = modRuleSelfReport()
  }
}

// ---- Judge: semantic correctness (catches kernel-valid-but-wrong, e.g. inverted polarity) ----------

phase('Judge')
const authorJudged = await parallel(candidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const valid = authoritative[key] === true
  if (!valid) return Promise.resolve({ key, model: r.model, taskId: r.task.id, valid: false, equivalent: false })
  return agent(
    `Judge whether a candidate A12 rule is SEMANTICALLY EQUIVALENT to the reference (gold): do they fire on
exactly the same documents? Polarity matters — a rule that fires on the opposite case is NOT equivalent.
Judge WITHIN the field's ACTUAL value domain: two rules that differ only on values the field cannot hold
ARE equivalent. The model is ${MODEL_FILE(r.task)} — first run \`${CLI} describe ${MODEL_FILE(r.task)}${INCLUDE_HINT(r.task)}\` to
see field types and enum value sets (e.g. an enum with only three possible values: "X not in {A,B}" then
equals "X == C"). PATH RESOLUTION: a bare field name resolves against the rule's group AND its ancestor
groups (the kernel walks UP the hierarchy), so a bare ref and an absolute ref denote the SAME field when
no nearer match exists — e.g. bare [Tier] from a rule scoped to /Subscription/Addons is the same field as
[/Subscription/Tier]. A difference that is only path NOTATION for the same resolved field is NOT a semantic
difference. For pattern/regex operands, equivalence is about capturing the SAME intended format —
treat trivially different regexes (anchors, equivalent character classes) that accept the same strings as
equivalent; do not require byte-identical regex text.
KERNEL FACT (empty operands): how an EMPTY field behaves in a comparison depends on its TYPE. A NUMBER empties
to 0 (so \`[N] < k\` FIRES on an empty N — a \`FieldFilled(N)\` guard there CHANGES the fire set, NOT redundant);
a confirm empties to False; but for STRING / DATE / BOOLEAN / ENUM the comparison is simply NOT EVALUATED on an
empty operand (it cannot fire). Therefore a presence guard (\`FieldFilled\`/\`AllFieldsFilled\`) over the very
field(s) a STRING/DATE/ENUM/BOOLEAN comparison ALREADY references is REDUNDANT — it does not change which
documents fire — so a candidate that ADDS (or drops) such a guard over the gold's own compared field IS
equivalent to the gold. This redundancy does NOT extend to (a) NUMBER comparisons, nor (b) date/aggregate
FUNCTIONS like \`DifferenceInDays\`/\`YearFromDate\` on an ABSENT date, which are formal errors and so genuinely
REQUIRE the presence guard — keep flagging a real difference there.
VERIFY BY EXECUTION — the kernel is the arbiter; PREFER this over reasoning whenever equivalence hinges on a
kernel semantic (empty operands, polarity, path resolution). You have the CLI: write 2-4 small documents to
files (JSON \`{"fields":{…}}\` — a repeatable group is an array of row objects, a field its text value, \`null\`
= present-but-empty; see \`${CLI} schema eval-doc\`) that exercise the fields BOTH conditions reference at their
edges (empty vs filled, a boundary value, each enum value). For each doc run, for the GOLD then the CANDIDATE,
\`${CLI} eval-doc ${MODEL_FILE(r.task)}${INCLUDE_HINT(r.task)} --condition '<that condition>' --field <that field> --doc <doc>\`
and read \`rule.fired\`. They are EQUIVALENT iff \`rule.fired\` matches on EVERY document; a document where they
differ is the witness that they are NOT. This runs the real kernel, so you needn't recall the empty/polarity rules.
Reference gold: field="${r.task.gold.field}", condition=${JSON.stringify(r.task.gold.condition)}.
Candidate: field="${r.out.field}", condition=${JSON.stringify(r.out.condition)}.
Return ONLY JSON: {"equivalent": true|false, "why": "<one sentence>"}`,
    { label: `judge ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, properties: { equivalent: { type: 'boolean' }, why: { type: 'string' } }, required: ['equivalent'] } })
    .then(v => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: !!(v && v.equivalent), why: v && v.why }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: false }))
}))

const explainJudged = await parallel(explained.filter(r => r && r.out).map(r => () =>
  agent(
    `Judge whether an analyst's explanations of A12 rules are CORRECT against the reference rubric. Each rule
must get the VIOLATION (when it fires) and the guard right; getting the polarity backwards is wrong.
Rubric (reference): ${JSON.stringify(r.task.expected)}.
Analyst's explanations: ${JSON.stringify(r.out.explanations)}.
Return ONLY JSON: {"correct": true|false, "why": "<one sentence>"}`,
    { label: `judge ${r.model}:${r.task.id}#${r.i}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, properties: { correct: { type: 'boolean' }, why: { type: 'string' } }, required: ['correct'] } })
    .then(v => ({ key: `${r.model}/${r.task.id}/${r.i}`, model: r.model, taskId: r.task.id, correct: !!(v && v.correct), why: v && v.why }))
    .catch(() => ({ key: `${r.model}/${r.task.id}/${r.i}`, model: r.model, taskId: r.task.id, correct: false }))))

const compJudged = await parallel(compCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const valid = compAuthoritative[key] === true
  if (!valid) return Promise.resolve({ key, model: r.model, taskId: r.task.id, valid: false, equivalent: false })
  return agent(
    `Judge whether a candidate A12 COMPUTATION is SEMANTICALLY EQUIVALENT to the reference (gold): do they
assign the SAME value to the SAME computed field under the SAME conditions? A computation is a computed
field + an optional common precondition + one or more (precondition, operation) alternatives. Two are
equivalent iff, for every document, they compute the same value (or both compute nothing). The model is
${MODEL_FILE(r.task)} — run \`${CLI} describe ${MODEL_FILE(r.task)}\` to see field types. A difference that
is only path notation, conjunct order, or an equivalent value expression is NOT a semantic difference.
KERNEL FACT (empty operands) — TYPE-DEPENDENT, get this right: an empty operand's effect on a computation
depends on the operand's TYPE. For a DATE (or other not-evaluated type) the operation computes NO value and
clears any stale value, so a precondition guarding the PRESENCE of such operands is REDUNDANT — a candidate
ADDING it over the gold's own date operands IS equivalent to the unguarded gold (and vice versa). But an
empty NUMBER operand is substituted with 0 (the operation IS evaluated, yielding 0), so a presence guard over
a NUMBER operand is NOT redundant: the guarded form computes nothing (cleared) while the unguarded computes 0
— a real difference, NOT equivalent. (String CONCATENATION also evaluates: empties concat as "".)
Reference gold: ${JSON.stringify(r.task.gold)}.
Candidate: ${JSON.stringify({ computedField: r.out.computedField, commonPrecondition: r.out.commonPrecondition, alternatives: r.out.alternatives })}.
Return ONLY JSON: {"equivalent": true|false, "why": "<one sentence>"}`,
    { label: `judge ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, properties: { equivalent: { type: 'boolean' }, why: { type: 'string' } }, required: ['equivalent'] } })
    .then(v => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: !!(v && v.equivalent), why: v && v.why }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: false }))
}))

const modJudged = await parallel(modCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const valid = modAuthoritative[key] === true   // accepted by the kernel AND an actual change
  if (!valid) return Promise.resolve({ key, model: r.model, taskId: r.task.id, valid: false, equivalent: false })
  return agent(
    `Judge whether a candidate A12 computation MODIFICATION matches the reference (gold) modification: does
the re-expressed computation assign the SAME value to the SAME computed field under the SAME conditions as
the gold? A computation is a computed field + an optional common precondition + one or more (precondition,
operation) alternatives. The candidate has already been confirmed (by the kernel) to be a CHANGE from the
original computation — judge only whether it is the RIGHT change (equivalent to the gold). Two are
equivalent iff, for every document, they compute the same value (or both compute nothing). The model is
${MODEL_FILE(r.task)} — run \`${CLI} describe ${MODEL_FILE(r.task)}\` to see field types. A difference that
is only path notation, conjunct order, or an equivalent value expression is NOT a semantic difference.
KERNEL FACT (empty operands) — TYPE-DEPENDENT: an empty DATE operand makes the operation compute NO value
(clears), so a presence guard over date operands is REDUNDANT (guarded == unguarded). But an empty NUMBER
operand is substituted with 0 (operation evaluated → 0), so a presence guard over a NUMBER operand is NOT
redundant — guarded clears while unguarded computes 0 (a real difference). (String CONCATENATION also
evaluates: empties concat "".)
Reference gold (the intended modified computation): ${JSON.stringify(r.task.gold)}.
Candidate: ${JSON.stringify({ computedField: r.out.computedField, commonPrecondition: r.out.commonPrecondition, alternatives: r.out.alternatives })}.
Return ONLY JSON: {"equivalent": true|false, "why": "<one sentence>"}`,
    { label: `judge ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, properties: { equivalent: { type: 'boolean' }, why: { type: 'string' } }, required: ['equivalent'] } })
    .then(v => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: !!(v && v.equivalent), why: v && v.why }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: false }))
}))

const modRuleJudged = await parallel(modRuleCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const valid = modRuleAuthoritative[key] === true   // accepted by the kernel AND an actual change
  if (!valid) return Promise.resolve({ key, model: r.model, taskId: r.task.id, valid: false, equivalent: false })
  return agent(
    `Judge whether a candidate A12 RULE CONDITION re-expression matches the reference (gold) modification: do the
two conditions fire on exactly the same documents (mark the same documents INVALID)? A rule's condition is TRUE
on a VIOLATION — polarity matters; a condition that fires on the OPPOSITE case is NOT equivalent. The candidate
has already been confirmed by the kernel to be a CHANGE from the original rule — judge only whether it is the
RIGHT change (semantically equivalent to the gold). The model is ${MODEL_FILE(r.task)} — run \`${CLI} describe
${MODEL_FILE(r.task)}\` to see field types and value domains. Judge WITHIN the field's ACTUAL value domain; a
difference that is only path notation or an equivalent value expression is NOT a semantic difference.
VERIFY BY EXECUTION — the kernel is the arbiter; PREFER it over reasoning when equivalence hinges on a kernel
semantic (empty operands, polarity). Write 2-4 small documents to files (\`{"fields":{…}}\`; see \`${CLI} schema
eval-doc\`) exercising the referenced fields at their edges; for each, run for the GOLD then the CANDIDATE
\`${CLI} eval-doc ${MODEL_FILE(r.task)}${INCLUDE_HINT(r.task)} --condition '<that condition>' --field ${r.task.gold.field} --doc <doc>\`
and compare \`rule.fired\` — EQUIVALENT iff it matches on every document.
Reference gold (the intended modified rule): error field "${r.task.gold.field}", condition=${JSON.stringify(r.task.gold.condition)}.
Candidate condition: ${JSON.stringify(r.out.condition)}.
Return ONLY JSON: {"equivalent": true|false, "why": "<one sentence>"}`,
    { label: `judge ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, properties: { equivalent: { type: 'boolean' }, why: { type: 'string' } }, required: ['equivalent'] } })
    .then(v => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: !!(v && v.equivalent), why: v && v.why }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: false }))
}))

// ---- Aggregate pass@k per (model, task). pass = kernel-valid AND semantically right ----------------

const agg = {}
function bump(model, taskId, passed) {
  const k = `${model}/${taskId}`
  agg[k] = agg[k] || { runs: 0, passed: 0 }
  agg[k].runs++
  if (passed) agg[k].passed++
}
for (const r of authorJudged) bump(r.model, r.taskId, r.valid && r.equivalent)
for (const r of compJudged) bump(r.model, r.taskId, r.valid && r.equivalent)
for (const r of modJudged) bump(r.model, r.taskId, r.valid && r.equivalent)
for (const r of modRuleJudged) bump(r.model, r.taskId, r.valid && r.equivalent)
for (const r of explainJudged) bump(r.model, r.taskId, r.correct)

log('pass@k by (model, task): ' + JSON.stringify(agg))
return { agg, authorJudged, compJudged, modJudged, modRuleJudged, explainJudged }
