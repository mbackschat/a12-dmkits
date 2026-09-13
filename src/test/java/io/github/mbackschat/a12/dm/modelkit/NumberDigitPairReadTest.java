package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * {@code NumberSpec}'s fractional-digit pair, held to the law A12 actually applies.
 *
 * <p>Two corrections in one place, both from the same grid
 * ({@code rulekit.validate.laws.NumberTypeDigitLawsTest}, [KERNEL-FINDINGS KF320]). The record used to
 * refuse a negative digit count, which A12 ADMITS — and since {@code FieldTypeSpecReader} builds one of
 * these for every declared number field, that refusal made a legal stored model impossible to inspect. And
 * it compared the pair only when BOTH members were set, where A12 reads an absent member as 0 and the
 * emitter writes one, so {@code minFractionalDigits(2)} with no maximum was accepted here and refused by
 * the kernel — a trap in the other direction, which is why both are fixed together rather than only the
 * over-rejection.
 */
class NumberDigitPairReadTest {

    private static FieldTypeSpec.NumberSpec digits(Integer min, Integer max) {
        return FieldTypeSpec.NumberSpec.builder()
                .minFractionalDigits(min)
                .maxFractionalDigits(max)
                .build();
    }

    @Test
    void aNegativeDigitCountIsReadRatherThanRefused() {
        assertEquals(-1, digits(null, -1).maxFractionalDigits());
        assertEquals(-1, digits(-1, 2).minFractionalDigits());
    }

    /** A negative maximum takes the pair out of A12's comparison, so it does not invert the range either. */
    @Test
    void aNegativeMaximumDoesNotMakeAPositiveMinimumAnInvertedRange() {
        assertDoesNotThrow(() -> digits(1, -1));
    }

    /** An ABSENT maximum reads as 0, which is what A12 compares against and what the emitter writes. */
    @Test
    void aMinimumWithNoMaximumIsTheInversionA12Refuses() {
        assertEquals("minFractionalDigits 2 > maxFractionalDigits 0",
                assertThrows(IllegalArgumentException.class, () -> digits(2, null)).getMessage());
    }

    @Test
    void anOrdinaryPairAndTheBoundaryAreStillAccepted() {
        assertDoesNotThrow(() -> digits(null, 2));
        assertDoesNotThrow(() -> digits(2, 2));
        assertDoesNotThrow(() -> digits(null, null));
    }

    /** The value range is untouched by all of this: equal bounds pass, an inverted pair does not. */
    @Test
    void theValueRangeKeepsItsOwnLaw() {
        assertDoesNotThrow(() -> FieldTypeSpec.NumberSpec.builder()
                .minValue(new BigDecimal("5")).maxValue(new BigDecimal("5")).build());
        assertThrows(IllegalArgumentException.class, () -> FieldTypeSpec.NumberSpec.builder()
                .minValue(new BigDecimal("10")).maxValue(new BigDecimal("5")).build());
    }

    /** The reader's own path, which is where an over-strict record costs a model its readability. */
    @Test
    void aStoredModelDeclaringANegativeScaleStaysInspectable() {
        var document = ModelDocument.read("""
                {"header":{"id":"catalog","modelType":"document","modelVersion":"28.4.0",
                  "locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Amount","Field":{"fieldType":{
                      "type":"NumberType","NumberType":{"maxFractionalDigits":-1}}}}
                  ]}}
                 ]}}}
                """);
        assertDoesNotThrow(document::authoredState);
    }
}
