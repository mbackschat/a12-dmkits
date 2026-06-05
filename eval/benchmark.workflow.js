// Layer-2 of the self-sufficiency benchmark (EVAL-SPEC §8). RUNNABLE, but RUN ON DEMAND — it is
// token-heavy (model × task × k clean agents + judges) and so is opt-in, never per-commit. The
// deterministic Layer-1 backbone (golds validate / comprehension rules ingest) is EvalGoldOracleTest.
//
// What it measures: given ONLY the shipped material (the CLI launcher + the operator catalog), can a
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
const CATALOG = 'src/main/resources/catalog/operators.json'
const MODEL_FILE = t => `cli/src/test/resources/models/${t.model}.dm.json`

const AUTHORING = TASKS.filter(t => t.family === 'authoring')
const COMPREHENSION = TASKS.filter(t => t.family === 'comprehension')

// ---- prompts (EVAL-SPEC §5: neutral, material-only, no leaking the technique/polarity) ------------

function authorPrompt(task) {
  return `You help a business analyst author an A12 validation rule. Assume NO prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (discover its verbs by running it with no args)
  - the operator catalog JSON: ${CATALOG}
  - the model file: ${MODEL_FILE(task)}
Do NOT read any rulekit source, docs, tests, or *.md files. Requirement to enforce: "${task.intent}".
Author the rule and CONFIRM the kernel accepts it via the CLI. Return ONLY JSON:
  {"field":"<absolute path>","condition":"<DSL>","code":"<ID>","kernelAccepted":true|false}`
}

function explainPrompt(task) {
  return `You help a business analyst understand the validation rules already on an A12 model. Assume NO prior
A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (discover its verbs by running it with no args)
  - the operator catalog JSON: ${CATALOG}
  - the model file: ${MODEL_FILE(task)}
Do NOT read any rulekit source, docs, tests, or *.md files. For each of these rules: ${JSON.stringify(task.rules)}
explain exactly what makes a document INVALID (the violation the rule fires on) and which field it guards.
Return ONLY JSON: {"explanations": { "<ruleName>": "<your explanation>", ... }}`
}

const AUTHOR_SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { field: { type: 'string' }, condition: { type: 'string' },
    code: { type: 'string' }, kernelAccepted: { type: 'boolean' } },
  required: ['field', 'condition'],
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

// ---- Recheck: ONE warm-JVM `cli batch` authoritatively re-validates every authored candidate -------
// (Don't trust the agents' self-reported kernelAccepted.) This is the batch verb's reason to exist:
// N checks in one process, paying the kernel's StringTemplate warm-up once.

const candidates = authored.filter(r => r && r.out && r.out.condition)
let authoritative = {}   // "model/taskId/i" -> bool valid
if (candidates.length) {
  const ops = candidates.map(r => ({
    id: `${r.model}/${r.task.id}/${r.i}`,
    verb: 'check',
    args: [MODEL_FILE(r.task), '--field', r.out.field, '--condition', r.out.condition, '--code', r.out.code || 'EVAL'],
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
ARE equivalent. The model is ${MODEL_FILE(r.task)} — first run \`${CLI} describe ${MODEL_FILE(r.task)}\` to
see field types and enum value sets (e.g. an enum with only three possible values: "X not in {A,B}" then
equals "X == C"). For pattern/regex operands, equivalence is about capturing the SAME intended format —
treat trivially different regexes (anchors, equivalent character classes) that accept the same strings as
equivalent; do not require byte-identical regex text.
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

// ---- Aggregate pass@k per (model, task). pass = kernel-valid AND semantically right ----------------

const agg = {}
function bump(model, taskId, passed) {
  const k = `${model}/${taskId}`
  agg[k] = agg[k] || { runs: 0, passed: 0 }
  agg[k].runs++
  if (passed) agg[k].passed++
}
for (const r of authorJudged) bump(r.model, r.taskId, r.valid && r.equivalent)
for (const r of explainJudged) bump(r.model, r.taskId, r.correct)

log('pass@k by (model, task): ' + JSON.stringify(agg))
return { agg, authorJudged, explainJudged }
