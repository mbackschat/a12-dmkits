// DRAFT — Layer-2 of the self-sufficiency benchmark (EVAL-SPEC §8). NOT yet runnable as-is:
// it depends on the `batch` CLI verb (build it with this runner — amortizes JVM startup + kernel
// warm-up across the many `check`s) and on the eval/tasks corpus being passed in via `args`.
//
// Shape: fan out `model × task × k` CLEAN subagents, each given ONLY the shipped material (the CLI
// launcher + the catalog), each scored objectively via the CLI (`check` accepts AND output matches
// the gold). Aggregate pass@k per (model, task). Pin the model explicitly for reproducibility and
// for the Sonnet-vs-Opus comparison. Run on demand (token-heavy), not per-commit.
//
// Invoke (once finished): Workflow({ scriptPath: "eval/benchmark.workflow.js",
//   args: { models: ["opus","sonnet"], k: 3, tasks: [/* eval/tasks/*.json contents */] } })

export const meta = {
  name: 'rulekit-self-sufficiency-benchmark',
  description: 'N6 self-sufficiency benchmark — clean agents author rules from only the shipped material; score pass@k per model',
  phases: [{ title: 'Author', detail: 'clean agent per (model,task,run)' }, { title: 'Score', detail: 'kernel-accepts + gold-match' }],
}

const MODELS = (args && args.models) || ['opus']
const K = (args && args.k) || 3
const TASKS = (args && args.tasks) || []     // each: {id, family, model, intent, gold:{field,condition,code}}
const CLI = 'JAVA_HOME="$(/usr/libexec/java_home -v 21)" cli/build/install/cli/bin/cli'
const CATALOG = 'src/main/resources/catalog/operators.json'

// The neutral material-only prompt (EVAL-SPEC §5: no leaking the technique/polarity).
function prompt(task) {
  return `You help a business analyst author an A12 validation rule. No prior A12/rulekit knowledge; use ONLY:
  - the CLI:  ${CLI} <args>   (discover it by running it with no args)
  - the operator catalog JSON: ${CATALOG}
  - the model file for model "${task.model}" under cli/src/test/resources/models/${task.model}.dm.json
Do NOT read any rulekit source/docs/tests/*.md. Requirement to enforce: "${task.intent}".
Author the rule and CONFIRM the kernel accepts it via the CLI's check verb. Return ONLY JSON:
  {"field": "...", "condition": "...", "code": "...", "kernelAccepted": true|false}`
}

const SCHEMA = {
  type: 'object', additionalProperties: true,
  properties: { field: { type: 'string' }, condition: { type: 'string' },
    code: { type: 'string' }, kernelAccepted: { type: 'boolean' } },
  required: ['field', 'condition', 'kernelAccepted'],
}

phase('Author')
const runs = []
for (const model of MODELS) {
  for (const task of TASKS) {
    for (let i = 0; i < K; i++) {
      runs.push({ model, task, i })
    }
  }
}

// One clean agent per run; pinned model. (Scoring note: kernelAccepted is the agent's self-report;
// the authoritative re-score is a deterministic `cli check` of the returned field/condition/code —
// wire that here once the `batch` verb lands so all re-checks share one warm JVM.)
const results = await parallel(runs.map(r => () =>
  agent(prompt(r.task), { label: `${r.model}:${r.task.id}#${r.i}`, phase: 'Author', model: r.model, schema: SCHEMA })
    .then(out => ({ model: r.model, taskId: r.task.id, out }))
    .catch(() => ({ model: r.model, taskId: r.task.id, out: null }))))

// Aggregate pass@k per (model, task). `passed` = kernelAccepted AND condition matches the gold
// (exact or render-equivalent). TODO: replace self-report with the batch re-check.
const agg = {}
for (const r of results.filter(Boolean)) {
  const key = `${r.model}/${r.taskId}`
  agg[key] = agg[key] || { runs: 0, passed: 0 }
  agg[key].runs++
  if (r.out && r.out.kernelAccepted) agg[key].passed++
}
log('pass@k by (model, task): ' + JSON.stringify(agg))
return agg
