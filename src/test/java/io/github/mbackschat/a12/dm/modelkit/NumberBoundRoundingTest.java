package io.github.mbackschat.a12.dm.modelkit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * {@code NumberSpec}'s value range, held to the comparison A12 makes: with no fractional digit allowed, both
 * bounds are rounded half away from zero before they are compared ([KERNEL-FINDINGS KF569], measured in
 * {@code adapter.laws.CheckerDeclarationAdmissionAgreementTest}). The record compared the written values, so it
 * refused {@code 1.4} over {@code 1} — a declaration A12 admits — while authoring, and on the read path too,
 * since {@code FieldTypeSpecReader} builds one for every declared number field.
 */
class NumberBoundRoundingTest {

    private static String verdict(String min, String max, Integer maxFractionalDigits) {
        try {
            FieldTypeSpec.NumberSpec.builder().maxFractionalDigits(maxFractionalDigits)
                    .minValue(new BigDecimal(min)).maxValue(new BigDecimal(max)).build();
            return "admitted";
        } catch (IllegalArgumentException refused) {
            return "refused";
        }
    }

    @Test
    void theBoundsAreComparedAsA12ComparesThem() {
        var observed = new LinkedHashMap<String, String>();
        observed.put("1.4 over 1, no fraction", verdict("1.4", "1", 0));
        observed.put("-1.5 over -1.6, no fraction", verdict("-1.5", "-1.6", 0));
        observed.put("1.4 over 1, maxFractionalDigits absent", verdict("1.4", "1", null));
        observed.put("1.5 over 1.4, no fraction", verdict("1.5", "1.4", 0));
        observed.put("1.04 over 1, one fraction digit", verdict("1.04", "1", 1));
        observed.put("1.4 over 1, maxFractionalDigits -1", verdict("1.4", "1", -1));
        assertEquals(Map.of(
                "1.4 over 1, no fraction", "admitted",
                "-1.5 over -1.6, no fraction", "admitted",
                "1.4 over 1, maxFractionalDigits absent", "admitted",
                "1.5 over 1.4, no fraction", "refused",
                "1.04 over 1, one fraction digit", "refused",
                "1.4 over 1, maxFractionalDigits -1", "refused"), observed);
    }

    @Test
    void aStoredModelWithRoundedEqualBoundsStaysInspectable() {
        var document = ModelDocument.read("""
                {"header":{"id":"policy","modelType":"document","modelVersion":"28.4.0",
                  "locales":[{"code":"en_US"}]},
                 "content":{"modelRoot":{"rootGroups":[
                  {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
                    {"type":"Field","id":"f0","name":"Instalments","Field":{"fieldType":{
                      "type":"NumberType","NumberType":{"maxFractionalDigits":0,"minValue":1.4,"maxValue":1}}}}
                  ]}}
                 ]}}}
                """);
        assertDoesNotThrow(document::authoredState);
    }
}
