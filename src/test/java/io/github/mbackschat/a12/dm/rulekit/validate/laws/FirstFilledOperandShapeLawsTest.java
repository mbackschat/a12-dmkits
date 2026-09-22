package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.github.mbackschat.a12.dm.core.KernelCodeRefusal;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.CategoryRef;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.FirstFilledOperand;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS KF71 — the SHAPE refusals of one {@code FirstFilledValue} operand, and which of them rulekit
 * may cite at the moment the operand is built.
 *
 * <p>Four shapes are refused on their own: a group with no field at any depth, a category projection on a
 * group, a {@code Having} filter on a group, and a {@code Having} filter on an unstarred field. Each class A12
 * reports for them outranks a duplicate and a type mismatch in the same list, wherever the shape sits. That is
 * what lets an operand-level refusal cite a code at all, since rulekit builds the operand before any list exists.
 *
 * <p>Two things move a group's class, and nothing else measured does. A repeatable level left unstarred is
 * bound by the rule when the rule iterates it and is a missing star ({@code MVK_NO_WILDCARD}) when it does not,
 * so the same operand answers differently on two rules. A semantic index answers {@code MVK_NO_INDEX_FIELD}
 * first when it resolves to no index field, which the operand cannot see. A group with neither keeps its class
 * on every rule context, inside a filter, and under the {@code $} marker, and only there does rulekit cite it.
 * For a field filter the class also depends on whether the rule iterates the level (KF358, KF424).
 */
class FirstFilledOperandShapeLawsTest {

    private static final String MODEL = DocumentModelDraft.named("catalogue").locale("en_US")
            .rootGroup("Item", i -> i
                    .field("Title", FieldKind.STRING)
                    .field("Subtitle", FieldKind.STRING)
                    .field("Weight", FieldKind.NUMBER)
                    .enumField("Tier", e -> e.value("BASIC").value("PLUS").category("Band", "LOW", "HIGH"))
                    .group("Grading", g -> g.enumField("Grade", e -> e.value("NEW").value("USED")
                            .category("Band", "LOW", "HIGH")))
                    .group("Lines", g -> g.repeatable(5).field("Sku", FieldKind.STRING).field("Qty", FieldKind.NUMBER))
                    .group("Reviews", g -> g.repeatable(5).enumField("Stars", e -> e.value("ONE").value("FIVE")
                            .category("Band", "LOW", "HIGH")))
                    .group("Empty", g -> g.group("Inner", inner -> { }))
                    .group("Blank", g -> g.repeatable(5).group("Inner", inner -> { }))
                    .group("Nested", g -> g.group("Deep", deep -> deep.field("Note", FieldKind.STRING)))
                    // the shapes one level down, under a repeatable level the rule may or may not iterate
                    .group("Outer", g -> g.repeatable(5).field("Label", FieldKind.STRING)
                            .group("Hollow", h -> h.group("Inner", inner -> { }))
                            .group("Graded", gr -> gr.enumField("Grade", e -> e.value("NEW").value("USED")
                                    .category("Band", "LOW", "HIGH"))))
                    .group("Keyed", g -> g.repeatable(5).indexField("Key").field("Key", FieldKind.STRING)
                            .field("Note", FieldKind.STRING)
                            .group("Hollow", h -> h.group("Inner", inner -> { }))))
            .build().toJson();

    @Test
    void eachShapesClass_onEveryGroupShape_andBesideASecondDefect() {
        Map<String, String> expected = new LinkedHashMap<>();
        // a filter on a group: one class, repeatable or not, starred or not
        expected.put("FirstFilledValue(Grading Having [Weight] > 0, Title) == \"x\"", NO_GROUPS);
        expected.put("FirstFilledValue(Lines Having [Lines/Qty] > 0, Title) == \"x\"", NO_GROUPS);
        expected.put("FirstFilledValue(Lines* Having [Lines/Qty] > 0, Title) == \"x\"", NO_GROUPS);
        // a category on a group: the same class, until a repeatable level is left unstarred
        expected.put("FirstFilledValue(Grading -> Band) == \"LOW\"", NO_GROUPS);
        expected.put("FirstFilledValue(Reviews* -> Band) == \"LOW\"", NO_GROUPS);
        expected.put("FirstFilledValue(Reviews -> Band) == \"LOW\"", NO_WILDCARD);
        expected.put("FirstFilledValue(Tier -> Band, Grading/Grade -> Band) == \"LOW\"", ADMITTED);
        expected.put("FirstFilledValue(Reviews*/Stars -> Band) == \"LOW\"", ADMITTED);
        // an empty group: likewise
        expected.put("FirstFilledValue(Empty) == \"x\"", EMPTY);
        expected.put("FirstFilledValue(Blank*) == \"x\"", EMPTY);
        expected.put("FirstFilledValue(Blank) == \"x\"", NO_WILDCARD);
        expected.put("FirstFilledValue(Nested) == \"x\"", ADMITTED);
        // a filter on an unstarred field: the class depends on the level it crosses
        expected.put("FirstFilledValue(Title Having [Weight] > 0, Subtitle) == \"x\"", WILDCARDS);
        expected.put("FirstFilledValue(Tier -> Band Having [Weight] > 0, Grading/Grade -> Band) == \"LOW\"",
                WILDCARDS);
        expected.put("FirstFilledValue(Lines/Sku Having [Lines/Qty] > 0, Title) == \"x\"", NO_WILDCARD);
        expected.put("FirstFilledValue(Lines*/Sku Having [Lines/Qty] > 0, Subtitle) == \"x\"", ADMITTED);
        // beside a second defect, in either position
        expected.put("FirstFilledValue(Empty, Title, Title) == \"x\"", EMPTY);
        expected.put("FirstFilledValue(Title, Title, Empty) == \"x\"", EMPTY);
        expected.put("FirstFilledValue(Title, Weight, Empty) == \"x\"", EMPTY);
        expected.put("FirstFilledValue(Grading -> Band, Tier, Tier) == \"LOW\"", NO_GROUPS);
        expected.put("FirstFilledValue(Title Having [Weight] > 0, Title) == \"x\"", WILDCARDS);
        expected.put("FirstFilledValue(Title, Title Having [Weight] > 0) == \"x\"", WILDCARDS);
        expected.put("FirstFilledValue(Title Having [Weight] > 0, Weight) == \"x\"", WILDCARDS);
        expected.put("[Title] == \"x\"", ADMITTED);

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition, verdict("Title", condition)));
        assertEquals(expected, actual);
    }

    /** The same shapes on a rule whose error field sits inside the repetition, the context KF424 turned on. */
    @Test
    void theGroupShapesKeepTheirClassOnARuleThatIteratesTheRepetition() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("FirstFilledValue(Lines Having [Lines/Qty] > 0, Title) == \"x\"", NO_GROUPS);
        expected.put("FirstFilledValue(Lines* Having [Lines/Qty] > 0, Title) == \"x\"", NO_GROUPS);
        expected.put("FirstFilledValue(Reviews* -> Band) == \"LOW\"", NO_GROUPS);
        expected.put("FirstFilledValue(Blank*) == \"x\"", EMPTY);
        expected.put("[Lines/Sku] == \"x\"", ADMITTED);

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(condition -> actual.put(condition, verdict("Lines/Sku", condition)));
        assertEquals(expected, actual);
    }

    /**
     * The two things that move a group's class, each beside the twin that does not move: one operand on three
     * rules (iterating nothing, the level it crosses, another level), then an index, then the {@code $} marker.
     */
    @Test
    void anUnstarredRepetitionOrAnIndexMovesTheClass_andNothingElseDoes() {
        Map<String, String> expected = new LinkedHashMap<>();
        for (String rule : List.of("Title", "Outer/Label", "Lines/Sku")) {
            // no unstarred repeatable level: one class on all three rules
            expected.put(rule + " | FirstFilledValue(Empty) == \"x\"", EMPTY);
            expected.put(rule + " | FirstFilledValue(Outer*/Hollow) == \"x\"", EMPTY);
            expected.put(rule + " | FirstFilledValue(Grading -> Band) == \"LOW\"", NO_GROUPS);
            expected.put(rule + " | FirstFilledValue(Outer*/Graded -> Band) == \"LOW\"", NO_GROUPS);
            expected.put(rule + " | [" + rule + "] == \"x\"", ADMITTED);
        }
        // the same two groups with Outer left unstarred: the rule decides
        expected.put("Title | FirstFilledValue(Outer/Hollow) == \"x\"", NO_WILDCARD);
        expected.put("Outer/Label | FirstFilledValue(Outer/Hollow) == \"x\"", EMPTY);
        expected.put("Lines/Sku | FirstFilledValue(Outer/Hollow) == \"x\"", NO_WILDCARD);
        expected.put("Title | FirstFilledValue(Outer/Graded -> Band) == \"LOW\"", NO_WILDCARD);
        expected.put("Outer/Label | FirstFilledValue(Outer/Graded -> Band) == \"LOW\"", NO_GROUPS);
        expected.put("Lines/Sku | FirstFilledValue(Outer/Graded -> Band) == \"LOW\"", NO_WILDCARD);
        // an index answers first where it resolves to no index field, and binds the level where it does
        expected.put("Title | FirstFilledValue(Empty For \"k\") == \"x\"", NO_INDEX_FIELD);
        expected.put("Title | FirstFilledValue(Grading For \"k\" -> Band) == \"LOW\"", NO_INDEX_FIELD);
        expected.put("Title | FirstFilledValue(Grading For \"k\" Having [Title] == \"a\", Title) == \"x\"",
                NO_INDEX_FIELD);
        expected.put("Title | FirstFilledValue(Keyed For \"k\" Having [Keyed/Note] == \"a\", Title) == \"x\"",
                NO_GROUPS);
        expected.put("Title | FirstFilledValue(Keyed/Hollow For \"k\") == \"x\"", EMPTY);
        expected.put("Title | FirstFilledValue(Keyed/Hollow) == \"x\"", NO_WILDCARD);
        expected.put("Title | FirstFilledValue(Keyed For \"k\") == \"x\"", ADMITTED);
        // inside a filter, with or without the outer marker: the classes of the bare shapes
        for (String marker : List.of("", "$")) {
            expected.put("Title | FirstFilledValue(Lines*/Sku Having FirstFilledValue(" + marker
                    + "Grading -> Band) == \"LOW\", Title) == \"x\"", NO_GROUPS);
            expected.put("Title | FirstFilledValue(Lines*/Sku Having FirstFilledValue(" + marker
                    + "Empty) == \"x\", Title) == \"x\"", EMPTY);
            expected.put("Title | FirstFilledValue(Lines*/Sku Having FirstFilledValue(" + marker
                    + "Grading Having [Title] == \"a\", Title) == \"x\", Subtitle) == \"x\"", NO_GROUPS);
        }

        Map<String, String> actual = new LinkedHashMap<>();
        expected.keySet().forEach(row -> {
            int bar = row.indexOf(" | ");
            actual.put(row, verdict(row.substring(0, bar), row.substring(bar + 3)));
        });
        assertEquals(expected, actual);
    }

    @Test
    void rulekitCitesEachClassExactlyWhereNeitherTheRuleNorAnIndexCanMoveIt() {
        BoundModel m = BoundModel.ofJson(MODEL);
        Condition filter = m.num("/Item/Weight").gt(0);
        CategoryRef gradeBand = m.enumOf("/Item/Grading/Grade").category("Band");

        for (String group : List.of("/Item/Grading", "/Item/Lines", "/Item/Lines*")) {
            assertCited(NO_GROUPS, () -> m.firstFilledGroup(group).having(filter));
        }
        assertCited(NO_GROUPS, () -> m.firstFilledGroup("/Item/Grading").outer().having(filter));
        for (String group : List.of("/Item/Grading", "/Item/Reviews*", "/Item/Outer*/Graded")) {
            assertCited(NO_GROUPS, () -> m.firstFilledGroup(group).withCategory(gradeBand));
        }
        assertCited(NO_GROUPS, () -> m.firstFilledGroup("/Item/Grading").outer().withCategory(gradeBand));
        for (String group : List.of("/Item/Empty", "/Item/Blank*", "/Item/Outer*/Hollow")) {
            assertCited(EMPTY, () -> m.firstFilledGroup(group));
        }

        // an unstarred repeatable level: the rule decides, and the operand has no rule yet
        assertUncoded(() -> m.firstFilledGroup("/Item/Reviews").withCategory(gradeBand));
        assertUncoded(() -> m.firstFilledGroup("/Item/Outer/Graded").withCategory(gradeBand));
        assertUncoded(() -> m.firstFilledGroup("/Item/Blank"));
        assertUncoded(() -> m.firstFilledGroup("/Item/Outer/Hollow"));
        // an index: whether it resolves to an index field decides, and MVK_NO_INDEX_FIELD is not ours to ask
        assertUncoded(() -> m.firstFilledGroupFor("/Item/Empty", "k"));
        assertUncoded(() -> m.firstFilledGroupFor("/Item/Empty", m.field("/Item/Title")));
        assertUncoded(() -> m.firstFilledGroupFor("/Item/Grading", "k").having(filter));
        assertUncoded(() -> m.firstFilledGroupFor("/Item/Grading", "k").withCategory(gradeBand));
        assertUncoded(() -> m.firstFilledGroup("/Item/Grading").forIndex("k").withCategory(gradeBand));
        // a field filter: the level it crosses, and then the rule, decide
        assertUncoded(() -> FirstFilledOperand.of(m.field("/Item/Title")).having(filter));
        assertUncoded(() -> FirstFilledOperand.of(m.field("/Item/Lines/Sku")).having(filter));
        // a category handle from a DIFFERENT field is a wrong pairing of handles, which A12 never sees
        assertUncoded(() -> FirstFilledOperand.of(m.field("/Item/Tier")).withCategory(gradeBand));

        // the admitted neighbours still build
        m.firstFilledGroup("/Item/Nested");
        m.firstFilledGroupFor("/Item/Keyed", "k");
        FirstFilledOperand.of(m.flatten("/Item/Lines*/Sku")).having(m.num("/Item/Lines/Qty").gt(0));
    }

    private static void assertCited(String code, Executable authoring) {
        assertEquals(code, assertThrows(KernelCodeRefusal.class, authoring).code().name());
    }

    private static void assertUncoded(Executable authoring) {
        IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, authoring);
        assertFalse(refusal instanceof KernelCodeRefusal, refusal.getMessage());
    }

    private static final String ADMITTED = "admitted";
    private static final String NO_GROUPS = "MVK_NO_GROUPS_ALLOWED";
    private static final String NO_WILDCARD = "MVK_NO_WILDCARD";
    private static final String WILDCARDS = "MVK_WILDCARDS_REQUIRED";
    private static final String EMPTY = "MVK_GROUP_IS_EMPTY";
    private static final String NO_INDEX_FIELD = "MVK_NO_INDEX_FIELD";

    /**
     * {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. The
     * error field sits in the level the rule iterates, so it also names the rule's context.
     */
    private static String verdict(String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Item", "../" + errorField,
                "FieldFilled(" + errorField + ") And " + condition);
        return r.ok() ? ADMITTED : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
