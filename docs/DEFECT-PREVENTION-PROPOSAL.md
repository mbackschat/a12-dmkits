# Preventing the defect class external review keeps finding — proposal

> **Status.** PROPOSAL, **revision 2**, 2026-08-01, awaiting owner review. Nothing here is built.
>
> **Revision 2 exists because revision 1 reproduced the defect class it proposed to prevent.** Review found that three of its four measures carried the same denominator and vacuity defects: one loop was called *exhaustive* while covering 99 of 5,219 vectors, one denominator was justified by six examples of which it contains three, one equality check stayed green under the symmetric regression it was meant to catch, and one site inventory misclassified a boundary direction. The measures are revised below and §1 names the generator that produced them, because a revision that only patched the four measures would fail the same way a third time.
>
> **Scope.** The mechanisms by which a defect survives *our own* verification. Not process, not review cadence, not a quality programme.

## 1. The generator: verification artifacts are exempted from the verification standard

Revision 1's own defects are one act repeated: **a property of a verification artifact I had designed was asserted without being executed or computed.** Not "the code went untested" — the *claims about the tests* were the untested part.

| revision 1 claimed | the computation it skipped | truth |
|---|---|---|
| the agreement loop is "exhaustive" | its vector count against the domain's | 99 distinct vectors of 5,219 |
| the Gradle-task denominator covers the motivating scanners | the intersection | 3 of 6 |
| `51 = 51` catches nested-type loss | which regressions preserve equality | any symmetric one |
| four inbound numeric bridge sites | each site's direction | 1 of the 4 is outbound; 4 further sites missed |
| "459 cases" | 17+34+51 | 102 |
| "`GapLedgerTemplateGuardTest` enforces both halves" | running it | it enforces neither; the guard was unwritten |

Production code in this repo is held to *run it and read the output*. Guards, and proposals about guards, were held to *it looks right*. That asymmetry is the generator, and it is self-similar: a guard is one level out from production, a proposal about guards is two, and the standard weakened at each step. It also explains why the six vacuity shapes already documented in [TESTING-SPEC](TESTING-SPEC.md) did not prevent shapes seven and eight — a documented class is prose, and prose about verification is exactly what the exemption protects.

**The rule that follows, and the one this document is the first test of: a claim about a measure is a claim, and gets a claim's oracle.** A set claim needs the computed set. A coverage claim needs the computed ratio. A "this catches X" claim needs the red run. §7 lists every quantitative claim below with the command that produced it, so the claim is auditable rather than trusted.

## 2. One structural pattern for every measure

Each measure below needs a denominator, and revision 1 hand-wrote three of them wrongly. The project already has a shape that works, in the two guards that have held: `WitnessTypesAreUnforgeableTest` derives the witness set from `ConsistencyEvidence`'s own method signatures and then requires each member to be *either* closed *or* listed in `CANNOT_BE_CLOSED` with a reason; `SchemaCommand.CONTRACT_EXEMPT` does the same for verbs without a schema pointer.

**The pattern: completeness is DERIVED from a live artifact; the per-element judgment is AUTHORED; an unclassified new element fails the build.**

Derivation alone is not enough — direction, intent and known-bad samples cannot be computed from source. Authoring alone is not enough — that is how all three revision-1 denominators went wrong. The split is what makes the obligation total: a new element cannot appear without forcing a decision, and the decision is visible in a diff.

Every measure in §3–§6 is an instance of this pattern, and each states **what is derived, what is authored, and what fails on omission**. Where a measure cannot achieve derived completeness, §6 says so instead of pretending.

## 3. M1 — representation validity: the domain rule belongs to the type

**Revision 1 grouped three unrelated things here.** Review is right that a closing constructor addresses only *locally decidable representation validity*. It does not establish **provenance** — a validating public constructor can still forge "kernel confirmed", which is why `ConsistencyEvidence` needs closed witness types, a measure that already shipped. And it does not establish **ordering** — "an incomplete write leaves no destination" is a temporal property needing the assertion that already shipped in `emitMutation`. M1 is therefore only the first of the three, and the other two are done, not proposed.

### The measured defect

`A12PointerFormat.parse` holds the repetition-domain rule inside its own loop; `format` is a peer entry point on the same domain with only an arity check. Four asymmetries follow, all confirmed by reading the current source (byte-unchanged since `3d54df91`):

| input | `format` emits | `parse` on that output |
|---|---|---|
| `format("Order/Count", [0,1])` | `Order[0]/Count` | rejects — `[0]` is legal only on the last part |
| `format("Order/Count", [-7,1])` | `Order[-7]/Count` | rejects — only `-5` is legal |
| `format("Order[2]/Count", [3,1])` | `Order[2][3]/Count` | rejects — not a valid path part |
| `format("", [])` | `""` | returns a pointer with zero parts |

Rows 3 and 4 are review's finding and my own follow-up: **the constructor rule must cover part NAMES and arity, not only repetitions**, or the measure leaves two of the four live. Revision 1's sketch validated repetitions only, and its test generated legal `path(arity)` strings, so it could not have found either.

### The measure

The domain moves into `A12Pointer`'s constructor — names, arity and repetitions — and `format` constructs one rather than joining strings, so no entry point can bypass it. The constructor also **snapshots** both lists: today it stores the caller's, so a consumer can construct a valid pointer and mutate it illegal afterwards (`parse` snapshots, the constructor does not — a third asymmetry in the same type).

```kotlin
data class A12Pointer(val names: List<String>, val repetitions: List<Int>) {
    init { requireLegalPointer(names, repetitions) }        // names + arity + repetitions, the ONLY site
    // stores immutable snapshots, so a valid value cannot be mutated illegal after construction
}
```

### The agreement test, and why agreement is not enough

Full Cartesian over arities 1–3 and repetitions `-8..8` is **5,219** vectors (`17 + 17² + 17³`), which runs in well under a second — so there is no reason to sample. Revision 1's single-coordinate loop covered **99** distinct vectors and would miss any defect needing two exceptional coordinates, `[-5,0]` and `[-7,-7]` among them.

**But agreement proves consistency, not correctness** — two entry points can agree on a wrong hybrid domain, and today's domain *is* the disputed one ([IAG10](INTERPRETER-API-GAPS.md#iag10)). So the agreement test is the *symmetry* lock, and it needs a second, independent oracle for the *domain*: the kernel's own pointer type, reachable in `:adapter` test scope, which is where this project's client-side-refusal rule already requires a measured kernel basis. Two assertions, two different questions:

- **symmetry** (`:interpreter`, 5,219 cases) — `format` and `parse` admit exactly the same set, and `parse(format(v)) == v` for every admitted `v`.
- **domain** (`:adapter`, kernel oracle) — the admitted set is what A12 admits, with every deliberate deviation named and reasoned.

Derived: the 5,219-vector domain, and the kernel's verdict. Authored: the bound `-8..8` and arity `1..3`. Fails on omission: a new repetition sentinel that neither entry point rejects shows up as an asymmetry or a kernel disagreement.

## 4. M2 — a guard that cannot fail: a marker, not a task

**Revision 1's denominator is withdrawn.** It proposed "test classes run by a Gradle task declaring repo-file inputs" and justified it with six scanners. Computed: `docGuardTest` runs **11** classes and contains **3** of the 6 — `DemoOutputIsRealTest`, `CommandReferencesResolveTest`, and (in its own task) `InterpreterAbiGoldenDepthTest`. The other three — `WitnessTypesAreUnforgeableTest`, `VerbDemonstrationTest`, `CliConformanceMatrixTest` — read no repo file: they reflect over classes or drive the CLI in-process, so they run in the ordinary suite and the denominator excludes half its own motivation. Under the broad reading every `Test` task has class directories as inputs, which makes the denominator the whole estate. And a *task* cannot say which of 11 classes is a scanner, nor which method is its control.

### The measure

A **semantic marker** on the class, plus a generic meta-guard that exercises it. The marker carries the two samples, because a known-bad sample is authored by nature:

```java
@ScanningGuard(knownBad = "…the shape this guard exists to reject…",
               knownGood = "…the same shape, corrected…")
class DemoOutputIsRealTest { static List<String> audit(String sample) { … } }
```

The meta-guard reflects over every `@ScanningGuard` class and asserts, generically, that `audit(knownBad)` is non-empty and `audit(knownGood)` is empty. This is stronger than "a method named X exists", which revision 1 would have settled for: it tests the scanner's *behaviour on known input*, so it survives a rewrite of the scanner's internals and fails if the scanner is later weakened.

It also imposes a design rule that is the real content of this measure: **a scanning guard must expose its scan as a pure function of its input.** Two already do (`GapLedgerTemplateGuardTest.audit`, `VerbDemonstrationTest.claimedEnvelopeKeys`); the ones that inline the scan into the `@Test` body cannot be positively controlled at all, which is why #5 and #6 in the defect table were possible.

### The recall limit, stated rather than hidden

**Completeness of the scanner set is NOT derivable, and this measure does not claim it.** An unannotated scanner is invisible to the meta-guard. What is derived is that every *marked* scanner has working samples, and what the marker buys is that adding a scanner without them is a visible omission in review rather than an invisible one. Revision 1's failure was claiming derivation it did not have; the honest version claims less and delivers it.

Derived: the marked set, and each member's behaviour on its samples. Authored: the marker and the two samples. Fails on omission: a marked class whose scanner no longer distinguishes the samples.

## 5. M3 — a claim with no oracle: narrowed, and two-sided

**Revision 1's `51 = 51` is vacuous under symmetric regression**, as review computed: if one shared ABI-filter change drops the same nested declaration from both goldens, `50 = 50` stays green, and dropping every common nested type preserves equality entirely. Hard-coding `51` only relocates the problem into an authored denominator that must change on legitimate API growth.

### The measure

Two assertions against two independent sources, because the two failure directions are different:

- **asymmetric loss** — the nested-type sets of the two goldens are equal after two documented exclusions. Measured today: JVM **55**, KLIB **72**, reconciling to **51 = 51** once four JVM-only `$DefaultImpls` (a Java interface artifact with no KLIB counterpart) and 21 KLIB enum entries are excluded.
- **symmetric loss** — the JVM golden's nested set is compared against the nested public types **reflected from the compiled artifact**, which no ABI filter touches. This is the third source the equality lacked, and it is an established pattern here: `PublicApiGuardTest` already reflects over the published classpath for the Java kits.

The existing derivation of engine subpackages from the source tree (`interpreter/build.gradle`, which already fails loudly if it finds none) is the third leg, guarding the *cause* rather than the symptom.

### What M3 no longer claims

**A historical diff count is not a current-artifact shape, and no guard reading the current golden can validate one.** "970 added / 55 removed" is a property of a *diff*; the honest home is a named command that regenerates it, never a frozen assertion. Revision 1's §5 also named the wrong artifact — the false removal claim is about `interpreter.klib.api`, not `interpreter.api`, and validating it needs both sides of the diff.

And the general ambition is **withdrawn**: a new prose claim can still omit its assertion, so without a mechanically enforced citation convention M3 is a procedure, not a class-wide measure. It is scoped to *shape claims about artifacts a guard already reads*, and it says so.

Derived: both nested sets, and the reflected set. Authored: the two exclusions, each with its reason. Fails on omission: a new nested type absent from either golden, in either direction.

## 6. M4 — the packed artifact: right oracle, wrong inventory

The packed-consumer gate is the one part of revision 1 review endorsed outright, and it is the only oracle that can see `1.5`, `NaN` and `Infinity` crossing into `Array<Int>`. Two corrections.

**The inventory was wrong.** Revision 1 named four inbound numeric bridge sites. Computed from `typescript.api` — 33 lines mention `number`, which classify exhaustively as **20 inbound members across 7 declaration sites** and 13 outbound:

| inbound site | numeric members |
|---|---|
| `A12PointerFormat.format(path, repetitions)` | 1 |
| `PreparedModel.pointer(path, coordinates)` | 1 |
| `RelevantCoordinate = number \| "ALL"` | 1 |
| `DocumentDecodeLimits` | 7 |
| `ModelPreparationLimits` | 8 |
| `A12DocumentField` | 1 |
| `DocumentInputPathSegment` | 1 |

Of revision 1's four, three are here and one — the custom-field `minLength`/`maxLength` — is **outbound**: it travels Kotlin→host inside `CustomFieldValidationParameters`, so it needs no consumer-input validation. Revision 1 also missed 4 sites and 17 members, most of them the two consumer-supplied limits interfaces. 20 inbound + 13 outbound = 33 reconciles against the raw count, which is the check that the classification is complete.

**Nothing forced a new entry point to acquire hostile cases.** A bridge function could be added to `typescript.api` with no test and the gate stayed green — the same hole as everywhere else. The measure: enumerate every `number`-bearing declaration from `typescript.api` (mechanical) and require each to be classified **INBOUND** with a hostile-case set or **OUTBOUND** with a reason, the build failing on an unclassified one.

The hostile set per inbound member: non-finite, non-integral, negative zero, out-of-range, wrong arity, and the wrong primitive type entirely.

One overstatement withdrawn: revision 1 said no Kotlin test "can ever" see malformed numbers. Kotlin/JS can construct them via `dynamic`/`unsafeCast`, so the claim should be that the packed consumer is the correct *release* oracle — not that Kotlin is blind.

Derived: the `number`-bearing declaration set from the golden. Authored: each declaration's direction and its hostile cases. Fails on omission: a new numeric declaration with no classification.

## 7. Every quantitative claim above, and what computed it

The §1 rule applied to this document. Each row is reproducible.

| claim | command |
|---|---|
| 5,219-vector Cartesian; revision 1's 99 distinct of 102 iterations | arithmetic on the loop bounds, shown inline |
| `docGuardTest` runs 11 classes, 3 of the 6 motivating scanners | `awk '/tasks.register\(.docGuardTest./,/^}/' cli/build.gradle \| grep -c includeTestsMatching` + set comparison. **The block scope matters**: the unscoped `grep -c` over the whole file returns 16, counting other tasks' filters — writing this row is what caught it |
| `*GuardTest` matches 18 classes | `find . \( -name '*GuardTest.java' -o -name '*GuardTest.kt' \) \| grep -v /build/ \| grep -v /worktrees/` — the worktree exclusion matters, an unanchored version returns 36 |
| nested types JVM 55, KLIB 72, reconciled 51 = 51 | the two golden greps in §5, plus `comm -23`/`comm -13` on the sorted sets |
| ABI diff KLIB +970/−55, JVM +268/−0 | `git diff --numstat fd3e3f1c^ fd3e3f1c -- interpreter/api/` |
| 20 inbound / 13 outbound numeric members reconciling to 33 | `grep -c number interpreter/api/typescript.api` + per-line classification |
| the four `format`/`parse` asymmetries | read from `A12PointerFormat.kt`, byte-unchanged since `3d54df91`; each is an executable case in the proposed test |

## 8. Required, optional, excluded

**Required** — the four measures as revised: M1 (constructor owns names+arity+repetitions and snapshots; 5,219-case symmetry test; kernel domain oracle), M2 (marker + generic sample meta-guard + the pure-function design rule), M3 (two-sided nested check, narrowed scope), M4 (classification derived from `typescript.api`, hostile cases per inbound member).

**Optional** — extending the symmetry pattern to other entry-point families; a citation convention for counts in prose.

**Excluded, with corrected reasons.** A general prose↔behaviour comparator: needs a design ([CG1](CLI-CONFORMANCE-GAPS.md#cg1)), and an overreaching regex gets weakened rather than obeyed. Coverage thresholds: every defect in the table ran under a green test. **Broad mutation testing — excluded on scope, cost and mutation-operator support, NOT on the grounds revision 1 gave.** That rationale was wrong: mutation testing asks whether a behavioural mutation is *killed*, not whether a line ran, and M2's "delete the control, re-run" is itself a targeted manual mutation. The objection to the broad tool is its cost against this estate and its operator coverage for Kotlin/JS and markdown scanners, not its principle.

## 9. Acceptance

Split three ways, because revision 1 conflated them — "a guard with its positive control deleted" is a synthetic mutation of a new artifact, not a real pre-fix artifact, and a scanner's control necessarily feeds synthetic input.

1. **A production measure must fail against the real pre-fix implementation** where one exists: M1 against current `format` (four cases), M4 against the packed consumer as published today.
2. **The M2 meta-guard must reject a real historical class that lacked a control** — the estate supplies candidates, so this is a real artifact, not a fixture.
3. **Each scanner's own control necessarily uses a constructed known-bad input**, and that is correct at that level rather than a weakness.

## 10. Open decisions this proposal does not take

- **The pointer domain itself** ([IAG10](INTERPRETER-API-GAPS.md#iag10)) — M1 makes the domain single-sited and kernel-oracled; it does not choose whether the published type is A12's exact or partially-known one. That gates npm publication and is the owner's.
- **The issued-id registry surface.** Review is right that the "Retired ids" sections added to two live ledgers conflict with the standing `-GAPS` lifecycle ("No shipped list, ever"). The registry is still the right idea and its guard is still unwritten; it belongs on **its own surface**, not as shipped-history rows inside live gap ledgers. Correcting that is separate work, tracked rather than folded in here.
