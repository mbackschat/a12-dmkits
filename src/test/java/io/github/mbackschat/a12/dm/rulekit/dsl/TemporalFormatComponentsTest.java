package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.TemporalFormats.Component;

/**
 * The typed peer of {@code validate.laws.TemporalExtractionFormatGateLawsTest}, which measures at the kernel
 * what this classifier has to reproduce: an extraction is admitted exactly when the declared format carries
 * that unit's component (KERNEL-FINDINGS {@code kf426}).
 *
 * <p>The classifier is a component SET rather than the three-valued {@code Shape} beside it because
 * {@code Shape} provably cannot state the law — it calls {@code yyyy-MM} and {@code yyyy-MM-dd} both
 * {@code DATE}, and the kernel refuses {@code DayFromDate} on the first while admitting it on the second.
 */
class TemporalFormatComponentsTest {

    @Test
    void aFormatDeclaresTheComponentsItsLettersName() {
        assertEquals(EnumSet.of(Component.YEAR, Component.MONTH, Component.DAY),
                TemporalFormats.componentsOf("yyyy-MM-dd"));
        assertEquals(EnumSet.of(Component.HOUR, Component.MINUTE, Component.SECOND),
                TemporalFormats.componentsOf("HH:mm:ss"));
        assertEquals(EnumSet.allOf(Component.class),
                TemporalFormats.componentsOf("yyyy-MM-dd'T'HH:mm:ss"));
    }

    /** The quoted section of the ISO datetime is a literal, so its {@code T} names no component. */
    @Test
    void aQuotedSectionContributesNoComponent() {
        assertEquals(EnumSet.of(Component.YEAR), TemporalFormats.componentsOf("yyyy'Md'"));
    }

    /** {@code M} is the month and {@code m} the minute — the pair that makes the scan case-sensitive. */
    @Test
    void theComponentLettersAreCaseSensitive() {
        assertEquals(EnumSet.of(Component.MONTH), TemporalFormats.componentsOf("MM"));
        assertEquals(EnumSet.of(Component.MINUTE), TemporalFormats.componentsOf("mm"));
    }

    /** The discriminating pair: identical under {@code Shape}, different under components. */
    @Test
    void componentsSeparateTwoFormatsThatShapeCannot() {
        assertEquals(TemporalFormats.shapeOf("yyyy-MM"), TemporalFormats.shapeOf("yyyy-MM-dd"));
        assertNotEquals(TemporalFormats.componentsOf("yyyy-MM"), TemporalFormats.componentsOf("yyyy-MM-dd"));
        assertTrue(TemporalFormats.componentsOf("yyyy-MM-dd").contains(Component.DAY));
        assertTrue(!TemporalFormats.componentsOf("yyyy-MM").contains(Component.DAY));
    }

    /** Every admitted format names at least one component, or the classifier would be silently vacuous. */
    @Test
    void everyAdmittedFormatNamesAComponent() {
        // VOCABULARY-GRID-START
        for (String format : List.of("HH:mm:ss", "MM", "MM-dd", "MMdd", "dd.MM.yyyy", "ddMM", "yyyy",
                "yyyy-MM", "yyyy-MM-dd", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd")) {
            // VOCABULARY-GRID-END
            Set<Component> components = TemporalFormats.componentsOf(format);
            assertTrue(!components.isEmpty(), () -> format + " named no component");
        }
    }

    /**
     * A fragment is in the component rule's domain but NOT in {@code isTemporal}'s, and the split is
     * deliberate: {@code isTemporal} gates substituting one kind's typed handle for another on a matching
     * declared shape, and whether a fragment may stand in for a date that way is unmeasured. A DATE_RANGE is
     * in neither — the family reports {@code MVK_NO_DATE} for a span.
     */
    @Test
    void aDateFragmentReadsComponentsWithoutJoiningTheShapeSubstitution() {
        assertTrue(TemporalFormats.readsComponents(ValueKind.DATE_FRAGMENT));
        assertTrue(!TemporalFormats.isTemporal(ValueKind.DATE_FRAGMENT));

        assertTrue(!TemporalFormats.readsComponents(ValueKind.DATE_RANGE));
        for (ValueKind kind : List.of(ValueKind.DATE, ValueKind.TIME, ValueKind.DATE_TIME)) {
            assertTrue(TemporalFormats.readsComponents(kind), kind::name);
        }
    }

    /** The kind defaults the kernel was measured to apply when a temporal field declares no format. */
    @Test
    void eachKindsDefaultCarriesTheComponentsItWasMeasuredWith() {
        assertEquals(EnumSet.of(Component.YEAR, Component.MONTH, Component.DAY),
                TemporalFormats.componentsOf(TemporalFormats.defaultFormat(ValueKind.DATE)));
        assertEquals(EnumSet.of(Component.HOUR, Component.MINUTE, Component.SECOND),
                TemporalFormats.componentsOf(TemporalFormats.defaultFormat(ValueKind.TIME)));
        assertEquals(EnumSet.allOf(Component.class),
                TemporalFormats.componentsOf(TemporalFormats.defaultFormat(ValueKind.DATE_TIME)));
    }
}
