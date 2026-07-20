# IG implementation review

This ledger records read-only implementation-review verdicts for a12-rulekit. It lives with the project documentation so review findings travel across machines. Entries are self-contained so a new agent can verify and act on them without the originating conversation.

## Working rules for follow-up agents

### Reviewers who write chapters

Reviews are read-only unless the owner explicitly asks for changes. A reviewer writing a new chapter independently verifies every cited claim against the live code, makes the chapter self-contained, and records exact evidence, required corrections, and acceptance checks. The implementor rule below does not reduce that review obligation.

### Implementors who resolve chapters

Treat the completed chapter as the authoritative and complete work brief: it already contains the reviewed facts, evidence, scope, corrections, and acceptance checks. Follow the reviewer's explicit instructions exactly as written; do not restart or repeat the review, add to or question the instructions, reinterpret them, broaden the audit, reopen ratified decisions, redesign adjacent surfaces, or edit unrelated proposals/documents. Re-check only the live locations needed for the recorded fix and its direct guard, unless implementation exposes a concrete contradiction or genuine blocker.

Resolve the chapter immediately with the smallest clean root fix and its named acceptance checks; do not overthink it. Completion means clearing the associated IG gap: finish every named residual, graduate the durable result into its owning finding/specification, and delete the IG row rather than moving ordinary unfinished work to “deferred” or “ongoing-incremental.” Deferral is allowed only for an owner-ratified external re-open trigger or a genuine in-scope blocker that cannot be resolved, and that exception must be recorded plainly.

Keep every correction within the recorded findings, including the instruction not to broaden work into rewrites of unconditional “kernel-exact,” “full conformance,” “full fidelity,” or “linear” language. Verify and commit the fix, add the commit and verification evidence beneath every resolved issue, preserve the original verdict as review history, and leave the result ready for independent review. These rules bind the primary implementer and every delegated subagent for every current and future implementation chapter; the primary implementer must include them in delegated work and enforce them during integration.

---

## Review status

### Active chapters

- [RV-2026-07-20-01 — IG89 → IF153 and IG90 → IF154 JS host-semantics review](#rv-2026-07-20-01--ig89--if153-and-ig90--if154-js-host-semantics-review)
- [RV-2026-07-20-02 — IG83 → IF155 Route-B timezone review](#rv-2026-07-20-02--ig83--if155-route-b-timezone-review)

### Resolution index

None.

## RV-2026-07-20-01 — IG89 → IF153 and IG90 → IF154 JS host-semantics review

**Verdict:** Changes required. Commits `41ecfc51` (IF153) and `128f3f99` (IF154) contain useful partial fixes, but their closure/evidence language exceeds the implemented behavior. IG89 must be restored as open, IG90 must remain open with a broader legal-pattern inventory, and the authoring gap must describe the kernel's actual limited blacklist rather than a Java/JavaScript portability guarantee.

**Reviewed scope:** `41ecfc51` (`feat(interpreter): honor the MathContext(50)/plain-render decimal contract on JS`), `517ef65b` (IG90 research), and `128f3f99` (`feat(interpreter): carry Java-Pattern semantics on JS and compile patterns once`). The unrelated proposal documents were not reviewed or changed. `0fb913f3` was checked only for its claimed documentation-index effect.

### What the review accepts

- IF153 genuinely isolates the interpreter's big.js constructor from mutable host globals, caps multiplication at 50 significant digits, fixes ordinary-range plain rendering, and changes negative power to the kernel's reciprocal-first form. `DecimalPrecisionParityDiffTest` provides real tri-engine evidence for the negative-power discriminator.
- IF154 genuinely removes the condition evaluator's per-row pattern compilation and adds correct Java-semantics translations for the exercised `\R`, `\h`, `\H`, `\v`, and `\V` cases. The common JVM/Node tests are useful primitive-level evidence for those cases.
- The owner decision remains unchanged: Groovy-dynamic/Java `Pattern` is the normative behavior on a legal strategy split (IF136). The kernel's raw-RegExp TypeScript behavior is evidence of a strategy divergence, not the interpreter's target.

### RV-2026-07-20-01-1 — the decimal contract is still observably wrong

**Severity:** High.

**Evidence:** The kernel applies one `MathContext(50)` to addition, subtraction, multiplication, division, and power in `VkBigDecimal` (`add`, `subtract`, `multiply`, `divide`, and `power`). The interpreter still performs exact addition/subtraction on both hosts: [`Dec.jvm.kt`](../interpreter/src/jvmMain/kotlin/io/github/mbackschat/a12/dm/interpreter/Dec.jvm.kt) uses `v + other.v` / `v - other.v`, and [`Dec.js.kt`](../interpreter/src/jsMain/kotlin/io/github/mbackschat/a12/dm/interpreter/Dec.js.kt) uses uncapped `plus` / `minus`. Their comments claiming that the kernel never caps these operations contradict the live kernel source.

The JS positive-power implementation is also not equivalent to `BigDecimal.pow(exponent, MathContext(50))`: it computes an exact big.js power and rounds once afterward, while Java uses a bounded working-precision algorithm with intermediate rounding. Concrete legal discriminators include `0.473471768303411 ^ 7`, where Java/kernel semantics end in `...2493319` and current JS ends in `...2493318`, and `4.870781059298 ^ 52`, where the Java/kernel value ends in `...293586` and current JS ends in `...293587`; the latter split survives the scale-19 comparison boundary. IF153 itself calls this a “theoretical residual,” but it is concrete.

The JS division helper rounds once to a decimal-place budget and then again with `prec(50)`, so a guard digit can be rounded twice. For `17980052662979 / 368740497464577`, Java/kernel produces `0.048760721392437376732357175376404888164903531794647`, while current JS produces the value ending in `...794648`. Its magnitude-sized `DP` can also exceed big.js's one-million-place limit on a legal nested power denominator and throw instead of returning the Java value.

Finally, setting big.js `PE`/`NE` to `±1,000,000` does not make rendering unconditionally plain: big.js switches notation at those inclusive boundaries. `display()` and `strippedParts()` therefore still mis-handle reachable exponent text at the boundary.

**Required correction:** Restore IG89 as open. Implement the kernel's one-rounding `MathContext(50)` behavior for every arithmetic operation, including `+`/`-`, positive and reciprocal-first negative powers, and division without double rounding or big.js configuration-limit failures. Produce plain rendering independently of finite exponent-switch sentinels. Use an original clean-room algorithm; do not transliterate kernel/OpenJDK source.

**Acceptance checks:** Add JVM/Node exact-value locks for `+`, `-`, the positive-power discriminators above, the division discriminator above, negative power, and exponent-render boundaries. Add tri-engine observable differentials where the result reaches rule/computation behavior. Cover `0^0`, `0^-1`, exponent boundaries `±1000`/outside, and nested legal magnitudes. Add a power-growth guard proving that JS does not materialize the entire exact `base^1000` result before rounding.

**Resolution:** Open.

### RV-2026-07-20-01-2 — IF153's status and evidence prose overstate the partial fix

**Severity:** Medium.

**Evidence:** [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md) labels IF153 “SHIPPED” and “graduated from IG89” while its own final paragraph admits an unresolved positive-power split. [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md), the architecture, certificate, performance, specification, build comment, and related findings then describe JS decimal parity as closed. `DecimalHostParityTest` says all expected values are anchored to both real kernel strategies, but the differential added by IF153 anchors only the negative-power case. IF16 still carries the older and now separately stale `Big.DP` description.

**Required correction:** Describe IF153 as the accepted partial increment listed above, keep IG89 live until issue 1 is complete, and sweep only the direct IF153/IG89 claims so no document or test comment says the entire decimal contract is closed or differentially proved.

**Acceptance checks:** `rg` finds no “IG89 closed,” “JS decimal parity shipped,” “full MathContext parity,” or equivalent claim outside explicitly historical review text while IG89 remains open. Every evidence statement names whether it is a common JVM/Node primitive lock or a real-kernel tri-engine differential.

**Resolution:** Open.

### RV-2026-07-20-01-3 — IG90's legal-pattern inventory is materially incomplete

**Severity:** High.

**Evidence:** The kernel's `PatternUtils.isPatternValid` is a limited textual blacklist, not proof of Java/JavaScript compatibility. It forbids possessive quantifiers, every `(?<...` prefix (lookbehind and named captures), `(?>...)`, the exact escape letters `\A \G \Z \z \a \e \p \Q \E`, and a nested unescaped `[` form. It does not forbid uppercase `\P`, every class intersection, or the rest of Java's regex vocabulary. [`JavaPatternToJs.kt`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/regex/JavaPatternToJs.kt) rewrites only `R/h/H/v/V` and passes everything else through to an unflagged JS `RegExp`.

Verified kernel-legal Java-vs-JS discriminators include:

- `\s` over NBSP U+00A0: Java false, JS true; this already changes the built-in `EMail` predefined type because it uses `[^@\s]`.
- `.` over NEL U+0085: Java false, JS true; over a supplementary code point such as 😀, Java matches the code point while an unflagged JS dot consumes one surrogate and the whole-value match fails.
- `(a|(b))\2c` over `ac`: Java false, JS true because unmatched-backreference behavior differs.
- `[a-z&&c-e]+` over `a`: Java false, JS true; the blacklist catches nested-class intersection syntax, not all intersections.
- `\P{L}+`, `\x{1F600}`, `\N{LATIN CAPITAL LETTER A}`, `\X`, and `\b{g}` are Java-valid and escape the blacklist but are rejected or silently misread by JS.

The current docs instead say only `R/h/H/v/V` and inline flags diverge and classify ordinary `\s` as identical. That premise is false.

**Required correction:** Broaden IG90 to derive and version the complete kernel-legal Java-Pattern contract, then implement it with a proven ES2018-safe translation or a compatible engine. Do not narrow the authoring language to hide interpreter deficiencies. Any legal construct not yet reproduced on JS must degrade through the interpreter's unsupported boundary rather than crash model construction/evaluation.

**Acceptance checks:** Add the concrete discriminators above and sibling classes to JVM/Node locks and tri-engine tests. Exercise both condition operators, declared STRING patterns, and consumer-supplied predefined-type patterns. Include an end-to-end built-in `EMail` NBSP discriminator. No kernel-legal pattern may throw a host `SyntaxError`.

**Resolution:** Open.

### RV-2026-07-20-01-4 — the translator and residual detector have independent parser defects

**Severity:** High.

**Evidence:** The translator treats every `]` as the end of a character class, although Java permits a leading `]` as a literal. A kernel-legal pattern such as `[]\h]` therefore changes parse context before `\h` is rewritten; negated siblings have the same mechanism. Negated `\H`/`\V` inside a class remain explicitly unhandled.

`containsResidual` tests only the character immediately following `(?` against a flag-letter set. It misses legal disabling/composite forms such as `(?-i)a`, which pass the kernel gate, throw in JS, and are reported as non-residual. The documented flag inventory also omits relevant Java flags and enable/disable combinations.

**Required correction:** Parse enough of the legal Java-pattern grammar to preserve class boundaries and complete flag groups, or replace the handwritten translator with a proven compatible mechanism. The residual/unsupported classifier and translator must share one closed construct model rather than maintain separate heuristics.

**Acceptance checks:** Lock leading-literal-`]` classes, negated classes, `[\H]`/`[\V]`, global/scoped flags, negative flags, combined flags, escapes, and nested groups. Add a guard proving that every known legal-but-unimplemented form is classified before JS compilation.

**Resolution:** Open.

### RV-2026-07-20-01-5 — compile-once and coverage claims exceed the evidence

**Severity:** Medium.

**Evidence:** The per-row defect is fixed, but compilation is cached per AST/model occurrence, not per distinct model-static pattern per engine as IG90 required and as the kernel's condition cache behaves. Identical literals in separate AST nodes and identical field/predefined patterns compile separately. There is still no compile counter or N/4N pattern-heavy gate.

`PatternDialectDiffTest` tri-checks only condition-level `\h`/`\H`; `\R`, `\v`, `\V`, declared patterns, and predefined patterns have no equivalent real-kernel differential in that change. Its class comment still says the kernel does not validate syntax and that `\A`/`\z` are loadable, directly contradicting its own rejection tests. `CapabilityEvidenceMatrixTest` names its `FORMAL_PATTERN` cell “declared + predefined” but maps it only to `PatternDiffTest`, whose exercised surface is condition operators.

The production seed generator also validates generated pattern candidates through raw common `Regex(pattern)` rather than `CompiledPattern`. A legal scoped-inline-flag field pattern followed by a zero-allowing quantifier can reach that verifier on JVM but throw on JS, so `dmtool model seed` is outside the claimed single host-semantics boundary.

**Required correction:** Deduplicate compilation at the prepared-model/engine boundary, add the compile counter and N/4N gate, route the seed verifier through the same compatible pattern abstraction, correct the stale test comment, and make the capability matrix/evidence prose name the actual covered surfaces.

**Acceptance checks:** Compilation count is no greater than the number of distinct model-static patterns per engine. Pattern-heavy N/4N growth is subquadratic without a first-answer regression. Add an end-to-end JVM/Node seed case for a legal dialect-sensitive field pattern. The evidence matrix either supplies specialist declared/predefined tests or narrows/splits the capability label.

**Resolution:** Open.

### RV-2026-07-20-01-6 — the dmkits/dmtool authoring gap is real but inaccurately described

**Severity:** Medium.

**Evidence:** This affects rulekit/modelkit/dmtool at both authoring loci: `PatternMatched`/`PatternViolated` literals and declared STRING/typedef patterns. The current [`DEV-API-GAPS.md`](DEV-API-GAPS.md) row calls `isPatternValid` a Java+JS-compatible constraint and says the authoring surface provides no warning or explanation. In reality the operator catalog already mentions a portable subset and `StringRef` prechecks Java syntax; what is missing is precise treatment of the additional A12K-30 blacklist. The field/typedef schemas still say only “anchored regex,” and `StructuralKernelFix` has no `MVK_INVALID_PATTERN` enrichment, so kernel refusal remains raw. The catalog wording is itself too broad because the blacklist does not establish portability.

**Required correction:** Keep one corrected `DEV-API-GAPS` row spanning the typed authoring API and dmtool; do not duplicate it in `FEATURE-GAPS`. Specify the exact two-stage gate (Java compilation plus the limited blacklist), add AUTHORING gotchas and invalid/fix examples for both pattern operators, describe declared field/typedef patterns in their schemas, enrich `MVK_INVALID_PATTERN` for both `rule check` and `model check`, and decide whether a shared rulekit/modelkit preflight should reproduce the limited blacklist. Do not advise authors to avoid other kernel-legal constructs merely because IG90 has not implemented them on JS.

**Acceptance checks:** Catalog examples are kernel-checked, schema examples are executed, both CLI rejection paths return a corrective diagnostic, and typed authoring distinguishes Java syntax failure from the A12K-30 blacklist. On ship, delete the DEV-API gap row and graduate the durable API rationale into `API-FINDINGS.md`.

**Resolution:** Open.

### RV-2026-07-20-01-7 — direct documentation contradictions remain

**Severity:** Medium.

**Evidence:** The later regex supplement in [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) still says the gate validates JavaScript compatibility, says only two legal construct families diverge, lists `\s` and named groups as identical/legal, and leaves a placeholder `IF<n>`. The earlier §7 finding correctly says the gate is limited, so the same document contradicts itself. The `docs/README.md` summary says `DEV-API-GAPS` has no missing-capability gaps although its open regex row now records one.

**Required correction:** Align the kernel finding with the exact blacklist and verified counterexamples, point the authoring consequence only to `DEV-API-GAPS`, replace the placeholder with IF154, and update the README's gap summary. Preserve the distinction between settled kernel facts and open interpreter/authoring work.

**Acceptance checks:** The doc guards pass, links resolve, and a repository search finds no remaining claim that `isPatternValid` proves Java/JavaScript portability or that the currently translated regex subset exhausts the legal divergences.

**Resolution:** Open.

### Chapter resolution

Open. An implementor should resolve issues 1–2 together in one decimal-context pass, issues 3–5 together in one regex/parser/evidence pass, and issues 6–7 in the same regex landing while that contract is warm. Do not close IG89 or IG90 until every acceptance check above is green.

## RV-2026-07-20-02 — IG83 → IF155 Route-B timezone review

**Verdict:** Changes required. The owner-ratified Route B-now / A-later decision stands, and the historical Berlin implementation appears correct on every independently checked transition entry and on the exercised runtime cases. IF155 nevertheless cannot be accepted as complete: the new preparation boundary silently evaluates an explicit invalid time zone as UTC, the full-history and three-verb evidence claims exceed the tests, the public-API guard is red, the changed historical `AddDays` path adds avoidable per-row allocation without matching performance evidence, and direct lifecycle/authoring documentation remains inaccurate. The reported custom-validator failure is also real, but it is a stale demo projection rather than an IF150/IF152 runtime regression.

**Reviewed scope:** `ac743157` (IG83 kernel characterization), `4777b982` (Berlin transition table), `bde3a581` (supported-zone refusal), `318d92f7` (historical differential), and `82c1d005` (IF155 graduation). The review also followed the explicitly reported `cli-custom-types` failure to commits `5fd309d3`/`955ccdc7` and the live CLI output contract. No proposal document was reviewed or changed.

### What the review accepts

- The kernel path really is legacy `java.util.TimeZone` plus non-lenient `SimpleDateFormat`, not `java.time`. Pre-1916 Berlin is flat CET on that path; the 1945/1947 CEMT offset and the 1980–1995 September DST end are real.
- All 62 committed epoch/offset pairs matched an independent IANA transition scan, and direct `java.util` spot checks confirmed the material pre-1916 and CEMT distinctions. The common JVM/Node tests and representative real-kernel differential pass. The review found an evidence-completeness problem, not a demonstrated bad table entry.
- `ModelEvalCommand`, `ModelComputeCommand`, and `RuleEvalCommand` each contain the typed `UnsupportedModelTimeZoneException` catch. The missing `rule eval` result is a guard gap, not evidence that the command currently crashes.
- Route A remains a legitimate owner-deferred capability: a general portable, versioned time-zone database is not required until its recorded consumer/publication trigger fires. The corrections below complete and honestly certify Route B; they do not reopen that decision.
- The custom-validator implementation preserves `errorMessage`, performs `$<fieldName>$` substitution, and emits the result under the schema-required `data.messages[].message` key. The demo asks jq for the nonexistent `text` key.

### RV-2026-07-20-02-1 — the time-zone boundary conflates absent, invalid, and unsupported values

**Severity:** High.

**Evidence:** [`ModelTimeZone.of`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/model/ModelTimeZone.kt) uses `id.isNullOrBlank()` to select UTC. A genuinely absent `modelConfig.timeZone` does default to UTC, but an explicitly authored empty or whitespace-only value is not absence: the kernel maps it through `TimeZone.getTimeZone`, observes the GMT fallback, and rejects it as `MVK_INVALID_TIME_ZONE`. The current interpreter therefore evaluates `timeZone: ""` and whitespace under UTC instead of failing closed—the exact wrong-zone behavior Route B was intended to prevent.

The classifier has a second sibling defect. Every other outside-set value becomes `UnsupportedModelTimeZoneException`, whose text and `RK_INTERPRETER_TIMEZONE_UNSUPPORTED` explanation describe a kernel-legal-but-interpreter-unsupported zone. That is true for `America/New_York`, but false for a typo such as `Europe/Berln`, which the kernel rejects. The actual input domains are: absent default, supported exact value, kernel-legal but unsupported value, and kernel-illegal value. The implementation models only supported versus “everything else,” with blank additionally folded into absence.

**Required correction:** Implement one explicit four-domain classification at the preparation/consumer boundary. Only a missing slot defaults to UTC. Exact `UTC`, `GMT`, and `Europe/Berlin` map to supported zones. Explicit empty/whitespace and other kernel-invalid ids must refuse as invalid configuration, while a known kernel-legal outside-set id must retain the deliberate supported-scope refusal. Common code that cannot decide the whole Java-ID domain without Route A must use an honest neutral outside-set result and let the JVM/kernel-backed authoring boundary supply the legal-versus-invalid distinction; it must never assert that an unchecked typo is kernel-legal.

**Acceptance checks:** Add common JVM/Node loader cases for missing, `""`, whitespace, `UTC`, `GMT`, `Europe/Berlin`, `Europe/Berln`, and `America/New_York`. Add CLI cases proving `model eval`, `model compute`, and `rule eval` never evaluate explicit blank/whitespace, distinguish the invalid typo from the legal New York scope refusal, and return structured corrective diagnostics rather than a bare failure or UTC result.

**Resolution:** Open.

### RV-2026-07-20-02-2 — “complete history” and tzdb-drift claims exceed representative evidence

**Severity:** Medium.

**Evidence:** [`DateMath.kt`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/eval/DateMath.kt) contains 62 transition pairs, but [`BerlinHistoricalTzTest`](../interpreter/src/commonTest/kotlin/io/github/mbackschat/a12/dm/interpreter/BerlinHistoricalTzTest.kt) samples pre-1916, 1945, 1980, and modern/future cases. [`BerlinHistoricalTzDiffTest`](../adapter/src/test/kotlin/io/github/mbackschat/a12/dm/adapter/laws/BerlinHistoricalTzDiffTest.kt) checks representative 1916/1945/1947/1980/pre-1916 behavior against Groovy-dynamic; its generated-static-Java leg covers only the 1980 fall-back plus a summer control. This does not support IF155/certificate wording that both kernel strategies lock the full table or that “any JDK-tzdb drift is caught.” A single mistyped unexercised transition could pass the current suite. The common test KDoc also points to `DstTimeZoneDiffTest` instead of the new historical differential.

The review independently compared all 62 committed pairs and found no bad entry. That is useful review evidence, but a one-off audit is not a durable executable certificate.

**Required correction:** Add a versioned, independently sourced transition oracle and a table-driven lock covering immediately before, at, and after every pinned transition, plus every gap/overlap resolution class. Reuse prepared kernel engines so the exhaustive guard remains practical, and compare the interpreter to Groovy-dynamic and generated static Java; do not derive the expected vector from the implementation arrays. If exhaustive second-strategy execution is deliberately not retained, lower every certificate/drift claim to the exact representative evidence instead. Correct the stale test KDoc.

**Acceptance checks:** The guard detects a one-second epoch mutation, an offset mutation, a missing/extra/reordered pair, and a wrong gap/overlap choice. It covers pre-first and post-last behavior and reports the pinned JDK/tzdb profile. Every document and test comment states exactly which strategy and transition set its named lock exercises.

**Resolution:** Open.

### RV-2026-07-20-02-3 — the CLI refusal/check-valid lock proves less than IF155 says

**Severity:** Medium.

**Evidence:** [`EvalUnsupportedTimeZoneRefusalTest`](../cli/src/test/java/io/github/mbackschat/a12/dm/cli/EvalUnsupportedTimeZoneRefusalTest.java) says all three interpreter-backed verbs refuse identically, but `allInterpreterVerbsRefuseAnUnsupportedZone` invokes only `model eval` and `model compute`. Its “same model is kernel-check-valid” test merely asserts that two diagnostic strings are absent; it never requires exit code 0, an `ok` outcome, or an otherwise clean check. An unrelated model-check failure could satisfy the current assertion. IF155 and the certificate cite this class as proof of both stronger claims.

**Required correction:** Exercise `rule eval` on a real stored rule and assert the same exact structured refusal, code, explanation, and fix as the other two verbs. Strengthen the New York control to prove that `model check` succeeds cleanly, not merely that one code is absent.

**Acceptance checks:** All three runtime verbs exit nonzero with `outcome: refused`, `RK_INTERPRETER_TIMEZONE_UNSUPPORTED`, the offending zone, and a nonblank corrective fix. The same unmodified New York model exits 0 from `model check` with its successful outcome/valid flag. Add negative controls for issue 1's invalid ids.

**Resolution:** Open.

### RV-2026-07-20-02-4 — the public rulekit API golden was not updated

**Severity:** Medium.

**Evidence:** `bde3a581` added the public enum constant `RkCode.INTERPRETER_TIMEZONE_UNSUPPORTED`, but [`api/dmkits-rulekit.api`](../api/dmkits-rulekit.api) still jumps from `INTERPRETER_INCLUDE_RULES_UNSUPPORTED` to `INVALID_CONFIG_VALUE`. The review ran `./build.sh apiGuardTest`; `PublicApiGuardTest.everyKitPublicApiMatchesItsGolden` failed on exactly that missing constant. The reported checkpoint omitted the root `apiGuardTest`, so its green module tasks could not detect this release-surface drift.

**Required correction:** Regenerate the public API goldens with the documented `dumpKitApi` task and review the resulting diff. Accept only the intended new enum member unless another independently owned API change is present.

**Acceptance checks:** `./build.sh apiGuardTest` and the root test/check path are green, and the reviewed golden diff contains the deliberate time-zone diagnostic API change.

**Resolution:** Open.

### RV-2026-07-20-02-5 — historical `AddDays` adds avoidable hot-path allocations and has no matching measurement

**Severity:** Medium.

**Evidence:** The new [`wallDayLandingInstant`](../interpreter/src/commonMain/kotlin/io/github/mbackschat/a12/dm/interpreter/eval/DateMath.kt) computes consistent candidates with `BERLIN_OFFSETS.filter { … }.map { … }`, allocating two collections per Berlin DATE_TIME `AddDays` evaluation. The prior two-offset implementation used scalar locals and allocated no collections. The existing B9 Berlin performance scenario exercises `DifferenceInHours`, not `AddDays`, so it does not cover the changed path. Complexity remains bounded/linear—the review found no O(N²) regression—but IF155's unqualified “no perf regression” claim has neither matching coverage nor measurement.

**Required correction:** Replace the collection pipeline with one scalar loop that tracks the earliest/latest consistent instant and the bracketing offsets without allocation. Add an at-scale Berlin DATE_TIME `AddDays` scenario that crosses historical ordinary/gap/overlap cases, and record the before/after result under the IF137 measurement posture. Do not promote a new numerical release bar without its protocol-compliant certification.

**Acceptance checks:** The hot function contains no per-call collection construction; correctness remains locked for normal, gap, and overlap landings in both travel directions, including a CEMT-era transition. The targeted N/4N case stays below the existing catastrophe floor and a same-run before/after measurement supports any retained “no regression” wording.

**Resolution:** Open.

### RV-2026-07-20-02-6 — lifecycle and direct status prose still describe the pre-IF155 state

**Severity:** Medium.

**Evidence:** IG83 currently sits in §1 “Open” while its row calls Route A owner-deferred with an external reopen trigger; that contradicts [`INTERPRETER-GAPS.md`](INTERPRETER-GAPS.md)'s own section invariant. It must remain open while issues 1–5 are active, but after those corrections only Route A remains and belongs in §2. Direct present-tense stale statements also remain in [`KERNEL-SEMANTICS.md`](KERNEL-SEMANTICS.md) (“modern Berlin only”), the earlier [`KERNEL-FINDINGS.md`](KERNEL-FINDINGS.md) supplement (“being pinned”), and the current-status summary in [`INTERPRETER-FINDINGS.md`](INTERPRETER-FINDINGS.md). IF128's dated historical account may retain what was true at that increment; current-status prose may not.

**Required correction:** While this chapter is open, make IG83's §1 row name the Route-B review residuals and Route A separately. Once issues 1–5 are complete, remove the §1 work and place only the owner-deferred Route-A capability under §2 with its existing trigger. Sweep the direct present-tense IF155/IG83 statements, preserving explicitly dated history. Replace “stable across releases”/automatic-drift claims with the exact pinned-profile and executable-evidence wording established by issue 2.

**Acceptance checks:** The gap ledger has no entry whose prose says “deferred” under §1 after Route B is complete. No current-status sentence says Berlin remains modern-only or is still being pinned. Links resolve, doc guards pass, and IF128 remains recognizable as historical provenance rather than silently rewritten history.

**Resolution:** Open.

### RV-2026-07-20-02-7 — the dmkits/dmtool authoring impact is real and needs the four-domain contract

**Severity:** Medium.

**Evidence:** This is a model-configuration authoring concern, not an operator-catalog concern. The typed mutation surfaces are `ModelDocument.setTimeZone`, `EditSession.setTimeZone`, and `ConfigEditor.timeZone`; dmtool exposes `config modify --time-zone` and the equivalent apply operation. `config modify` already calls `session.validate()`, so a kernel-invalid id is rejected immediately as well as by later `model check`, but the raw `MVK_INVALID_TIME_ZONE` has no corrective enrichment. Conversely, a kernel-legal outside-set id such as New York can be authored and kernel-validated successfully, then is refused by interpreter-backed `model eval`, `model compute`, and `rule eval`; the authoring surfaces provide no advance warning. The existing [`DEV-API-GAPS.md`](DEV-API-GAPS.md) row is the single owning backlog entry and has been corrected by this review to reflect those exact components and behaviors.

**Required correction:** Complete issue 1's classifier first. Then enrich `MVK_INVALID_TIME_ZONE` wherever the kernel-backed authoring/check path emits it; document the accepted-id versus interpreter-supported-set distinction on the public modelkit/edit setters and dmtool help/manifest/schema; and surface a nonblocking warning when a successful config mutation selects a kernel-legal zone outside `{UTC, GMT, Europe/Berlin}`. Do not put this model-config fact in an operator catalog and do not duplicate the gap in `FEATURE-GAPS`.

**Acceptance checks:** Typed docs and CLI discovery explain the four domains. `config modify`/apply and `model check` give a corrective invalid-id diagnostic for empty/whitespace/typo inputs. A New York mutation remains authorable and kernel-valid but carries a clear evaluator-scope warning; all three runtime verbs then produce the precise scope refusal. On ship, delete the DEV-API gap row and graduate the durable API rationale into `API-FINDINGS.md`.

**Resolution:** Open.

### RV-2026-07-20-02-8 — `cli-custom-types` projects the wrong result key

**Severity:** Medium.

**Evidence:** The reported demo failure reproduces with Node available: expected `"text": "Invalid Iban"`, actual `"text": null`. This is not a custom-validator runtime regression. `InterpreterEngine.addMessage` has long emitted the interpolated text under `message`, and [`eval-doc.schema.json`](../cli/src/main/resources/schema/eval-doc.schema.json) requires that key. `955ccdc7` changed [`examples/cli-custom-types.md`](../examples/cli-custom-types.md) to project `{code, field, text}` without changing the CLI contract. The focused `CustomFieldTypeJsContractTest` is green with Node, but it asserts the code/context and never the emitted message value, so the demo typo escaped.

**Required correction:** Change the demo projection and expected block to use `{code, field, message}` and `"message": "Invalid Iban"`. Add a focused CLI assertion that the JS validator's `errorMessage` reaches `data.messages[].message` after `$<fieldName>$` substitution. Keep this out of `DEV-API-GAPS`: custom implementations are intentionally runtime-owned, and the supported projection already works.

**Acceptance checks:** Run `CustomFieldTypeJsContractTest` with Node available and `uvx showboat@0.6.1 verify examples/cli-custom-types.md`; both are green, and the test fails if the interpolated message disappears or moves keys.

**Resolution:** Open.

### Review verification

- Green: focused interpreter JVM + Node `BerlinHistoricalTzTest`/`ModelTimeZoneTest`; focused `BerlinHistoricalTzDiffTest`; focused `EvalUnsupportedTimeZoneRefusalTest`; focused `CustomFieldTypeJsContractTest` with Node explicitly available; CLI document/semantics/stale-gap guards reached by the focused CLI tasks.
- Red as reviewed: root `apiGuardTest` on the missing public enum constant; `showboat verify examples/cli-custom-types.md` on the `text` versus `message` projection.
- No evaluator, CLI, kit, proposal, or Lean-repository source file was changed by this review.

### Chapter resolution

Open. Resolve issues 1–3 together as the Route-B classification/evidence pass, issue 4 in the same public-diagnostic landing, issue 5 as the focused historical-`AddDays` performance cleanup, and issues 6–7 with those implementation commits while their exact contracts are warm. Resolve issue 8 independently as the narrow demo/test correction. Do not describe IF155 as fully certified or move Route A to the deferred section until issues 1–7 are green.

## Resolved chapter archive

None.
