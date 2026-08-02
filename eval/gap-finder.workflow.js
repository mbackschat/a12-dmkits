export const meta = {
  name: 'cli-gap-finder',
  description: 'Cold LLM agents audit the dmtool self-describing surface for qualitative gaps (Option C)',
  whenToUse: 'On-demand self-explainability audit — the layer between the deterministic conformance matrix and the N6 benchmark. Finds gaps the matrix structurally cannot (non-blank-but-unhelpful, latent modes).',
  phases: [{ title: 'Explore', detail: 'k cold agents critique the self-description' }],
}

// The denominator is the LIVE manifest, supplied verbatim by the operator. A hand-authored target list would
// rot with the CLI; letting each explorer choose its own denominator would let three empty reports look clean.
const A = (typeof args === 'string') ? JSON.parse(args) : (args || {})
const MANIFEST = A.manifest
if (!MANIFEST || !Array.isArray(MANIFEST.verbs) || MANIFEST.verbs.length === 0) {
  throw new Error('gap-finder needs args.manifest from `bin/dmtool manifest`; an absent/empty denominator cannot be audited')
}
const AUDIT_TARGETS = [...new Set(MANIFEST.verbs
  .map(v => String(v.verb || '').trim())
  .filter(verb => verb.includes(' '))
  .map(verb => verb.split(/\s+/)[0]))].sort()
if (AUDIT_TARGETS.length === 0) {
  throw new Error('the live manifest yielded no top-level targets with operations; refusing a vacuous gap audit')
}

// One cold agent's report. The gapType enum is FIXED — the aggregator keys on (verb, gapType), so the
// allow-list tripwire is stable across nondeterministic runs.
const GAP_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['findings', 'audits'],
  properties: {
    findings: {
      type: 'array',
      description: 'self-explanation gaps that would block a cold agent — empty if the surface is clear',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['verb', 'gapType', 'detail'],
        properties: {
          verb: { type: 'string', description: "the exact verb name from the manifest, e.g. 'meta' or 'rule add'" },
          gapType: {
            type: 'string',
            enum: ['AMBIGUOUS_MODE', 'UNCLEAR_INPUT', 'UNCLEAR_OUTPUT', 'PARAM_MEANING_UNCLEAR',
              'MISSING_CONSTRAINTS', 'UNCLEAR_PURPOSE', 'MISSING_DISCOVERY', 'OTHER'],
          },
          detail: { type: 'string', description: 'one specific sentence: what you cannot tell from the tool alone' },
        },
      },
    },
    audits: {
      type: 'array',
      minItems: 1,
      description: 'one absent-sibling routing receipt for every manifest-derived top-level target',
      items: {
        type: 'object',
        additionalProperties: false,
        required: ['target', 'candidate', 'attemptedCommand', 'observed', 'discoveredRoute'],
        properties: {
          target: { type: 'string', minLength: 1 },
          candidate: { type: 'string', minLength: 1 },
          attemptedCommand: { type: 'string', minLength: 1 },
          observed: { type: 'string', minLength: 1 },
          discoveredRoute: { type: 'string', description: 'the live route/corrective, or an empty string if none exists' },
        },
      },
    },
  },
}

const BIN = 'bin/dmtool'   // the canonical `dmtool` shim → installDist JVM launcher (never native; EVAL-SPEC §2a)
const SEP = '\t'

const EXPLORER_PROMPT = `You are a COLD agent. You have been handed exactly one thing: a command-line tool you run as \`${BIN}\` (it calls itself \`dmtool\`). You have NEVER seen its source code, its docs, or any examples — and you must NOT look for them. Use ONLY the tool's own self-description, via these read-only commands (none of them touch a model or write anything):
  - \`${BIN} manifest\`            — every verb, its params (with descriptions + kinds), and a worked example
  - \`${BIN} <verb...> --help\`    — one verb's usage (e.g. \`${BIN} rule add --help\`)
  - \`${BIN} schema <verb...>\`    — a verb's I/O contract (e.g. \`${BIN} schema rule add\`, \`${BIN} schema meta\`)
  - \`${BIN} operators\`           — the DSL operator catalogue
  - \`${BIN} diagnostics\`         — the error-code catalogue
Do NOT pass \`-m\`, do NOT run a verb against a model, do NOT read any file off disk. Stay strictly inside the self-description.

YOUR JOB: walk EVERY verb in the manifest and decide whether — from the tool's self-description ALONE — you could confidently (a) say what the verb does, (b) know exactly what to pass (each param's meaning + its accepted values/format), and (c) know what you'll get back. Where you genuinely could NOT, record a finding.

After that verb walk, test the surface in the opposite direction for EXACTLY these top-level targets, which were
derived by the runner from the live manifest: ${AUDIT_TARGETS.join(', ')}. For each target, invent exactly ONE plausible sibling operation a new user
might reasonably try but which is ABSENT from that target's help. Actually run `${BIN} <target> <candidate> --help`.
Then search the manifest/help for the real route to that intent. Report a MISSING_DISCOVERY finding against the
nearest existing manifest verb ONLY when the failed guess plus the live self-description still do not reveal the
correct route. A clean failed guess with a corrective that names the real route is NOT a finding. Include the exact
attempted command and observed failure in detail. This is bounded to one guess per target; do not brainstorm a wish list.
Whether or not it becomes a finding, return one audit receipt for every target:
  {target, candidate, attemptedCommand, observed, discoveredRoute}
The audit target set must match the supplied list exactly; no omissions, duplicates, or extra targets.

A finding is a real self-explanation gap an LLM agent would trip on — NOT a missing feature, NOT a style nit, NOT "I'd like more examples". Be CONSERVATIVE: only report what would actually block correct first-try use. It is normal to report only a handful across the whole surface; reporting nothing is a valid result if the surface is clear.

Classify each finding by gapType:
  - AMBIGUOUS_MODE        — the verb behaves differently depending on which flags are present, and the tool doesn't make that switch clear (e.g. reads with no flags, writes with them).
  - UNCLEAR_INPUT         — you can't tell what to pass; a required input's shape/format isn't discoverable.
  - UNCLEAR_OUTPUT        — you can't tell what comes back (the shape/fields of the result).
  - PARAM_MEANING_UNCLEAR — a specific param's meaning or accepted values are unclear.
  - MISSING_CONSTRAINTS   — valid values / ranges / mutual-exclusions aren't stated where they matter.
  - UNCLEAR_PURPOSE       — what the verb is for is unclear, or it's easily confused with a sibling verb.
  - MISSING_DISCOVERY     — a plausible absent sibling was tried, and neither its failure nor the live help reveals the real existing route.
  - OTHER                 — a genuine self-explanation gap not covered above (spell it out in detail).

Return ONLY the structured output {findings:[{verb, gapType,detail}],audits:[{target,candidate,attemptedCommand,observed,discoveredRoute}]}. detail = one specific sentence naming exactly what you cannot tell. Use the exact manifest verb name.`

phase('Explore')
const K = 3
const reports = await parallel(
  Array.from({ length: K }, (_, i) =>
    () => agent(EXPLORER_PROMPT, { label: `explorer-${i + 1}`, phase: 'Explore', schema: GAP_SCHEMA, model: 'opus' })
  )
)

// Deterministic aggregation: a (verb, gapType) pair is "agreed" when a MAJORITY of the agents that
// returned a report raised it — this is what damps run-to-run LLM noise into a stable tripwire signal.
const valid = reports.filter(Boolean)
if (valid.length === 0) throw new Error('gap-finder received no valid explorer reports')

function assertAuditCoverage(report, index) {
  const receipts = report.audits || []
  const covered = [...new Set(receipts.map(a => a.target))].sort()
  const missing = AUDIT_TARGETS.filter(target => !covered.includes(target))
  const unexpected = covered.filter(target => !AUDIT_TARGETS.includes(target))
  if (receipts.length !== covered.length || missing.length || unexpected.length) {
    throw new Error(`explorer-${index + 1} audit denominator mismatch: expected [${AUDIT_TARGETS}], `
      + `covered [${covered}], receipts=${receipts.length}, missing=[${missing}], unexpected=[${unexpected}]`)
  }
  for (const receipt of receipts) {
    if (!receipt.attemptedCommand.includes(`${BIN} ${receipt.target} `)) {
      throw new Error(`explorer-${index + 1} did not record an executed absent-sibling command for ${receipt.target}: ${receipt.attemptedCommand}`)
    }
  }
}
valid.forEach(assertAuditCoverage)

const counts = {}
for (const r of valid) {
  const seen = new Set()
  for (const f of (r.findings || [])) {
    const key = (f.verb || '?') + SEP + (f.gapType || 'OTHER')
    if (seen.has(key)) continue       // one vote per agent per (verb, gapType)
    seen.add(key)
    if (!counts[key]) counts[key] = { votes: 0, details: [] }
    counts[key].votes++
    if (f.detail) counts[key].details.push(f.detail)
  }
}
const threshold = Math.ceil(valid.length / 2)
const rows = Object.entries(counts).map(([key, v]) => {
  const parts = key.split(SEP)
  return { verb: parts[0], gapType: parts[1], votes: v.votes, of: valid.length, details: v.details }
})
const agreed = rows.filter(r => r.votes >= threshold).sort((a, b) => b.votes - a.votes || a.verb.localeCompare(b.verb))
const singletons = rows.filter(r => r.votes < threshold).sort((a, b) => b.votes - a.votes)

log(`gap-finder: ${valid.length}/${K} agents reported; audited ${AUDIT_TARGETS.length} live targets each; ${agreed.length} agreed gaps (>=${threshold} votes), ${singletons.length} singletons`)
return { agents: valid.length, of: K, threshold, auditedTargets: AUDIT_TARGETS, agreed, singletons }
