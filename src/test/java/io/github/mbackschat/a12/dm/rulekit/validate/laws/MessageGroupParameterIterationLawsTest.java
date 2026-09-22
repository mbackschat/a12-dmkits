package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * KERNEL-FINDINGS §13 — the gates the {@code $#…$} GROUP position applies AFTER containment.
 *
 * <p>[KF214] and the group-parameter grid measured which groups this position may name. Three further gates
 * run behind that one in {@code checkLevelParam}, and none has a measured shape: the group must be
 * referenced by the condition WITHOUT a repetition wildcard ({@code ITER_PARAM_WITH_STARREFERENCE}), and a
 * parallel iteration then admits the parameter only where the condition's own shape allows it
 * ({@code INVALID_USE_OF_ITERATION_PARAM}, or {@code INVALID_USE_OF_SEMANTIC_INDEX} when the parameter
 * carries a key).
 *
 * <p><b>What this grid decides is which of them a checker can reach at all.</b> The wildcard gate reads the
 * condition's references to the named group, which is a question the checker already answers elsewhere; the
 * other two run a kernel visitor over the parsed condition. So the rows move the reference's FORM and hold
 * everything else, and the containment control is what says a refusal belongs to these gates rather than to
 * the one before them.
 *
 * <p>Own domain (retail ordering).
 */
class MessageGroupParameterIterationLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String LINES = "/Order/Lines";
    private static final String ITEMS = "/Order/Lines/Items";

    private static final String ORDER = DocumentModelDraft.named("order-group-parameter").locale("en_US")
            .rootGroup("Order", order -> order
                    .stringField("Reference", f -> { })
                    .group("Lines", lines -> lines.repeatable(9)
                            .stringField("Sku", f -> { })
                            .group("Items", items -> items.repeatable(9)
                                    .stringField("Code", f -> { }))))
            .build().toJson();

    private static final BoundModel M = BoundModel.ofJson(ORDER);

    private static String render(Condition condition) {
        return KernelLaws.render(condition);
    }

    /** In a row of `Lines`, reading its own field per row. */
    private static final String ROW_READS_SKU = render(Dsl.fieldFilled(M.field("/Order/Lines/Sku")));

    /** …and beside a STARRED read of that same field, which is the form the wildcard gate is about. */
    private static final String ROW_READS_SKU_STARRED = render(Dsl.fieldFilled(M.field("/Order/Lines/Sku"))
            .and(Dsl.atLeastOneFieldFilled(M.flatten("/Order/Lines*/Sku"))));

    /** In a row of the inner `Items`, reading its own field — `Lines` ridden by the path alone. */
    private static final String ITEM_READS_CODE = render(Dsl.fieldFilled(M.field("/Order/Lines/Items/Code")));

    /** The same, beside a starred read of the OUTER group's field. */
    private static final String ITEM_READS_CODE_AND_STARRED_SKU =
            render(Dsl.fieldFilled(M.field("/Order/Lines/Items/Code"))
                    .and(Dsl.atLeastOneFieldFilled(M.flatten("/Order/Lines*/Sku"))));

    /** A STARRED read of the rule's own group, and nothing else — the shorthand's own gate needs it. */
    private static final String STARRED_SKU_ALONE =
            render(Dsl.atLeastOneFieldFilled(M.flatten("/Order/Lines*/Sku")));

    private static String inLine(String condition, String message) {
        return KernelLaws.messageVerdict(ORDER, LINES, "../Sku", condition, "en_US", message);
    }

    private static String inItem(String condition, String message) {
        return KernelLaws.messageVerdict(ORDER, ITEMS, "../Code", condition, "en_US", message);
    }

    @Test
    void whetherTheGroupPositionNeedsAnUnstarredReferenceToTheGroupItNames() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("its own group, read per row — the control", inLine(ROW_READS_SKU, "row $#/Order/Lines$"));
        verdicts.put("its own group, parameter-free", inLine(ROW_READS_SKU_STARRED, "row"));
        verdicts.put("its own group, also read through a STAR",
                inLine(ROW_READS_SKU_STARRED, "row $#/Order/Lines$"));
        verdicts.put("an ANCESTOR group, read per row — the control",
                inItem(ITEM_READS_CODE, "row $#/Order/Lines$"));
        verdicts.put("an ANCESTOR group whose field the condition reads through a STAR",
                inItem(ITEM_READS_CODE_AND_STARRED_SKU, "row $#/Order/Lines$"));
        verdicts.put("that same shape, parameter-free", inItem(ITEM_READS_CODE_AND_STARRED_SKU, "row"));
        verdicts.put("the RULE-GROUP shorthand, its group read per row",
                inLine(ROW_READS_SKU, "row $#RuleGroup$"));
        verdicts.put("the RULE-GROUP shorthand, its group read through a STAR ALONE",
                inLine(STARRED_SKU_ALONE, "row $#RuleGroup$"));
        verdicts.put("that same condition, parameter-free", inLine(STARRED_SKU_ALONE, "row"));
        verdicts.put("the same group by ABSOLUTE PATH, read through a star alone",
                inLine(STARRED_SKU_ALONE, "row $#/Order/Lines$"));
        verdicts.put("the ROOT shorthand on that same condition", inLine(STARRED_SKU_ALONE, "row $#RootGroup$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("its own group, read per row — the control", ADMITTED);
        expected.put("its own group, parameter-free", ADMITTED);
        expected.put("its own group, also read through a STAR", ADMITTED);
        expected.put("an ANCESTOR group, read per row — the control", ADMITTED);
        expected.put("an ANCESTOR group whose field the condition reads through a STAR", ADMITTED);
        expected.put("that same shape, parameter-free", ADMITTED);
        expected.put("the RULE-GROUP shorthand, its group read per row", ADMITTED);
        expected.put("the RULE-GROUP shorthand, its group read through a STAR ALONE",
                "ITER_PARAM_WITH_STARREFERENCE");
        expected.put("that same condition, parameter-free", ADMITTED);
        // The gate is the POSITION's, not the shorthand's: the same group spelled out draws the same class,
        // which is what lets a checker apply it to every spelling rather than to the one word.
        expected.put("the same group by ABSOLUTE PATH, read through a star alone",
                "ITER_PARAM_WITH_STARREFERENCE");
        expected.put("the ROOT shorthand on that same condition", ADMITTED);
        assertEquals(expected, verdicts,
                "whether the group position needs an unstarred reference to the group it names");
    }
}
