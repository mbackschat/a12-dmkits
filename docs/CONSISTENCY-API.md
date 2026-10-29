# The portable consistency checker — public API

> **What this is.** The consumer-facing contract of `:consistency`, the Kernel-free consistency checker for a DocumentModel's rules and computations: the entry point, what a report means, and how to call it from Node. Design and scope live in [PORTABLE-CONSISTENCY-PROPOSAL.md](archived/PORTABLE-CONSISTENCY-PROPOSAL.md); what has landed and what has not is the Status column of [PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md](archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md).

## Read this first: two different questions, and only one of them has a pass

**"Did everything I can check pass?" has an answer. "Would A12 accept this model?" does not.** The checker answers the first with `CheckOutcome.ALL_CHECKS_PASSED` — every family it implements ran and none rejected — because the families are a finite set and that claim is decidable. It cannot answer the second, so `ConsistencyStatus` has no `VALID`, by construction and not by omission.

**Read `outcome` for the pass and `status` for A12's verdict; they are not interchangeable.** Treating `INCONCLUSIVE` as "valid" is the single most likely way to misuse this API, which is why the status is a word rather than a boolean and why `complete` sits beside it.

Two statuses exist today:

| status | means | safe reading |
|---|---|---|
| `INVALID` | at least one measured A12 acceptance constraint is violated | the Kernel would reject this model; the findings say where |
| `INCONCLUSIVE` | no violation was established, and coverage is incomplete | **not** "valid" — only "nothing this checker looked at was wrong" |

`complete` is `false` on every report today and is published anyway, so that a consumer switching on it keeps working when it starts carrying information. It tracks A12 coverage, not the checker's own — so it stays `false` on a report whose `outcome` is `ALL_CHECKS_PASSED`, and that combination is the normal, honest one rather than a contradiction. A `VALID` *status* arrives with the coverage evidence that earns it, not with the next family.

**Every report also says what it did NOT check.** `checksCompleted` names the families that ran and `gaps` names what nobody examined — a measured storefront model returns 39 families and 5 gaps (2026-09-13). A consumer that shows a user "no problems found" without surfacing the gaps is reporting a verdict the checker did not give.

## Constant index keys: bounded JVM consistency semantics

Authored STRING tokens are decoded once by `decodeAuthoredStringLiteral`: remove one outer quote pair, collapse doubled quotes, then replace CRLF with LF in one pass. Token text, UTF-16 offsets and declaration-owned patterns retain their source values. `ConditionCallTest`, `ComparisonConstantTest`, `DateConstructionSlotsTest`, `ValidityConditionOperandsTest`, `ValueInFieldsConstantKindTest`, `ValueListConditionGatesTest` and `MessageParameterDecoderTest` cover the consuming readers; `ConditionTokenizerTest` holds the original source spans.

For String index fields, `admitsIndexConstant` receives the decoded key, checks positive declared length bounds, then applies whole-value Java-pattern semantics. A String type definition contributes its referenced constraints. Custom index types contribute their static length bounds; this is not execution of a custom validator. The oracle is A12's **JVM consistency check on both checker targets**, not the native JavaScript runtime's RegExp behavior ([KF481](KERNEL-FINDINGS.md#kf481)). `CheckerIndexKeyConstantAgreementTest` separately measures String and CUSTOM bounds after decoding; repeated-CR controls distinguish one pass from two. CR-bearing CUSTOM keys remain deliberately unjudged. `CheckerComparisonConstantAgreementTest` measures comparisons independently, and `MessageParameterDecoderAgreementTest` locks the quoted enum/name controls and the kernel refusal of line breaks in error text.

Pattern judgment is deliberately bounded. The compiler represents ordinary literals, groups/alternation/repetition, its character-class algebra and non-multiline anchors, and supported scalar escapes and Basic Latin character names. Java/JS differences require a certified pattern/key domain: general-letter properties and word boundaries use ASCII keys, graphemes use printable ASCII, and Unicode case folding uses Latin-1 source atoms and keys with a flag scope covering the whole expression. Unsupported names/properties/escapes, kernel-blacklisted nested classes, escaped range endpoints, nonuniform Unicode-case scopes, unverified flags, captured backreferences and keys outside those domains remain unjudged on **both** targets. This is not a complete Java Pattern implementation, and absence of a key finding never establishes validity.

The enforcement points are `KeyPattern.parse`/`matches` and `admitsIndexConstant`; `KeyPatternTest` locks positive, negative and unsupported controls on JVM/Node. `CheckerNearMissSweepTest.indexKeyPatternsHaveOnlyLedgeredDifferences` checks the fixed kernel populations and their byte-exact portable replay. The certificate applies effective ASCII case folding to decoded scalar escapes and every letter in a mixed range; intersection composes complete character predicates, including empty intersections and their outer complements. Omitted intersection operands retain Java’s operand provenance: `[a&&]` retains `a`, while `[xa-c&&]` excludes `x` and retains the range. Odd ampersand runs can resume a union; treating every `&&` as a separator changes the result. Malformed omitted operands, descending or set-ended ranges, and repetition bounds outside Java’s signed-int domain remain unjudged. `PatternDialectDiffTest.repetitionBoundDomainIsMeasuredAgainstJava` measures the bound against the pinned JDK; `KeyPatternTest.repetitionBoundsRespectJavasIntegerDomain` retains overflow, maximum, open-ended and leading-zero controls on both hosts. The admitted escaped-close/nested form `[a\][b]]` is represented; the kernel source-text blacklist still excludes ordinary nested spellings. Case-insensitive named escapes and braced class escapes remain unjudged unless covered by the whole-expression Unicode-case domain. These explicit exclusions are the M4 disposition for the wider legal Java-pattern domain; a wider claim requires new oracle evidence, not a host-dependent guess.

Rule-message group arguments preserve the measured parser event order: reached lexical/syntax faults, parent-walk checks at their grammar boundary, and completed-path resolution and availability before trailing tokens. Keyword/name decisions use the message vocabulary for the model's condition language; `Zeile` permits a signed digit offset. Availability includes a read sibling and each enclosing group of a deeper field; bare-word fallback retains field short-name ambiguity. Enforcement lives in `readMessageParameter` and `completedGroupDefect`. `MessageGroupEventAgreementTest` compares 284 typed English/German witnesses with the kernel, while `MessageGroupArgumentAgreementTest` retains the earlier 77; their common `MessageGroupEventTest` and `MessageGroupArgumentTest` replays preserve exact bytes and verdicts on JVM and Node. The separate `MessageGroupParentBoundaryAgreementTest` and `MessageGroupParentBoundaryTest` add 108 depth/up-count/token controls for the two parent actions. [KF214](KERNEL-FINDINGS.md#kf214) gives the controls and limits: this extension measures fixed groups and field reads. The shared parameter reader also checks keyed group syntax and event ordering (`MessageParameterDecoderAgreementTest.groupParametersShareTheKeyAndParentEventStream`); keyed group membership and its positive-presence proof are enforced by `keyedMessageGroupDefect`. `SelectedMessageReferenceDiffTest` supplies the 16-row ancestry/presence grid and four index-ancestor controls, replayed exactly on both hosts by `MessageParameterDecoderTest` ([KF546](KERNEL-FINDINGS.md#kf546)). The broader dotted-name grammar remains bounded. At the dotted-name boundary, unquoted reserved suffixes are explicitly declined; quoted and ordinary suffixes retain lookup. `MessageGroupDottedBoundaryAgreementTest` and `MessageGroupDottedBoundaryTest` lock that boundary with 44 kernel-measured rows.

Message-parameter families share one reader and retain their `CheckFamily` identities. `messageParameterFindings` runs name, parent, key and completion actions in grammar order; `completedFieldDefect` checks membership before value/category suffixes, while form-position suffixes require a field without requiring membership. Message short-name lookup ignores the entry model’s condition-reference flag (`resolveMessageEntity`); provider binding scope is unchanged and is not newly claimed by this measurement. `MessageParameterDecoderAgreementTest`, `MessageNamePositionAgreementTest` and `MessageIndexSuffixLanguageAgreementTest` compare complete kernel/portable class lists, including language selection, key-tail ordering and base-year reporting. Their exact model bytes and explicit decline reasons are replayed by `MessageParameterDecoderTest` on JVM and Node. Form line/name suffixes end their alternative and refuse a following key before subject lookup; the separate 36-row `MessageFormAlternativeAgreementTest` and common replay lock that boundary. Index-terminal cross-field/category checks and dotted reserved-tail grammar retain their explicit declines. Keyed group presence accepts a matching `FieldFilled`/`GroupFilled` or all-filled quantifier, with one proof-bearing AND arm or every OR arm; negative/comparison and at-least-one/more-than-one operands supply no proof. The parallel-iteration presence visitor remains outside this measured subset.

Numeric `FirstFilledValue` summarizes the maximum known scale across all expanded fields, while nonnumeric results retain their first-field kind. `Abs` shares rounding's immediate-operation constant and numeric-field gates; it has no digit-count gate. Enforcement is in `OperationAnalysis` and `roundingContributions`; `NumericOperationGateAgreementTest` measures rules and computations against the kernel, and `NumericOperationGateTest` replays the same models on JVM and Node ([KF181](KERNEL-FINDINGS.md#kf181), [KF167](KERNEL-FINDINGS.md#kf167)).

Common-precondition target reads follow the first judged computation alternative: once per common read, absent when every alternative is unguarded among several. The projected target-read class still answers once for the whole computation ahead of that walk. `computationSelfReadContributions` enforces placement; `CommonPreconditionWalkAgreementTest` and its `CommonPreconditionWalkTest` replay lock fixed-root, fixed-nested and repeatable Number carriers with the scale gate suppressed ([KF181](KERNEL-FINDINGS.md#kf181)).

Computation cycles retain ordinary reads from every writer, including unselectable later computations. Positive containing-group reads omit protected producers from the scheduling graph according to common-expanded conditions and implicit repeatable guards; they do not narrow the operand inventory. Enforcement is in `ComputationDependencyProfiles`; `ContainingGroupDependencyAgreementTest` measures the 21 admission cases and `ContainingGroupDependencyTest` replays the same model bytes on JVM and Node ([KF511](KERNEL-FINDINGS.md#kf511)).

## The entry point

Strings in, strings out — and that is the durable decision, not the number of calls. The checker's Kotlin types are still moving, so exporting *them* would publish an ABI that changes under every consumer; a JSON string commits to the wire shape below and to nothing else.

```js
import { checkModelJson } from 'a12-consistency';

const report = JSON.parse(checkModelJson(modelJson));
```

`checkModelJson(modelJson, diagnosticLabel?)` takes the DocumentModel as JSON text and returns a report as JSON text. The optional label is echoed back on diagnostics, for a caller checking many sources that needs to say which one spoke.

**It never throws.** An unreadable source — malformed JSON, a model the reader refuses — comes back as a report whose `diagnostics` explain the failure at the source level, with `findings` empty and `checksCompleted` empty. No `try`/`catch` is needed to get an answer, and a caller that wraps it in one will never see the branch.

From Kotlin or Java on the JVM the same check is `check(ModelSource(json))`, returning the typed `ConsistencyReport`; `report.toJson()` produces the identical wire shape.

**`CHECKER_VERSION` names the build you resolved.** It is generated into the artifact from this module's own Gradle version, so it moves with the checker rather than with whoever calls it — which is the point: a consumer that publishes a receipt naming the engine that answered must cite the artifact it actually loaded, and a constant on the consumer's own side would state its expectation instead. `dmtool model check --engine=portable` publishes exactly this value.

**From Node you read that version off `package.json`, not off an export** — the constant is Kotlin-side only, because a JS consumer already resolved the package by name and version and a second copy of the answer inside the bundle would be a second thing to keep true. `:consistency:verifyCheckerArtifactIdentity` is what makes the two answers the same answer: it reads the npm name and version out of the **staged** `package.json` — the bytes `packConsistencyNpmPackage` tars and a consumer installs — the version out of the jvm jar's coordinate, and `CHECKER_VERSION` out of that jar's compiled class, and refuses a build where any of the three disagrees. *(Corrected 2026-09-18: it read the Kotlin distribution's generated manifest, a file the staging step exists to replace. The two spelled one name and one version, so the check was green while verifying bytes nobody receives — and the shipped manifest is hand-written, so a wrong literal in it would have passed. "Packaged" was the word this paragraph used, and it was the word that was wrong.)* So `a12-consistency@X` and a dmtool receipt citing `X` are the same checker, and that is checked rather than intended.

## The report

```json
{
  "modelId": "storefront",
  "status": "INVALID",
  "complete": false,
  "outcome": "REJECTED",
  "findings": [ … ],
  "diagnostics": [ … ],
  "checksCompleted": [ "DECLARED_REFERENCES", "CONDITION_REFERENCES", … ],
  "gaps": [ … ]
}
```

All eight keys are always present. An empty array and an absent key would mean opposite things — no gap remains, against nothing was disclosed — so nothing is omitted when empty, and an absent path is JSON `null` rather than a dropped key or `""`.

**`status` and `outcome` answer different questions and are both published for that reason.** `status` is A12's verdict, where there is no `VALID`; `outcome` is the checker's own classification and the only place a pass appears. A sound model reports `status: "INCONCLUSIVE"` with `outcome: "ALL_CHECKS_PASSED"`, which is the normal, honest pairing rather than a contradiction.

### `findings` — what A12 says about a declaration

```json
{
  "code": "MVK_INVALID_ENTITY",
  "category": "A12_CONSTRAINT",
  "severity": "ERROR",
  "explain": "in `errorCondition`: no declared element matches `/Storefront/NoSuchField`, and no model-wide field short name resolved it",
  "at": "/Storefront/EmailRequiredForPaidOrder",
  "span": { "member": "errorCondition", "start": 15, "end": 40 },
  "justifiesRejection": true,
  "corrective": {
    "fix": "The group or field name in the condition could not be resolved in the rule's scope. Use its ABSOLUTE path …",
    "meaning": "MVK_INVALID_ENTITY (\"the group or field 'X' is not valid\") means the kernel could not resolve that name …"
  }
}
```

`code` is A12's own `MVK_*` code wherever the Kernel has one, so it joins against A12 diagnostics directly. Codes beginning `CK_` are this checker's own and mark a tier A12 has no code for — `CK_MODEL_NOT_BUILDABLE` means the source never loads at all, so no consistency verdict about that model exists in either direction.

**Read `justifiesRejection`, not `severity`, to decide whether a model is rejected.** The two come apart on purpose: `category` is `A12_CONSTRAINT` for a measured Kernel verdict and `AUTHORING_POLICY` for a house rule that the Kernel accepts (a third constant, `EVALUATOR_SUPPORT`, is published for a gap in what an evaluator can support and **no producer currently emits it** — handle it as you would a house rule, and do not expect it on the wire), and an `AUTHORING_POLICY` finding at `ERROR` deliberately does not make a model invalid. `justifiesRejection` is the conjunction, published because a consumer cannot derive it from the fields without knowing that rule. Two house rules are advisories at `WARNING` for message locales A12 admits and the Java builders refuse: `MVK_LANGUAGE_DUPLICATE` for two error messages in one locale, of which A12 keeps the last, and `RK_UNDECLARED_MESSAGE_LOCALE` for an error message in a locale the model does not declare, which A12 never collects.

`at` is the declaration's model path, or `null` for a finding about the model as a whole.

**`span` is what an editor underlines, and it is `null` wherever a text holds nothing to point at.** Its offsets are half-open over the `member` text it names — `errorCondition`, `commonPrecondition`, `computationAlternatives[0].operation` — so a consumer holding several of a declaration's texts knows which buffer they index. A span covers what the family that judged the text read: an unresolved reference (at its FIRST occurrence, because A12 stops at the first defect in a text), characters the lexer cannot read, half-typed input, a shape the grammar refuses, and a reference whose repetition wildcard is misplaced or missing, or whose repetition the target — a rule's error field, a computation's computed field — does not address. On a `Having`-filtered operand that last span runs to the end of the operand, body included, because A12 asks the question only once the whole operand is read; the filtered entity's KIND is asked as the `Having` keyword is read, so that class spans the operand up to its `Having`. An operator's gate spans the production A12 raises it from: the whole call for an operator's own check, the comparison for a comparison's, and the one argument for a check its argument's production owns, such as a date slot's; a check of the condition's own structure spans the part it reads, such as mixed connectives through the part after the second one or an arithmetic chain whole; and a semantic index's class spans the index from its `For` through its key, so it encloses the key's own. Every parse-action finding carries an editor span, but report selection uses its reached execution site and declaration occurrence independently of that span. A finding about a declaration's structure has no characters to point at and publishes `null` rather than an approximate range, because an editor underlining the wrong words is worse than one underlining nothing. When a parser ran out with a bracket still open the range is EMPTY at the end of the text — there is nothing left to underline, and an editor places a caret.

A finding's identity is its defect, not its location: typing a character earlier in a text moves every span after it, and `checkEdit`/`checkDraft` compare findings with the span excluded, so an ordinary keystroke does not report one defect as resolved and an identical one as introduced.

**`corrective` is A12's own guidance for the CODE, and it is `null` far more often than not.** Its `fix` says what to change and its `meaning` says what the code classifies wherever it is raised — neither is about the declaration in front of you, which is what `explain` is for. The texts are PROJECTED from the dmkits' Java diagnostic owners rather than restated here, over the codes this checker's own sources spell, so there is one live statement of each and a build guard fails when they drift. That bound is a superset of what the checker can actually report — a code it compares an authored value against is spelled too — so a second test measures every entry against a real report and names the residue. A `null` is the ordinary case: most codes are explained by the operator catalog, whose entries are keyed by operator rather than by code, so a code-keyed lookup has nothing to return. The member is always present, because an omitted one and a `null` one would look identical to a consumer reading an older report.

### `diagnostics` — what the checker could not read

A diagnostic is the checker speaking about itself; a finding is A12's verdict about a declaration. **They stay separate arrays and a consumer must not merge them**, because a merged list reports a model defect that was never measured — which is precisely the conflation the JVM `ModelLoader.loadAndCheck` makes. Each carries a `code`, `modelId`, `explain`, `at` and the `diagnosticLabel` the caller supplied.

### `checksCompleted` and `gaps` — the coverage evidence

`checksCompleted` is evidence that a family ran, never a claim that the families are sufficient — and it is derived from what the check actually scheduled, so a source that declares no elements names the model-wide families only, not the per-declaration ones that had nothing to run over (corrected 2026-09-18; that state also carries a gap of its own). `gaps` is the other half: each entry is a `reason` and an optional `at`, naming something nobody examined. Gaps are not defects in the model — they are the checker disclosing its own reach, including checks not supplied by syntax recognition and families not yet implemented.

**A family that ran can still have declined a DECLARATION, and that is disclosed too.** `checksCompleted` names a family once for the whole report, so a family that read most declarations and skipped one would otherwise publish "examined, nothing found" about the one it skipped. Every such skip carries its own gap, `at` the declaration it concerns: a range whose (format, separator) pair was never put to the kernel or carries a supplementary character, an enumeration whose `values` or `categories` is not the shape the pairing law was measured on. The iteration guard does the same for a rule or computation whose shape it does not judge, such as a `$` marker or a selection keyed by a field: it reports neither `MVK_NEG_CONDITION_IN_ITERATION` nor `MVK_PARALLEL_ITERATION_INVALID` there, and names the declaration instead, because a guess that refused would be an over-rejection. Two are model-level instead, because they silence a family everywhere at once: a character restriction spelled with a combined grapheme, and the field types declared under `content.typeDefinitions`, which are read for their ids alone. All of it is under-reporting — the safe direction — but undisclosed under-reporting is indistinguishable from a clean result.

## Syntax verdicts and positions

One common recognizer supplies the selectable `CONDITION_LEXICALITY`, `CONDITION_GRAMMAR` and `CONDITION_COMPLETENESS` families. It recognizes conditions and operations in EN and DE without a model; acceptance here does not establish reference binding, types or semantic check actions. A required-token failure at EOF reports `MVK_EXPECTED_TOKEN_NOT_FOUND`; a failed syntax choice at EOF reports `MVK_INCOMPLETE_INPUT`; either failure at a real token reports `MVK_UNEXPECTED_TOKEN`. A demanded lexical failure reports `MVK_LEXER_STANDARD_ERROR`. A later lexical defect cannot replace an earlier syntax failure.

Lexical failures belong to lexicality, required-token failures to grammar, and syntax-choice failures to grammar at authored tokens or completeness at EOF. Computations are recognized in the joined condition, including generated continuations: a syntax-choice failure immediately beyond an incomplete member belongs to completeness while retaining the joined-text class. Syntax offsets are half-open UTF-16 positions; EOF is a zero-width position at the end. An authored token identifies its own member. An immediate generated continuation identifies the incomplete member's end; delayed EOF identifies the member owning the unfinished construct. Thus an alternative precondition `FieldFilled(../Tier` followed by operation `42.00` reports the later EOF at the precondition's end, rather than at the operation.

`ConditionSyntax` enforces family and member attribution; `SyntaxVerdictTest` locks those contracts on JVM and JS. The adapter-only `A12ParseTraceTest` independently compares failure kind, code, offending text and positions with Kernel 30.8.1, keeping early semantic throws explicitly censored. The [conformance plan](CONFORMANCE-PLAN.md) records the measured populations and remaining gaps; the [archived reconstruction proposal](archived/CHECKER-RECONSTRUCTION-PROPOSAL.md#r3a-contract) carries the design rationale and deletion matrix. Recognition prediction does not publish semantic sites: only completed walks contribute them, including the steps completed before an unfinished walk. `SyntaxVerdictTest.anUnfinishedWalkReportsOnlyStepsThatCompleted` locks this distinction. The interpreter retains its separate parser.

Completed field reads come from that same recognizer: consuming the closing bracket and successfully demanding the next token makes the read eligible; an immediate lexical failure prevents it. `FIELD_VALUE_READ_OPERAND` owns the group, star, raw-value and partial-Date gates in ordinary expressions and filter bodies. The former public `CheckFamily.FILTER_WILDCARD` member is removed; code naming that member must migrate to `FIELD_VALUE_READ_OPERAND`. Existing whole-bracket report spans remain the projection. `FieldReadContractTest` locks the kernel-recorded origin and harvested list assertions on JVM/JS; `CheckFamilyWiringTest` locks ownership, and the field-read populations in `CheckerNearMissSweepTest` compare the kernel's first classes.

Numeric entity references use the recognizer's completed path positions, including completed steps before a later failure. `05` retains its spelling and source span; numeric operations, precision arguments and application-condition names remain outside binding. Standalone semantic-index operand fragments use the same entity recognizer. `NumericNameContractTest` locks these projections and the harvested ordered code lists on JVM/JS; the numeric-name population in `CheckerNearMissSweepTest` checks diagnostic classes against A12.

### Report selection

The checker retains each declaration occurrence through local checking and incremental caching, including declarations with identical public paths. It selects findings in reached condition-action order, stopping at the first rejecting action and preserving that action's notifications. Target, preamble and condition failures stop only their own declaration; calculation checks and message checks follow their measured separate branches. Messages retain the order of collected locale entries. Editor spans and diagnostic codes do not rank findings.

`ReportHarvestContractTest` locks the transferred assertions, occurrence isolation, reached-site validation and span-independent selection. `FamilyCellReuseTest` checks reused against fresh ordered reports. `A12ReportStreamTest` compares the supported outer order, multiplicity and member projections with raw and public kernel callbacks; its finite populations do not establish every possible model-stage interaction. Remaining differences and coverage limits are recorded in the [conformance plan](CONFORMANCE-PLAN.md).

Readable declarations remain analyzable beside `CK_MODEL_NOT_BUILDABLE`, including collided declarations with the same public path. The build blocker and its coverage disclosure remain; additional findings are the portable checker's analysis, not a downstream kernel report on an input the kernel could not build. Declaration occurrences retain independent local stopping decisions. `ReportHarvestContractTest` preserves the 316 harvested assertion identities, including the explicitly revised targets recorded in the [historical compatibility decision](archived/CHECKER-RECONSTRUCTION-PROPOSAL.md#r5-h-compatibility-decision-after-original-fixture-refusal); `CheckCellKeyTest` locks distinct cells at one path.

An Enumeration declaration's coded findings follow A12's blocks and multiplicity: invalid characters per UTF-16 unit, every category's unclassified values, every surplus token, the spoilt pairings, then the value duplicates, each identical notification once, and no coded finding naming a value, label, token or special value that carries a supplementary-plane character, which draws the unsupported-signs sentence alone. A line break in a value, label or category token is refused one gate before those classes and with no class key, so it is published as `RK_ENUM_TEXT_LINE_BREAK`, once per field however many of its texts carry one ([KF418](KERNEL-FINDINGS.md#kf418)). Two codeless sentences part from that stream. The unsupported-signs sentence is published once as `RK_UNSUPPORTED_CHARACTER`, but after the category blocks rather than before them. The repeated-value sentence is not published; it never stands alone. `CheckerEnumDeclarationStreamAgreementTest` locks all three against Kernel 30.8.1 ([KF551](KERNEL-FINDINGS.md#kf551)). Under `supportedCharacters` a supplementary character's high unit is refused even inside a range spanning it, and a restriction entry holding a surrogate unit is reported as `CK_MODEL_NOT_BUILDABLE` with no value judged against it ([KF560](KERNEL-FINDINGS.md#kf560)).

A String, Custom or Number length outside 1 to 99,999 draws one of A12's three codeless sentences, published as `RK_MEMBER_ABOVE_TECHNICAL_LIMIT`, `RK_MEMBER_NOT_POSITIVE` or `RK_MEMBER_SIGNED`, once per sentence and field. `-1` is "no bound" on String and Custom only, an out-of-range member judges no other length law, and a field deleted from the default variant is not judged. `CheckerLengthLimitAgreementTest` locks the grid against Kernel 30.8.1 ([KF556](KERNEL-FINDINGS.md#kf556)); the same sentences answer a Number type's digit counts, published beside `RK_MEMBER_ABOVE_DIGIT_BUDGET` for an integer count above 15 and `RK_MEMBER_ABOVE_SCALE_LIMIT` for a fractional count above 14. An amount's derived integer count is judged too, and a law reading an out-of-range count stays silent; `CheckerDigitMemberRangeAgreementTest` locks that grid ([KF559](KERNEL-FINDINGS.md#kf559)).

A Number's `minValue` or `maxValue` is printed before A12 judges it, rounded half away from zero to a whole number when no fractional digit is allowed and as its shortest decimal otherwise, and the printed value meets two codeless sentences: `RK_BOUND_SIGNED_ZERO` for a negative bound that prints as `-0`, and `RK_BOUND_TOO_MANY_DIGITS` for more than 15 digits, a leading `0` counted. Each is reported once per field, and either one withholds `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE`, as A12 withholds it. `CheckerDeclarationAdmissionAgreementTest` locks the rows ([KF569](KERNEL-FINDINGS.md#kf569)). A third, `RK_NUMBER_MIN_VALUE_EXCEEDED` or `RK_NUMBER_MAX_VALUE_EXCEEDED`, bounds the magnitude by the fractional digits alone, `10^(15 - k) - 10^(-k)` for `maxFractionalDigits` `k` (an absent count is 0), judging the maximum signed and the minimum by its absolute value, unrounded. It is independent of the other bound laws, and it is asked of every field, including one the default variant deletes. A bound within two ulps of a limit whose power of ten is not an exact double is left to a coverage gap, because A12's `Math.pow` may decide it differently on another JVM. `CheckerNumberBoundMagnitudeAgreementTest` locks the grid ([KF582](KERNEL-FINDINGS.md#kf582)).

A rule or computation surviving the default variant meets two alphabets, each answered by a codeless sentence: `RK_RULE_NAME_ALPHABET` for a name that is not an ASCII letter followed by ASCII letters, digits and underscores, which withholds `MVK_RULE_NAME_INVALID`, and `RK_ERROR_CODE_ALPHABET` for a rule's error code, or a computation's name, carrying anything but letters, digits, a space and the underscore, the letters including the German umlauts and `ß` ([KF578](KERNEL-FINDINGS.md#kf578)). A12's own words are *"A rule name must start with a letter followed by letters, digits and underscores."* and *"The error code is only allowed to contain letters, digits, blanks and underscores."*; both judge the value after Java's `trim`, and a number written as the code is judged as its text.

A12's basic sentences are `CheckFamily.BASIC_SENTENCES`, each published under a code of the checker's own. They are stated over the model as read, after every class and through any variant filter, so on a declaration an `ONLY` filter deletes or suppresses the sentence is the only refusal ([KF579](KERNEL-FINDINGS.md#kf579), [KF626](KERNEL-FINDINGS.md#kf626)).

| code | drawn by |
|---|---|
| `RK_ID_EMPTY`, `RK_NAME_EMPTY` | a declaration's or a type definition's `id` or `name` that is the empty string |
| `RK_ERROR_CODE_EMPTY` | a rule's `errorCode` absent or empty, beside `MVK_ERROR_CODE_MISSING` |
| `RK_ERROR_CONDITION_EMPTY`, `RK_ERROR_ENTITY_EMPTY` | a rule's `errorCondition` or `errorEntityRelPath` that is the empty string |
| `RK_COMPUTED_FIELD_EMPTY`, `RK_COMPUTATION_OPERATION_EMPTY` | a computation's `computedFieldRelPath`, or any alternative's `operation`, that is the empty string |
| `RK_ENUM_VALUE_DUPLICATE`, `RK_ENUM_CATEGORY_DUPLICATE` | an Enumeration repeating a value token or a category name, once per repeated one |
| `RK_TYPE_REFERENCE_EMPTY` | a `TypeDefType` field whose `typeDefinitionId` is absent or the empty string |

They come in A12's order: the type definitions' sentences first, sorted by id, one for each empty id and each absent or empty name, then each declaration's; an Enumeration's repeated values and categories come in the iteration order of Java's `HashMap`, which is neither declaration order nor first-occurrence order. Two type definitions sharing an id, the empty one included, leave no model and report `CK_MODEL_NOT_BUILDABLE` ([KF647](KERNEL-FINDINGS.md#kf647)).

A localized-text member is read as A12's text-map reader reads it, on every carrier: a field's or group's label, helper text, descriptions, an Enumeration value's label, every error message and each `variantErrorMessages` value. A list's elements or an object's values are its entries, each an object with a string `locale` in a spelling A12 parses and a string `text`, and a scalar or an empty list carries no text. Any other entry, and `null` for the member, is a source A12's reader refuses, reported as `CK_MODEL_NOT_BUILDABLE` through any variant filter ([KF646](KERNEL-FINDINGS.md#kf646)).

Each fires on the empty string alone, never on a blank one. A model that also fails to build reports `CK_MODEL_NOT_BUILDABLE` beside it: two categories of one name, two hint-list entries for one locale and an empty computed field do, unless the default variant removes the declaration, which A12 then never converts.

Three element-name laws sit beside the path gate ([KF583](KERNEL-FINDINGS.md#kf583)). A computation whose computed field's path carries a dot or a colon on any segment draws `MVK_ERROR_INVALID_FIELDNAME`, after the index-field gate and only when its condition was read. An underscore group name below an `xml…` ancestor draws `NAME_STARTS_WITH_UNDERSCORE_AND_WRONG_PATTERN` beside the prefix class. A default-variant declaration whose name is blank as Java's `isBlank` reads it draws `CK_MODEL_NOT_BUILDABLE`; a no-break space is not blank. A computation's `computedFieldRelPath` is lexed as a condition operand is, so a character A12's condition lexer has no terminal for draws `MVK_LEXER_STANDARD_ERROR` at the computation, ahead of the index and dot gates ([KF586](KERNEL-FINDINGS.md#kf586)). Wherever the checker reads a digit or a name character in condition text, it reads A12's lexer sets rather than a platform's Unicode tables, so a date constant spelled with another script's digit is a string, as it is to A12 ([KF587](KERNEL-FINDINGS.md#kf587)). A date constant's day is judged on the Julian–Gregorian hybrid calendar A12 parses with, so `"05.10.1582"` is no date and `"29.02.1500"` is one ([KF588](KERNEL-FINDINGS.md#kf588)).

A `DateType`, `DateTimeType` or `TimeType` surviving the default variant whose `format` is present and blank as Java's `isBlank` reads it draws `CK_MODEL_NOT_BUILDABLE`, because A12 constructs all three from that member; an absent `format` is the type's default and draws nothing, and a no-break space is not blank and is left to A12's format vocabulary ([KF584](KERNEL-FINDINGS.md#kf584)). A field typed by a type definition carries a coverage gap at the field, because its body is the definition's, which these families do not read.

A `DateType`, `TimeType`, `DateTimeType` or `DateFragmentType` field surviving the default variant whose format A12 refuses, its own or that of the type definition it uses, draws the codeless `RK_DATE_FORMAT_INVALID` ("The date format is invalid."). Only the Refused table of [TEMPORAL-FORMAT-VOCABULARY](TEMPORAL-FORMAT-VOCABULARY.md) is judged, generated into the checker by `:dumpTemporalVocabulary`; a format outside the probed universe draws nothing ([KF648](KERNEL-FINDINGS.md#kf648)).

A `DateRangeType` surviving the default variant is judged on the range format A12 composes, `format + rangeSeparator + format`, an omitted format composing as `null` ([KF581](KERNEL-FINDINGS.md#kf581)): a composite blank once trimmed draws `MVK_FORMAT_DATE_RANGE_MISSING`; otherwise a separator that, trimmed, is not empty, `-` or `/` draws the codeless `RK_DATE_RANGE_SEPARATOR_INVALID`, and a pair A12 does not allowlist draws the codeless `RK_DATE_RANGE_FORMAT_INVALID`. The pair verdict is stated only inside the measured domain; outside it the declaration carries a coverage gap.

A `sortFields` list is judged per distinct name, wherever the name stands; `MVK_SORT_FIELD_DUPLICATE` reads only the three entries the list holds, and each entry beyond them is published as `RK_SORT_LIST_ABOVE_CAPACITY` ([KF345](KERNEL-FINDINGS.md#kf345)). A rule whose target or condition fails keeps its name, code and message-presence classes and withholds its condition and error-text classes ([KF339](KERNEL-FINDINGS.md#kf339)); `CheckerDeclarationWithholdingAgreementTest` compares this and the sort list's reading with Kernel 30.8.1, multiplicity included. An `indexFieldName` or `sortFields[].name` that lands on a field whose short name A12 refuses publishes `RK_FIELD_NAME_ALPHABET` once more, beside the member's own codes; those codes (`MVK_INVALID_INDEX_FIELD`, `MVK_INVALID_SORT_FIELD` and the two `…_NO_VALUE_VALIDATION`) are withheld whenever an element path, a repetition count, a field's own message or a model-wide setting is refused, because A12 asks them only of a model its entity walk admitted ([KF589](KERNEL-FINDINGS.md#kf589), `CheckerOrderingNameSentenceAgreementTest`). A group counted below 1 publishes `MVK_MAX_VALUES_INVALID` at itself and at every element beneath it, with the member's range sentence ([KF590](KERNEL-FINDINGS.md#kf590), `CheckerRepeatabilityCascadeAgreementTest`).

Only supplied producers participate in selection: disabling a family cannot let its unproduced findings veto a supplied family's output. `FamilyCellReuseTest` exercises this beside the fresh-versus-reused comparison. Raw kernel callback multiplicity is distinct from published report multiplicity; `A12ReportStreamTest` measures both on its declared population. Measured duplicate collapse does not establish a universal equality key for all notifications.

### Operation results

Arithmetic, temporal extrema and the seven comparison families share one per-declaration `OperationAnalysis`. It types completed operations from their children, retaining signed numeric scale, constant expandability, temporal components and direct-field/literal provenance. Recognition preserves arithmetic precedence, explicit grouping and completion order; a completed child survives an unfinished parent, while failed next-token demand prevents completion. Unsupported results remain unknown rather than being assigned a guessed type. Computations use their joined condition with diagnostics mapped back to authored members.

The grouped producer retains all nine public `CheckFamily` assignments. Its tracked model reads govern incremental reuse: changing a referenced scale invalidates the operation cell, while an unrelated field edit preserves it. `OperationContractTest` locks those controls against a full check, operation completion structure, the 53 captured numeric-law targets and the retired grids' ordered-list assertions on JVM/JS. `CheckFamilyWiringTest` locks ownership. The two retained diagnostic methods in `BooleanComparisonAgreementTest` and `ComparisonOperatorOperandsTest` lock their corrective wording and declaration location.

CUSTOM reads have the text base type for `==` and `!=`: numeric direct reads and constants produce `MVK_INVALID_COMPARE_TO_ENUM_OR_STRING` on either side, before custom display-domain delegation. `OperationComparisons.comparisonFinding` enforces that ordering; `TextComputationAdmissionReadTest` measures ordinary rules and joined computation assignments against the kernel, and `ComparisonArmOrderTest` locks both portable targets. Composite enumeration assignments retain the separately documented delegation in `EnumComputationSource`.

The fixed operation population in `CheckerNearMissSweepTest`, paired with `A12ParseTraceTest`, checks child-result typing and completion boundaries against A12. Existing joined-computation, field-copy, keyed-reference and filter differentials cover their respective carriers; those measurements do not establish types for every unmodeled call.

### Element paths and names

`ELEMENT_NAME_ALPHABET` and the reach check for `FIELD_TYPE_MESSAGE_TEXT` share one carrier-sensitive path predicate. Root groups, nested groups and fields have distinct leading-character and segment rules; field-name restrictions remain separate from path admission. A field removed from the default variant is not checked. Beneath a refused path no element states a path class of its own, as A12's entity walk stops there. Beside the classes, the family states A12's four codeless name sentences ([KF580](KERNEL-FINDINGS.md#kf580)): `RK_GROUP_PATH_INVALID` at every nested group, field, rule and computation whose group path holds a part outside the group alphabet or over 60 characters, `RK_ROOT_GROUP_NAME_ALPHABET` and `RK_MEMBER_ABOVE_60_CHARACTERS` at the root group, and `RK_FIELD_NAME_ALPHABET` at a field; each judges the Java-trimmed value, and none is stated where the value carries a supplementary character or a name carries `/`. `ElementPathStageLawsTest` compares the fixed name/carrier/message/filter populations with A12, retaining the names carrying `/` as explicit ledger differences. `ElementNameAlphabetTest` and `FieldTypeMessageTextTest` retain portable report contracts on JVM/JS. This does not promise complete codeless diagnostics, H-model rules, or every interaction with other model checks.

## Checking an EDIT — `checkEdit`, for a candidate you have not saved

`checkEdit(baseline, edited)` answers a different question from `check`: not *is this model acceptable* but **what did this edit do**. It is what an editor wants before saving, and what `dmtool rule check --engine portable` uses for a candidate rule.

It exists because this checker reports over a whole model, so the edited model's report cannot serve as the edit's verdict: a consumer shown it would read `INVALID` whenever the **baseline** already carried an ERROR of its own — a verdict about another declaration, delivered as a verdict about the edit. That is an over-rejection, the one direction this checker must never take.

```kotlin
val report = checkEdit(ModelSource(savedJson), ModelSource(editedJson))
report.status        // the EDIT's verdict: INVALID or INCONCLUSIVE, never VALID
report.introduced    // the findings the edit ADDED — these, and only these, decide `status`
report.resolved      // the findings the edit removed, so a repair is visible as one
report.baseline      // the full report for the model as it stood: a DISCLOSURE, never part of the verdict
report.edited        // the full report for the model as edited
```

**From JavaScript it is `checkEditJson(baselineJson, editedJson, diagnosticLabel?)`**, returning that wire shape as text — the parameter order is baseline first, as in the Kotlin call above.

`report.toJson()` gives the wire shape, with `introduced` and `resolved` always present rather than omitted when empty — absent and empty would otherwise mean the same thing to a consumer deciding whether an edit is safe to save.

Two properties are worth knowing before you rely on it. Findings are compared by **content** (`Finding` is a data class), which works because the baseline's own findings were measured to survive an edit unchanged; attribution by each finding's `at` path was rejected as assuming more, since it would additionally need every edit-caused finding to land under the edited declaration and nothing has measured that. And it **refuses rather than guesses when the baseline could not be analysed at all** — with no baseline there is no "already carried", so every finding would read as introduced — reporting that refusal as a gap while still handing back the edited model's own report.

## Checking text an editor has not saved — `checkDraft`

```kotlin
val report = checkDraft(ModelSource(modelJson), "/Order/CodeRequired", "errorCondition", typedText)
```

**The draft is applied to the fact base, never to your source.** Text being typed is not in the model yet: an editor that wrote it into the source to find out would have to undo the write on every keystroke, and a consumer that checked the text on its own would lose the model context every reference in it resolves against. So the declaration is re-declared internally with that member carrying your text, and the result is attributed exactly as `checkEdit` attributes a saved edit — the draft's own findings, apart from what its neighbours already carried.

`member` is spelled the way a report spells it: `errorCondition` for a rule, and `commonPrecondition`, `computationAlternatives[0].precondition` or `computationAlternatives[0].operation` for a computation. A member the declaration does not yet have is still draftable — writing a computation's first `commonPrecondition` is the case an editor starts from — while an alternative that does not exist is not, because adding one is a structural edit rather than text being typed.

**Two things this gets right that an editor would otherwise get wrong.** Half-typed text is unfinished, not invalid, and nothing here promotes it to a rejection — an author would otherwise be told their rule is wrong on every keystroke. Its class is A12's: `MVK_INCOMPLETE_INPUT` where the member ends A12's input, and `MVK_UNEXPECTED_TOKEN` raised past your text in a computation member A12 reads on after, because A12 parses a computation's members inside one condition it generates from them. And a draft's verdict never absorbs another declaration's defect, so typing into a model that is broken elsewhere still reports honestly about your text; the neighbour's findings ride along on `baseline`.

A draft that could not be applied at all — an unreadable source, a path the model does not declare, a member the declaration cannot carry — attributes **nothing** and says why in `gaps`, naming the members the declaration can carry. It never raises.

**From JavaScript it is `checkDraftJson(modelJson, at, member, text, diagnosticLabel?)`**, returning the same edit-shaped JSON `checkEditJson` returns. For an editor calling per keystroke, prefer the snapshot handle below: this function admits and prepares the source on every call.

## Which declarations a change reaches — `impactOf`, before you commit to it

`checkEdit` says what your change broke. `impactOf(baseline, edited)` says what you now have to re-read, which is a different question and the one an author cannot answer by hand on a model of any size.

```kotlin
val report = impactOf(ModelSource(before), ModelSource(after))

report.delta          // what changed: paths added, removed, re-declared; type definitions; moved short names
report.impacts        // one entry per (declaration, dependency) reached, each with the reason it was reached
report.affected       // the declarations, each named once
report.edit           // the verdict half — `checkEdit` verbatim, not re-derived
report.gaps           // what the ANALYSIS could not follow
```

**It may over-report and may not under-report**, and two design consequences follow from that direction. An element dependency reaches a referent's **ancestors** as well as the referent, because nothing in a model textually references the groups a path passes through — so rescoping a repeatable group would otherwise reach nothing at all. And a bare reference the model-wide short-name fallback resolved depends on the **name** as well as on what it currently names, because declaring a namesake in the reading declaration's own group takes the reference over through the local tier while touching nothing it names. That last case is the one a path-keyed analysis cannot see: both models are acceptable, so neither the check nor the edit report says anything.

| reason | what a change did |
|---|---|
| `REFERENCES_A_CHANGED_ELEMENT` | added, removed or re-declared an element the declaration names — or one above it |
| `BINDS_A_CHANGED_TYPE_DEFINITION` | added or removed the `content.typeDefinitions` entry a field types by |
| `RESOLVES_A_MOVED_SHORT_NAME` | moved what a bare name can bind to, or flipped `fieldRefByShortNameAllowed` |
| `READS_A_CHANGED_COMPUTED_FIELD` | reached a computation, and this declaration reads the field that computation writes |

**`gaps` is the analysis talking about itself, and it is not the same list as a report's own coverage gaps.** A reference that resolves to nothing has no position for a change to be matched against, so a declaration reachable only through it can be missed — that is a limit of the ANALYSIS. That the model is also defective there is a finding. An unexpanded include is the stronger case: the fact base is missing declarations that exist, so no declaration can be reported as *unreached* at all, and the report carries the mount and nothing else.

**No claim is made about runtime behaviour or intent.** A reached declaration is one whose consistency may have moved, never one whose computed values have — the transitive reason included, which reports a dependency on a computation's output rather than a change in it.

**From JavaScript it is `impactOfJson(baselineJson, editedJson, diagnosticLabel?)`**, returning the same five members as JSON: `affected`, `delta`, `impacts`, `edit`, `gaps`. `edit` is byte-identical to what `checkEditJson` publishes for the same pair, because the verdict is delegated and not re-derived.

A dependency crosses the wire as `{kind, value}` rather than with a key per arm — `kind` is `ELEMENT`, `TYPE_DEFINITION`, `SHORT_NAME` or `UNFOLLOWABLE`, and `value` is the path, the id or the bare name, `null` for an arm that names nothing. **`UNFOLLOWABLE` is spelled out but cannot currently arrive**: a change set maps that arm to no reason, so no impact carries one. It is published so a consumer's switch is total over the sealed type rather than over what today happens to reach the wire. **The key set is deliberately fixed across arms**: a consumer that only displays the dependency needs no switch, and one that acts on it still has the discriminator. Read `value` as a path only when `kind` is `ELEMENT` — `SHORT_NAME` is the one dependency with no position at all, which is exactly where a consumer assuming a path goes wrong.

```ts
import { impactOfJson } from 'a12-consistency';

const { affected, impacts } = JSON.parse(impactOfJson(before, after));
impacts[0].on;      // e.g. { kind: 'SHORT_NAME', value: 'Code' }
impacts[0].reason;  // 'RESOLVES_A_MOVED_SHORT_NAME'
```

**One declaration appears once per dependency in `impacts`**, so a rename can reach a declaration through both the element and the name. `affected` is the de-duplicated list in the order the declarations were reached, published rather than left derived because a consumer reaching for a set would lose that order and one reaching for a plain map would show the declaration twice.

## Holding one analysis across many questions — `snapshotOf`

Every entry point above is stateless, and that is right for a gate and wrong for an editor: `checkDraft` performs one admission, one preparation and **two** checks per call, and an editor asks per keystroke while the baseline side cannot possibly have changed. A snapshot pays for the source parses and the fact base once.

```kotlin
var snapshot = snapshotOf(listOf(ModelSource(orderJson), ModelSource(catalogJson)), "orders")

snapshot.report                                   // the entry model's report, computed once
snapshot.draft("/Order/CodeRequired", "errorCondition", typed).edit   // `checkDraft`'s answer, one check
snapshot = snapshot.withSource(ModelSource(editedOrderJson))         // re-admits that source, reuses the rest
snapshot = snapshot.withoutSource("catalog")                         // and the mount goes unexpanded, visibly
```

**A snapshot is a value.** Every operation returns a new one and none writes into the receiver, so a consumer may hold an old snapshot and keep getting the answer it already had — which is what makes it safe to hand one to a UI that renders asynchronously.

**Reuse can never change an answer, by construction rather than by care.** Everything a snapshot reuses is a pure function of inputs that did not change — a source's parse, the entry's fact base, the baseline report — so there is no invalidation rule to get wrong. `SnapshotTest` still holds every change kind to a fresh full check, because "by construction" is a claim and the table is the oracle for it.

**What was reused is reported, and measured rather than asserted.** `snapshot.reuse` and `DraftResult.reuse` carry `sourcesReused`, `sourcesAdmitted`, `preparationReused`, `baselineReportReused` and `families`; the first compares each admitted parse against the previous snapshot's by object identity, and the two flags compare the objects an answer was actually computed from against the snapshot's own. Two readings are structural and say so in the type's own documentation: making a snapshot always re-prepares and re-checks, and answering a draft never calls admission at all. **No latency is claimed** — H5 owns the after-edit boundary and left it unset, so a number here would be a bar nobody ratified.

**Reuse INSIDE one check is reported too, as `reuse.families`, and it is the same discipline one level down.** A keystroke changes one declaration, so a family that never read that declaration is answered from the verdict it gave last time: `FamilyReuse` carries how many were reused and how many recomputed, and the two always sum to the CELLS the check scheduled — a cell being one family's verdict about the whole model, or, for a family the checker asks once per declaration, about one declaration. What makes it safe is that no family declares its own footprint — the checker OBSERVES what each one reads while it runs, and a cached verdict is used only when every fact it read still compares equal. An observed read cannot be too narrow, because it *is* the read, which is the property a hand-kept footprint per family could never have had.

**What a reused cell GUARANTEES, stated so a consumer knows what not to infer.** The answer is the answer: a report built partly from cells is equal to the report a full recheck produces, and that equality is asserted against the stateless `checkDraft` on the whole report object rather than on a projection of it. The store lives on the `ConsistencySnapshot` whose check built it and is handed only to that snapshot's own `draft`, so nothing is ever reused across snapshots or across models. Two things it does not tell you: the numbers count CELLS and not families, so the denominator grows as more families are asked per declaration — comparing `reused` between checker versions says nothing — and **no latency is claimed anywhere**, because H5 left the after-edit boundary unset and a number here would be a bar nobody ratified.

**What that buys today is measured rather than promised, and it is growing one batch of families at a time.** On an eight-declaration model, re-typing a rule's condition reuses **444 of 518** cells; re-typing text it already had reuses all 518, which is the validator's own ceiling and the control that makes the first figure mean something (2026-09-18). Both figures are dated evidence and move with the registry; what does not move is the form they come from — one cell per model-wide family plus one per declaration for the rest, `38 + 60 × 8` here — which `FamilyCellReuseTest.theCellDenominatorIsTheProductThePublishedFiguresAreDerivedFrom` holds, and which `./build.sh :consistency:jvmTest` re-derives. The cells that fall are the ones that read the edited declaration — and a group's body nests its elements, so an edit also fells every ancestor's cell. Most families still read every declaration and so still hold one model-wide cell; moving them to the finer key is where the rest of the reuse is. The [implementation plan](archived/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md) carries that as its own work items rather than a follow-up here. *(Corrected 2026-09-18: this paragraph previously said per-declaration reuse "is deliberately not attempted: it would need each of the 103 families to declare the model footprint its verdict depends on". Both halves are superseded — the footprint is observed, and the reuse now ships.)*

**No latency is claimed here either.** `FamilyReuse` counts WORK, for the same reason the source counters do.

**From JavaScript it is `snapshotOfJson(modelJsons, entryModelId)`**, returning an opaque `ConsistencySnapshotHandle`. The handle is the one exported *type* on this surface, and it carries no structure: every payload is still a JSON string. (Kotlin/JS also emits a `ConsistencySnapshotHandle.$metadata$` namespace beside it, carrying an `abstract new ()` construct signature. It is a compiler artifact, not part of this contract, and it does not defeat the opacity — an abstract construct signature is not newable.)

```ts
import { snapshotOfJson } from 'a12-consistency';

let held = snapshotOfJson([orderJson, catalogJson], 'orders');

JSON.parse(held.reportJson());                                        // the entry model's report
JSON.parse(held.draftJson('/Order/CodeRequired', 'errorCondition', typed));   // one check, nothing re-prepared
JSON.parse(held.draftWithReuseJson(path, 'errorCondition', typed));   // `{ edit, reuse }` in one computation
held = held.withSource(editedOrderJson);                              // a NEW handle; the old one still answers
held = held.withoutSource('catalog');
JSON.parse(held.reuseJson());                                         // what making THIS handle reused
```

`reuseJson()` and `draftWithReuseJson`'s `reuse` member carry the counters above with the family pair flattened to `familiesReused` and `familiesRecomputed`. The handle's constructor is declared `private` in the published `a12-consistency.d.mts`, so `snapshotOfJson` is the only way a TypeScript consumer can obtain one — a handle that analysed nothing cannot be fabricated. **That opacity is type-level**: Kotlin/JS emits an ordinary class, so a consumer bypassing TypeScript was never constrained by it.

**Holding a handle retains the prepared model and its cell store**, which is the cost that buys the reuse. JavaScript gives no `close()` to enforce release, so a consumer that keeps handles it no longer needs keeps that memory live.

## Calling it from a JVM dmkits consumer — `dmkits-consistency-gate`

A caller already holding DM-JSON and a `ModelResolver` should not have to assemble a `List<ModelSource>` by hand, nor re-shape the report to fit the result type the rest of their code handles. The `dmkits-consistency-gate` artifact is the façade for that, and it lives beside both products rather than inside either — neither the kits nor this checker depends on the other.

```java
PortableGateRun run = PortableConsistencyGate.check(
        new ModelSource(orderJson, "orders.dm.json"), ReferenceResolvers.directory(modelsDir));

run.validation().ok()                 // false iff something rejected the model
run.validation().diagnostics()        // the checker's own codes, source PORTABLE_CHECKER
run.validation().consistencyGate()    // Verification.PORTABLE_CHECKED — never a kernel confirmation
run.report()                          // this checker's own report: status, checksCompleted, gaps
```

**`ok() == true` is not VALID, and the façade cannot make it one.** It means nothing that ran rejected the model; the status beside it is still `Inconclusive`, and that is the whole first section of this document restated at a second surface. A consumer that reads only `ok()` has silently upgraded "nothing rejected it" into "it is valid", which is why both halves are published and why `report()` is the one carrying the coverage evidence.

**The receipt is its own value on purpose.** `PORTABLE_CHECKED` is neither of its neighbours: not `KERNEL_CONFIRMED`, because no kernel saw the model, and not `STRUCTURAL_ONLY`, because a measured re-implementation of A12's constraints ran rather than the kits' own prechecks. Diagnostics carry the matching origin, `DiagnosticSource.PORTABLE_CHECKER` — `KERNEL` would claim the kernel arbitrated and `PRECHECK` would claim a rulekit heuristic refused the model.

**Selection is explicit, and there is no `ServiceLoader` registration.** The façade is deliberately not a `ConsistencyOracle` provider: a portable verdict arriving through that port would make `Backends.verification()` answer `KERNEL_CONFIRMED` for a run no kernel arbitrated — an over-claim no consumer could detect, since the kernel is never asked. A caller reaches `PortableConsistencyGate` by name.

Includes and type-definition imports are resolved through the caller's own resolver, transitively: a provider that itself includes a third model is supplied too. A reference the resolver answers with `null` is simply not supplied and the mount is then reported as a coverage gap rather than as a missing reference — the same honest under-report every other entry point makes.

## Calling it from an interpreter consumer — `ConsistencyPreflight`

The preflight ships in the **same** `dmkits-consistency-gate` artifact as the gate above. It had its own `dmkits-interpreter-preflight` coordinate until 2026-09-20, so that a dmkits caller would not carry the interpreter and an interpreter consumer would not carry rulekit and modelkit; with the dmkit developer wave deferred ([RELEASE-SPEC](RELEASE-SPEC.md) §1), nobody could resolve either coordinate and the split bought that hygiene from no one. Its package is unchanged — `io.github.mbackschat.a12.dm.preflight` — so only the dependency line moves if the two are ever split again.

Evaluating a model says nothing about whether A12 accepts it, and the two questions are easy to conflate because one of them answers first. `ModelWorkspace.prepare` says the interpreter can build an evaluator; `Interpreter.supportReport()` says which rules it can execute. **Neither consults A12's constraints**, so a model A12 rejects prepares perfectly well and reports full support — measured, and locked by `ConsistencyPreflightTest`. A consumer who gated evaluation on preparation would evaluate a broken model and get answers about it.

```kotlin
val sources = listOf(WorkspaceModelSource.builder().json(orderJson).build())

when (ConsistencyPreflight.of(sources, "orders").outcome) {
    CheckOutcome.REJECTED        -> // A12 rejects a declaration
    CheckOutcome.NO_REJECTION    -> // nothing rejected it — NOT a pass
    CheckOutcome.NOT_ESTABLISHED -> // nothing was checked; the report says why
}
```

It takes exactly the sources `ModelWorkspace.fromSources` takes, so asking the second question costs no second representation of the model. It builds no prepared graph, holds no interpreter, and changes no evaluator admission — the interpreter accepts and refuses precisely what it did before.

`CheckOutcome` is the checker's own classification (`ConsistencyReport.outcome`), and it exists because `ConsistencyStatus` answers a different question: `Inconclusive` covers both "every family ran and none rejected this" and "nothing ran at all", which are the same verdict and opposite instructions.

**`ALL_CHECKS_PASSED` is the one pass, and it is scoped to the checker rather than to A12** (owner-decided 2026-09-18). It says every family this checker implements ran and none rejected the model — a claim over a finite, enumerable set, so it is decided rather than extrapolated. It does **not** say A12 would accept the model: that is the coverage registry's bar, and `MVK_INVALID_TIME_ZONE` is the measured witness that a model A12 rejects can pass every family here. Act on it; never record it as A12 validation. *(This paragraph replaces "none of the three is a pass, and no fourth value is coming until the coverage registry does" — which tied any pass to the registry, and so refused the weaker claim along with the strong one.)*

**It is not called `VALID` on purpose.** `valid` already names the kernel's verdict on dmtool's envelope, and the two disagree on exactly that timezone model, so one word would carry two contracts across our own surfaces. The name states its own scope instead.

## Calling it from Node

The worked example is [`consistency/consumer-tests/node`](../consistency/consumer-tests/node), which is executable rather than illustrative: `./build.sh :consistency:nodeConsumerTest` packs the library into a real npm tarball, clean-installs it into a staged copy of that project, and runs it. It needs `node` and `npm` and reaches no network — the package declares no runtime dependency. Its cases are not in it: they live under [`consumer-tests/shared`](../consistency/consumer-tests/shared), which the browser example below runs too, so the two arms share a corpus rather than agreeing by inspection. [`editor.ts`](../consistency/consumer-tests/shared/editor.ts) is the one that runs a whole editing sequence — hold an analysis, type into it, decide whether to save — rather than independent calls.

That test is also the only thing in the build that can catch an unconsumable artifact. Every other test has the checker on the compile classpath by construction, and the JS target compiled green for the module's whole life while emitting nothing importable.

### The CI gate — what it passes on, and what it still never claims

[`ci-gate.mjs`](../consistency/consumer-tests/node/ci-gate.mjs) in that same project is a runnable pipeline step: `node ci-gate.mjs <sources…>` prints a structured result and exits `0`, `1` or `70`, reusing dmtool's own code contract ([CLI-SPEC](CLI-SPEC.md) §3b) rather than inventing a second numbering for the same three meanings.

**Copy its shape rather than writing the obvious pipeline, because the obvious one is wrong.** "No findings, therefore green" is the silent pass this checker's report was designed to prevent, and a consumer who writes it will never be told. So the gate's payload carries `validated: false` with no code path that sets it true, and lists per source why nothing was validated. Exit `0` means *no rejection was found*, which is a weaker claim a CI job may still legitimately act on.

> **The gate reports the scoped pass since 2026-09-18, and `validated` still never becomes true.** Those are different claims and the payload carries both: `allChecksPassed` lists the sources where every implemented family ran and none rejected, while `validated` stays constantly `false` because it is about A12 validation, which no amount of the checker's own coverage establishes. A source that passed every check appears in `notValidated` too, with its gaps — not a contradiction, but the distance between the two claims, stated.
>
> **This is also where a duplicated rule went away.** The gate used to mirror `outcome`'s logic in JavaScript, because it holds JSON rather than a typed report; the report now publishes `outcome` and the gate reads it. That bought the one outcome the mirror could never compute — deciding `ALL_CHECKS_PASSED` needs the family denominator, which no member of the wire shape carries. **It also removed a name inversion**: the gate had called "families ran, none rejected" `NOT_ESTABLISHED` and "nothing was checked" `UNREADABLE`, which are the library's `NO_REJECTION` and `NOT_ESTABLISHED` — two identical words meaning opposite things across two surfaces of one product. The gate now uses the library's names, and keeps `UNREADABLE` only for the case the library has no value for: a file the gate itself could not open.

| the gate's outcome | what the report carried | what a job should do |
|---|---|---|
| `REJECTED` | `outcome: REJECTED` | fail the build, and show the codes that justify rejection |
| `ALL_CHECKS_PASSED` | `outcome: ALL_CHECKS_PASSED` | proceed — every implemented family ran and none rejected. Still not A12 validation; never record it as such |
| `NO_REJECTION` | `outcome: NO_REJECTION` | proceed if your policy allows it, but this is weaker than the row above: the run was too narrow to pass, or a declaration went unread |
| `NOT_ESTABLISHED` | `outcome: NOT_ESTABLISHED` — the checker could not admit the source | fail the build as a TOOL failure; nothing was checked |
| `UNREADABLE` | no report at all: the gate could not open the file | fail the build as a TOOL failure. The library has no counterpart — this is the gate's own input failing |
| `UNRECOGNIZED` | an `outcome` this gate does not know, or none at all | fail closed; a gate pinned to an older package must not read an unfamiliar value as a pass |

> **`outcome` here is not `dmtool`'s envelope `outcome`.** The CLI result envelope carries a field of the same name whose vocabulary is what the *operation* did — `applied`, `preview`, `refused`, `rejected`, `read`, `staged`, `completed`, `error`, lower-cased — while this one says what the *check* concluded. They share the word and the value `rejected`, and nothing today conflates them because the CLI does not surface this field. It is stated so a consumer reading both does not join them, and so that surfacing the report through `model check --engine=portable` is recognised as needing a name for one of the two rather than a nested second `outcome`.

**The operational test is `checksCompleted` being empty, never "a diagnostic arrived"** — the distinction a consumer is most likely to get wrong. A `DECLARATION_NOT_WELL_FORMED` diagnostic rides along with a fully completed run: one declaration could not be read and its siblings were checked anyway. Treating that as a tool failure would fail the build for the wrong reason and send the author looking in the wrong place, which is an over-rejection in the consumer rather than in the checker and lands on them just the same.

## Calling it from a browser

**Nothing changes, and that is the finding.** The published npm package is Kotlin/JS's Node production distribution, and it names no host API at all — zero `require(`, `node:` or `process.` references across its six ES modules — so a bundler consumes it unchanged. Import the same functions, `fetch` the model text instead of reading a file, and call them:

```ts
import { checkEditJson, checkModelJson } from 'a12-consistency';

const source = await (await fetch('models/storefront.dm.json')).text();
const report = JSON.parse(checkModelJson(source));
```

The worked example is [`consistency/consumer-tests/browser`](../consistency/consumer-tests/browser), executable like the Node one: `./build.sh :consistency:browserConsumerTest` packs the tarball, installs it into a staged copy of that project, bundles it with Vite, and replays the shared cases in a real system Chrome. It needs `pnpm` and a browser, so it is off `:consistency:check` — and a missing browser fails it rather than skipping it, because a browser arm that passes without a browser establishes nothing.

Two things the example deliberately does not offer. It is a **test fixture, not a showcase**: the page renders verdicts as JSON for its driver to read, and nothing more. And its bundle size is not a bar — the checker's absolute bars are byte counts on what it *publishes*, the packed tarball and the JVM jar, and no tree-shaking is promised ([the measurement, with what it is not](PORTABLE-CONSISTENCY-RESULTS.md#browser-2026-09-17)).

## What this API does not promise

- **No `VALID`.** See the first section; this is the contract, not a temporary state.
- **No stable Kotlin/TypeScript object model.** `checkModelJson` returns a string. The JSON shape above is the contract; the Kotlin types behind it are not exported and will change.
- **No guidance for most codes.** A finding's `corrective` is present whenever an A12 diagnostic owner explains its code and `null` otherwise, which is the common case — see the `findings` section. What is deliberately NOT delivered is the operator catalog's own guidance, 293 KB of it: no finding here names an operator id, so there is nothing to join it against, and packaging it would ship data nothing reads. That changes when a finding carries an operator.
- **No document validation.** This checker reads a DocumentModel and says whether A12 would accept the model. Evaluating a Document against a model is `:interpreter`, a different component with a different contract.


The reserved executable-name check reports `MVK_RULE_NAME_INVALID` only within the formal name domain; malformed reserved names do not suppress an independent missing-code finding (`DeclarationSlotTest`, [KF160](KERNEL-FINDINGS.md#kf160)). Portable findings retain their named-code contract. They do not reproduce the kernel's complete codeless notification stream, whose model-check reporting controls are recorded in [KF291](KERNEL-FINDINGS.md#kf291).
