package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §9 — <b>what scalar {@code GroupFilled} admits is decided by the OPERAND's repeatability,
 * not by where the rule sits</b> (measured 2026-09-04). A repeatable operand needs the rule anchored inside it
 * ({@code MVK_NO_WILDCARD} from outside — the same class the unstarred count draws, [KF185]); a non-repeatable
 * operand, a root group included, carries no repetition to bind and is admitted from either locus.
 *
 * <p><b>Why this exists.</b> {@code skill/SKILL.md} told agents that plain {@code GroupFilled(Group)} is
 * "valid only from <em>inside</em> the iterating group, never as a model-level reference" — true of a
 * repeatable operand and false of every other one, so the two admitted model-level rows below were being
 * authored around. The findings record already carried both halves separately ([KF185]'s locus table for the
 * repeatable arm, §9's root-group bullet for root groups), which is precisely why the skill's over-general
 * sentence survived: each half was documented and no surface held them side by side.
 *
 * <p><b>The spelling axis is varied on purpose.</b> [KF185] closes by noting that a shape's class can differ
 * between the relative and the absolute spelling, so a table written only in absolute paths would leave the
 * claim scoped to a spelling nobody stated. Both spellings agree on the repeatability split here; the one
 * place they part is unrelated to it — a relative group name resolves from the HOST group only, so
 * {@code ShippingAddress} seen from {@code /Order/Items} is {@code MVK_INVALID_ENTITY}. There is no upward
 * search for a group either, exactly as there is none for a field (§10).
 *
 * <p><b>Every row carries the error-field conjunct, and that is load-bearing.</b> {@code GroupFilled} alone
 * satisfies the error-field reference only when it ENCLOSES the error field (the catalog's own
 * {@code GroupFilled} gotcha), so an operand naming any other group draws
 * {@code MVK_ERROR_FIELD_NOT_REFERENCED} — measured, on the first attempt. Without the conjunct the admitted
 * rows would report that unrelated class and the table would read as a locus law.
 *
 * <p><b>Not here:</b> the negative half of the pair. {@code GroupNotFilled} carries its own
 * {@code MVK_NEG_CONDITION_IN_ITERATION} gate inside an iterating rule (§9), which would move a second
 * variable in every row; its arms are locked with that gate rather than beside this one.
 */
class ScalarGroupPresenceLocusLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String NO_WILDCARDS_ALLOWED = "MVK_NO_WILDCARDS_ALLOWED";
    private static final String INVALID_ENTITY = "MVK_INVALID_ENTITY";

    /** The model-level host: the rule sits at the root group with its error field outside every repetition. */
    private static final String OUTSIDE_GROUP = "/Order";
    private static final String OUTSIDE_FIELD = "../CustomerName";

    /** The inside host: the rule sits at the repeatable group, so it iterates its rows. */
    private static final String INSIDE_GROUP = "/Order/Items";
    private static final String INSIDE_FIELD = "../Sku";

    @Test
    void repeatabilityOfTheOperandDecidesAdmission() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("non-repeatable operand, from outside", outside("/Order/ShippingAddress"));
        actual.put("non-repeatable operand, from inside the repeatable group", inside("/Order/ShippingAddress"));
        actual.put("root group, from outside", outside("/Order"));
        actual.put("root group, from inside the repeatable group", inside("/Order"));
        actual.put("repeatable operand, from outside", outside("/Order/Items"));
        actual.put("repeatable operand, from inside itself", inside("/Order/Items"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("non-repeatable operand, from outside", ADMITTED);
        expected.put("non-repeatable operand, from inside the repeatable group", ADMITTED);
        expected.put("root group, from outside", ADMITTED);
        expected.put("root group, from inside the repeatable group", ADMITTED);
        // The only pair the locus moves, and it moves because the operand has rows to bind — not because a
        // model-level reference is barred.
        expected.put("repeatable operand, from outside", NO_WILDCARD);
        expected.put("repeatable operand, from inside itself", ADMITTED);
        assertEquals(expected, actual, "the operand's repeatability selects the class, not the rule's locus");
    }

    @Test
    void theSplitIsTheSameInTheRelativeSpelling() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("relative non-repeatable child, from its parent", outside("ShippingAddress"));
        actual.put("relative repeatable child, from its parent", outside("Items"));
        actual.put("relative name of a group the host does not contain", inside("ShippingAddress"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("relative non-repeatable child, from its parent", ADMITTED);
        expected.put("relative repeatable child, from its parent", NO_WILDCARD);
        // Not part of the repeatability split: a relative group name resolves from the host group and there is
        // no upward search, so the operand names nothing at all from this locus.
        expected.put("relative name of a group the host does not contain", INVALID_ENTITY);
        assertEquals(expected, actual, "both spellings agree on the split that this file is about");
    }

    @Test
    void aStarIsRefusedFromEitherLocus() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("starred operand, from outside", outside("/Order/Items*"));
        actual.put("starred operand, from inside itself", inside("/Order/Items*"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("starred operand, from outside", NO_WILDCARDS_ALLOWED);
        expected.put("starred operand, from inside itself", NO_WILDCARDS_ALLOWED);
        assertEquals(expected, actual, "the scalar slot bars the star from both loci — it is not a locus gate");
    }

    private static String outside(String operand) {
        return verdict(OUTSIDE_GROUP, OUTSIDE_FIELD, "FieldFilled(CustomerName) And GroupFilled(" + operand + ")");
    }

    private static String inside(String operand) {
        return verdict(INSIDE_GROUP, INSIDE_FIELD, "FieldFilled(Sku) And GroupFilled(" + operand + ")");
    }

    /** The kernel's verdict — {@link KernelLaws#ADMITTED} or the comma-joined classes it reported. */
    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(Fixtures.orderJson(), group, errorField, condition);
        return r.ok() ? ADMITTED : r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).collect(Collectors.joining(","));
    }
}
