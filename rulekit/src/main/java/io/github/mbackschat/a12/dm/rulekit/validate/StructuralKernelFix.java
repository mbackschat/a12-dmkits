package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Map;
import java.util.Optional;

import io.github.mbackschat.a12.dm.core.KernelCode;

/**
 * Curated {@code fix}/{@code explain} for kernel codes the operator catalog-join can't correctly serve.
 * {@link DiagnosticEnricher} consults this <b>first</b>, so these win over the join. Two kinds live here —
 * and note the kind that is <em>not</em>: a <b>cross-operator</b> code documented by several operators with
 * differing fixes is now handled soundly by the join itself (it attaches the <em>union</em>), so it does
 * NOT need an entry here. What does:
 * <ol>
 *   <li><b>ORPHAN codes</b> — <em>no</em> operator's {@code invalidExample} documents them, so the join has
 *       nothing to attach: the placement / iteration-scope family ({@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP},
 *       {@code MVK_ERROR_FIELD_NOT_REFERENCED}, {@code MVK_NEG_CONDITION_IN_ITERATION},
 *       {@code MVK_REPEATABLE_GROUP_MISSING}, {@code MVK_NOT_IN_SAME_PATH}), the bare <b>syntax</b> code the parser raises with no message
 *       of its own ({@code MVK_UNEXPECTED_TOKEN} — the likely cause, a lowercase boolean / unquoted string /
 *       unbracketed field operand, must be inferred from the DSL's literal conventions), the
 *       <b>entity-resolution</b> code ({@code MVK_INVALID_ENTITY} — a bare/relative name that doesn't bind;
 *       the fix is the absolute path), and the <b>enum locale-coverage</b> code
 *       ({@code MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES} — an enum value labeled in some declared locales but
 *       not all, whose opaque "display value and XML value not specified together" message hides the gap),
 *       and the <b>field-declaration</b> pair, where a DateType's config disagrees with its declared format
 *       ({@code MVK_ADDITIONAL_CHECK_INVALID}, {@code MVK_OPTIONAL_DATE_TYPE_INVALID} — the two gates read the
 *       format differently, so neither message says which formats would work, and the second echoes the
 *       offending one back in German pattern letters).</li>
 *   <li><b>CROSS-CONTEXT codes</b> — documented by operator(s), but ALSO fired by a construct the catalog
 *       cannot describe, so the documenter's fix is <em>wrong</em> there (probe findings F06-2 / dec-places):
 *       {@code MVK_NO_WILDCARD} (CurrentRepetition documents it, but a non-iterating aggregate / computation
 *       raises it too — the per-repetition "put the error field inside Items" fix mis-stamps the aggregate
 *       case), {@code MVK_NO_GROUPS_ALLOWED} (FieldFilled documents it as "you passed a group, use
 *       GroupFilled", but an AGGREGATE / {@code Having} filter given a bare group wildcard raises it too —
 *       there the fix is to flatten to a repetition field list, not to swap in GroupFilled) and
 *       {@code MVK_INVALID_COMPARE_DEC_PLACES} (Equal/NotEqual document it for incompatible numeric
 *       {@code ==}/{@code !=} operands, but a computed field whose scale differs from its expression raises
 *       it too — the
 *       "round one with RoundDown" comparison fix mis-stamps the computation case, where the fix is to
 *       match the computed field's scale; and since 2026-09-07 a third trigger, the POWER's derived scale,
 *       which is unknown for every shape but a scale-0 base under a nonnegative literal exponent
 *       (KERNEL-FINDINGS KF269) — that context is what makes an author's own operands read as "?"). The
 *       catalog can only see operators, never these other triggers, so the corrective must be authored here
 *       at the code level.</li>
 * </ol>
 *
 * <p>Hand-authored guidance, like the {@code RK_*} meanings in {@link RkCode} — but locked the same
 * way the catalog fixes are: a test triggers the real kernel code and asserts the fix attaches
 * (e.g. {@code RuleValidatorTest.attachesAPlacementFixToTheNotInRuleGroupCode}; the cross-context
 * {@code DEC_PLACES} two-context hint by {@code DiagnosticEnricherTest}; the {@code NO_GROUPS_ALLOWED}
 * aggregate/Having corrective by {@code validate.laws.GroupWildcardAggregateLawsTest}), so the text can't
 * drift away from the behavior it explains. The placement law itself is locked in
 * {@code validate.laws.IterationLawsTest} (KERNEL-FINDINGS §9). The complementary cross-OPERATOR
 * soundness — that the join never silently drops a corrective, so no such code needs an entry here — is
 * guarded by {@code DiagnosticEnricherTest.noCrossOperatorCodeSilentlyDropsACorrective}.
 *
 * <p>Public because it is also the single source for the {@code diagnostics <code>} lookup of these
 * codes — a probe (P-mini-1) found an agent that hit one of them naturally tries {@code diagnostics
 * <code>}, which used to dead-end ("see operators/explain"): an ORPHAN code has no operator home, and a
 * CROSS-CONTEXT code's operator home would give the wrong fix. The CLI resolves both through
 * {@link #forCode} instead.
 */
public final class StructuralKernelFix {

    /** A corrective {@code (fix, explain)} pair for one structural kernel code. */
    public record Hint(String fix, String explain) {
    }

    private static final Map<KernelCode, Hint> HINTS = Map.ofEntries(
            Map.entry(KernelCode.MVK_INVALID_PATTERN, new Hint(
                    "Use a Java-valid regular expression that also avoids the kernel 30.8.x admission restrictions: "
                            + "possessive quantifiers, lookbehind/named-capture `(?<…`, atomic `(?>…)`, "
                            + "the A12K-30 escapes `\\A \\G \\Z \\z \\a \\e \\p \\Q \\E` (lowercase `\\p` only — "
                            + "uppercase `\\P` is admitted), and nested unescaped `[` class syntax. The gate reads "
                            + "the pattern's SOURCE TEXT, so escaping buys no exemption: `a\\*+` is refused for "
                            + "spelling `*` before `+`, though the pattern it means is ordinary. Passing this gate "
                            + "does NOT prove JavaScript portability.",
                    "The kernel first requires Java Pattern syntax, then applies an additional textual admission "
                            + "gate. MVK_INVALID_PATTERN can therefore mean invalid Java syntax or a Java-valid "
                            + "kernel-rejected form; it is not a general cross-runtime portability check. A "
                            + "DECLARED field pattern raises MVK_PATTERN_INVALID instead — the same two words "
                            + "swapped, and a different code.")),
            Map.entry(KernelCode.MVK_PATTERN_INVALID, new Hint(
                    "A DECLARED field pattern (`StringType.pattern`, and the same slot on a type definition) "
                            + "goes through the identical gate as a condition's pattern literal: valid Java "
                            + "Pattern syntax, then the kernel's A12K-30 admission blacklist read on the "
                            + "pattern's SOURCE TEXT — possessive quantifiers, lookbehind/named-capture "
                            + "`(?<…`, atomic `(?>…)`, the escapes `\\A \\G \\Z \\z \\a \\e \\p \\Q \\E` "
                            + "(lowercase `\\p` only), and nested unescaped `[`. Because it reads text, "
                            + "escaping buys no exemption: `a\\*+` is refused for spelling `*` before `+`.",
                    "The DECLARED-field twin of MVK_INVALID_PATTERN, and a DIFFERENT code — the same two "
                            + "words swapped, so a consumer keying on the condition's code sees nothing "
                            + "here. Like it, it can mean invalid Java syntax or a Java-valid form the "
                            + "admission gate refuses, and passing it does not prove JavaScript "
                            + "portability.")),
            Map.entry(KernelCode.MVK_ADDITIONAL_CHECK_INVALID, new Hint(
                    "A DATE or DATEFRAGMENT field's opt-in pre-1900 check (`youngerThan1900Check`, our "
                            + "`dateField(…)`/`dateFragmentField(…).rejectBefore1900()`) requires the declared "
                            + "FORMAT to carry a YEAR. Over the kernel's admitted format vocabulary it is "
                            + "accepted on `yyyy`, `yyyy-MM`, `yyyyMM`, `yyyy-MM-dd`, `dd.MM.yyyy`, `yyyyMMdd` "
                            + "and `yyyy-MM-dd'T'HH:mm:ss`, and refused on the year-free `MM`, `MM-dd`, `MMdd`, "
                            + "`ddMM`, `dd.MM.` and `HH:mm:ss` — identically on both kinds. So either declare a "
                            + "year-bearing format or drop the check — it is not admitted-and-inert on a "
                            + "year-free declaration, so there is no way to keep both. An INCOMPLETE format is not "
                            + "the trigger: `yyyy` and `yyyy-MM` carry no day and are accepted. A DATERANGE is "
                            + "EXEMPT from this gate — every admitted (format, separator) pair takes the check, "
                            + "the year-free ones included — so if a year-free period is what you need, declare "
                            + "it as a range.",
                    "The kernel gates the additional date check on the declared format's YEAR component rather "
                            + "than on the field's kind — except that the DATERANGE kind is not gated at all — so "
                            + "the code means the format and the check disagree, never that the check is "
                            + "unsupported for this field type.")),
            Map.entry(KernelCode.MVK_OPTIONAL_DATE_TYPE_INVALID, new Hint(
                    "A partially known date (`datePrecision` DAY_OPTIONAL / MONTH_OPTIONAL / YEAR_OPTIONAL) "
                            + "requires the declared FORMAT to be a COMPLETE date and nothing further. Over the "
                            + "kernel's admitted format vocabulary that is exactly `yyyy-MM-dd`, `dd.MM.yyyy` and "
                            + "`yyyyMMdd`; every component-omitting format is refused, and so is the instant "
                            + "`yyyy-MM-dd'T'HH:mm:ss` despite carrying all three date components. Declare one of "
                            + "those three, or leave the precision at its FULL default — the precision is what "
                            + "lets the stored VALUE omit parts (`00.02.2024`), so the format must still be able "
                            + "to spell all of them. Do NOT copy the format out of this rejection: it echoes the "
                            + "spelling in GERMAN pattern letters (`JJJJ-MM` for `yyyy-MM`, `MM-TT` for `MM-dd`) "
                            + "in every locale, and those are not declarable.",
                    "The kernel requires a complete date format before it will accept a partial-precision marker, "
                            + "and reports the conflict against the format rather than against the precision — the "
                            + "verdict is the same for all three partial values, so the code identifies the "
                            + "mismatch and not which side to edit.")),
            Map.entry(KernelCode.MVK_INVALID_COMPARE_TO_DATE, new Hint(
                    "Check the LITERAL before the field. A double-quoted literal whose CONTENT parses as a date "
                            + "is a TEMPORAL constant — `\"05.03.2024\"` is one — and it reports this date class "
                            + "against whatever it meets, including a String, Enumeration or Number field with "
                            + "nothing temporal about it. So the code does NOT imply either side is a date. If "
                            + "the field really is textual, stop the literal looking like a date rather than "
                            + "changing the field.",
                    "This is the kernel's COMPARISON vocabulary, not an assignment-specific class: it fires the "
                            + "same way on a plain rule condition, inside a Having filter, and on a computation's "
                            + "constant assignment. Which class a wrong-kind constant draws is an ordered ladder "
                            + "over the (constant, field) PAIR rather than a function of the field: a BOOLEAN "
                            + "field reports MVK_INVALID_COMPARE_TO_YESNO and a CONFIRM one "
                            + "MVK_INVALID_COMPARE_TO_YES for any constant at all; failing those, a temporal "
                            + "constant reports THIS code against every remaining kind; only then does the "
                            + "field's own kind decide. Measured in KERNEL-FINDINGS entry KF257, which carries "
                            + "the ladder and its controls.")),
            Map.entry(KernelCode.MVK_NO_WILDCARD, new Hint(
                    "A repeatable group is referenced from a rule that does not iterate over it — the fix is "
                            + "operator-dependent. For an AGGREGATE, flatten the repetitions with the `*` wildcard: "
                            + "Sum(Lines*/Amount), NumberOfFilledFields(Lines*/Sku), NumberOfFilledGroups(Lines*). "
                            + "For a PER-REPETITION predicate (RepetitionNotUnique / CurrentRepetition), make the rule "
                            + "iterate by putting its group at the PARENT of the repeated group with the error field "
                            + "inside it — via the CLI, `rule check --group <parentGroup> --field <parentGroup>/<repeatedGroup>/<field>` "
                            + "(e.g. --group /Order --field /Order/Items/Sku for RepetitionNotUnique(Items/Sku)).",
                    "MVK_NO_WILDCARD fires when a repeatable group is referenced from a non-iterating scope: the kernel "
                            + "expected either a flattening `*` (aggregates) or a rule that iterates over that group "
                            + "(per-repetition predicates). The required form depends on the operator, so this code is "
                            + "not specific to any single one.")),
            Map.entry(KernelCode.MVK_NO_GROUPS_ALLOWED, new Hint(
                    "A GROUP was supplied where a FIELD (or a flattened field list) is required — the fix depends "
                            + "on the construct. For an AGGREGATE or a `Having` filter, the operand is a FLATTENED "
                            + "repetition list (the repeatable group starred, then the field: `/Order/Lines*/Amount`), "
                            + "NOT a bare group wildcard (`Lines*`): write Sum(Lines*/Amount) or "
                            + "Sum(Lines*/Amount Having <filter>); to count the group's rows instead use "
                            + "NumberOfFilledGroups(Lines*). For a FIELD-PRESENCE predicate, FieldFilled / FieldNotFilled "
                            + "take a field — for a group's presence use GroupFilled / GroupNotFilled.",
                    "MVK_NO_GROUPS_ALLOWED fires when a group is supplied where the construct requires a field or a "
                            + "flattened field list: an aggregate / Having operand must flatten the repetitions to a "
                            + "starred-group-then-field list (a bare group wildcard is not a value list), and a "
                            + "field-presence predicate (FieldFilled) must name a field, not a group. The required form "
                            + "depends on the operator, so this code is not specific to any single one.")),
            Map.entry(KernelCode.MVK_REPEATABLE_GROUP_MISSING, new Hint(
                    "Check BOTH causes — this one code covers them and does not say which applies. (1) The rule sits "
                            + "INSIDE the repeated group: place it at the PARENT while keeping its error field inside "
                            + "that group — via the CLI, `rule check --group <parentGroup> "
                            + "--field <parentGroup>/<repeatedGroup>/<field>`, for example `--group /Order "
                            + "--field /Order/Items/Sku` with `RepetitionNotUnique(Items/Sku)`. (2) A key names a "
                            + "field whose group is not repeatable at all — whether it is the sole key or sits beside "
                            + "a good one; moving the rule cannot fix that, so drop the key or make its group "
                            + "repeatable.",
                    "Every RepetitionNotUnique key must cross a repeatable-group boundary relative to the rule group. "
                            + "Declaring the rule inside the repeated row removes that boundary, and a key in a "
                            + "non-repeatable group never had one; the kernel reports both as this single class, so "
                            + "the code identifies the missing boundary and not which edit restores it.")),
            Map.entry(KernelCode.MVK_PARAMSIZE_INVALIDGN, new Hint(
                    "If the sole operand is a REPEATABLE group, star it — NumberOfFilledGroups(Items*) — rather than "
                            + "adding a second group, which the kernel's own message suggests but which can then draw "
                            + "MVK_NEG_CONDITION_IN_ITERATION inside an iterating rule. Add a second distinct, "
                            + "non-overlapping group only when the operands are NON-repeatable groups you genuinely "
                            + "want counted together.",
                    "The count admits either two or more distinct groups or a single STARRED repeatable group, so "
                            + "\"there must be more than one group\" names only one of the two ways out. Which code a "
                            + "sole unstarred repeatable operand draws depends on the rule's anchoring: "
                            + "MVK_NO_WILDCARD when the error field sits outside that group, this code when it sits "
                            + "inside — and the star satisfies both.")),
            Map.entry(KernelCode.MVK_NOT_IN_SAME_PATH, new Hint(
                    "Keep every RepetitionNotUnique key on one repetition path. Do not mix fields from sibling or "
                            + "parallel repeatable groups in one key; choose one path, or enforce the independent "
                            + "uniqueness constraints with separate rules.",
                    "A composite uniqueness key needs one shared repetition lineage so the kernel can form one row "
                            + "tuple. Fields from parallel repeatable groups do not identify a single correlated row.")),
            Map.entry(KernelCode.MVK_ERROR_FIELD_NOT_IN_RULEGROUP, new Hint(
                    "The declaration ITERATES and writes outside its own group, so there are three repairs and "
                            + "the first is usually the smallest: DECLARE it in a group that contains the field it "
                            + "writes — an ancestor will do, containment is what the kernel asks for, not membership "
                            + "of the repeating group. Or write into a field under the declaring group. Or stop "
                            + "iterating: a STAR aggregate that stars EVERY repeatable level it crosses "
                            + "(Sum(Group*/Field), Sum(Outer*/Inner*/Field)) reads no row, so it lifts the demand "
                            + "entirely, where any level left unstarred is what creates it.",
                    "A declaration that iterates has to say which repetition its result belongs to, and the kernel "
                            + "reads that from PATH CONTAINMENT: the written field must lie at or below the declaring "
                            + "group. It gates both constructs, but they differ in what derives the iteration — an "
                            + "unstarred per-row read does so for either, while a per-row COMPUTED field derives it "
                            + "with no operand at all and a per-row ERROR field does not (measured 2026-09-15, "
                            + "KERNEL-FINDINGS KF212). Crossing a root boundary is not itself a refusal.")),
            Map.entry(KernelCode.MVK_ERROR_FIELD_NOT_REFERENCED, new Hint(
                    "Name the rule's error field somewhere in the condition. Most often this is a model-level "
                            + "aggregate rule whose condition only references the aggregated per-row path — reference "
                            + "the error field by comparing the aggregate AGAINST it (`[FeeCap] < Sum(Lines*/Amount)` "
                            + "references FeeCap) or by guarding with `FieldFilled(<the error field>) And …`.",
                    "Every rule's error field must appear among the fields its condition references — the kernel "
                            + "attaches the fired error to that field and rejects a condition that never names it. An "
                            + "aggregate's starred path does not reference the (non-repeatable) error field, so "
                            + "model-level aggregate rules hit this unless the error field is referenced explicitly.")),
            Map.entry(KernelCode.MVK_NEG_CONDITION_IN_ITERATION, new Hint(
                    "Guard the negative predicate with a positive existence check on the row: "
                            + "GroupFilled(<the repeatable group>) And <your negative condition> — so the rule fires "
                            + "only for rows that actually exist, not for the empty/absent repetitions. A negative "
                            + "presence predicate used as an iterating Or branch is not guarded by its sibling; rewrite "
                            + "that branch as a positively guarded And, or split it into a separate guarded rule.",
                    "A condition the kernel reads as able to fire on the empty/non-existent repetitions of the "
                            + "iterated group needs a positive guard to scope it to real rows. The trigger is NOT the "
                            + "operator's name: FieldsNotCollectivelyFilled is negative and accepted, while "
                            + "NumberOfFilledFields(<group>) < 1 has no negation and is refused. Nor is it the "
                            + "condition's meaning: '< 1' is refused where the equivalent '<= 0' and '== 0' are "
                            + "accepted. The analysis is syntactic and kernel-owned, so add the guard rather than "
                            + "predicting the gate. This is a structural iteration law, not a restriction "
                            + "on RepetitionNotUnique composing as an ordinary leaf under a legal And/Or tree.")),
            Map.entry(KernelCode.MVK_INVALID_COMBINATION_OF_REPETITON_NOT_UNIQUE, new Hint(
                    "Keep exactly one RepetitionNotUnique leaf in the condition. Put every component of the "
                            + "composite repetition key into that one call, then combine that leaf with ordinary "
                            + "conditions as needed.",
                    "The kernel's structural model gate permits at most one RepetitionNotUnique node in one "
                            + "condition tree. The restriction counts nodes, not key fields and not And/Or nesting; "
                            + "one leaf may carry a composite key and otherwise composes normally.")),
            Map.entry(KernelCode.MVK_UNEXPECTED_TOKEN, new Hint(
                    "The condition has a token the DSL parser did not expect (a syntax slip the kernel reports "
                            + "with no message of its own). The most common causes, in order: (1) a BOOLEAN literal "
                            + "must be CAPITALIZED — write `True` / `False`, not the JSON `true` / `false` "
                            + "(e.g. `[Flag] == True`); (2) a string value must be DOUBLE-QUOTED — `[Status] == \"OPEN\"`; "
                            + "(3) operator/keyword names are case-sensitive PascalCase — `FieldFilled`, `And`, `Or`, "
                            + "`Not`; (4) a FIELD used as a comparison VALUE must be BRACKETED — `[Approved] != True`, "
                            + "not `Approved != True` (bare names belong only inside a function/predicate's parentheses, "
                            + "like `FieldFilled(Approved)`). Re-check the token at the reported position against these.",
                    "MVK_UNEXPECTED_TOKEN is the kernel's generic parse error — a token appeared where the grammar "
                            + "did not allow it — and it is raised WITHOUT a message, so the likely cause has to be "
                            + "inferred from the DSL's literal/keyword conventions (capitalized booleans True/False, "
                            + "double-quoted strings, case-sensitive PascalCase operators).")),
            Map.entry(KernelCode.MVK_INVALID_ENTITY, new Hint(
                    "The group or field name in the condition could not be resolved in the rule's scope. Use its "
                            + "ABSOLUTE path (a leading `/`, e.g. `/Order/Items/Sku`) rather than a bare or relative "
                            + "name — a bare name binds against the rule's OWN group first, and reaches ANY other "
                            + "group (an ancestor's field included: there is no upward search) only through the "
                            + "model-wide fallback, which needs `fieldRefByShortNameAllowed` AND a unique match. "
                            + "If the name is already correct, check "
                            + "the rule's iteration scope contains it: a per-row field needs the rule to iterate over "
                            + "its repeatable group (set the rule group to that group's PARENT, error field inside).",
                    "MVK_INVALID_ENTITY (\"the group or field 'X' is not valid\") means the kernel could not resolve "
                            + "that name — almost always a bare/relative reference that does not bind across sibling "
                            + "groups, or a field outside the rule's iteration scope.")),
            Map.entry(KernelCode.MVK_FIELDNAME_NOT_UNIQUE, new Hint(
                    "The bare name resolved through the model-wide fallback, which found MORE THAN ONE field "
                            + "with that short name and will not choose between them. Qualify the reference "
                            + "instead of declaring anything: give it the ABSOLUTE path of the field you mean "
                            + "(a leading `/`, e.g. `/Order/Warehouse/Code`). This is the OPPOSITE corrective "
                            + "to MVK_INVALID_ENTITY, which means no field carries the name at all — there the "
                            + "field is missing, here there are too many, and turning "
                            + "`fieldRefByShortNameAllowed` off converts this code into that one rather than "
                            + "fixing the reference.",
                    "MVK_FIELDNAME_NOT_UNIQUE means the rule's own group does not declare the name, so the "
                            + "kernel fell back to a model-wide lookup (which `fieldRefByShortNameAllowed` "
                            + "enables), and that lookup requires a UNIQUE match.")),
            Map.entry(KernelCode.MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES, new Hint(
                    "An enum's display labels are STARTED and not finished: top each value's `labels` up so every "
                            + "value carries a NONBLANK text for every declared locale, or remove the label entries "
                            + "altogether — an unlabelled enum is accepted. Blanking a text does not remove it. This "
                            + "most often surfaces right after adding a locale (`config modify --add-locale`): do the "
                            + "`--add-locale` and the per-value label top-ups in ONE `apply` so the single terminal "
                            + "gate sees a consistent model.",
                    "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES (\"the display value and the XML value were not specified "
                            + "together\") is armed by the PRESENCE of a label entry for a declared locale, not by "
                            + "coverage: once any value carries one, every value owes a nonblank text for every "
                            + "declared locale. Labels written only for an UNDECLARED locale are accepted, and every "
                            + "text blank is refused, so neither \"is the locale covered\" nor \"is any label there\" "
                            + "describes it (measured 2026-09-13, KERNEL-FINDINGS KF321). A value that carries no "
                            + "`labels` at all while a sibling does is a different code, MVK_COUNT_OF_VALUES_INVALID.")),
            Map.entry(KernelCode.MVK_INVALID_TIME_ZONE, new Hint(
                    "Set modelConfig.timeZone to a nonblank Java time-zone id. Exact UTC, GMT, and Europe/Berlin "
                            + "are accepted by both the kernel and the interpreter-backed evaluator; another known "
                            + "Java id such as America/New_York is kernel-valid but the interpreter will refuse it. "
                            + "Omit the slot to use the UTC default.",
                    "The kernel resolves modelConfig.timeZone through java.util.TimeZone and rejects an explicit "
                            + "null, blank, or unknown/typo id that silently collapses to GMT. Absence is different: "
                            + "only a missing slot selects the UTC default.")),
            Map.entry(KernelCode.MVK_INVALID_RAW_TYPE, new Hint(
                    "The condition (or computation) reads the VALUE of a field that declares noValueValidation "
                            + "(a raw-type string) — the kernel forbids every value use of such a field: comparisons, "
                            + "value lists, computations, even $field.value$ in a message. Test PRESENCE instead "
                            + "(FieldFilled / FieldNotFilled and the quantifiers work normally), or drop "
                            + "noValueValidation from the field (`field modify`) if its value must participate.",
                    "MVK_INVALID_RAW_TYPE (\"It is not allowed to use a field which defines 'no value validation'\"): "
                            + "a noValueValidation field's value is never interned for evaluation — the kernel caches a "
                            + "pseudo value — so no rule or computation can read it. The only value-shaped construct it "
                            + "permits is the whole-condition `Length(f) > <constant>`, and that is a disguised "
                            + "max-length declaration, not an evaluated rule.")),
            Map.entry(KernelCode.MVK_INVALID_LENGTH_OF_RAW_TYPE, new Hint(
                    "Length over a noValueValidation (raw-type) field is allowed ONLY as the WHOLE rule condition "
                            + "`Length(f) > <constant>` (or the mirrored `<constant> < Length(f)`), where the constant "
                            + "is a decimal-point-free signed-32-bit integer. Use no And/Or around it, strict > only "
                            + "(no >=, ==, <=), and never inside a computation. Know what you author: "
                            + "that one shape is a MAX-LENGTH DECLARATION the kernel lifts into the field's metadata and "
                            + "ELIMINATES as a rule — it never fires at runtime, so do not expect a validation message "
                            + "from it.",
                    "MVK_INVALID_LENGTH_OF_RAW_TYPE (\"conditions using the length of a field which defines 'no value "
                            + "validation' must have following form: 'Length(<field>) > <constant>'\"): the kernel "
                            + "accepts exactly that comparison as a meta-length restraint (codegen removes the rule and "
                            + "tightens the generated max length); every other Length use of a raw-type field is "
                            + "rejected because its value is never available to evaluation.")),
            Map.entry(KernelCode.MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD, new Hint(
                    "Drop the PROJECTION from the read of the computed field, not just the reference: a computation "
                            + "may not read its own computed field through an enum category (`[Target -> Cat]`) or a "
                            + "semantic index (`[Group/Target For \"key\"]`), in an operation OR a precondition. "
                            + "Compute from a DIFFERENT field's projection instead (`[Source -> Cat]` is accepted, "
                            + "including as a FirstFilledValue entry), or read the same indexed field into a different "
                            + "target. Removing only the projection still leaves the plain self-reference "
                            + "(MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD), so the operand has to change, not its form.",
                    "MVK_ERROR_SEMANTIC_INDEX_OR_CATEGORY_FOR_ERRORFIELD (\"the calculated field must not specify a "
                            + "semantic index or a category access\") is the PROJECTED half of the "
                            + "calculated-field-reference ban, and it pre-empts the plain half when both readings occur "
                            + "in one operation — so this code identifies the reading MODE, while the plain code is what "
                            + "an unprojected self-read reports (kernel-measured, KF182).")),
            Map.entry(KernelCode.MVK_INVALID_COMPARE_DEC_PLACES, new Hint(
                    "The kernel could not establish compatible DERIVED decimal scales here. Numeric == / != carries "
                            + "a known SIGNED scale or unknown plus an independent multiplicative-constant capability: "
                            + "when known scales differ, only a capable SMALLER-scale side may be padded; an unknown "
                            + "scale rejects. ORDERING (< <= > >=) is exempt. THREE contexts trigger this code: "
                            + "(1) a COMPUTED FIELD whose declared scale is incompatible with its expression "
                            + "(e.g. a field re-scaled to 2 places while the Sum-computed field stays scale 0) — "
                            + "re-scale the computed field or explicitly round the expression, doing both edits in ONE "
                            + "`apply` so the terminal gate sees a consistent model; expect a SECOND rejection to "
                            + "surface if the operation also reads its own computed field, because this scale "
                            + "comparison is decided ahead of that refusal; (2) numeric == / != with "
                            + "incompatible derived scales — round one side to a known compatible scale, or accept it with "
                            + "allowDifferingDecimals:true on the rule spec (the kernel's one suppressible warning — it "
                            + "prepends @SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)); (3) a POWER, whose scale is "
                            + "DERIVED and is reported as '?' more often than authors expect — it is known (and 0) only "
                            + "for a scale-0 base under a nonnegative literal exponent, never the base's own scale, so a "
                            + "decimal-scale base, a non-literal exponent such as a field or aggregate, and a negative "
                            + "literal exponent all leave it unknown; ordering the power instead of comparing it for "
                            + "equality is usually the smallest repair.",
                    "MVK_INVALID_COMPARE_DEC_PLACES (\"the fractional digits of lhs and rhs are different\") fires "
                            + "when numeric == / != cannot reconcile the operands' derived signed-scale/capability "
                            + "summaries, OR when a computed field is incompatible with its expression. It is not "
                            + "specific to any one operator (Sum, Equal, …); the scale-coupling is the constraint, so the "
                            + "corrective is context-dependent.")));

    private StructuralKernelFix() {
    }

    /** The curated hint for {@code code}, when it is a known structural kernel code. */
    public static Optional<Hint> forCode(String code) {
        return KernelCode.of(code).map(HINTS::get);
    }

    /**
     * Every code {@link #forCode} serves, sorted.
     *
     * <p>Exists because a lookup-only surface cannot be PROJECTED. The Kernel-free portable checker carries
     * no project dependency and so cannot read this class at build or run time; its guidance is therefore a
     * generated projection, and a generator needs the denominator rather than a way to ask about one code at
     * a time. Deliberately not the {@link #forMessage} class, which is keyed by message substring and has no
     * code to project onto.
     */
    public static java.util.Set<String> codes() {
        return HINTS.keySet().stream().map(KernelCode::name)
                .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
    }

    /**
     * A hint matched by a kernel MESSAGE substring — for content-free rejections the kernel raises with a
     * message but <b>no MVK_ code</b> (so {@link #forCode} can't reach them). Today: the date/time field
     * <em>format</em> rejection (probe 08 — "The date range format is invalid" / "The date format is
     * invalid"), which named what's wrong but not the valid forms. Best-effort English-message match (the
     * kernel localizes the message to the model's display locale; a non-English message simply gets no hint).
     */
    public static Optional<Hint> forMessage(String message) {
        if (message == null) {
            return Optional.empty();
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("date range format is invalid") || m.contains("date format is invalid")) {
            return Optional.of(new Hint(
                    "A date/time field FORMAT must be a CASE-SENSITIVE Java pattern from the kernel's fixed allowed "
                            + "set — case is not merely lowercase, since `MM` means months where `mm` means minutes, "
                            + "so `yyyy-mm-dd` is a different pattern rather than a miscased one, and neither it nor "
                            + "the editor's UPPERCASE display label `YYYY-MM-DD` is accepted. "
                            + "Derived from the kernel, THIRTEEN are admitted and each on any of DATE/TIME/DATETIME: "
                            + "the complete dates `yyyy-MM-dd`, `dd.MM.yyyy`, `yyyyMMdd`; the component-omitting "
                            + "`yyyy`, `yyyy-MM`, `yyyyMM`, `MM`, `MM-dd`, `MMdd`, `ddMM`, `dd.MM.`; the clock `HH:mm:ss`; "
                            + "and the instant `yyyy-MM-dd'T'HH:mm:ss` (a whitelist, not a compositional rule — "
                            + "`yyyy/MM`, `MM.yyyy`, `MM-yyyy` and `dd.MM` are refused, so the separator decides "
                            + "between same-component spellings, and a TRAILING one counts — `dd.MM.` with the closing dot IS "
                            + "admitted where `dd.MM` is not); DATERANGE the FORMAT "
                            + "AND SEPARATOR are allowlisted as a PAIR, "
                            + "and exactly EIGHT are admitted — `dd.MM.yyyy`+`-`, `yyyy-MM-dd`+`/`, `yyyy`+`/`, "
                            + "`yyyy-MM`+`/`, `MM`+`/`, `MM-dd`+`/`, `MM`+`` (the empty separator, which halves "
                            + "the stored value), and `dd.MM`+`-`; swapping the separator is always refused "
                            + "(`MM`+`-`, `dd.MM.yyyy`+`/`), no format takes `.`, `yyyyMM` is a legal DATE "
                            + "format but not a legal DATERANGE one, and `dd.MM` is the reverse; and DATEFRAGMENT "
                            + "admits the same thirteen, measured as its own domain rather than inherited. The "
                            + "per-kind conventions are DEFAULTS, never restrictions — TIME `HH:mm:ss` (never "
                            + "`HH:mm`), DATEFRAGMENT `MM`/`yyyy`/`yyyy-MM`/`MM-dd`, and DATE_TIME the ISO "
                            + "instant `yyyy-MM-dd'T'HH:mm:ss`, though the degenerate time-only `HH:mm:ss` is "
                            + "just as legal there: outside DATERANGE the gate reads the STRING and never the "
                            + "kind, so a cross-kind spelling is admitted. See `schema field add`.",
                    "The kernel validates a date/time field's declared format against a fixed allowed set (the same "
                            + "regardless of locale) and rejects anything else with a message but no MVK_ code."));
        }
        return Optional.empty();
    }
}
