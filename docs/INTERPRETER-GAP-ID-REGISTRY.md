# Interpreter gap ID registry

This is the append-only registry for every `IAG<n>` and `IRG<n>` identifier issued by the interpreter public-API and release gap ledgers. The live ledgers remain live-only: [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md) and [INTERPRETER-RELEASE-GAPS](INTERPRETER-RELEASE-GAPS.md) carry only open and deferred work, while this surface makes shipped identifiers resolvable and prevents reuse.

`InterpreterGapIdRegistryGuardTest` requires each identifier to occur exactly once here, requires every open or deferred ledger heading to match its registered state and canonical meaning, and rejects a shipped identifier reappearing as live work. Every ID-bearing registry row and live heading must first match the canonical grammar; a malformed duplicate cannot disappear from the denominator merely because its anchor or suffix is wrong. New work takes the next unused number in its family. Historical commits cannot be rewritten, so the three accidental reuses are corrected here: the oldest meanings keep `IAG9`, `IRG2` and `IG140`; the later meanings are `IAG12`, `IRG4` and `IG144`. `IG140` was issued twice in five days — for the DateRange operand-refusal basis (2026-08-19, shipped in `validate.laws.DateRangeConstructionOperandLawsTest`, which cites it) and again for the TIME-with-a-DATE-format gap (2026-08-24). The `IG` family sits outside `InterpreterGapIdRegistryGuardTest`'s enforced namespace, which is why nothing caught it; the rows are kept here so both citations resolve.

| id | state | canonical meaning |
|---|---|---|
| IAG1 <a id="iag1"></a> | deferred | Kernel-shaped TypeScript import replacement |
| IAG2 <a id="iag2"></a> | deferred | JVM `IDocumentRtService` drop-in |
| IAG3 <a id="iag3"></a> | deferred | richer document values, storage, inspection, and persistent editing |
| IAG4 <a id="iag4"></a> | deferred | stored, targeted, and candidate program APIs |
| IAG5 <a id="iag5"></a> | deferred | registered services, pools, version switching, and Worker coordination |
| IAG6 <a id="iag6"></a> | deferred | broader metadata and application-owned annotations |
| IAG7 <a id="iag7"></a> | deferred | broader evaluation configuration and conversion helpers |
| IAG8 <a id="iag8"></a> | deferred | callback-only Kernel extension compatibility adapters |
| IAG9 <a id="iag9"></a> | shipped | public computation diagnostics retain the Kernel's two explanatory channels |
| IAG10 <a id="iag10"></a> | shipped | a typed pointer on the MESSAGE channel must model A12's partially-known type, not our exact one |
| IAG11 <a id="iag11"></a> | shipped | the house pointer form has no published renderer |
| IAG12 <a id="iag12"></a> | shipped | `A12PointerFormat` is not on the npm surface |
| IAG13 <a id="iag13"></a> | shipped | concurrent misuse of one Interpreter corrupts instead of failing |
| IAG14 <a id="iag14"></a> | deferred | per-entry cost surface for bounding a model cache in bytes |
| IAG15 <a id="iag15"></a> | shipped | cross-check the two published message-address projections without adding interpreter API |
| IG148 <a id="ig148"></a> | shipped → [IF323](INTERPRETER-FINDINGS.md#if323) | the rule-message `$#…$` group parameter is not implemented and renders verbatim |
| IG149 <a id="ig149"></a> | shipped → [IF327](INTERPRETER-FINDINGS.md#if327) | the fragment runtime completion is measured only on the four conventional formats, and the kind admits all twelve |
| IG150 <a id="ig150"></a> | out-of-scope | the two form-line message parameters render verbatim, and both kernel strategies fault on them |
| IG146 <a id="ig146"></a> | shipped → [IF315](INTERPRETER-FINDINGS.md#if315) | the compute path's over-repetition relevance tested group paths against a set of field paths, so the over-limit ROW code never reached the operand channel |
| IG147 <a id="ig147"></a> | shipped → [IF326](INTERPRETER-FINDINGS.md#if326) | an unresolvable temporal constant is an operand error with no outcome for the Kernel and an ERRORED outcome for the interpreter |
| IG145 <a id="ig145"></a> | shipped → [IF325](INTERPRETER-FINDINGS.md#if325) | `formatPointer` drops a repeatable GROUP node's own row index, collapsing addresses the Kernel distinguishes |
| IG144 <a id="ig144"></a> | shipped → [IF314](INTERPRETER-FINDINGS.md#if314) | a TIME field declared with a DATE format is live in the Kernel and rejected by the interpreter on every value |
| IG143 <a id="ig143"></a> | deferred | a hand-built interpreter document is a route hybrid, so two cross-route laws are ungated |
| IG140 <a id="ig140"></a> | shipped | which operands a `DateRange(start, finish)` construction admits, and the measured basis for `Dsl.dateRange`'s client-side refusals |
| IG142 <a id="ig142"></a> | shipped → [IF313](INTERPRETER-FINDINGS.md#if313) | a computed TIME is never source-equal in the interpreter, so a re-apply rewrites the cell |
| IRG1 <a id="irg1"></a> | deferred | publish the separate Kernel drop-in artifact |
| IRG2 <a id="irg2"></a> | shipped | deploy the showcase semantics badge after v0.9.1 |
| IRG3 <a id="irg3"></a> | shipped | make both Kotlin ABI goldens member-level |
| IRG4 <a id="irg4"></a> | shipped | demonstrate per-row computed identity in the browser showcase |
| IRG5 <a id="irg5"></a> | deferred | a scripted documentation-only mirror refresh |

The corrected identifiers are provenance labels, not live work. `IAG12` shipped with the npm pointer export and was subsequently corrected to the two owner-shaped pointer contracts in [IF242](INTERPRETER-FINDINGS.md#if242). `IRG4` shipped as the repeated permit-model computation in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md). `IAG11` closed by keeping the lossy house spelling tool-owned and removing the accidental public `MessagePointers` façade; it is not an A12 format. `IAG15` closed without reopening that surface: the adapter projector is a test fixture consumed by the CLI equivalence guard, not interpreter production API.
