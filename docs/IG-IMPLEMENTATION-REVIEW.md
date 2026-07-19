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

## Resolved chapter archive

None.
