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
 * Whether ONE condition text carrying two independent defects draws both classes or stops at the first.
 *
 * <p>{@link RuleMemberVerdictWithholdingLawsTest} found that A12 stops examining a rule once its error target
 * fails. This asks the same question one level down, INSIDE a member: a portable checker runs a separate
 * family per defect kind over the same text, so if A12 stops at the first defect then every additional family
 * is an over-rejection on any condition carrying two.
 *
 * <p>The pairs are chosen to be independent of each other — a bad reference cannot cause a bad connective and
 * vice versa — so a missing class is a stop rather than a consequence.
 *
 * <p>Own domain (a claims policy).
 */
class ConditionMultipleDefectLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .stringField("Adjuster", f -> { }))
            .build().toJson();

    @Test
    void whetherOneConditionReportsEveryDefectOrStopsAtTheFirst() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("sound — the control", verdict("FieldFilled(Reference)"));
        actual.put("one unresolvable reference", verdict("FieldFilled(NoSuchField)"));
        actual.put("TWO unresolvable references",
                verdict("FieldFilled(NoSuchField) And FieldFilled(AlsoMissing)"));
        actual.put("one unresolvable reference and a trailing operator",
                verdict("FieldFilled(NoSuchField) And"));
        actual.put("a trailing operator alone", verdict("FieldFilled(Reference) And"));
        // Which side wins is the mechanism, and the rows above only show one direction. A text that cannot
        // be PARSED at all carries a bad reference inside it here, so it asks whether resolution still runs.
        actual.put("an unbalanced delimiter around a bad reference", verdict("FieldFilled(NoSuchField"));
        actual.put("an unbalanced delimiter around a GOOD reference", verdict("FieldFilled(Reference"));
        actual.put("a bad reference and an unexpected token",
                verdict("FieldFilled(NoSuchField) And And FieldFilled(Reference)"));
        actual.put("an unexpected token alone",
                verdict("FieldFilled(Reference) And And FieldFilled(Reference)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("sound — the control", KernelLaws.ADMITTED);
        expected.put("one unresolvable reference", "MVK_INVALID_ENTITY");
        // TWO bad references draw ONE class, not one per reference — so a reader emitting a finding per
        // reference says more than A12 did, though it says nothing A12 did not.
        expected.put("TWO unresolvable references", "MVK_INVALID_ENTITY");
        // The three rows that matter, each against the "alone" row below it: every syntax class fires on its
        // own and is WITHHELD when the same text also carries an unresolvable reference. Reference
        // resolution runs first and wins, so a checker running a family per defect kind over one text
        // reports classes A12 withheld on any condition carrying two.
        expected.put("one unresolvable reference and a trailing operator", "MVK_INVALID_ENTITY");
        expected.put("a trailing operator alone", "MVK_INCOMPLETE_INPUT");
        // …and resolution is not merely surviving a parse: this text cannot be parsed at all and the entity
        // class still wins, so the order is not "parse, then resolve".
        expected.put("an unbalanced delimiter around a bad reference", "MVK_INVALID_ENTITY");
        expected.put("an unbalanced delimiter around a GOOD reference", "MVK_EXPECTED_TOKEN_NOT_FOUND");
        expected.put("a bad reference and an unexpected token", "MVK_INVALID_ENTITY");
        expected.put("an unexpected token alone", "MVK_UNEXPECTED_TOKEN");
        assertEquals(expected, actual, "whether one condition reports every defect or stops at the first");
    }

    private static String verdict(String condition) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", condition).put("errorEntityRelPath", "../Reference")
                    .put("errorCode", "C1").put("severity", "ERROR");
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "see it");
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
