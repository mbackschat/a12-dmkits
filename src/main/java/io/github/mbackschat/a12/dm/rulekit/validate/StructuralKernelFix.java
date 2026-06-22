package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Map;
import java.util.Optional;

/**
 * Curated {@code fix}/{@code explain} for kernel codes the operator catalog-join can't correctly serve.
 * {@link DiagnosticEnricher} consults this <b>first</b>, so these win over the join. Two kinds live here —
 * and note the kind that is <em>not</em>: a <b>cross-operator</b> code documented by several operators with
 * differing fixes is now handled soundly by the join itself (it attaches the <em>union</em>), so it does
 * NOT need an entry here. What does:
 * <ol>
 *   <li><b>ORPHAN codes</b> — <em>no</em> operator's {@code invalidExample} documents them, so the join has
 *       nothing to attach: the placement / iteration-scope family ({@code MVK_ERROR_FIELD_NOT_IN_RULEGROUP},
 *       {@code MVK_NEG_CONDITION_IN_ITERATION}), the bare <b>syntax</b> code the parser raises with no message
 *       of its own ({@code MVK_UNEXPECTED_TOKEN} — the likely cause, a lowercase boolean / unquoted string /
 *       unbracketed field operand, must be inferred from the DSL's literal conventions), the
 *       <b>entity-resolution</b> code ({@code MVK_INVALID_ENTITY} — a bare/relative name that doesn't bind;
 *       the fix is the absolute path), and the <b>enum locale-coverage</b> code
 *       ({@code MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES} — an enum value labeled in some declared locales but
 *       not all, whose opaque "display value and XML value not specified together" message hides the gap).</li>
 *   <li><b>CROSS-CONTEXT codes</b> — documented by operator(s), but ALSO fired by a construct the catalog
 *       cannot describe, so the documenter's fix is <em>wrong</em> there (probe findings F06-2 / dec-places):
 *       {@code MVK_NO_WILDCARD} (CurrentRepetition documents it, but a non-iterating aggregate / computation
 *       raises it too — the per-repetition "put the error field inside Items" fix mis-stamps the aggregate
 *       case) and {@code MVK_INVALID_COMPARE_DEC_PLACES} (Equal/NotEqual document it as a field-vs-field
 *       comparison, but a computed field whose scale differs from its expression raises it too — the
 *       "round one with RoundDown" comparison fix mis-stamps the computation case, where the fix is to
 *       match the computed field's scale). The catalog can only see operators, never these other triggers,
 *       so the corrective must be authored here at the code level.</li>
 * </ol>
 *
 * <p>Hand-authored guidance, like the {@code RK_*} meanings in {@link RkCode} — but locked the same
 * way the catalog fixes are: a test triggers the real kernel code and asserts the fix attaches
 * (e.g. {@code RuleValidatorTest.attachesAPlacementFixToTheNotInRuleGroupCode}; the cross-context
 * {@code DEC_PLACES} two-context hint by {@code DiagnosticEnricherTest}), so the text can't drift away
 * from the behavior it explains. The placement law itself is locked in
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

    private static final Map<String, Hint> HINTS = Map.of(
            "MVK_NO_WILDCARD", new Hint(
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
                            + "not specific to any single one."),
            "MVK_ERROR_FIELD_NOT_IN_RULEGROUP", new Hint(
                    "Place the error field inside every repeatable group the rule iterates over. The condition "
                            + "references a per-row field, so the rule iterates and its error field must live in that "
                            + "repeatable group — either pick an error field inside it, or make the rule whole-document "
                            + "by aggregating the per-row operand (e.g. Sum(Group*/Field)) so it no longer iterates.",
                    "A rule's iteration scope is the set of repeatable groups among the fields it references "
                            + "(including the error field); the error field must lie in every repeatably-referenced group, "
                            + "otherwise the per-row error has nowhere to attach."),
            "MVK_NEG_CONDITION_IN_ITERATION", new Hint(
                    "Guard the negative predicate with a positive existence check on the row: "
                            + "GroupFilled(<the repeatable group>) And <your negative condition> — so the rule fires "
                            + "only for rows that actually exist, not for the empty/absent repetitions.",
                    "A bare negative (FieldNotFilled/NoFieldFilled) inside an iterating rule would also fire on the "
                            + "empty/non-existent repetitions of the iterated group; the kernel requires a positive "
                            + "guard to scope it to real rows."),
            "MVK_UNEXPECTED_TOKEN", new Hint(
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
                            + "double-quoted strings, case-sensitive PascalCase operators)."),
            "MVK_INVALID_ENTITY", new Hint(
                    "The group or field name in the condition could not be resolved in the rule's scope. Use its "
                            + "ABSOLUTE path (a leading `/`, e.g. `/Order/Items/Sku`) rather than a bare or relative "
                            + "name — a short name is unreliable across sibling groups under a wrapper and binds only "
                            + "when the model sets `fieldRefByShortNameAllowed`. If the name is already correct, check "
                            + "the rule's iteration scope contains it: a per-row field needs the rule to iterate over "
                            + "its repeatable group (set the rule group to that group's PARENT, error field inside).",
                    "MVK_INVALID_ENTITY (\"the group or field 'X' is not valid\") means the kernel could not resolve "
                            + "that name — almost always a bare/relative reference that does not bind across sibling "
                            + "groups, or a field outside the rule's iteration scope."),
            "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES", new Hint(
                    "An enum value's display label (`labels`) covers some of the model's declared locales but not "
                            + "all — the kernel requires every enum value to carry a display label for EVERY declared "
                            + "locale (or none at all). This most often surfaces right after adding a locale "
                            + "(`config modify --add-locale`): top up each enum value's `labels` to cover the new "
                            + "locale, and do the `--add-locale` plus the per-value label top-ups in ONE `apply` so the "
                            + "single terminal gate sees a consistent model.",
                    "MVK_INTERNAL_VALUES_AND_DISPLAY_VALUES (\"the display value and the XML value were not specified "
                            + "together\") fires when an enum value's internal (XML) value and its per-locale display "
                            + "labels are not specified together across the declared locales — a label-coverage gap on "
                            + "the enum, not a condition error."),
            "MVK_INVALID_COMPARE_DEC_PLACES", new Hint(
                    "The two decimal operands here must share the SAME declared scale (fractional digits) — only "
                            + "== / != and a computed-field assignment are checked; ORDERING (< <= > >=) is exempt. TWO "
                            + "contexts trigger it: (1) a COMPUTED FIELD whose declared scale differs from its "
                            + "expression's operands (e.g. a field re-scaled to 2 places while the Sum-computed field "
                            + "stays scale 0) — re-scale the computed field (or the operands) so their fractional digits "
                            + "agree, doing both edits in ONE `apply` so the terminal gate sees a consistent model; "
                            + "(2) a field-vs-field == / != across differing scales — either round one side to the "
                            + "other's scale with RoundDown(expr, n), or accept the mismatch with "
                            + "allowDifferingDecimals:true on the rule spec (the kernel's one suppressible warning — it "
                            + "prepends @SuppressWarning(MVK_INVALID_COMPARE_DEC_PLACES)).",
                    "MVK_INVALID_COMPARE_DEC_PLACES (\"the fractional digits of lhs and rhs are different\") fires "
                            + "wherever the kernel compares two decimal operands of unequal declared scale — an == / != "
                            + "field comparison OR a computed field whose scale doesn't match its expression. It is not "
                            + "specific to any one operator (Sum, Equal, …); the scale-coupling is the constraint, so the "
                            + "corrective is context-dependent."));

    private StructuralKernelFix() {
    }

    /** The curated hint for {@code code}, when it is a known structural kernel code. */
    public static Optional<Hint> forCode(String code) {
        return Optional.ofNullable(HINTS.get(code));
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
                    "A date/time field FORMAT must be a LOWERCASE Java pattern from the kernel's fixed allowed set "
                            + "(the editor's UPPERCASE labels like YYYY-MM-DD are display labels, NOT format values). "
                            + "Verified: DATE `yyyy-MM-dd` or `dd.MM.yyyy`; DATERANGE the pair `dd.MM.yyyy`+separator `-` "
                            + "or `yyyy-MM-dd`+separator `/`; TIME the full `HH:mm:ss` (not `HH:mm`); DATEFRAGMENT "
                            + "`MM`/`yyyy`/`yyyy-MM`/`MM-dd`. See `schema field add`.",
                    "The kernel validates a date/time field's declared format against a fixed allowed set (the same "
                            + "regardless of locale) and rejects anything else with a message but no MVK_ code."));
        }
        return Optional.empty();
    }
}
