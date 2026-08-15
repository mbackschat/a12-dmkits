package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/**
 * {@code CG15} / {@code DG36} — a MIXED entity list (fields beside group scopes) round-trips to the text the
 * author wrote.
 *
 * <p><b>What this file replaces, and why the replacement is the fix rather than a stronger assertion.</b> The
 * converter used to REBUILD each group member into the fields the kernel expands it to, and that rewrite went
 * wrong twice in nine days: first it dropped the operand's own star, so {@code Items*} read back as
 * {@code Items} and the rule silently meant repetition 1; then, with that fixed, it dropped a NESTED
 * repeatable's star — and the kernel refuses that spelling outright ({@code MVK_NO_WILDCARD},
 * <a href="../../../../../../../../docs/KERNEL-FINDINGS.md">KF188</a>), so reading a kernel-VALID model
 * produced a condition no author could store. Two defects of one shape is the tell that the shape itself was
 * wrong: the kernel has ONE entity list whose members may be fields or groups, and rulekit modelled it as two
 * disjoint records, which left the converter no way to say what it had read. With one record over
 * {@code AggregateOperand} (DG36) there is nothing to rebuild, so neither defect has a place to live.
 *
 * <p>The star assertions are kept as rows rather than folded into the round trip: a mismatch on the full text
 * is correct but says nothing about <i>which</i> star moved, and both historical defects were exactly a star.
 */
class MixedEntityListRoundTripTest {

    private static final DslRenderer RENDER = new DslRenderer(ConditionLanguage.EN);

    /** {@code Items} is REPEATABLE and flat; {@code Sections} nests a SECOND repeatable; {@code Meta} is flat. */
    private static String surveyJson() {
        return DocumentModelDraft.named("survey").locale("en_US")
                .rootGroup("Survey", s -> s
                        .stringField("Note", f -> { })
                        .group("Items", i -> i.repeatable(20)
                                .stringField("Code", f -> { })
                                .stringField("Label", f -> { }))
                        .group("Sections", g -> g.repeatable(20)
                                .stringField("Title", f -> { })
                                .group("Answers", a -> a.repeatable(20).stringField("Text", f -> { })))
                        .group("Meta", m -> m.stringField("Author", f -> { })))
                .build().toJson();
    }

    private static final String SURVEY_JSON = surveyJson();

    /** The repeatable group keeps its star — the first of the two historical losses. */
    @Test
    void aStarredGroupBesideAFieldKeepsItsStar() {
        String rendered = assertRoundTrips("FieldsNotCollectivelyFilled(/Survey/Items*, /Survey/Note)");
        assertTrue(rendered.contains("/Survey/Items*"), rendered);
    }

    /**
     * The NESTED repeatable needs no star of its own here, which is the second loss stated positively: the
     * group operand covers every repeatable level inside it, and nothing in the read-back names those levels.
     */
    @Test
    void aNestedRepeatableUnderAStarredGroupNeedsNoInnerStar() {
        String rendered = assertRoundTrips("FieldsNotCollectivelyFilled(/Survey/Sections*, /Survey/Note)");
        assertTrue(rendered.contains("/Survey/Sections*") && !rendered.contains("/Answers"),
                () -> "the group is the operand; its levels are the kernel's to expand: " + rendered);
    }

    /** The CONTROL: a NON-repeatable group must gain no star, so the star is the operand's, never the arm's. */
    @Test
    void aNonRepeatableGroupBesideAFieldGainsNoStar() {
        String rendered = assertRoundTrips("FieldsNotCollectivelyFilled(/Survey/Meta, /Survey/Note)");
        assertEquals(-1, rendered.indexOf('*'),
                () -> "a non-repeatable group has no repetitions to span: " + rendered);
    }

    /** Both operand orders, because the converter's own branching keys on whether the FIRST entity is a field. */
    @Test
    void bothOperandOrdersRoundTrip() {
        assertRoundTrips("FieldsNotCollectivelyFilled(/Survey/Note, /Survey/Items*)");
        assertRoundTrips("FieldValuesNotUnique(/Survey/Note, /Survey/Meta)");
        assertRoundTrips("FieldValuesNotUnique(/Survey/Meta, /Survey/Note)");
    }

    /** A starred FIELD beside a group — the member kinds are independent, and each keeps its own spelling. */
    @Test
    void aStarredFieldBesideAGroupKeepsBothSpellings() {
        assertRoundTrips("AtLeastOneFieldFilled(/Survey/Items*/Code, /Survey/Meta)");
    }

    private static String assertRoundTrips(String predicate) {
        String authored = "FieldFilled(/Survey/Note) And " + predicate;
        KernelAdapter adapter = KernelAdapter.ofJson(inject(SURVEY_JSON, authored));
        var verdict = adapter.diagnose("/Survey/MixedRule");
        assertTrue(verdict.ok(),
                () -> "the authored condition must be kernel-legal: " + authored + " → " + verdict.diagnostics());
        var converted = assertInstanceOf(ReadResult.Converted.class, adapter.read("/Survey/MixedRule"),
                () -> "the mixed entity list must convert: " + authored);
        String rendered = RENDER.render(converted.condition());
        assertEquals(authored, rendered, "the mixed list must render back to the authored condition");
        return rendered;
    }

    private static String inject(String modelJson, String rawCondition) {
        String escaped = rawCondition.replace("\\", "\\\\").replace("\"", "\\\"");
        String rule = "{ \"type\": \"Rule\", \"id\": \"/Survey/MixedRule\", \"name\": \"MixedRule\","
                + " \"Rule\": { \"errorEntityRelPath\": \"../Note\", \"errorCode\": \"M\","
                + " \"errorCondition\": \"" + escaped + "\", \"severity\": \"ERROR\","
                + " \"errorMessage\": [ { \"locale\": \"en_US\", \"text\": \"x\" } ] } }";
        int br = modelJson.indexOf('[', modelJson.indexOf("\"elements\""));
        return modelJson.substring(0, br + 1) + "\n" + rule + ",\n" + modelJson.substring(br + 1);
    }
}
