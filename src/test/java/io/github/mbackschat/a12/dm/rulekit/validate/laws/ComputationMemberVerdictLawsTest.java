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
 * Whether a COMPUTATION's two DSL members are judged independently, or one stops the other.
 *
 * <p>The third carrier for the question {@link RuleMemberVerdictWithholdingLawsTest} and
 * {@link ConditionMultipleDefectLawsTest} ask, and the one a portable checker reads with the most families —
 * a precondition and an operation, each carrying references and syntax. {@link FieldTypeMultipleDefectLawsTest}
 * is why this cannot be inferred: the stop holds on two members here and not on a third, so each carrier is
 * measured rather than reasoned from its neighbour.
 *
 * <p>Own domain (a courier depot).
 */
class ComputationMemberVerdictLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEPOT = DocumentModelDraft.named("courier-depot").locale("en_US")
            .rootGroup("Depot", depot -> depot
                    .numberField("Weight", f -> f.fractionDigits(0, 2))
                    .numberField("Base", f -> f.fractionDigits(0, 2))
                    .stringField("Label", f -> { })
                    .stringField("Note", f -> { })
                    .enumField("Status", e -> e.value("OPEN").value("CLOSED")))
            .build().toJson();

    @Test
    void whetherABrokenPreconditionStopsTheOperationBesideIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both members sound — the control", verdict("FieldFilled(Base)", "[Base] + 1"));
        actual.put("the PRECONDITION names nothing", verdict("FieldFilled(NoSuchField)", "[Base] + 1"));
        actual.put("the OPERATION names nothing", verdict("FieldFilled(Base)", "[NoSuchField] + 1"));
        actual.put("BOTH name nothing", verdict("FieldFilled(NoSuchField)", "[AlsoMissing] + 1"));
        actual.put("a sound precondition, an operation that does not parse",
                verdict("FieldFilled(Base)", "[Base] +"));
        actual.put("a precondition naming nothing, an operation that does not parse",
                verdict("FieldFilled(NoSuchField)", "[Base] +"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both members sound — the control", KernelLaws.ADMITTED);
        expected.put("the PRECONDITION names nothing", "MVK_INVALID_ENTITY");
        expected.put("the OPERATION names nothing", "MVK_INVALID_ENTITY");
        // One class for two bad names in two different MEMBERS, as within one condition text.
        expected.put("BOTH name nothing", "MVK_INVALID_ENTITY");
        expected.put("a sound precondition, an operation that does not parse", "MVK_INCOMPLETE_INPUT");
        // The row that settles the granularity: the unresolvable name is in the PRECONDITION and the syntax
        // failure in the OPERATION, and the syntax class is still withheld. So resolution-before-syntax is
        // per DECLARATION, not per text — which a checker gating one text at a time would get wrong.
        expected.put("a precondition naming nothing, an operation that does not parse", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "whether a broken precondition stops the operation beside it");
    }

    /**
     * Whether a class drawn DURING the parse withholds the two the calculation module draws once the operation
     * parsed: a read of the computed field itself, and an enumeration target given a source it cannot take.
     *
     * <p>Each defect sits in the operation or in the precondition, because a parse-time class in the other
     * member withholds a syntax class across that boundary (the test above), and the calculation module reads
     * both members. The self-read computes into a TEXT field, where no implicit scale comparison can answer
     * ahead of it ([KF181](../../../../../../../../../../../docs/KERNEL-FINDINGS.md)).
     */
    @Test
    void whetherAParseTimeClassWithholdsTheCalculationModulesClasses() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a read of the computed field — the control",
                verdictInto("../Note", "FieldFilled(Base)", "[Note] + \"x\""));
        actual.put("the same read, a rounding gate in the operation",
                verdictInto("../Note", "FieldFilled(Base)", "[Note] + RoundUpValue(Label, 2)"));
        actual.put("the same read, an arity gate in the precondition",
                verdictInto("../Note", "FieldFilled(Base, Label)", "[Note] + \"x\""));
        actual.put("the same read, a name in the operation that resolves to nothing",
                verdictInto("../Note", "FieldFilled(Base)", "[Note] + [NoSuchField]"));
        actual.put("a text into an enumeration — the control",
                verdictInto("../Status", "FieldFilled(Base)", "[Label]"));
        actual.put("the same text, an arity gate in the precondition",
                verdictInto("../Status", "FieldFilled(Base, Label)", "[Label]"));
        actual.put("the same text, a name in the precondition that resolves to nothing",
                verdictInto("../Status", "FieldFilled(NoSuchField)", "[Label]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a read of the computed field — the control", "MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD");
        expected.put("the same read, a rounding gate in the operation", "MVK_NO_NUMBER");
        expected.put("the same read, an arity gate in the precondition", "MVK_PARAMSIZE_INVALID1");
        expected.put("the same read, a name in the operation that resolves to nothing", "MVK_INVALID_ENTITY");
        expected.put("a text into an enumeration — the control", "MVK_ERROR_INVALID_ENUM_CALCULATION");
        expected.put("the same text, an arity gate in the precondition", "MVK_PARAMSIZE_INVALID1");
        expected.put("the same text, a name in the precondition that resolves to nothing", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "whether a parse-time class withholds the calculation module's classes");
    }

    /**
     * Where a suppression's class stands against the texts' own: A12 renders {@code errorCodesToSuppress} as
     * an {@code @SuppressWarning(…)} preamble in front of the generated condition and parses the two as one
     * text ({@code ComputationSuppressionLawsTest}), so the member is not obviously beside the DSL at all.
     */
    @Test
    void whereASuppressionsClassStandsAgainstTheTexts() {
        String bogus = "[\"MVK_TOTALLY_BOGUS\"]";
        String twice = "[\"MVK_INVALID_COMPARE_DEC_PLACES\",\"MVK_INVALID_COMPARE_DEC_PLACES\"]";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an unsupported name, sound texts — the control",
                verdictInto("../Weight", "FieldFilled(Base)", "[Base] + 1", bogus));
        actual.put("an unsupported name, an operation naming nothing",
                verdictInto("../Weight", "FieldFilled(Base)", "[NoSuchField] + 1", bogus));
        actual.put("an unsupported name, a precondition naming nothing",
                verdictInto("../Weight", "FieldFilled(NoSuchField)", "[Base] + 1", bogus));
        actual.put("an unsupported name, a rounding gate in the operation",
                verdictInto("../Weight", "FieldFilled(Base)", "RoundUpValue(Label, 2)", bogus));
        actual.put("an unsupported name, a read of the computed field",
                verdictInto("../Note", "FieldFilled(Base)", "[Note] + \"x\"", bogus));
        actual.put("two entries, sound texts — the control",
                verdictInto("../Weight", "FieldFilled(Base)", "[Base] + 1", twice));
        actual.put("two entries, an operation naming nothing",
                verdictInto("../Weight", "FieldFilled(Base)", "[NoSuchField] + 1", twice));
        actual.put("two entries, a rounding gate in the operation",
                verdictInto("../Weight", "FieldFilled(Base)", "RoundUpValue(Label, 2)", twice));
        // The message is judged only beside a sound DSL (KF338), so these say which side the preamble is on.
        actual.put("sound texts, a control character in the message — the control",
                verdictInto("../Weight", "FieldFilled(Base)", "[Base] + 1", null, TAB));
        actual.put("an unsupported name, a control character in the message",
                verdictInto("../Weight", "FieldFilled(Base)", "[Base] + 1", bogus, TAB));
        actual.put("two entries, a control character in the message",
                verdictInto("../Weight", "FieldFilled(Base)", "[Base] + 1", twice, TAB));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an unsupported name, sound texts — the control", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("an unsupported name, an operation naming nothing", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("an unsupported name, a precondition naming nothing", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("an unsupported name, a rounding gate in the operation", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("an unsupported name, a read of the computed field", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("two entries, sound texts — the control", "MVK_UNEXPECTED_TOKEN");
        expected.put("two entries, an operation naming nothing", "MVK_UNEXPECTED_TOKEN");
        expected.put("two entries, a rounding gate in the operation", "MVK_UNEXPECTED_TOKEN");
        expected.put("sound texts, a control character in the message — the control", "INVALID_CHARACTERS");
        expected.put("an unsupported name, a control character in the message", "MVK_INVALID_SUPPRESSED_WARNING");
        expected.put("two entries, a control character in the message", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "where a suppression's class stands against the texts'");
    }

    /** The depot with one computation into `Weight`, carrying [precondition] and [operation]. */
    private static String verdict(String precondition, String operation) {
        return verdictInto("../Weight", precondition, operation);
    }

    /** …into [target]. */
    private static String verdictInto(String target, String precondition, String operation) {
        return verdictInto(target, precondition, operation, null);
    }

    private static final String TAB = "see" + (char) 0x09 + "it";

    /** …suppressing [suppressed], a raw JSON list, when it is not null. */
    private static String verdictInto(String target, String precondition, String operation, String suppressed) {
        return verdictInto(target, precondition, operation, suppressed, "see it");
    }

    /** …with [message] as its one text. */
    private static String verdictInto(String target, String precondition, String operation, String suppressed,
            String message) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(DEPOT);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cprobe").put("name", "WeightCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", target);
            ObjectNode alternative = body.putArray("computationAlternatives").addObject();
            alternative.put("precondition", precondition).put("operation", operation);
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", message);
            if (suppressed != null) {
                body.set("errorCodesToSuppress", MAPPER.readTree(suppressed));
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
