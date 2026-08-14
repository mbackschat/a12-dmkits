package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;


import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * {@code CG15} — the converter's group expansion must keep the {@code *}.
 *
 * <p><b>Two arms of one operator family, opposite semantics.</b> A field-fill quantifier over a MIXED group+field
 * scope expands each group through {@code descendantFields}, which collects {@code field.path()} — the DECLARED
 * path, with no star. Its twin {@code groupScopedFieldFill}, for the group-only scope, expands through
 * {@code model.flattenGroup(reconstructStarPath(e))} and keeps it. So
 * {@code FieldsNotCollectivelyFilled(Items*, Note)} reads back as repetition 1 only, while the same call without
 * the extra field operand reads back as all rows.
 *
 * <p><b>Why the existing lock cannot see it.</b> {@code MixedScopeFieldFillReadTest} uses a NON-repeatable
 * {@code Section}, where there is no star to drop — so the arm is covered and the defect is invisible, which is
 * the ordinary way a converter bug survives a green suite.
 *
 * <p>The group-ONLY arm is covered by {@code GroupScopedFieldFillReadTest} and is not repeated here: its error
 * field cannot be referenced by a bare group operand ({@code MVK_ERROR_FIELD_NOT_REFERENCED}), which is the
 * exactness/reachability split of {@code CG16} showing up in a fixture — a real constraint, not a detour worth
 * fighting in this file.
 */
class MixedScopeStarPreservationTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** {@code Items} is REPEATABLE — the star is the whole subject. {@code Note} is the mixing field operand. */
    private static String orderJson() {
        return DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o
                        .stringField("Note", b -> { })
                        .group("Items", i -> i.repeatable(10)
                                .stringField("Code", b -> { })
                                .stringField("Label", b -> { })))
                .build().toJson();
    }

    /**
     * <b>THE DISCRIMINATOR.</b> Every field the starred group expands to must carry the star, so the rule still
     * means "across all rows". Dropping it silently narrows the rule to repetition 1.
     */
    @Test
    void aMixedScopeKeepsTheStarOnTheExpandedGroupFields() {
        String rule = "FieldsNotCollectivelyFilled(Items*, Note)";
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(inject(orderJson(), rule)).read("/Order/MixedRule"),
                "a mixed group+field field-fill quantifier converts");

        String rendered = RENDER.render(converted.condition());
        assertTrue(rendered.contains("/Order/Items*/Code") && rendered.contains("/Order/Items*/Label"),
                () -> "the expansion of a STARRED group must keep the star on every field it produces, or the "
                        + "rule silently means repetition 1 only: " + rendered);
    }

    /**
     * The CONTROL that keeps the discriminator honest: an UNSTARRED (non-repeatable) group must NOT gain a star.
     * Without it, "always append a star" satisfies the row above and corrupts every non-repeatable scope.
     */
    @Test
    void aNonRepeatableGroupInAMixedScopeGainsNoStar() {
        String json = DocumentModelDraft.named("form").locale("en_US")
                .rootGroup("Form", f -> f
                        .stringField("Note", b -> { })
                        .group("Section", s -> s.stringField("Line1", b -> { }).stringField("Line2", b -> { })))
                .build().toJson();
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(injectInto(json, "/Form", "FieldsNotCollectivelyFilled(Section, Note)"))
                        .read("/Form/MixedRule"),
                "the non-repeatable mixed form converts");
        String rendered = RENDER.render(converted.condition());
        assertEquals(-1, rendered.indexOf('*'),
                () -> "a non-repeatable group has no repetitions to span, so no star may appear: " + rendered);
    }

    /**
     * A survey model whose starred group nests a SECOND repeatable group — the shape the two rows above cannot
     * reach, because {@code Items} has no subgroup at all.
     */
    private static String surveyJson() {
        return DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", s -> s
                        .stringField("Note", f -> { })
                        .group("Sections", g -> g.repeatable(20)
                                .stringField("Title", f -> { })
                                .group("Answers", a -> a.repeatable(20).stringField("Text", f -> { }))))
                .build().toJson();
    }

    /**
     * <b>EVERY repeatable level in the expansion needs its own star, not just the operand's own.</b> The
     * expansion walks the group's DECLARED descendant paths, so a nested repeatable subgroup arrived unstarred
     * while the outer star was correctly restored — and the kernel refuses that spelling outright.
     *
     * <p>Measured before the fix: the rule below is kernel-VALID as authored, and reading it produced
     * {@code FieldsNotCollectivelyFilled(…Sections*}{@code /Title, …Sections*}{@code /Answers/Text, …Note)},
     * which the kernel then rejects with <i>"Missing specification of an asterisk for 'Sections/Answers'"</i>
     * ({@code MVK_NO_WILDCARD}). A read path that turns a legal model into an unauthorable condition is the
     * sharpest form of this defect class, and no star-count assertion catches it — the outer star was there.
     */
    @Test
    void aNestedRepeatableGroupInTheExpansionGetsItsOwnStar() {
        String json = surveyJson();
        String authored = "FieldsNotCollectivelyFilled(Sections*, Note)";
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(injectInto(json, "/Survey", authored)).read("/Survey/MixedRule"),
                "the nested-repeatable mixed form converts");

        String rendered = RENDER.render(converted.condition());
        assertTrue(rendered.contains("/Survey/Sections*/Answers*/Text"),
                () -> "the nested repeatable must carry its own star: " + rendered);
        assertTrue(KernelAdapter.ofJson(injectInto(json, "/Survey", rendered))
                        .diagnose("/Survey/MixedRule").ok(),
                () -> "the read-back must itself be authorable — the kernel refuses a repeatable group "
                        + "addressed without its star: " + rendered);
    }

    /**
     * The CONTROL for the row above: a NON-repeatable subgroup under a starred group must gain no inner star,
     * so the fix is "star what repeats" rather than "star every segment".
     */
    @Test
    void aNonRepeatableSubgroupUnderAStarredGroupGainsNoInnerStar() {
        String json = DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", s -> s
                        .stringField("Note", f -> { })
                        .group("Sections", g -> g.repeatable(20)
                                .stringField("Title", f -> { })
                                .group("Meta", m -> m.stringField("Author", f -> { }))))
                .build().toJson();
        var converted = assertInstanceOf(ReadResult.Converted.class,
                KernelAdapter.ofJson(injectInto(json, "/Survey", "FieldsNotCollectivelyFilled(Sections*, Note)"))
                        .read("/Survey/MixedRule"),
                "the non-repeatable-subgroup mixed form converts");

        String rendered = RENDER.render(converted.condition());
        assertTrue(rendered.contains("/Survey/Sections*/Meta/Author"),
                () -> "a non-repeatable subgroup has no repetitions to span: " + rendered);
    }

    private static String inject(String modelJson, String rawCondition) {
        return injectInto(modelJson, "/Order", rawCondition);
    }

    /**
     * Inject a raw-text rule at the first {@code elements} array — a mixed group+field scope is not reachable
     * through the typed DSL, which is itself a {@code DG}-class erasure but not this test's subject. Same shape
     * as {@code MixedScopeFieldFillReadTest}'s helper, parameterized by the group so both fixtures can use it.
     */
    private static String injectInto(String modelJson, String group, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"" + group + "/MixedRule\", \"name\": \"MixedRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../Note\", \"errorCode\": \"C\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + rule + ",\n" + modelJson.substring(br + 1);
    }
}
