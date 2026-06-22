package io.github.mbackschat.a12.dm.rulekit.validate;

import java.util.Map;
import java.util.Optional;

/**
 * Curated {@code fix}/{@code explain} for kernel codes the operator catalog does <em>not</em> document —
 * the <b>structural</b> placement / iteration-scope / wildcard family, plus a bare <b>syntax</b> code the
 * parser raises with no message of its own ({@code MVK_UNEXPECTED_TOKEN}, where the likely cause — a
 * lowercase boolean, an unquoted string — must be inferred from the DSL's literal conventions). Two kinds
 * live here: codes <em>no</em> operator's {@code invalidExample} documents (placement / iteration-scope /
 * syntax), and <b>shared</b> codes that
 * <em>several</em> operators trigger ({@code MVK_NO_WILDCARD} — CurrentRepetition, RepetitionNotUnique,
 * aggregates, a model-scope group reference) where keying the fix off a single operator's
 * {@code invalidExample} mis-stamps every occurrence (probe finding F06-2: every {@code MVK_NO_WILDCARD}
 * got CurrentRepetition's "put the error field inside Items" fix). {@link DiagnosticEnricher} consults
 * this <b>first</b>, so a shared structural code gets the generic, operator-independent hint; an
 * operator-specific code misses here and falls through to the N7-locked catalog-join.
 *
 * <p>Hand-authored guidance, like the {@code RK_*} meanings in {@link RkCode} — but locked the same
 * way the catalog fixes are: a test triggers the real kernel code and asserts the fix attaches
 * (e.g. {@code RuleValidatorTest.attachesAPlacementFixToTheNotInRuleGroupCode}), so the text can't
 * drift away from the behavior it explains. The placement law itself is locked in
 * {@code validate.laws.IterationLawsTest} (KERNEL-FINDINGS §9).
 *
 * <p>Public because it is also the single source for the {@code diagnostics <code>} lookup of these
 * codes — a probe (P-mini-1) found an agent that hit one of them naturally tries {@code diagnostics
 * <code>}, which used to dead-end ("see operators/explain") since they have no operator home. The CLI
 * resolves them through {@link #forCode} instead.
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
                            + "`Not`. Re-check the token at the reported position against these.",
                    "MVK_UNEXPECTED_TOKEN is the kernel's generic parse error — a token appeared where the grammar "
                            + "did not allow it — and it is raised WITHOUT a message, so the likely cause has to be "
                            + "inferred from the DSL's literal/keyword conventions (capitalized booleans True/False, "
                            + "double-quoted strings, case-sensitive PascalCase operators)."));

    private StructuralKernelFix() {
    }

    /** The curated hint for {@code code}, when it is a known structural kernel code. */
    public static Optional<Hint> forCode(String code) {
        return Optional.ofNullable(HINTS.get(code));
    }
}
