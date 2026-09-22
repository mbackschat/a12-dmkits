package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A stored model spelling A12's own "no bound" sentinel stays readable through the kit.
 *
 * <p>A12 encodes an undefined length as {@code -1} ({@code IFieldType.UNDEFINED}), and it ADMITS a string
 * type declaring one — measured on every combination by
 * {@code rulekit.validate.laws.StringTypeParameterLawsTest}. {@code StringSpec}'s constructor refused it, so
 * a legal stored model could not be INSPECTED: {@code FieldTypeSpecReader} builds a {@code StringSpec} for
 * every declared string field, which is the sharper half of a client-side over-rejection and the one nothing
 * on the authoring side would have shown.
 *
 * <p>The trigger is {@link ModelDocument#authoredState()} rather than {@code read} — the spec is built when
 * field state is asked for, not when the bytes are parsed — and that is measured rather than assumed: an
 * earlier draft of this test asserted on {@code read} alone and passed against the unfixed code, which would
 * have published a reachability claim the fixture could not support.
 *
 * <p>The second row is the one a reader would not predict: {@code minLength: 2} with {@code maxLength: -1}
 * is a real minimum and NO maximum, and comparing the two as numbers reads it as an inverted range.
 */
class StringLengthSentinelReadTest {

    private static String modelWith(String stringTypeBody) {
        return """
                {"header":{"id":"catalog","modelType":"document","modelVersion":"28.4.0",
                  "locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Catalog","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{
                      "type":"StringType","StringType":%s}}}
                  ]}}
                 ]}}}
                """.formatted(stringTypeBody);
    }

    @Test
    void anUndefinedMinimumIsReadRatherThanRefused() {
        var document = ModelDocument.read(modelWith("{\"minLength\":-1,\"maxLength\":3}"));
        assertDoesNotThrow(document::authoredState);
    }

    @Test
    void aRealMinimumWithNoMaximumIsReadRatherThanRefused() {
        var document = ModelDocument.read(modelWith("{\"minLength\":2,\"maxLength\":-1}"));
        assertDoesNotThrow(document::authoredState);
    }

    /** The sentinel is PRESERVED, not normalized: the kit reports what the model declares. */
    @Test
    void theSentinelSurvivesTheRead() {
        var spec = new FieldTypeSpec.StringSpec(-1, 3, null, null, false, false, Map.of(), false,
                Map.of());
        assertEquals(-1, spec.minLength());
    }

    /** The SIBLING record carrying the same two members, fixed in the same pass and measured beside it. */
    @Test
    void theSameSentinelIsReadOnACustomFieldType() {
        var spec = new FieldTypeSpec.CustomSpec("iban", 2, -1);
        assertEquals(-1, spec.maxLength());
    }

    /** A genuinely inverted range — both bounds real — is still refused, which is what A12 does too. */
    @Test
    void anInvertedRangeOfTwoRealBoundsIsStillRefused() {
        assertEquals("minLength 5 > maxLength 3",
                org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                        () -> new FieldTypeSpec.StringSpec(5, 3, null, null, false, false, Map.of(),
                                false, Map.of())).getMessage());
    }
}
