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
 * A computation's {@code additionalParserInformation}, the free-text parser preamble that replaced the
 * {@code errorCodesToSuppress} list in document-model format 29.8.0 (A12K-4372).
 *
 * <p>A12 validates the text on its own, as a preamble and nothing else ({@code DocumentModelService}
 * parses it as {@code preambleSnippet}), and a preamble that fails stops the rest of the computation's checks.
 * Blank text counts as absent, and the legacy list is folded into the text only when the text is blank
 * ({@code Computation._internal_foldLegacyErrorCodesToSuppress}). Each suppressing row has a control without the
 * preamble that draws the class it suppresses, so an admitted row cannot be an admitted model that never needed
 * the suppression. {@link ComputationSuppressionLawsTest} measures the legacy list itself.
 *
 * <p>Own domain (an insurance premium quote).
 */
class ComputationParserInformationLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SCALE = "MVK_INVALID_COMPARE_DEC_PLACES";
    private static final String ENUM = "MVK_ERROR_INVALID_ENUM_CALCULATION";

    private static final String QUOTE = DocumentModelDraft.named("premium-quote").locale("en_US")
            .rootGroup("Quote", quote -> quote
                    .numberField("Rate", n -> n.fractionDigits(2))
                    .numberField("Factor", n -> n.fractionDigits(2))
                    .numberField("Premium", n -> n.fractionDigits(2))
                    .stringField("PlanName", s -> { })
                    .enumField("Plan", e -> e.value("BASIC").value("PLUS")))
            .build().toJson();

    /** A scale-4 product into a scale-2 field: the static scale gate fires unless the preamble suppresses it. */
    private static final String SCALED = "[Rate] * [Factor]";

    @Test
    void whatA12DoesWithEachPreamble() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("absent, the scale gate fires — the control", scaled(null));
        actual.put("the scale gate suppressed", scaled("@SuppressWarning(" + SCALE + ")"));
        actual.put("empty text", scaled(""));
        actual.put("blank text", scaled("  "));
        actual.put("two supported codes", scaled("@SuppressWarning(" + SCALE + "," + ENUM + ")"));
        actual.put("the same code twice", scaled("@SuppressWarning(" + SCALE + "," + SCALE + ")"));
        actual.put("spaces inside the directive", scaled("@ SuppressWarning ( " + SCALE + " )"));
        actual.put("an unsupported code", scaled("@SuppressWarning(MVK_TOTALLY_BOGUS)"));
        actual.put("a supported code beside an unsupported one", scaled("@SuppressWarning(" + SCALE + ",MVK_TOTALLY_BOGUS)"));
        actual.put("no code", scaled("@SuppressWarning()"));
        actual.put("a bare code, no directive", scaled(SCALE));
        actual.put("the preamble followed by a condition", scaled("@SuppressWarning(" + SCALE + ") FieldFilled(Rate)"));
        actual.put("the German directive in an English model", scaled("@UnterdrueckeWarnung(" + SCALE + ")"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("absent, the scale gate fires — the control", SCALE);
        expected.put("the scale gate suppressed", KernelLaws.ADMITTED);
        expected.put("empty text", SCALE);
        expected.put("blank text", SCALE);
        expected.put("two supported codes", KernelLaws.ADMITTED);
        expected.put("the same code twice", KernelLaws.ADMITTED);
        expected.put("spaces inside the directive", KernelLaws.ADMITTED);
        expected.put("an unsupported code", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("a supported code beside an unsupported one", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("no code", "MVK_UNEXPECTED_TOKEN");
        expected.put("a bare code, no directive", "MVK_UNEXPECTED_TOKEN");
        expected.put("the preamble followed by a condition", "MVK_UNEXPECTED_TOKEN");
        expected.put("the German directive in an English model", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "what A12 does with each additionalParserInformation");
    }

    /** The directive is spelled in the model's condition language, one variable per pair. */
    @Test
    void theDirectiveIsSpelledInTheConditionLanguage() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("German directive, German model", scaledIn("de_DE", "@UnterdrueckeWarnung(" + SCALE + ")", null));
        actual.put("English directive, German model", scaledIn("de_DE", "@SuppressWarning(" + SCALE + ")", null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("German directive, German model", KernelLaws.ADMITTED);
        expected.put("English directive, German model", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "which directive spelling each condition language reads");
    }

    /** The text wins over the legacy list, which A12 folds in only when the text is blank. */
    @Test
    void theLegacyListIsFoldedOnlyWhenTheTextIsBlank() {
        String bogus = "[\"MVK_TOTALLY_BOGUS\"]";
        String supported = "[\"" + SCALE + "\"]";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a suppressing text beside a bogus list", scaledIn("en_US", "@SuppressWarning(" + SCALE + ")", bogus));
        actual.put("blank text beside a bogus list", scaledIn("en_US", " ", bogus));
        actual.put("blank text beside a suppressing list", scaledIn("en_US", " ", supported));
        actual.put("a suppressing list, German model", scaledIn("de_DE", null, supported));
        actual.put("two supported codes in the list", scaledIn("en_US", null, "[\"" + SCALE + "\",\"" + ENUM + "\"]"));
        actual.put("a suppressing text beside a list that is a bare string",
                scaledIn("en_US", "@SuppressWarning(" + SCALE + ")", "\"" + SCALE + "\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a suppressing text beside a bogus list", KernelLaws.ADMITTED);
        expected.put("blank text beside a bogus list", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("blank text beside a suppressing list", KernelLaws.ADMITTED);
        expected.put("a suppressing list, German model", KernelLaws.ADMITTED);
        expected.put("two supported codes in the list", KernelLaws.ADMITTED);
        expected.put("a suppressing text beside a list that is a bare string", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "when the legacy errorCodesToSuppress list is read");
    }

    /** The second suppressible class: a text computed into an enumeration. */
    @Test
    void anEnumerationCalculationIsSuppressible() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a text into an enumeration — the control", computing("../Plan", "[PlanName]", "en_US", null, null));
        actual.put("the same, suppressed",
                computing("../Plan", "[PlanName]", "en_US", "@SuppressWarning(" + ENUM + ")", null));
        actual.put("the same, the scale gate suppressed instead",
                computing("../Plan", "[PlanName]", "en_US", "@SuppressWarning(" + SCALE + ")", null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a text into an enumeration — the control", ENUM);
        expected.put("the same, suppressed", KernelLaws.ADMITTED);
        expected.put("the same, the scale gate suppressed instead", ENUM);
        assertEquals(expected, actual, "whether an enumeration calculation is suppressible");
    }

    /** The shapes that are not a string, the reader's tier. */
    @Test
    void aPreambleThatIsNotAString() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an explicit null", computingRaw("null"));
        actual.put("a number", computingRaw("7"));
        actual.put("a list", computingRaw("[\"@SuppressWarning(" + SCALE + ")\"]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an explicit null", SCALE);
        expected.put("a number", "MVK_UNEXPECTED_TOKEN");
        expected.put("a list", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "an additionalParserInformation that is not a string");
    }

    private static String scaled(String preamble) {
        return scaledIn("en_US", preamble, null);
    }

    private static String scaledIn(String conditionLanguage, String preamble, String legacyList) {
        return computing("../Premium", SCALED, conditionLanguage, preamble, legacyList);
    }

    private static String computingRaw(String rawPreamble) {
        ObjectNode root = withComputation("../Premium", SCALED, "en_US", null, null);
        try {
            body(root).set("additionalParserInformation", MAPPER.readTree(rawPreamble));
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the raw fragment must parse", unreadable);
        }
        return KernelLaws.verdictOf(root.toString());
    }

    private static String computing(String target, String operation, String conditionLanguage, String preamble,
            String legacyList) {
        return KernelLaws.verdictOf(withComputation(target, operation, conditionLanguage, preamble, legacyList).toString());
    }

    /** The quote with one computation of [operation] into [target], carrying the given members when not null. */
    private static ObjectNode withComputation(String target, String operation, String conditionLanguage,
            String preamble, String legacyList) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(QUOTE);
            ((ObjectNode) root.path("content").path("modelConfig").path("conditionLanguage"))
                    .put("code", conditionLanguage);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cpremium").put("name", "PremiumCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", target);
            body.putArray("computationAlternatives").addObject().put("operation", operation);
            if (preamble != null) {
                body.put("additionalParserInformation", preamble);
            }
            if (legacyList != null) {
                body.set("errorCodesToSuppress", MAPPER.readTree(legacyList));
            }
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the premium as quoted");
            return root;
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static ObjectNode body(ObjectNode root) {
        return (ObjectNode) root.path("content").path("modelRoot").path("rootGroups").get(0).path("Group")
                .path("elements").path(root.path("content").path("modelRoot").path("rootGroups").get(0)
                        .path("Group").path("elements").size() - 1).path("Computation");
    }
}
