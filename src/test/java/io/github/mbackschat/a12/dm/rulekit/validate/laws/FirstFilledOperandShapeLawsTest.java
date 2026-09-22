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
 * <p>Only the filter-on-a-group class is the same on every shape measured. The other three change once a level
 * repeats and is left unstarred, where the missing star ({@code MVK_NO_WILDCARD}) answers first. For the field
 * filter the class also depends on whether the rule iterates that level (KF358, KF424). An operand does not
 * carry which of its levels repeat, so those three refusals stay uncoded, and the mirror below locks that.
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
                    .group("Nested", g -> g.group("Deep", deep -> deep.field("Note", FieldKind.STRING))))
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
        expected.keySet().forEach(condition -> actual.put(condition, verdict("../Title", "Title", condition)));
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
        expected.keySet().forEach(condition -> actual.put(condition,
                verdict("../Lines/Sku", "Lines/Sku", condition)));
        assertEquals(expected, actual);
    }

    @Test
    void rulekitCitesOnlyTheClassNoGroupShapeChanges() {
        BoundModel m = BoundModel.ofJson(MODEL);
        Condition filter = m.num("/Item/Weight").gt(0);
        for (String group : List.of("/Item/Grading", "/Item/Lines", "/Item/Lines*")) {
            assertEquals(NO_GROUPS, assertThrows(KernelCodeRefusal.class,
                    () -> m.firstFilledGroup(group).having(filter)).code().name(), group);
        }

        CategoryRef gradeBand = m.enumOf("/Item/Grading/Grade").category("Band");
        assertUncoded(() -> m.firstFilledGroup("/Item/Grading").withCategory(gradeBand));
        assertUncoded(() -> m.firstFilledGroup("/Item/Empty"));
        assertUncoded(() -> m.firstFilledGroup("/Item/Blank"));
        assertUncoded(() -> FirstFilledOperand.of(m.field("/Item/Title")).having(filter));
        assertUncoded(() -> FirstFilledOperand.of(m.field("/Item/Lines/Sku")).having(filter));
        // a category handle from a DIFFERENT field is a wrong pairing of handles, which A12 never sees
        assertUncoded(() -> FirstFilledOperand.of(m.field("/Item/Tier")).withCategory(gradeBand));

        // the admitted neighbours still build
        m.firstFilledGroup("/Item/Nested");
        FirstFilledOperand.of(m.flatten("/Item/Lines*/Sku")).having(m.num("/Item/Lines/Qty").gt(0));
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

    /** {@code admitted}, or the COMPLETE emitted code list — a partial list would hide a co-firing gate. */
    private static String verdict(String errorRel, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, "/Item", errorRel,
                "FieldFilled(" + errorField + ") And " + condition);
        return r.ok() ? ADMITTED : String.join(" + ", r.diagnostics().stream()
                .map(d -> d.code().orElse("(no code: " + d.summary() + ")")).map(Object::toString).toList());
    }
}
