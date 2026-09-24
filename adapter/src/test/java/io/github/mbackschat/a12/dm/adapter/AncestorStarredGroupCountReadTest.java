package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A stored {@code NumberOfFilledGroups} reads back as the text it was stored as, a star on an ANCESTOR level
 * included.
 *
 * <p>A12 admits a fixed group below a starred repeatable level as an operand, alone or beside others
 * (KERNEL-FINDINGS KF462). A {@code GroupRef} carries the group's own repeatability and nothing about its
 * ancestors, and the renderer stars a group exactly when it repeats, so a group read as a {@code GroupRef}
 * comes back without the ancestor's star — a different rule, which A12 refuses as {@code MVK_NO_WILDCARD}
 * ({@link #theSpellingWithoutTheAncestorStarIsARuleA12Refuses}). The oracle is the round trip: the kernel admits
 * the stored text, and the read-back renders that same text.
 */
class AncestorStarredGroupCountReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    @Test
    void everyStoredCountRendersBackAsStored() {
        List<String> stored = List.of(
                "NumberOfFilledGroups(/Item/Batches*/Seal) > 0",
                "NumberOfFilledGroups(/Item/Batches*/Seal, /Item/Grading) > 0",
                "NumberOfFilledGroups(/Item/Grading, /Item/Batches*/Seal) > 0",
                // the controls: each operand spelled as its own repeatability stars it
                "NumberOfFilledGroups(/Item/Batches*) > 0",
                "NumberOfFilledGroups(/Item/Batches*, /Item/Grading) > 0",
                "NumberOfFilledGroups(/Item/Grading, /Item/Specs) > 0");
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> actual = new LinkedHashMap<>();
        for (String count : stored) {
            String condition = "FieldFilled(/Item/Title) And " + count;
            expected.put(count, condition);
            actual.put(count, readBack(condition));
        }
        assertEquals(expected, actual);
    }

    /**
     * The spelling a {@code GroupRef} would render, measured rather than claimed: without the ancestor's star
     * the fixed group names one instance below a repeatable level, which A12 refuses.
     */
    @Test
    void theSpellingWithoutTheAncestorStarIsARuleA12Refuses() {
        Map<String, List<String>> actual = new LinkedHashMap<>();
        for (String count : List.of(
                "NumberOfFilledGroups(/Item/Batches/Seal) > 0",
                "NumberOfFilledGroups(/Item/Batches/Seal, /Item/Grading) > 0")) {
            DraftRule rule = new DraftRule("/Item", "CountRule", "../Title",
                    "FieldFilled(/Item/Title) And " + count, "C", "ERROR");
            actual.put(count, VALIDATOR.check(model(), rule).diagnostics().stream()
                    .flatMap(diagnostic -> diagnostic.code().stream()).distinct().toList());
        }
        Map<String, List<String>> expected = new LinkedHashMap<>();
        expected.put("NumberOfFilledGroups(/Item/Batches/Seal) > 0", List.of("MVK_NO_WILDCARD"));
        expected.put("NumberOfFilledGroups(/Item/Batches/Seal, /Item/Grading) > 0", List.of("MVK_NO_WILDCARD"));
        assertEquals(expected, actual, "what A12 says about each count once the ancestor's star is dropped");
    }

    /** The renderer's spelling of what the adapter read, or the result that stopped it. */
    private static String readBack(String stored) {
        DraftRule rule = new DraftRule("/Item", "CountRule", "../Title", stored, "C", "ERROR");
        assertTrue(VALIDATOR.check(model(), rule).ok(), () -> "the kernel must admit the row it reads: " + stored);
        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(model(), rule)).read("/Item/CountRule");
        return read instanceof ReadResult.Converted converted
                ? new DslRenderer(ConditionLanguage.EN).render(converted.condition())
                : read.toString();
    }

    /** Seal is fixed inside the repeatable Batches; Grading and Specs are fixed groups beside it. */
    private static String model() {
        return DocumentModelDraft.named("warehouse-seal-count").locale("en_US")
                .rootGroup("Item", i -> i
                        .field("Title", FieldKind.STRING)
                        .group("Grading", g -> g.field("Grade", FieldKind.NUMBER))
                        .group("Specs", g -> g.field("Label", FieldKind.STRING))
                        .group("Batches", g -> g.repeatable(4)
                                .field("Lot", FieldKind.STRING)
                                .group("Seal", s -> s.field("Code", FieldKind.STRING))))
                .build().toJson();
    }
}
