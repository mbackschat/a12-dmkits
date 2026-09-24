package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * What A12 says about a message PARAMETER when the rule's own condition is invalid.
 *
 * <p>`FehlertextErrorType` carries a class for exactly this — {@code INVALID_CONDITION}, "a text with a
 * parameter can only be checked on rules with valid conditions" — which means the parameter's own verdict is
 * REPLACED rather than merely accompanied. A checker judging the two members independently would report a
 * parameter class A12 withheld, on every model that has a broken condition and a message beside it.
 *
 * <p>Own domain (a claims policy). The model is built whole rather than through
 * {@link KernelLaws#messageVerdict} because that helper asserts the condition is admitted first, which is
 * precisely the premise this file removes.
 */
class MessageParameterConditionDependencyLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(0, 2))
                    .group("Claims", claims -> claims.repeatable(9)
                            .numberField("Amount", f -> f.fractionDigits(0, 2))))
            .build().toJson();

    @Test
    void whatAParameterDrawsWhenTheConditionBesideItIsInvalid() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a valid condition, a parameter it does not read — the control",
                verdict("FieldFilled(Reference)", "../Reference", "see $Premium$"));
        actual.put("a condition naming nothing, a parameter it does not read",
                verdict("FieldFilled(NoSuchField)", "../Reference", "see $Premium$"));
        actual.put("a condition naming nothing, a plain text — the control",
                verdict("FieldFilled(NoSuchField)", "../Reference", "see the reference"));
        actual.put("a condition that does not PARSE, a parameter beside it",
                verdict("FieldFilled(", "../Reference", "see $Premium$"));
        // The sweep the parameter rows demand: does a broken condition withhold the message's CHARACTER
        // verdict too, or only the one that needs the condition's references? The two checks differ in
        // whether they depend on the condition at all, so the answer cannot be carried over.
        actual.put("a valid condition, a control character in the text — the control",
                verdict("FieldFilled(Reference)", "../Reference", "see" + (char) 0x09 + "the reference"));
        actual.put("a condition naming nothing, a control character in the text",
                verdict("FieldFilled(NoSuchField)", "../Reference", "see" + (char) 0x09 + "the reference"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a valid condition, a parameter it does not read — the control", "INVALID_FIELD");
        // The parameter's own class is WITHHELD, not merely accompanied: the condition's class is the whole
        // verdict, identical to the plain-text row beside it. So a checker judging the two members
        // independently reports a class A12 gave no sign of, on every model whose condition is half-typed —
        // which is the ordinary state of a model being edited, and exactly when a checker is asked.
        expected.put("a condition naming nothing, a parameter it does not read", "MVK_INVALID_ENTITY");
        expected.put("a condition naming nothing, a plain text — the control", "MVK_INVALID_ENTITY");
        // …and the same for a condition that does not parse, under that failure's own class.
        expected.put("a condition that does not PARSE, a parameter beside it", "MVK_INCOMPLETE_INPUT");
        // The character verdict is withheld by the SAME broken condition, so this is A12 declining to
        // examine the message member at all rather than a rule about parameters — which means every family
        // reading that member answers to it, not only the one that needs the condition's references.
        expected.put("a valid condition, a control character in the text — the control", "INVALID_CHARACTERS");
        expected.put("a condition naming nothing, a control character in the text", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "what a parameter draws when the condition beside it is invalid");
    }

    /**
     * A condition that PARSES and resolves but is refused: does its class withhold the message member too?
     * The rows above broke the condition by a name or by the grammar, which is where the checker's gate
     * stands; these move only the class, with the text held fixed per column.
     */
    @Test
    void whetherARefusedConditionThatResolvesWithholdsTheMessage() {
        String tab = "see" + (char) 0x09 + "the reference";
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> conditions = new LinkedHashMap<>();
        conditions.put("a starred aggregate — the control", "FieldFilled(Reference) And Sum(Claims*/Amount) > 0");
        conditions.put("a star on the field", "FieldFilled(Reference) And Sum(Claims/Amount*) > 0");
        conditions.put("an unstarred repetition read", "FieldFilled(Reference) And FieldFilled(Claims/Amount)");
        conditions.put("a star in a scalar slot", "FieldFilled(Reference) And FieldFilled(Claims*/Amount)");
        conditions.put("the error field unreferenced", "FieldFilled(Premium)");
        conditions.forEach((label, condition) -> {
            actual.put(label + " | a control character", verdict(condition, "../Reference", tab));
            actual.put(label + " | a parameter it does not read", verdict(condition, "../Reference", "see $Premium$"));
        });

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a starred aggregate — the control | a control character", "INVALID_CHARACTERS");
        expected.put("a starred aggregate — the control | a parameter it does not read", "INVALID_FIELD");
        expected.put("a star on the field | a control character", "MVK_INVALID_WILDCARD");
        expected.put("a star on the field | a parameter it does not read", "MVK_INVALID_WILDCARD");
        expected.put("an unstarred repetition read | a control character", "MVK_NO_WILDCARD");
        expected.put("an unstarred repetition read | a parameter it does not read", "MVK_NO_WILDCARD");
        expected.put("a star in a scalar slot | a control character", "MVK_NO_WILDCARDS_ALLOWED");
        expected.put("a star in a scalar slot | a parameter it does not read", "MVK_NO_WILDCARDS_ALLOWED");
        // A POST-parse class withholds it too, so the gate is not "the condition parsed and resolved".
        expected.put("the error field unreferenced | a control character", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("the error field unreferenced | a parameter it does not read", "MVK_ERROR_FIELD_NOT_REFERENCED");
        assertEquals(expected, actual, "whether a refused condition that resolves withholds the message");
    }

    /**
     * …and the same question on a COMPUTATION, whose unsound member is an operation rather than a condition.
     *
     * <p>The gate this file justifies reads a computation's members too, so those rows are a claim the rule
     * rows cannot support: a computation has no condition, and nothing says A12 stops examining its message
     * when its operation fails. Measured rather than carried across the carrier boundary.
     */
    @Test
    void whetherAComputationsMessageIsWithheldByItsOwnBrokenMember() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a valid operation, a control character in the text — the control",
                computing("1", "see" + (char) 0x09 + "the total"));
        actual.put("an operation naming nothing, a control character in the text",
                computing("[NoSuchField] + 1", "see" + (char) 0x09 + "the total"));
        actual.put("an operation naming nothing, a plain text — the control",
                computing("[NoSuchField] + 1", "see the total"));
        actual.put("a valid operation, a parameter it does not read — the control",
                computing("1", "see $Reference$"));
        actual.put("an operation naming nothing, a parameter beside it",
                computing("[NoSuchField] + 1", "see $Reference$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a valid operation, a control character in the text — the control", "INVALID_CHARACTERS");
        // The same withholding, on a carrier with no condition at all: the broken member is an OPERATION,
        // and both error-text verdicts give way to its class exactly as a rule's do. So the gate is "the
        // declaration's own DSL is sound", not anything about conditions.
        expected.put("an operation naming nothing, a control character in the text", "MVK_INVALID_ENTITY");
        expected.put("an operation naming nothing, a plain text — the control", "MVK_INVALID_ENTITY");
        expected.put("a valid operation, a parameter it does not read — the control", "INVALID_FIELD");
        expected.put("an operation naming nothing, a parameter beside it", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "whether a computation's message is withheld by its own broken member");

        // An operation that resolves but is refused, the carrier twin of the rule rows above.
        Map<String, String> refused = new LinkedHashMap<>();
        refused.put("a starred aggregate — the control | a control character",
                computing("Sum(Claims*/Amount)", "see" + (char) 0x09 + "the total"));
        refused.put("a star on the field | a control character",
                computing("Sum(Claims/Amount*)", "see" + (char) 0x09 + "the total"));
        refused.put("a star on the field | a parameter it does not read",
                computing("Sum(Claims/Amount*)", "see $Reference$"));
        refused.put("an unstarred repetition read | a control character",
                computing("[Claims/Amount] + 1", "see" + (char) 0x09 + "the total"));

        Map<String, String> refusedExpected = new LinkedHashMap<>();
        refusedExpected.put("a starred aggregate — the control | a control character", "INVALID_CHARACTERS");
        refusedExpected.put("a star on the field | a control character", "MVK_INVALID_WILDCARD");
        refusedExpected.put("a star on the field | a parameter it does not read", "MVK_INVALID_WILDCARD");
        refusedExpected.put("an unstarred repetition read | a control character", "MVK_NO_WILDCARD");
        assertEquals(refusedExpected, refused, "whether a refused operation that resolves withholds the message");
    }

    /** The drafted policy with one computation into `Premium`, carrying [operation] and [text]. */
    private static String computing(String operation, String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cprobe").put("name", "PremiumCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Premium");
            body.putArray("computationAlternatives").addObject().put("operation", operation);
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /**
     * A class about a rule member that is NOT its DSL — the name, the error code — beside the same message
     * defect, so the trigger's width is measured rather than guessed from the condition rows.
     */
    @Test
    void whetherAClassAboutAnotherRuleMemberWithholdsTheMessage() {
        String tab = "see" + (char) 0x09 + "the reference";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a reserved rule name, a plain text — the control", rule("vk_vkProbe", "C1", "see it"));
        actual.put("a reserved rule name, a control character", rule("vk_vkProbe", "C1", tab));
        actual.put("a reserved rule name, a parameter it does not read", rule("vk_vkProbe", "C1", "see $Premium$"));
        actual.put("a blank error code, a plain text — the control", rule("Probe", " ", "see it"));
        actual.put("a blank error code, a control character", rule("Probe", " ", tab));
        actual.put("a blank error code, a parameter it does not read", rule("Probe", " ", "see $Premium$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a reserved rule name, a plain text — the control", "MVK_RULE_NAME_INVALID");
        // ACCOMPANIED, not withheld: the member A12 declines to examine the message behind is the DSL, so a
        // class about the name or the code leaves the message's own verdict standing beside it.
        expected.put("a reserved rule name, a control character", "MVK_RULE_NAME_INVALID,INVALID_CHARACTERS");
        expected.put("a reserved rule name, a parameter it does not read", "MVK_RULE_NAME_INVALID,INVALID_FIELD");
        expected.put("a blank error code, a plain text — the control", "MVK_ERROR_CODE_MISSING");
        expected.put("a blank error code, a control character", "MVK_ERROR_CODE_MISSING,INVALID_CHARACTERS");
        expected.put("a blank error code, a parameter it does not read", "MVK_ERROR_CODE_MISSING,INVALID_FIELD");
        assertEquals(expected, actual, "whether a class about another rule member withholds the message");
    }

    /**
     * The Base Year terminal, a third message class beside the character and membership ones: the policy
     * declares no Base Year, so {@code $BaseYear$} draws {@code NO_BASE_YEAR} wherever A12 examines the text.
     * Measured on its own because the checker reports it from a family that also judges conditions, so the
     * withholding the other two classes get cannot be assumed to reach it.
     */
    @Test
    void whetherARefusedDslWithholdsTheBaseYearClass() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a sound condition — the control",
                verdict("FieldFilled(Reference)", "../Reference", "see $BaseYear$"));
        actual.put("a star on the field",
                verdict("FieldFilled(Reference) And Sum(Claims/Amount*) > 0", "../Reference", "see $BaseYear$"));
        actual.put("an unstarred repetition read",
                verdict("FieldFilled(Reference) And FieldFilled(Claims/Amount)", "../Reference", "see $BaseYear$"));
        actual.put("the error field unreferenced", verdict("FieldFilled(Premium)", "../Reference", "see $BaseYear$"));
        actual.put("a condition naming nothing", verdict("FieldFilled(NoSuchField)", "../Reference", "see $BaseYear$"));
        actual.put("the condition names the constant too",
                verdict("FieldFilled(Reference) And YearFromDate(BaseYear) > 2020", "../Reference", "see $BaseYear$"));
        actual.put("a reserved rule name beside it", rule("vk_vkProbe", "C1", "see $BaseYear$"));
        actual.put("a sound operation — the control", computing("1", "see $BaseYear$"));
        actual.put("a star on the operation's field", computing("Sum(Claims/Amount*)", "see $BaseYear$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a sound condition — the control", "NO_BASE_YEAR");
        expected.put("a star on the field", "MVK_INVALID_WILDCARD");
        expected.put("an unstarred repetition read", "MVK_NO_WILDCARD");
        expected.put("the error field unreferenced", "MVK_ERROR_FIELD_NOT_REFERENCED");
        expected.put("a condition naming nothing", "MVK_INVALID_ENTITY");
        // The constant refused in BOTH members is reported once, by the condition: its own class is a DSL class,
        // so it withholds the message's like any other.
        expected.put("the condition names the constant too", "MVK_NO_BASE_YEAR");
        expected.put("a reserved rule name beside it", "MVK_RULE_NAME_INVALID,NO_BASE_YEAR");
        expected.put("a sound operation — the control", "NO_BASE_YEAR");
        expected.put("a star on the operation's field", "MVK_INVALID_WILDCARD");
        assertEquals(expected, actual, "whether a refused DSL withholds the Base Year class");
    }

    /**
     * The rest of the message member's classes, beside the same sound and refused condition: two more
     * error-text ones, and the two that judge whether the member is PRESENT for each declared locale, which
     * the checker reports from a family it treats as independent of the DSL. A duplicate locale is not among
     * them: A12 admits it at this tier (`RuleSlotLawsTest`).
     */
    @Test
    void whichOtherMessageClassesARefusedConditionWithholds() {
        Map<String, String> conditions = new LinkedHashMap<>();
        conditions.put("sound", "FieldFilled(Reference)");
        conditions.put("refused", "FieldFilled(Reference) And FieldFilled(Claims/Amount)");
        Map<String, String> actual = new LinkedHashMap<>();
        conditions.forEach((label, condition) -> {
            actual.put(label + " | an unterminated parameter", messages(condition, false, "en_US", "see $Premium"));
            actual.put(label + " | a group parameter naming a descendant",
                    messages(condition, false, "en_US", "row $#/Policy/Claims$ needs it"));
            actual.put(label + " | no message entry", messages(condition, false));
            actual.put(label + " | a declared locale unanswered", messages(condition, true, "en_US", "see it"));
        });

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sound | an unterminated parameter", "WRONG_NUMBER_OF_DOLLARS");
        expected.put("sound | a group parameter naming a descendant", "INVALID_GROUP");
        expected.put("sound | no message entry", "MVK_ERROR_MESSAGE_MISSING,RULE_HAS_NO_ERRORTEXT");
        expected.put("sound | a declared locale unanswered",
                "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING,RULE_HAS_NO_ERRORTEXT");
        expected.put("refused | an unterminated parameter", "MVK_NO_WILDCARD");
        expected.put("refused | a group parameter naming a descendant", "MVK_NO_WILDCARD");
        // The member's PRESENCE is judged beside the refused condition, under its prefixed classes, while the
        // unprefixed error-text class that rides with them is withheld: the split is A12's own vocabulary, the
        // model-validation keys against `FehlertextErrorType`, and not which member the defect sits in.
        expected.put("refused | no message entry", "MVK_ERROR_MESSAGE_MISSING,MVK_NO_WILDCARD");
        expected.put("refused | a declared locale unanswered",
                "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING,MVK_NO_WILDCARD");
        assertEquals(expected, actual, "which other message classes a refused condition withholds");
    }

    /**
     * The drafted policy with one rule reporting on {@code Reference}, carrying the (locale, text) pairs in
     * [localeAndText] as its messages; [twoLocales] also declares {@code de_DE}, which none of them answers.
     */
    private static String messages(String condition, boolean twoLocales, String... localeAndText) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            if (twoLocales) {
                ((ArrayNode) root.path("header").path("locales")).addObject().put("code", "de_DE");
            }
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", condition).put("errorEntityRelPath", "../Reference")
                    .put("errorCode", "C1").put("severity", "ERROR");
            ArrayNode entries = body.putArray("errorMessage");
            for (int i = 0; i < localeAndText.length; i += 2) {
                entries.addObject().put("locale", localeAndText[i]).put("text", localeAndText[i + 1]);
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    /** The drafted policy with one rule injected, so the model is whole in every way but the condition. */
    private static String verdict(String condition, String errorField, String text) {
        return rule("Probe", "C1", condition, errorField, text);
    }

    /** A rule with a sound condition, varying the name and the error code. */
    private static String rule(String name, String errorCode, String text) {
        return rule(name, errorCode, "FieldFilled(Reference)", "../Reference", text);
    }

    private static String rule(String name, String errorCode, String condition, String errorField, String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", name);
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", condition).put("errorEntityRelPath", errorField)
                    .put("errorCode", errorCode).put("severity", "ERROR");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
