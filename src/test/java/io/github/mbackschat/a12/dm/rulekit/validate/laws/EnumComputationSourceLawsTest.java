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
 * KERNEL-FINDINGS §11 — what a computation whose TARGET is an enumeration may compute from.
 *
 * <p>{@code NormalisierteBerechnungsFormHelper.checkBerechnungsFunktion} adds one gate for an enum-valued
 * computed field that no other target kind carries: the operation must be a string constant the target
 * declares, a {@code FieldValue} read of an enumeration whose values the target covers, or a
 * {@code FirstFilledValue} over such reads — and nothing else at all. The kernel's own note says why it is
 * stricter than the other kinds: a computed value outside the domain has no display value to show.
 *
 * <p><b>Coverage runs one way, and a category is read as its own token set.</b> A narrower source is
 * admitted and a wider one refused, so this is subset containment rather than equality; a category access
 * substitutes the category's tokens for the field's, which is the one spelling that can rescue a source the
 * target does not cover.
 *
 * <p><b>The display half rides along</b>, reusing the same {@code checkDisplayValues} the comparison walk
 * uses ([KF407](../../../../../../../../../../../docs/KERNEL-FINDINGS.md)) — so a labelled source and an
 * unlabelled target are incomparable here for the same reason they are there.
 *
 * <p>Own domain (a policy administration desk).
 */
class EnumComputationSourceLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String REFUSED = "MVK_ERROR_INVALID_ENUM_CALCULATION";

    private static final String POLICY = DocumentModelDraft.named("policy-enum-computation").locale("en_US")
            .rootGroup("Policy", p -> p
                    .stringField("Reference", f -> { })
                    .stringField("Note", f -> { })
                    .numberField("Amount", f -> f.fractionDigits(0))
                    .enumField("Status", e -> e.value("OPEN").value("CLOSED"))
                    .enumField("Mirror", e -> e.value("OPEN").value("CLOSED"))
                    .enumField("Narrow", e -> e.value("OPEN"))
                    .enumField("Wider", e -> e.value("OPEN").value("CLOSED").value("VOID"))
                    .enumField("Labelled", e -> e
                            .value("OPEN", l -> l.label("en_US", "Open case"))
                            .value("CLOSED", l -> l.label("en_US", "Closed case")))
                    .enumField("Banded", e -> e.value("OPEN").value("CLOSED").value("VOID")
                            .category("Band", "OPEN", "CLOSED", "OPEN")
                            .category("Wide", "OPEN", "CLOSED", "VOID")))
            .build().toJson();

    @Test
    void whatAnEnumValuedComputationMayComputeFrom() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a declared value of the target — the control", computing("\\\"OPEN\\\""));
        actual.put("a value the target does not declare", computing("\\\"VOID\\\""));
        actual.put("an enumeration declaring exactly the target's values", computing("[Mirror]"));
        actual.put("an enumeration declaring FEWER", computing("[Narrow]"));
        actual.put("an enumeration declaring MORE", computing("[Wider]"));
        actual.put("a LABELLED enumeration against an unlabelled target", computing("[Labelled]"));
        actual.put("a category whose tokens the target covers", computing("[Banded -> Band]"));
        actual.put("a category whose tokens it does not", computing("[Banded -> Wide]"));
        actual.put("a text field", computing("[Note]"));
        actual.put("a number field", computing("[Amount]"));
        actual.put("FirstFilledValue over two covered enumerations",
                computing("FirstFilledValue(Mirror, Narrow)"));
        actual.put("FirstFilledValue where one is not covered", computing("FirstFilledValue(Mirror, Wider)"));
        actual.put("a string OPERATION rather than a read", computing("[Mirror] + \\\"\\\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a declared value of the target — the control", KernelLaws.ADMITTED);
        // FOUR rows are answered by the PARSER before the calc module runs, and they are what bounds this
        // gate's reach: a constant outside the domain, a labelled source, and a wrong-KIND source are all
        // classes the comparison walk already draws, because a computation is lowered to a comparison
        // against its target.
        expected.put("a value the target does not declare", "MVK_INVALID_STRING_CONSTANT_FOR_ENUM_COMPARISON");
        expected.put("an enumeration declaring exactly the target's values", KernelLaws.ADMITTED);
        // Containment runs one way, so the pair of rows is the measurement rather than either alone.
        expected.put("an enumeration declaring FEWER", KernelLaws.ADMITTED);
        expected.put("an enumeration declaring MORE", REFUSED);
        expected.put("a LABELLED enumeration against an unlabelled target",
                "MVK_INVALID_COMPARE_ENUM_TO_STRING");
        expected.put("a category whose tokens the target covers", KernelLaws.ADMITTED);
        expected.put("a category whose tokens it does not", REFUSED);
        expected.put("a text field", REFUSED);
        expected.put("a number field", "MVK_INVALID_COMPARE_TO_ENUM_OR_STRING");
        expected.put("FirstFilledValue over two covered enumerations", KernelLaws.ADMITTED);
        expected.put("FirstFilledValue where one is not covered", REFUSED);
        // The gate is an allow-list of THREE operation kinds, so every other operation is refused whatever
        // it would produce — a concatenation of the target's own declared value included.
        expected.put("a string OPERATION rather than a read", REFUSED);
        assertEquals(expected, actual, "what an enum-valued computation may compute from");
    }

    /** The same operations computing into a TEXT field, which carries no such gate. */
    @Test
    void theSameSourcesIntoATextTarget() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a value no enumeration declares", computingInto("../Note", "\\\"ANYTHING\\\""));
        actual.put("an enumeration declaring MORE than the target ever could",
                computingInto("../Note", "[Wider]"));
        actual.put("a string operation", computingInto("../Note", "[Mirror] + \\\"\\\""));

        Map<String, String> expected = new LinkedHashMap<>();
        // The control that makes the grid above a law about the TARGET's kind rather than about the
        // operations: every one of these is refused into the enumeration and admitted into the text field.
        expected.put("a value no enumeration declares", KernelLaws.ADMITTED);
        expected.put("an enumeration declaring MORE than the target ever could", KernelLaws.ADMITTED);
        expected.put("a string operation", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "the same sources into a text target");
    }

    private static String computing(String operation) {
        return computingInto("../Status", operation);
    }

    /** The model with one computation writing [operation] into [target]. */
    private static String computingInto(String target, String operation) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode computation = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            computation.put("type", "Computation").put("id", "cst").put("name", "DerivedStatus");
            ObjectNode body = computation.putObject("Computation");
            body.put("computedFieldRelPath", target);
            body.putArray("computationAlternatives").addObject()
                    .put("operation", operation.replace("\\\"", "\""));
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "the derived status");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
