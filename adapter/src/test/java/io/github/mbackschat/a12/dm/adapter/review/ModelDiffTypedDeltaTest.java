package io.github.mbackschat.a12.dm.adapter.review;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.BooleanValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.DecimalValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.Delta;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.IntegerValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.StringListValue;
import io.github.mbackschat.a12.dm.adapter.review.ModelDiff.TextValue;

/** Locks the additive typed view over the stable rendered delta contract. */
class ModelDiffTypedDeltaTest {

    @Test
    void scalarAspectsExposeTheirNaturalTypes() {
        assertEquals(Optional.of(new BooleanValue(true)),
                new Delta("fieldRefByShortNameAllowed", "false", "true").afterValue());
        assertEquals(Optional.of(new IntegerValue(3)),
                new Delta("repeatability", "1", "3").afterValue());
        assertEquals(Optional.of(new DecimalValue(new BigDecimal("12.50"))),
                new Delta("max", "10", "12.50").afterValue());
        assertEquals(Optional.of(new TextValue("[Price] + [Tax]")),
                new Delta("condition", "[Price]", "[Price] + [Tax]").afterValue());
    }

    @Test
    void setLikeAspectsExposeStringListsWithoutChangingTheirRenderedForm() {
        Delta locales = new Delta("locales", "[\"en_US\"]", "[\"de_DE\",\"en_US\"]");

        assertEquals(Optional.of(new StringListValue(List.of("de_DE", "en_US"))), locales.afterValue());
        assertEquals("[\"de_DE\",\"en_US\"]", locales.after(), "the established string contract remains stable");
        assertEquals(Optional.of(new StringListValue(List.of("MVK_A", "MVK_B"))),
                new Delta("errorCodesToSuppress", null, "MVK_A,MVK_B").afterValue());
    }

    @Test
    void absentSidesStayEmptyAndMalformedKnownValuesDegradeToText() {
        Delta added = new Delta("baseYear", null, "not-a-number");

        assertEquals(Optional.empty(), added.beforeValue());
        assertEquals(Optional.of(new TextValue("not-a-number")), added.afterValue());
    }
}
