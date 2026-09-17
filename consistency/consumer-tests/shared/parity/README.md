# The three-arm parity corpus

**Generated. Do not edit by hand** — `./build.sh :consistency:syncCheckerParityCorpus` rewrites every file here from the packed npm package, and [`write-parity-corpus.mjs`](../../node/write-parity-corpus.mjs) is what it runs.

[F's claim-specific oracle](../../../../docs/PORTABLE-CONSISTENCY-PROPOSAL.md) for "JS matches JVM" asks for one thing that per-field assertions cannot give: *"Execute the same fixtures through Java, packed Node and real-browser consumers, **comparing the full portable report contract**"*. So this corpus is fully data-driven — an arm reads a call, its sources, and the report it must produce, and compares the whole document. It adds no semantic claim of its own; the readable, claim-per-case assertions live in [`cases.ts`](../cases.ts) and stay there.

**The inputs are BYTES, not derivations.** Every arm reads the same source text rather than deriving it, so a parity failure cannot be an arm having built a slightly different model. That is also why `broken.dm.json` is stored rather than produced by a substitution the way `cases.ts` produces its own.

| file | what it is |
|---|---|
| `manifest.json` | the case list: each case's call, its source keys, and the report file that answers it |
| `sources/*` | the exact input bytes, shared by the cases that use them |
| `reports/*.json` | the full report each case must produce, pretty-printed for a readable diff |

Comparison is over PARSED documents, never bytes: object key order is not part of the contract and array order is, which is exactly what a JSON-element comparison gives on every arm.
