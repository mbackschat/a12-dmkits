package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * WHICH condition class an unresolvable name withholds, and on what it depends — correcting the mechanism
 * {@link ConditionMultipleDefectLawsTest} recorded.
 *
 * <p>That file measured three syntax classes, found each one absent beside an unresolvable reference, and
 * concluded that resolution runs first and wins. <b>Every pair it held put the bad name FIRST in the text</b>,
 * so "resolution wins" and "the earlier defect in the text wins" made identical predictions on all of them,
 * and the second account is the true one: reverse the position and the syntax class comes back
 * ({@code an unexpected token, bad name after}), while a bad name placed ahead of a class that file never
 * paired withholds that one too ({@code mixed connectives, bad name BEFORE}).
 *
 * <p>Two classes do not compete for a position, and that is the split this grid exists to draw:
 *
 * <table>
 *   <caption>what an unresolvable name does to each condition class</caption>
 *   <tr><th>class</th><th>bad name BEFORE it</th><th>bad name after it</th></tr>
 *   <tr><td>every syntax and operand class below</td><td>withheld</td><td>still reported</td></tr>
 *   <tr><td>{@code MVK_ERROR_FIELD_NOT_REFERENCED}, {@code MVK_ERROR_INVALID_LANGUAGE_FEATURE}</td>
 *       <td>withheld</td><td><b>withheld</b></td></tr>
 * </table>
 *
 * <p>Both members of the second row are relations over the condition's resolved references rather than a
 * defect at some offset in it — which is a plausible reason for the difference and is <b>not</b> measured
 * here; what is measured is that their position does not matter and the others' does.
 *
 * <p>Own domain (a claims policy).
 */
class ConditionClassWithholdingLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Score", f -> { })
                    .group("Assessment", assessment -> assessment.stringField("Notes", f -> { }))
                    .group("Archive", archive -> { }))
            .rootGroup("Registry", registry -> registry.stringField("Code", f -> { }))
            .build().toJson();

    /** Appended to a defective condition, so the pair differs in exactly one term. */
    private static final String AND_A_BAD_NAME = " And FieldFilled(NoSuchField)";

    @Test
    void whichConditionClassAnUnresolvableNameWithholds() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        actual.put("sound — the control", verdict("FieldFilled(Reference)"));
        expected.put("sound — the control", KernelLaws.ADMITTED);
        actual.put("a bad name alone", verdict("FieldFilled(NoSuchField)"));
        expected.put("a bad name alone", "MVK_INVALID_ENTITY");

        // The unterminated literal swallows whatever follows it, so this class gets no after-row: appended,
        // the bad name would sit inside the literal and never be a reference at all.
        actual.put("an unlexable literal", verdict("FieldFilled(\"Reference)"));
        expected.put("an unlexable literal", "MVK_LEXER_STANDARD_ERROR");
        actual.put("an unlexable literal, bad name BEFORE",
                verdict("FieldFilled(NoSuchField) And FieldFilled(\"Reference)"));
        expected.put("an unlexable literal, bad name BEFORE", "MVK_INVALID_ENTITY");

        // The pair that falsifies "resolution runs first and wins": one text, one bad name, two positions,
        // two different classes.
        String mixed = "FieldFilled(Reference) And FieldFilled(Reference) Or FieldFilled(Reference)";
        measure(actual, expected, "mixed connectives", mixed, "MVK_BRACKET_MISSING");

        measure(actual, expected, "an empty group named", "GroupFilled(/Policy/Archive)",
                "MVK_GROUP_IS_EMPTY");
        measure(actual, expected, "a group named twice",
                "AllGroupsFilled(/Policy/Assessment, /Policy/Assessment)", "MVK_DUPLICATE_PARAM1");
        measure(actual, expected, "a ROOT group as an operand",
                "AllGroupsFilled(/Registry, /Policy/Assessment)", "MVK_ROOT_GROUP_REFERENCED");
        measure(actual, expected, "a field where a group belongs",
                "GroupFilled(/Policy/Assessment/Notes)", "MVK_NO_FIELDS_ALLOWED");
        measure(actual, expected, "a star on a fixed group", "GroupFilled(/Policy/Assessment*)",
                "MVK_INVALID_WILDCARD");

        // The reversal for the classes ConditionMultipleDefectLawsTest could only ask one way round.
        // MVK_INCOMPLETE_INPUT gets no row at all: the class says the text ended early, so no second defect
        // can sit after it and its reversed pair does not exist.
        actual.put("an unexpected token, bad name after",
                verdict("FieldFilled(Reference) And And FieldFilled(NoSuchField)"));
        expected.put("an unexpected token, bad name after", "MVK_UNEXPECTED_TOKEN");
        // Its alone-row draws MVK_EXPECTED_TOKEN_NOT_FOUND, so appending changed the parse and this is not
        // that class's reversal — it is a second text carrying a syntax defect ahead of a bad name.
        actual.put("an unclosed bracket, bad name after",
                verdict("FieldFilled(Reference And FieldFilled(NoSuchField)"));
        expected.put("an unclosed bracket, bad name after", "MVK_UNEXPECTED_TOKEN");

        // The first of the two position-independent classes. Appending a bad name leaves the error field just
        // as unreferenced, and the class goes anyway.
        actual.put("an error field the condition does not name",
                targetedVerdict("FieldFilled(Reference)", "../Score"));
        expected.put("an error field the condition does not name", "MVK_ERROR_FIELD_NOT_REFERENCED");
        actual.put("an unnamed error field, bad name after",
                targetedVerdict("FieldFilled(Reference)" + AND_A_BAD_NAME, "../Score"));
        expected.put("an unnamed error field, bad name after", "MVK_INVALID_ENTITY");
        actual.put("an unnamed error field, bad name BEFORE",
                targetedVerdict(badNameBefore("FieldFilled(Reference)"), "../Score"));
        expected.put("an unnamed error field, bad name BEFORE", "MVK_INVALID_ENTITY");

        // The second one, on the computation carrier, which is where a precondition's app condition lives.
        String appCondition = "FieldFilled(Reference) And CustomCondition NotReverse";
        actual.put("a sound precondition — the control", preconditionVerdict("FieldFilled(Reference)"));
        expected.put("a sound precondition — the control", KernelLaws.ADMITTED);
        actual.put("a bad name in a precondition", preconditionVerdict("FieldFilled(NoSuchField)"));
        expected.put("a bad name in a precondition", "MVK_INVALID_ENTITY");
        actual.put("an app condition in a precondition", preconditionVerdict(appCondition));
        expected.put("an app condition in a precondition", "MVK_ERROR_INVALID_LANGUAGE_FEATURE");
        actual.put("an app condition, bad name after", preconditionVerdict(appCondition + AND_A_BAD_NAME));
        expected.put("an app condition, bad name after", "MVK_INVALID_ENTITY");
        actual.put("an app condition, bad name BEFORE", preconditionVerdict(badNameBefore(appCondition)));
        expected.put("an app condition, bad name BEFORE", "MVK_INVALID_ENTITY");

        // The bad name is not the only thing that withholds them. Every operand row above leaves the error
        // target unreferenced as well and A12 named the operand class alone, so the relational classes lose
        // to an ordinary text defect too. Measured here on the other carrier rather than transferred.
        actual.put("an app condition beside an empty group",
                preconditionVerdict("GroupFilled(/Policy/Archive) And CustomCondition NotReverse"));
        expected.put("an app condition beside an empty group", "MVK_GROUP_IS_EMPTY");
        actual.put("an app condition AHEAD of an empty group",
                preconditionVerdict("CustomCondition NotReverse And GroupFilled(/Policy/Archive)"));
        expected.put("an app condition AHEAD of an empty group", "MVK_GROUP_IS_EMPTY");

        assertEquals(expected, actual, "which condition class an unresolvable name withholds");
    }

    /** One class in three rows: alone, with the bad name after it, and with the bad name ahead of it. */
    private static void measure(Map<String, String> actual, Map<String, String> expected, String row,
            String condition, String alone) {
        actual.put(row, verdict(condition));
        expected.put(row, alone);
        actual.put(row + ", bad name after", verdict(condition + AND_A_BAD_NAME));
        expected.put(row + ", bad name after", alone);
        actual.put(row + ", bad name BEFORE", verdict(badNameBefore(condition)));
        expected.put(row + ", bad name BEFORE", "MVK_INVALID_ENTITY");
    }

    private static String badNameBefore(String condition) {
        return "FieldFilled(NoSuchField) And " + condition;
    }

    private static String verdict(String condition) {
        return targetedVerdict(condition, "../Reference");
    }

    private static String targetedVerdict(String condition, String errorTarget) {
        return verdictOf(elements -> {
            ObjectNode node = elements.addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", condition).put("errorEntityRelPath", errorTarget)
                    .put("errorCode", "C1").put("severity", "ERROR");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "see it");
        });
    }

    private static String preconditionVerdict(String precondition) {
        return verdictOf(elements -> {
            ObjectNode node = elements.addObject();
            node.put("type", "Computation").put("id", "cprobe").put("name", "ScoreCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Score");
            ObjectNode alternative = body.putArray("computationAlternatives").addObject();
            alternative.put("precondition", precondition).put("operation", "1");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "see it");
        });
    }

    private static String verdictOf(Consumer<ArrayNode> declaration) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            declaration.accept((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements"));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
