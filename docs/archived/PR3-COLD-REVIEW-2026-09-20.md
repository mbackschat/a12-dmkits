# PR #3 — cold review record

**PR:** [mbackschat/a12-dmkits#3](https://github.com/mbackschat/a12-dmkits/pull/3) — "Promote and publish the duplicate/overlap parameter rule", author Alexander Ried, opened 2026-09-09.
**Frozen at:** `47e25139d`. **Merge base:** `5473f87a3` (= `origin/main`, 2026-09-08). **Reviewed against local `main`:** `83e5ebfa8` (2026-09-20).
**Size:** 208 files, +17 029 / −1 353, 17 commits.
**Review date:** 2026-09-20. Three read-only cold reviewers on separate estates, plus the session's own verification pass.

> **Disposition, 2026-09-20 (owner): NOT MERGED — closed, and harvested instead.** §0.1/§0.2 are why: seventeen content conflicts against the real `main`, and six call sites that break in files merging *without* conflict, so Gradle would fail at configuration time with nothing in the diff pointing at it. Taking the PR's side on `ci.yml` would additionally have dropped `verifyInterpreterPurity` — the EUPL licence-boundary gate — from CI. Three things are worth re-landing as fresh commits on `main`: the seventeen `KF10xx` kernel findings, the 27 trigger/control claim probes, and the gate-mutant sweep tooling. The Kotlin 2.2 → 2.4 bump the PR carried undisclosed (§0.3) was performed separately and landed as `a31dd1eef`, so it is off the harvest list. The measured payoff for `:consistency` in §3b is filed against `MVK_DUPLICATE_PARAM1`/`PARAM2` in [`../MVK-LEDGER.md`](../MVK-LEDGER.md).

> Status of each claim below is marked: **[verified here]** = the session re-ran the check itself; **[reviewer]** = reported by a reviewer and not independently re-run. Nothing in this file is a run of the PR's test suite — no lane was executed against `47e25139d`.

---

## 0. Session-verified findings (re-run independently)

### 0.1 The PR is built against a `main` that no longer exists here **[verified here]**

GitHub reports `MERGEABLE` / `CLEAN`, but that is measured against `origin/main` = `5473f87a3`. Local `main` is **686 commits ahead** of the published tip (expected — pushes are batched here). Against the real `main`:

```
git merge-tree --write-tree --name-only main pr-3-review   # → 17 CONFLICT (content) files
```

Conflicting: `.github/workflows/ci.yml`, `README.md`, `build.gradle`, `docs/API-FINDINGS.md`, `docs/ARCHITECTURE.md`, `docs/BUILD-ENVIRONMENT.md`, `docs/MVK-LEDGER.md`, `docs/OPERATOR-INDEX.md`, `docs/SEMANTICS-MAP.md`, `docs/TESTING-SPEC.md`, `gradle.properties`, `rulekit/.../dsl/Rule.java`, `rulekit/.../dsl/SumOfProducts.java`, `rulekit/.../validate/StructuralKernelFix.java`, `scripts/README.md`, `src/test/.../GateTierWiringTest.java`, `src/test/.../MvkLedgerTest.java`.

### 0.2 Six call sites break in files that merge WITHOUT conflict **[verified here]**

The PR deletes `ext.guardInputs`, `ext.verifyCompiledPurity`, `ext.routeExclusivelyHostedTests` and `ext.dmkitsPom` from the root `build.gradle` (moving them into the new `build-logic` included build). Modules `main` added since the base still call them, and those files merge silently:

| merged file | orphaned call | defined after merge? |
|---|---|---|
| `consistency/build.gradle` | `rootProject.verifyCompiledPurity`, `rootProject.dmkitsPom` | no |
| `consistency-gate/build.gradle` | `rootProject.verifyCompiledPurity`, `rootProject.dmkitsPom` | no |
| `predicates/build.gradle` | `rootProject.dmkitsPom` | no |
| `adapter/build.gradle` | `rootProject.guardInputs` (added by `dd269918e`, 2026-09-13) | no |

`rootProject.verifyKernelFreeClasspath` survives (still an `ext` closure). Net: five sites need migration plus `id 'a12.build-guards'` in the three new modules. Gradle would fail at configuration time on `:consistency`, `:consistency-gate`, `:predicates` and `:adapter`, with nothing flagging it in the diff.

### 0.3 An undisclosed Kotlin toolchain bump **[verified here]**

`947d8b7c6` bumps **Kotlin 2.2.0 → 2.4.0** (plus `abi-tools`), migrates the ABI-validation extension, rewrites `kotlin-js-store/yarn.lock`, and sets `kotlin.incremental=false`. Consequences on a **published** artifact, none mentioned in the PR's What/Risks:

- `interpreter/api/interpreter.api` loses **32** `public synthetic fun <init>(… DefaultConstructorMarker)V` entries.
- `interpreter/api/npm-package-files.txt` drops `package/internal/a12-interpreter-core.d.ts`. **This one is reasoned and correct** — Kotlin/JS 2.4 emits `.d.mts` natively where 2.2 emitted `.d.ts` and the build renamed a copy; the façade imports via `.mjs` specifiers, which TypeScript resolves to the `.d.mts` sibling. `package/dist/index.d.ts` (the public entry typing) is untouched.
- Forces `testCompileOnly 'org.projectlombok:lombok:1.18.42'` onto `:adapter` (see §0.4).
- Gradle is already 9.0.0 on `main`, so no Gradle bump is entailed. Dokka stays at 2.2.0 (independent product line).

### 0.4 Lombok provenance **[verified here]**

Not a PR invention — it is the kernel's own Lombok leaking through its published bytecode:

- `../a12-kernel/kernel-md/kernel-md-a12internal/src/main/java` — **69 files import Lombok**, **65 × `import lombok.NonNull`**, plus `@Data`, `@Builder`, `@EqualsAndHashCode`, …
- The compiled jar carries **107 `Llombok/NonNull;`** and 72 `Llombok/Generated;` descriptors.
- `kernel-md-a12internal-30.8.1.pom` does **not** declare Lombok → never transitive; `@NonNull` is CLASS-retention, so nothing has ever had to resolve it.
- Jar provenance: `~/.m2/.../kernel-md-a12internal/30.8.1/`, 2 June 2026, **no `_remote.repositories`** (locally installed, not downloaded) and a `.module` file beside the POM (Gradle-published). Source: `../a12-kernel-bootstrap/bootstrap.sh`, which pins `KERNEL_VERSION=30.8.1` / `A12_KERNEL_REF=cb66e51f…` and lists `kernel-md-a12internal` as module 30.
- `adapter/src/main` contains **zero** `.kt` files on base, `main` and the PR branch → `testCompileOnly` is the correct and sufficient scope.
- ~~**Unmeasured:** the *mechanism* … is the PR author's comment.~~ **Measured 2026-09-20**, while performing the bump separately on `main`. The compiler states the mechanism itself rather than leaving it to inference — `CorpusModelPreparer.kt:56:33 Type annotation class 'lombok.NonNull' of the inferred type is inaccessible.` — and the discriminating change is that putting **exactly that annotation** on the Kotlin test compile classpath, with nothing else moved, clears it; a rival "something else about 2.4" account does not predict that. The controlled observation remains as stated: same kernel bytecode, same adapter Kotlin files, 2.2 compiles / 2.4 does not.

### 0.5 `docs/API-GUIDE.md` is textually corrupted on the branch **[verified here]**

Code-fence count: base **194**, `main` **196**, PR branch **199 — odd**. At `docs/API-GUIDE.md:723-731` the paragraph *"A constructed date range is an equality operand as well as a computation value."* appears twice: once in an older form followed by three orphaned Java lines and a **stray closing fence at :729**, then again at :731 with newer content (KF271). Fence parity inverts for the rest of the file. Shipped by the merge resolution; `DocSnippetFreshnessTest` locks snippet *bodies* and cannot see a stray fence between them.

### 0.6 Scope: five independent mechanisms in one PR **[verified here]**

| commit | files | mechanism |
|---|---|---|
| `947d8b7c6` | 35 | build-logic included build **+ Kotlin 2.2→2.4 toolchain bump** |
| `5f04713af` | 9 | release-mirror script refactor + dropping a lane guard |
| `ad256bfb8` | 10 | `KernelCode` typed vocabulary in `core` + modelkit |
| `2e2ab6ba1` + `9cba5b174` | 148 | promoting **three** law families (temporal, iteration, duplicate) into gates + locks |
| `b95dfa4b4` | 20 | the titled feature: duplicate/overlap promotion + publication |

The title and "What" describe only the last. Under the repo's own convergence gate this is at least four partitions.

### 0.7 Things that are fine **[verified here]**

- **KF id chunking works.** The PR allocates `KF1001–KF1015`; `main` continued in `KF2xx–KF4xx`. No collision; `KERNEL-FINDINGS.md` merges additively. (`kf-id-chunks: 1-999 = Martin Backschat; 1001- = Alexander Ried`.)
- **Open points are properly filed**, not buried in the PR body: `E15`/`E16` in `docs/PRECHECK-PROMOTION-PROPOSAL.md`, `DG1002` in `docs/DEV-API-GAPS.md`, each with "why filed rather than fixed" and "what would settle it".
- **No `pull_request` CI trigger** exists on any of the three workflows, so nothing ran automatically on this PR.

### 0.8 Disclosure gap in the PR description **[verified here]**

`docs/PRECHECK-PROMOTION-PROPOSAL.md`'s own tables carry open rows the PR body's "Open points" does not mention: **C1, C2, C4 open**; **C3 "4 of 8 measured, 1 corrected"** (four client-side refusals still without measured kernel basis); B3 "partly measured"; B4 "half measured"; E9 "denominator half landed". Partition D also records that an **independent external-corpus differential found 15 refusals of constructions the kernel admits** (all traced to D1, since landed).

---

## 1. Reviewer A — rulekit / core main-source estate

Read-only; one oracle executed (a standalone `javadoc` run). Everything else source-trace.

### Explicit negatives (checked, nothing found)

- **No non-additive public-API change.** The apparent removals in `api/dmkits-rulekit.api` (`AggregateOperand.renderedPath()`, `ValueListOperand.renderedPath()/fieldPath()`) are *inheritance moves* — both interfaces now `extends DuplicateOperand`, which redeclares them. Source-compatible; binary-compatible under JVMS 5.4.3.4. No parameter narrowing, no newly-sealed *existing* type. Exception **messages** changed (the `(CODE)` suffix moved to `Refusals.message`) — behavioural for a string-matching consumer; the one in-repo consumer was updated.
- **`Computation.Builder`'s dropped regex code-lift loses nothing** — all remaining `require(boolean, String)` messages enumerated (`Computation.java:259,260,271,279` + `assignmentMismatchMessage:401-413`); none names an `MVK_*`/`RK_*` token.
- **`DuplicateGate` arm ORDER is measured**, not assumed — `FirstFilledValueDuplicatePrecedenceLawsTest` pins exact-then-overlap and first-repeat-wins.

### F1 — imports inserted BETWEEN class Javadoc and declaration, 17 files **[verified here]**

`javadoc` emits *"kein Kommentar"* and the class description is **absent from the generated HTML**. Reviewer reproduced it with a minimal case. Session re-ran the sweep: 17 files on the branch match `*/` immediately followed by `^import`; the same sweep over `main` returns **zero**, so it is introduced here.

Affected: `BoundModel`, `Dsl`, `Rule`, `Computation`, `CountAggregate`, `DateConstruction`, `DateListAggregate`, `EnumRef`, `FieldListEntityListCondition`, `FieldListRef`, `FirstFilledValue`, `ListAggregate`, `RepetitionNotUniqueCondition`, `SumOfProducts`, `TemporalOperands`, `TimeConstruction` (all `rulekit/.../dsl/`), plus `modelkit/.../FieldTypeSpec.java`. 15 are public API. Mechanical import-insertion that anchored on the wrong line; latent only because no `withJavadocJar` task exists today. Fix is positional.

### F2 — `ReadPathFormatGateIsolationTest`'s denominator contradicts its own stated rule **[reviewer]**

`GATED_FACTORIES` names **6** entries; `PromotedGateIsReachedGuardTest.GATES` names **21**. The javadoc says the list "has to name those too, or it would guard a shrinking minority of the gated surface." The base-year four are excused with a reason; these are omitted with none: `valueAsDateFirstDay`, `valueAsDateLastDay`, `latestOf`, `earliestOf`, `latestTimeOf`, `earliestTimeOf`, `latestDateTimeOf`, `earliestDateTimeOf`, `numberOfDifferentValues`, `flatten`, `flattenGroup`, `firstFilledGroup`, `firstFilledGroupFor`.

Reviewer ran the grep; the omissions split in two:
- `flatten`, `flattenGroup`, `firstFilledGroup` **are** reached by the read path (`ConditionConverter.java:407,737,1414,1454,1370`; `ComputationModelSynthesizer.java:691`) and carry a real `Refusals.of(KernelCode.MVK_INVALID_WILDCARD, …)` (`BoundModel.java:470,498`). Adding them would fail the test. The refusal is **pre-existing** (ADJACENT) — but it means the guard's silence is not evidence the read path is gate-free; the list was trimmed to calls that happen to be absent.
- `valueAsDate*`, `latest*`/`earliest*`, `numberOfDifferentValues` are genuinely absent from `adapter/src/main`, so adding them would pass today. They are **new** refusing gates in this slice and exactly what the guard exists to protect. (`numberOfDifferentValues` needs the lookbehind predicate, not bare `contains` — a mechanical reason the list stayed short, unstated.)

### F3 — the Linter's new level logic warns on a shape whose presence-twin is measured ADMITTED **[reviewer, not executed]**

- **F3a** — `collectPositiveLevels` treats presence and comparison guards asymmetrically. `case FieldPresenceCondition` (`Linter.java:332-336`) adds `ANY_LEVEL` unconditionally; `case ComparisonCondition` (`:352-354` → `comparisonLevel:405-409`) adds only the operand's own level. So `M.str("/Order/Notes/Text").eq("x").and(fieldNotFilled(Sku))` warns, while the identical *presence* form is pinned non-warning by `IterationGuardLintLevelTest.anUnstarredGuardOnAnotherLevelSuppresses` — a row that exists because the kernel admits it (`IterationGuardAdmissionRoutesLawsTest`, "H3 an unstarred FIELD read into the sibling" → ADMITTED).
- **F3b** — `comparisonLevel` is `if (operand instanceof FieldValue value)`, so `FieldValueAsNumber`, `IndexedFieldValue`, `EnumCategoryAccess` and every arithmetic node contribute nothing and warn. Measured row "H2 … plus a value comparison at that level" → ADMITTED, and its doc says the rule's word is *references*. The H2 fixture happens to use `bool(...).isTrue()`, which builds a bare `FieldValue` — a fixture constant, not coverage.
- **F3c (smell)** — `ANY_LEVEL` carries two opposite meanings: on the negative side "does not iterate, not my business" (`:293`); on the positive side "licenses every level" (`:334,340,345,366,371`). `levelOf` returns it for "no repeatable ancestor", which is neither. `reportUnguarded`'s comment claims they are "separate values"; they are one string.

### F4 — `GroupListOperand`'s class javadoc now states the opposite of the code **[reviewer]**

Javadoc (`GroupListOperand.java:12,16-18`) says *"Duplicate, overlap, arity, root, and wildcard gates stay kernel-arbitrated (F4)"* and *"`GroupListPredicate` pre-checks neither … not this one."* — but the constructor now runs `DuplicateGate.rejectDuplicates(...)` (`GroupListPredicate.java:36`). The edit touched the very sentence (`ListAggregate.checkNoDuplicates` → `DuplicateGate`) and left the claim around it. `GroupListPredicate.overEntities`' own javadoc *was* correctly narrowed, so two docs in one file now disagree.

### F5 — three new refusals resting on a within-carrier scope transfer **[reviewer, not executed]**

None is a demonstrated over-rejection; each is one probe row from settled.

- **F5a** — the 3-part `Date(...)` YEAR slot. `TemporalExtractorComponentLawsTest` measures the *four*-part grid exhaustively and the three-part form only at slot 0 plus `YearFromDate@2`. Slot 2 with a mismatched/cross-family unit has no row; the rule is transferred from slots 0–1. Locus `DateConstruction.java:57/62/67`.
- **F5b** — `TimeConstruction`'s positional gate on a list shorter than three. The constructor maps list index → `TIME_POSITIONS.get(slot)` (`:35-37`), so index 0 is always HOURS; every measured row uses three components. `ConditionConverter.convertTimeConstruction` (`:2370-2382`) builds the list by **skipping null slots**, so if `hour == null, minute != null` is representable the converter shifts `MinutesFromTime` into slot 0 and the gate refuses a kernel-accepted model **on read-back**. Correct iff the kernel's `ITimeConstructionOperation` is strictly a prefix — which is our own prose about kernel behaviour.
- **F5c** — a repeated bare **repeatable** group on the group-list carrier. `GroupListRef.exactIdentity()` (`:56-58`) returns an identity whenever `flattened.isEmpty()`, so `NoGroupFilled(Items, Items)` throws `MVK_DUPLICATE_PARAM1`. The sweep measured "group-list | same UNSTARRED non-repeatable group twice" → P1 and "aggregate | same UNSTARRED repeatable group twice" → **`MVK_NO_WILDCARD`** — the repeatable arm behaves *differently* on the carrier where it was measured, and the group-list × repeatable cell is the one not taken. **This widening replaced an explicit documented delegation**: the removed `Dsl.referenceIdentity` carried `case GroupListRef ignored -> null;` with *"an unstarred group's dedup behaviour is unmeasured. Skipping is the only safe direction."*

### F6 — the new `ValueListCondition` gate pre-empts the converter's `$`-preservation backstop **[reviewer, not executed]**

A stored `FieldValueIncludedInValueList($X, [X])` inside a `Having` filter: `ConditionConverter.valueListEntry` (`:952-963`) hard-codes `outer=false`, both operands reduce to the same identity, and the new gate (`ValueListCondition.java:27`) throws `MVK_DUPLICATE_PARAM1`. The dropped `$` is **pre-existing** (ADJACENT — it had its own `verifyOuterPreserved` backstop); what changes is *which* failure fires: a loud specific diagnostic becomes a `MVK_DUPLICATE_PARAM1` that reads as a kernel verdict about the author's rule (IN-SCOPE).

**Wider point:** `ReadPathFormatGateIsolationTest` guards only `BoundModel`/`Dsl` *factories*. Thirteen gates this slice put in **compact constructors** — `DateConstruction`, `TimeConstruction`, `ValueListCondition`, `FieldListEntityListCondition`, `FieldListValueListCondition`, `EntityListPredicate`, `GroupListPredicate`, `NumberOfValueInFields`, `RepetitionNotUniqueCondition`, `CountAggregate`, `DateListAggregate`, `ListAggregate`, `FirstFilledValue` — are reached by `ConditionConverter` unconditionally, and **no guard covers that surface at all**.

### F7 — `DuplicateGate` correctness, cell by cell **[reviewer]**

Walked every measured row of `DuplicateOverlapCarrierSweepLawsTest`: **the gate agrees with all 46 rows evaluable**. Wildcard exemption, `overlapModifier()` equality (`"For:a"` vs `"For:a"` → P2; vs `"For:b"` → delegate; vs `""` → delegate), starred-group-beside-starred-descendant → P2, and the `RepetitionNotUnique` exact-only choice all match. Three residuals:

- **F7a** — `candidateParent == child` (`DuplicateGate.java:54`) is reference identity where index inequality is meant. Behaviourally equivalent today (`isStrictlyInside` is false for equal paths); a latent trap. Smell, not defect.
- **F7b** — the wildcard exemption is stated two ways: `GroupListRef.exactIdentity()` conditions on `flattened.isEmpty()` (`:56-58`); `FieldListRef.exactIdentity()` returns empty **unconditionally** (`:133-135`) although its canonical constructor is public and an empty `flattened` is representable — such an operand renders **bare** yet claims the star's exemption. Under-reporting, so safe; but the contract says "EMPTY for a WILDCARDED operand" and one arm honours it conditionally.
- **F7c** — the CATEGORY component of `overlapModifier()` rests on a row the kernel never reached: the only two category rows are P2 and `MVK_ROOT_GROUP_REFERENCED`, the second decided by a *pre-empting* gate. The file flags pre-emption explicitly elsewhere and not here. Direction is under-reporting; the *basis* is one row short.

### F8 — unremarked behaviour change propagating into the computation assignment gate **[reviewer, not executed]**

`ModelIndex.temporalFormat` now answers `"yyyy"` for a `DATE_FRAGMENT` declaring `formatOfFragment` where it previously answered empty (`ModelIndex.java:186-196`) → `BoundModel.computation(FieldRef):731` → `Computation.Builder.temporalTargetFamily():389-392` returns the DATE family instead of `ofDefaultFor(DATE_FRAGMENT)` → the assignment gate (`:363-383`) **admits** a pair it previously refused. Relaxation is the safe direction and the `formatOfFragment` key is correct (confirmed against `../a12-kernel/.../DateFragmentType.java`), but nothing names the propagation and no test exercises a DATE_FRAGMENT computation target. The other two consumers are unaffected.

### F9 — code smells **[reviewer]**

- `KernelCode.of(String)` (`:365-367`) reimplements `valueOf` as an O(n) stream over ~320 constants, allocating per call on a diagnostics path.
- Two constants out of alphabetical order in an otherwise strictly sorted enum (`MVK_INVALID_TIME`, `MVK_INVALID_TYPE`, `MVK_INVALID_TIME_ZONE`).
- Fully-qualified `java.util.*` inline throughout new code in files that already have import blocks.
- Static field declared after its only use (`DateConstruction.DATE_POSITIONS:80-81`, `TimeConstruction.TIME_POSITIONS:41-42`).
- `DateConstruction.unwrapExtraction` (`:83-85`) is a one-line `instanceof` wrapper returning `null`.
- `TimeConstruction`'s arity check throws a bare `IllegalArgumentException` while two checks three lines below use `Refusals.of` — pre-existing, now the odd one out, and invisible to `KernelCodeLiteralRatchetTest`.
- `Linter.negativeOf` returns `String[]` used as a 2-tuple (`:378-402`); the slice rewrote the region and a record was the Java-21 shape.
- `Computation.java` still imports `java.util.regex.Matcher`/`Pattern` after the regex lift was deleted.

### Deliberately not concluded **[reviewer]**

- `GroupListPredicate`'s renderer and duplicate identity **do** agree about the star (both bare) — traced, stated because `CountAggregate.groupOperands`' javadoc names exactly this hazard.
- **No over-rejection found** in `requireSlotCompatibleNumberBounds`: all 19 rows of `DateConstructionComponentDeclarationLawsTest` evaluated by hand against `BoundModel.java:340-360`; every kernel-refused row is refused, all four kernel-admitted rows admitted. The `maxLength == 2` ceiling is measured on MONTH and transferred to DAY — an under-reporting transfer.
- **No measured over-rejection** anywhere in the temporal component-set / partial-precision / base-year family; those law files are unusually well controlled.
- **Zero dangling law-class citations** in `rulekit/src/main` + `core/src/main`. `PromotedGateIsReachedGuardTest.REFUSAL_SITES` pinned counts are correct (`BoundModel` 7, `Dsl` 9, `DuplicateGate` 2).

---

## 2. Reviewer B — build / tooling estate

### (a) Did the `build.gradle` extraction preserve the guard estate? — **yes, for the root/cli/adapter/interpreter estate**

No task, filter, input entry or `dependsOn` edge lost by the extraction itself. Helper migration is 1:1: all 26 call sites moved; all 9 modules + root carry `id 'a12.build-guards'`. Deliberate changes: `deliveryGuardTest` −1 class (`LaneGuardScriptTest`) and −2 inputs; `docGuardTest` +2 classes (`MvkLedgerTest`, `KernelCodeLiteralRatchetTest`) and +6 inputs plus a new `publicOnlyMarkers` property; new tasks `gateWiringGuardTest`, `verifyCompileCompleteness`, `:build-logic:test`/`:check` edges; `verifyKitPurity` becomes a typed `@CacheableTask`. `routeExclusivelyHostedTests`' owner list gained `gateWiringGuardTest` in the same edit — the derivation invariant holds.

### B1 — the native-image capture path refuses at configuration time **[reviewer, not executed]** — highest value

`guardInputs` unconditionally sets `task.systemProperty("a12.guard.observedReads", observedDir.absolutePath)` (`BuildGuardsExtension.kt:56`). Under `-PrkNativeAgent`, `assertCaptureRehostIsSupplied` (`:243-245` → `ExclusiveHosting.kt:133-149`) compares `owner.systemProperties.keys` against `broad.systemProperties.keys`. **Session verified the mechanism in source:** `:cli:test` is not itself passed through `guardInputs` (7 call sites, all on guard tasks), while 6 of the 7 traced owners are. So:

```
GradleException: "-PrkNativeAgent makes :cli:test run 'docGuardTest''s classes, but
[a12.guard.observedReads] is declared on 'docGuardTest' and not here. …"
```

before any tracing. The PR's **own** functional test asserts this exact mechanism fires (`BuildGuardsFunctionalTest.kt:386`). Invisible class: no CI job runs `-PrkNativeAgent`, no JUnit guard sees it, it surfaces at the start of a release-only ~27-minute capture. Cheapest fix: classify the key on each owner (it *is* observation-only), or set it on the broad lane, or skip it under `rkNativeAgent`.

### B2 — merging breaks three modules — **same finding as §0.2, independently reached**

### B3 — DG1002 confirmed, plus a consequence the ledger entry does not name **[reviewer]**

`scripts/lane-guard.sh`, `scripts/selftest-lane-guard.sh` and `LaneGuardScriptTest.java` are still tracked; `build.gradle` dropped the filter line and both input entries. Three live statements disagree: `scripts/README.md:27` says *"sourced by `build.sh`"* (greps to **zero**), `docs/BUILD-LOGIC-PROPOSAL.md:283` says *"lane-guard.sh is deleted"*, `LongRunGuardScriptTest.java:52` says *"still unwired"*. The unnamed consequence: because `routeExclusivelyHostedTests` derives exclusions from owner filters, a class **no owner claims stays IN** broad root `:test` — so it now spawns `/bin/bash scripts/selftest-lane-guard.sh` (15 checks) on **every** root `:test`, including every `-Pfast` inner-loop run, where it previously lived in `deliveryGuardTest` which `-Pfast` drops. It also escapes `ShellPortabilityGuardTest`'s `PATH_SELECTED_BASH` detector by spelling it `CHECKOUT.command(List.of("/bin/bash", …))`.

### B4 — the new guard's doc states its lane two ways in one paragraph **[reviewer]**

`docs/BUILD-ENVIRONMENT.md:271` says `:verifyCompileCompleteness` is *"wired to `check`"*; `:274` says *"It hangs off root `test`, not `check`"*. The build says `test` (`build.gradle:831`). The parenthetical at :271 is wrong.

### B5 — `guardInputs` makes ~20 guard tasks non-relocatable in the build cache **[reviewer, mechanism verified here]**

`observedDir.absolutePath` goes into the task's `systemProperties`, i.e. into the cache key — so the same task at the same commit misses in a different checkout path. Affects CI-vs-local cache sharing (CI uses `${{ github.workspace }}/.gradle-isolated`) and the review-worktree workflow. Irony: `scripts/verify-build-cache.sh` was extended in this very PR with the reason *"a composite build is the case most likely to carry an absolute path into its cache key, and nothing else here would notice"* — while checking only Kotlin compile tasks, so nothing notices this either. Remedy: `CommandLineArgumentProvider` with `@Internal`.

### B6 — the included build does not inherit the Kotlin settings the PR added **[reviewer]**

`build-logic` has **no `gradle.properties`**, and an included build reads its own. So neither `kotlin.incremental=false` nor the pre-existing `kotlin.daemon.jvmargs=-Xmx1536m` reaches the one Kotlin task on *every* invocation's path — leaving the documented output-snapshot race live there, and keying a second `KotlinCompileDaemon` at the 512 MiB default.

### (f) + B7 — `:build-logic:functionalTest` is CI-only in practice **[reviewer]**

It hangs off `:build-logic:check` → root `check`. Root `check` is not in CLAUDE.md's lane list, and the build's own neighbouring comment says *"CI runs `… test :adapter:test :cli:test …` and never the root `check`"*. So the only tier that proves any of these guard refusals *fire* never executes locally. Named in `ci.yml:127` and locked by `GateTierWiringTest.java:51-53`.

### B8 — `gradle/focused-test-selection.gradle` does not cover the included build **[reviewer]**

`gradle.taskGraph.whenReady` is the *main* build's graph; an included build executes on its own plan, so `./build.sh :build-logic:test --tests '…'` without `-Pfast` is allowed. Harmless today; the advertised invariant is now narrower than its wording.

### B9 / B10 — the undeclared-read gate's denominator and leniency **[reviewer]**

`ReadLog.record` has exactly **one** producer — `Checkout.resolve(String)` (`:127`). `Checkout.tracked()` records nothing; a guard spawning a script records nothing; nothing enforces routing through `Checkout`. Direction is safe (under-report), and the cross-build property-name risk *is* locked — but the recall limit is not written where the claim is. Separately, `GuardInputs.isCovered` treats a read as covered when any declared file sits *under* it, and `declared` comes from `task.inputs.files.files`, which for a `Test` task includes the whole runtime classpath — so a read of a top-level module directory is "covered" by that module's test classes being on the classpath.

### B11 — two artifact-binding guards moved behind `-Pfast` **[reviewer, judgement]**

`MvkLedgerTest` and `KernelCodeLiteralRatchetTest` are filtered into `docGuardTest`, which `-Pfast` drops. Both bind a committed artifact to the source it mirrors — the class the neighbouring comment says must stay unconditional. The input-declaration reason for the move is sound and stated; the `-Pfast` consequence is not acknowledged.

### (d) GRADLE_USER_HOME isolation — **preserved and correctly extended, no finding**

`settings.gradle:11` applies the single-sourced `gradle/public-repositories.gradle` inside `pluginManagement` before `rootProject.name`; `BuildPerformancePolicyTest` asserts it for **both** build roots; `build-logic/.no-global-gradle-repos` exists; `build.sh` untouched. One unstated consequence: a *fresh* isolated `GRADLE_USER_HOME` now needs `kotlin-dsl`'s transitives cached before an `--offline` build succeeds, and `verify-build-cache.sh` primes with `--offline`.

### (e) POSIX shell and TypeScript

**TypeScript clean** — `gate-mutant-sweep.ts` uses only `node:fs`/`node:path`, no package dependency; `gate-mutant-sweep.test.ts` uses `node:test` + `node:assert/strict` (8 cases incl. a vacuity control); executed by CI's glob and locked by `GateTierWiringTest.java:151`; declared as a runtime input of `gateWiringGuardTest`.

**B12** — `release-mirror-common.sh` (on the legacy Bash exemption list) **gains** ~20 lines of bash-array pattern registry plus a classification decision. CLAUDE.md names registries as application logic belonging in `scripts/typescript/`; the exemption is extended rather than retired. One unmeasured edge: `release_write_public_inventory` now lets `shasum` print the path, and GNU/perl `shasum` escapes a filename containing a backslash or newline by prefixing the line with `\`, which would change the format `shasum -c` reads back. Also `listing="$(mktemp)"` leaks on a failing subshell. **Positive:** the classification split is covered both ways by `InterpreterReleaseMirrorAssemblerTest.credentialAndPrivateKeyMarkersAreRejectedAsSecretsRatherThanAsPaths`.

### B14 / B15 — smaller items **[reviewer, counts re-run by reviewer]**

- `build.gradle:787` carries figures with **no measurement date**, and two are already stale in the PR: *"adapter 135/43, rulekit 290/195, cli 258/169, edit 9/9"* against actual adapter **45**, rulekit **198**, cli 169 ✓, edit 9 ✓, schemakit 8 ✓. `docs/BUILD-ENVIRONMENT.md:276-278` repeats them under a dated heading. The guard's invariant is unaffected.
- Two Zip tasks drop `preserveFileTimestamps`/`reproducibleFileOrder` with the comment that the comparison task proves the assumption — and it genuinely does. `scripts/interpreter-delivery-footprint.init.gradle:188-192` makes the **same** deletion with the same justification and **no** comparison task: an advertised invariant with no enforcement locus.

---

## 3. Reviewer C — PR claims + docs audit

### The PR description's six claims

| # | claim | verdict |
|---|---|---|
| 1 | "The check lives on its operand; `DuplicateOperand` is sealed." | **PARTLY TRUE** — sealed is true; the *check* lives in `DuplicateGate` (package-private utility) and the containment relation in `HasEntityPath.isStrictlyInside`. What lives on the operand is the data the check reads. |
| 2 | "`gate-mutant-sweep.ts` enumerates 23 decision sites … driven by `:gateWiringGuardTest`" | **TRUE** — `PINNED = { call: 11, identity: 8, modifier: 4 }`; reviewer re-ran the enumeration by hand and got exactly 11/8/4. Task exists (`build.gradle:213`), really execs the sweep (`DuplicateGateSiteDenominatorGuardTest:44-56`) with a `MIN_SITES = 20` vacuity floor, inputs declared. Not present on `main`. |
| 3 | "15 operator ids (29 covered), 27 new claim probes" | **MIXED** — probes **TRUE** (322 → 349, zero existing claims modified; all 27 carry `trigger` REJECTED + `control` ACCEPTED; kernel-executed via `CatalogKernelClaimEvidenceTest`). "29 covered" reproducible only as *entries whose JSON contains the word "duplicate"*; under `MVK_DUPLICATE_PARAM*` it is 26. **"15 operator ids" not reproducible** by any measure run. The source row E13 says "13 entries touched" while head has **14**, and "26 claim probes" while head has 27 — `3aee691cc` edited one of the two supposedly-inheriting entries and E13 was not updated. |
| 4 | "`DuplicateOperand` … 0 occurrences in main's API golden" | **TRUE** — base 0, `main` 0, PR 6. Appears in the golden **only** as a supertype declaration; no published method takes or returns it. |
| 5 | "No CI on this PR" | **TRUE** — all three workflows are `push: [main]` + `workflow_dispatch`; zero `pull_request:`. |
| 6 | the lane counts (2522 / 1185+24 / 3228 / 348 / 135 …) | **UNVERIFIABLE FROM THE REPOSITORY** — `git grep` for those numbers over the branch's `*.md`/`*.txt`/`*.json` returns **zero hits**; no committed run-evidence artifact exists (only the tooling). Not falsified; simply unauditable. |

**Three narrowings the description does not mention:** `api/dmkits-interpreter-bridge.api` loses `public boolean isGroup(java.lang.String)` from `ConditionBridge`; `AggregateOperand` loses its own `renderedPath()` declaration (inheritance move — see Reviewer A); and the 31–32 synthetic `<init>` entries + the npm `.d.ts` drop (see §0.3).

> **Correction, 2026-09-20, measured while performing the Kotlin bump separately on `main`.** Two of those three are **not narrowings by the PR at all** — they reproduce from the Kotlin 2.2 → 2.4 bump alone, with no other change in the tree:
> - **`isGroup`** is still present in the compiled `ConditionBridge` and still callable. At 2.4 it carries `flags: (0x0041) ACC_PUBLIC, ACC_BRIDGE`, and `PublicApiDumper` filters bridge methods; `Mapping.isGroup` is a genuine JVM default method (`public default boolean isGroup(java.lang.String)`), so a Java caller is unaffected. Source- and binary-compatible.
> - **The synthetic `<init>` entries** are still emitted — verified with `javap -p` on `A12Pointer`, `DocumentSnapshot`, `ModelDependency`, `InterpreterConfiguration` and `DocumentDecodeLimits`, all five still declaring their `DefaultConstructorMarker` constructor. `abi-tools` 2.4 simply stopped *reporting* synthetics. The published binary surface does not change.
>
> The bump does produce one genuine bytecode rename, which the PR's goldens do not show because that module did not exist at its base: `dmkits-consistency-gate`'s `of$dmkits_consistency_gate` → `of$io_github_mbackschat_a12_dm_dmkits_consistency_gate`. It is an **`internal`** Kotlin function, uncallable from Kotlin outside its module, so nothing outside recompiles against it.
>
> **The `yarn.lock` rewrite is likewise the bump, not a PR choice.** Performing the bump alone on `main` produced a 248/191-line rewrite of `gradle/kotlin-js-store/yarn.lock`: `kotlinStoreYarnLock` refuses the committed lock and `kotlinUpgradeYarnLock` moves Mocha 10.7.3 → 11.7.5, `kotlin-web-helpers` 2.0.0 → 3.0.0 and TypeScript 5.5.4 → 5.9.3. So §0's listing of it beside the PR's own changes should not be read as something the author elected to do.

### Docs

**Indexing met** — all three new docs (`PRECHECK-PROMOTION-PROPOSAL.md`, `GUARD-INPUT-PROPOSAL.md`, `BUILD-LOGIC-PROPOSAL.md`) have `docs/README.md` rows; `scripts/README.md:48` indexes the sweep.

**Hard-wrapping VIOLATED, and it leaks into existing docs.** Runs of ≥2 consecutive prose lines (headings/bullets/tables/fences excluded):

| file | prose paragraphs | hard-wrapped runs | wrap column |
|---|---|---|---|
| `docs/BUILD-LOGIC-PROPOSAL.md` | 58 | **36** | 91–129 (≈110) |
| `docs/PRECHECK-PROMOTION-PROPOSAL.md` | 16 | **9** | 102–111 |
| `docs/GUARD-INPUT-PROPOSAL.md` | 10 | 0 — clean |

Introduced into previously-unwrapped docs: `BUILD-ENVIRONMENT.md` 7 → **18**, `DEV-API-GAPS.md` 0 → **4**, `TESTING-SPEC.md` 0 → **3**. `KERNEL-FINDINGS.md` 0 → 0 across all 306 added lines — clean.

**Length** — `BUILD-LOGIC-PROPOSAL.md` at 525 lines has 22 headings, several recording the author's reasoning process rather than the design (§"Correcting the argument I had been leaning on", §"The census, and why it took four passes", §"5d. … the answer inverted", §"5f. … closed, nothing to build"). `PRECHECK-PROMOTION-PROPOSAL.md` at 177 lines for 5 partitions / ~40 items is proportionate. **Dispositions-as-rationale: no finding** — both are work-item state carriers with a Status column, which CLAUDE.md sanctions.

**`KERNEL-FINDINGS.md` (+306) — test-locked, clean.** 17 new findings `kf1001`–`kf1017`; **every one carries a `Locked:` citation and all 19 distinct cited classes resolve to a file**. Spot-checked KF1015, KF1008, KF1016. KF1015 explicitly withdraws a control that could not discriminate.

### Staleness — where the PR would revert or contradict `main`

1. **`docs/MVK-LEDGER.md` — worst.** Two incompatible *enforced* taxonomies: `main` introduced a `checker-reported` bucket used on **207 lines** with separator `/`; the PR rewrites `MvkLedgerTest.java` to enforce a closed `BUCKET_ATOMS` list joined by `" + "` **plus** two-way equality against `KernelCode.buckets()`. Every one of `main`'s 207 mentions fails the PR's guard. Header count regresses from `main`'s corrected **"306 codes across five sources"** back to **"300 across four"** — the exact figure `main` flags as wrong. `main`'s `LedgerResidueFigureGuardTest` is absent from the branch. 296 of 300 rows changed on `main`, 42 on the PR, **all 42 collide**. Rows where the PR reinstates something `main` measured otherwise include `MVK_NO_FIELDS_ALLOWED` (main: *"measured FALSE"*; PR reinstates the falsified sentence verbatim), `MVK_EXTRACT_OP_NOT_ALLOWED_4_DATE_CONSTRUCT_WITH_4_PARAMS`, `MVK_NO_DATE_CONSTANT`, `MVK_WRONG_DATE_FORMAT_FOR_OP`, `MVK_ERROR_FIELD_AND_ITERATION_INVALID`, the two `MVK_WILDCARD_*`, `MVK_DIFFERENT_GROUPS`, `MVK_REPEATABLE_LEVEL_REQUIRED`, `MVK_SEMANTIC_INDEX_NOT_ALLOWED`, `MVK_CATEGORY_UNKOWN`, `MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK`, `MVK_PARALLEL_ITERATION_INVALID`. **Denominator conflict on the PR's own subject:** `main` strikes through *"`MVK_DUPLICATE_PARAM1`'s duplicate notion"* as audited, *"the same on all **28 carriers that build a list**"*; the PR restores it unstruck and its KF1015 says *"12 call sites over 10 constructs, governing **38 operators**"*; the catalog claim says 15 ids. Three same-typed quantities for one class. Also: the PR's guard makes `advisory` and `renderer-preserved` load-bearing atoms, neither declared in the ledger's own Buckets legend.
2. **`docs/SEMANTICS-MAP.md`** — the PR's §1/§6/§7/§8/§9/§10/§11 registry rows are a different, older set; taking the PR's side on any row silently drops `main`'s laws-test registrations.
3. **`docs/ARCHITECTURE.md`** — `main` says **"Fourteen Gradle units"** with `predicates`/`consistency`/`consistency-gate` rows and `core`'s `implementation project(':predicates')` edge; the PR says **"Ten … plus one included build"** and omits all three modules and the edge. The PR's `build-logic` paragraph is genuinely new and must survive alongside.
4. **`README.md`** — `main`'s *"the portable checker (`a12-consistency`)"* bullet is absent from the PR's list.
5. **`docs/TESTING-SPEC.md`** — `main`'s 5-row `docGuardTest` table + `SourceDocLinkResolvesTest` (2026-09-15) + the index-denominator trap (2026-09-17) would be replaced by a single prose sentence naming only `DocSnippetFreshnessTest`.
6. **`docs/OPERATOR-INDEX.md`** — generated and guarded by `OperatorIndexGuardTest`; regenerate after merge, no manual reconciliation.
7. **`.github/workflows/ci.yml`** — `main`'s run line names `:interpreter:verifyInterpreterPurity` (**the EUPL import boundary**), `:interpreter:verifyInterpreterDependencies` and three more individually, with a comment noting *"five of them ran nowhere at all until 2026-09-12"*. The PR's line is the shorter, older one. **Resolving in the PR's favour would silently drop the licence-boundary gate from CI.**
8. `gradle.properties`, `docs/BUILD-ENVIRONMENT.md`, `scripts/README.md` — additive conflicts only.
9. **`CLAUDE.md`** — the PR adds 3 standing paragraphs (the delegated-obligation rule, "promoted is not reached", "record WHY it is constant"). Owner's call, not a defect.

### API-GUIDE / gaps / numbering

- **API-GUIDE snippets: yes for the temporal gates, no for the rest.** Two new test-backed snippets (`untyped-temporal-gate` → `UntypedTemporalExtractionGateTest`, `date-precision-gate` → `DatePrecisionExtractionGateTest`, both registered in `DocSnippetFreshnessTest:104-107`). ~16 further published `BoundModel`/`Dsl` methods get **prose only**, inside the paragraph that is corrupted (§0.5). The headline duplicate/overlap promotion gets one sentence at :488 and no snippet.
- **DEV-API-GAPS** — `DG47`, `DG1001`, `DG1002` added under a section empty on `main`; the 1000-offset convention holds.
- **🔴 `docs/API-FINDINGS.md` id collision.** Base had AF1–AF145; `main` added **AF146–AF150**; the PR added **AF146–AF148**. Three ids each name two different findings:

  | id | main | PR |
  |---|---|---|
  | AF146 | "A partial rebuild loses exactly what the typed surface cannot express." | "A gate the typed surface enforces by construction still has to be stated for the untyped path…" |
  | AF147 | "The typed draft may emit only models A12 can build, and the read path may not share that refusal." | "An absent declaration usually means the DEFAULT, not 'unknown'…" |
  | AF148 | "A lint keyed on a shape the renderer never emits fires on nothing, and its test can say otherwise." | "Where a kernel refusal can live is decided by whether it is INTRINSIC or CONTEXTUAL…" |

  This is exactly what `65e4978d0` was written to prevent, and its own message says why it could not be: *"API-FINDINGS cannot [take a chunk]: its entries are an ordered list a renderer renumbers by position."* The PR's three must be renumbered to AF151–AF153 on merge and every citation re-pointed.
- **KF / DG / E ids do not collide** (see §0.7).

### What Reviewer C did not run

No Gradle, so no guard verdict is theirs. Did not execute `gate-mutant-sweep.ts` (re-implemented its rules in shell instead). Did not verify that the cited laws classes *assert* the rows they are cited for — only that each exists. Notes that `PRECHECK-PROMOTION-PROPOSAL.md` row C4 openly records that `ClientRefusalKernelBasisCensusTest.measuredAtTheKernel` counts a code as "measured" when merely *named* in law-test source, which weakens the description's "every MVK literal backed by a kernel-executed trigger" **for the census**; the 27 catalog probes are genuinely trigger+control and separate.

---

## 3b. What PR #3's EVIDENCE is worth to `:consistency` (analysed 2026-09-20)

`:consistency` did not exist when the PR was cut, and it is a Kernel-free reimplementation of A12's consistency checking — so the PR's **kernel measurements** are its currency independently of whether any PR code lands. The checker today: **103 check families, 236 `MVK_*` codes, 134 distinct KF ids cited, zero KF10xx**.

### The duplicate/overlap rule — the checker already has the mechanism, and KF1015 sharpens its identity

Coverage is real, not absent: `ENTITY_LIST_PRE_GATES` (`EntityListPreGates.kt`) asks both arms for **22** entity-list operators, and `GROUP_LIST_OPERANDS` owns **6** group-list carriers — the file documents the denominator as *"the operator set `EntityListArity` enumerates … less the six `groupListOperandFindings` already owns"*, i.e. **28**, which matches `main`'s MVK-LEDGER "28 carriers that build a list". Its doc already states KF1017's precedence fact independently: *"**Duplicates precede everything**, the root-group gate and each operator's own arity gate included, because they are asked while the list is built rather than after."*

**What KF1015 adds that the checker does not have — the identity's COMPONENTS.**

| | A12 (KF1015, measured) | `:consistency` today |
|---|---|---|
| identity | **four** components: resolved path, category projection, semantic index, `$` outer flag (of `EntityRef.equals`'s seven; the other three measured out) | **one**: the resolved path |
| relative → absolute | normalized before comparison | ✓ same (`addressedTargetOf` → `Reference.Resolved.path`) |
| wildcard skip, direct arm | yes, **A12-only** (`ref.hasWildCards() && !parser.isHModel()`) | yes, unconditional — diverges on an H model (under-reports, safe) |
| wildcard skip, containment arm | never skips | ✓ same |
| containment compares modifiers | yes — an index-selected ancestor does **not** contain a differently-keyed descendant | **no** — bare `inner.startsWith("$outer/")` |
| kernel locus | `CheckEntityListenUtils:176` (PARAM1) / `:164` (PARAM2), one shared caller `checkParamsReferencedOnlyOnce` | cites KF325 |

**Two OVER-REJECTION candidates follow, and over-rejection is the direction that makes the checker answer INVALID on a legal model.** The mechanism is that `CallOperand` carries `path`, `base` and `clauses` — where `clauses` holds *"a filter, a scope, or a semantic-index key"* — and `entityListReferences` reduces each operand with `operands.map { it.path ?: return null }`, **discarding `clauses`**. So two operands differing only in their key collapse to one string.

- **D-1 (containment arm) — a discriminator KF1015 already measured.** `NumberOfFilledFields(Items/Count For "a", Items For "b")` → KF1015 records **admitted** (different keys). Traced through the checker: paths `/…/Items/Count` and `/…/Items`, `startsWith` holds, no key comparison → it would report `MVK_DUPLICATE_PARAM2`. The PR's table also carries the positive control one variable away: same key → `MVK_DUPLICATE_PARAM2`, which is the verdict the checker would give in both cases.
- **D-2 (direct arm).** `Sum(Amount For "a", Amount For "b")` — both reduce to the same path, neither is starred, so `firstDirectDuplicate` fires → `MVK_DUPLICATE_PARAM1`, where A12's four-component identity separates them. Same shape for a category projection (`field -> Cat` beside a plain read).

### RUN 2026-09-20 — D-1 and D-2 REFUTED as over-rejections; a wider UNDER-report confirmed instead

Executed with the built CLI against `cli/src/test/resources/models/order-aggregates.dm.json` (`/Order/Items` repeatable 50, `indexFieldName = Sku`, `Count : NumberType` — KF1015's own shape), `dmtool rule check --engine=kernel` vs `--engine=portable`, one variable per pair.

| # | condition | kernel (A12 30.8.1) | portable (`:consistency` 0.13.0) |
|---|---|---|---|
| D-1 | `NumberOfFilledFields(Items/Count For "SKU-1", Items For "SKU-2") > 0` | no duplicate code (only `MVK_ERROR_FIELD_NOT_REFERENCED`, about the chosen error field) | INCONCLUSIVE |
| D-1′ | `… For "SKU-1", Items For "SKU-1"` — same key | **`MVK_DUPLICATE_PARAM2`** | INCONCLUSIVE |
| D-2 | `Sum(Items/Count For "SKU-1", Items/Count For "SKU-2") > 0` | no duplicate code | INCONCLUSIVE |
| D-2′ | `… For "SKU-1", Items/Count For "SKU-1"` — same key | **`MVK_DUPLICATE_PARAM1`** | INCONCLUSIVE |
| D-3 | `Sum(Quantity, Quantity) > 0` | `MVK_DUPLICATE_PARAM1` | **INVALID** ✓ |
| D-3′ | `Sum(Items/Count For "SKU-1", Quantity, Quantity) > 0` | `MVK_DUPLICATE_PARAM1` | **INCONCLUSIVE** ✗ |

**KF1015 reproduces exactly on our own carrier** (D-1/D-1′, D-2/D-2′): a differently-keyed pair draws no duplicate code, an identically-keyed one draws PARAM2 on the containment arm and PARAM1 on the direct arm.

**The over-rejection hypothesis is REFUTED.** The checker never reports a false duplicate on a differently-keyed pair. My read-based chain was wrong at its first link: a clause-carrying operand does not silently reduce to a bare path — it yields `CallOperand.path == null`, and `entityListReferences`'s `?: return null` makes the reader **abstain**. That is the checker's documented conservative design, and it holds.

**What IS confirmed is a wider under-report than the code reads as.** D-3/D-3′ move exactly one variable — prepending one unrelated index-clause operand — and the identical `Quantity, Quantity` duplicate goes from INVALID to INCONCLUSIVE. So **a single clause-carrying operand anywhere in an entity list suppresses the duplicate pre-gates for the ENTIRE list**, including duplicates among operands the clause has nothing to do with. Safe direction (INCONCLUSIVE, never a false INVALID), but broader than "an indexed operand is unjudged".

**KF1015 is exactly what closes it.** With the four-component identity (resolved path, category, semantic index, `$` outer flag), a clause-carrying operand does not need to be unjudgeable — it needs a *key*. `Items/Count For "SKU-1"` then simply carries a distinct key and the remaining operands still compare against each other. That is the concrete, measured payoff of the PR's evidence for `:consistency`, independent of any PR code.

### The denominator question, and why it is now answerable

Four same-typed quantities are live for one class: checker **28**, `main`'s ledger **28**, PR KF1015 **38 operators / 12 call sites / 10 constructs**, PR catalog **15 published ids (29 covered)**. These may all be correct measurements of different things, and the repository's own rule is to name the quantity before comparing numbers. What KF1015 contributes is the means to settle it: it names the exact kernel locus (`CheckEntityListenUtils:164/:176` and the sole caller `checkParamsReferencedOnlyOnce`), so the governed set can be re-derived from the kernel's own call graph rather than argued. If the answer is 38 against the checker's 28, the checker is silent on up to ten carriers where A12 refuses — under-reporting, safe, but a coverage gap worth closing.

### The other 15 findings, by checker relevance

| finding | checker family that would own it | why it matters |
|---|---|---|
| **KF1016** — `MVK_NO_WILDCARD` judges each **LEVEL**, not the reference (a ref can carry a star and still be missing one) | 26 files mention `MVK_NO_WILDCARD`; `addressedTargetIn` already reasons per level via `starred`/`iterated` | a per-level rule is exactly the shape `addressedTargetIn` implements — worth checking the two agree |
| **KF1012** — an index selection pins a row **only when its KEY is a constant** | `OPERAND_REPETITION_SCOPE`, `addressedTargetIn` | same clause-dropping mechanism as D-1/D-2: if the checker treats *any* index selection as addressing a level, it diverges on a field-keyed one |
| **KF1002** — `MVK_PARTIAL_DATE_NOT_ALLOWED`'s complete denominator is the three entity-list aggregates | 2 files mention the code | a **completeness** claim, the kind a reimplementation cannot derive for itself |
| **KF1009** — `BaseYear` needs a DECLARED base year on **every** carrier (six measured) | `BASE_YEAR_REQUIREMENT` (1 file) | carrier-uniformity claim; cheap to compare |
| **KF1008** — a `Date` construction's NUMBER component must match its SLOT's range **exactly** (not containment) | `DATE_CONSTRUCTION_SLOT` | an exact-vs-containment distinction is precisely where a reimplementation guesses wrong |
| **KF1007 + KF1014** — a point-in-time constant, and **every derived temporal operand**, answers its kind's DEFAULT format | `TEMPORAL_*`, `VALUE_AS_DATE_OPERAND` | KF1014 is the sibling sweep, i.e. the completeness half of KF1007 |
| **KF1005** — a date-range construction's endpoints must agree, and the refusal is the **construction's**, not the comparison's | `DATE_RANGE_OPERAND_LIST` | a diagnostic-identity fact: which code and which locus |
| **KF1001** — the extraction operand gate reads the declared format's **COMPONENT SET**, not its shape | temporal families | |
| **KF1003** — the parallel-iteration code partitions on PARALLELISM; parallel iteration needs a shared index name | `PARALLEL_ITERATION_UNIQUENESS` (1 file) | |
| **KF1004, KF1006, KF1010, KF1011, KF1013** — iteration admission: an index-selected reference pins and does not iterate; an out-of-group error field **pre-empts** the iteration gate; an `Or` lends no guard at any depth; a negative over the rule's own group is not self-bounding; three further admission routes, and the level rule holds only for the STARRED spelling | `ERROR_TARGET_ITERATION`, `ERROR_TARGET_CONTAINMENT`, `FILTERED_ENTITY_ITERATION` | mostly **ADMITTED** rows — for a reimplementation an admission is as valuable as a refusal, because it bounds over-rejection |
| **KF1017** — the duplicate arms are keyed on the operand LIST, not the keyword, and run ahead of every operand-kind bar | `ENTITY_LIST_PRE_GATES` | the checker states this independently; KF1017 is corroboration from a second estate |

### The 27 claim probes are directly reusable

`src/test/resources/catalog/operator-claim-probes.json` gains 27 entries, each a `trigger` with `verdict: REJECTED` + an `MVK_*` code **and** a `control` with `verdict: ACCEPTED`, executed against the real kernel by `CatalogKernelClaimEvidenceTest`. That trigger/control shape is exactly what a checker differential wants, and the controls are the half that bounds over-rejection. They are worth harvesting as checker fixtures whatever happens to the PR's Java.

---

## 4. Corrections the session made to its own checks

- Initially reported `E15`/`E16`/`DG1002` as unfiled — **wrong**, a bad pathspec in the grep. They are properly filed.
- Initially reported `KF191` as a dangling cross-reference — **wrong**, the anchor is lower-case (`<a id="kf191">` at `KERNEL-FINDINGS.md:3090`).
- Initially flagged the npm `.d.ts` removal as a possible shipped-types regression — **not one**; see §0.3.
- Initially cautioned that `testCompileOnly` might be too narrow for Lombok because `:adapter` has main Kotlin — **it does not**; zero `.kt` under `adapter/src/main` on all three refs.
