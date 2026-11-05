# Kernel-upgrade runbook — when a12-kernel(-mm) changes

rulekit pins one kernel (`ext.kernelVersion` in the root `build.gradle`, the single pin every module reads) and never reimplements its truth — the kernel is the oracle (N3). Every place rulekit encodes a kernel assumption is guarded by a CI test against the pinned kernel, so a kernel change surfaces as a **precise, actionable test failure** rather than silent drift. This is the deliberate split behind F11: **quiet tolerance at runtime, loud detection at build time.** A user running against a newer kernel sees at most a gentle advisory (never a false "corrupt"); the *maintainer* gets a hard failure the moment an encoded assumption diverges. This doc is the procedure for realigning when that happens.

## The release log — read it before the bump <a id="release-log"></a>

A12 publishes the kernel changelog at <https://geta12.com/#/releases/releases-overview/kernel>. It is the upgrade's change inventory, so every upgrade reads the entries between the old pin and the new one, and only those: entries above the new pin belong to the next upgrade. The site is a single-page app, so the log is not served as a page; [`kernel-changelog.ts`](../scripts/typescript/kernel-changelog.ts) extracts it from the site's bundle as markdown:

```bash
scripts/run-typescript.sh "kernel changelog" scripts/typescript/kernel-changelog.ts --from 30.8.1 --to 31.1.1
```

`--from` is exclusive and `--to` inclusive, by version number, so the maintenance releases of the old line that carry a higher number than the old pin are in the range too. Where Node cannot reach the site but `curl` can, save the bundle that `https://geta12.com/` names and pass it with `--bundle <file>`.

The log is a source to check, not an oracle: it names the change, while its effect on us is settled by the measurement its claim kind needs ([the oracle table](../CLAUDE.md#kernel-claim-oracles)). Every entry gets a disposition in the upgrade's proposal, as one of: supported, with the component evidence; not applicable, with the reason (a Java API or TypeScript runtime we do not consume, a security fix in a dependency we do not ship); or deferred to the next upgrade.

## Every component supports every new or changed feature <a id="all-components"></a>

A new or changed document-model or rule feature is supported in **all** components, not only where it is first met: rulekit and modelkit (the typed authoring surface, renderer and read-back), `dm.edit`, `dm.core`/`ModelIndex`, schemakit import and export, the portable consistency checker, the interpreter (clean-room, locked by a differential on both kernel strategies), the adapter, and dmtool (verbs, `apply` ops, schemas, manifest, MCP tools and demos). A component the feature cannot reach records why, rather than staying silent. The per-finding consumer matrix in `cli/src/test/resources/kernel-finding-adoption.json` is where each component's state and evidence lives, and an `OPEN` consumer there is unfinished upgrade work. The 31.1.1 migration applied this to SumOfTerms (KF670), the text-and-enumeration comparison (KF655) and document uniqueness criteria (KF671).

## The procedure

1. **Bump the pin.** Update `ext.kernelVersion` and, in lockstep, `ext.a12Distribution` in the root `build.gradle`; `adapter/build.gradle` reads the former. Republish via the sibling `a12-kernel-bootstrap` if the new version is not in `mavenLocal`. The version is whatever the sibling `../a12-kernel` checkout carries, never one found elsewhere on the machine (CLAUDE.md, "Test & sample document models").
2. **Run the lanes.** The checkpoint set in CLAUDE.md "Build": `:cli:check`, root `:test`, `:adapter:test`, `:interpreter:check`, `:schemakit:test`, `:interpreter-bridge:check`, `:consistency:check`. Core guardians fail fast; the adapter canary is the N8 pin+canary for the unstable internal surface.
3. **Read failures by class → act** using the coupling map below. Each guardian failure names exactly what changed and the realignment.
4. **Re-stamp the floor.** Update the catalog's `verifiedAgainst` (in `operators.json`) to the new version once green — that is the F11 verification floor the runtime skew advisory compares against. Set `sinceKernel` on any newly-added operator entries.
5. **Sweep docs.** Record any new kernel fact in `KERNEL-FINDINGS.md`, note rulekit's treatment in `KERNEL-INTEGRATION-NOTES.md`, and (if the bump is a design observation worth keeping) add a finding to `API-FINDINGS.md` — in the same change (docs travel with the code).
6. **Re-run the kernel measurement probes** (`@Tag("kernel-probe")`), which are deliberately absent from every routine lane because only a kernel change can move them:

   ```bash
   ./build.sh -Pfast -PrunKernelProbe :adapter:test --tests "*EngineFootprintReportTest*" -Da12.engine.footprint=true
   ./build.sh -Pfast -PrunKernelProbe :adapter:test --tests "*JavaEngineHeapHistogramReportTest*" -Da12.java.histogram=true
   ```

   They assert nothing, so nothing fails here — read the printed tables and refresh the dated numbers in [`INTERPRETER-PERFORMANCE.md`](INTERPRETER-PERFORMANCE.md) "The same axis against both kernel strategies" plus the ratios quoted in [`INTERPRETER-API-GUIDE.md`](INTERPRETER-API-GUIDE.md), [`../interpreter/README.md`](../interpreter/README.md) and [`../interpreter/public-release/README.md`](../interpreter/public-release/README.md). A materially changed kernel footprint is itself a finding ([IF253](INTERPRETER-FINDINGS.md#if253)).

## The coupling map — guardian ↔ what a failure means ↔ realignment

| rulekit's kernel assumption | guardian (CI test) | a failure means | realignment |
|---|---|---|---|
| each operator renders to text the kernel accepts, with the documented semantics | `CatalogExamplesAreKernelValidTest` (N7) | an operator's syntax/semantics changed | fix the renderer or the catalog `validExample`/`invalidExample`; re-confirm `meaning`/`gotchas` |
| catalog keyword IS a kernel lexer terminal (EN/DE) | `KeywordParityTest` (forward) | a keyword was renamed/removed | update the catalog `keywords` for that operator |
| every kernel **construct** is cataloged or explicitly excluded | `adapter.KernelConstructParityTest` (reverse) | the kernel **added** a construct (new operator!), or removed one we excluded | catalog the new operator, or add it to `EXCLUDED` with a reason; drop stale exclusions |
| every `MVK_*` code is classified | `MvkLedgerTest` + `MVK-LEDGER.md` | a new/removed error code | classify it (erased-by-types / pre-checked / kernel-delegated / pending / experiment-queue) |
| public `IRule`/`IComputation` slots are all handled | `SlotParityTest` | a new accessor (= a new authorable slot) | support it, or record it as a deferred slot |
| the write-gate consistency categories are all classified | `SerializerTierParityTest` (F6) | a `BasicDocumentModelConsistencyCategory` was added | classify it (pre-checked by a builder / n/a-structural) |
| the running kernel version vs the verified floor | `KernelVersionTest` (F11) | informational only — never a hard fail | re-stamp `verifiedAgainst`; the runtime advisory is INFO |
| the a12internal read surface (parser/formatter/meta) still wires up | `adapter.KernelAdapterBootstrapTest` (N8 pin+canary) | the unstable internal tier broke | fix the adapter; kernel types stay confined to it (boundary conversion) |
| the condition formatter's EN↔DE keyword switch is keyed by exactly `Locale.US` / `Locale.GERMANY` (`formatAndTranslateConditionText`, behind `rule`/`computation format`) | `adapter.KernelAdapterFormatTest` | the kernel changed its supported condition-locale set or the translation behavior (an EN request silently yielding German keywords is the tell) | re-map `ConditionLanguage → Locale` in `KernelAdapter`; the kernel still silently defaults unknown locales to German (KERNEL-FINDINGS), so the mapping must stay exact |
| the a12internal **reference-rewrite** surface — `MoveSupportDM.changePathReferencesForMove` + the `getInternDocumentModel` intern-unwrap (the rename/move engine) | `adapter.ReferenceRewriteTest` | the internal rewriter changed (signature, the unwrap-shares-state contract, or its starred-ref handling) | fix `adapter.ReferenceRewriter`; if `*`-refs stop following a rename, the rename/move gate must refuse the affected case (the kernel re-validate is the backstop) |
| every runtime evaluation-core class is classified | `adapter.RtSemanticsLedgerTest` + `RT-SEMANTICS-LEDGER.md` | the kernel added/removed an evaluation class (new/changed runtime semantics) | classify it: drain (source-read → law-lock → findings) or mark infrastructure/out-of-scope; drop stale rows |
| every condition-grammar production is classified | `GrammarProductionLedgerTest` + `GRAMMAR-LEDGER.md` (opt-in, sibling `../a12-kernel`) | the kernel added/removed a syntax shape (a production no keyword/enum reveals) | classify it (rendered / plumbing / entry / legacy / queued); drop stale rows |
| the committed **conformance corpus** records the kernel's runtime outcomes as data | `adapter.CorpusReplayTest` + `CorpusSchemaGuardTest` (the `corpusReplayTest` task, corpus files declared as inputs) | the groovy-dynamic replay leg diverges from a committed `expected`/`computeExpected` — a kernel change altered a captured outcome; or a java-static codegen split appeared | **re-capture**: `-Pfast -PrunCorpusCapture :adapter:test --tests '*CorpusGenerator'` (tri-verified), commit the diff — the diff **is** the behavior-change characterization; `meta.kernelVersion` moves with it (bump `CorpusEngines.KERNEL_VERSION`). Detail: [`CONFORMANCE-CORPUS-SPEC.md`](CONFORMANCE-CORPUS-SPEC.md) §13 / [`../corpus/README.md`](../corpus/README.md) |
| the **portable checker** reports only what A12 reports, and nothing where A12 is silent — every `CheckFamily` encodes measured A12 verdicts | `adapter.laws.CheckerSilenceOnCleanModelsTest`, `…CheckerSilenceOnMutatedModelsTest`, `…CheckerClassAgreementTest` and `…CheckerSilenceOnSynthesizedModelsTest` (the `checkerSilenceGuardTest` task) | a gate A12 runs changed its class, its order, or whether it fires — the checker now over-reports (a model A12 builds drawing a finding) or names a class A12 does not | **re-measure the affected `rulekit.validate.laws` grid against the new kernel, then its `:consistency` mirror**; the family KDocs cite the grid that owns each claim, so the failing class names the grid. Re-derive `SILENT_CLASSES` rather than editing it to match — it is the silence WORK LIST, so an entry arriving is a family that lost reach. The pinned population count moves with the model trees and is stated in the test's own KDoc |
| valid DM structure (schema + constraint laws) | `KernelMmIntegrationTest` (opt-in, needs the `../a12-kernel-mm` clone) | the metamodels or DM shape changed | re-mine the metamodels (own-words, EUPL); update structural pre-checks |
| the **native-image** reflection/resource metadata covers the kernel's reflective hotspots + i18n bundles | the **native gate** (release-time, not CI): the `examples/` showboat demos (byte-match) + the **no-crash battery** ([`../scripts/native-battery.sh`](../scripts/native-battery.sh) — every fixture × read/validate verbs + FieldKind × constraint + invalid conditions per locale) vs the native binary | the kernel reflects on a new type / loads a new resource-bundle → the native binary throws `MissingReflectionRegistrationError` / `MissingResourceException` / `STNoSuchPropertyException` | **redo all THREE native metadata sources in lockstep** — they all derive from the kernel's classes: **(1)** `./scripts/gen-native-reflect-metadata.sh` (re-derives the rule-based whole-package registration of the Jackson-POJO + StringTemplate-codegen surfaces — §3.4), **(2)** `./scripts/capture-native-config.sh` (GraalVM 25; re-runs the test-suite agent capture for the rest), **(3)** confirm the i18n `-H:IncludeResources` glob in `cli/build.gradle` still matches the bundle layout; then rebuild + re-pass the gate; commit both regenerated `reachability-metadata.json` files. **The demo-only gate is insufficient** — it once passed while patterned-field/parser-error paths were broken (§3.5). Detail: [`NATIVE-IMAGE-GRAALVM.md`](NATIVE-IMAGE-GRAALVM.md) §3.4–§3.6 / §5.1 |
| the **public-release EUPL bundle** (LICENSE/NOTICE/THIRD_PARTY_NOTICES/licenses) is for the *pinned* kernel | release-time (not CI) — the bundle is assembled fresh from the `../a12-kernel` checkout | a kernel bump changes the kernel's notices / third-party deps | **re-run [`../scripts/assemble-release-repo.sh`](../scripts/assemble-release-repo.sh)** at the next release (it guards that `../a12-kernel` HEAD matches the pin and copies the current notices). Detail: [`RELEASE-SPEC.md`](RELEASE-SPEC.md) §7 / LICENSING.md "EUPL-1.2 compliance checklist" |

## When `a12-kernel-mm` changes (the metamodels, not the engine)

The metamodels are rulekit's **schema + constraint oracle** (constraint-mining instrument 3), read-only and EUPL — analyze in our own words, never copy. On a change: refresh the sibling `../a12-kernel-mm` clone and run `KernelMmIntegrationTest` (opt-in; skipped when absent). Re-mine for new `MM_*`/`CT_MODEL_HEADER` structures (the slot/schema oracle) and new constraint rules (the validity-law oracle); fold genuine new structural laws into rulekit's pre-checks and record them in `KERNEL-FINDINGS.md`. Committed fixtures stay our own re-expressions (project CLAUDE.md license rule) — a metamodel change never means copying its domain in.

## Test-driving a change before it ships (dry-run)

You cannot conjure a future kernel, but the **negative-control rehearsals** prove each guardian fires on the corresponding change — run them to rehearse, and keep them as guards-for-the-guardians (a parity test that rots into a no-op is worse than none):

- **New operator** → `adapter.KernelConstructParityTest#rehearsal_aKernelAddingANewConstructIsFlagged` (a fabricated construct is neither covered nor excluded → would fail) + `rehearsal_theCatalogMatchSetIsNonEmpty` (anti-rot).
- **Renamed/removed keyword** → `KeywordParityTest#rehearsal_aDriftedKeywordWouldBeRejected` + `rehearsal_theLexerBundlesAreNonEmpty` (anti-rot).
- **Newer kernel version** → `KernelVersionTest#aNewerRunningKernelYieldsOneAdvisoryInfo` (inject a fake runtime version → the advisory fires, INFO-only).

When a *second* kernel version actually publishes, add the CI version matrix (build/test against floor + latest) — the standing detector that runs every guardian against both, recorded as the F11 carry-forward.

## A major-release migration — the playbook

A patch bump is the procedure above. A major release (a new A12 distribution with a DM format change, renamed modules, new operators) also changes shapes the guardians assume, so it runs as a planned migration. The 31.1.1 migration's record is [its proposal](KERNEL-31-UPGRADE-PROPOSAL.md).

1. **Tag the last verified revision first** (`kernel-<old>-final`). Every later "what changed since the old kernel" question (findings, adoption records, peer letters) is a diff against that tag. Without it, the base is a guess.
2. **Write the change analysis as a proposal whose work-item table carries the state.** Seed it from [the release log](#release-log), one disposition per entry. Partition by mechanism, and order the partitions so each one unblocks the next:
   - build wiring plus the probe tooling (a12-kernel-lean measures through `kernelProbe`/`probeDifferential`, so they get their oracle back first);
   - model format and includes;
   - guardian realignment;
   - runtime requalification;
   - new operators;
   - surfaces plus native;
   - the gated release.
3. **Use the compile errors as the first denominator.** `:adapter:compileJava`, `:adapter:compileTestJava` and `:adapter:compileTestKotlin` against the new kernel list every API break. Fix them before any behaviour work.
4. **Migrate fixtures with A12's own migrator, never by hand.** Use `DocumentModelServiceFactory().createDocumentModelMigrator(null)`, the call `adapter.laws.KernelMmCorpus` makes. Diff each migrated model against its original, and re-express a changed shape only through what the migrator wrote (CLAUDE.md, "NEVER invent a shape A12 already defines").
5. **Make every inventory guardian exact in both directions before updating its expected data.** That covers the construct, MVK, slot, consistency-category and RT-semantics ledgers. Otherwise a removed member reads as still covered.
6. **Amend findings in place.** A measured change to an existing kernel fact is a `Corrected <date> (kernel <new>)` note in its `KF` entry, beside the 30.8-era record it qualifies. Re-pin the entry's review hash in `kernel-finding-adoption.json`.
7. **Send one verdict-delta letter per batch** ([A12-KERNEL-LEAN-HANDBACK](A12-KERNEL-LEAN-HANDBACK.md)). Before calling the series complete, sweep every finding added or amended since the tag against the sent letters, by id and by the finding a correction cites, and send what is uncarried. Record per finding in `kernel-finding-adoption.json` the letter that carries it. A bulk "reported" basis is a claim nobody can point at: on 2026-11-03, 28 of 75 records the migration commit had marked reported appeared in no letter.
8. **Re-capture what is captured, not merely re-run it.** That means the conformance corpus (`meta.kernelVersion` moves with it), the native reflection metadata before any native rebuild ([NATIVE-IMAGE-GRAALVM](NATIVE-IMAGE-GRAALVM.md) §5.1), the perf anchors on an idle machine, and the catalog's `verifiedAgainst`.
9. **Run one simplification pass and one cold review over the whole migration range before the release.** A migration accretes duplicated construction, stale comments and dead overloads behind green tests. Its reviews were per slice, so nothing else looks across the range.
