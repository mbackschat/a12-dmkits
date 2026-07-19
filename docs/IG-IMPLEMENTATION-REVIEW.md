# IG implementation review

This ledger records read-only implementation-review verdicts for a12-rulekit. It lives with the project documentation so review findings travel across machines. Entries are self-contained so a new agent can verify and act on them without the originating conversation.

## Working rules for follow-up agents

### Reviewers who write chapters

Reviews are read-only unless the owner explicitly asks for changes. A reviewer writing a new chapter independently verifies every cited claim against the live code, makes the chapter self-contained, and records exact evidence, required corrections, and acceptance checks. The implementor rule below does not reduce that review obligation.

### Implementors who resolve chapters

The completed chapter already contains the important facts, evidence, scope, and acceptance checks, assembled through substantial review work. When asked to implement it, **use it as the authoritative work brief: do not restart or repeat the review and do not broaden the audit.** Re-check only the live locations needed to apply the recorded fix and its direct guard. Do not reopen ratified owner decisions unless implementation exposes a concrete contradiction or genuine blocker.

**The default completion condition is to clear the associated IG gap, not to preserve unfinished work by moving it to “deferred” or “ongoing-incremental.”** Finish every residual already named by the chapter, graduate the durable result into its owning finding/specification, and delete the IG row. A gap may remain deferred only when the owner explicitly ratified an external re-open trigger or implementation hits a genuine blocker that cannot be resolved within the authorized scope; record that exception plainly instead of silently reclassifying ordinary unfinished work. This rule applies to every current and future implementation chapter.

Keep corrections focused on the recorded findings. In particular, do not broaden an entry into a rewrite of unconditional “kernel-exact,” “full conformance,” “full fidelity,” or “linear” language. Record the fixing commit and verification results in a resolution note beneath every corrected issue, retain the original verdict as review history, and leave the finished result for an independent reviewer to verify.

---

## Review status

### Active chapters

- [RV-2026-07-19-03 — IG91 → IG87 prepared-model and include-remap chain review](#rv-2026-07-19-03) — **Changes requested.** IF145 leaves the normal CLI gate red; IF146's raw-text remapper is not safe or complete for the legal reference grammar, and IG87 was reclassified as ongoing despite ordinary unfinished correctness/evidence work.

### Resolution index

- [RV01-1](#rv01-1), [RV01-2](#rv01-2), [RV01-3](#rv01-3) — resolved in `f4d31e0b`.
- [CR01-4](#cr01-4) — resolved in `e3cc3762`.
- [RV02-1](#rv02-1), [RV02-2](#rv02-2) — resolved in `e1162cdb`.
- [RV02-3](#rv02-3) — resolved in `be4e4e93` and `a00bfc08`.
- [RV02-4](#rv02-4) — resolved in `be4e4e93`.
- [RV02-5](#rv02-5) — resolved in `d3121905` and `5a25c25d`.

## Active review chapters

<a id="rv-2026-07-19-03"></a>

### RV-2026-07-19-03 — IG91 → IG87 prepared-model and include-remap chain review

**Repository:** `$HOME/Projects/oss/a12/a12-rulekit`

**Review type:** Read-only post-commit review. No implementation, test, specification, or gap-ledger file was changed by this review; this chapter is the only review artifact.

**Reviewed commits and claimed outcomes:**

- `c8124f58`, `f9a09041`, `884bfb0e` — IG91 → IF145: route `model seed` through the shared kernel-free preparation boundary and make `ModelLoader` loudly reject unexpanded includes, unresolved typedefs, and opaque IDs.
- `d18cbcb2`, `7d67c0b5` — IG87 → IF146 increment 1: replace the old depth-based included-condition relativizer with an absolute-prefix remap and evaluate formerly refused bare-root, MODEL_ROOT, and `$`-outer reference forms.

**Verdict:** Changes requested; the chain is not complete. IF145's route-plus-loader-guard design is directionally sound, but it broke a normal repository gate and did not satisfy the original opaque-ID seed evidence arm. IF146 cannot be accepted as a semantics-preserving MOVE implementation: its raw-text scanner misses legal reference positions, rewrites non-reference literal text, does not perform the kernel-required short-name disambiguation, and can retain warnings for content later excluded. The reported `$`-outer and static-Java evidence is also absent. IG87 must return to the active gap section and be fully cleared; “impractical this pass” is not a blocker under this ledger's implementor rule.

<a id="rv03-1"></a>

#### RV03-1 — IF145 leaves the normal CLI gate red

**Severity:** High / release-blocking regression.

**Evidence:**

- `interpreter/.../ModelLoader.requirePrepared` correctly rejects a raw group carrying `modelAlias`.
- `cli/.../ModelLoaderParityTest:42-46` still feeds every discovered fixture directly to both loaders without preparing multifile models first.
- The two committed `multifile/app/storefront.dm.json` fixtures contain `/Storefront/Inventory` with `modelAlias='catalog'`.
- The current `cli/build/test-results/docGuardTest/TEST-io.github.mbackschat.a12.dm.cli.interp.ModelLoaderParityTest.xml` records `38` tests and `2` failures, both the expected `unexpanded include` exception for those storefront fixtures.
- `:cli:test` depends on `:cli:docGuardTest`, so the normal CLI verification path is red. This contradicts IF145's claim in `INTERPRETER-FINDINGS.md` that the CLI loader-parity tests stayed green.

**Required correction:**

- Preserve the new loud loader boundary.
- Make the parity guard compare the loaders over the same prepared representation for multifile fixtures, using the shared resolver/preparer rather than excluding those fixtures.
- Keep an explicit raw-residue rejection arm so preparation cannot accidentally become optional again.
- Correct IF145's green-suite claim only after the actual gate is green.

**Acceptance checks:**

- `ModelLoaderParityTest` covers the multifile fixtures after preparation and both loader implementations agree.
- Raw include, unresolved typedef, and opaque-ID residue still reject through `ModelLoaderPreparedGuardTest` on JVM and Node.
- `./build.sh :cli:docGuardTest` and the normal `:cli:test` dependency path pass.

**Resolution:** Open.

<a id="rv03-2"></a>

#### RV03-2 — IF145's opaque-ID closure evidence cites a different pipeline and omits the required seed integration arm

**Severity:** Medium / incomplete original acceptance contract plus documentation drift.

**Evidence:**

- The original IG91 acceptance text required own-domain include, typedef, **and opaque-ID** seed fixtures.
- `ModelSeedPreparationTest` has only the include and typedef positive paths.
- IF145 says seed's opaque-ID normalization “rides the same `ModelReader.expand` boundary the corpus preparer already locks (`CorpusModelPreparerTest`).”
- That is not the same boundary: `CorpusModelPreparer.toInterpreterJson` performs its own test-only `normalizeIdsToPaths` JSON transform after kernel preparation. It does not call `dm.core.ModelReader.expand`.
- The direct unit evidence for the production transform is `ModelReaderTest.normalizesElementIdsToTheirNamePath`; there is no CLI `model seed` integration fixture proving that an opaque-ID input crosses that production boundary.
- `ModelLoader`'s active KDoc simultaneously says preparation uses the kernel's `expand()` and then names the actual kernel-free `ModelReader.expand`.
- IG68 still says a standalone raw consumer “can silently load a partial/wrong model,” although IF145 now loudly rejects each residue class it can identify. The missing commonMain assembler remains real; silent acceptance of these three residues does not.

**Required correction:**

- Add the missing opaque-ID `model seed` positive integration fixture through the production `ModelReader.expand` route.
- Cite `ModelReaderTest` for its direct unit role and the new seed test for the consumer boundary; do not cite the corpus preparer's independent transform as proof of `ModelReader`.
- Correct the loader KDoc to name the kernel-free preparation path.
- Reword IG68 to distinguish “cannot assemble raw models” from “rejects recognized unprepared residue loudly.”

**Acceptance checks:**

- An opaque-ID source model seeds successfully with the same structure and value constraints as its prepared name-path representation.
- The unprepared opaque input still fails when deliberately passed straight to `ModelLoader`.
- IF145, IG68, `ModelLoader` KDoc, and the named tests describe one factual preparation/refusal lifecycle.

**Resolution:** Open.

<a id="rv03-3"></a>

#### RV03-3 — IF146's character-context scanner is neither reference-complete nor literal-safe

**Severity:** Critical / legal-input silent-wrong behavior.

**Root mechanism:**

`ModelReader.remapAbsolutePaths` scans raw condition characters and treats `/` as a reference start only at text start or after `[`, `(`, `,`, or `$`. It has no lexical state and does not know whether the characters are an entity-reference span, a string literal, or another grammar construct. The invariant IF146 needs is stronger: rewrite every and only parsed entity reference whose resolved pre-mount target moved.

**Confirmed sibling failures from the live grammar and implementation:**

- Legal absolute paths after grammar keywords are missed with `hadResidual=false`, including the second scope in `RepetitionNotUnique(/Benefits/Lines/Code @From /Benefits/Lines)`, an entity-valued `... In /Benefits/Lines*/Code`, and a semantic-index key `[/Benefits/Lines/Value For /Benefits/Lookup]`. The stale root survives silently because the slash follows a keyword letter rather than one of the scanner's opening characters.
- A legal path whose first segment requires keyword quoting, such as `/'Length'/Value`, is missed because the character after `/` is not accepted by `isIdentStart`.
- Text inside a quoted literal can be rewritten as if it were a path. For example, the legal comparison `[Kind] == "(/Benefits"` presents a slash preceded by `(`; the scanner changes the literal to the mount prefix, changing the business value. An unmapped path-like literal can instead produce a false refusal.
- Therefore the comments that every legal absolute reference is remapped, every other token/literal is unchanged, and an unhandled absolute is always refused are all disproven by the same mechanism.

**Relevant kernel evidence, for behavior only:** The include-expansion path is `DocumentModelExpandService.RuleAndComputationConditionUpdaterVisitor` plus `EntityPathsRelativizer`. It identifies parsed entity-reference spans and applies position-based updates; it does not search arbitrary slash-shaped text. The clean-room rule still applies: reproduce the behavior with an original implementation, never copy or transliterate the kernel expression.

**Required root correction:**

- Replace the character-context heuristic with an original reference-aware lexer/parser or span collector that understands every legal entity-reference slot and string/quoted-path boundaries.
- Resolve/remap only genuine reference spans, preserve spelling outside those spans, and retain a structured loud refusal for a genuine unmappable absolute reference.
- Apply the shared mechanism to rule conditions and every computation fragment: common precondition, alternative precondition, and operation.

**Acceptance checks:**

- Positive coverage includes function/bracket arguments, `@From`, entity-valued `In`, semantic-index `For`, outer `$` inside legal `Having`, and quoted path segments across SINGLE_RG, MODEL_ROOT, and a nested mount.
- Negative coverage proves quoted literals, division, relative references, and `$`-relative references remain byte-for-byte unchanged.
- A genuine unknown absolute reference produces the structured CLI refusal end to end; a path-like string literal never does.
- Representative cases match the dynamic-Groovy kernel and static-Java strategy where strategy coverage is claimed.

**Resolution:** Open.

<a id="rv03-4"></a>

#### RV03-4 — IF146 leaves short-name references bound to the wrong post-mount context

**Severity:** High / legal-input ambiguity or wrong-target behavior.

**Evidence:**

- `remapAbsolutePaths` only considers slash-prefixed paths; a bare cross-group short-name reference is left verbatim.
- Kernel include expansion deliberately replaces short-name references when mounting so a name that was unique in the included model cannot become ambiguous or bind differently in the including model.
- The project already documents and locks that a bare short name is a flag-gated, model-wide unique-field lookup. Mounting into a larger model can therefore change its meaning or make it invalid if the serialized short name is retained.
- No IF146 test mounts a model whose included rule/computation uses a cross-group short name and introduces a colliding name in the including model.

**Required correction:**

- Extend the reference-aware remap to bind and rewrite included short-name references to their moved targets before integration.
- Preserve ordinary same-group relative references where their meaning is mount-invariant.
- Handle a collision deterministically according to kernel expansion rather than relying on the post-mount short-name lookup.

**Acceptance checks:**

- A unique included cross-group short name keeps the same target after mounting.
- An including-model name collision does not retarget or invalidate the included rule/computation.
- Flag-on/flag-off behavior and rules/computations agree with both kernel strategies.

**Resolution:** Open.

<a id="rv03-5"></a>

#### RV03-5 — residual warnings can outlive excluded rules/computations and identify elements ambiguously

**Severity:** Medium / false refusal.

**Evidence:**

- `ModelReader.mount` recursively expands included content and accumulates warnings before the outer include's `excludeRules` / `excludeComputations` filter is applied.
- A nested included rule/computation can therefore record an unmappable-reference warning and later be removed by the outer exclusion, while its warning remains in the shared list.
- `includedRulesNeedingRelativization()` is documented/consumed as a location signal but warnings store only `node.name`, so identical rule/computation names in different groups are indistinguishable.
- No test covers an excluded nested residual or duplicate names.

**Required correction:**

- Couple warnings to surviving elements, or perform/remap validation after exclusion, so removed content cannot refuse evaluation.
- Record an unambiguous element name-path and use terminology that covers both rules and computations and the current remap mechanism.

**Acceptance checks:**

- A residual contained only in excluded nested rules/computations produces no warning and no CLI refusal.
- The same residual in retained content refuses.
- Two same-named elements in different groups yield distinguishable locations.

**Resolution:** Open.

<a id="rv03-6"></a>

#### RV03-6 — IG87's claimed differential/strategy coverage does not meet its own acceptance contract

**Severity:** High / blocks graduation and the “chain complete” claim.

**Evidence:**

- The original IG87 required all three residual reference families at mount depths `0/1/n`, against kernel expansion and both evaluation strategies, with rules and computations over each form.
- `IncludeResidualRefDiffTest` currently covers one bare-root rule, one MODEL_ROOT rule, and one descending SINGLE_RG computation operation at one mount path. It calls `CliTestSupport.runKernel`, the dynamic-Groovy path, and contains no static-Java leg.
- It contains no `$`-outer end-to-end case. The current ledger itself admits that residual and says a legal `Having` fixture was “impractical this pass.” `OuterCorrelationDiffTest` already supplies a legal own-domain `Having` shape that can be mounted and adapted.
- It does not exercise computation common preconditions or alternative preconditions, the additional legal reference slots in RV03-3, short names, nested mount depth, or the structured refusal path.
- The IF146/IG87 statement that static-Java coverage “rides IG94's capability × strategy matrix” is false. `CapabilityEvidenceMatrixTest` marks `ASSEMBLY_RAW_COMPOSITION` as excluded under IG68/IG87. Its `ASSEMBLY_PREPARED` static cell points to `CorpusModelPreparerTest`, which covers an already-prepared typedef/ID model, not these include/remap shapes.
- Moving IG87 to “ongoing-incremental” because the fixture was not completed violates this review ledger's completion rule. There is no owner-ratified external trigger and no genuine blocker.

**Required correction:**

- Move IG87 back to the active gap section and broaden it to include RV03-3 through RV03-5; do not present IF146 as a completed legal-shape capability.
- Build the missing legal mounted `$`/`Having` differential and the reference-slot/depth/fragment coverage required above.
- Add real static-Java evidence for representative include shapes before claiming it; update the capability matrix with a truthful derived cell or keep the strategy claim explicitly absent.
- After every named residual is green, graduate the durable behavior into IF146/the owning specification and **delete IG87**, rather than returning it to §2.

**Acceptance checks:**

- The original `0/1/n`, rules/computations, dynamic-Groovy/static-Java contract is either executed or narrowed only by an explicit owner decision.
- The capability matrix resolves to the actual include evidence rather than a coarse unrelated prepared-model test.
- No ordinary unfinished IG87 work remains in the deferred/ongoing section.

**Resolution:** Open.

<a id="rv03-7"></a>

#### RV03-7 — the certificate, findings, test comments, and kernel-source attribution contradict the live implementation

**Severity:** Medium / current contract and executable-comment drift.

**Evidence:**

- `INTERPRETER-DROPIN-CERTIFICATE.md` still says the old relativizer rewrites to depth-relative paths, says MODEL_ROOT/bare/`$` forms remain loud-refused, cites the deleted `flagsAResidualBareRootGroupAbsoluteReferenceItCannotRelativize` test, and repeats the obsolete state in R1 and G1′.
- IF122 in `INTERPRETER-FINDINGS.md` still says G1′ remains a loud-refused capability residual.
- `IncludeEvalDefaultPathTest` comments and names still describe the old relative `../` rewrite even though IF146 now emits post-mount absolute paths.
- IF146 attributes the include behavior to `RulesAndComputationsAdjusterVisitor`, which belongs to decoration-model joining. The relevant kernel include path is `DocumentModelExpandService.RuleAndComputationConditionUpdaterVisitor` with `EntityPathsRelativizer`. This is not a cosmetic distinction: the actual mechanism exposes the reference-span and short-name requirements missed by the current scanner.
- `INTERPRETER-GAPS.md` and IF146 say all three formerly refused shapes evaluate and rules **and** computations are differentially locked, which the current tests do not establish.

**Required correction:**

- After the functional/evidence fixes, sweep the certificate, IF122/IF146, IG87, specification, diagnostic text, and include-test KDocs for one exact current-state account.
- Correct the source attribution while keeping the clean-room prose at the behavioral/mechanism level.
- Keep historical statements only when they are unmistakably historical; remove present-tense refusal or coverage claims that are false.

**Acceptance checks:**

- No active certificate/finding/comment describes the removed depth-relative implementation or the pre-IF146 refusal state as current.
- Every named test exists and proves the claim attached to it.
- Static-Java, rule/computation, and supported-reference wording matches the evidence after RV03-3 through RV03-6 close.

**Resolution:** Open.

#### What the review accepted

- Routing `model seed` through `InterpreterEngine.expandForEval` is the correct shared-boundary direction.
- Enforcing raw include, unresolved typedef, and opaque-ID rejection inside the common `ModelLoader` is the right class-level defense; `ModelLoaderPreparedGuardTest` is green on JVM and Node.
- `ModelSeedPreparationTest` positively locks the include and typedef seed paths.
- The prefix-map construction for the three simple tested condition forms is a useful basis, and retaining a loud refusal for genuinely unsupported references is the right safety posture.
- The five listed commits are narrowly scoped; concurrent proposal/test work visible in the shared worktree is not part of this review.

#### Recommended implementation order

1. Fix RV03-1 first so the normal repository gate is truthful and green.
2. Fix the shared reference mechanism in RV03-3 and RV03-4, then its warning lifecycle in RV03-5.
3. Complete the differential/strategy/depth matrix and close IG87 under RV03-6.
4. Add the opaque-ID seed arm and align IF145/IG68 under RV03-2.
5. Perform the bounded documentation/certificate sweep in RV03-7 inside the same commits as the corresponding functional fixes.

#### Resolution

Open. Independent verification should reject the “IG91 → IG87 chain complete” claim until RV03-1 through RV03-7 are resolved, every issue carries a fixing commit and green verification note, and IG87 is deleted rather than deferred.

## Resolved chapter archive

### RV-2026-07-19-01 — IG76, IG84, and IG86 owner-decision landing review

**Repository:** `$HOME/Projects/oss/a12/a12-rulekit`

**Review type:** Read-only post-commit review; no repository files were modified.

**Reviewed commits and intended decisions:**

- `97899183` — IG76 → IF136: Groovy-dynamic is the normative semantic anchor; when legal input splits dynamic-Groovy from static-Java, the interpreter follows dynamic-Groovy and records the static-Java result as a characterized corpus divergence.
- `66e1fd1f` — IG84 → IF137: candidate NFR numbers remain informative until each number passes the ratified certification protocol; the fair generated-Java `≤1.25×` ratio is informative.
- `8ec65400` — IG86 → IF138: Node is the supported Kotlin/JS runtime; browser support is deferred and unverified until a real consumer or publication promise reopens it.

**Verdict:** Changes requested. The three owner decisions are coherent, but their landing is not yet internally consistent. Three concrete contract/documentation defects remain.

<a id="rv01-1"></a>

#### RV01-1 — IF136 is contradicted by the project’s highest-priority interpreter instructions

**Severity:** High / blocking the claim that IG76 is fully settled.

**Evidence:**

- `CLAUDE.md:91` says: “Where the two strategies diverge, match production codegen.” This is the opposite of IF136’s Groovy-dynamic anchor.
- `interpreter/CLAUDE.md:19` still gives the absolute instruction “MATCH kernel semantics EXACTLY — BOTH evaluation strategies (runtime AND codegen).”
- `interpreter/CLAUDE.md:21` initially frames the pair as the md-runtime dynamic service versus the Groovy code generator and says the interpreter must match both, before later stating the correct IF136 rule: the actual split axis is dynamic-Groovy versus static-Java and dynamic-Groovy wins.

The later clarification does not neutralize the earlier imperative, especially because the root instruction calls this rule catastrophic and says it must hold before any interpreter file is opened. A future agent can reasonably follow the stale root instruction and implement the wrong behavior.

**Required correction:**

- Align both `CLAUDE.md` and `interpreter/CLAUDE.md` with one unambiguous invariant: match both strategies where they agree; on a legal-input dynamic-Groovy/static-Java split, dynamic-Groovy is normative and the static-Java result is characterized but never adopted.
- Describe the strategies as dynamic-Groovy and static-Java, not runtime versus codegen; the dynamic path also uses generated Groovy.
- Preserve the clean-room and “dead in both strategies” rules.

**Acceptance checks:**

- No active interpreter instruction says production codegen wins a divergence.
- No active instruction demands two mutually inconsistent outputs on a characterized split.
- Root and nested instructions, CONFORMANCE §11, the certificate, IF136, and the divergence enforcement test express the same anchor.
- Preserve the `AGENTS.md → CLAUDE.md` symlinks.

**Resolution — implemented in `f4d31e0b`, ready for independent review.** The root and nested interpreter instructions now use one dynamic-Groovy/static-Java evidence axis: both are matched where they agree, dynamic-Groovy is normative on a legal split, and static-Java divergences are recorded but never adopted. The clean-room and dead-in-both rules remain intact. A focused text check found no remaining active instruction that gives production codegen precedence or demands both outputs on a split; the existing symlinks were not touched.

<a id="rv01-2"></a>

#### RV01-2 — IF137’s “only PerfBounds gates” statement is factually overbroad

**Severity:** Medium.

**Evidence:**

- `docs/INTERPRETER-SPEC.md:18` says: “What gates today: only the PerfBounds catastrophe floor” and “Nothing else here gates.”
- `docs/INTERPRETER-SPEC.md:36`, `docs/INTERPRETER-FINDINGS.md` around IF137, and `docs/INTERPRETER-PERFORMANCE.md` repeat that posture.
- Executable performance assertions remain outside `PerfBounds`, including:
  - `interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/DocumentScaleTest.kt:63,81` — two `<20 seconds` scaling assertions.
  - `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/OuterCorrelationPerfTest.kt:86,107` — growth and interpreter/kernel ratio assertions.
  - `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/GroupScopedFillPerfTest.kt:76,86` — growth and ratio assertions.
  - `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/perf/CustomTypePerfTest.kt:66,82` and `CharsetPerfTest.kt:42` — relative-cost and scaling assertions.

These assertions may be diagnostic or catastrophe guards rather than ratified release SLOs, and some may run only in tagged lanes, but they are still executable gates. The owner decision does not require removing them; it requires honest classification.

**Required correction:**

- Say that `PerfBounds` is the only ratified NFR/release-level catastrophe floor under IF137.
- Explicitly acknowledge that other executable diagnostic/catastrophe assertions remain and are not certified NFR bars.
- Do not promote the candidate NFR numbers or the fair-Java `≤1.25×` ratio.

**Acceptance checks:**

- The documentation distinguishes a ratified NFR/release bar from any executable timing or growth assertion.
- Every remaining performance assertion has an honest classification and lane.
- The six-point per-number promotion gate and the paired N/4N median estimator remain unchanged.

**Resolution — implemented in `f4d31e0b`, ready for independent review.** INTERPRETER-SPEC, INTERPRETER-PERFORMANCE, and IF137 now say that `PerfBounds` is the only ratified NFR/release-level floor while the common/default/tagged timing and growth assertions remain executable diagnostics or catastrophe guards. The candidate NFR-S/P/F numbers and fair-Java `≤1.25×` ratio remain informative, and the six-point promotion gate plus paired N/4N estimator are unchanged.

<a id="rv01-3"></a>

#### RV01-3 — IF138’s browser deferral is still contradicted by active public documentation and source KDoc

**Severity:** Medium.

**Evidence:**

- `README.md:81` says the same engine “runs in the native binary and the browser.”
- `README.md:764` says the evaluator runs “in your JVM service, in the GraalVM native image, and in the browser.”
- `interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/load/ModelLoader.kt:25` describes a “browser library” as a standalone consumer.
- `docs/INTERPRETER-SPEC.md:60` lists “dmtool, and the browser” as the arbitrary-model-at-runtime product case.

The module currently has only `js { nodejs() }`; there is no browser target, smoke test, conformance replay, packaging proof, or browser performance evidence. Kotlin Multiplatform/commonMain compatibility makes browser support plausible, not supported.

**Required correction:**

- Replace current browser-support claims with Node-supported wording.
- Where the browser remains relevant, describe it explicitly as a deferred future consumer under IF138.
- Sweep active README, specification, architecture, API documentation, build comments, and source KDocs for equivalent claims; do not alter archived historical proposals unless they make a present-tense support claim.

**Acceptance checks:**

- Active documentation never presents browser execution, packaging, or support as shipped/proven.
- Node remains the supported and tested JS runtime.
- IG86 remains a deferred residual with its existing reopen trigger and coordination with IG85, IG89, and IG90.

**Resolution — implemented in `f4d31e0b`, ready for independent review.** Both public README claims, the `ModelLoader` KDoc, and the INTERPRETER-SPEC product-case footnote now name Node as the supported/tested JS host and browser support as deferred and unverified under IF138. The existing reopen trigger and IG85/IG89/IG90 coordination remain in the owning gap entry.

#### What the review accepted

- IF136’s selected normative anchor and the shared divergence-selector direction are sound.
- IF137 correctly keeps every candidate NFR number, including the fair generated-Java `≤1.25×` ratio, informative until per-number certification.
- IF138 correctly defers browser work rather than building a speculative lane without a consumer.
- Green tests reported for the commits do not invalidate the findings above because the remaining defects are contradictory instructions, classification language, and unsupported capability claims that those tests do not guard.

#### Recommended follow-up order

1. Correct RV01-1 first because it is a top-level implementation instruction capable of causing wrong interpreter behavior.
2. Correct RV01-3 next because README/KDoc currently advertise an unsupported runtime.
3. Clarify RV01-2 after classifying the surviving assertions; no benchmark redesign is required.

#### Resolution

Resolved in `f4d31e0b`; `./build.sh :interpreter:check :cli:docGuardTest` passed on JVM and Node, including interpreter purity and the documentation guards. Independent review remains pending.

---

### CR-2026-07-19-01 — IG81 → IG60 → IG98 cache and correlation performance chain

**Repository:** `$HOME/Projects/oss/a12/a12-rulekit`

**Record type:** Self-contained implementation-completion record supplied after the commits landed. This entry records the reported findings and verification; it is not an independent post-commit review verdict.

**Branch state reported:** All work committed on `main`, not pushed. Concurrent proposal-document changes were excluded from every listed commit.

**Overall status:** The substantive correctness and O(N²) performance chain is reported closed:

- IG81 → IF142: document mutation invalidates every document-derived cache.
- IG60 → IF143: overlay-derived index columns and correlation buckets are cached once per stable computation step.
- IG98 → IF144: multi-star equality correlation uses a bind-depth-correct hash join rather than the pairwise scan.

The narrow 3+ bind-depth evidence residual subsequently closed in `e3cc3762`; IG98 is now fully graduated. The O(N²) cliff and its explicit deeper-scope evidence are both reported closed.

#### CR01-1 — IF142: one mutation boundary invalidates every document-derived cache

**Commits:** `2ceee778`

**Original defect:** `Document.add` invalidated the primary index but could leave document-derived row-context and correlation-bucket caches populated. Query/evaluate → mutate → query/evaluate could therefore reuse stale rows, values, or correlation counts.

**Reported outcome:** Every public document mutation now invalidates all document-derived caches through the complete mutation boundary. This restores the invariant that a document-derived cache cannot outlive the document state from which it was built.

**Expected lock class:** Warm each derived view, mutate the document, then prove the next row-context and correlation query equals the result from an equivalently rebuilt fresh document.

#### CR01-2 — IF143: computation-overlay derived views are cached per stable computation step

**Commits:** `24cee9bd`; measurement/curve record `5f2fbc42`

**Terminology:** A compute overlay is the temporary `(field path, repetition indices) → computed value` layer read ahead of the stored document during one computation cascade. It gives dependent computations read-your-earlier-results behavior without mutating the caller’s document. Cleared and poisoned computed cells are tracked alongside it.

**Original defect:** Overlay-aware semantic-index columns and `$`-correlation buckets were rebuilt from all inner rows for every outer-row evaluation. For `O` outer rows and `I` inner rows this cost `O(O×I)`, quadratic when both dimensions scaled together.

**Reported fix:**

- Allocate a fresh derived-view cache for each computation step.
- Reuse a view over another field across that step’s outer rows because only the current computation’s target column can change during the step.
- Do not cache a view that reads the current computation’s own changing target; rebuild that view to avoid stale self-reference.
- Start the next computation step with a fresh view cache so it sees every prior computation’s completed overlay writes.

**Reported result:** The semantic-index growth curve improved from `18.75×` to `3.6×` for the measured 4× input increase, moving from quadratic-class behavior to linear-class behavior.

**Required enduring evidence:** Large-row compute scenarios for semantic-index reads and equality-correlation reads, each covering stable other-field reads and unstable self-target/write cases, with VALUE/CLEARED/ERRORED semantic parity established before timing.

#### CR01-3 — IF144: multi-star equality correlation uses the correct bind-depth hash-join scope

**Commits:** legality/semantic probe `c7d5d96e`; implementation `c15036b5`

**Original defect:** Multi-star equality correlation was forced onto the naive pairwise fallback. The existing hash-join bucket scope was derived from the single-star leaf parent, which is too narrow when the expression contains multiple stars.

**Reported fix:** Thread `star.bindDepth` into `correlationBuckets`. The bucket scope now widens to the first-star bind depth, matching exactly the row universe traversed by the reference naive scan.

**Deliberate fallback:** A multi-star `CurrentRepetition` self-exclusion remains on the naive path. That shape can remove multiple rows, while the hash join’s current subtraction mechanism represents only one excluded row; using it would be semantically unsound.

**Reported performance result:**

- Before: 4× growth `6.41×`; interpreter/kernel-Java ratio widened from `0.51` to `1.21`.
- After: 4× growth `4.26×`; ratio remained approximately `0.16`.
- Interpretation reported: linear-class scaling and approximately six times faster than kernel-Java on the measured scenario, with a stable relative ratio.

**Reported verification:**

- `MultiStarOuterCorrelationDiffTest` covers the hash-join and deliberate naive-fallback paths with interpreter/kernel agreement.
- `multiStarCorrelationStaysSubQuadratic` protects the growth class.
- Full correlation, `Having`, and property regression sets passed.
- Compute-overlay differentials passed.
- `:interpreter:check` passed on JVM and JS, including purity.
- Documentation guards passed.

<a id="cr01-4"></a>

#### CR01-4 — remaining IG98 evidence residual

**Status:** Resolved.

**Residual:** Add an explicit 3+ star/bind-level differential lock. The bind-depth threading is reported generic and therefore expected to work at deeper levels, but that depth is not yet directly evidenced.

**Closure boundary:** Do not describe IG98 as entirely graduated until the explicit deeper-depth lock lands or receives a different owner disposition. It is accurate to say the O(N²) multi-star correlation cliff is closed by IF144 while the deeper evidence residual remains.

**Resolution — implemented in `e3cc3762`, ready for independent review.** `MultiStarOuterCorrelationDiffTest` now carries an own-domain four-level differential that exercises bind depths 0 and 2, duplicate correlation keys across scopes, and empty/malformed keys against dynamic-Groovy, static-Java, and the interpreter. The targeted command `./build.sh :adapter:test --tests io.github.mbackschat.a12.dm.adapter.laws.MultiStarOuterCorrelationDiffTest` passed, and the IG98 residual row was removed while IF144 and the related status documentation were updated.

#### Lean-spec disposition

**Reported review:** No lean-spec change was required.

- IG81 and IG60 are behavior-preserving cache/invalidation implementation details; the semantic specification models the cascade without requiring the implementation’s overlay mechanism.
- IG98’s behavior was already determined by the composition of the specification’s multi-star binding rule (§07.4) and `$`-correlation rule (§07.3). The probe confirmed those composed rules rather than discovering a new semantic.
- A worked example combining both rules would be optional explanatory polish, not a correctness correction.

#### Independent-review checklist

An agent reviewing this record should:

1. Inspect the four listed commits and verify that no unrelated proposal-document changes entered them.
2. Confirm IF142’s invalidation reaches every document-derived cache and every public mutation path.
3. Confirm IF143’s cache lifetime is exactly one computation step and self-target/join-key mutations bypass caching.
4. Confirm IF144’s bucket key and row scope include `bindDepth`, and that the multi-row self-exclusion shape cannot accidentally enter the hash-join path.
5. Reproduce the semantic differentials before treating any benchmark sample as eligible.
6. Verify the reported benchmark estimator, input sizes, raw timings, and kernel-Java comparison from the committed curve evidence.
7. Confirm IF142/IF143/IF144 are present in the findings, the explicit deeper-bind lock is present in `MultiStarOuterCorrelationDiffTest`, and IG98 no longer remains in §2.

#### Resolution

Implementation chain complete, including the former deeper-bind residual in `e3cc3762`; independent review pending.

---

### RV-2026-07-19-02 — IG97 → IG94 → IG82 chain review

**Repository:** `$HOME/Projects/oss/a12/a12-rulekit`

**Review type:** Read-only post-commit review; no repository files were modified.

**Reviewed commits and claimed state:**

- `0f1bb360` — IG97 → IF139: `validateFull` fails loudly when validation reports unsupported rules; IG97 closed.
- `f1adf735` — IG94 → IF140 increment 1: capability × strategy evidence matrix; IG94 remains ongoing in §2.
- `b7e53566` — IG82 → IF141 increment 1: deleted-gap reference guard and initial stale-comment sweep; IG82 remains ongoing in §2.

**Selected IG94 increment-1 contract:** cover the formal, computation, configuration, customization, platform, and assembly axes; classify every capability as dynamic evidence plus static evidence or a reasoned exclusion; fail on capability omission; and make named evidence tests resolve. Per-cell coverage tiers and certificate generation were follow-on work.

**Verdict:** Changes requested. IF139’s runtime mechanism is sound and IF141’s bounded sweep was useful, but the chain does not yet support the claim that the selected increments are complete. IF140 misses core acceptance conditions and overstates evidence; IF141’s guard has real coverage and classifier defects; IF139 lacks the evidence required to close the full reported class.

<a id="rv02-1"></a>

#### RV02-1 — IF140 omits two required axes and cannot detect capability omission

**Severity:** High / blocks acceptance of IG94 increment 1.

**Evidence:**

- `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/CapabilityEvidenceMatrixTest.kt:31` defines only `OPERATOR`, `FORMAL`, `COMPUTATION`, `CONFIG`, and `CUSTOMIZATION`. The selected increment required `PLATFORM` and `ASSEMBLY`; neither exists.
- The curated `matrix` list is its own capability universe. Deleting any capability row leaves the tests green.
- `everyCapabilityIsClassifiedExactlyOnce` at lines 106–110 checks only duplicate `(axis, id)` pairs.
- The partition assertion at lines 143–146 is guaranteed by the sealed `Coverage` type: every row must already be either `Evidence` or `Excluded`.
- `docs/INTERPRETER-GAPS.md:73` defers live-universe derivation and no longer explicitly preserves the omitted platform and assembly axes, allowing them to disappear from the residual contract.

**Required correction:**

- Restore explicit `PLATFORM` and `ASSEMBLY` axes with current capability classifications.
- Derive or independently declare the expected axis/capability universe so deleting or adding an unclassified capability fails.
- Keep per-cell coverage tiers and certificate generation as the documented follow-on; omission detection was part of increment 1, not that follow-on.

**Acceptance checks:**

- Removing one matrix capability produces a failing test.
- Adding a capability to a live source universe without classifying it produces a failing test.
- Every selected axis is represented, including platform and assembly.
- IG94 §2 explicitly retains every unfinished axis and follow-on dimension.

**Resolution — implemented in `e1162cdb`, ready for independent review.** The matrix now contains all seven axes: 110 operator cells derived from the live catalog plus an independently declared 23-cell non-operator universe spanning formal, computation, configuration, customization, platform, and assembly. Focused mutation canaries prove that removing a row, adding an unclassified universe entry, duplicating a row, or leaving an axis empty fails. The full tier and certificate-generation follow-on also landed, so IG94 was graduated to IF140 and deleted rather than retained in §2.

<a id="rv02-2"></a>

#### RV02-2 — IF140’s tri-engine meter and evidence links overstate what the cited tests prove

**Severity:** High.

**Evidence:**

- `CapabilityEvidenceMatrixTest.kt:52–58` classifies the single “operators (110/110, derived)” cell as tri-engine using `InterpreterKernelFuzzTest`.
- `adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/CatalogParityTest.kt:81–130` explicitly carries 54 fuzz-pending callables. The fuzz test therefore cannot prove static-Java evidence for all 110 operators.
- IF140’s derived “4 tri-engine” total counts that coarse operator cell as fully tri-engine and is repeated in the findings, gaps ledger, and certificate.
- `CapabilityEvidenceMatrixTest.kt:79–80` labels one capability “multi-field / cross-root” but cites `MultiComputationFieldDiffTest`, whose model has one `/Quote` root. The actual cross-root static tri-check is `MultiRootComputationPointerDiffTest.multiRootCodegenAgrees` around lines 89–98.
- `CapabilityEvidenceMatrixTest.kt:99–100` cites `ValidateFullDiffTest` for unsupported-rule safety while its own comment correctly says a message-only differential cannot prove that unsupported work was not skipped. The real `ValidateFullUnsupportedSafetyTest` is mentioned only in a reason string, not referenced as evidence.
- The “tracked reason” guards require only a nonblank string; they do not verify a live IG/IF reference or that the cited test contains the claimed evidence.

**Required correction:**

- Split or conservatively classify the operator evidence so fuzz-pending callables are not counted as proven by the fuzz class.
- Point each capability to the test class or method that actually exercises it.
- Represent IF139’s multiplatform safety lock as real evidence rather than prose attached to an unrelated differential.
- Recompute and correct every derived count and certificate statement after the evidence classifications are truthful.

**Acceptance checks:**

- No capability is classified tri-engine from a test that exercises only a subset of its declared scope.
- Cross-root and unsupported-safety cells resolve to their actual locks.
- The reported tri-engine/dynamic-only/excluded totals are mechanically derived from accurate cells.

**Resolution — implemented in `e1162cdb`, ready for independent review.** `CatalogOperatorStrategyDiffTest` derives the ordinary catalog examples into one model and evaluates it through the interpreter, Groovy-dynamic, and generated Java; `DateRange`, `CustomCondition`, `ValidType`, and `InvalidType` point to their exact specialist tri-checks instead of being inferred from the partial fuzz net. All 13 missing static-Java checks landed in their dedicated differentials, cross-root points to `MultiRootComputationPointerDiffTest`, and IF139 safety points to its real kernel-legality/fail-loud lock without inflating strategy evidence. The generated meter and certificate now report 110 live operator tri-checks, 17 non-operator tri-checks, 6 reasoned exclusions, and 133 total accounted cells.

<a id="rv02-3"></a>

#### RV02-3 — IF141 excludes a real interpreter-gap source surface and misses a live stale claim

**Severity:** Medium.

**Evidence:**

- `cli/src/test/java/io/github/mbackschat/a12/dm/cli/StaleGapReferenceGuardTest.java:42–50` scans only interpreter, adapter, and CLI sources and asserts that kit modules contain no interpreter IGs.
- `core/src/main/java/io/github/mbackschat/a12/dm/core/ModelReader.java:126–131` describes an active preparation/refusal limitation as “does not (yet)” and cites deleted IG67.
- `ModelReader.java:322–328` repeats the active residual with another IG67 citation.
- The current durable references are the executed retirement/preparation findings and current assembly/reference gaps, including IF122/IG87 as applicable. The two source comments must be re-verified against the exact owning entry before repointing.
- This residual is absent from IG82’s §2 remaining-work list.

**Required correction:**

- Derive or broaden the scanned source surface rather than assuming interpreter-related claims cannot live in shared kit modules.
- Repoint the two `ModelReader` comments to their verified current durable home.
- Add the missed class to IG82’s remaining work until it is swept.

**Acceptance checks:**

- The guard scans every repository source/build surface that can carry an interpreter IG reference, or derives that file set.
- A present-tense deleted-gap reference in `core` fails the guard.
- The known `ModelReader` references no longer point to a deleted gap.

**Resolution — implemented in `be4e4e93` and completed in `a00bfc08`, ready for independent review.** The guard now derives the root and every module’s source/build surfaces, and focused discovery assertions include the shared kit modules. Both `ModelReader` references point to executed IF122 with IG87 as the remaining legal-reference-shape gap. The closing IG82 sweep corrected every other named stale reference/content/mechanism item and deleted the IG82 row rather than leaving it ongoing.

<a id="rv02-4"></a>

#### RV02-4 — IF141 classifies whole lines rather than individual references and has no positive/negative canaries

**Severity:** Medium.

**Evidence:**

- At `StaleGapReferenceGuardTest.java:123–133`, one historical token exempts every IG reference on the line, while one open token applies to every IG on the line.
- Consequently, a line equivalent to `IG20 graduated; IG80 pending` false-negatives the open IG80 claim, while `IG94 pending; IG20 provenance` false-positives the historical IG20 reference.
- Wrapped comments escape because the open phrase and IG token must occur on one physical line. The live `ModelReader` “does not (yet)” wording demonstrates both a phrase variant and a line-boundary escape.
- No focused classifier fixtures prove detection of a deleted/open reference or acceptance of live and explicitly historical references. A broken regex can therefore remain green when the repository happens to contain no triggering line.
- `live.size() >= 15` at lines 68–70 creates an artificial backlog floor: after enough legitimate graduations, the guard fails despite a valid ledger.
- The failure message offers `[IGn]` as an allowed historical form, but the historical regex does not recognize that form.

**Required correction:**

- Extract reference-local classification and lock it with focused positive and negative cases, including multiple references on one line, wrapped comments, capitalization/phrase variants, live gaps, deleted open gaps, and explicit history.
- Replace the fixed minimum live-gap count with structural ledger validation that remains valid when gaps close.
- Keep diagnostics and accepted historical forms aligned with the actual classifier.

**Acceptance checks:**

- Each mixed-reference discriminator above produces the correct result.
- The test suite contains a positive canary that fails if detection is disabled.
- Closing any number of legitimate gaps does not trip an arbitrary count floor.

**Resolution — implemented in `be4e4e93`, ready for independent review.** `StaleGapReferenceClassifier` classifies each `IGn` from its nearest open/history marker inside a logical block or consecutive-line comment. Its focused test covers the positive canary, both mixed-reference discriminators, wrapped comments, capitalization and `does not (yet)`, live gaps, deleted open gaps, and explicit history. Ledger title/section/row-shape/duplicate checks replaced the fixed live-gap minimum, and the diagnostic now lists only historical forms the classifier accepts. `./build.sh :cli:staleGapGuardTest :cli:docGuardTest --rerun-tasks` passed.

<a id="rv02-5"></a>

#### RV02-5 — IF139’s implementation is generic and correct, but its closure test does not cover the required legal-input class

**Severity:** Medium.

**Evidence:**

- `interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/DmInterpreter.kt:93–102` correctly calls `validate`, throws when `unsupported` is non-empty, and returns messages unchanged on the supported path.
- `interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/ValidateFullUnsupportedSafetyTest.kt:26–36` calls a semantic-index `For "K1"` expression over `/Order/Items` but declares no index field.
- `docs/KERNEL-FINDINGS.md:1319–1324` records that the repeated group must declare `indexFieldName`; otherwise the kernel rejects the model with `MVK_NO_INDEX_FIELD`. The test’s “kernel-legal” description is therefore false.
- IG97 explicitly required two safety arms: a legal-but-unsupported construct and a missing external registration. The new test covers neither with valid evidence through `validateFull`; existing customization tests only establish unsupported reporting through the tolerant `validate` API.

**Required correction:**

- Make the semantic-index fixture kernel-legal by declaring and populating a real index field, then verify the construct remains the intended interpreter-unsupported discriminator.
- Add a missing custom-condition or custom-field-type registration case that calls `validateFull` and proves fail-loud behavior.
- Retain the supported-only control.

**Acceptance checks:**

- The legal unsupported fixture passes the real kernel’s structural gate before exercising the interpreter.
- Both the legal-unmodeled and missing-registration siblings fail loudly through `validateFull`.
- `validate` still exposes each unsupported report, and supported models remain unchanged on JVM and JS.

**Resolution — implemented in `d3121905` and corrected at the real-kernel boundary in `5a25c25d`, ready for independent review.** The first follow-up declared/populated the missing index and added the unregistered `CustomCondition` sibling, but the new kernel bridge exposed a second structural defect in the original discriminator: `FieldValuesNotUnique(Number semantic-index, CustomerName STRING)` violates `MVK_VARYING_TYPES_NOT_ALLOWED`. The final fixture uses a typed composed `RepetitionNotUnique` placement that `RuleValidator` accepts, then proves tolerant `validate()` reports it and `validateFull` fails loudly; the missing-registration sibling and supported control remain. Focused JVM, Node, adapter bridge, and capability-matrix tests passed.

#### What the review accepted

- IF139’s production implementation closes the false-clean mechanism generically; the defect is in its closure evidence, not the `validateFull` check itself.
- IF141 correctly repointed the six IG80 performance references to IF132.
- IF141’s removal of `FuzzDiagnostics.ignoreCodes`/`filterCodes` is behavior-preserving because no caller supplied a non-empty filter and IF42 closed the old boundary.
- At review time the chapter recommended keeping IG94 and IG82 ongoing until their residuals were complete. The ledger's implementor rule now makes the closure boundary explicit: both residual sets were completed, graduated to IF140/IF141, and deleted from the gaps ledger.

#### Recommended follow-up order

1. Repair IF140’s universe/axes and evidence classifications first because the current matrix can mechanically certify an omission and publish misleading coverage totals.
2. Repair IF141’s source coverage and classifier canaries, then sweep the proven `ModelReader` stale references.
3. Complete IF139’s two required evidence arms; the runtime implementation itself need not be redesigned.

#### Resolution

Resolved in `e1162cdb`, `be4e4e93`, `a00bfc08`, `d3121905`, and `5a25c25d`. IG94, IG82, and IG97 are fully graduated; their gap rows are deleted. The focused capability-matrix/catalog-strategy tests, JVM+Node unsupported-safety tests, real-kernel legality bridge, stale-reference guard, and documentation guards relevant to these corrections passed. Independent review remains pending.
