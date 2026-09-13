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
                    .numberField("Base", f -> f.fractionDigits(0, 2)))
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

    /** The depot with one computation into `Weight`, carrying [precondition] and [operation]. */
    private static String verdict(String precondition, String operation) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(DEPOT);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cprobe").put("name", "WeightCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Weight");
            ObjectNode alternative = body.putArray("computationAlternatives").addObject();
            alternative.put("precondition", precondition).put("operation", operation);
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "see it");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
