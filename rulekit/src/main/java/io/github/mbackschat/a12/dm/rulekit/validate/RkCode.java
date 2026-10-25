package io.github.mbackschat.a12.dm.rulekit.validate;

import io.github.mbackschat.a12.dm.core.KernelCode;

import java.util.Optional;

import io.github.mbackschat.a12.dm.core.Severity;

/**
 * The {@code RK_*} diagnostic vocabulary — rulekit/structure codes — as a typed enum that <b>is</b> the
 * single-source registry (CLI-CONFORMANCE-SPEC). Each constant carries the code's canonical meaning, its
 * typical {@link Severity} and {@link DiagnosticSource}, and the general repair guidance, so every surface
 * that explains a code derives from here: the CLI {@code diagnostics} verb, the {@code diagnostic.schema},
 * and the library Javadoc all read this enum — never a second hand-authored copy that could drift.
 *
 * <p>The constant name <em>is</em> the code minus the {@code RK_} prefix ({@link #NO_SUCH_FIELD} ⇒
 * {@code "RK_NO_SUCH_FIELD"}), so {@link #code()} is derived and an emit site that references the constant
 * cannot ship an un-catalogued code. {@code MVK_*} (kernel) codes are <em>not</em> here — their meaning is
 * owned by the kernel and surfaced through the operator catalog / {@link DiagnosticEnricher}; this enum is
 * the vocabulary rulekit owns and emits.
 *
 * <p>{@code severity}/{@code source} are the <em>canonical</em> classification for the catalog; a specific
 * emit may legitimately differ (e.g. a precheck warning vs error), and the wire carries the emitted value.
 */
public enum RkCode {

    // --- lint advisories (valid-but-suspicious; LINT) -------------------------------------------
    UNGUARDED_NUMBER_COMPARISON(Severity.WARNING, DiagnosticSource.LINT,
            "a number is compared with an ordering operator but its presence is not guarded; an empty number reads as 0, so the rule also fires on empty — a trap only when empty should be NEUTRAL, intentional when a missing number is itself a violation. This also covers an ordering between two unguarded number fields ([A] < [B]): either side empty reads as 0, so the ordering can fire on the absence of either field.",
            "decide by intent: if an empty value should NOT trip the rule, guard it — FieldFilled(<number>) And <the comparison> (or AllFieldsFilled(<A>, <B>) for a field-vs-field ordering); if empty-as-0 SHOULD fire (a missing number is a violation), the unguarded form is intentional — leave it."),
    UNGUARDED_STRING_CONCAT(Severity.WARNING, DiagnosticSource.LINT,
            "a string field participates in a concatenation compared as a value, but its operands are not presence-guarded; an empty string still concatenates, so the comparison is evaluated and can mis-fire.",
            "guard the concatenated operands with AllFieldsFilled(...) before comparing."),
    INVERTED_REQUIREDNESS(Severity.WARNING, DiagnosticSource.LINT,
            "the condition's polarity looks inverted for a requiredness rule — it appears to fire when the field IS filled rather than when it is missing.",
            "re-check the polarity; a 'required' rule fires on the NOT-filled case."),
    NEG_CONDITION_IN_ITERATION(Severity.WARNING, DiagnosticSource.LINT,
            "a negated condition inside an iteration (repeatable group) that no positive presence guard bounds — the kernel rejects this shape with " + KernelCode.MVK_NEG_CONDITION_IN_ITERATION + ", so this advisory PREDICTS a refusal rather than advising on intent.",
            "state the per-row condition positively, or add the positive presence guard the kernel needs to bound the iteration."),
    REDUNDANT_OPERAND(Severity.WARNING, DiagnosticSource.LINT,
            "an operand is redundant — it cannot change the predicate's outcome.",
            "drop the redundant operand."),
    CONTRADICTORY_PRESENCE(Severity.WARNING, DiagnosticSource.LINT,
            "presence predicates over the same field contradict (e.g. filled AND not-filled), so the condition can never hold as written.",
            "remove the contradiction; one of the presence checks is wrong."),
    PRESENCE_NOT_COMPLEMENTARY(Severity.WARNING, DiagnosticSource.LINT,
            "two presence predicates that look intended as complements are not (e.g. both positive), so the rule's coverage is not what it appears.",
            "make the presence checks complementary, or confirm the overlap is intended."),
    GOTCHA(Severity.INFO, DiagnosticSource.LINT,
            "a catalog-known gotcha applies to an operator used here — valid, but a common trap worth confirming.",
            "read the operator's gotcha (see `operators <name>`) and confirm the intent."),
    COMPUTATION_FORBIDDEN_CONSTRUCT(Severity.WARNING, DiagnosticSource.LINT,
            "a computation precondition uses a construct the kernel forbids in computations — CustomCondition or RepetitionNotUnique — so the kernel will reject it (MVK_ERROR_INVALID_LANGUAGE_FEATURE).",
            "remove the forbidden construct from the precondition; CustomCondition and RepetitionNotUnique are not allowed in a computation."),
    COMPUTATION_OVERLAPPING_PRECONDITIONS(Severity.WARNING, DiagnosticSource.LINT,
            "two computation alternatives share an identical precondition, so they overlap — computation takes the first match, but every guarded mismatch remains active in implicit validation.",
            "make the alternatives' preconditions mutually exclusive; a later overlap cannot compute but can still make validation fire."),

    // --- build-time / structure prechecks (ERROR; PRECHECK) -------------------------------------
    // Named for rules because Rule.Builder was its only emitter; the computation-spec parser now emits it too,
    // so the meaning names both slot sets rather than sending a computation author looking for an error field.
    INCOMPLETE_RULE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a rule or computation is missing a required slot — a rule needs a name, error entity, condition, code"
                    + " and severity; a computation needs a computed field, at least one alternative with an"
                    + " operation, and at least one message. It cannot be built.",
            "supply the missing slot before building it (see `schema rule add` / `schema computation add`)."),
    ERROR_FIELD_OUTSIDE_GROUP(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the rule's error entity is not strictly inside the rule's scope group, so the error cannot attach.",
            "point the error entity at a field or group within the rule's group subtree, or author the rule in a"
                    + " group that contains it."),
    NO_SUCH_FIELD(Severity.ERROR, DiagnosticSource.PRECHECK,
            "no field exists at the given path.",
            "pass an existing field's full name-path (see `model describe` or `field read`)."),
    NO_SUCH_GROUP(Severity.ERROR, DiagnosticSource.PRECHECK,
            "no group exists at the given path (a target, parent, or destination).",
            "pass an existing group's full name-path (see `model describe` or `group read`)."),
    NO_SUCH_RULE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "no rule exists at the given full name.",
            "pass an existing rule's full name (group-path + name; see `rule read` or `where-used`)."),
    NO_SUCH_TYPEDEF(Severity.ERROR, DiagnosticSource.PRECHECK,
            "no type definition with the given id is declared on the model.",
            "pass an existing type-definition id (see `typedef read` or `model describe`)."),
    NO_SUCH_COMPUTATION(Severity.ERROR, DiagnosticSource.PRECHECK,
            "no computation exists at the given full name.",
            "pass an existing computation's full name (group-path + name; see `computation read`)."),
    NO_SUCH_INCLUDE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "no model include with the given alias is declared on the model.",
            "pass an existing include alias (see `include read`)."),
    UNRESOLVED_REFERENCE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a model reference (an include or a type-definition import) could not be resolved against the workspace — the referenced model id was not found among the indexed models, or a found model failed to parse. The model cannot be loaded/validated until the reference resolves.",
            "make the referenced model available to this run: on the CLI, pass -w <dir> (or set $DMTOOL_WORKSPACE) pointing at the directory that holds it; over MCP, send its DM-JSON in `references`. Also check the reference matches the target model's header id (see `include read`)."),
    UNDECLARED_ENTITY_REFERENCE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a rule condition or computation operation names an entity (field or group) the model does not declare, so the rule it belongs to cannot exist — the kernel's consistency gate rejects the same model as MVK_INVALID_ENTITY. Runtime evaluation refuses rather than answering, because an undeclared reference has no single failure shape: a presence predicate reads the missing cell as EMPTY and the rule FIRES, a starred aggregate collapses to an empty row domain and the rule PASSES, and a value position has no empty reading at all. Every one of those is a confident observation about a rule that cannot exist.",
            "correct the reference to a declared entity: check the spelling and the path form against `model describe` (a bare name binds inside the rule's own group first, then — only if the model sets fieldRefByShortNameAllowed — to a unique model-wide field short name; `../Name` walks up one group; a leading `/` is absolute). Run `model check` for the kernel's own verdict on the whole model."),
    NO_SUCH_ELEMENT(Severity.ERROR, DiagnosticSource.PRECHECK,
            "no element (field, group, rule, or computation by name-path) or type definition (by id) exists at the given reference.",
            "pass an existing element's full name-path or a declared type-definition id (see `model describe`)."),
    LABEL_NOT_SUPPORTED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the referenced element does not carry a display-label slot; only fields and groups are labelled.",
            "remove --label, or pass a field/group reference; descriptions and annotations are supported on rules, computations, and type definitions."),
    INVALID_CONFIG_VALUE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a document-config value is invalid: a scalar is outside its domain, or a supported-character entry violates the singleton/range/atomic-grapheme definition — the kernel stores these values verbatim, so dmkits pre-checks them.",
            "pass a valid value (a single-character decimal separator; a valid IANA time zone; a declared locale code; or a singleton BMP character, inclusive x-y range, or atomic combined grapheme)."),
    NAME_EXISTS(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the chosen new name/id collides with an existing sibling field/group or declared id.",
            "choose a name not already used by a sibling, or rename/remove the existing one first."),
    INTERPRETER_INCLUDE_RULES_UNSUPPORTED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a surviving rule/computation from an INCLUDED model retains a genuine reference the kernel-free "
                    + "assembly layer cannot map to a moved target (INTERPRETER-FINDINGS IF146). Mount preparation "
                    + "is reference-aware across every rule/computation fragment and preserves literals/comments; "
                    + "the remaining element is refused so evaluation never silently mis-reports.",
            "check every reference in the named included element resolves inside the included model; or "
                    + "author the rule on the INCLUDING model referencing the mounted fields, or use a relative form."),
    MODEL_INPUT_INVALID(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the model DM-JSON could not be read as an A12 model, before a consistency verdict was available.",
            "repair the model JSON at the reported location: complete truncated JSON, remove unknown properties, "
                    + "use the expected value types, and supply required rule/computation references; then retry."),
    MODEL_PREPARATION_FAILED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the portable workspace could not turn the selected model-reference closure into one unambiguous, "
                    + "self-contained model. The diagnostic summary names the exact preparation reason; no partial "
                    + "expanded or evaluated result is usable.",
            "fix the named model source, include/type-definition edge, ambiguity, cycle, language mismatch, or "
                    + "resource-limit failure, then expand or evaluate the complete closure again."),
    INTERPRETER_TIMEZONE_UNSUPPORTED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the model's modelConfig.timeZone is outside the interpreter-backed evaluator's supported set "
                    + "{UTC, GMT, Europe/Berlin}. At the JVM boundary this code is emitted only after the id is "
                    + "known to be legal in the Java TimeZone domain; blank, null, and unknown/typo ids are invalid "
                    + "configuration instead. A kernel-valid model can therefore carry a zone the kernel-free "
                    + "interpreter deliberately does not model — evaluation is refused so it never runs under the "
                    + "wrong DST rules.",
            "set modelConfig.timeZone to UTC, GMT, or Europe/Berlin to evaluate with the interpreter; the "
                    + "kernel-backed 'model check' still validates the wider zone domain."),
    MOUNT_NOT_EDITABLE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the target group mounts an include (it has a modelAlias); adding a local child element would make the kernel use the local elements and STOP expanding the include, silently discarding the mounted content — valid but not what was meant.",
            "add the element to the included model instead (edit that model's structure), or choose a non-mount group."),
    TYPE_MISMATCH(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the fields to factor into a type definition do not all exist and share one concrete type — there is no single type to extract.",
            "pass fields that exist and share the same concrete type (none already a typedef)."),
    REPEATABLE_INCLUDE_ROOT(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the group cannot be an include root — an include's root group must be single and non-repeatable.",
            "extract a non-repeatable group, or make this group non-repeatable first."),
    OUTWARD_REFERENCE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a rule inside the subtree references a field outside it, so the subtree cannot move into a standalone sub-model.",
            "move those rules out of the subtree, or extract a larger group that contains what they reference."),
    MULTISELECT_MIN_ROWS(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a multi-select group must be repeatable — it needs a maximum of at least 2 rows.",
            "pass --max-rows 2 (or more)."),
    UNDECLARED_MESSAGE_LOCALE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the candidate supplies an error message for a locale the model does not declare; the kernel's error-text checker runs per declared locale, so that text would be reported valid without ever being examined.",
            "supply the message for one of the model's declared locales, or declare the locale on the model first."),
    ATTACHMENT_LOCALE_UNSUPPORTED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the attachment-group template generates complete rules (conditions and messages) and can author them only in the kernel's two DSL keyword languages, en_US/de_DE; the model declares another locale.",
            "use a model whose declared locales are en_US and/or de_DE, or build the attachment fields and rules manually."),
    MOVE_INTO_SELF(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a group cannot move into itself or its own subtree — it would orphan the subtree.",
            "choose a destination group that lies outside the moved subtree."),
    REFERENCED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the element is still referenced by a rule/computation; removing it would dangle the referrers.",
            "remove or re-point the referrers first, or pass --cascade to remove them together."),
    UNKNOWN_OP(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the op is not valid for its target in an apply/batch sequence.",
            "use an op valid for the target (see `manifest`)."),
    UNKNOWN_ARG(Severity.ERROR, DiagnosticSource.PRECHECK,
            "an op-record carries a key that is not a parameter of that verb.",
            "use the verb's documented keys (see `manifest` / `schema <target> <op>`); check the did-you-mean."),
    INPUT_UNREADABLE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a JSON input named on the command line could not be read — the file is missing or unreadable, or "
                    + "the stdin it was to come from is empty, already consumed, or absent on this transport.",
            "check the path exists and is readable; if the argument was '-', pipe the JSON in and use '-' at "
                    + "most once per process."),
    // Deliberately NOT folded into INPUT_UNREADABLE: nothing was named, so there is no path to check, and its
    // corrective would send an agent looking at a file it never supplied.
    MODEL_NOT_NAMED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "the verb operates on a document model but no model was named — `-m/--model` was not given.",
            "pass -m <model.json>; it is inherited, so it may come before or after the verb."),
    INVALID_VALUE(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a recognized spec key carries a value of the wrong type or shape — a string where an object/array is required, or a value outside the key's declared set (e.g. required:\"MANDATORY\" instead of true/false/\"ifParentPresent\").",
            "give the value the type the schema declares for this key (see `schema <target> <op>`); a wrong shape is rejected, never silently coerced to a default."),
    WRONG_BATCH_KIND(Severity.ERROR, DiagnosticSource.PRECHECK,
            "a batch entry's shape does not match the kind of call it claims to be.",
            "match the entry to the frame `schema batch` documents."),
    APPLY_OP_FAILED(Severity.ERROR, DiagnosticSource.PRECHECK,
            "an op in an atomic apply sequence failed, so the whole sequence was rolled back (nothing written).",
            "fix the failing op (see its per-op diagnostic) and re-run the sequence."),
    RETYPE_AFFECTS_READERS(Severity.WARNING, DiagnosticSource.PRECHECK,
            "a field re-type changes the field's KIND, and rules/computations read it — operators that assume the old type (e.g. a pattern comparison on a now-enum) can break; an advisory, not a refusal (the kernel re-validate is the hard gate).",
            "review the listed referrers; if the change is rejected, fix or remove the conditions that depend on the old kind."),
    MOVE_CHANGES_GROUP_EXPANSION(Severity.WARNING, DiagnosticSource.PRECHECK,
            "a move takes the element out of an enclosing group that rules/computations read as a GROUP OPERAND — the kernel expands such an operand to every field below the group, so the listed elements silently lose (or gain) a member. There is no reference TEXT to rewrite and the model stays kernel-valid, so nothing downstream reports it; an advisory, not a refusal, because the move is legal and usually intended.",
            "check that each listed rule or computation still means what you intend over its new expansion; if one must keep the element, name the element directly instead of its group, or move it back."),

    // --- A12's codeless model-member sentences, as the portable checker publishes them (ERROR; PORTABLE_CHECKER)
    // A12 refuses these with a sentence and no code; the checker names each sentence so a report can be acted on.
    MEMBER_SIGNED(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values without a sign are allowed.\": a length, a digit count or a group's repeatability is negative. -1 is the one signed value admitted, and only where it means \"no bound\" (a String or Custom length, a fractional count) (KERNEL-FINDINGS KF556, KF559, KF590), where the portable checker names the sentence by this code.",
            "write a count of 1 or more (a fractional count may be 0), or -1 to declare no bound where the member admits it; a Number length admits no -1."),
    MEMBER_NOT_POSITIVE(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values greater than 0 are allowed.\": a length, an integer count or a group's repeatability is 0, where A12 admits 1 and above (KERNEL-FINDINGS KF556, KF559, KF590), where the portable checker names the sentence by this code.",
            "declare at least 1; to leave a String or Custom length unbounded, write -1 rather than 0."),
    MEMBER_ABOVE_TECHNICAL_LIMIT(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values smaller than or equal to 99999 are allowed.\": a String, Custom or Number length is above A12's technical limit of 99,999 (KERNEL-FINDINGS KF556), where the portable checker names the sentence by this code.",
            "declare a length of 99,999 or less, or -1 for no bound on a String or Custom field."),
    MEMBER_ABOVE_DIGIT_BUDGET(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values smaller than or equal to 15 are allowed.\": a Number's integer count, declared or derived for an amount, is above A12's digit budget of 15 (KERNEL-FINDINGS KF559), where the portable checker names the sentence by this code.",
            "declare 15 integer places or fewer; integer and fractional places together are bounded by the same budget."),
    MEMBER_ABOVE_SCALE_LIMIT(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values smaller than or equal to 14 are allowed.\": a Number's fractional count is above A12's scale cap of 14 (KERNEL-FINDINGS KF559), where the portable checker names the sentence by this code.",
            "declare 14 fractional places or fewer."),
    MEMBER_ABOVE_60_CHARACTERS(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values with up to 60 characters are allowed.\": a root group's name is longer than 60 characters, and its alphabet is admitted (an alphabet refusal takes this sentence's place; KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
            "shorten the root group's name to 60 characters or fewer."),

    // --- A12's other codeless model sentences, as the portable checker publishes them (ERROR; PORTABLE_CHECKER)
    BOUND_SIGNED_ZERO(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The value '0' as well as positive values must be provided without a sign.\": a Number's `minValue` or `maxValue` prints as `-0`, A12 rounding it half away from zero to a whole number when no fractional digit is allowed (KERNEL-FINDINGS KF569), and it withholds `" + KernelCode.MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE + "`, where the portable checker names the sentence by this code.",
            "write a bound that keeps its value at the declared fractional digits — 0 itself, or allow a fractional digit so a small negative bound does not round to -0."),
    BOUND_TOO_MANY_DIGITS(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values with up to 15 digits are allowed.\": a Number's `minValue` or `maxValue`, as A12 prints it, has more than 15 digits, a leading `0` counted (KERNEL-FINDINGS KF569), and it withholds `" + KernelCode.MVK_MIN_VALUE_BIGGER_THAN_MAX_VALUE + "`, where the portable checker names the sentence by this code.",
            "write the bound with 15 digits or fewer, counting a leading 0; drop precision the field's fractional digits cannot hold."),
    NUMBER_MIN_VALUE_EXCEEDED(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The minimum value of the number type in field [id: …] is specified with '…'. It may not exceed '…'.\": a Number's `minValue`, by its absolute value and unrounded, is above `10^(15 - k) - 10^(-k)` for `maxFractionalDigits` k, an absent count being 0 (KERNEL-FINDINGS KF582), where the portable checker names the sentence by this code.",
            "bring the minimum's absolute value within 10^(15 - k) - 10^(-k), raising a negative minimum or lowering a positive one, or declare fewer fractional digits to widen that limit."),
    NUMBER_MAX_VALUE_EXCEEDED(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The maximum value of the number type in field [id: …] is specified with '…'. It may not exceed '…'.\": a Number's `maxValue`, signed and unrounded, is above `10^(15 - k) - 10^(-k)` for `maxFractionalDigits` k, an absent count being 0 (KERNEL-FINDINGS KF582), where the portable checker names the sentence by this code.",
            "lower the maximum to 10^(15 - k) - 10^(-k) or less, or declare fewer fractional digits to widen that limit."),
    DATE_RANGE_SEPARATOR_INVALID(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only minus (-) or slash (/) may be used for date range types.\": a DateRange type's `rangeSeparator`, trimmed, is neither empty, `-` nor `/` (KERNEL-FINDINGS KF581), where the portable checker names the sentence by this code.",
            "use `-` or `/` as the range separator."),
    DATE_RANGE_FORMAT_INVALID(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The date range format is invalid.\": a DateRange type composes `format + rangeSeparator + format`, and A12 allowlists that PAIR rather than either half, so a valid format and a valid separator can still be refused together (KERNEL-FINDINGS KF581), where the portable checker names the sentence by this code.",
            "pair the format with the separator A12 admits for it, for example `dd.MM` with `-`, or `yyyy` with `/`."),
    ENUM_TEXT_LINE_BREAK(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"Only values without line breaks are allowed.\": an Enumeration's value, label for a declared locale or category token carries a newline or carriage return inside its Java-trimmed text, a break at either end being admitted. A12 states it once for the field, ahead of the tab and pipe classes, and does not ask those of the text carrying the break (KERNEL-FINDINGS KF418), where the portable checker names the sentence by this code.",
            "remove the line break from the value, label or category token."),
    RULE_NAME_ALPHABET(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"A rule name must start with a letter followed by letters, digits and underscores.\": a rule's or computation's trimmed name is not an ASCII letter followed by ASCII letters, digits and underscores (KERNEL-FINDINGS KF578), and it withholds `" + KernelCode.MVK_RULE_NAME_INVALID + "`, where the portable checker names the sentence by this code.",
            "rename it to an ASCII letter followed by ASCII letters, digits and underscores."),
    ERROR_CODE_ALPHABET(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The error code is only allowed to contain letters, digits, blanks and underscores.\": a rule's trimmed error code, or a computation's name, carries anything else, the letters including the German umlauts and `ß` (KERNEL-FINDINGS KF578), where the portable checker names the sentence by this code.",
            "write the error code, or the computation's name, in letters, digits, spaces and underscores only."),
    ERROR_CODE_EMPTY(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The error code is empty.\": a rule's `errorCode` is absent or empty, drawn beside `" + KernelCode.MVK_ERROR_CODE_MISSING + "`; a blank but non-empty code draws that class alone, and A12 states this sentence before any variant filter (KERNEL-FINDINGS KF579), where the portable checker names the sentence by this code.",
            "give the rule a non-empty `errorCode`."),
    GROUP_PATH_INVALID(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"A group name may only contain letters, numbers or underscores. Group names must begin with a letter or an underscore. Name parts may not be longer than 60 characters …\": a part of the trimmed group path an element lives in is over 60 characters or outside the group alphabet, so A12 states it at that group and at every element beneath (KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
            "rename the group: a nested group name begins with an ASCII letter or `_` and continues in ASCII letters, digits, `_`, the German umlauts and `ß`, at most 60 characters."),
    ROOT_GROUP_NAME_ALPHABET(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"A root group name may only contain letters, numbers and underscores.\": a root group's trimmed name carries a character outside ASCII letters, digits, `_`, the German umlauts and `ß`; a leading digit is admitted (KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
            "rename the root group to ASCII letters, digits, underscores, the German umlauts and `ß`."),
    FIELD_NAME_ALPHABET(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"A field name may only contain letters, digits or underscores. Field names must not begin with a digit.\": a field's trimmed name is not ASCII letters, digits, underscores and colons in dot-separated parts, each part after the first beginning with an ASCII letter. Despite the sentence, a leading digit is admitted (KERNEL-FINDINGS KF580), where the portable checker names the sentence by this code.",
            "rename the field to ASCII letters, digits, underscores and colons, separating parts with a dot and starting each later part with an ASCII letter."),
    SORT_LIST_ABOVE_CAPACITY(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The index for group '/MVK/Model/Groups/SortInfo/SortFields' was specified with 4, but at most 3 is allowed.\": a group's `sortFields` holds more than three entries, and each entry beyond the third draws it once (KERNEL-FINDINGS KF345), where the portable checker names the sentence by this code.",
            "keep at most three `sortFields` entries."),
    UNSUPPORTED_CHARACTER(Severity.ERROR, DiagnosticSource.PORTABLE_CHECKER,
            "A12's sentence \"The field contains one or several unsupported signs.\": a text A12 validates as part of the model, such as a rule's condition, code or message, or an enum value, carries a character outside the Basic Multilingual Plane. Model, group and field labels are not checked (KERNEL-FINDINGS KF489), where the portable checker names the sentence by this code, once per element.",
            "replace the character above U+FFFF, such as an emoji, in that text."),

    // --- environment / version (advisory or fail-fast) ------------------------------------------
    // ENVIRONMENT rather than PRECHECK: every check passed and the work was done — the destination refused it.
    // Calling it a precheck would blame the input; calling it INTERNAL would blame the tool (the mistake the
    // batch observations write failure made, carrying RK_INTERNAL_ERROR beside a fix about choosing a path).
    OUTPUT_UNWRITABLE(Severity.ERROR, DiagnosticSource.ENVIRONMENT,
            "the selected output destination could not be written — a path component is not a directory, the "
                    + "location is not writable, or the filesystem refused. The model itself was fine and, for a "
                    + "single-file destination, nothing was written.",
            "choose a writable destination whose parent is an existing directory and rerun; a multi-file writer "
                    + "reports any files it had already left behind in `data.written` so they can be cleaned up."),
    NO_KERNEL_BACKEND(Severity.INFO, DiagnosticSource.ENVIRONMENT,
            "no kernel backend is on the classpath, so the authoritative consistency/MVK gate did not run — this result reflects the structural prechecks that ran, not a kernel-confirmed verdict (verification=STRUCTURAL_ONLY). ok() means only 'no ERROR among the checks that ran' and must never be read as kernel-green.",
            "add the kernel backend (the CLI bundles it; a library consumer adds the :adapter module) to obtain KERNEL_CONFIRMED validation — typed authoring and structural reads are fully available without it."),
    KERNEL_VERSION_SKEW(Severity.INFO, DiagnosticSource.ENVIRONMENT,
            "the running kernel is newer than the catalog's verified floor — safe (rulekit binds only the public surface), an advisory only.",
            "no action required; re-stamp the catalog `verifiedAgainst` to silence it."),
    MODEL_VERSION_SKEW(Severity.INFO, DiagnosticSource.ENVIRONMENT,
            "the document's modelVersion differs from the reference but is accepted by the kernel — tolerated, flagged for awareness.",
            "no action required; align the modelVersion if you want the note gone."),
    MODEL_VERSION_INCOMPATIBLE(Severity.ERROR, DiagnosticSource.KERNEL,
            "the kernel rejects the document's modelVersion (a newer or different-major version) — fail-fast.",
            "load a model whose version is compatible with this kernel, or migrate the model."),

    // --- internal tool failure (ERROR; INTERNAL) ------------------------------------------------
    INTERNAL_ERROR(Severity.ERROR, DiagnosticSource.INTERNAL,
            "the tool itself failed while executing the command — an unexpected exception/error caught at the boundary (not a rejected input), e.g. a native-image reflection-metadata gap. The result is untrustworthy and nothing was written; the full stack trace is on stderr.",
            "this is a dmtool defect, not an invalid input: report it with the stderr trace; a JVM build (if available) may not have the native gap.");

    private final Severity severity;
    private final DiagnosticSource source;
    private final String meaning;
    private final String fix;

    RkCode(Severity severity, DiagnosticSource source, String meaning, String fix) {
        this.severity = severity;
        this.source = source;
        this.meaning = meaning;
        this.fix = fix;
    }

    /** The wire code — {@code "RK_" + name()} (the constant name is the code minus the prefix). */
    public String code() {
        return "RK_" + name();
    }

    /** The canonical severity for the catalog (a specific emit may differ; the wire carries the emitted value). */
    public Severity severity() {
        return severity;
    }

    /**
     * Whether this advisory PREDICTS a kernel refusal, as opposed to advising on A12 evaluation semantics.
     *
     * <p>The distinction decides what a firing on kernel-LEGAL input means, and the two answers are opposites.
     * For a prediction it is a conformance defect: the advisory says the kernel rejects the shape and the
     * kernel does not. For the rest it is the advisory doing its job — {@code UNGUARDED_NUMBER_COMPARISON}
     * exists precisely to warn that an empty number reads as 0, which the kernel accepts and the author may
     * not have intended.
     *
     * <p>Recorded because the difference was invisible and got measured as if it were uniform: an external
     * conformance pass counted every advisory firing on legal input as a miss, when only the predictions
     * qualify. {@code NEG_CONDITION_IN_ITERATION} is the one that mattered — and its declared text used to
     * read as intent advice while the text it EMITTED claimed the kernel rejects, which is the drift
     * {@code RkCode} classification now holds shut.
     */
    public boolean predictsKernelRefusal() {
        return this == NEG_CONDITION_IN_ITERATION || this == COMPUTATION_FORBIDDEN_CONSTRUCT;
    }

    /** The canonical source for the catalog. */
    public DiagnosticSource source() {
        return source;
    }

    /** What the code means (the catalog entry an agent/dev reads). */
    public String meaning() {
        return meaning;
    }

    /** The general repair guidance (a specific instance may carry a more concrete fix). */
    public String fix() {
        return fix;
    }

    /** The {@link RkCode} for a wire code string ({@code "RK_NO_SUCH_FIELD"}), or empty if not an RK_ code. */
    public static Optional<RkCode> byCode(String code) {
        if (code == null || !code.startsWith("RK_")) {
            return Optional.empty();
        }
        String name = code.substring(3);
        for (RkCode c : values()) {
            if (c.name().equals(name)) {
                return Optional.of(c);
            }
        }
        return Optional.empty();
    }
}
