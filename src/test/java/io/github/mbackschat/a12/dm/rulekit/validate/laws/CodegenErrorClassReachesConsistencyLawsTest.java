package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * Reachability law — <b>{@code checkConsistency} re-runs the validation CODE GENERATOR, so the generator's own
 * {@code NoMetaModelCheckErrorType} classes reach an ordinary authoring gate.</b>
 *
 * <p>This exists because [`MVK-LEDGER`](../../../../../../../../../docs/MVK-LEDGER.md)'s scope boundary
 * asserted the opposite for six weeks — "unreachable from our surface because we never invoke codegen" — and
 * that sentence was a reachability claim carried on a reasonable-sounding mechanism with no run behind it. The
 * mechanism is {@code CodeGenerationRule} (kernel-md-model consistency rules), which extends
 * {@code DocumentModelConsistencyRule} and calls {@code generateValidationCode} from {@code checkModel},
 * turning every generator notification into a consistency problem. Reported by a12-kernel-lean and re-derived
 * here before the ledger was corrected — a peer's measurement is their report, not our pin.
 *
 * <p>Two controls make the refusal the charset restriction's rather than the declaration's: the same enum with
 * no restriction is admitted, and an all-uppercase spelling under the same restriction is admitted. The
 * per-CHARACTER cardinality is asserted too, because a consumer counting diagnostics as defects overcounts a
 * single offending value fourfold.
 *
 * <p>Own domain (subscription add-ons).
 */
class CodegenErrorClassReachesConsistencyLawsTest {

    private static final String INVALID_CHARACTER = "MVK_ENUM_VALUE_WITH_INVALID_CHARACTER";

    @Test
    void aRestrictedCharsetRefusesAnEnumValueThroughTheGeneratorsOwnErrorClass() {
        ModelConsistencyException refusal = assertThrows(ModelConsistencyException.class,
                () -> BoundModel.ofJson(model("A-Z", "ALPHA", "beta")));

        String message = String.valueOf(refusal.getMessage());
        assertTrue(message.contains(INVALID_CHARACTER),
                () -> "a NoMetaModelCheckErrorType class must reach checkConsistency: " + message);
        assertEquals(4, occurrences(message, INVALID_CHARACTER),
                () -> "one diagnostic per offending CHARACTER of 'beta', not per value: " + message);
    }

    @Test
    void theSameEnumIsAdmittedWithNoCharsetRestriction() {
        assertEquals(emptyVerdict(), verdict(model(null, "ALPHA", "beta")),
                "the restriction is what refuses, not the lowercase value");
    }

    @Test
    void aConformingSpellingIsAdmittedUnderTheSameRestriction() {
        assertEquals(emptyVerdict(), verdict(model("A-Z", "ALPHA", "BETA")),
                "the value is what refuses, not the restriction");
    }

    private static String model(String supportedCharacter, String... values) {
        DocumentModelDraft.Builder builder = DocumentModelDraft.named("addon-charset").locale("en_US");
        if (supportedCharacter != null) {
            builder.supportedCharacters(supportedCharacter);
        }
        return builder
                .rootGroup("Addon", g -> g.enumField("Tier", e -> {
                    for (String value : values) {
                        e.value(value);
                    }
                }))
                .build().toJson();
    }

    private static String emptyVerdict() {
        return "ADMITTED";
    }

    private static String verdict(String modelJson) {
        try {
            BoundModel.ofJson(modelJson);
            return emptyVerdict();
        } catch (ModelConsistencyException e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static int occurrences(String haystack, String needle) {
        Matcher matcher = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
