package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

import org.junit.jupiter.api.Test;

/**
 * KERNEL-FINDINGS §13 — three gates on the rule-message surface that a UNIFORM reading gets wrong, each
 * measured against a within-model twin differing in one variable: the template's character repertoire is the
 * BMP and not ASCII, the field-membership gate is skipped by two name-position forms, and the value suffix has
 * a second gate on the referenced field's DECLARATION.
 *
 * <p><b>Why each of the three refuses a legal model if read uniformly.</b> An ASCII template bound refuses
 * Greek, Cyrillic and CJK message text the kernel admits. A membership rule stated without exception refuses
 * two admitted forms — and the exception is not a quirk of the fixture: `checkVordruckzeile` /
 * `checkVordruckname` call only `checkValidFieldName`, never the membership check the plain form goes through.
 * And a value suffix gated only on the non-starred reference misses the declaration gate entirely.
 *
 * <p>Reported by a12-kernel-lean's `SPEC-2026-09-07-12`; re-derived here on our own fixtures, since a peer's
 * measurement is their report and not a pin. Recorded as [KF279]. The rest of this surface — the category
 * suffix, the base-year terminal, the semantic-index key, the quoting rule — is
 * {@link RuleMessageParameterLawsTest}, whose `fieldMembershipBindsEveryFieldFormIncludingTheCategoryOne` row
 * is the plain-form control the bypass rows below are measured against.
 */
class RuleMessageAdmissionGateLawsTest {

    private static final String GROUP = "/Policy";
    private static final String ERROR_FIELD = "../Reference";
    private static final String ADMITTED = KernelLaws.ADMITTED;

    private static final String MODEL = model();
    private static final BoundModel M = BoundModel.ofJson(MODEL);

    /** Reads {@code Reference} ALONE, so every parameter naming another field is outside the condition. */
    private static final String READS_REFERENCE = render(Dsl.fieldFilled(M.field("/Policy/Reference")));

    /** Reads the value-validation-free field too, so the value suffix's declaration gate is the only one left. */
    private static final String READS_FREEFORM = render(Dsl.fieldFilled(M.field("/Policy/Reference"))
            .and(Dsl.fieldFilled(M.field("/Policy/Freeform"))));

    private static String verdict(String condition, String message) {
        return KernelLaws.messageVerdict(MODEL, GROUP, ERROR_FIELD, condition, "en_US", message);
    }

    /**
     * The template's repertoire is the BMP. The four admitted rows span three scripts and the replacement
     * character; the refusal is a supplementary-plane code point, which is where the BMP boundary lies and the
     * one place a surrogate pair appears in UTF-16. The model declares no `supportedCharacters`, so the
     * effective charset is the BMP by the empty-list rule rather than by an authored restriction.
     */
    @Test
    void theTemplateRepertoireIsTheBmpRatherThanAscii() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("ASCII — the baseline", verdict(READS_REFERENCE, "see the reference"));
        verdicts.put("Greek", verdict(READS_REFERENCE, "see αβγ"));
        verdicts.put("Cyrillic", verdict(READS_REFERENCE, "see док"));
        verdicts.put("CJK", verdict(READS_REFERENCE, "see 注意"));
        verdicts.put("the replacement character", verdict(READS_REFERENCE, "see �"));
        verdicts.put("a supplementary-plane code point", verdict(READS_REFERENCE, "see 😀"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("ASCII — the baseline", ADMITTED);
        expected.put("Greek", ADMITTED);
        expected.put("Cyrillic", ADMITTED);
        expected.put("CJK", ADMITTED);
        expected.put("the replacement character", ADMITTED);
        // CODELESS, and the text is the RUNTIME formal charset class's ("unsupported signs" =
        // ZeichenNichtImZeichensatz) rather than the checker's own WRONG_ENCODING — the peer's row, confirmed.
        expected.put("a supplementary-plane code point",
                "(no code: Rule with path '/Policy/ProbeRule': "
                        + "The field contains one or several unsupported signs.)");

        assertEquals(expected, verdicts,
                "an ASCII template bound would refuse four legal models; the refusal that does exist carries "
                        + "NO class and is identified only by its text");
    }

    /**
     * The two form-line suffixes skip field membership. One condition, one field, three suffixes — so the
     * plain form's `INVALID_FIELD` and the two admissions differ in exactly the suffix.
     */
    @Test
    void theTwoFormLineSuffixesSkipTheFieldMembershipGateThePlainFormMeets() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("plain — the membership control",
                verdict(READS_REFERENCE, "see $/Policy/Adjuster$"));
        verdicts.put("Vordruckzeile", verdict(READS_REFERENCE, "see $/Policy/Adjuster.Vordruckzeile$"));
        verdicts.put("Vordruckname", verdict(READS_REFERENCE, "see $/Policy/Adjuster.Vordruckname$"));
        verdicts.put("Vordruckzeile on a GROUP", verdict(READS_REFERENCE, "see $/Policy/Claims.Vordruckzeile$"));
        verdicts.put("Vordruckname on a GROUP", verdict(READS_REFERENCE, "see $/Policy/Claims.Vordruckname$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("plain — the membership control", "INVALID_FIELD");
        expected.put("Vordruckzeile", ADMITTED);
        expected.put("Vordruckname", ADMITTED);
        // Skipping MEMBERSHIP is not skipping every gate: `checkValidFieldName` still asks whether the
        // parameter resolved to a FIELD, which is the only check these two forms do make (added 2026-09-15).
        expected.put("Vordruckzeile on a GROUP", "INVALID_FIELD_IN_PARAMETER");
        expected.put("Vordruckname on a GROUP", "INVALID_FIELD_IN_PARAMETER");

        assertEquals(expected, verdicts,
                "a checker enforcing membership uniformly across name-position forms refuses two admitted ones");
    }

    /**
     * The value suffix's second gate is on the DECLARATION, not on the reference: a field declared
     * `noValueValidation` refuses the suffix while its byte-identical twin without the flag admits it. The
     * plain form on the same field is the control that keeps the refusal about the suffix.
     */
    @Test
    void theValueSuffixHasADeclarationGateBesideItsReferenceGate() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("value on a value-validation-free field",
                verdict(READS_FREEFORM, "see $/Policy/Freeform.value$"));
        verdicts.put("plain on the same field — the control",
                verdict(READS_FREEFORM, "see $/Policy/Freeform$"));
        verdicts.put("value on its ordinary twin", verdict(READS_REFERENCE, "see $/Policy/Reference.value$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("value on a value-validation-free field", "INVALID_NO_VALUE_VALIDATION");
        expected.put("plain on the same field — the control", ADMITTED);
        expected.put("value on its ordinary twin", ADMITTED);

        assertEquals(expected, verdicts, "the gate reads the field's declaration, not the parameter's shape");
    }

    private static String render(Condition condition) {
        return KernelLaws.render(condition);
    }

    /**
     * A claims policy. `Adjuster` is never read by the conditions here, which is what makes it the membership
     * subject; `Freeform` carries `noValueValidation` (with the line breaks the kernel requires beside it,
     * MVK_STRING_TYPE_NO_VAL_AND_NO_LINE_BREAK) and `Reference` is its ordinary twin.
     */
    private static String model() {
        return DocumentModelDraft.named("message-gates").locale("en_US")
                .rootGroup("Policy", policy -> policy
                        .stringField("Reference", f -> { })
                        .stringField("Freeform", f -> f.noValueValidation().lineBreaks())
                        .stringField("Adjuster", f -> { })
                        // A GROUP to name in the form-line position, which is the one place this surface
                        // asks whether the parameter resolved to a FIELD at all.
                        .group("Claims", claims -> claims.stringField("Payout", f -> { })))
                .build().toJson();
    }
}
