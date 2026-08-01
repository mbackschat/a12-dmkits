# Preventing the defect class external review keeps finding — proposal

> **Status.** PROPOSAL, 2026-08-01, awaiting owner review. Nothing here is built. Written after a second external review round found five defects in work that was green, targeted-tested, and reviewed once already; the owner's instruction was to establish measures against the class rather than fix the instances, **via code guards or tests, not prose**.
>
> **Scope.** The mechanisms by which a defect survives *our own* verification. Not: a general quality programme, not process, not review cadence. Every measure below is an executable artifact.

## 1. The class, from evidence

Eleven defects found by external review across two rounds on this work series. Each is classified by **the mechanism that let it through**, not by what it broke — because the mechanism is what a measure can address.

| # | defect | mechanism |
|---|---|---|
| 1 | `A12PointerFormat.format` emits `Order[0]/Count` and `Order[-7]/Count`, both of which `parse` rejects | M1 |
| 2 | `pointers.ts` passes `[...repetitions]` unvalidated to the bridge, where `model.ts`/`documents.ts`/`limits.ts` all validate | M1 |
| 3 | three `ConsistencyEvidence` witnesses were forgeable records | M1 |
| 4 | `emitMutation` validated the write record *after* performing the write | M1 |
| 5 | `InterpreterAbiGoldenDepthTest` discards every `$` name, so it cannot catch the nested-type regression its own commit advertises | M2 |
| 6 | `GapLedgerTemplateGuardTest` checks that a gap row *has* an id, not that the id is unique or unissued | M2 |
| 7 | four `examples/` demos captured a `jq` error as expected output; `showboat verify` proved capture *stability*, which a captured error satisfies | M2 |
| 8 | `CatalogExamplesAreKernelValidTest` filters before generating, so a missing example produces no test to skip | M2 |
| 9 | "strictly additive, 0 removed" — the KLIB dump has 970 additions and 55 removals; "zero member lines" — it had 72 enum entries; "every nested public type" — four `$DefaultImpls` were already present | M3 |
| 10 | `profile compare`'s description advertised `valid:false` a phase after `valid` stopped being emitted | M3 |
| 11 | the packed npm consumer accepts `1.5`, `NaN`, `Infinity` as repetitions; no Kotlin test can see this, because Kotlin/JS erases `Int` | M4 |

Four mechanisms, and they are independent — a measure against one does nothing for the others.

## 2. The four mechanisms and the measure for each

### M1 — an invariant with several entry points, enforced at some of them

The invariant is written as a statement *inside one function*, so it binds that function's callers and nothing else. A peer entry point on the same domain shares none of it, and no compiler or test notices.

Measured instance: the repetition-domain rule lives in `A12PointerFormat.parse`'s loop. `format` is a peer on the same domain and has only an arity check. The test file makes the gap visible four lines apart — `writesTheWildcardAsBracketZero` asserts `format` **emits** `[0]`, `rejectsAWildcardThatIsNotTheLastPart` asserts `parse` **refuses** it — and never asks whether the two agree.

**Measure M1a — the invariant moves into the type whose domain it defines.** `A12Pointer`'s constructor already rejects a length mismatch; it takes the whole domain rule, and `format` constructs an `A12Pointer` rather than joining strings. Then there is exactly one enforcement site and no entry point can bypass it. This is the project's existing "make illegal states unrepresentable" rule applied to the value rather than the function.

```kotlin
data class A12Pointer(val names: List<String>, val repetitions: List<Int>) {
    init { requireLegalRepetitions(names, repetitions) }   // the ONLY site
}
fun format(path: String, repetitions: List<Int>): String =
    render(A12Pointer(splitNames(path), repetitions))       // cannot bypass the init
```

**Measure M1b — an exhaustive agreement test, not examples.** For a family of entry points over one enumerable domain, assert agreement over the *whole bounded domain* rather than over hand-picked cases:

```kotlin
@Test fun formatAndParseAdmitExactlyTheSameRepetitions() {
    for (arity in 1..3) for (position in 0 until arity) for (rep in -8..8) {
        val reps = List(arity) { if (it == position) rep else 1 }
        val formatted = runCatching { A12PointerFormat.format(path(arity), reps) }
        val reparsed = formatted.mapCatching { A12PointerFormat.parse(it) }
        assertEquals(formatted.isSuccess, reparsed.isSuccess, "asymmetric at $reps")
        if (formatted.isSuccess) assertEquals(reps, reparsed.getOrThrow().repetitions)
    }
}
```

102 cases, no hand-picked denominator, and it fails today. **Applicability is narrow by construction** — it needs a bounded enumerable domain — so the general form of M1 is M1a, and M1b applies where the domain permits it.

### M2 — a guard whose scope excludes the defect it advertises

A guard is written *after* the fix, from the fix's shape, so it asserts what is now true instead of what could go wrong. The six vacuity shapes already in [TESTING-SPEC](TESTING-SPEC.md) are this mechanism; #5 and #6 above are two more, found after that table was written, which is the evidence that documenting the class does not prevent it.

**Measure M2a — every scanning guard ships a positive control, and a meta-guard enumerates them.** A positive control feeds the scanner the shape it exists to reject and asserts it is seen. Two exist by hand already (`VerbDemonstrationTest.theClaimScannerRecognisesTheFormThatWentStale`, `InterpreterAbiGoldenDepthTest.theBlockScannersRecogniseAMemberlessDeclaration`); the measure makes them required and enumerated rather than remembered.

**The denominator is the load-bearing design decision, and a naming convention will not do.** `*GuardTest` matches **18** classes (measured, excluding `/build/` *and* `/.claude/worktrees/`), but the scanning guards that found real defects this series — `WitnessTypesAreUnforgeableTest`, `VerbDemonstrationTest`, `CliConformanceMatrixTest`, `DemoOutputIsRealTest`, `CommandReferencesResolveTest`, `InterpreterAbiGoldenDepthTest` — match none of them. Proposed denominator instead: **the test classes run by a Gradle task that declares repo files or goldens as inputs.** The project already requires exactly that wiring for a different reason (a broad-task input declaration causes false greens), so the two rules reinforce and the source is live rather than authored.

**Measure M2b — a positive control is a `@Test` that would fail if the scanner regressed, never a comment.** The control asserts on the scanner's *output for known-bad input*, so it survives a rewrite of the scanner's internals.

### M3 — a published claim with no oracle

Prose is cheap to write and nothing executes it. Both instances are claims about an artifact a guard already reads: the ABI goldens, and a verb's envelope.

**Measure M3 — a claim about a guarded artifact's shape becomes an assertion in that guard, and the doc points at the guard.** The ABI depth facts belong in `InterpreterAbiGoldenDepthTest`'s assertions and failure messages, not in `INTERPRETER-RELEASE-SPEC` prose. Where a number must appear in prose for a reader, it names the command that recomputes it. This is the same single-sourcing lever that makes `manifest` and `schema <target> <op>` unable to drift, applied to claims about goldens.

Worked example of what the assertion buys: the nested-type sets across the two goldens are **JVM 55** and **KLIB 72**, which reconcile to **51 = 51** once four JVM-only `$DefaultImpls` (a Java interface artifact with no KLIB counterpart) and 21 KLIB enum entries are excluded. That reconciliation is a cross-golden equality a test can assert and a sentence can only claim — and asserting it closes #5, because losing nested types from either dump breaks the equality.

### M4 — verification that never touches the artifact a consumer receives

Our verification reads *source* and runs tests over inputs we chose. A consumer receives a *packed artifact* and supplies inputs we did not choose. #11 is invisible to every Kotlin test that could ever be written, because the type system enforces `Int` at the only layer we test.

**Measure M4 — each published surface gets one adversarial gate on the packed artifact, over the hostile input domain.** The gate already exists (`consumer-tests/typescript`, `consumer-tests/browser`); what is missing is that it only ever supplies well-formed input. The measure is a required hostile-input case set per exported entry point that accepts a primitive: non-finite, non-integral, negative zero, out-of-range, wrong arity, and the wrong primitive type entirely. For the four inbound numeric sites on the JS bridge (`pointer`, `relevantPointer`, the custom-field `minLength`/`maxLength`, `formatA12Pointer`) that is a bounded, enumerable obligation.

## 3. Required, optional, excluded

**Required** — the four measures above, each as an executable artifact: M1a (domain rule in the constructor) with M1b where the domain is enumerable; M2a+M2b (positive controls with a task-derived denominator); M3 (shape claims as assertions); M4 (hostile-input cases on the packed artifact).

**Optional** — extending M1b to other entry-point families beyond pointers; a convention for citing the recomputing command beside every count in prose.

**Excluded, with reasons.** A general prose↔behaviour comparator: measured as needing a design ([CG1](CLI-CONFORMANCE-GAPS.md#cg1)), and a regex that overreaches gets weakened rather than obeyed. Coverage thresholds and mutation testing: they measure whether a line *ran*, and every defect in the table above ran under a green test — #7's demo executed on every CI run and captured its own error as the expected output. Reviewer cadence: this proposal exists because review is expensive and late, not to schedule more of it.

## 4. Feasibility risks and recall limits

| risk | statement |
|---|---|
| M2a's denominator | a scanner reading a repo file *without* declaring it as a task input is invisible to the meta-guard. That wiring is already required for a different reason, but the recall limit is real and must be stated in the guard's own failure message rather than assumed away |
| M1a's blast radius | moving the domain rule into `A12Pointer`'s constructor makes construction fail where it previously succeeded. Whether the resulting domain is the right *published* domain is a separate, open owner decision ([IAG10](INTERPRETER-API-GAPS.md#iag10)) — this measure makes the domain single-sited, it does not choose it |
| M1b's scope | needs a bounded enumerable domain. It does not generalise to string or structural domains, and claiming otherwise would be the M2 mechanism applied to this proposal |
| M4's cost | packing is already a gate; the incremental cost is the new cases. The gate must run on the *packed* artifact — running the same cases against source would reproduce exactly the blindness it exists to remove |
| M3's boundary | it applies to claims about artifacts a guard already reads. A claim about kernel behaviour is not in scope here; that is the existing oracle-by-claim-kind rule |

## 5. What acceptance means

Each measure is accepted when it **fails against the real pre-fix artifact**, not against a synthetic fixture — the standard the guards added earlier in this series were held to. For M1b that is the current `format`; for M2a, a guard with its positive control deleted; for M3, the pre-merge `interpreter.api`; for M4, the packed consumer as published today.
