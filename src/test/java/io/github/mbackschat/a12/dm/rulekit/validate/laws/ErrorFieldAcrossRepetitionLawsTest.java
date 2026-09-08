package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;

/**
 * KERNEL-FINDINGS §9 — is {@code MVK_ERROR_FIELD_AND_ITERATION_INVALID} the general law the ledger states, or
 * one operator's?
 *
 * <p>The ledger reads "an indirectly referenced error field must be reachable without crossing further
 * repeatable groups", which is phrased operator-independently. Its only measurement is a SINGLE row in
 * {@code RepetitionNotUniqueReferenceBoundaryLawsTest} — "in key group | bare | @From scope" — so
 * {@code RepetitionNotUnique} and its {@code @From} marker are constant across the entire evidence for the
 * code, and that class states its own limit explicitly.
 *
 * <p>This asks what an ORDINARY rule reports for the same structural shape: an error field two repeatable
 * levels below the rule's group, reached without any operator-specific scope marker. <b>It is admitted</b> —
 * at every depth measured — so the ledger's operator-independent phrasing is too broad, and a client-side
 * pre-check written from it would refuse rules the kernel accepts. The code is the RNU shape's, not
 * placement's.
 */
class ErrorFieldAcrossRepetitionLawsTest {

    /** Two nested repeatable levels, so an error field can sit across a further repetition boundary. */
    private static final String MODEL = DocumentModelDraft.named("error-site-across-repetition").locale("en_US")
            .rootGroup("Doc", d -> d
                    .stringField("Reference", s -> { })
                    .group("Outer", o -> o.repeatable(20).indexField("OuterKey")
                            .field("OuterKey", FieldKind.STRING)
                            .stringField("OuterNote", s -> { })
                            .group("Inner", i -> i.repeatable(20).indexField("InnerKey")
                                    .field("InnerKey", FieldKind.STRING)
                                    .stringField("InnerNote", s -> { }))))
            .build().toJson();

    private static String verdict(String group, String errorField, String condition) {
        ValidationResult r = KernelLaws.checkModel(MODEL, group, errorField, condition);
        return r.ok() ? "admitted"
                : r.diagnostics().stream().map(d -> d.code().orElse("?")).findFirst().orElse("?");
    }

    /**
     * The measurement. Each row places the error field one repetition boundary further from the rule's group
     * than the row above, with an ordinary condition throughout — no {@code @From}, no
     * {@code RepetitionNotUnique}.
     *
     * <p>The conditions are POSITIVE presence deliberately: an unguarded negative trips
     * {@code MVK_NEG_CONDITION_IN_ITERATION} first and masks the error-site analysis entirely, which is what
     * the first draft of this class measured.
     */
    @Test
    void anOrdinaryRulesErrorFieldAcrossRepetitionBoundaries() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("error field in the rule's own repeatable group",
                verdict("/Doc/Outer", "../OuterNote", "FieldFilled(OuterNote)"));
        actual.put("error field one level deeper than the rule group",
                verdict("/Doc/Outer", "../Inner/InnerNote", "FieldFilled(Inner/InnerNote)"));
        actual.put("error field two levels below a non-repeatable rule group",
                verdict("/Doc", "../Outer/Inner/InnerNote", "FieldFilled(Outer/Inner/InnerNote)"));

        Map<String, String> expected = new LinkedHashMap<>();
        // All admitted. An ordinary rule's error field crosses repetition boundaries freely, so the code
        // is NOT the general law the ledger states — it belongs to the RepetitionNotUnique/@From
        // indirect-reference shape its single measured row uses.
        expected.put("error field in the rule's own repeatable group", "admitted");
        expected.put("error field one level deeper than the rule group", "admitted");
        expected.put("error field two levels below a non-repeatable rule group", "admitted");
        assertEqualsVerbose(expected, actual);
    }

    private static void assertEqualsVerbose(Map<String, String> expected, Map<String, String> actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual,
                "whether this code is the general law the ledger states, or RepetitionNotUnique's alone");
    }
}
