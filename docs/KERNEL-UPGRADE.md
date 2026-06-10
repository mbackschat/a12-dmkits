# Kernel-upgrade runbook — when a12-kernel(-mm) changes

rulekit pins one kernel (`30.8.1` today) and never reimplements its truth — the kernel is the oracle (N3). Every place rulekit encodes a kernel assumption is guarded by a CI test against the pinned kernel, so a kernel change surfaces as a **precise, actionable test failure** rather than silent drift. This is the deliberate split behind F11: **quiet tolerance at runtime, loud detection at build time.** A user running against a newer kernel sees at most a gentle advisory (never a false "corrupt"); the *maintainer* gets a hard failure the moment an encoded assumption diverges. This doc is the procedure for realigning when that happens.

## The procedure

1. **Bump the pin.** Update `kernelVersion` in `build.gradle` *and* `adapter/build.gradle` (and republish via the sibling `a12-kernel-bootstrap` if the new version isn't in `mavenLocal`).
2. **Run everything.** `./gradlew test` (core + adapter). Core guardians fail fast; the adapter canary is the N8 pin+canary for the unstable internal surface.
3. **Read failures by class → act** using the coupling map below. Each guardian failure names exactly what changed and the realignment.
4. **Re-stamp the floor.** Update the catalog's `verifiedAgainst` (in `operators.json`) to the new version once green — that is the F11 verification floor the runtime skew advisory compares against. Set `sinceKernel` on any newly-added operator entries.
5. **Sweep docs.** Update the §9 board (`API-POC-STUDY.md`), record any new kernel fact in `KERNEL-FINDINGS.md`, and note rulekit's treatment in `KERNEL-INTEGRATION-NOTES.md` — in the same change (docs travel with the code).

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
| every runtime evaluation-core class is classified | `adapter.RtSemanticsLedgerTest` + `RT-SEMANTICS-LEDGER.md` | the kernel added/removed an evaluation class (new/changed runtime semantics) | classify it: drain (source-read → law-lock → findings) or mark infrastructure/out-of-scope; drop stale rows |
| every condition-grammar production is classified | `GrammarProductionLedgerTest` + `GRAMMAR-LEDGER.md` (opt-in, sibling `../a12-kernel`) | the kernel added/removed a syntax shape (a production no keyword/enum reveals) | classify it (rendered / plumbing / entry / legacy / queued); drop stale rows |
| valid DM structure (schema + constraint laws) | `KernelMmIntegrationTest` (opt-in, needs the `../a12-kernel-mm` clone) | the metamodels or DM shape changed | re-mine the metamodels (own-words, EUPL); update structural pre-checks |

## When `a12-kernel-mm` changes (the metamodels, not the engine)

The metamodels are rulekit's **schema + constraint oracle** (constraint-mining instrument 3), read-only and EUPL — analyze in our own words, never copy. On a change: refresh the sibling `../a12-kernel-mm` clone and run `KernelMmIntegrationTest` (opt-in; skipped when absent). Re-mine for new `MM_*`/`CT_MODEL_HEADER` structures (the slot/schema oracle) and new constraint rules (the validity-law oracle); fold genuine new structural laws into rulekit's pre-checks and record them in `KERNEL-FINDINGS.md`. Committed fixtures stay our own re-expressions (project CLAUDE.md license rule) — a metamodel change never means copying its domain in.

## Test-driving a change before it ships (dry-run)

You cannot conjure a future kernel, but the **negative-control rehearsals** prove each guardian fires on the corresponding change — run them to rehearse, and keep them as guards-for-the-guardians (a parity test that rots into a no-op is worse than none):

- **New operator** → `adapter.KernelConstructParityTest#rehearsal_aKernelAddingANewConstructIsFlagged` (a fabricated construct is neither covered nor excluded → would fail) + `rehearsal_theCatalogMatchSetIsNonEmpty` (anti-rot).
- **Renamed/removed keyword** → `KeywordParityTest#rehearsal_aDriftedKeywordWouldBeRejected` + `rehearsal_theLexerBundlesAreNonEmpty` (anti-rot).
- **Newer kernel version** → `KernelVersionTest#aNewerRunningKernelYieldsOneAdvisoryInfo` (inject a fake runtime version → the advisory fires, INFO-only).

When a *second* kernel version actually publishes, add the CI version matrix (build/test against floor + latest) — the standing detector that runs every guardian against both, recorded as the F11 carry-forward.
