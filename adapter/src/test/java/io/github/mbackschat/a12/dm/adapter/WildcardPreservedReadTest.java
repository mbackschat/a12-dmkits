package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A starred operand keeps its star on read, and a conversion that loses one is refused rather than published.
 *
 * <p>The defect this locks was silent. A field-fill quantifier with a {@code $}-marked member rebuilt every
 * member from its plain path, so a starred sibling came back unstarred: the read reported {@code Converted} and
 * rendered a different rule. The other markers a conversion can drop ({@code $}, a category, a semantic index)
 * each have a count-check that degrades the rule to {@code Opaque}; the star had none, so nothing stopped it.
 */
class WildcardPreservedReadTest {

    private static final RuleValidator VALIDATOR = new RuleValidator();

    /** The discriminating row: kernel-admitted, and it read back as {@code Breaks/From} before the fix. */
    @Test
    void aStarredMemberBesideACorrelatedOneReadsBackWithItsStar() {
        String stored = "FieldFilled(/Schedule/Slots/Start) And AtLeastOneFieldFilled(/Schedule/Slots*/Start Having"
                + " [/Schedule/Slots/End] > [$/Schedule/Slots/Start]"
                + " And NoFieldFilled($/Schedule/Slots/End, /Schedule/Breaks*/From))";
        DraftRule rule = new DraftRule("/Schedule/Slots", "Gap", "../Start", stored, "C", "ERROR");
        assertEquals(true, VALIDATOR.check(scheduleJson(), rule).ok(), "the kernel must admit the row it reads");

        ReadResult read = KernelAdapter.ofJson(VALIDATOR.inject(scheduleJson(), rule)).read("/Schedule/Slots/Gap");

        assertEquals(stored, read instanceof ReadResult.Converted converted
                ? new DslRenderer(ConditionLanguage.EN).render(converted.condition())
                : read.toString());
    }

    /** The backstop itself, driven directly, so it holds whichever conversion sites keep their stars. */
    @Test
    void aConversionThatRendersFewerStarsThanTheSourceIsRefused() {
        ConditionConverter converter = new ConditionConverter(BoundModel.ofJson(scheduleJson()));

        assertThrows(UnsupportedConstructException.class,
                () -> converter.verifyWildcardPreserved("NoFieldFilled(/Schedule/Breaks/From)", 1));
        assertDoesNotThrow(() -> converter.verifyWildcardPreserved("NoFieldFilled(/Schedule/Breaks*/From)", 1));
    }

    /** A multiplication and a star inside a string literal are not path stars, so neither may mask a drop. */
    @Test
    void onlyAStarOnAPathSegmentCounts() {
        ConditionConverter converter = new ConditionConverter(BoundModel.ofJson(scheduleJson()));

        assertThrows(UnsupportedConstructException.class,
                () -> converter.verifyWildcardPreserved("[/Schedule/Slots/Start] * 2 > 0", 1));
        assertThrows(UnsupportedConstructException.class,
                () -> converter.verifyWildcardPreserved("[/Schedule/Breaks/From] PatternMatched \"a*\"", 1));
        assertDoesNotThrow(() -> converter.verifyWildcardPreserved("Sum('Order'*/Net) > 0", 1),
                "a quoted reserved segment keeps its star outside the quotes");
    }

    private static String scheduleJson() {
        return DocumentModelDraft.named("schedule").locale("en_US")
                .rootGroup("Schedule", s -> s
                        .group("Slots", g -> g.repeatable(9)
                                .dateField("Start", d -> d.format("yyyy-MM-dd"))
                                .dateField("End", d -> d.format("yyyy-MM-dd")))
                        .group("Breaks", g -> g.repeatable(9).stringField("From", x -> { })))
                .build().toJson();
    }
}
