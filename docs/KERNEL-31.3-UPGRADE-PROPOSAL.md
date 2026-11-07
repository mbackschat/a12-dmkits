# Kernel 31.3.1 upgrade (2026.06-ext2) — change analysis & plan

**Status:** in progress since 2026-11-06, on the owner's directive to migrate to A12 2026.06-ext2 and support its new features in every component. This is the concrete instance of the [KERNEL-UPGRADE.md](KERNEL-UPGRADE.md) runbook; the work-item table below is the state carrier across sessions, updated in the same commit as each landing.

Primary sources:
- The upstream migration instructions: `a12-kernel` `6e6b3a6:documentation/dev/index.adoc`, section "2026.06-ext2" (kernel 31.3.0 there, released as 31.3.1).
- The kernel delta `eec9134…6e6b3a6` (31.1.1 → 31.3.1). The sibling's `fixes/2026.06-ext2-fixes` branch carries six build and documentation commits on top; source reads use its head `6363abf`, extracted with `git archive` because the sibling's working tree stays on 31.1.1 `main`.

## Bottom line

A **model-format migration with four new authoring members**, not a pin bump. The document-model format moves from 29.4.0 to **29.8.0** in four steps, each adding an optional member:

| DM step | ticket | change |
|---|---|---|
| 29.5.0 | A12K-4095 | `Computation.errorCode`: the generated rule's code, falling back to the computation's name |
| 29.6.0 | A12K-4329 | `changeNote` on described model objects |
| 29.7.0 | A12K-2361 | `Rule.filterForPossiblyCausingEntities` |
| 29.8.0 | A12K-4372 | `Computation.errorCodesToSuppress` is rewritten into the free-text `additionalParserInformation` preamble (`["A","B"]` → `@SuppressWarning(A,B)`); `IComputation#getErrorCodesToSuppress()` is deprecated |

Behaviour changes beside the format: relative paths may climb to the model root (A12K-4275, with a backward migration rewriting them to absolute paths, A12K-4647); `youngerThan1900Check` is no longer evaluated (A12K-4073); sort-field `order` became non-null; a duplicate type-definition id leaves no model; several converter and message sentences changed. JDK stays 21.

## Work items (the state carrier)

| id | item | status |
|---|---|---|
| B1 | Pin to 31.3.1 / 2026.06-ext2, `ModelFormat.VERSION` 29.8.0, compile fixes, fixture stamping | done 2026-11-07 |
| B2 | Guardian realignment (two-way inventories) + pointer `toString` | done 2026-11-07: the parse-trace model names decisions `rule#k`, since 31.2's `preamble` rule renumbered the ATN |
| Y | `youngerThan1900Check` deprecated and unevaluated (A12K-4073) across reader, writer, checker, interpreter, CLI | done 2026-11-07 |
| M1 | Source-oracle guardians run against the 31.3.1 tree via `-Da12.kernel.dir` / `-Da12.kernel.mm.dir` | done 2026-11-07 (environmental) |
| M2 | New kernel codes, slots and productions classified: `MVK_ERROR_INVALID_FIELDTYPE` (unreachable, `ComputedFieldToleranceKindLawsTest`), the three new `IRule`/`IComputation` slots, the `preambleSnippet` production | done 2026-11-07: the code, the slots (`SlotParityTest`), the production (`GrammarProductionLedgerTest`) and the new `MVK_TOO_MANY_PARENT_GROUPS` emit site, all guardians green against the 31.3.1 trees |
| M3 | Sort-field `order` non-null: readers, writers, checker and law fixtures | done 2026-11-07 ([KF680](KERNEL-FINDINGS.md#kf680)): a sort entry is read only with its `name` and an `order` bound by name or ordinal (the shared `:predicates` binding, used by the checker and both modelkit readers); an ordering member landing on a group draws its own code; an empty sort name draws `RK_SORT_FIELD_EMPTY` |
| M4 | Relative paths reaching the model root (R, A12K-4275): `MVK_TOO_MANY_PARENT_GROUPS` → `MVK_INVALID_ROOT_GROUP` and the message-parameter walk | done 2026-11-07 ([KF679](KERNEL-FINDINGS.md#kf679)): the model root is a turning point in the checker's condition, message and group-message walks; interpreter and both read-back backends already agreed; the `checker-near-miss` replay fixtures restamped to 29.8.0 (B5's replay item); the `MVK_TOO_MANY_PARENT_GROUPS` emit-site row went with M2 |
| M5 | Computation members: `errorCode`, `additionalParserInformation` (suppression), the computed-field "not found" sentence | done 2026-11-07: the sentence (M5a), the preamble (M5b) and `errorCode` (M5c, [KF678](KERNEL-FINDINGS.md#kf678)) across builder, draft, writer, adapter, CLI, checker and interpreter; the enumeration-calculation code is [IG187](INTERPRETER-GAPS.md#ig187) |
| M6 | A duplicate type-definition id leaves no model (X) | done 2026-11-07 ([KF647](KERNEL-FINDINGS.md#kf647)): unchanged except A12's message, now "Duplicate Typedef for id '…'"; no production code matched the old one |
| M7 | Computation member order in written models | done 2026-11-07: `errorCode` and `additionalParserInformation` sit where A12's serializer writes them (`ElementMemberOrderTest`) |
| M8 | Sentence, frame and runtime-conversion changes (X/T): converter and message sentences, `VersionChecker`, parse trace, number cap | done 2026-11-07: missing locales read as none ([KF650](KERNEL-FINDINGS.md#kf650)); an empty reference beneath an empty-named root leaves no model ([KF652](KERNEL-FINDINGS.md#kf652)); the runtime Number text is capped at fifteen digits, half up ([KF681](KERNEL-FINDINGS.md#kf681), interpreter [IF464](INTERPRETER-FINDINGS.md#if464)) |
| B4 | The four new members as typed, CLI, checker and interpreter capabilities (`errorCode`, `additionalParserInformation`, `filterForPossiblyCausingEntities`, `changeNote`) | the first two shipped under M5; the rest is partitioned below. `changeNote` is admitted on every element and survives every `ModelDocument` and `EditSession` write (measured 2026-11-07) |
| B4a | The filter in the portable checker: its reader refusals, the two `CUSTOM_REF_…_DUPLICATE` classes and the two `MVK_REF_ERROR_ENTITY_…_NOT_REFERENCED` classes; corrects KF61 and both ledger rows, which classed the codes unreachable from stored DM-JSON | done 2026-11-07 ([KF682](KERNEL-FINDINGS.md#kf682)): `CheckFamily.POSSIBLE_CAUSE_FILTER`, A12's withholding gates included; the 1 001-entry capacity sentence is deferred as DG182 |
| B4b | The interpreter's possibly-causing entities, grouped and filtered as A12's `IMessage` reports them | open |
| B4c | Typed, read and CLI surface for the filter and `changeNote` | kits done 2026-11-07: `Rule.possibleCauses` takes handles and derives A12's entry through the shared `:predicates` spelling, `RuleInfo.possibleCauses()` reads it, the writer places it last and omits an empty list, and `EditSession` edits keep it with every `changeNote` (`EditSessionUnmodeledMemberTest`); the CLI too: `rule add` and `apply` take `possibleCauses` by absolute path, `rule read` returns them, and the two `NOT_REFERENCED` codes carry a corrective (`RulePossibleCausesCliTest`); a typed `changeNote` is open |
| B5 | Re-capture: corpus, native metadata, `operators.json` `verifiedAgainst`, perf anchors, interpreter measurement | open; the `checker-near-miss` replay fixtures were restamped under M4, `verifiedAgainst` and the conformance-frontier admission digests with the landing. The landing moved all 15 perf scenarios to version 5: the 29.8.0 stamp and the retired pre-1900 fingerprint entry reproduce the version-4 hashes exactly, so no lane changed what it measures, but every anchored key is orphaned until both runner classes are re-anchored |
| B6 | a12-kernel-lean update letter (outbound, per [A12-KERNEL-LEAN-HANDBACK](A12-KERNEL-LEAN-HANDBACK.md)) | open |
| B7 | Simplification pass, cold review, growth record | open |

**Landing.** Every commit must be green, and the pin bump turns 64 tests red across 43 classes (measured 2026-11-07, root + cli + adapter on the 31.3.1 build), so B1, B2, Y and M1–M8 land together once the estate is green; B4 and B5 may follow as their own commits.

## Mechanism batches (failing classes on 2026-11-07)

| batch | owning classes |
|---|---|
| M2 | `MvkLedgerTest`, `SlotParityTest`, `GrammarProductionLedgerTest`, `KernelVersionTest` |
| M3 | `GroupIndexAndSortReferenceLawsTest`, `GroupOrderingFieldTypeLawsTest`, `GroupOrderingMultipleDefectLawsTest`, `CheckerOrderingNameSentenceAgreementTest`, `CheckerDeclarationWithholdingAgreementTest`, `ModelInputFailureTest` |
| M4 | `ConditionReferencePathLawsTest`, `PathSegmentWalkLawsTest`, `MessageGroupParentBoundaryAgreementTest`, `MessageGroupWalkAgreementTest`, `ComputationDeclaringGroupCliTest` |
| M5 | `ComputationSuppressionLawsTest`, `ComputationSuppressionCliTest`, `ComputationMemberVerdictLawsTest`, `ComputedFieldTargetKindLawsTest`, `ErrorFieldRelativePathLawsTest`, `KernelExpansionBuildFailureTest`, `CheckerComputedPathAgreementTest` |
| M6 | `WorkspaceTypeDefinitionCollisionKernelTest`, `CheckerBasicSentenceOrderAgreementTest` |
| M7 | `ElementMemberOrderTest` |
| M8 | `CheckerBasicSentenceAgreementTest`, `CheckerConverterSentenceAgreementTest`, `CheckerModelFrameAgreementTest`, `CheckerEmptyMemberAgreementTest`, `MessageGroupArgumentAgreementTest`, `MessageGroupEventAgreementTest`, `MessageNamePositionAgreementTest`, `MessageParameterMembershipLawsTest`, `ModelHeaderSlotLawsTest`, `GroupOperandAndKeyResolverClassLawsTest`, `StarredNonRepeatableReachLawsTest`, `CheckOnlyFrameRuntimeDiffTest`, `AggMalformedDiffTest`, `NestedGroupFillCountLawsTest`, `NestedOverLimitAbsorptionDiffTest`, `KernelProbeRuleRaiseTest` |
| B5 | `InterpreterMeasurementCurrencyGuardTest` |

Each batch gets its acceptance matrix (row, oracle, predicted outcome, discriminator) before its first run, per the [witness-grid procedure](TESTING-SPEC.md#witness-grid-design). A changed kernel verdict is a measured fact: it amends the owning [KERNEL-FINDINGS](KERNEL-FINDINGS.md) entry in place and updates the checker or interpreter where an agreement test now differs.
