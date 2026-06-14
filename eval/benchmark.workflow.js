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
// Prereq: build the launcher once — `./gradlew :cli:installDist` → cli/build/install/dmtool/bin/dmtool
// (the agents invoke it through the repo's bin/dmtool shim, the same canonical `dmtool` entry the
// examples/ demos use; never the native binary — EVAL-SPEC §2a).
// Invoke:
//   Workflow({ scriptPath: "eval/benchmark.workflow.js",
//     args: { models: ["opus","sonnet"], k: 3, tasks: [ /* contents of eval/tasks/<kit>/*.json */ ] } })
// (The script has no filesystem access, so the caller reads eval/tasks/*/*.json — the kit subfolders — and passes them in.)

export const meta = {
  name: 'rulekit-self-sufficiency-benchmark',
  description: 'N6 self-sufficiency benchmark — clean agents author/read rules from only the shipped material; pass@k per model',
  phases: [
    { title: 'Prep', detail: 'mirror the models tree into a /tmp sandbox (committed fixtures stay read-only)' },
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
const CLI = 'JAVA_HOME="$(/usr/libexec/java_home -v 21)" bin/dmtool'
// A task names its model by stem (<MODELS_ROOT>/<model>.dm.json) OR carries an explicit `modelPath`
// (repo-relative) for nested multi-file layouts. `includeDir` (repo-relative) is threaded to every verb
// as `-w/--workspace` so the recheck/judge can resolve a multi-file model's includes/typedefs.
//
// SANDBOX — committed fixtures are NEVER mutated. The authoring verbs (`add`/`computation add`) write IN
// PLACE by default, so an agent pointed at a committed fixture can corrupt it — it has, repeatedly, even
// slipping a mutated order.dm.json into a commit. So the Prep phase mirrors the whole models tree into a
// /tmp sandbox and every path below resolves THERE: no agent ever names a committed file. The mirror keeps
// the tree structure, so a multi-file model's relative `../lib` includes still resolve. Read-only verbs and
// `--dry-run` validation are unaffected; the author prompts also steer to read-only confirmation.
const MODELS_ROOT = 'cli/src/test/resources/models'
const SANDBOX = '/tmp/rk-eval-sandbox'
const sandboxed = p => p.startsWith(MODELS_ROOT) ? SANDBOX + p.slice(MODELS_ROOT.length) : p
const SRC_MODEL = t => t.modelPath || `${MODELS_ROOT}/${t.model}.dm.json`
const MODEL_FILE = t => sandboxed(SRC_MODEL(t))
const INCLUDE_ARGS = t => t.includeDir ? ['-w', sandboxed(t.includeDir)] : []
const INCLUDE_HINT = t => t.includeDir ? ` -w ${sandboxed(t.includeDir)}` : ''
// Shell single-quote a value so agent-produced free text (descriptions, comments, names) can never break a
// score-agent command. Used by the deterministic metadata/template/refactor scorers below.
const sq = s => `'` + String(s).replace(/'/g, `'\\''`) + `'`

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
const STRUCTURE = TASKS.filter(t => t.family === 'structure' && t.status !== 'deferred')
const SESSION = TASKS.filter(t => t.family === 'session' && t.status !== 'deferred')
const DELETE_SAFETY = TASKS.filter(t => t.family === 'delete-safety' && t.status !== 'deferred')
const METADATA = TASKS.filter(t => t.family === 'metadata' && t.status !== 'deferred')
const TEMPLATE = TASKS.filter(t => t.family === 'template' && t.status !== 'deferred')
const REFACTOR = TASKS.filter(t => t.family === 'refactor' && t.status !== 'deferred')

// ---- prompts (EVAL-SPEC §5: neutral, material-only, no leaking the technique/polarity) ------------

function authorPrompt(task) {
  // For a multi-file model, the boundary permits resolving the models it references (neutral — it does
  // NOT name the flag; the agent discovers `-w/--workspace` from the CLI error + `<verb> --help`).
  const multifile = task.includeDir
    ? `\n  - this model is part of a multi-file workspace: it REFERENCES other model files located elsewhere in the workspace, which you may read/resolve as needed to validate it.`
    : ''
  return `You help a business analyst author an A12 validation rule. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (it is self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <target> <op> --help\` shows an op's parameters, \`${CLI} operators\` lists the DSL operators and \`${CLI} operators <id>\` gives one in full)
  - the model file: ${MODEL_FILE(task)}${multifile}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement to enforce: "${task.intent}".
Author the rule and CONFIRM the kernel accepts it with the read-only check verb (it validates a candidate
WITHOUT writing) — do NOT run any verb that persists/writes the model file. Return ONLY JSON — and note that "field" is
the error field's path INSIDE the model (e.g. /Group/Field), NOT the model FILE path you pass on the command line:
  {"field":"<the error field's path inside the model>","condition":"<DSL>","code":"<ID>","kernelAccepted":true|false}`
}

function modifyComputationPrompt(task) {
  return `You help a business analyst CHANGE an EXISTING A12 COMPUTATION on a model. A computation COMPUTES
(fills in) a field's value from other fields. The model ALREADY has a computation; re-express it to meet a
new requirement, KEEPING THE SAME computed field. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <target> <op> --help\` an op's parameters; \`${CLI} -m ${MODEL_FILE(task)} computation explain ${task.computation}\` shows the existing computation, \`${CLI} schema computation add\` the computation-spec shape, \`${CLI} computation modify --help\` (its \`--dry-run\` PREVIEWS a re-expression read-only), \`${CLI} operators\` the DSL operators)
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
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <target> <op> --help\` an op's parameters; \`${CLI} -m ${MODEL_FILE(task)} rule explain ${task.rule}\` shows the existing rule, \`${CLI} rule modify --help\` (its \`--dry-run\` PREVIEWS a re-expression read-only), \`${CLI} operators\` the DSL operators)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). The existing rule to change: ${task.rule}.
New requirement: "${task.intent}".
Re-express the rule's CONDITION (true on a VIOLATION) and CONFIRM the kernel accepts the CHANGE by PREVIEWING it
(read-only, e.g. \`${CLI} -m ${MODEL_FILE(task)} rule modify ${task.rule} --condition "<DSL>" --dry-run\`) — do NOT
write the model file. Return ONLY JSON ("condition" = the new DSL condition that is true on a violation):
  {"condition":"<DSL>","kernelAccepted":true|false}`
}

function authorComputationPrompt(task) {
  return `You help a business analyst author an A12 COMPUTATION — a rule that COMPUTES (fills in) a field's value
from other fields, rather than validating one. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (it is self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <target> <op> --help\` shows an op's parameters, \`${CLI} schema computation add\` shows the input shapes, \`${CLI} operators\` lists the DSL operators and \`${CLI} operators <id>\` gives one in full)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement to implement: "${task.intent}".
Author the computation and CONFIRM the kernel accepts it by PREVIEWING with \`--dry-run\` (it writes NOTHING) —
do NOT run any verb that persists/writes the model file. Return ONLY JSON:
  {"computedField":"<the computed field's path inside the model, e.g. /Group/Field — NOT the model FILE path>","commonPrecondition":"<DSL or omit>","alternatives":[{"precondition":"<DSL or omit>","operation":"<DSL value expression>"}],"messages":[{"locale":"<code>","text":"..."}],"kernelAccepted":true|false}`
}

function structurePrompt(task) {
  return `You help a business analyst edit an A12 document model's STRUCTURE — its fields and groups. Assume NO
prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <target> <op> --help\` an op's parameters — e.g. \`${CLI} field add --help\` / \`${CLI} group add --help\`; \`${CLI} -m ${MODEL_FILE(task)} model describe\` shows the current structure and its field kinds)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement: "${task.intent}".
Decide the single structure edit that satisfies it and return ONLY JSON describing the op (discover the exact field-kind set from \`${CLI} field add --help\`):
  {"op":"field add" | "group add","group":"<parent group path — for field add>","parent":"<parent path — for group add>","name":"<the new element's name>","kind":"<the field kind — for field add>"}`
}

function sessionPrompt(task) {
  return `You help a business analyst edit an A12 document model in ONE atomic session — structure AND rules
together, applied as an ordered list of operations (a later op may reference a field an earlier op added).
Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <target> <op> --help\` an op's parameters, \`${CLI} schema apply\` shows the op-record frame, \`${CLI} -m ${MODEL_FILE(task)} model describe\` the current structure, \`${CLI} operators\` the DSL operators)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement: "${task.intent}".
Build the ordered op-record list that satisfies it; you may PREVIEW with \`apply --dry-run\` (writes nothing) to confirm before answering. Return ONLY JSON — each op-record's fields are the verb's params (manifest / \`schema apply\`):
  {"ops":[ {"target":"field","op":"add","group":"…","name":"…","kind":"…"}, {"target":"rule","op":"add","spec":{"field":"…","condition":"<DSL>","code":"…","messages":[{"locale":"…","text":"…"}]}} ]}`
}

function deleteSafetyPrompt(task) {
  return `You help a business analyst REMOVE part of an A12 document model. Removing a field that other rules
reference is REFUSED unless you opt into removing those referrers too. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} field remove --help\`, \`${CLI} -m ${MODEL_FILE(task)} model describe\`, \`${CLI} -m ${MODEL_FILE(task)} where-used <path>\` shows what references a field)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement: "${task.intent}".
Decide the field to remove and whether to **cascade** (also remove the rules/computations that reference it). Return ONLY JSON:
  {"field":"<the field's path inside the model, e.g. /Order/X>","cascade":true|false}`
}

function metadataPrompt(task) {
  return `You help a business analyst attach METADATA to an A12 document model — a human-facing DESCRIPTION of an
element, a name/value ANNOTATION on an element, or a model-level COMMENT. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` and \`${CLI} manifest\` list the verbs, \`${CLI} <target> <op> --help\` an op's parameters; \`${CLI} -m ${MODEL_FILE(task)} model describe\` shows the elements and their full name-paths)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement: "${task.intent}".
Decide the single metadata edit that satisfies it and return ONLY JSON, using ONE of these shapes:
  - DESCRIBE an element (field/group/rule by path): {"op":"describe","kind":"internal"|"external","ref":"<element path, e.g. /Order/X>","locale":"<code, e.g. en_US>","text":"<the description>"}
  - ANNOTATE an element with a name/value pair:     {"op":"annotate","ref":"<element path>","name":"<name>","value":"<value>"}
  - set the model-level COMMENT:                     {"op":"comment","text":"<the comment>"}
(An "internal" description is maintainer-facing; "external" is end-user-facing.)`
}

function templatePrompt(task) {
  return `You help a business analyst add a higher-level GROUP to an A12 document model — either a MULTI-SELECT
group (a repeatable group letting the user pick one or more values for a single selection field) or a
file-ATTACHMENT group. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` and \`${CLI} manifest\` list the verbs, \`${CLI} <target> <op> --help\` an op's parameters; \`${CLI} -m ${MODEL_FILE(task)} model describe\` shows the current structure)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement: "${task.intent}".
Decide the single group-template edit that satisfies it and return ONLY JSON, using ONE of these shapes:
  - MULTI-SELECT group:    {"op":"group multiselect","parent":"<parent group path, e.g. /Order>","name":"<group name>","field":"<selection field name>","values":["<v>", …],"maxRows":<max selections, an integer > 1>}
  - file-ATTACHMENT group: {"op":"group attachment","parent":"<parent group path>","name":"<group name>"}`
}

function refactorPrompt(task) {
  return `You help a business analyst RENAME a field in an A12 document model. The rename must keep the model
valid: every rule/computation that references the field has to follow to the new name automatically. Assume NO
prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (self-describing: \`${CLI} --help\` and \`${CLI} manifest\` list the verbs, \`${CLI} <target> <op> --help\` an op's parameters; \`${CLI} -m ${MODEL_FILE(task)} model describe\` shows the fields and their full name-paths, \`${CLI} -m ${MODEL_FILE(task)} where-used <path>\` what references a field)
  - the model file: ${MODEL_FILE(task)}${skillBlock()}
Do NOT read any rulekit source, docs, tests, or other *.md files (beyond the authoring skill named above, if any). Requirement: "${task.intent}".
Decide the field to rename and its new name, then return ONLY JSON:
  {"op":"field rename","field":"<the field's current full path, e.g. /Order/X>","to":"<the new NAME (not a path)>"}`
}

function explainPrompt(task) {
  return `You help a business analyst understand the validation rules already on an A12 model. Assume NO prior
A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (it is self-describing: \`${CLI} --help\` lists the verbs, \`${CLI} <target> <op> --help\` shows an op's parameters, \`${CLI} operators\` lists the DSL operators and \`${CLI} operators <id>\` gives one in full)
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
const STRUCTURE_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: {
    op: { type: 'string' }, group: { type: 'string' }, parent: { type: 'string' },
    name: { type: 'string' }, kind: { type: 'string' },
  },
  required: ['op', 'name'],
}

const SESSION_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { ops: { type: 'array', items: { type: 'object', additionalProperties: true } } },
  required: ['ops'],
}

const DELETE_SAFETY_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { field: { type: 'string' }, cascade: { type: 'boolean' } },
  required: ['field'],
}

const METADATA_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: {
    op: { type: 'string' }, kind: { type: 'string' }, ref: { type: 'string' },
    locale: { type: 'string' }, text: { type: 'string' }, name: { type: 'string' }, value: { type: 'string' },
  },
  required: ['op'],
}

const TEMPLATE_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: {
    op: { type: 'string' }, parent: { type: 'string' }, name: { type: 'string' },
    field: { type: 'string' }, values: { type: 'array', items: { type: 'string' } }, maxRows: { type: 'number' },
  },
  required: ['op', 'name'],
}

const REFACTOR_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { op: { type: 'string' }, field: { type: 'string' }, to: { type: 'string' } },
  required: ['op', 'field', 'to'],
}

const EXPLAIN_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { explanations: { type: 'object', additionalProperties: true } },
  required: ['explanations'],
}

// ---- Prep: VERIFY the model sandbox (committed fixtures stay read-only) ----------------------------
// The COPY is done by eval/prep-sandbox.sh, which the operator runs from the main loop alongside
// `:cli:installDist` — NOT by an agent. A workflow subagent runs under a restricted permission mode and
// cannot be granted approval for a mutating setup command (`rm`/`cp`), so prompting one to build the sandbox
// is non-deterministically denied. This phase only CONFIRMS the sandbox is present (read-only `ls`) and
// HALTS loudly if not, so a forgotten prep can never silently score correct candidates as failures.
phase('Prep')
const prep = await agent(
  `Verify the benchmark's model sandbox exists — READ-ONLY, do NOT create anything. Run exactly:  ls ${SANDBOX}
It must list the model files (e.g. order.dm.json, subscription.dm.json). Report whether the sandbox is ready.`,
  { label: 'verify sandbox', phase: 'Prep',
    schema: { type: 'object', additionalProperties: false, required: ['ready'],
      properties: { ready: { type: 'boolean' }, detail: { type: 'string' } } } })
if (!prep || prep.ready !== true) {
  throw new Error(`model sandbox ${SANDBOX} is not ready — run \`bash eval/prep-sandbox.sh\` from the repo `
    + `root before launching (EVAL-SPEC §8c). Detail: ${(prep && prep.detail) || 'no report'}`)
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

const structureRuns = []
for (const model of MODELS)
  for (const task of STRUCTURE)
    for (let i = 0; i < K; i++) structureRuns.push({ model, task, i })

const authoredStructure = await parallel(structureRuns.map(r => () =>
  agent(structurePrompt(r.task), { label: `struct ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: STRUCTURE_SCHEMA })
    .then(out => ({ kind: 'structure', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'structure', model: r.model, task: r.task, i: r.i, out: null }))))

const sessionRuns = []
for (const model of MODELS)
  for (const task of SESSION)
    for (let i = 0; i < K; i++) sessionRuns.push({ model, task, i })

const authoredSession = await parallel(sessionRuns.map(r => () =>
  agent(sessionPrompt(r.task), { label: `session ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: SESSION_SCHEMA })
    .then(out => ({ kind: 'session', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'session', model: r.model, task: r.task, i: r.i, out: null }))))

const deleteRuns = []
for (const model of MODELS)
  for (const task of DELETE_SAFETY)
    for (let i = 0; i < K; i++) deleteRuns.push({ model, task, i })

const authoredDeleteSafety = await parallel(deleteRuns.map(r => () =>
  agent(deleteSafetyPrompt(r.task), { label: `del ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: DELETE_SAFETY_SCHEMA })
    .then(out => ({ kind: 'delete-safety', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'delete-safety', model: r.model, task: r.task, i: r.i, out: null }))))

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

const metadataRuns = []
for (const model of MODELS)
  for (const task of METADATA)
    for (let i = 0; i < K; i++) metadataRuns.push({ model, task, i })

const authoredMetadata = await parallel(metadataRuns.map(r => () =>
  agent(metadataPrompt(r.task), { label: `meta ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: METADATA_SCHEMA })
    .then(out => ({ kind: 'metadata', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'metadata', model: r.model, task: r.task, i: r.i, out: null }))))

const templateRuns = []
for (const model of MODELS)
  for (const task of TEMPLATE)
    for (let i = 0; i < K; i++) templateRuns.push({ model, task, i })

const authoredTemplate = await parallel(templateRuns.map(r => () =>
  agent(templatePrompt(r.task), { label: `tmpl ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: TEMPLATE_SCHEMA })
    .then(out => ({ kind: 'template', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'template', model: r.model, task: r.task, i: r.i, out: null }))))

const refactorRuns = []
for (const model of MODELS)
  for (const task of REFACTOR)
    for (let i = 0; i < K; i++) refactorRuns.push({ model, task, i })

const authoredRefactor = await parallel(refactorRuns.map(r => () =>
  agent(refactorPrompt(r.task), { label: `refac ${r.model}:${r.task.id}#${r.i}`, phase: 'Produce', model: r.model, schema: REFACTOR_SCHEMA })
    .then(out => ({ kind: 'refactor', model: r.model, task: r.task, i: r.i, out }))
    .catch(() => ({ kind: 'refactor', model: r.model, task: r.task, i: r.i, out: null }))))

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
write its \`spec\` (verbatim JSON) to its \`file\`, then run (READ-ONLY — \`--dry-run\` writes NOTHING, so the
committed fixture is never mutated):  ${CLI} -m <entry.model> computation add <entry.file> --dry-run
The kernel validates the computation and prints the RESULT ENVELOPE: on ACCEPT \`outcome\` is "preview" (exit 0);
on REJECT \`outcome\` is "rejected" (exit 1, with kernel diagnostics in \`diagnostics\`). Entries:
${JSON.stringify(entries)}
Report every entry via the structured-output tool: one {id, valid} per entry (valid = accepted, i.e. \`outcome\` == "preview").`,
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
  ${CLI} -m <entry.model> computation modify <entry.computation> --spec <entry.file> --dry-run
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
Each op runs \`-m <model> rule modify <name> --condition <DSL> --dry-run\` (READ-ONLY — writes nothing): on ACCEPT
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
ARE equivalent. The model is ${MODEL_FILE(r.task)} — first run \`${CLI} -m ${MODEL_FILE(r.task)} model describe${INCLUDE_HINT(r.task)}\` to
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
= present-but-empty; see \`${CLI} schema model eval\`) that exercise the fields BOTH conditions reference at their
edges (empty vs filled, a boundary value, each enum value). For each doc run, for the GOLD then the CANDIDATE,
\`${CLI} -m ${MODEL_FILE(r.task)} model eval${INCLUDE_HINT(r.task)} --condition '<that condition>' --field <that field> --doc <doc>\`
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
${MODEL_FILE(r.task)} — run \`${CLI} -m ${MODEL_FILE(r.task)} model describe\` to see field types. A difference that
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
${MODEL_FILE(r.task)} — run \`${CLI} -m ${MODEL_FILE(r.task)} model describe\` to see field types. A difference that
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
RIGHT change (semantically equivalent to the gold). The model is ${MODEL_FILE(r.task)} — run \`${CLI} -m
${MODEL_FILE(r.task)} model describe\` to see field types and value domains. Judge WITHIN the field's ACTUAL value domain; a
difference that is only path notation or an equivalent value expression is NOT a semantic difference.
VERIFY BY EXECUTION — the kernel is the arbiter; PREFER it over reasoning when equivalence hinges on a kernel
semantic (empty operands, polarity). Write 2-4 small documents to files (\`{"fields":{…}}\`; see \`${CLI} schema
model eval\`) exercising the referenced fields at their edges; for each, run for the GOLD then the CANDIDATE
\`${CLI} -m ${MODEL_FILE(r.task)} model eval${INCLUDE_HINT(r.task)} --condition '<that condition>' --field ${r.task.gold.field} --doc <doc>\`
and compare \`rule.fired\` — EQUIVALENT iff it matches on every document.
Reference gold (the intended modified rule): error field "${r.task.gold.field}", condition=${JSON.stringify(r.task.gold.condition)}.
Candidate condition: ${JSON.stringify(r.out.condition)}.
Return ONLY JSON: {"equivalent": true|false, "why": "<one sentence>"}`,
    { label: `judge ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, properties: { equivalent: { type: 'boolean' }, why: { type: 'string' } }, required: ['equivalent'] } })
    .then(v => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: !!(v && v.equivalent), why: v && v.why }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, valid: true, equivalent: false }))
}))

// ---- Score (structure): DETERMINISTIC — no LLM judge. The agent only RUNS the CLI and reports raw facts;
// the workflow decides pass/fail against the gold's exact post-conditions. The edit is applied with `-o` to
// a fresh /tmp file (never the committed/sandbox fixture), and `field add`/`group add` re-bind before
// writing, so `applied` (ok) already means kernel-valid.
const structureCandidates = authoredStructure.filter(r => r && r.out && r.out.op && r.out.name)
const structureScored = await parallel(structureCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const gold = r.task.gold
  const out = `/tmp/rk-struct-${key.replace(/[^a-zA-Z0-9]/g, '_')}.json`
  const o = r.out
  const applyCmd = o.op === 'group add'
    ? `${CLI} -m ${MODEL_FILE(r.task)} group add --parent ${o.parent} --name ${o.name} -o ${out}`
    : `${CLI} -m ${MODEL_FILE(r.task)} field add --group ${o.group} --name ${o.name} --kind ${o.kind} -o ${out}`
  const describeCheck = `${CLI} -m ${out} model describe | jq -c --arg p '${gold.expectField}' `
    + `'{fieldExists: ([.data.fields[]|select(.path==$p)]|length>0), fieldKind: (([.data.fields[]|select(.path==$p)|.kind][0]) // "")}'`
  return agent(
    `Run these two commands and report the raw facts — do NOT judge, just execute and copy the asked JSON fields.
1. Apply the edit (writes a NEW file, never the input): ${applyCmd}
   Report \`applied\` = the result envelope's \`ok\` (true/false).
2. Read the expected element's kind back from the result (this prints {fieldExists, fieldKind} directly): ${describeCheck}
   Report \`fieldExists\` and \`fieldKind\` exactly as that prints them.
Return ONLY JSON: {"applied": <bool>, "fieldExists": <bool>, "fieldKind": "<string>"}`,
    { label: `score ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, required: ['applied'],
        properties: { applied: { type: 'boolean' }, fieldExists: { type: 'boolean' }, fieldKind: { type: 'string' } } } })
    .then(f => ({ key, model: r.model, taskId: r.task.id,
      pass: !!(f && f.applied && f.fieldExists && f.fieldKind === gold.kind), facts: f }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, pass: false }))
}))

// ---- Score (session): DETERMINISTIC — apply the produced `apply` op-list with `-o` (a fresh /tmp file),
// then check the gold's expect.{field,rule} are present on the committed result. No LLM judge.
const sessionCandidates = authoredSession.filter(r => r && r.out && Array.isArray(r.out.ops) && r.out.ops.length)
const sessionScored = await parallel(sessionCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const expect = r.task.gold.expect
  const safe = key.replace(/[^a-zA-Z0-9]/g, '_')
  const opsFile = `/tmp/rk-sess-ops-${safe}.json`
  const out = `/tmp/rk-sess-out-${safe}.json`
  return agent(
    `Run these and report the raw facts — do NOT judge, just execute and copy the asked JSON fields.
1. Write this op-record list (verbatim JSON ARRAY) to ${opsFile}:  ${JSON.stringify(r.out.ops)}
2. Apply it atomically, committing to a NEW file:  ${CLI} -m ${MODEL_FILE(r.task)} apply ${opsFile} -o ${out}
   Report \`committed\` = the envelope's \`ok\` (true iff the whole sequence committed; on rollback it is false).
3. ONLY if committed, on the result:  ${CLI} -m ${out} field read ${expect.field}  → \`fieldExists\` = its \`ok\`;
   ${CLI} -m ${out} where-used ${expect.field}  → \`ruleOnField\` = true iff its \`data.rules\` is NON-EMPTY (some
   rule now references the new field — the rule's own code/name is the author's choice, so do NOT match it
   exactly). (If not committed, both are false.)
Return ONLY JSON: {"committed": <bool>, "fieldExists": <bool>, "ruleOnField": <bool>}`,
    { label: `score ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, required: ['committed'],
        properties: { committed: { type: 'boolean' }, fieldExists: { type: 'boolean' }, ruleOnField: { type: 'boolean' } } } })
    .then(f => ({ key, model: r.model, taskId: r.task.id,
      pass: !!(f && f.committed && f.fieldExists && f.ruleOnField), facts: f }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, pass: false }))
}))

// ---- Score (delete-safety): DETERMINISTIC — run the produced removal with `-o`, then confirm every
// gold.expectRemoved path is GONE on the result. (A referenced field WITHOUT --cascade is refused → ok:false.)
const deleteCandidates = authoredDeleteSafety.filter(r => r && r.out && r.out.field)
const deleteScored = await parallel(deleteCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const removed = r.task.gold.expectRemoved
  const out = `/tmp/rk-del-out-${key.replace(/[^a-zA-Z0-9]/g, '_')}.json`
  const cascade = r.out.cascade ? ' --cascade' : ''
  return agent(
    `Run these and report the raw facts — do NOT judge, just execute.
1. Remove the field, committing to a NEW file:  ${CLI} -m ${MODEL_FILE(r.task)} field remove ${r.out.field}${cascade} -o ${out}
   Report \`applied\` = the envelope's \`ok\` (a referenced field WITHOUT --cascade is refused → ok:false).
2. ONLY if applied, confirm every path in ${JSON.stringify(removed)} is GONE on ${out}: for each, run
   \`${CLI} -m ${out} field read <path>\` (if it names a field) or \`${CLI} -m ${out} rule read <path>\` (if a rule);
   every one must report ok:false (not found). Report \`allRemoved\` = true iff none of them resolves.
Return ONLY JSON: {"applied": <bool>, "allRemoved": <bool>}`,
    { label: `score ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, required: ['applied'],
        properties: { applied: { type: 'boolean' }, allRemoved: { type: 'boolean' } } } })
    .then(f => ({ key, model: r.model, taskId: r.task.id, pass: !!(f && f.applied && f.allRemoved), facts: f }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, pass: false }))
}))

// ---- Score (metadata): DETERMINISTIC — apply the produced metadata edit with `-o` (a fresh /tmp file),
// then read the gold's expected value back. The WRITE is built from the AGENT's chosen op (describe /
// annotate / comment); the READ + the expected value come from the GOLD — so a wrong op, ref, locale, or
// text leaves the gold's location empty and fails. The meta/config reads always succeed (the gold's ref
// exists), so they pipe to jq; the score-agent just copies the raw string. No LLM judge.
const metadataCandidates = authoredMetadata.filter(r => r && r.out && r.out.op)
const metadataScored = await parallel(metadataCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const gold = r.task.gold
  const o = r.out
  const out = `/tmp/rk-meta-${key.replace(/[^a-zA-Z0-9]/g, '_')}.json`
  let writeCmd
  if (o.op === 'comment') {
    writeCmd = `${CLI} -m ${MODEL_FILE(r.task)} config modify --comment ${sq(o.text || '')} -o ${out}`
  } else if (o.op === 'annotate') {
    writeCmd = `${CLI} -m ${MODEL_FILE(r.task)} meta ${sq(o.ref || '')} --annotate ${sq((o.name || '') + '=' + (o.value || ''))} -o ${out}`
  } else { // describe (internal/external)
    const flag = o.kind === 'external' ? '--external' : '--internal'
    writeCmd = `${CLI} -m ${MODEL_FILE(r.task)} meta ${sq(o.ref || '')} ${flag} ${sq((o.locale || '') + '=' + (o.text || ''))} -o ${out}`
  }
  let readCmd, expected
  if (gold.op === 'comment') {
    readCmd = `${CLI} -m ${out} config read | jq -r '.data.comment // ""'`
    expected = gold.text
  } else if (gold.op === 'annotate') {
    readCmd = `${CLI} -m ${out} meta ${sq(gold.ref)} | jq -r '.data.annotations[${JSON.stringify(gold.name)}] // ""'`
    expected = gold.value
  } else { // describe
    const fld = gold.kind === 'external' ? 'externalDescription' : 'internalDescription'
    readCmd = `${CLI} -m ${out} meta ${sq(gold.ref)} | jq -r '.data.${fld}[${JSON.stringify(gold.locale)}] // ""'`
    expected = gold.text
  }
  return agent(
    `Run these and report the raw facts — do NOT judge, just execute and copy the asked values.
1. Apply the metadata edit (writes a NEW file, never the input): ${writeCmd}
   Report \`applied\` = the result envelope's \`ok\` (true/false).
2. ONLY if applied, read the value back from the result (prints the raw string, or an empty line): ${readCmd}
   Report \`readBack\` = EXACTLY that string (the empty string if it printed nothing). Do not trim or alter it.
Return ONLY JSON: {"applied": <bool>, "readBack": "<string>"}`,
    { label: `score ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, required: ['applied'],
        properties: { applied: { type: 'boolean' }, readBack: { type: 'string' } } } })
    .then(f => ({ key, model: r.model, taskId: r.task.id, pass: !!(f && f.applied && f.readBack === expected), facts: f }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, pass: false }))
}))

// ---- Score (template): DETERMINISTIC — apply the produced group-template verb with `-o`, then confirm the
// expected group exists AND the generated skeleton carries the right `usageType` marker (the kernel's
// MultiSelectGroupRule/AttachmentGroupRule already accepted the composition, since `applied`==ok re-binds
// before writing — the marker proves the agent used the TEMPLATE verb, not a hand-rolled plain `group add`).
// `group read` on a missing group fails fast (stderr, no JSON), so it is agent-interpreted, not jq-piped.
const templateCandidates = authoredTemplate.filter(r => r && r.out && r.out.op && r.out.name)
const templateScored = await parallel(templateCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const gold = r.task.gold
  const o = r.out
  const out = `/tmp/rk-tmpl-${key.replace(/[^a-zA-Z0-9]/g, '_')}.json`
  const marker = gold.op === 'group multiselect' ? 'multi-select' : 'attachment'
  let applyCmd
  if (o.op === 'group multiselect') {
    const vals = Array.isArray(o.values) && o.values.length ? ` --values ${sq(o.values.join(','))}` : ''
    applyCmd = `${CLI} -m ${MODEL_FILE(r.task)} group multiselect --parent ${sq(o.parent || '')} --name ${sq(o.name)} `
      + `--field ${sq(o.field || '')}${vals} --max-rows ${Number(o.maxRows) || 0} -o ${out}`
  } else if (o.op === 'group attachment') {
    applyCmd = `${CLI} -m ${MODEL_FILE(r.task)} group attachment --parent ${sq(o.parent || '')} --name ${sq(o.name)} -o ${out}`
  } else {
    return Promise.resolve({ key, model: r.model, taskId: r.task.id, pass: false })  // not a template verb
  }
  const groupCheck = `${CLI} -m ${out} group read ${sq(gold.expectGroup)}`
  const markerCheck = `grep -c ${sq('"usageType" : "' + marker + '"')} ${out} || true`
  return agent(
    `Run these and report the raw facts — do NOT judge, just execute.
1. Apply the group-template edit (writes a NEW file, never the input): ${applyCmd}
   Report \`applied\` = the result envelope's \`ok\` (true/false).
2. ONLY if applied, confirm the expected group exists:  ${groupCheck}
   Report \`groupExists\` = true iff it RESOLVES (prints an \`ok\`:true envelope); false if it reports "no group …".
3. ONLY if applied, confirm the template skeleton was generated:  ${markerCheck}
   Report \`marker\` = true iff that command prints a number greater than 0.
Return ONLY JSON: {"applied": <bool>, "groupExists": <bool>, "marker": <bool>}`,
    { label: `score ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, required: ['applied'],
        properties: { applied: { type: 'boolean' }, groupExists: { type: 'boolean' }, marker: { type: 'boolean' } } } })
    .then(f => ({ key, model: r.model, taskId: r.task.id, pass: !!(f && f.applied && f.groupExists && f.marker), facts: f }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, pass: false }))
}))

// ---- Score (refactor): DETERMINISTIC — run the produced `field rename --to` with `-o`, then confirm the NEW
// path resolves and the OLD path is gone. `field rename` re-validates the whole model (valid->valid) before
// writing, so `applied`==ok already proves every reference followed to the new name (a dangling ref would
// reject, not apply). `field read` on the gone path fails fast (stderr, no JSON) → agent-interpreted. No judge.
const refactorCandidates = authoredRefactor.filter(r => r && r.out && r.out.op && r.out.field && r.out.to)
const refactorScored = await parallel(refactorCandidates.map(r => () => {
  const key = `${r.model}/${r.task.id}/${r.i}`
  const gold = r.task.gold
  const o = r.out
  const out = `/tmp/rk-refac-${key.replace(/[^a-zA-Z0-9]/g, '_')}.json`
  const applyCmd = `${CLI} -m ${MODEL_FILE(r.task)} field rename ${sq(o.field)} --to ${sq(o.to)} -o ${out}`
  const newCheck = `${CLI} -m ${out} field read ${sq(gold.expect.to)}`
  const oldCheck = `${CLI} -m ${out} field read ${sq(gold.expect.renamed)}`
  return agent(
    `Run these and report the raw facts — do NOT judge, just execute.
1. Apply the rename (writes a NEW file, never the input): ${applyCmd}
   Report \`applied\` = the result envelope's \`ok\` (true/false). (The rename re-validates the whole model before
   writing, so \`ok\`:true already means every reference followed to the new name and the model is still valid.)
2. ONLY if applied, confirm the NEW path resolves:  ${newCheck}
   Report \`newExists\` = true iff it RESOLVES (prints the field's type; \`ok\`:true).
3. ONLY if applied, confirm the OLD path is GONE:  ${oldCheck}
   Report \`oldGone\` = true iff it does NOT resolve (it reports "no field '…' in the model").
Return ONLY JSON: {"applied": <bool>, "newExists": <bool>, "oldGone": <bool>}`,
    { label: `score ${key}`, phase: 'Judge',
      schema: { type: 'object', additionalProperties: true, required: ['applied'],
        properties: { applied: { type: 'boolean' }, newExists: { type: 'boolean' }, oldGone: { type: 'boolean' } } } })
    .then(f => ({ key, model: r.model, taskId: r.task.id, pass: !!(f && f.applied && f.newExists && f.oldGone), facts: f }))
    .catch(() => ({ key, model: r.model, taskId: r.task.id, pass: false }))
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
for (const r of structureScored) bump(r.model, r.taskId, r.pass)
for (const r of sessionScored) bump(r.model, r.taskId, r.pass)
for (const r of deleteScored) bump(r.model, r.taskId, r.pass)
for (const r of metadataScored) bump(r.model, r.taskId, r.pass)
for (const r of templateScored) bump(r.model, r.taskId, r.pass)
for (const r of refactorScored) bump(r.model, r.taskId, r.pass)

log('pass@k by (model, task): ' + JSON.stringify(agg))
return { agg, authorJudged, compJudged, modJudged, modRuleJudged, explainJudged,
  structureScored, sessionScored, deleteScored, metadataScored, templateScored, refactorScored }
