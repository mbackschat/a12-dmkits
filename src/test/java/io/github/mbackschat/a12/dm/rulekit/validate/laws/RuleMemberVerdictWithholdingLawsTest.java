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
 * Whether A12 reports on a rule's two DSL-bearing members INDEPENDENTLY, or withholds one when the other
 * fails.
 *
 * <p>{@link MessageParameterConditionDependencyLawsTest} found one such withholding — the error text is not
 * examined at all once the declaration's own DSL is unsound — and the question it raises is how far that
 * goes. A checker built as one family per member reports whatever each member shows, so every conditional
 * check A12 performs is an over-rejection waiting on the right input, and the only way to know is to break
 * two members at once and count the classes.
 *
 * <p><b>Every withheld row has a sound-target twin</b>, because "the class was withheld" and "this fixture
 * never draws that class" are otherwise indistinguishable — the error-text classes are measured elsewhere on
 * other fixtures, which is exactly the instrument the confound hides behind.
 *
 * <p><b>How far the withholding reaches was probed and is only partly settled.</b> An illegal rule NAME was
 * tried as the discriminator between "the whole declaration" and "its DSL members", and it moves two
 * variables — an illegal name makes the model UNBUILDABLE, a different tier — so it cannot isolate the
 * question and is not recorded as a row. Every member measured here is withheld, so the checker takes the
 * conservative reading and withholds the declaration's other findings too: that can only under-report.
 *
 * <p>Own domain (a claims policy).
 */
class RuleMemberVerdictWithholdingLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(0, 2)))
            .build().toJson();

    @Test
    void whetherABrokenErrorTargetWithholdsTheConditionsOwnVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both members sound — the control",
                verdict("FieldFilled(Reference)", "../Reference"));
        actual.put("the error TARGET names nothing", verdict("FieldFilled(Reference)", "../NoSuchField"));
        actual.put("the CONDITION names nothing", verdict("FieldFilled(NoSuchField)", "../Reference"));
        actual.put("BOTH name nothing", verdict("FieldFilled(NoSuchField)", "../NoSuchField"));
        // How far the withholding reaches: the error text is a third member, already known to be withheld
        // by an unsound condition. A broken TARGET is a different cause, so it needs its own row.
        actual.put("the error TARGET names nothing, a control character in the text",
                verdict("FieldFilled(Reference)", "../NoSuchField", "see" + (char) 0x09 + "it"));
        actual.put("the error TARGET names nothing, a parameter the condition does not read",
                verdict("FieldFilled(Reference)", "../NoSuchField", "see $Premium$"));
        // The twins that make the two rows above mean "withheld" rather than "this fixture never draws that
        // class": the same texts over a SOUND target. Without them the error-text classes are measured only
        // on other fixtures, and "suppressed" and "never fires here" are indistinguishable.
        actual.put("a sound target, the same control character — the twin",
                verdict("FieldFilled(Reference)", "../Reference", "see" + (char) 0x09 + "it"));
        actual.put("a sound target, the same parameter — the twin",
                verdict("FieldFilled(Reference)", "../Reference", "see $Premium$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both members sound — the control", KernelLaws.ADMITTED);
        expected.put("the error TARGET names nothing", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("the CONDITION names nothing", "MVK_INVALID_ENTITY");
        // The row that matters: the condition's own class is GONE. So A12 examines the error target first
        // and stops on it, and a checker judging the members independently reports a class it withheld.
        expected.put("BOTH name nothing", "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        // …and it stops for the error text too, under a cause entirely different from the unsound-DSL one
        // that withholds it elsewhere — so a broken target terminates the whole rule, not one member.
        expected.put("the error TARGET names nothing, a control character in the text",
                "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("the error TARGET names nothing, a parameter the condition does not read",
                "MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        expected.put("a sound target, the same control character — the twin", "INVALID_CHARACTERS");
        expected.put("a sound target, the same parameter — the twin", "INVALID_FIELD");
        assertEquals(expected, actual, "whether a broken error target withholds the condition's verdict");
    }

    /** The drafted policy with one rule injected, so the model is whole in every way but the two members. */
    private static String verdict(String condition, String errorField) {
        return verdict(condition, errorField, "see it");
    }

    private static String verdict(String condition, String errorField, String text) {
        return build(condition, errorField, text);
    }

    private static String build(String condition, String errorField, String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", condition).put("errorEntityRelPath", errorField)
                    .put("errorCode", "C1").put("severity", "ERROR");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
