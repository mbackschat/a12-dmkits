# Interpreter gap ID registry

This is the append-only registry for every `IAG<n>` and `IRG<n>` identifier issued by the interpreter public-API and release gap ledgers. The live ledgers remain live-only: [INTERPRETER-API-GAPS](INTERPRETER-API-GAPS.md) and [INTERPRETER-RELEASE-GAPS](INTERPRETER-RELEASE-GAPS.md) carry only open and deferred work, while this surface makes shipped identifiers resolvable and prevents reuse.

`InterpreterGapIdRegistryGuardTest` requires each identifier to occur exactly once here, requires every open or deferred ledger heading to match its registered state and canonical meaning, and rejects a shipped identifier reappearing as live work. Every ID-bearing registry row and live heading must first match the canonical grammar; a malformed duplicate cannot disappear from the denominator merely because its anchor or suffix is wrong. New work takes the next unused number in its family. Historical commits cannot be rewritten, so the two accidental reuses are corrected here: the oldest meanings keep `IAG9` and `IRG2`; the later meanings are `IAG12` and `IRG4`.

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
| IAG15 <a id="iag15"></a> | deferred | the published message-address policy has no home both publishing seams can reach |
| IG142 <a id="ig142"></a> | shipped → [IF313](INTERPRETER-FINDINGS.md#if313) | a computed TIME is never source-equal in the interpreter, so a re-apply rewrites the cell |
| IRG1 <a id="irg1"></a> | deferred | publish the separate Kernel drop-in artifact |
| IRG2 <a id="irg2"></a> | shipped | deploy the showcase semantics badge after v0.9.1 |
| IRG3 <a id="irg3"></a> | shipped | make both Kotlin ABI goldens member-level |
| IRG4 <a id="irg4"></a> | shipped | demonstrate per-row computed identity in the browser showcase |
| IRG5 <a id="irg5"></a> | deferred | a scripted documentation-only mirror refresh |

The corrected identifiers are provenance labels, not live work. `IAG12` shipped with the npm pointer export and was subsequently corrected to the two owner-shaped pointer contracts in [IF242](INTERPRETER-FINDINGS.md#if242). `IRG4` shipped as the repeated permit-model computation in [INTERPRETER-RELEASE-SPEC](INTERPRETER-RELEASE-SPEC.md). `IAG11` closed by keeping the lossy house spelling tool-owned and removing the accidental public `MessagePointers` façade; it is not an A12 format.
