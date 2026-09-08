# Build logic — giving the shared build code a home that can be tested

> **What this is.** A PROPOSAL and the **state carrier** for the work: the table in §6 is updated in the same commit as each landing. Opened 2026-09-03, out of the guard-input work ([GUARD-INPUT-PROPOSAL](GUARD-INPUT-PROPOSAL.md)), which ended by naming a typed `guardInputs` as the thing worth doing on its own merits.
>
> **The question it answers.** The estate's shared build code lives in untyped Groovy closures on the root project's `ext`, and the invariants over that code are asserted by substring-matching the build script's text. Nothing can unit-test it, and the substitute is vacuity-prone. Does it want a `build-logic` build?
>
> **Status: (a) SHIPPED 2026-09-03 — W1–W5 and W8 are landed; see §6 for what each carries and what it cost. (b) still blocked.**
>
> **Recommended disposition, in two parts after a cold review on 2026-09-03.**
>
> **(a) `build-logic` — DO IT.** A **Kotlin** included build holding the shared `ext` helpers, with the consuming build scripts left in Groovy. The evidence for this survived review intact: the helpers are untestable and have a measured defect rate (§2), the plugin-management costs are measured and single-sourceable (§4), and the testability gain is demonstrated (§3). This is W1–W5.
>
> **(b) Converting the corpus guards to linter tasks — NOT ESTABLISHED. Do not start W6.** The intuition is good and the drift it targets is real, but the cold review defeated four of the load-bearing steps: the guards are **not** all floor-defended, and the strongest one defends itself *with the very second derivation the conversion deletes* (§5); a converted guard **leaves the native-image capture surface**, which the earlier "VOID" got backwards (§5c); the "all 26 sites" denominator is wrong (§5); and several candidates' oracle is live product code, which an included build cannot reach (§5). §5 records the argument and exactly what would have to be settled first. Re-partition before re-opening.

## 1. What was true before this landed

**Frozen: this section is the BEFORE state, kept as the baseline the case rested on — read §6 for what is true now.** Every figure here is measured on 2026-09-03 at `6b842fc1f`; the commands are ordinary `wc`/`grep` over the tracked build scripts.

| | |
|---|---|
| Groovy build script | **7 455 lines** across 12 files — `:interpreter` 3 604, root 1 125, `:adapter` 952, `:cli` 792, then `scripts/interpreter-delivery-footprint.init.gradle` **527** (an init script, not a consuming build script), `:schemakit` 127, and the rest ≤ 92 |
| `buildSrc` / `build-logic` | **neither exists** |
| Gradle TestKit | **not used anywhere** — no functional test of build logic exists |
| shared helpers | **four**, all closures on the root project's `ext`: `guardInputs`, `routeExclusivelyHostedTests`, `verifyCompiledPurity`, and **`dmkitsPom`** — which has the most consumers of any of them (6 modules reach it as `rootProject.dmkitsPom(...)`) and was missed until the 2026-09-03 review |
| how the other modules reach them | `rootProject.<helper>(...)` — **26 cross-project call sites**: 20 for the three guard helpers (`:cli` 8, `:adapter` 8, `:interpreter` 3, `:interpreter-bridge` 1) plus 6 for `dmkitsPom`. Load-bearing on root configuring first |
| `guardInputs` sites | **26** (root 8 · `:adapter` 8 · `:cli` 7 · `:interpreter` 3) |

There is **no prior decision against a nested build.** Searched with `command grep` across `*.md`/`*.gradle`/`*.sh` and with `git log -S buildSrc --all`: the only statement that the repo "deliberately does without" `buildSrc` is the one **this proposal deletes** — an inference I wrote into `GUARD-INPUT-PROPOSAL.md` on 2026-09-02 and did not check. The one genuine pre-existing note is `.no-global-gradle-repos`, and it is a scope statement rather than a decision; §4 takes it seriously.

## 2. What the current shape costs

**The build logic is untested, and it has a measured defect rate.** `guardInputs` is ~60 lines carrying three distinct failure modes — an input tree that resolves empty, a runtime read nothing declares, and *when* the comparison may run. It was corrected **twice in four commits** on 2026-09-02: `06bc5194d` moved the check so a failing guard could not skip it, and `6b842fc1f` reverted that because a shared finalizer judges a task that never ran. Neither correction could be driven by a test; both were found by running the whole build and reasoning about it.

**The substitute — substring-matching the build script — is vacuity-prone, and demonstrably so.** `PurityGuardMechanismTest` asserts `build.contains("verifyCompiledPurity")`, which is equally true of a commented-out call, a call in a dead branch, or a call in a task nothing depends on. `routeExclusivelyHostedTests`' own comment records the sharper case: a mutant that gutted the derivation left "every text-matching guard still passing while the owned classes quietly re-entered the broad lane". At least four tests assert build-script behaviour this way (`BuildPerformancePolicyTest`, `PerfTestClassificationTest`, `PurityGuardMechanismTest`, and parts of `GateTierWiringTest`, which mostly reads `ci.yml`); the exact retirement set is W6b/W6c's job to enumerate, not this section's to assert.

**`ext` is untyped and ordering-dependent.** `guardInputs(Test, Map<String, ?>)` takes a human-readable named map, which is the wire shape [GUARD-INPUT-PROPOSAL](GUARD-INPUT-PROPOSAL.md) already flagged as the un-idiomatic part. A typed extension with named `@InputFiles` properties is what Gradle offers instead, and it is what makes the helper reachable from a unit test.

**It is on Gradle's deprecation path.** `guardInputs` already carries a workaround comment for it: reaching `Task.project` from a `doFirst`/`doLast` is deprecated in Gradle 9 and fails in 10, because it cannot work with the configuration cache. Cross-project `rootProject.ext` access is the same family. The configuration cache is **not** enabled here today (`gradle.properties` sets only `org.gradle.caching`), so nothing is broken now — but the helpers are the part of the estate that will have to move first when it is.

## 3. The extracted code is Kotlin; the build scripts stay Groovy

The two halves are independent, and both are measured.

**`build-logic` itself must be Kotlin DSL.** `plugins { id 'kotlin-dsl' }` from a Groovy `build-logic/build.gradle` fails — *"not a core plugin"*, and *"plugin dependency must include a version number for this source"*. The `kotlin-dsl` plugin is reachable only as `` `kotlin-dsl` `` from a `build.gradle.kts`. That is not a cost: Kotlin is the right language for extracted build logic, because the Gradle API it consumes is typed, nullability-annotated and lambda-heavy, and because plain Kotlin classes are the part a unit test can reach.

**Consumers stay Groovy.** Measured: a Kotlin precompiled script plugin (`build-logic/src/main/kotlin/a12.guard-conventions.gradle.kts`) applies from a Groovy root `build.gradle` via `plugins { id 'a12.guard-conventions' }` and its task runs. The 7 455 lines are untouched by this proposal.

**And the testability claim is demonstrated, not asserted.** The coverage rule inside `guardInputs`' `doLast` — *is an observed read covered by a declared path?* — was lifted verbatim into a Kotlin object in a scratch `build-logic`, with four JUnit cases:

| | |
|---|---|
| control | 4 tests, **green in 1 s** |
| mutant — `read.startsWith("$it/")` weakened to `read.startsWith(it)` | **`a sibling prefix must not count as coverage` FAILED**, 4 completed / 1 failed |

That mutant is a real bug class: it would silently treat a read under `docsX/` as covered by a declared `docs`. **Nothing in the estate can catch it today** — the live rule is correct, and no test exercises it, because it lives in a Groovy closure that only a full build can reach.

## 4. Plugin management for a nested build — verified against upstream, then measured

**Upstream is explicit.** The composite-builds guide: *"Included builds do not share any configuration with the root build or other included builds. This includes repositories, plugin management, or dependency versions"*, and *"`buildSrc` is essentially an included build that Gradle manages automatically; a `build-logic` build included via `includeBuild()` in your settings file is the same idea, made explicit."*

Measured here on Gradle 9.0.0, resolving a nonexistent plugin so the failure enumerates what was searched:

| probe | repositories searched |
|---|---|
| root project *(positive control)* | **the root settings' marker**, then the init script's three |
| `buildSrc`, no settings file | the init script's three — **the root marker is absent** |
| included `build-logic` | the init script's three — **the root marker is absent** |
| `buildSrc` with its **own** `settings.gradle` declaring a marker | **its own marker first**, then the init script's three |
| root **and** nested settings both `apply from:` one shared file | **the shared file's repository first**, in both |

**One upstream claim did not survive.** Gradle issue #21204 reports that an init script's `settingsEvaluated` plugin-management does not reach `buildSrc`; it is closed as not planned. On 9.0.0 it **does** — the discriminator is an empty Gradle home, where `buildSrc` searched only the Plugin Portal, against MavenLocal + Portal + MavenRepo under the isolated home whose init script adds exactly those three.

That correction cuts both ways, and the second edge is ours. The isolated home's public-only init script protects a nested build — **and so would a hostile one.** A nested build has neither defence this repository relies on: `settings.gradle`'s ordering is not inherited (measured above), and `.no-global-gradle-repos` is read from the build's root directory, which the marker file already states — *"a nested buildSrc or included build has its own root and would not be covered. This repository has neither."* That last sentence becomes false in the commit that lands W1.

**The fix is single-sourcing, not duplication.** `apply from: <file>, to: it` inside `pluginManagement { }` works from both the root settings and a nested build's, and in both the shared file's repositories land **first** — so one `gradle/public-repositories.gradle` serves both and there is no second place to keep right. This is why the objection "a nested build means two copies of the defence" does not stand, and it is what W1 must implement rather than a copy-pasted block.

**Still unmeasured:** what the *nested build itself* adds to configuration time and to cache behaviour in a build this size. (Distinct from §5c, which measures the linter task's own cacheability.) W1's pilot settles it, against the ~1.15 s baseline in §5c.

## 5. Most of these guards are linters, and a linter is a Gradle task

**The drift we currently owe is structural, not incidental.** A guard's file set is derived **twice**: declared in `build.gradle` as `guardInputs`, then re-derived at runtime in Java through `Checkout`, `:cli`'s `RepoWalk`, or a test's own private `locate`. Two derivations of one set can disagree, and when they do the lane goes `UP-TO-DATE` while the guard checks something else — green while checking nothing.

What we built to detect that is the measure of how bad the shape is: a read-recorder plus a per-task comparison that needs three separate enumeration spellings, cannot run when the guard itself fails, and took **two corrections in four commits** to get the timing right. It is a detector for a problem the design creates.

**And on 18 of the 26 sites that detector is vacuous, not merely absent.** `Checkout` — the only class that records a read — lives in the root project's test sources, and **no test under `cli/src`, `adapter/src` or `interpreter/src` references it** (measured: zero files). So on every non-root site the observed-read set is always empty, the declared-versus-observed comparison finds nothing, and the check *passes because it saw nothing*, which is indistinguishable from passing because everything was declared. Only the empty-input half of `guardInputs` is doing work there. A single `@InputFiles` derivation removes the need for the detector rather than extending it.

**And these guards are linters, which is why they fit a task.** Measured shape, not impression:

| | |
|---|---|
| `DocsIndexGuardTest` | **2** `@Test` methods, **6** loops over the corpus, asserting `audit.unlinked().isEmpty()` and `audit.deadLinks().isEmpty()` |
| `DocRelativeLinkGuardTest` | **2** methods over 2 432 links, asserting collections empty |
| `DocAnchorResolveGuardTest` | 6 methods, **6** accumulate-into-a-list sites, asserting `violations.isEmpty()` |
| guards matching *both* "has a `MIN_` floor" and "asserts a collection empty" | **10** — swept over `*.java` in `src/test`, `cli/src/test`, `adapter/src/test`; 17 Java files carry a floor. **The sweep is Java-only**: 18 `.kt` files also carry a `MIN_`-shaped constant, but Kotlin's are largely performance floors, so they are *not* claimed as linters here. The 10 is a lower bound on a Java denominator, not a whole-estate count |

Walk a declared corpus → accumulate findings → assert the collection is empty → guard the denominator with a floor. That is a linter's control flow, and for the **corpus arm** JUnit contributes a runner and one assertion; its granularity there is per *invariant*, not per finding.

**But the corpus arm is not the whole class.** Of the 10 `@Test` methods across those three exemplars, **4 are `@TempDir` does-fire positive controls** — the "prove a new gate can fail" tests this repository mandates. Those are tests of our own code (the taxonomy's third row), and JUnit contributes fixture management and the controls themselves. So conversion does not remove JUnit from these classes; it **splits each in two** — corpus arm to a task, positive control to a unit test — and relocating those controls is an obligation no work item currently carries.

**Gradle's own model for this is a task.** `SourceTask`/`VerificationTask` with an `@InputFiles source` and a report is what Checkstyle, PMD, Detekt and ktlint all are. None of them is a JUnit test, and the reason is the one above: the file set is the subject, so declaring it twice is the defect.

### Correcting the argument I had been leaning on

[GUARD-INPUT-PROPOSAL](GUARD-INPUT-PROPOSAL.md) concluded that a declared input answers *when must this re-run* while a guard's subject answers *what does this check*, so the two should not be made one object. **That conclusion was drawn from a pilot whose problem was the transport, not the identity.** It serialized Gradle's resolution across a process boundary into a separate JUnit JVM, and needed a slug wire protocol and a listing file to do it. A task's `@InputFiles` is the same object in the same process: no transport exists to get wrong.

Its one substantive objection — narrowing the declaration silently narrows the check — **remains undischarged.** This section previously claimed it was "already defended, everywhere" by the `MIN_` floors. That is false, and false in the textbook scope-transfer shape: the property was measured on the three doc guards profiled above and transferred to every guard the conversion row names, without measuring on the new carriers. Measured 2026-09-03:

| guard, as named in the taxonomy row | denominator defence |
|---|---|
| `DocsIndexGuardTest`, `DocRelativeLinkGuardTest`, `DocAnchorResolveGuardTest`, `DerivedFigureDatingGuardTest` | ✅ `MIN_` floors |
| **`PrivateMarkerLeakGuardTest`** ("no leaked marker") | ❌ no floor — it asserts `scan.filesRead() >= tracked.size() * 9 / 10`, **a cross-check against `git ls-files`**: it defends itself *with the second derivation the conversion exists to delete* |
| **`TrackedOutputGuardTest`**, **`RawClockCensusTest`** | ❌ **none at all** |

So the trade is not "an unenforceable second derivation versus a floor we already write". For at least one guard the second derivation *is* the defence, and for two others there is no defence to inherit. A conversion would have to supply one.

### The revised taxonomy

| the check's subject | anchor | why |
|---|---|---|
| a **repository corpus** — docs indexed, links and anchors resolve, no undated figure | **a Gradle task**, `@InputFiles` *being* the subject | one derivation removes the drift class **on the sites that convert** — not on all 26. `:adapter`'s 8 sites are model/perf/corpus **fixtures feeding behaviour tests**, `:interpreter`'s are ABI goldens, and cli's `mutationTailGuardTest` compiles production source: all third row. At most ~15 sites are candidates, fewer after row-3 filtering |
| **build logic** — does a refusal fire, is a derivation empty | a **unit test in `build-logic`** | §3: 1 s, and it kills a mutant nothing today can see |
| **behaviour of our own code** that happens to read repo files | **stays a JUnit `Test` task** | it really is a test; the read-observer keeps its job here, on a much smaller and better-defined set |

**A bound on how much can convert: a `build-logic` task cannot depend on the project's own modules.** The dependency runs the other way — `:cli` consumes build-logic's plugins — so a guard that needs production classes cannot simply become a plugin task. Measured over the 10 candidates by enumerating non-JDK instantiation: **9 are pure file I/O and regex**, and **one is not** — `CommandReferencesResolveTest` does `new CommandLine(new RootCommand())`, walking the live picocli tree from `cli/src/main`, which is the whole point of that guard.

*(An import scan says all ten are clean, and that is wrong: `RootCommand` is in the test's own package, so no import statement exists to find. The zero-hit answer and the true answer are indistinguishable without checking usage rather than imports.)*

So W6b's classification must test **production-class dependency, by usage and not by import**. A guard that has one has two honest options: stay a JUnit `Test` task, or run its check in a worker with the project's runtime classpath injected as a `@Classpath` property — which is exactly how Checkstyle and PMD run a tool they do not compile against. The second is real but is machinery, and nothing in this proposal needs it yet.

**Consequences worth stating plainly.** The read-observer's reason to exist disappears only for what converts; it keeps its job on the third row, which is where the second derivation survives. `Checkout` stays (11 of its 44 users are the linter shape, so 33 are not), but enumerate-a-corpus-for-linting stops being a test-source concern, which is most of what `RepoWalk` and the private `locate`s were for.

**Nobody outside this repository owns the JUnit-XML interface, so converting does not break a contract.** CI reads none of it — no test-result upload, reporter or annotation action appears in `.github/workflows/`. The only two real readers are ours: `scripts/typescript/test-evidence.ts`, which globs `<module>/build/test-results/<task>/` and parses the `testsuite` counts, and `.githooks/pre-commit`, which compares the newest `TEST-*.xml` mtime against the staged files. What they need is **a per-task artifact with an mtime and a count**, which the linter task's declared stamp supplies — and `test-evidence.ts` already takes each task's executed / `UP-TO-DATE` / `FROM-CACHE` state from the **build log**, not from the XML. (For the record: Gradle defines no standard machine-readable verification-result format; JUnit XML is a de-facto CI interchange convention, and Gradle's own verification tasks implement `Reporting<T>` with their own formats instead.)

**So what is genuinely given up** is not the XML: it is `--tests` filtering to run one guard class, and per-case run/debug in an IDE. Against that, note the granularity being lost is per-invariant — two methods for a whole corpus — and the granularity being gained is per-finding, with a path and a line.

## 5b. GitHub CI reporting — a real gap, and the conversion is what makes it cheap

**Today a CI failure gives you a log and nothing else.** `ci.yml` runs `./gradlew … test :adapter:test :cli:test …` in one step and uploads nothing; `permissions:` is `contents: read`, so anything needing `checks:` or `security-events:` is out until that changes deliberately. A red run means reading a ~53-minute log. `gradle/actions/setup-gradle@v6`, already in use, adds a job summary of tasks and outcomes by default and can post it as a PR comment — but it surfaces neither Problems-API problems nor file annotations, and its richer test reporting requires Develocity, which we do not run.

**Four improvements, all available now and none of them requiring a conversion:**

| | cost | limit |
|---|---|---|
| `actions/upload-artifact` under `if: failure()` for `**/build/reports/**` | 3 lines of YAML | none — the single biggest debuggability win, and it works with the guards exactly as they are |
| `$GITHUB_STEP_SUMMARY` markdown — the findings as a table on the run page | a few lines of emitting code | 1 MiB per step |
| `::error file=…,line=…::` workflow commands — findings annotated on the PR diff | one `printf` per finding | **10 errors + 10 warnings per step, 50 per job, silently dropped beyond** |
| `add-job-summary-as-pr-comment` on the action we already use | one line | — |

**Does this change the odds on converting? Only a little, and not the way it first looks.** The Problems API does carry locations — measured, `lineInFileLocation('docs/KERNEL-FINDINGS.md', 1234)` renders in the report — and a structured finding maps onto an annotation directly, where an assertion message has to be regex-parsed back into a path and a line. But **nothing about that requires a Gradle task**: a JUnit guard that collects findings into a list instead of concatenating them into an assertion message can emit the same workflow commands. The prerequisite is **structured findings**, not a task type, and it is available in both worlds.

The annotation cap sharpens this rather than the reverse: at 10 per step, findings have to be ranked or aggregated, which is another argument for structure and none at all for conversion.

**Where it interacts is the single-home argument, which is the proposal's own.** One reporting implementation should serve every guard rather than twenty copies. Once §5's linters are tasks in `build-logic`, that home exists and the findings are already structured — reporting becomes a property of the shared task type rather than something each guard re-implements. So W8 should be done immediately and independently, while W9 is cheapest *after* the conversion rather than before it.

## 5c. Mechanics settled, and the risks that remain

**The Gradle-native option space, measured on 9.0.0** — the choice in §5 is only honest if the mechanisms are known rather than assumed:

| shape | up-to-date | multi-finding reporting | fails the build |
|---|---|---|---|
| custom task, `@InputFiles`, **no declared output** | **never** — re-runs every invocation | one exception | yes |
| + an `@OutputFile` stamp | **UP-TO-DATE** | one exception | yes |
| + **Problems API** `reporter.report(...)` | as above | **yes** — structured id / details / solution / severity, into the HTML problems report, and `lineInFileLocation(path, line)` renders | **NO** — two `Severity.ERROR` problems still gave `BUILD SUCCESSFUL` |
| + `reporter.throwing(VerificationException, …)` | as above | yes | yes, rendering details and solution nested under the message |
| **configuration-time check** — what `routeExclusivelyHostedTests` and `guardInputs`' `doFirst` already are | n/a, runs each configuration | fail-fast exception | yes |

**A third trap is on record in one of §5's own exemplars.** `DocRelativeLinkGuardTest`'s javadoc reports that the guard was green on the author's checkout and **red in every `git worktree`**, and that the first worktree run returned **`FROM-CACHE` green** — because an out-of-repo path cannot be a declared task input, so the cache key could not see it. Cacheability is presented below as the win; that incident is the same mechanism as a cost, and W6a would have to pin it.

**Two further rows are traps**, and both would otherwise be found the expensive way: a check task **with no output is never up to date**, so a naive conversion re-runs on every build; and the Problems API's `report()` at `ERROR` severity **does not fail anything** — it must be paired with `throwing()`, or the guard becomes exactly the advertised-but-unenforced shape this repository has a standing rule against.

**W6a's task shape is measured, not assumed.** A `@CacheableTask` with `@InputFiles @PathSensitive(RELATIVE)` and an `@OutputFile` stamp: deleting the stamp with inputs unchanged restores **`FROM-CACHE`** with the stamp's content intact, changing an input **re-executes**, and the configuration-cache entry is **stored then reused**. So a converted linter is cacheable across a fresh CI checkout, invalidates correctly, and is configuration-cache-ready — which the current `ext` closures are not (§2). Baseline for W1 to be measured against: this build configures in **~1.15 s** today.

**Four risks are open. None blocks W1–W5; the first blocks W6 outright.**

The native-capture row is worth reading twice. It was raised, then marked VOID on the grounds that the agent attaches per-task and `docGuardTest` has its own JVM — both true, and both true only of the **non-capture** configuration, which is the one configuration where no tracing happens. Reading one branch of a conditional and not the other is the same failure the voiding text claimed to memorialise, committed in the act of memorialising it.

| risk | why it is real | how it closes |
|---|---|---|
| **BLOCKING — the native-image capture DOES trace the guard classes.** *(This row was marked VOID on 2026-09-03 and the voiding was wrong; see below.)* | `build.gradle:419` sets `tracingForNativeImage` from `-PrkNativeAgent`, and under it **only build tooling is excluded** — the `else` branch is the one that excludes every exclusively-owned class. The comment states the design: *"During a capture the exclusion is therefore not applied at all, so the traced set stays a superset."* So during a capture `docGuardTest`'s classes run **inside** the traced `:cli:test` JVM, and two of them drive the CLI in-process (`new CommandLine(new RootCommand())`; `CliTestSupport.run`). Converting them removes that surface permanently | **not by a metadata diff** — `build.gradle:414-418` says outright that comparing against the baseline is non-discriminating here, because the baseline was captured with the class already excluded, and a cold review caught exactly that basis once. It needs a per-class enumeration of product-facing reflection. Until then, W6 does not start |
| **`.githooks/pre-commit` and `test-evidence.ts` key on `TEST-*.xml` mtimes** | a converted `docGuardTest` stops producing them, and the hook's failure direction is to refuse correct commits | a **same-commit obligation of W6b**, not a follow-up: both readers move to the stamp, which carries an mtime and the count |
| **`routeExclusivelyHostedTests` derives `test`'s excludes from owning tasks' *test filters*.** A converted linter has none | net simplification — a non-`Test` task cannot be double-run by the broad lane — but the derivation's denominator shifts mid-migration, and the helper **throws on an empty derivation**, so converting the last class out of an owning task fails the build | sequence it in W6b: retire the owning task in the same commit that converts its last class |
| **The 10-annotation-per-step cap needs a ranking policy** (§5b) | findings beyond it are dropped silently | W9's design; the job summary carries the full set, annotations carry the ranked head |

**And a process note, corrected.** This proposal's recommendation inverted during drafting, and this section previously read that as "stop analysing and start landing". That cites the convergence gate **backwards**: a round that changes what "done" means is the signal to stop and **re-partition** — freeze the observations, name the coupled mechanisms, and give each partition its own acceptance matrix. Which is what the two-part disposition at the top now does: the helper extraction lands, the conversion goes back to being a question.

## 5d. The script layering — measured again, and the answer inverted

**Frozen note on the first version.** This section originally counted "20 scripts that already invoke Gradle"
and concluded that a new rule 3 would move most of them. The count was wrong and so was the conclusion. It is
replaced rather than amended because the disposition changes.

### The census, and why it took four passes

`build.sh` and `gradlew` appear **71 times** across 27 tracked scripts. Three successive regex passes over
those occurrences returned **25, 15 and 20** invoking scripts — every disagreement a Gradle command name that
is not an invocation: inside an `echo` of follow-up instructions (`bump-version.sh`, `gap.sh`,
`gen-native-reflect-metadata.sh`), inside an error string (`interpreter-release-publisher-core.sh`), as a
**grep pattern over changed paths** (`lane-guard.sh` — which turns out never to invoke Gradle at all), and as
a *fabricated* builder in a rehearsal fixture. The defensible answer came from printing all 71 occurrences
and reading each one.

**16 scripts genuinely start a Gradle build.** Applying the blockers:

| what blocks conversion | scripts |
|---|---|
| it IS the launcher | `build.sh` |
| it drives a **fabricated** build, not this one | `rehearse-interpreter-release.sh`, `selftest-prepare-dmtool-source.sh` |
| it **sequences several** Gradle runs — a task cannot re-enter its own build | `capture-native-config.sh` (3), `interpreter-performance.sh` (3), `native-gate.sh` (2), `perf.sh` (2), `interpreter-delivery-footprint.sh` (2), `smoke-interpreter-public-release.sh` (2) |
| it needs a **second checkout** | `verify-build-cache.sh` |
| it is **network/credentialed** | `publish-release.sh` |
| it is a **documented invocation path** | `mutant.sh`, `probe/prep-probe.sh`, `verify-demos.ts` (behind its documented wrapper) |
| **release machinery** | `prepare-dmtool-source.sh` |

That leaves **two**, and neither is the win the rule was written for. `perf-keys.sh` (223 lines) starts one
Gradle run to *produce* a JFR profile and then spends ~160 lines parsing stacks — its problem is analysis
logic in Bash, which the **existing** TypeScript requirement already covers; a Gradle task is the wrong
destination. `interpreter-footprint.sh` (51 lines) is the only genuine rule-3 candidate: argument validation,
a clean-tree precondition, and one call forwarding seven `-P` properties.

**So the standing-requirement change this section asked the owner for would move one 51-line script.** The
recommendation is withdrawn: rule 3 is not worth a change to a standing contract, and W10 is closed.

### Where the room for improvement actually is

**Inside Gradle, not at the shell boundary.** The estate registers **119 tasks** across 13 build scripts.
**28 are untyped** — `tasks.register('name') { … doLast { … } }` — carrying **1 348 lines** of Groovy that no
unit test can reach. That is four times the volume of the four helpers W1–W5 just moved, it is the same
class of defect, and it needs **no rule change and no owner ruling**.

| | untyped tasks | lines | declare outputs | never up to date |
|---|---|---|---|---|
| `verify*` / `check*` | 10 | 431 | 3 | 3 |
| `generate*` / `sync*` | 8 | 375 | 6 | 0 |
| other | 10 | 542 | 6 | 2 |

Concentration matters more than the total: **20 of the 28, and 861 of the 1 348 lines, are in
`interpreter/build.gradle`** — already the largest build script in the repository at 3 607 of its 7 247 Groovy lines. Thirteen of
the 28 declare no output at all, so they re-run on every invocation by construction.

**The class is not hypothetical, and one instance was a live false green.** `verifyInterpreterPurity` enforces
"the filtered field-list traversal has ONE owner" by counting `havingInterp(` per line of `ExprEval.kt`.
Measured both directions on the real file: a comment mentioning the symbol failed a correct tree, and
**commenting the only real call site out left the count at one and the guard reported the invariant holding**.
Counting over code rather than text needs a lexer with Kotlin's nesting and raw-string rules, which is
precisely what a build script cannot test — so `KotlinSource` now lives in `build-logic` with both measured
cases locked, and the guard calls it.

**A third group, already decided.** Twenty-two shell scripts of over 60 code lines never touch Gradle and
carry real logic — **4 311 lines**, led by `perf-trend.sh` (447), `perf-anchor.sh` (285) and
`obligations.sh` (205). These are offenders against the **existing** POSIX/TypeScript requirement, needing no
new rule and no Gradle involvement. They are the largest single pile, and the cheapest to reason about.

### W11's denominator, partitioned

The line count is the wrong headline. **62 of the estate's refusals live inside those 28 untyped closures**
— 62 `throw new GradleException` sites that no unit test can reach and none of which has a does-fire case.
For comparison, `build-logic`'s 16 refusals all have a unit test and 11 have a TestKit case. (Counted two
ways: by walking each closure, and by a per-file `throw new` census — 115 in the Groovy build scripts
overall, 69 of them in `interpreter/build.gradle` alone.)

**24 of the 28 carry a decision**; the other four are wiring (`dependsOn` holders and a 3-line `Delete`) and
should stay where they are. **Only 3 of the 24 reach the project's own `sourceSets`/`configurations`**, and
even those split cleanly — the decision moves, the wiring stays — so the dependency direction that bounds
`build-logic` blocks almost nothing here. **None forks a process.**

| partition | tasks | lines | refusals | why this order |
|---|---|---|---|---|
| **A — `verify*` / `require*`** | 10 | 441 | 22 | pure linters, none reaches project config, and 3 can never be up to date today. The shape `VerifyCompiledPurityTask` already proved |
| **B — `generate*` / `sync*`** | 8 | 375 | 9 | 6 of 8 already declare outputs, so they gain a typed input set and real cacheability for the least work. One reaches project config |
| **C — the rest** | 6 | 517 | 31 | the hardest and the most valuable: half the refusals in a fifth of the tasks |

Two individual observations worth carrying into the work:

- **`interpreterRuntimeClosurePolicyTest` (130 lines) is a unit test written as a Gradle task.** Its own
  description says it "exercises the collector and policy against live-graph and synthetic does-fire
  fixtures" — eight hand-built fixture records and a policy assertion, with no Gradle in the logic at all. It
  is partition C's clearest case and needs no task at the end of it, only a test.
- **`interpreterDeliveryFootprintSnapshot` (260 lines, 30 refusals) lives in an INIT SCRIPT**
  (`scripts/interpreter-delivery-footprint.init.gradle`), not a build script. An init script is applied to a
  build it does not belong to, so it can reach neither `build-logic`'s plugins nor the project's types; it is
  the one item here whose home has to be decided before its content can move.

### Closed: `lane-guard.sh` is deleted, because Gradle already does its job exactly

It narrowed what to run, and the obvious question was why that is not the task graph's job. It is. The script
hand-wrote `adapter <- interpreter`, `cli <- interpreter|adapter`, `interpreter-bridge <- interpreter` as grep
patterns — a **second derivation of a fact Gradle holds exactly**, and it had gone stale: three real edges were
missing (`cli` -> `schemakit`, `schemakit` -> `adapter`, `schemakit` -> `interpreter`), on top of the four
defects its own header recorded. It had been unwired since 2026-08-24.

**The deletion rests on measurement, not on the defect count** (2026-09-04, clean tree at `f239195e9`):

| lane | cold | no-change re-run | always-run tasks on its graph |
|---|---|---|---|
| `:cli:check` | 3 m 43 s | **1.16 s** | 0 |
| root `:test` | 2 m 18 s | **8.3 s** | 2 |
| `:interpreter:check` | 2 m 38 s | **92 s** | 14 |
| `:adapter:test`, `:interpreter-bridge:check`, `:schemakit:test` | — | — | 0 |

`:cli:check` is the lane the guard was written for — a session ran it against a `scripts/`-only diff. When the
change genuinely cannot reach it, the graph answers in **1.16 s**. And the positive control shows the
narrowing is not merely coarse: a `scripts/`-only edit runs `docGuardTest` and `referentGuardTest`
**and nothing else** — exactly the two tasks that declare `scripts/` as inputs.

**One transition costs more, and it is not a narrowing failure.** `generateVersionProperties` records
CLEAN/DIRTY, so the *first* edit after a commit rewrites the packaged provenance snapshot and re-runs
`jar` -> `installDist` -> `publicContractTest` (~3 m 39 s). The build already normalizes this off the test
runtime *classpath*; it is not normalized off the packaged artifact that `publicContractTest` executes. It
fires once per CLEAN/DIRTY transition, never per edit — a second scripts-only edit on an already-dirty tree
costs 6.9 s. A lane guard would not have helped: the artifact genuinely changed.

**The soundness precondition is enforced, which is what makes deletion safe.** Gradle skips correctly only if
inputs are declared, and an under-declared guard is exactly the silent failure. `guardInputs` refuses a guard
task that reads a repo file nothing declares (7 sites), so the narrowing rests on a checked property rather
than on trust.

### The always-run set is four kinds, and only one is a staleness claim

43 `outputs.upToDateWhen { false }` sites (interpreter 22, adapter 11, cli 5, root 5), but only **16 sit on an
everyday lane** at all:

| kind | examples | negotiable? |
|---|---|---|
| **fixers / dumpers** — invoked to *write* a file | `dumpKitApi`, `syncDocSnippets`, `dumpOperatorIndex` | No. You asked for the write; none is on a `check` graph |
| **measurements** | `perfGateTest`, `profile*`, browser first-answer | No — observations are never reusable task outputs |
| **environment probes** | `verifyGraalvmNativeToolchain`, `kernelProbe` | No. The input is the machine, not files |
| **derived-denominator guards** | `leakGuardTest`, `verifyCompileCompleteness` | The only staleness claim, and it is sound |

The staleness claim is **sound in its strong form**, and more sharply than its comment states: `guardInputs`
is *blind* on this class. For a guard whose subject is "every file matching a rule", a file missing from the
declaration is one the guard also never reads — declared-set and read-set shrink together and stay consistent,
so nothing fires and a vacuous scan is indistinguishable from a correct one.

It can still be done properly: a `git ls-files` ValueSource derives the declaration from *the same query that
defines the subject*, so the two cannot diverge — the same derive-one-side fix as `guardInputs` and
`routeExclusivelyHostedTests`, measured at 7 ms including the `git add` case. **It is not worth doing**: the
payoff is ~7 s on one lane and the downside is a silently vacuous leak scan. The existing call stands.

### Where the cost actually is: one wall-clock timestamp

`:interpreter:check`'s 92 s no-op is 91 % five tasks — `assembleInterpreterPublicMirror` (37.2 s) and three
external-consumer verifications (43 s) — and the cause is upstream of all of them. `PublishToMavenRepository`
declares no outputs, so Gradle always executes it, and it regenerates `maven-metadata.xml` with a fresh
`<lastUpdated>`. That is the **whole** difference between two publishes of the same commit: 15 of 95 staged
files, all of them that one file and its four checksum sidecars per publication. Jars and POMs are already
reproducible. The staging directory is a declared *input* of the mirror and the verifiers, so one timestamp
invalidates ~80 s of work on every build. Options and their failure modes are §5f.

### The rule that survives

1. must run **without** a JVM/Gradle → **POSIX `sh`**, logic in TypeScript — unchanged
2. logic with **no Gradle need** → **TypeScript** — unchanged, and 4 311 lines are currently in breach
3. ~~already requires Gradle → a Gradle task~~ — **withdrawn on measurement**: one 51-line candidate
4. **new:** an untyped `tasks.register(name) { doLast { … } }` carrying a decision → a typed task or a
   plain object in `build-logic`, where a unit test can reach the decision and a declared output can make it
   up-to-date. 28 tasks, 1 348 lines, no rule change required

## 5f. The staging timestamp — closed, nothing to build

The cause is settled (§5d): `PublishToMavenRepository` declares no outputs, so it always executes and always
rewrites `<lastUpdated>`. Six ways out were weighed; **all are rejected, and the 92 s stays.**

| option | how it can be wrong |
|---|---|
| **A — exclude `**/maven-metadata.xml*` from the four consumers' declared inputs** | the mirror **ships** that file, so its output stops being a pure function of its declared inputs, and four sites must stay in step |
| **B — normalise `<lastUpdated>` to the commit timestamp** | the `SOURCE_DATE_EPOCH` idiom is standard for *archive entries*; neither the spec nor Maven's guide extends it to `maven-metadata.xml`. Backdating is safe only on a static tree — on a repository that merges metadata server-side a backdated publish can read as older than what it replaces |
| **C — do not delete before publishing** | **refuted by measurement**: with the `Delete` excluded the publish still executed and still rewrote the timestamp. No benefit, and it gives up stale-artifact protection |
| **D — declare the individual jars/POMs** | A plus an artifact enumeration that drifts when a publication is added |
| **E — Gradle input normalisation** | unavailable: `normalization { runtimeClasspath { ignore } }` is classpath-only |
| **F — declare `outputs.dir` on the publish tasks so they can go up to date** | the three publications write disjoint subtrees, so the overlapping-outputs objection is soluble by Gradle's own recommendation. **The blocker is one layer down:** Gradle declares no outputs because publication targets an external service, and correspondingly never made the task's *inputs* complete (`repository` is `@Internal`). Declaring outputs on a task with under-declared inputs converts "runs too often" into "silently publishes a stale artifact" |

**The rule this closes on: an upstream gap does not get a local workaround on the release path.** F is the only
option that addresses the cause, and it is exactly the shape whose failure mode is invisible and expensive.
The measured cost of doing nothing is 92 s on a lane that runs at release time.

**What the investigation did leave behind** is the reason the assembler compares content rather than size and
mtime, now recorded at the `rsync` itself: a reproducible artifact can differ in bytes while its timestamps
are identical by construction, so an mtime-based sync is exactly wrong for this tree.

### 5g. The inversion: the cheap guards on that lane run in NO workflow

Splitting `:interpreter:check` by audience prices the two halves very differently (profile, 2026-09-04):

| half | cost | in CI? |
|---|---|---|
| release qualification — mirror assembly, publication + external-consumer verification | **83.72 s** | no, and correctly so |
| routine guards — `verifyInterpreterPurity`, `interpreterRuntimeClosure`, `interpreterJsRuntimeClosure`, `interpreterRuntimeClosurePolicyTest`, `interpreterAbiGoldenDepthTest`, `verifyInterpreterVersionAlignment` | **0.19 s** | **no — and that is a defect** |

`verifyInterpreterPurity` is the lint enforcing the EUPL import boundary (interpreter rule B, one of the two
that are never scoped down), and the two closure guards enforce NFR-F1's hard zero. They are wired to
`check`, and no workflow runs `check`, so they fire only when someone happens to run the release route
locally.

**Denominator, established rather than assumed.** The CI task graph was enumerated by `--dry-run` over the
exact `ci.yml` command (167 tasks, 38 of them `:interpreter:`), and the extraction was positive-controlled on
five tasks known to be in it. `verifyKitPurity` covers `core`/`rulekit`/`modelkit`/`edit` only; `noBackendTest`
is the kits' degraded-mode lane; `interpreter-bridge` has its own purity task, which CI **does** reach via
`:interpreter-bridge:check`. Nothing else covers the `:interpreter` module.

**The fix is ~0.2 s of guards added to the CI lane** — not a build-logic change, and worth more than the 92 s
that led here.

## 6. Work items

**W1–W5 and W8 landed on 2026-09-03; W10 closed on measurement the same day, and W11/W12 opened in its place. W6a–W6c and W7 remain blocked** on the §5 and §5c findings; W9 and W10 follow their own gates. A blocked row keeps its acceptance criteria so that re-opening it starts from the bar, not from scratch.

| id | item | state | acceptance |
|---|---|---|---|
| W1 | Land `build-logic` as an included build, Kotlin DSL, with `gradle/public-repositories.gradle` **single-sourced** into both settings files via `apply from: …, to: it` | **shipped** | our repositories resolve first in *both* builds, shown by a probe like §4's; `.no-global-gradle-repos`' "This repository has neither" corrected in the same commit; configuration-time and cache impact measured and recorded |
| W2 | Move `verifyCompiledPurity` to Kotlin — smallest, already parameterised, two callers | **shipped** | unit tests for the zero-classes-scanned and missing-`jdeps` refusals; `PurityGuardMechanismTest` re-anchored off `build.contains(...)` |
| W3 | Move `guardInputs`, **typed** — named `@InputFiles` properties, not a `Map<String, ?>` | **shipped** | unit tests for all three refusals (empty tree · undeclared read · the `doLast`-vs-finalizer timing corrected twice on 2026-09-02), including §3's sibling-prefix case; the 26 call sites migrate mechanically |
| W4 | Move `routeExclusivelyHostedTests` **and `dmkitsPom`** to Kotlin | **shipped** | unit tests for the empty-derivation and ambiguous-ownership refusals — the two its own comment says a text guard could not see |
| W5 | A TestKit functional lane proving each plugin **applies** and each refusal **fires** | **shipped** | a does-fire test per refusal, per the refactoring-safety-gate convention; lane cost measured and kept off the inner loop |
| W6a | A shared **repository-linter task type** in `build-logic`: `@InputFiles` subject, structured `(path, line, message, fix)` findings, Problems-API reporting paired with `throwing()`, a declared output stamp, and a floor on the subject size | **BLOCKED** | the two §5c traps pinned by unit tests — no-output means never up to date, and `report()` alone leaves the build green; a does-fire test per refusal |
| W6b | Migrate the corpus linters onto it, starting with `DocsIndexGuardTest` as the pilot (pure file I/O, and the read-observer's own subject) | **BLOCKED** | **the enumeration is part of the deliverable** — classify every guard into §5's three rows with its reason, testing production-class dependency **by usage, not by import** (§5); each migration keeps its floor and gains file/line findings; the read-observer's declared scope narrows in the same commit. `CommandReferencesResolveTest` is already known not to qualify |
| W6c | Re-anchor the remaining build-script substring guards — text → model | **BLOCKED** | a guard whose subject is genuinely a file (`ci.yml`, a version string) keeps its current anchor and is listed as such |
| W8 | **Independent, do first:** upload `**/build/reports/**` on failure and enable the PR-comment job summary | **shipped in part** | a deliberately failed CI run shows the report artifact and the summary; no permission change needed |
| W9 | Give the guards **structured findings** — a list of `(path, line, message, fix)` rather than a concatenated assertion message — and emit `$GITHUB_STEP_SUMMARY` plus capped annotations from one place | proposed | ranked so the 10-per-step cap truncates the least useful; one implementation, homed per §5b |
| W10 | ~~Move Gradle-requiring shell orchestration into `build-logic` tasks~~ | **CLOSED — measured away** | The classification WAS the deliverable and it inverted the answer: 16 scripts genuinely start a build, and every one is blocked (multi-run, second checkout, credentialed, a fabricated build, or a documented command) except `interpreter-footprint.sh`, 51 lines. A change to a standing requirement is not worth one script. No owner decision needed after all; §5d records the census |
| W11 | **Typed, testable tasks for the 28 untyped `doLast` closures** (1 348 lines; 20 of them in `interpreter/build.gradle`) — the same move W1–W5 made for the four helpers, at four times the volume and with no rule change | proposed | per task: the decision becomes a plain object in `build-logic` with unit tests, the task gains declared inputs and an output so it can be up to date, and a does-fire case proves its refusal fires. Start with the `verify*` group (10 tasks, 431 lines) — they are linters and 3 of them can never be up to date today. `KotlinSource` + the `havingInterp(` guard is the worked first instance |
| W12 | **The 4 311 lines of Gradle-free shell carrying logic** — `perf-trend.sh` (447), `perf-anchor.sh` (285), `obligations.sh` (205) and 19 more | proposed | no new rule: these are already in breach of the POSIX/TypeScript requirement. One script at a time, each keeping its selftest, `perf-trend.sh` first as the largest and the one with no Gradle entanglement at all |
| W7 | **Blocked with W6:** whether `Checkout`/`RepoWalk` still need unifying | proposed | 11 of `Checkout`'s 44 users are the linter shape and 33 are not, so this shrinks rather than dissolves — but *how much* is only knowable once W6b has run. Starting it first would build a shared home for a problem the conversion was supposed to remove — and the conversion is no longer scheduled |

### What landed, and where it differs from the acceptance text

**The claim §3 rested on is now the estate's, not a scratch project's.** Six mutants, six kills, each run
against its own control class rather than a lane:

| mutant | control | verdict |
|---|---|---|
| `read.startsWith("$it/")` weakened to `read.startsWith(it)` — the sibling-prefix bug | `GuardInputsTest` | killed |
| `violations(…)` returns `emptyList()` | `CompiledPurityTest` | killed |
| the empty-owner refusal never fires | `ExclusiveHostingTest` | killed |
| the plugin stops THROWING on an empty declared tree (the rule intact, the wiring gutted) | `BuildGuardsFunctionalTest` | killed |
| the plugin stops THROWING on ambiguous ownership | `BuildGuardsFunctionalTest` | killed |
| the single-declared-`File` branch collapses into the collection check | `GuardInputsTest` | killed |

The two plugin-wiring rows are the ones that matter for the argument: they leave every decision correct and
break only the call, which is precisely what a `build.contains(...)` assertion cannot see.

**Four deviations, all stated rather than quietly satisfied:**

- **W3 did not grow named `@InputFiles` properties.** Gradle offers no way to add a typed input property to
  a `Test` task from outside it, and `guardInputs` decorates an existing `Test` task by contract — its whole
  job is to attach a declaration to a lane the consuming script owns. Converting the guards to a task type
  that owns its inputs is W6a, which is blocked. What W3 delivered instead is the half that was actually
  reachable: the decision logic left the untestable closure, the named map is validated, and the deprecated
  execution-time `Task.project` read is gone (every value the doFirst/doLast needs is captured at
  configuration time). The map stays.
- **W8 shipped the artifact upload and not the PR-comment job summary.** This row's acceptance said "no
  permission change needed"; that is wrong for the comment half. Posting a comment requires
  `pull-requests: write`, and this workflow deliberately holds `permissions: contents: read` — widening a
  token on a `pull_request`-triggered workflow is an owner decision, not a side effect of adding an upload.
  **Unverified either way:** its acceptance names "a deliberately failed CI run", and no run has been made.

- **W2's `PurityGuardMechanismTest` is still a `build.contains(...)` check, re-pointed rather than
  re-anchored.** What it asserts is which MECHANISM the build script chose — a task type over class files
  rather than a regex over source — and that subject genuinely is a file, which is the carve-out §6's own
  W6c row makes. What moved off the text is everything that can be moved: the gate's verdicts are unit-tested,
  its refusals are proved to fire, and its reachability rests on CI naming `:verifyKitPurity` in its own
  invocation. The class's javadoc now states each of those three loci and says plainly that it can see none
  of them itself. The bridge gate has no reachability locus at all — it hangs off `:interpreter-bridge:check`,
  and deleting it would make that lane quietly stop checking.

**A fourth deviation, found by writing the tests the acceptance asked for rather than by review:** the port
had silently dropped a refusal. A `FileCollection` resolves a declared `File` whether or not the file exists,
so routing every declared entry through one answered "not empty" for a deleted file — and most `guardInputs`
call sites declare a file rather than a tree, so a deleted doc would have stopped being checked with nothing
said. `GuardInputs.declaredEntryIsEmpty` restores the original branch and both a unit case and a TestKit case
now hold it. Worth stating because the migration read as mechanical and this is the one class that makes a
mechanical migration dangerous.

**Two things the delivery found that the analysis had not.**

- **A package named `build` is invisible to git here.** `.gitignore`'s `build/` matches a directory of that
  name at ANY depth, so `…/a12/dm/build/` — the natural package for build code — was silently unstaged and
  would have been committed as a plugin with no implementation class. The package is `…dm.buildlogic`. Noted
  because W6a would reach for the same name.
- **A `Test` task with no candidate class files is SKIPPED, and a skipped task runs neither its `doFirst`
  nor its `doLast`.** Any functional probe of `guardInputs` therefore needs the synthetic project to carry
  one real test. The shape is recorded in [TESTING-SPEC](TESTING-SPEC.md), because W6a's task type will need
  the same probe.

**The one moved helper with no test of its own is checked against its output instead.** `dmkitsPom` shapes a published artifact and has no verdict to unit-test, so the port was settled by generating all seven POMs on both trees and diffing: **byte-identical**, `dmkits-{core,rulekit,modelkit,edit,schemakit,interpreter-bridge}` and the `dmkits` aggregator. A one-off comparison, deliberately not left behind as a gate — `PublicApiGuardTest` already owns the published surface, and a "byte-identical to before" check proves nothing changed rather than that anything is correct.

**Coverage.** 16 refusal sites in `build-logic/src/main`; every one has a unit test over its rule, and 11 have a TestKit case proving the plugin actually raises it. The five without one are the leak detector (which needs an owned class to defeat its own exclusion), a `jdeps` binary that is absent or exits non-zero (a broken toolchain, not a shape a probe can arrange cheaply), and two capture-time classification refusals whose sibling — the one that actually shipped a defect — does have a case.

**What a cold review changed, and the two rulings it asked for.** The review ran against a moving `HEAD` —
my fault, the diff should have been frozen — and it re-based its own findings rather than reporting stale
ones. Three of its findings were behaviour the port had silently changed: the ownership sweep lost Groovy's
short-circuit and resolved every sibling lane's `testClassesDirs` before exempting it by name; jdeps' stderr
was being fed to the dependency-edge parser; a `null` declared value stopped naming itself and started
NPE-ing. Four more were **vacuity in the new tests**, and all four mutants it named are now killed — the
sharpest being that the observed-reads system-property name is a two-sided contract across two Gradle builds
with nothing holding the ends together, and that a mutant deleting the exclusion *entirely* — the derivation's
whole job — survived every case in the lane. Three advertised invariants had no enforcement locus: root
`test`'s dependency on the unit lane, and CI naming `:verifyKitPurity` and `:noBackendTest`. **One of the
guards I wrote to close that last one was itself vacuous** — `ci.yml` names each task in the prose above the
step, so a whole-file `contains` passed with the task deleted from the command; it reads the invocation line
now, and each of the three was proved to fire by deleting it.

Two rulings the review asked for rather than decided:

- **The empty-input refusal keeps its shortened message.** The dropped `(kernel-decoupling §3b #17, …)` was a
  doc citation in a runtime error; the message already says what broke and what to do, and the class is still
  greppable by name. Declined, not overlooked.
- **`File.readLines()`' charset change is the correct direction, not a regression.** The producer writes with
  `Files.write`, which is UTF-8; Groovy's platform default was the mismatched side.

**Cost, measured after landing** (the figure §4 left open): warm-daemon full configuration went from
**0.52–0.54 s** to **0.64–0.67 s**, about **+0.13 s**. The `:build-logic:test` lane is ~1 s and rides root
`test`; the TestKit lane is ~24 s, rides `check`, and is named explicitly in CI.

**Review checkpoints.** W1 is foundational and takes its own cold review; W2–W4 share one terminal review. **W5** (a new lane) and **W9** (emits CI annotations) each need one. **W11** takes one per partition rather than one for the whole 1 348 lines — the `verify*` group, then the generators — because a single review over 28 tasks is the shape the convergence gate refuses. **W12** needs none per script; its guard is `check-shell-portability.sh` plus each script's own selftest. W8 needs none. W6a–W6c are not scheduled: see the disposition.

## 7. Non-goals

Migrating the 7 455 lines of Groovy build script to the Kotlin DSL — §3 measures that consumers can stay Groovy, and no problem here needs otherwise. Moving `:interpreter`'s 3 604-line script. Converting guards whose subject is the behaviour of our own code — those are tests (§5, third row). Enabling the configuration cache, which is its own decision with its own blast radius. Doing any of this while Partition A of [PRECHECK-PROMOTION-PROPOSAL](PRECHECK-PROMOTION-PROPOSAL.md) is unfinished. Converting the 16 scripts that genuinely start a build — §5d measures that closed.

## 8. What would reopen the parts left out

- **Adopting the configuration cache or project isolation.** Cross-project `rootProject.ext` access and execution-time `Task.project` reads both stop working; the helpers would have to move regardless, and this proposal becomes the prerequisite rather than an improvement.
- ~~**A fourth shared helper.**~~ **Already fired, and was fired before this was written** — `dmkitsPom` is the fourth, with 6 consumers. Kept as a record that a reopen trigger phrased against a count must be checked against the count at the time of writing.
- **A change in how init scripts reach nested builds.** §4's correction rests on a measurement of Gradle 9.0.0 that contradicts a closed upstream issue; a Gradle upgrade should re-run that probe, because the nested build's exposure to a hostile init script depends on it.
- **A measured cost from W1's pilot** — if a nested build's configuration overhead lands somewhere that matters, W2–W6c should be re-argued against a plain `.gradle` script applied by `apply from:`, which is weaker but free.
