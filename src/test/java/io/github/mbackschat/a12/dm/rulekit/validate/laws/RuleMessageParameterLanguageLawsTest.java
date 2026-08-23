package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition;
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer;

/**
 * KERNEL-FINDINGS §13 — the rule error-text <b>parameter</b> grammar is bilingual, it follows the model's
 * declared CONDITION language rather than its display locale, and exactly five of its terminals differ
 * between the two ([KF198]).
 *
 * <p><b>Why this matters more than a spelling table.</b> The other language's spelling of a
 * <em>name-position</em> terminal is not a syntax error — it falls through to an ENTITY lookup
 * ({@code INVALID_ENTITY}), so on a model that happens to declare a field of that name it would silently
 * resolve the field instead of the terminal. That is a wrong value, not a refusal. A
 * <em>keyword-position</em> terminal ({@code For}/{@code Fuer}) fails to parse instead and cannot bind
 * silently, which is why the two halves of the table are asserted separately.
 *
 * <p>The rule-group pair is the sharpest instrument here: the OWN-language spelling reaches
 * {@code INVALID_PARAMETER} (recognised as the terminal, refused in this position) while the other's reaches
 * {@code INVALID_ENTITY}, so one model pair discriminates recognition from lookup in both directions.
 *
 * <p>The monolingual half of [KF198] — the category suffix, the Base Year offsets, the quoting exemption and
 * the semantic-index key — is {@link RuleMessageParameterLawsTest}.
 */
class RuleMessageParameterLanguageLawsTest {

    private static final String ADMITTED = KernelLaws.ADMITTED;
    private static final String NOT_AN_ENTITY = "INVALID_ENTITY";
    private static final String RECOGNISED_BUT_REFUSED_HERE = "INVALID_PARAMETER";
    private static final String DOES_NOT_PARSE = "MVK_UNEXPECTED_TOKEN";

    // ---- the English model, condition language en_US -------------------------------------------

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US").baseYear(2020)
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(0, 2))
                    .group("Claims", claims -> claims.repeatable(20).indexField("ClaimNo")
                            .stringField("ClaimNo", f -> { })
                            .stringField("Payout", f -> { })))
            .build().toJson();

    private static final BoundModel EN = BoundModel.ofJson(POLICY);

    private static final String EN_READS_PREMIUM = renderEn(Dsl.fieldFilled(EN.field("/Policy/Reference"))
            .and(Dsl.fieldFilled(EN.field("/Policy/Premium"))));
    private static final String EN_READS_KEYED = renderEn(Dsl.fieldFilled(EN.field("/Policy/Reference"))
            .and(EN.field("/Policy/Claims/Payout").semanticIndex("C-1").notFilled()));
    private static final String EN_READS_ROW = renderEn(Dsl.fieldFilled(EN.field("/Policy/Claims/ClaimNo"))
            .and(Dsl.fieldNotFilled(EN.field("/Policy/Claims/Payout"))));

    // ---- the German model, condition language de_DE --------------------------------------------

    /**
     * The condition language is a model-config declaration, NOT the display locale — a {@code de_DE} model
     * still parses English-keyword conditions until this is set, which is why the fixture sets it explicitly
     * through the surface that owns it.
     */
    private static final String VERTRAG = ModelDocument.read(
            DocumentModelDraft.named("schadenvertrag").locale("de_DE").baseYear(2020)
                    .rootGroup("Vertrag", vertrag -> vertrag
                            .stringField("Referenz", f -> { })
                            .numberField("Beitrag", f -> f.fractionDigits(0, 2))
                            .group("Schaeden", schaeden -> schaeden.repeatable(20).indexField("SchadenNr")
                                    .stringField("SchadenNr", f -> { })
                                    .stringField("Auszahlung", f -> { })))
                    .build().toJson())
            .setConditionLanguage("de_DE").toJson();

    private static final BoundModel DE = BoundModel.ofJson(VERTRAG);

    private static final String DE_READS_BEITRAG = renderDe(Dsl.fieldFilled(DE.field("/Vertrag/Referenz"))
            .and(Dsl.fieldFilled(DE.field("/Vertrag/Beitrag"))));
    private static final String DE_READS_KEYED = renderDe(Dsl.fieldFilled(DE.field("/Vertrag/Referenz"))
            .and(DE.field("/Vertrag/Schaeden/Auszahlung").semanticIndex("S-1").notFilled()));
    private static final String DE_READS_ROW = renderDe(Dsl.fieldFilled(DE.field("/Vertrag/Schaeden/SchadenNr"))
            .and(Dsl.fieldNotFilled(DE.field("/Vertrag/Schaeden/Auszahlung"))));

    // ---- the three name-position terminals -------------------------------------------------------

    /** {@code value} / {@code Wert}: each language admits its own and looks the other's up as a field. */
    @Test
    void theValueSuffixFollowsTheConditionLanguage() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("en model, .value", en(EN_READS_PREMIUM, "see $Premium.value$"));
        verdicts.put("en model, .Wert", en(EN_READS_PREMIUM, "see $Premium.Wert$"));
        verdicts.put("de model, .Wert", de(DE_READS_BEITRAG, "siehe $Beitrag.Wert$"));
        verdicts.put("de model, .value", de(DE_READS_BEITRAG, "siehe $Beitrag.value$"));

        assertEquals(Map.of(
                "en model, .value", ADMITTED,
                "en model, .Wert", NOT_AN_ENTITY,
                "de model, .Wert", ADMITTED,
                "de model, .value", NOT_AN_ENTITY), verdicts);
    }

    /** {@code BaseYear} / {@code Referenzjahr}, with the offset form carried along. */
    @Test
    void theBaseYearTerminalFollowsTheConditionLanguage() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("en model, $BaseYear$", en(EN_READS_PREMIUM, "see $BaseYear$"));
        verdicts.put("en model, $Referenzjahr$", en(EN_READS_PREMIUM, "see $Referenzjahr$"));
        verdicts.put("de model, $Referenzjahr$", de(DE_READS_BEITRAG, "siehe $Referenzjahr$"));
        verdicts.put("de model, $Referenzjahr+3$", de(DE_READS_BEITRAG, "siehe $Referenzjahr+3$"));
        verdicts.put("de model, $BaseYear$", de(DE_READS_BEITRAG, "siehe $BaseYear$"));

        assertEquals(Map.of(
                "en model, $BaseYear$", ADMITTED,
                "en model, $Referenzjahr$", NOT_AN_ENTITY,
                "de model, $Referenzjahr$", ADMITTED,
                "de model, $Referenzjahr+3$", ADMITTED,
                "de model, $BaseYear$", NOT_AN_ENTITY), verdicts);
    }

    /**
     * {@code RuleGroup} / {@code RegelKontext} — the pair that separates recognition from lookup. Neither
     * spelling is admitted at a rule-owned parameter, but the OWN-language one is recognised as the terminal
     * and refused in this position, while the other's is looked up as an entity and not found.
     */
    @Test
    void theOtherLanguagesSpellingBecomesAnEntityLookupRatherThanASyntaxError() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("en model, $RuleGroup$", en(EN_READS_ROW, "see $RuleGroup$", "/Policy/Claims", "../Payout"));
        verdicts.put("en model, $RegelKontext$",
                en(EN_READS_ROW, "see $RegelKontext$", "/Policy/Claims", "../Payout"));
        verdicts.put("de model, $RegelKontext$",
                de(DE_READS_ROW, "siehe $RegelKontext$", "/Vertrag/Schaeden", "../Auszahlung"));
        verdicts.put("de model, $RuleGroup$",
                de(DE_READS_ROW, "siehe $RuleGroup$", "/Vertrag/Schaeden", "../Auszahlung"));

        assertEquals(Map.of(
                "en model, $RuleGroup$", RECOGNISED_BUT_REFUSED_HERE,
                "en model, $RegelKontext$", NOT_AN_ENTITY,
                "de model, $RegelKontext$", RECOGNISED_BUT_REFUSED_HERE,
                "de model, $RuleGroup$", NOT_AN_ENTITY), verdicts);
    }

    /**
     * …and the root-group index pair, {@code RootGroup} / {@code LfdNr}, is not reachable from a rule-owned
     * parameter in EITHER language: both spellings are looked up as entities. The rule-group rows above are
     * the positive control that this fixture CAN recognise a non-field terminal, so this is a measured
     * absence rather than a fixture that reaches nothing.
     */
    @Test
    void theRootGroupIndexPairIsNotReachableFromARuleOwnedParameter() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("en model, $RootGroup$", en(EN_READS_ROW, "see $RootGroup$", "/Policy/Claims", "../Payout"));
        verdicts.put("en model, $LfdNr$", en(EN_READS_ROW, "see $LfdNr$", "/Policy/Claims", "../Payout"));
        verdicts.put("de model, $LfdNr$", de(DE_READS_ROW, "siehe $LfdNr$", "/Vertrag/Schaeden", "../Auszahlung"));
        verdicts.put("de model, $RootGroup$",
                de(DE_READS_ROW, "siehe $RootGroup$", "/Vertrag/Schaeden", "../Auszahlung"));

        assertEquals(Map.of(
                "en model, $RootGroup$", NOT_AN_ENTITY,
                "en model, $LfdNr$", NOT_AN_ENTITY,
                "de model, $LfdNr$", NOT_AN_ENTITY,
                "de model, $RootGroup$", NOT_AN_ENTITY), verdicts);
    }

    // ---- the one keyword-position terminal -------------------------------------------------------

    /**
     * {@code For} / {@code Fuer} sits in KEYWORD position, so the other language's spelling fails to parse
     * instead of becoming a lookup — this pair carries no wrong-value risk.
     */
    @Test
    void theSemanticIndexKeywordFailsToParseInTheOtherLanguage() {
        Map<String, String> verdicts = new LinkedHashMap<>();
        verdicts.put("en model, For", en(EN_READS_KEYED, "see $Claims/Payout For \"C-1\"$"));
        verdicts.put("en model, Fuer", en(EN_READS_KEYED, "see $Claims/Payout Fuer \"C-1\"$"));
        verdicts.put("de model, Fuer", de(DE_READS_KEYED, "siehe $Schaeden/Auszahlung Fuer \"S-1\"$"));
        verdicts.put("de model, For", de(DE_READS_KEYED, "siehe $Schaeden/Auszahlung For \"S-1\"$"));

        assertEquals(Map.of(
                "en model, For", ADMITTED,
                "en model, Fuer", DOES_NOT_PARSE,
                "de model, Fuer", ADMITTED,
                "de model, For", DOES_NOT_PARSE), verdicts);
    }

    // ---- fixture ---------------------------------------------------------------------------------

    private static String en(String condition, String message) {
        return en(condition, message, "/Policy", "../Reference");
    }

    private static String en(String condition, String message, String group, String errorField) {
        return KernelLaws.messageVerdict(POLICY, group, errorField, condition, "en_US", message);
    }

    private static String de(String condition, String message) {
        return de(condition, message, "/Vertrag", "../Referenz");
    }

    private static String de(String condition, String message, String group, String errorField) {
        return KernelLaws.messageVerdict(VERTRAG, group, errorField, condition, "de_DE", message);
    }

    private static String renderEn(Condition condition) {
        return new DslRenderer(ConditionLanguage.EN).render(condition);
    }

    private static String renderDe(Condition condition) {
        return new DslRenderer(ConditionLanguage.DE).render(condition);
    }
}
