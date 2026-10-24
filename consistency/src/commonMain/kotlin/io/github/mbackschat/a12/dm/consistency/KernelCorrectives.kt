package io.github.mbackschat.a12.dm.consistency

/**
 * GENERATED — do not edit. Regenerate with `./build.sh :dumpCheckerCorrectives`.
 *
 * A12's own guidance for the codes this checker emits, projected from the single Java
 * owners — `rulekit.validate.StructuralKernelFix` for the curated structural correctives
 * and `rulekit.validate.RkCode` for the `RK_*` catalog. The projection carries exactly the
 * codes the checker's own sources SPELL, which bounds it to this checker's vocabulary but
 * is a superset of what it can emit: a code held to compare an authored value against is
 * spelled too. That every entry is reachable from a real report is `CorrectiveTest`'s
 * measurement, and it names the residue; `CheckerCorrectiveProjectionGuardTest` re-derives
 * this file and fails the build on any drift.
 */
internal val KERNEL_CORRECTIVES: Map<String, Corrective> = mapOf(
    "MVK_ADDITIONAL_CHECK_INVALID" to Corrective(
        fix = "A DATE or DATEFRAGMENT field's opt-in pre-1900 check (`youngerThan1900Check`, our `dateField(…)`/`dateFragmentField(…).rejectBefore1900()`) requires the declared FORMAT to carry a YEAR. Over the kernel's admitted format vocabulary it is accepted on `yyyy`, `yyyy-MM`, `yyyyMM`, `yyyy-MM-dd`, `dd.MM.yyyy`, `yyyyMMdd` and `yyyy-MM-dd'T'HH:mm:ss`, and refused on the year-free `MM`, `MM-dd`, `MMdd`, `ddMM`, `dd.MM.` and `HH:mm:ss` — identically on both kinds. So either declare a year-bearing format or drop the check — it is not admitted-and-inert on a year-free declaration, so there is no way to keep both. An INCOMPLETE format is not the trigger: `yyyy` and `yyyy-MM` carry no day and are accepted. A DATERANGE is EXEMPT from this gate — every admitted (format, separator) pair takes the check, the year-free ones included — so if a year-free period is what you need, declare it as a range.",
        meaning = "The kernel gates the additional date check on the declared format's YEAR component rather than on the field's kind — except that the DATERANGE kind is not gated at all — so the code means the format and the check disagree, never that the check is unsupported for this field type.",
    ),
    "MVK_ERROR_FIELD_AND_ITERATION_INVALID" to Corrective(
        fix = "Read the error field itself per row, unstarred, somewhere in the condition — most often as the guard: FieldFilled(<the error field>) And … instead of a guard on a sibling field of the same row. Or drop the per-row read, so the rule no longer iterates.",
        meaning = "A per-row (unstarred) read makes the rule run once per repetition, and the kernel then needs a reference NAMING the error field — the field or a group containing it — that reads the same repetition per row. As KERNEL-FINDINGS KF417 measures, a starred list such as Sum(Rows*/Field) names the field without saying which row, so it does not count. A computation has no error field and is not affected.",
    ),
    "MVK_ERROR_FIELD_NOT_IN_RULEGROUP" to Corrective(
        fix = "The declaration ITERATES and writes outside its own group, so there are three repairs and the first is usually the smallest: DECLARE it in a group that contains the field it writes — an ancestor will do, containment is what the kernel asks for, not membership of the repeating group. Or write into a field under the declaring group. Or stop the operand iterating: a STAR aggregate that stars EVERY repeatable level it crosses (Sum(Group*/Field), Sum(Outer*/Inner*/Field)) reads no row, where any level left unstarred is what makes it iterate. That lifts the demand unless the computed field itself repeats, which iterates with no operand at all.",
        meaning = "A declaration that iterates has to say which repetition its result belongs to, and the kernel reads that from PATH CONTAINMENT: the written field must lie at or below the declaring group. It gates both constructs, but they differ in what derives the iteration — an unstarred per-row read does so for either, while a per-row COMPUTED field derives it with no operand at all and a per-row ERROR field does not (measured 2026-09-15, KERNEL-FINDINGS KF212). Crossing a root boundary is not itself a refusal.",
    ),
    "MVK_ERROR_FIELD_NOT_REFERENCED" to Corrective(
        fix = "Name the rule's error field somewhere in the condition. Most often this is a model-level aggregate rule whose condition only references the aggregated per-row path — reference the error field by comparing the aggregate AGAINST it (`[FeeCap] < Sum(Lines*/Amount)` references FeeCap) or by guarding with `FieldFilled(<the error field>) And …`.",
        meaning = "Every rule's error field must appear among the fields its condition references — the kernel attaches the fired error to that field and rejects a condition that never names it. An aggregate's starred path does not reference the (non-repeatable) error field, so model-level aggregate rules hit this unless the error field is referenced explicitly.",
    ),
    "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD" to Corrective(
        fix = "Drop the PROJECTION from the read of the computed field, not just the reference: a computation may not read its own computed field through an enum category (`[Target -> Cat]`) or a semantic index (`[Group/Target For \"key\"]`), in an operation OR a precondition. Compute from a DIFFERENT field's projection instead (`[Source -> Cat]` is accepted, including as a FirstFilledValue entry), or read the same indexed field into a different target. Removing only the projection still leaves the plain self-reference (MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD), so the operand has to change, not its form.",
        meaning = "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD (\"the calculated field must not specify a semantic index or a category access\") is the PROJECTED half of the calculated-field-reference ban, and it pre-empts the plain half when both readings occur in one operation — so this code identifies the reading MODE, while the plain code is what an unprojected self-read reports (kernel-measured, KF182).",
    ),
    "MVK_ERROR_WRONG_ROUNDING_MODE" to Corrective(
        fix = "Give EVERY alternative a tolerance band (toleranceRangeOp DiffersWithToleranceRange1, 2, 5 or 10), or keep the rounding mode Exact. The mode is never wrong by itself: it is refused only beside an alternative without a band, in any position. Before adding the band, check that rounding is what you want: RoundUp/RoundDown round the WRITTEN value to a whole number (RoundUp toward +infinity, RoundDown toward -infinity), not to the computed field's digits; to round to digits, keep Exact and round inside the operation (RoundUp([op], 2)).",
        meaning = "A12's check (\"When using exact calculation comparisons only the rounding mode 'exact' is allowed\") judges a PAIR: an alternative without a band compares the computed field exactly, and A12 refuses a non-Exact mode against that comparison, while the same mode beside a band is admitted (kernel-measured, KF297; what the admitted pair writes: KF474).",
    ),
    "MVK_FIELDNAME_NOT_UNIQUE" to Corrective(
        fix = "The bare name resolved through the model-wide fallback, which found MORE THAN ONE field with that short name and will not choose between them. Qualify the reference instead of declaring anything: give it the ABSOLUTE path of the field you mean (a leading `/`, e.g. `/Order/Warehouse/Code`). This is the OPPOSITE corrective to MVK_INVALID_ENTITY, which means no field carries the name at all — there the field is missing, here there are too many, and turning `fieldRefByShortNameAllowed` off converts this code into that one rather than fixing the reference.",
        meaning = "MVK_FIELDNAME_NOT_UNIQUE means the rule's own group does not declare the name, so the kernel fell back to a model-wide lookup (which `fieldRefByShortNameAllowed` enables), and that lookup requires a UNIQUE match.",
    ),
    "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES" to Corrective(
        fix = "An enum's display labels are STARTED and not finished: top each value's `labels` up so every value carries a NONBLANK text for every declared locale, or remove the label entries altogether — an unlabelled enum is accepted. Blanking a text does not remove it. This most often surfaces right after adding a locale (`config modify --add-locale`): do the `--add-locale` and the per-value label top-ups in ONE `apply` so the single terminal gate sees a consistent model.",
        meaning = "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES (\"the display value and the XML value were not specified together\") is armed by the PRESENCE of a label entry for a declared locale, not by coverage: once any value carries one, every value owes a nonblank text for every declared locale. Labels written only for an UNDECLARED locale are accepted, and every text blank is refused, so neither \"is the locale covered\" nor \"is any label there\" describes it (measured 2026-09-13, KERNEL-FINDINGS KF321). A value that carries no `labels` at all while a sibling does is a different code, MVK_COUNT_OF_VALUES_INVALID.",
    ),
    "MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE" to Corrective(
        fix = "Keep exactly one RepetitionNotUnique leaf in the condition. Put every component of the composite repetition key into that one call, then combine that leaf with ordinary conditions as needed.",
        meaning = "The kernel's structural model gate permits at most one RepetitionNotUnique node in one condition tree. The restriction counts nodes, not key fields and not And/Or nesting; one leaf may carry a composite key and otherwise composes normally.",
    ),
    "MVK_INVALID_COMPARE_DEC_PLACES" to Corrective(
        fix = "The kernel could not establish compatible DERIVED decimal scales here. Numeric == / != carries a known SIGNED scale or unknown plus an independent multiplicative-constant capability: when known scales differ, only a capable SMALLER-scale side may be padded; an unknown scale rejects. ORDERING (< <= > >=) is exempt. THREE contexts trigger this code: (1) a COMPUTED FIELD whose declared scale is incompatible with its expression (e.g. a field re-scaled to 2 places while the Sum-computed field stays scale 0) — re-scale the computed field or explicitly round the expression, doing both edits in ONE `apply` so the terminal gate sees a consistent model; expect a SECOND rejection to surface if the operation also reads its own computed field, because this scale comparison is decided ahead of that refusal; (2) numeric == / != with incompatible derived scales — round one side to a known compatible scale, or accept it with allowDifferingDecimals:true on the rule spec (the kernel's one suppressible warning — it prepends @SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES), @UnterdrueckeWarnung in a German-condition model); (3) a POWER, whose scale is DERIVED and is reported as '?' more often than authors expect — it is known (and 0) only for a scale-0 base under a nonnegative literal exponent, never the base's own scale, so a decimal-scale base, a non-literal exponent such as a field or aggregate, and a negative literal exponent all leave it unknown; ordering the power instead of comparing it for equality is usually the smallest repair.",
        meaning = "MVK_INVALID_COMPARE_DEC_PLACES (\"the fractional digits of lhs and rhs are different\") fires when numeric == / != cannot reconcile the operands' derived signed-scale/capability summaries, OR when a computed field is incompatible with its expression. It is not specific to any one operator (Sum, Equal, …); the scale-coupling is the constraint, so the corrective is context-dependent.",
    ),
    "MVK_INVALID_COMPARE_TO_DATE" to Corrective(
        fix = "Check the LITERAL before the field. A double-quoted literal whose CONTENT parses as a date is a TEMPORAL constant — `\"05.03.2024\"` is one — and it reports this date class against whatever it meets, including a String, Enumeration or Number field with nothing temporal about it. So the code does NOT imply either side is a date. If the field really is textual, stop the literal looking like a date rather than changing the field.",
        meaning = "This is the kernel's COMPARISON vocabulary, not an assignment-specific class: it fires the same way on a plain rule condition, inside a Having filter, and on a computation's constant assignment. Which class a wrong-kind constant draws is an ordered ladder over the (constant, field) PAIR rather than a function of the field: a BOOLEAN field reports MVK_INVALID_COMPARE_TO_YESNO and a CONFIRM one MVK_INVALID_COMPARE_TO_YES for any constant at all; failing those, a temporal constant reports THIS code against every remaining kind; only then does the field's own kind decide. Measured in KERNEL-FINDINGS entry KF257, which carries the ladder and its controls.",
    ),
    "MVK_INVALID_ENTITY" to Corrective(
        fix = "The group or field name in the condition could not be resolved in the rule's scope. Use its ABSOLUTE path (a leading `/`, e.g. `/Order/Items/Sku`) rather than a bare or relative name — a bare name binds against the rule's OWN group first, and reaches ANY other group (an ancestor's field included: there is no upward search) only through the model-wide fallback, which needs `fieldRefByShortNameAllowed` AND a unique match. If the name is already correct, check the rule's iteration scope contains it: a per-row field needs the rule to iterate over its repeatable group (set the rule group to that group's PARENT, error field inside).",
        meaning = "MVK_INVALID_ENTITY (\"the group or field 'X' is not valid\") means the kernel could not resolve that name — almost always a bare/relative reference that does not bind across sibling groups, or a field outside the rule's iteration scope.",
    ),
    "MVK_INVALID_LENGTH_OF_RAW_TYPE" to Corrective(
        fix = "Length over a noValueValidation (raw-type) field is allowed ONLY as the WHOLE rule condition `Length(f) > <constant>` (or the mirrored `<constant> < Length(f)`), where the constant is a decimal-point-free signed-32-bit integer. Use no And/Or around it, strict > only (no >=, ==, <=), and never inside a computation. Know what you author: that one shape is a MAX-LENGTH DECLARATION the kernel lifts into the field's metadata and ELIMINATES as a rule — it never fires at runtime, so do not expect a validation message from it.",
        meaning = "MVK_INVALID_LENGTH_OF_RAW_TYPE (\"conditions using the length of a field which defines 'no value validation' must have following form: 'Length(<field>) > <constant>'\"): the kernel accepts exactly that comparison as a meta-length restraint (codegen removes the rule and tightens the generated max length); every other Length use of a raw-type field is rejected because its value is never available to evaluation.",
    ),
    "MVK_INVALID_PATTERN" to Corrective(
        fix = "Use a Java-valid regular expression that also avoids the kernel 30.8.x admission restrictions: possessive quantifiers, lookbehind/named-capture `(?<…`, atomic `(?>…)`, the A12K-30 escapes `\\A \\G \\Z \\z \\a \\e \\p \\Q \\E` (lowercase `\\p` only — uppercase `\\P` is admitted), and nested unescaped `[` class syntax. The gate reads the pattern's SOURCE TEXT, so escaping buys no exemption: `a\\*+` is refused for spelling `*` before `+`, though the pattern it means is ordinary. Passing this gate does NOT prove JavaScript portability.",
        meaning = "The kernel first requires Java Pattern syntax, then applies an additional textual admission gate. MVK_INVALID_PATTERN can therefore mean invalid Java syntax or a Java-valid kernel-rejected form; it is not a general cross-runtime portability check. A DECLARED field pattern raises MVK_PATTERN_INVALID instead — the same two words swapped, and a different code.",
    ),
    "MVK_INVALID_RAW_TYPE" to Corrective(
        fix = "The condition (or computation) reads the VALUE of a field that declares noValueValidation (a raw-type string) — the kernel forbids every value use of such a field: comparisons, value lists, computations, even \$field.value\$ in a message. Test PRESENCE instead (FieldFilled / FieldNotFilled and the quantifiers work normally), or drop noValueValidation from the field (`field modify`) if its value must participate.",
        meaning = "MVK_INVALID_RAW_TYPE (\"It is not allowed to use a field which defines 'no value validation'\"): a noValueValidation field's value is never interned for evaluation — the kernel caches a pseudo value — so no rule or computation can read it. The only value-shaped construct it permits is the whole-condition `Length(f) > <constant>`, and that is a disguised max-length declaration, not an evaluated rule.",
    ),
    "MVK_NEG_CONDITION_IN_ITERATION" to Corrective(
        fix = "Guard the negative predicate with a positive existence check on the row: GroupFilled(<the repeatable group>) And <your negative condition> — so the rule fires only for rows that actually exist, not for the empty/absent repetitions. A negative presence predicate used as an iterating Or branch is not guarded by its sibling; rewrite that branch as a positively guarded And, or split it into a separate guarded rule.",
        meaning = "A condition the kernel reads as able to fire on the empty/non-existent repetitions of the iterated group needs a positive guard to scope it to real rows. The trigger is NOT the operator's name: FieldsNotCollectivelyFilled is negative and accepted, while NumberOfFilledFields(<group>) < 1 has no negation and is refused. Nor is it the condition's meaning: '< 1' is refused where the equivalent '<= 0' and '== 0' are accepted. The analysis is syntactic and kernel-owned, so add the guard rather than predicting the gate. This is a structural iteration law, not a restriction on RepetitionNotUnique composing as an ordinary leaf under a legal And/Or tree.",
    ),
    "MVK_NOT_IN_SAME_PATH" to Corrective(
        fix = "Keep every RepetitionNotUnique key on one repetition path. Do not mix fields from sibling or parallel repeatable groups in one key; choose one path, or enforce the independent uniqueness constraints with separate rules.",
        meaning = "A composite uniqueness key needs one shared repetition lineage so the kernel can form one row tuple. Fields from parallel repeatable groups do not identify a single correlated row.",
    ),
    "MVK_NO_GROUPS_ALLOWED" to Corrective(
        fix = "A GROUP was supplied where a FIELD (or a flattened field list) is required — the fix depends on the construct. For an AGGREGATE or a `Having` filter, the operand is a FLATTENED repetition list (the repeatable group starred, then the field: `/Order/Lines*/Amount`), NOT a bare group wildcard (`Lines*`): write Sum(Lines*/Amount) or Sum(Lines*/Amount Having <filter>); to count the group's rows instead use NumberOfFilledGroups(Lines*). For a FIELD-PRESENCE predicate, FieldFilled / FieldNotFilled take a field — for a group's presence use GroupFilled / GroupNotFilled.",
        meaning = "MVK_NO_GROUPS_ALLOWED fires when a group is supplied where the construct requires a field or a flattened field list: an aggregate / Having operand must flatten the repetitions to a starred-group-then-field list (a bare group wildcard is not a value list), and a field-presence predicate (FieldFilled) must name a field, not a group. The required form depends on the operator, so this code is not specific to any single one.",
    ),
    "MVK_NO_WILDCARD" to Corrective(
        fix = "A repeatable group is referenced from a rule that does not iterate over it — the fix is operator-dependent. For an AGGREGATE, flatten the repetitions with the `*` wildcard: Sum(Lines*/Amount), NumberOfFilledFields(Lines*/Sku), NumberOfFilledGroups(Lines*). For a PER-REPETITION predicate (RepetitionNotUnique / CurrentRepetition), make the rule iterate by putting its group at the PARENT of the repeated group with the error field inside it — via the CLI, `rule check --group <parentGroup> --field <parentGroup>/<repeatedGroup>/<field>` (e.g. --group /Order --field /Order/Items/Sku for RepetitionNotUnique(Items/Sku)).",
        meaning = "MVK_NO_WILDCARD fires when a repeatable group is referenced from a non-iterating scope: the kernel expected either a flattening `*` (aggregates) or a rule that iterates over that group (per-repetition predicates). The required form depends on the operator, so this code is not specific to any single one.",
    ),
    "MVK_OPTIONAL_DATE_TYPE_INVALID" to Corrective(
        fix = "A partially known date (`datePrecision` DAY_OPTIONAL / MONTH_OPTIONAL / YEAR_OPTIONAL) requires the declared FORMAT to be a COMPLETE date and nothing further. Over the kernel's admitted format vocabulary that is exactly `yyyy-MM-dd`, `dd.MM.yyyy` and `yyyyMMdd`; every component-omitting format is refused, and so is the instant `yyyy-MM-dd'T'HH:mm:ss` despite carrying all three date components. Declare one of those three, or leave the precision at its FULL default — the precision is what lets the stored VALUE omit parts (`00.02.2024`), so the format must still be able to spell all of them. Do NOT copy the format out of this rejection: it echoes the spelling in GERMAN pattern letters (`JJJJ-MM` for `yyyy-MM`, `MM-TT` for `MM-dd`) in every locale, and those are not declarable.",
        meaning = "The kernel requires a complete date format before it will accept a partial-precision marker, and reports the conflict against the format rather than against the precision — the verdict is the same for all three partial values, so the code identifies the mismatch and not which side to edit.",
    ),
    "MVK_PARAMSIZE_INVALIDGN" to Corrective(
        fix = "If the sole operand is a REPEATABLE group, star it — NumberOfFilledGroups(Items*) — rather than adding a second group, which the kernel's own message suggests but which can then draw MVK_NEG_CONDITION_IN_ITERATION inside an iterating rule. Add a second distinct, non-overlapping group only when the operands are NON-repeatable groups you genuinely want counted together.",
        meaning = "The count admits either two or more distinct groups or a single STARRED repeatable group, so \"there must be more than one group\" names only one of the two ways out. Which code a sole unstarred repeatable operand draws depends on the rule's anchoring: MVK_NO_WILDCARD when the error field sits outside that group, this code when it sits inside — and the star satisfies both.",
    ),
    "MVK_PATTERN_INVALID" to Corrective(
        fix = "A DECLARED field pattern (`StringType.pattern`, and the same slot on a type definition) goes through the identical gate as a condition's pattern literal: valid Java Pattern syntax, then the kernel's A12K-30 admission blacklist read on the pattern's SOURCE TEXT — possessive quantifiers, lookbehind/named-capture `(?<…`, atomic `(?>…)`, the escapes `\\A \\G \\Z \\z \\a \\e \\p \\Q \\E` (lowercase `\\p` only), and nested unescaped `[`. Because it reads text, escaping buys no exemption: `a\\*+` is refused for spelling `*` before `+`.",
        meaning = "The DECLARED-field twin of MVK_INVALID_PATTERN, and a DIFFERENT code — the same two words swapped, so a consumer keying on the condition's code sees nothing here. Like it, it can mean invalid Java syntax or a Java-valid form the admission gate refuses, and passing it does not prove JavaScript portability.",
    ),
    "MVK_REPEATABLE_GROUP_MISSING" to Corrective(
        fix = "Check BOTH causes — this one code covers them and does not say which applies. (1) The rule sits INSIDE the repeated group: place it at the PARENT while keeping its error field inside that group — via the CLI, `rule check --group <parentGroup> --field <parentGroup>/<repeatedGroup>/<field>`, for example `--group /Order --field /Order/Items/Sku` with `RepetitionNotUnique(Items/Sku)`. (2) A key names a field whose group is not repeatable at all — whether it is the sole key or sits beside a good one; moving the rule cannot fix that, so drop the key or make its group repeatable.",
        meaning = "Every RepetitionNotUnique key must cross a repeatable-group boundary relative to the rule group. Declaring the rule inside the repeated row removes that boundary, and a key in a non-repeatable group never had one; the kernel reports both as this single class, so the code identifies the missing boundary and not which edit restores it.",
    ),
    "MVK_UNEXPECTED_TOKEN" to Corrective(
        fix = "The condition has a token the DSL parser did not expect (a syntax slip the kernel reports with no message of its own). The most common causes, in order: (1) a BOOLEAN literal must be CAPITALIZED — write `True` / `False`, not the JSON `true` / `false` (e.g. `[Flag] == True`); (2) a string value must be DOUBLE-QUOTED — `[Status] == \"OPEN\"`; (3) use the configured language's operator and keyword spellings; connective variants are enumerated, for example `And`/`and`/`AND`, not arbitrary mixed case; (4) a FIELD used as a comparison VALUE must be BRACKETED — `[Approved] != True`, not `Approved != True` (bare names belong only inside a function/predicate's parentheses, like `FieldFilled(Approved)`). Re-check the token at the reported position against these.",
        meaning = "MVK_UNEXPECTED_TOKEN is the kernel's generic parse error — a token appeared where the grammar did not allow it — and it is raised WITHOUT a message, so the likely cause has to be inferred from the DSL's literal/keyword conventions (capitalized booleans True/False, double-quoted strings, and the configured language's admitted keyword spellings).",
    ),
    "RK_BOUND_SIGNED_ZERO" to Corrective(
        fix = "write a bound that keeps its value at the declared fractional digits — 0 itself, or allow a fractional digit so a small negative bound does not round to -0.",
        meaning = "A12's sentence \"The value '0' as well as positive values must be provided without a sign.\": a Number's `minValue` or `maxValue` prints as `-0`, A12 rounding it half away from zero to a whole number when no fractional digit is allowed (KERNEL-FINDINGS KF569), and it withholds `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE`, where the portable checker names the sentence by this code.",
    ),
    "RK_BOUND_TOO_MANY_DIGITS" to Corrective(
        fix = "write the bound with 15 digits or fewer, counting a leading 0; drop precision the field's fractional digits cannot hold.",
        meaning = "A12's sentence \"Only values with up to 15 digits are allowed.\": a Number's `minValue` or `maxValue`, as A12 prints it, has more than 15 digits, a leading `0` counted (KERNEL-FINDINGS KF569), and it withholds `MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE`, where the portable checker names the sentence by this code.",
    ),
    "RK_DATE_RANGE_FORMAT_INVALID" to Corrective(
        fix = "pair the format with the separator A12 admits for it, for example `dd.MM` with `-`, or `yyyy` with `/`.",
        meaning = "A12's sentence \"The date range format is invalid.\": a DateRange type composes `format + rangeSeparator + format`, and A12 allowlists that PAIR rather than either half, so a valid format and a valid separator can still be refused together (KERNEL-FINDINGS KF581), where the portable checker names the sentence by this code.",
    ),
    "RK_DATE_RANGE_SEPARATOR_INVALID" to Corrective(
        fix = "use `-` or `/` as the range separator.",
        meaning = "A12's sentence \"Only minus (-) or slash (/) may be used for date range types.\": a DateRange type's `rangeSeparator`, trimmed, is neither empty, `-` nor `/` (KERNEL-FINDINGS KF581), where the portable checker names the sentence by this code.",
    ),
    "RK_ENUM_TEXT_LINE_BREAK" to Corrective(
        fix = "remove the line break from the value, label or category token.",
        meaning = "A12's sentence \"Only values without line breaks are allowed.\": an Enumeration's value, label for a declared locale or category token carries a newline or carriage return. A12 states it once for the field, ahead of the tab and pipe classes, and does not ask those of the text carrying the break (KERNEL-FINDINGS KF418), where the portable checker names the sentence by this code.",
    ),
    "RK_ERROR_CODE_ALPHABET" to Corrective(
        fix = "write the error code, or the computation's name, in letters, digits, spaces and underscores only.",
        meaning = "A12's sentence \"The error code is only allowed to contain letters, digits, blanks and underscores.\": a rule's trimmed error code, or a computation's name, carries anything else, the letters including the German umlauts and `ß` (KERNEL-FINDINGS KF578), where the portable checker names the sentence by this code.",
    ),
    "RK_ERROR_CODE_EMPTY" to Corrective(
        fix = "give the rule a non-empty `errorCode`.",
        meaning = "A12's sentence \"The error code is empty.\": a rule's `errorCode` is absent or empty, drawn beside `MVK_ERROR_CODE_MISSING`; a blank but non-empty code draws that class alone, and A12 states this sentence before any variant filter (KERNEL-FINDINGS KF579), where the portable checker names the sentence by this code.",
    ),
    "RK_FIELD_NAME_ALPHABET" to Corrective(
        fix = "rename the field to letters, digits, underscores and colons, separating parts with a dot and starting each later part with a letter.",
        meaning = "A12's sentence \"A field name may only contain letters, digits or underscores. Field names must not begin with a digit.\": a field's trimmed name is not letters, digits, underscores and colons in dot-separated parts, each part after the first beginning with a letter. Despite the sentence, a leading digit is admitted (KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
    ),
    "RK_GROUP_PATH_INVALID" to Corrective(
        fix = "rename the group: a nested group name begins with an ASCII letter or `_` and continues in letters, digits, `_`, the German umlauts and `ß`, at most 60 characters.",
        meaning = "A12's sentence \"A group name may only contain letters, numbers or underscores. Group names must begin with a letter or an underscore. Name parts may not be longer than 60 characters …\": a part of the trimmed group path an element lives in is over 60 characters or outside the group alphabet, so A12 states it at that group and at every element beneath (KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
    ),
    "RK_MEMBER_ABOVE_60_CHARACTERS" to Corrective(
        fix = "shorten the root group's name to 60 characters or fewer.",
        meaning = "A12's sentence \"Only values with up to 60 characters are allowed.\": a root group's name is longer than 60 characters, and its alphabet is admitted (an alphabet refusal takes this sentence's place; KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
    ),
    "RK_MEMBER_ABOVE_DIGIT_BUDGET" to Corrective(
        fix = "declare 15 integer places or fewer; integer and fractional places together are bounded by the same budget.",
        meaning = "A12's sentence \"Only values smaller than or equal to 15 are allowed.\": a Number's integer count, declared or derived for an amount, is above A12's digit budget of 15 (KERNEL-FINDINGS KF559), where the portable checker names the sentence by this code.",
    ),
    "RK_MEMBER_ABOVE_SCALE_LIMIT" to Corrective(
        fix = "declare 14 fractional places or fewer.",
        meaning = "A12's sentence \"Only values smaller than or equal to 14 are allowed.\": a Number's fractional count is above A12's scale cap of 14 (KERNEL-FINDINGS KF559), where the portable checker names the sentence by this code.",
    ),
    "RK_MEMBER_ABOVE_TECHNICAL_LIMIT" to Corrective(
        fix = "declare a length of 99,999 or less, or -1 for no bound on a String or Custom field.",
        meaning = "A12's sentence \"Only values smaller than or equal to 99999 are allowed.\": a String, Custom or Number length is above A12's technical limit of 99,999 (KERNEL-FINDINGS KF556), where the portable checker names the sentence by this code.",
    ),
    "RK_MEMBER_NOT_POSITIVE" to Corrective(
        fix = "declare at least 1; to leave a String or Custom length unbounded, write -1 rather than 0.",
        meaning = "A12's sentence \"Only values greater than 0 are allowed.\": a length, an integer count or a group's repeatability is 0, where A12 admits 1 and above (KERNEL-FINDINGS KF556, KF559, KF590), where the portable checker names the sentence by this code.",
    ),
    "RK_MEMBER_SIGNED" to Corrective(
        fix = "write a count of 0 or more, or -1 to declare no bound where the member admits it; a Number length admits no -1.",
        meaning = "A12's sentence \"Only values without a sign are allowed.\": a length, a digit count or a group's repeatability is negative. -1 is the one signed value admitted, and only where it means \"no bound\" (a String or Custom length, a fractional count) (KERNEL-FINDINGS KF556, KF559, KF590), where the portable checker names the sentence by this code.",
    ),
    "RK_NUMBER_MAX_VALUE_EXCEEDED" to Corrective(
        fix = "lower the maximum to 10^(15 - k) - 10^(-k) or less, or declare fewer fractional digits to widen that limit.",
        meaning = "A12's sentence \"The maximum value of the number type in field [id: …] is specified with '…'. It may not exceed '…'.\": a Number's `maxValue`, signed and unrounded, is above `10^(15 - k) - 10^(-k)` for `maxFractionalDigits` k, an absent count being 0 (KERNEL-FINDINGS KF582), where the portable checker names the sentence by this code.",
    ),
    "RK_NUMBER_MIN_VALUE_EXCEEDED" to Corrective(
        fix = "raise the minimum until its absolute value is within 10^(15 - k) - 10^(-k), or declare fewer fractional digits to widen that limit.",
        meaning = "A12's sentence \"The minimum value of the number type in field [id: …] is specified with '…'. It may not exceed '…'.\": a Number's `minValue`, by its absolute value and unrounded, is above `10^(15 - k) - 10^(-k)` for `maxFractionalDigits` k, an absent count being 0 (KERNEL-FINDINGS KF582), where the portable checker names the sentence by this code.",
    ),
    "RK_ROOT_GROUP_NAME_ALPHABET" to Corrective(
        fix = "rename the root group to letters, digits, underscores, the German umlauts and `ß`.",
        meaning = "A12's sentence \"A root group name may only contain letters, numbers and underscores.\": a root group's trimmed name carries a character outside letters, digits, `_`, the German umlauts and `ß`; a leading digit is admitted (KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
    ),
    "RK_RULE_NAME_ALPHABET" to Corrective(
        fix = "rename it to an ASCII letter followed by ASCII letters, digits and underscores.",
        meaning = "A12's sentence \"A rule name must start with a letter followed by letters, digits and underscores.\": a rule's or computation's trimmed name is not an ASCII letter followed by ASCII letters, digits and underscores (KERNEL-FINDINGS KF578), and it withholds `MVK_RULE_NAME_INVALID`, where the portable checker names the sentence by this code.",
    ),
    "RK_SORT_LIST_ABOVE_CAPACITY" to Corrective(
        fix = "keep at most three `sortFields` entries.",
        meaning = "A12's sentence \"The index for group '/MVK/Model/Groups/SortInfo/SortFields' was specified with 4, but at most 3 is allowed.\": a group's `sortFields` holds more than three entries, and each entry beyond the third draws it once (KERNEL-FINDINGS KF345), where the portable checker names the sentence by this code.",
    ),
    "RK_UNDECLARED_MESSAGE_LOCALE" to Corrective(
        fix = "supply the message for one of the model's declared locales, or declare the locale on the model first.",
        meaning = "the candidate supplies an error message for a locale the model does not declare; the kernel's error-text checker runs per declared locale, so that text would be reported valid without ever being examined.",
    ),
    "RK_UNSUPPORTED_CHARACTER" to Corrective(
        fix = "replace the character above U+FFFF, such as an emoji, in that text.",
        meaning = "A12's sentence \"The field contains one or several unsupported signs.\": a text A12 validates as part of the model, such as a rule's condition, code or message, or an enum value, carries a character outside the Basic Multilingual Plane. Model, group and field labels are not checked (KERNEL-FINDINGS KF489), where the portable checker names the sentence by this code, once per element.",
    ),
)
