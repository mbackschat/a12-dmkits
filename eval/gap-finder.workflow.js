export const meta = {
  name: 'cli-gap-finder',
  description: 'Cold LLM agents audit the dmtool self-describing surface for qualitative gaps (Option C)',
  whenToUse: 'On-demand self-explainability audit — the layer between the deterministic conformance matrix and the N6 benchmark. Finds gaps the matrix structurally cannot (non-blank-but-unhelpful, latent modes).',
  phases: [{ title: 'Explore', detail: 'k cold agents critique the self-description' }],
}

// One cold agent's report. The gapType enum is FIXED — the aggregator keys on (verb, gapType), so the
// allow-list tripwire is stable across nondeterministic runs.
const GAP_SCHEMA = {
  type: 'object',
  additionalProperties: false,
  required: ['findings'],
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
              'MISSING_CONSTRAINTS', 'UNCLEAR_PURPOSE', 'OTHER'],
          },
          detail: { type: 'string', description: 'one specific sentence: what you cannot tell from the tool alone' },
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

A finding is a real self-explanation gap an LLM agent would trip on — NOT a missing feature, NOT a style nit, NOT "I'd like more examples". Be CONSERVATIVE: only report what would actually block correct first-try use. It is normal to report only a handful across the whole surface; reporting nothing is a valid result if the surface is clear.

Classify each finding by gapType:
  - AMBIGUOUS_MODE        — the verb behaves differently depending on which flags are present, and the tool doesn't make that switch clear (e.g. reads with no flags, writes with them).
  - UNCLEAR_INPUT         — you can't tell what to pass; a required input's shape/format isn't discoverable.
  - UNCLEAR_OUTPUT        — you can't tell what comes back (the shape/fields of the result).
  - PARAM_MEANING_UNCLEAR — a specific param's meaning or accepted values are unclear.
  - MISSING_CONSTRAINTS   — valid values / ranges / mutual-exclusions aren't stated where they matter.
  - UNCLEAR_PURPOSE       — what the verb is for is unclear, or it's easily confused with a sibling verb.
  - OTHER                 — a genuine self-explanation gap not covered above (spell it out in detail).

Return ONLY the structured output {findings:[{verb, gapType, detail}]}. detail = one specific sentence naming exactly what you cannot tell. Use the exact manifest verb name.`

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

log(`gap-finder: ${valid.length}/${K} agents reported; ${agreed.length} agreed gaps (>=${threshold} votes), ${singletons.length} singletons`)
return { agents: valid.length, of: K, threshold, agreed, singletons }
