# Portable consistency — implementation plan

> **Planning baseline — 2026-09-11.** Implements the reviewed [portable consistency proposal](PORTABLE-CONSISTENCY-PROPOSAL.md) at `f0cba2488`. This is the fresh plan following the restart; no implementation slice is claimed complete. The proposal owns the product contract and acceptance matrices. This plan owns execution order, prerequisites, review checkpoints and the next bounded task.

The fixed interpreter comparison baseline is `c8e3b99795fe9c2ce3e9db32632a6dd8f6537043`. The abandoned attempt at `6b4c6838737b5db7ad264ba7e207446b3cfcb27d` is read-only source/evidence material. Recover individual original-code changes only after identifying their owner and retained tests; do not restore its module graph, preview API, migration harness or performance qualification.

## 1. Working boundaries

- Build a standalone KMP checker with its own admission, syntax, binding and immutable analysis state. The default is a port of our original infrastructure. A neutral shared leaf is optional and starts with no permitted types until A establishes its bounded allowlist.
- Share selected model facts and measured constraints between designated Java façades and the checker. Preserve existing Java public contracts and deliberate authoring policies. Designate consumers explicitly; do not claim repository-wide consolidation.
- Keep interpreter execution preparation independent. Check both products' production closures and shipped-file ownership, including copied sources, declarations and generated output. Build-tool reuse does not transfer ownership of shipped API files.
- Preserve default Kernel checking in JVM/native dmtool. Portable checking is an explicitly selected consumer of the published checker; it never substitutes automatically for Kernel or vice versa.
- Start each port with the existing implementation and test owner. Translate our own code and reuse expectations. Add a test only for a named uncovered requirement, translation defect or platform difference; use red/green TDD for new behavior and fixes.
- Complete one finite slice before opening another. Freeze its families, fixtures, oracles and exclusions before implementation. An expanding acceptance denominator triggers the repository's convergence procedure, not another patch round.

All proposal slices remain required. A–F can produce an intermediate validator; G–M and N are part of the finished product. No implementation in this plan requires a Document, runtime callback, universal AST, portable editing DSL, language server or Kernel removal.

## 2. Execution order and landing boundaries

Rows below are dependency stages, not permission to combine every named slice into one commit. Each independently meaningful, verified feature lands with its documentation. B1–B3 and C1–C2 retain the proposal's separate acceptance scopes even where their delivery is interleaved with B or C. The detailed case denominator remains in [proposal §7](PORTABLE-CONSISTENCY-PROPOSAL.md#7-implementation-slices-and-finite-acceptance-matrices) and [§12](PORTABLE-CONSISTENCY-PROPOSAL.md#12-project-synergies-additional-features-and-focused-cleanups).

| Order | Slice / bounded work | Prerequisite and exit evidence | Review checkpoint |
|---|---|---|---|
| 1 | A: port/ownership boundary and finite profile inventory; A1–A4 as separate inventory tasks | Complete the records in §3 below. Freeze the first implementation family and performance workload matrix; identify unresolved evidence without counting it as coverage | Terminal foundation review of the inventory and its consumer/constraint boundaries before B begins |
| 2 | B foundation: B2 admission/phase separation and the minimum B3 source-preserving preparation needed for references | A's boundary is fixed. Reuse source admission/parser/workspace owners; retain invalid input, original numeric configuration and source identity. Establish Kernel-free module/test dependencies | One earlier foundation review of public source/snapshot and phase contracts; terminal review before the bounded foundation lands |
| 3 | B1 reference binding, C2 reference diagnostics, then B0's public reference slice | Land the selected shared resolver and corrective owner before B0 consumes them. Run the four-row reference grid, absence and precedence controls. JVM/common tests assert completed reference evidence and overall incomplete status; the API cannot emit VALID | Terminal review of the classified reference result, positive controls and designated Java adoption |
| 4 | Finish B/B3 context and preparation responsibilities; complete C2 diagnostic delivery | Extend the frozen source/fact set to includes, typedefs and repeated mounts; finish Java query reconciliation and parse-once controls. Apply A2's resource ownership decision | Terminal review for each remaining preparation/diagnostic contract; no second general architecture exercise |
| 5 | C/C1 local semantic families, each followed by its D stored-declaration consumer | Translate each selected responsibility and adopt it in its designated Java caller before the checker consumer lands. Retain Java tests, independent Kernel witnesses and policy separation. D covers rules, operations, every precondition/alternative, targets and relevant messages | Terminal review per frozen family; one earlier review only if it introduces a new foundational finding/syntax contract |
| 6 | E relational families | D exposes the required context. Implement the frozen iteration/filter/index and computation-dependency laws using existing evidence plus focused uncovered controls | Terminal review per finite relational family |
| 7 | F2 durable differential, building on the independent witnesses used since B0 | Consolidate A4's same inputs and profile-scoped expectations into the durable adapter lane; neither engine gates admission to the other. Retain complete Kernel observations separately | Terminal review of oracle independence and comparison exclusions |
| 8 | F standalone public delivery and qualification | B–E/C2/F2 supply the declared profile and full reports. Execute Java, packed Node and browser acceptance, including B0's retained controls, ownership/closure guards, API compatibility and ratified H5 budgets | One earlier API/schema/publication-boundary review; terminal review of the actual packed consumers and qualification |
| 9 | F1 dmtool coexistence; F3 published-library integration, as separate commits | F provides the artifact and explicit result contract. F1 retains default/explicit Kernel in both launchers and adds explicit portable selection. F3 adds dmkits/schemakit checking and an integration façade outside both standalone cores | Terminal review for each integration; F1 includes packaged JVM/native identity and substitution controls |
| 10 | G Node CI example; H candidate checking | F's package can be consumed normally. G distinguishes invalid, incomplete and operational failure; H checks immutable candidates without persistence and agrees with fresh checking | Terminal review of each public consumer contract |
| 11 | J impact reports; I editor diagnostics, as separate slices | J uses E's dependency facts and H's candidates. I uses source-aware partial analysis and H's snapshots; neither depends on the other's implementation | Terminal review of affected/unaffected controls for J and revision/cancellation controls for I |
| 12 | L incremental checking, then K completion | L uses B3/J and must match fresh full reports while demonstrating reuse. K uses I and shared model/operator facts. Complete H/I/K/L browser integration once both land | Terminal review per API, including stale-cache and supersession controls |
| 13 | M two-profile compatibility comparison | A3 has pinned two real versions and independent witnesses; both profiles' required implementations are present. Compare with separate checks under each profile | Terminal review of profile evidence and deterministic public differences |
| 14 | N combined availability and qualification | Every earlier slice passes its frozen matrix; all G–M exports and the browser integration work from shipped packages. Complete §5 below | Terminal whole-product contract review before declaring completion |

Packed delivery belongs to F. A2's earlier resource check is a bounded packed-resource experiment using existing tooling, not an early implementation of the full package estate. B0's JVM/common tests do not depend on F. Later package tests replay those same fixtures rather than inventing another reference matrix.

## 3. First task: A, with a finite inventory contract

**Recommendation:** finish the following records before production translation. They settle what will be shared, what can be claimed and what will measure success. This is an inventory of selected responsibilities and the profile denominator, not a new general-purpose verification framework.

| Record | Required contents | Exit oracle |
|---|---|---|
| Port and consumer boundary | Enumerate selected source responsibilities, imports/callers, existing test owners, designated Java consumers and residual owners. Decide explicitly whether `ConditionBridge` is designated. Record proposed checker-owned types and every optional neutral-leaf type | Read every included source/caller; report search commands, matches found/read and anchored exclusions. A leaf with no established benefit stays absent. No reliance on orphaned `build/` output or archived modules as current source |
| Constraint/profile denominator | Map selected laws and grammar productions to current enforcement, Kernel firing sources, raw-input carriers, prerequisites, authoring policies, witnesses and exclusions. Include conversion/non-MVK and applicable generation-path restrictions | Every required profile entry has a disposition; unresolved entries prevent a complete coverage claim. A code count is not a law count |
| A1 erased-bucket disposition | Re-derive the proposal's dated enumeration and disposition every matching row, including legacy/transport shapes | Reachability/acceptance claims have the appropriate Kernel evidence; an unrepresentable Java builder shape is never the exclusion basis |
| A2 diagnostic ownership | Map catalog guidance, `StructuralKernelFix`, applicable `RkCode` knowledge and consumer projections. Prefer direct resource reuse; use deterministic projection when delivery/size or Java-owned guidance requires it | Bounded packed Node resource check plus source/projection freshness and contextual guidance controls; acyclic graph, no new hand-maintained dictionary |
| A3 two-version basis | Record 30.8.1 and exactly one additional available real A12 version, artifact identities, accessible independent oracles and finite profile witnesses | Artifact/version availability and oracle access are verified. If the second version cannot be obtained, identify the concrete acquisition blocker; M/N remain blocked, while unrelated slices can proceed |
| A4 corpus ownership | Identify retained fixtures and typed authors, canonical sources, stable case ids, profile applicability and explicit accepted/rejected/incomplete expectations | Reuse one input owner across common replay, adapter differentials and packed consumers. Add only minimal raw-input mutations needed to reach shapes builders cannot author |
| H4 comparison matrix | Fixed restart revision, row/workload identities, hosts, collectors, matching runtime/launch rules, and the resolved-production-set plus built-artifact-digest comparison | Every required row has an existing measurement owner or a named missing observation. The mechanical closure/artifact comparison decides whether an affected sweep is necessary |
| H5 budget proposal | Workload identities and candidate latency, memory and size budgets for cold, prepared and after-edit checking; include resources/release and combined use | Follow IF137's six-point gate and obtain owner ratification before measuring the implementation those budgets will qualify. Calibration evidence and candidate qualification are separate records |

Use the [proposal research sources](PORTABLE-CONSISTENCY-PROPOSAL.md#11-sources) as the starting map. Existing reference owners include [UndeclaredReferenceTest](../interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/UndeclaredReferenceTest.kt) and [ShortNameTierLawsTest](../src/test/java/io/github/mbackschat/a12/dm/rulekit/validate/laws/ShortNameTierLawsTest.java). These are reuse candidates with the exact limits recorded in proposal §3.4; neither is asserted to cover the new complete reference/report contract alone.

A produces the slice-specific acceptance records needed to start B, not empty tables to be filled during implementation. Preserve these in this plan while they are planning decisions; place the machine-readable profile/check registry with its consuming implementation. Keep measured results in a dated evidence document and link them, following [documentation discipline](DOC-DISCIPLINE.md). Do not create a second status tracker.

### Decisions to resolve during A

- **Shared syntax:** default to checker-owned source. Extract a neutral leaf only when the enumerated closure and H4/H5 evidence support it; this avoids turning parser extraction into an interpreter redesign.
- **Java binding adoption:** investigate designating `ConditionBridge` alongside the relevant Java façades, because otherwise its resolver remains an additional owner. Record the actual decision and residual behavior after reading its closure; do not promise consolidation of `ParsedModel`.
- **Checker budgets:** prepare a concrete, measured recommendation per number before asking for ratification. The proposal's H5 explicitly requires the owner to approve release bars; this plan does not supply that approval or invent numerical targets.
- **Second A12 version:** recommend the closest available real version with independently executable evidence and a useful compatibility discriminator. Name the artifact before selecting it; a synthetic profile cannot substitute.

## 4. Verification and review protocol

Each implementation task records: the frozen behavior/family set, source/test owners, selected fixture ids, expected results, enforcement locus, failing control, targeted command and non-goals. New Kernel-semantic claims add the [witness grid](TESTING-SPEC.md#witness-grid-design) before fixtures. Translation reuses the original cases; shared Java/JS agreement is parity, not an independent oracle.

| Obligation | Test owner / required detector |
|---|---|
| Shared behavior and Java compatibility | Retained Java tests and applicable compiled API checks; designated-façade dependency and targeted mutation controls prove both consumers use the shared predicate |
| A12 decision/classification | Independent pinned Kernel calls in `:adapter`, using original canonical inputs; portable module tests remain Kernel-free |
| Truthful report and coverage | Common tests with explicit expected findings/gaps; controls for skipped checks, first-error short circuit, lost attribution, failed prerequisites and unsupported input. Full report projection repeats through F consumers |
| Source fidelity and reuse | Instrument the checker parser/expansion owner for self-contained, repeated-mount, malformed and all computation-fragment cases; repeat queries and old snapshots must retain their contract |
| Standalone ownership | Compiled/installed closure plus complete shipped-file/provenance enumeration, in both directions, with forbidden-dependency/file positive controls; external consumers run without the other core or Kernel |
| Engine identity | F1's actual invocation and substitution controls; derive receipts from resolved published implementation identity, preserving requested engine and failure stage. Verify JVM/native artifacts, not backend presence alone |
| Application correctness | Proposal §12's expected results through typed packed APIs; fresh-check equivalence for H/J/L/M, explicit reuse for L, browser cancellation/supersession for I/K/L |

Review tasks in §2 follow [CLAUDE.md's cold-review rules](../CLAUDE.md): one terminal review per bounded landing and at most one earlier foundational review. Use a fresh, same-model, read-only reviewer with the frozen diff, matrix and actual verification evidence; no implementation delegation. Where code follows an already-settled pattern, do not add another foundational review. Settle in-scope findings against their oracle; route adjacent findings to their owning task. A change to the acceptance denominator invokes repartitioning before more implementation.

Run affected retained tests and mandatory compatibility/doc checks under the [run discipline](AGENT-RUN-DISCIPLINE.md). Use [test-evidence.sh](../scripts/test-evidence.sh) to distinguish executed results from cached/stale output. Do not repeat the full estate per commit. Native rebuilding occurs in F1/final artifact qualification under the required recapture → build → gate sequence; it is not part of each checker development loop.

## 5. Performance and completion gates

A maps all six [proposal performance boundaries](PORTABLE-CONSISTENCY-PROPOSAL.md#performance) to existing collectors: cold standalone use, prepared use, after-edit use, resources/delivery, interpreter-only use and combined use. Start with the owners described in [INTERPRETER-PERFORMANCE](INTERPRETER-PERFORMANCE.md), the [interpreter build/consumer tasks](../interpreter/build.gradle) and the adapter performance lanes. Reading these owners is required before assigning exact commands; a missing observation gets a bounded addition to its owner, not another harness.

H4 compares each required matching interpreter row independently with the fixed restart baseline. An increase above 5%, or missing/inconclusive evidence, fails; faster rows and checker gains do not offset it. At N, preserve that same row set, baseline and per-row bar as a standing gate, alongside the unchanged permanent interpreter gates. Re-basing needs a separate owner decision. An unchanged resolved production set and built-artifact digest permits avoiding repeated sweeps for checker-only edits; a changed comparison requires the corresponding evidence.

H5 separately qualifies first analysis, prepared checking, after-edit work, memory/release and delivery size on JVM, plain Node and browser. Freeze workloads and owner-ratified budgets before measuring the candidate they gate; report cached lookup separately. L must both equal fresh checking and reuse unaffected work. An unset or unratified budget blocks F/L/N qualification. Combined-use measurements report the cost of both independently prepared products without promising one parse or one graph.

N closes only when:

- Every required slice and full-report matrix passes through its delivered public surface, including all G–M exports and two real profiles.
- The browser H/I/K/L example handles rejected candidates, cancellation and superseded requests correctly.
- Shared Java/checker adoption, symmetric package independence and H1–H5 are established by their named guards and measurements.
- Both packaged dmtool distributions retain the default Kernel oracle and truthful explicit portable selection; F1–F3 pass.
- Public API/architecture/CLI documentation describes the implemented contracts with executed examples. Update the portable API guide as capabilities land; synchronize the F1 wording identified in the proposal. Archive the proposal and this plan together after graduation.
- Remove the temporary owner directive supplied for this migration and any migration-only guardrails actually introduced, after confirming the standing H4 anchor, H5 bars and durable F2 differential remain enforced. The abandoned harness stays retired; cleanup must not remove permanent acceptance.

No code implementation, semantic remeasurement, benchmark qualification or release is part of creating this plan. The next executable task is A's port/consumer boundary and inventory, followed by its terminal review.
