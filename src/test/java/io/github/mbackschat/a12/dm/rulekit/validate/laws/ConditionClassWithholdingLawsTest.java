package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
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
                    // Of another scale than Score, so `==` between the two draws the comparison's scale gate.
                    .numberField("Premium", f -> f.fractionDigits(2))
                    .group("Assessment", assessment -> assessment.stringField("Notes", f -> { }))
                    .group("Archive", archive -> { })
                    // A repeatable group, so an unstarred read of it has a position to compete for.
                    .group("Claims", claims -> claims.repeatable(5).numberField("Amount", f -> { }))
                    // Keyed where Claims is not, so a read of it from inside Claims is a parallel level the
                    // walk refuses with an INDEX class rather than the wildcard one.
                    .group("Payouts", payouts -> payouts.repeatable(5).indexField("PayoutNo")
                            .stringField("PayoutNo", f -> { }).numberField("Total", f -> { })))
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
        measure(actual, expected, "an unstarred repeatable read", "FieldFilled(/Policy/Claims/Amount)",
                "MVK_NO_WILDCARD");
        // A lexer class that swallows nothing after it, unlike the unterminated literal above, so it has
        // an after-row.
        measure(actual, expected, "a character the lexer cannot take",
                "FieldFilled(Reference) And % FieldFilled(Reference)", "MVK_LEXER_STANDARD_ERROR");
        // A second REFERENCE class, so the bad name competes with a class drawn by the same resolution rather
        // than by another phase.
        measure(actual, expected, "a path climbing above the root", "FieldFilled(../../Reference)",
                "MVK_TOO_MANY_PARENT_GROUPS");
        // The walk's INDEX class, on a rule iterating Claims: the same walk as MVK_NO_WILDCARD's, measured
        // rather than transferred.
        String parallel = "FieldFilled(/Policy/Claims/Amount) And FieldFilled(/Policy/Payouts/Total)";
        actual.put("a parallel level with no index on the target's side",
                targetedVerdict(parallel, "../Claims/Amount"));
        expected.put("a parallel level with no index on the target's side", "MVK_ERROR_FIELD_NO_INDEX");
        actual.put("a parallel level with no index on the target's side, bad name after",
                targetedVerdict(parallel + AND_A_BAD_NAME, "../Claims/Amount"));
        expected.put("a parallel level with no index on the target's side, bad name after",
                "MVK_ERROR_FIELD_NO_INDEX");
        actual.put("a parallel level with no index on the target's side, bad name BEFORE",
                targetedVerdict(badNameBefore(parallel), "../Claims/Amount"));
        expected.put("a parallel level with no index on the target's side, bad name BEFORE", "MVK_INVALID_ENTITY");

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

    /**
     * The same order law between two classes that are NOT a missing name: a repetition wildcard's, drawn once
     * a reference is read, against the parser's own and an operand class. Each pair is one text read both
     * ways round, so the only variable is which defect comes first.
     */
    @Test
    void aWildcardClassAndAParserOrOperandClassAnswerInTextOrder() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        String stray = "FieldFilled(Reference) And And FieldFilled(Reference)";
        actual.put("a misplaced star, then a stray connective",
                verdict("GroupFilled(/Policy/Assessment*) And " + stray));
        expected.put("a misplaced star, then a stray connective", "MVK_INVALID_WILDCARD");
        actual.put("a stray connective, then a misplaced star",
                verdict(stray + " And GroupFilled(/Policy/Assessment*)"));
        expected.put("a stray connective, then a misplaced star", "MVK_UNEXPECTED_TOKEN");
        actual.put("an unstarred repeatable read, then a stray connective",
                verdict("FieldFilled(/Policy/Claims/Amount) And " + stray));
        expected.put("an unstarred repeatable read, then a stray connective", "MVK_NO_WILDCARD");
        actual.put("a stray connective, then an unstarred repeatable read",
                verdict(stray + " And FieldFilled(/Policy/Claims/Amount)"));
        expected.put("a stray connective, then an unstarred repeatable read", "MVK_UNEXPECTED_TOKEN");
        // And against an OPERAND class, drawn while the operator reads its operand list.
        actual.put("an empty group, then a misplaced star",
                verdict("GroupFilled(/Policy/Archive) And GroupFilled(/Policy/Assessment*)"));
        expected.put("an empty group, then a misplaced star", "MVK_GROUP_IS_EMPTY");
        actual.put("a misplaced star, then an empty group",
                verdict("GroupFilled(/Policy/Assessment*) And GroupFilled(/Policy/Archive)"));
        expected.put("a misplaced star, then an empty group", "MVK_INVALID_WILDCARD");
        assertEquals(expected, actual, "a wildcard class and a parser or operand class answer in text order");
    }

    /**
     * Where a FILTERED operand's defects fall in that order, and a text that does not parse at all.
     *
     * <p>A {@code Having} body is read inside its operand's entity spec, so what the body draws and what the
     * operand's own repetition walk draws are ordered by when each is raised rather than by where the base is
     * spelled. The rows vary one term each against the admitted control and against the unstarred base.
     */
    @Test
    void aFilteredOperandsDefectsAnswerInTheOrderA12ReadsThem() {
        // Names the error field too, so the control is admitted rather than MVK_ERROR_FIELD_NOT_REFERENCED.
        String filtered = "FieldFilled(Reference) And Sum(Claims*/Amount Having [Claims/Amount] > 0) > 0";
        String unstarred = "Sum(Claims/Amount Having [Claims/Amount] > 0) > 0";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filtered sum — the control", verdict(filtered));
        actual.put("…then a stray connective", verdict(filtered + " And FieldFilled(Reference) And And "
                + "FieldFilled(Reference)"));
        actual.put("…then a bad name and a dangling connective", verdict(filtered + AND_A_BAD_NAME + " And"));
        actual.put("an unstarred filtered base", verdict(unstarred));
        actual.put("an unstarred filtered base, bad name after", verdict(unstarred + AND_A_BAD_NAME));
        actual.put("an unstarred filtered base, bad name INSIDE its body",
                verdict("Sum(Claims/Amount Having [Claims/NoSuchField] > 0) > 0"));
        actual.put("an unstarred filtered base, stray connective INSIDE its body",
                verdict("Sum(Claims/Amount Having [Claims/Amount] > 0 And And [Claims/Amount] > 1) > 0"));
        actual.put("an unstarred filtered base, misplaced star INSIDE its body",
                verdict("Sum(Claims/Amount Having GroupFilled(/Policy/Assessment*)) > 0"));
        actual.put("a starred filtered base, misplaced star inside its body",
                verdict("Sum(Claims*/Amount Having GroupFilled(/Policy/Assessment*)) > 0"));
        actual.put("a name after a complete condition", verdict("FieldFilled(Reference) NoSuchField"));
        actual.put("a bad name, then a bracket never closed", verdict("FieldFilled(NoSuchField"));
        actual.put("a connective inside a field operand's brackets",
                verdict("FieldFilled(Reference And FieldFilled(Reference))"));
        actual.put("a group filtered in a count",
                verdict("NumberOfFilledGroups(Claims Having [Claims/Amount] > 0) > 0"));
        actual.put("the same group read unfiltered first",
                verdict("GroupFilled(Claims) And NumberOfFilledGroups(Claims Having [Claims/Amount] > 0) > 0"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a filtered sum — the control", KernelLaws.ADMITTED);
        expected.put("…then a stray connective", "MVK_UNEXPECTED_TOKEN");
        expected.put("…then a bad name and a dangling connective", "MVK_INVALID_ENTITY");
        expected.put("an unstarred filtered base", "MVK_NO_WILDCARD");
        expected.put("an unstarred filtered base, bad name after", "MVK_NO_WILDCARD");
        expected.put("an unstarred filtered base, bad name INSIDE its body", "MVK_INVALID_ENTITY");
        expected.put("an unstarred filtered base, stray connective INSIDE its body", "MVK_UNEXPECTED_TOKEN");
        expected.put("an unstarred filtered base, misplaced star INSIDE its body", "MVK_INVALID_WILDCARD");
        expected.put("a starred filtered base, misplaced star inside its body", "MVK_INVALID_WILDCARD");
        // The two sides of one tie: a name the parser never takes into a reference is a token error, and a
        // reference the parser did take is resolved before the parser looks for what follows it.
        expected.put("a name after a complete condition", "MVK_UNEXPECTED_TOKEN");
        expected.put("a bad name, then a bracket never closed", "MVK_INVALID_ENTITY");
        // A field operand's argument is not a condition, so the parser stops at the connective.
        expected.put("a connective inside a field operand's brackets", "MVK_UNEXPECTED_TOKEN");
        // A group handed to a filter is refused ahead of its walk, so the unfiltered read ahead of it is the
        // row that asks whether that refusal is about the operand or about the group's spelling.
        expected.put("a group filtered in a count", "MVK_NO_GROUPS_ALLOWED");
        expected.put("the same group read unfiltered first", "MVK_NO_WILDCARD");
        assertEquals(expected, actual, "where a filtered operand's defects fall in text order");
    }

    /**
     * The operand gates beyond the four above, one per operator family and the comparison's scale gate beside
     * its type gate: each is raised by the operator's own check, which the condition grammar runs as a parser
     * action once the call is read, so each should order like the measured operand classes and should
     * withhold the unreferenced-target class as they do.
     */
    @Test
    void theOperatorGatesOrderLikeTheOtherOperandClasses() {
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        measure(actual, expected, "text in a rounding slot", "0 < RoundUpValue(Reference, 2)", "MVK_NO_NUMBER");
        measure(actual, expected, "an argument too many", "FieldFilled(Reference, Score)", "MVK_PARAMSIZE_INVALID1");
        measure(actual, expected, "a filtered group in a count",
                "NumberOfFilledGroups(Claims Having [Claims/Amount] > 0) > 0", "MVK_NO_GROUPS_ALLOWED");
        measure(actual, expected, "text in a date slot", "DifferenceInDays(Reference, Reference) > 0",
                "MVK_NO_DATE");
        measure(actual, expected, "text ordered against a number", "[Reference] > 5",
                "MVK_INVALID_TYPE_FOR_COMPARISON");
        measure(actual, expected, "two scales held equal", "[Premium] == [Score]",
                "MVK_INVALID_COMPARE_DEC_PLACES");
        // No gate text names Assessment/Notes, so the class it withholds is live on every row: the control.
        String unnamed = "../Assessment/Notes";
        actual.put("a sound condition, error field unreferenced", targetedVerdict("FieldFilled(Reference)", unnamed));
        expected.put("a sound condition, error field unreferenced", "MVK_ERROR_FIELD_NOT_REFERENCED");
        for (String gate : List.of("0 < RoundUpValue(Reference, 2)", "FieldFilled(Reference, Score)",
                "DifferenceInDays(Reference, Reference) > 0", "[Reference] > 5", "[Premium] == [Score]")) {
            actual.put(gate + ", error field unreferenced", targetedVerdict(gate, unnamed));
        }
        expected.put("0 < RoundUpValue(Reference, 2), error field unreferenced", "MVK_NO_NUMBER");
        expected.put("FieldFilled(Reference, Score), error field unreferenced", "MVK_PARAMSIZE_INVALID1");
        expected.put("DifferenceInDays(Reference, Reference) > 0, error field unreferenced", "MVK_NO_DATE");
        expected.put("[Reference] > 5, error field unreferenced", "MVK_INVALID_TYPE_FOR_COMPARISON");
        expected.put("[Premium] == [Score], error field unreferenced", "MVK_INVALID_COMPARE_DEC_PLACES");
        assertEquals(expected, actual, "the operator gates against a bad name and an unreferenced target");
    }

    /**
     * A gate raised before a later GRAMMAR error still answers: the parser runs the operator's check once the
     * call or comparison is read, before it meets the token it cannot use. Reversed, the grammar class answers.
     *
     * <p>The last two rows discriminate that account from its rival, ANTLR's adaptive prediction refusing a
     * condition part before its call is read. The rival predicts the grammar class for a call that cannot
     * become a comparison ({@code RoundUpValue(…) And …}), which leaves the prediction no viable alternative;
     * A12 draws the gate there too, so a call read to its end has its gate raised whatever follows it.
     */
    @Test
    void aGateAnswersAheadOfALaterGrammarError() {
        String stray = " And And FieldFilled(Reference)";
        Map<String, String> actual = new LinkedHashMap<>();
        Map<String, String> expected = new LinkedHashMap<>();
        Map<String, String> gates = new LinkedHashMap<>();
        gates.put("0 < RoundUpValue(Reference, 2)", "MVK_NO_NUMBER");
        gates.put("FieldFilled(Reference, Score)", "MVK_PARAMSIZE_INVALID1");
        gates.put("NumberOfFilledGroups(Claims Having [Claims/Amount] > 0) > 0", "MVK_NO_GROUPS_ALLOWED");
        gates.put("DifferenceInDays(Reference, Reference) > 0", "MVK_NO_DATE");
        gates.put("[Reference] > 5", "MVK_INVALID_TYPE_FOR_COMPARISON");
        gates.put("[Premium] == [Score]", "MVK_INVALID_COMPARE_DEC_PLACES");
        gates.forEach((gate, alone) -> {
            actual.put(gate + ", then a stray connective", verdict(gate + stray));
            expected.put(gate + ", then a stray connective", alone);
        });
        actual.put("a gate, then a dangling connective", verdict("0 < RoundUpValue(Reference, 2) And"));
        expected.put("a gate, then a dangling connective", "MVK_NO_NUMBER");
        actual.put("a gate, then a comparison as a field operand",
                verdict("[Reference] > 5 And FieldFilled(Score > 3)"));
        expected.put("a gate, then a comparison as a field operand", "MVK_INVALID_TYPE_FOR_COMPARISON");
        actual.put("a gate, then a signed reference",
                verdict("0 < RoundUpValue(Reference, 2) And [Score] > -[Score]"));
        expected.put("a gate, then a signed reference", "MVK_NO_NUMBER");
        actual.put("a gate, then a parenthesised operation",
                verdict("0 < RoundUpValue(Reference, 2) And ([Score] + 1) > 0"));
        expected.put("a gate, then a parenthesised operation", "MVK_NO_NUMBER");
        actual.put("a gate, a bad name, then a stray connective",
                verdict("[Reference] > 5 And FieldFilled(NoSuchField)" + stray));
        expected.put("a gate, a bad name, then a stray connective", "MVK_INVALID_TYPE_FOR_COMPARISON");
        actual.put("a stray connective, then a gate — the reverse",
                verdict("FieldFilled(Reference)" + stray + " And [Reference] > 5"));
        expected.put("a stray connective, then a gate — the reverse", "MVK_UNEXPECTED_TOKEN");
        actual.put("a gated call that cannot become a comparison",
                verdict("RoundUpValue(Reference, 2) And FieldFilled(Reference)"));
        expected.put("a gated call that cannot become a comparison", "MVK_NO_NUMBER");
        actual.put("a gated call before a comparison operator, a defect after it",
                verdict("RoundUpValue(Reference, 2) > -[Score]"));
        expected.put("a gated call before a comparison operator, a defect after it", "MVK_NO_NUMBER");
        assertEquals(expected, actual, "a gate against a later grammar error");
    }

    /**
     * A gate is raised where the grammar production whose action draws it ENDS, which is not always the call.
     *
     * <p>The date slot's gate belongs to the date argument ({@code datumAngabe}), so it is raised before the next
     * argument is read; the arity gate belongs to the call, so a bad name in a later argument is resolved ahead
     * of it. The two rows differ only in which of those productions owns the gate, and the nested row asks
     * whether an inner call read to its end keeps its gate when the call around it then fails to parse.
     */
    @Test
    void aGateIsRaisedWhereItsOwnProductionEnds() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a date-argument gate, then a bad name in the next argument",
                verdict("DifferenceInDays(Reference, NoSuchField) > 0"));
        actual.put("a call's arity gate, with a bad name in its extra argument",
                verdict("FieldFilled(Reference, NoSuchField)"));
        actual.put("a comparison's gate, with a bad name on its right side", verdict("[Reference] > [NoSuchField]"));
        actual.put("a gated call inside a call that then fails to parse",
                verdict("Abs(RoundUpValue(Reference, 2) > 1) > 0"));
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a date-argument gate, then a bad name in the next argument", "MVK_NO_DATE");
        expected.put("a call's arity gate, with a bad name in its extra argument", "MVK_INVALID_ENTITY");
        expected.put("a comparison's gate, with a bad name on its right side", "MVK_INVALID_ENTITY");
        expected.put("a gated call inside a call that then fails to parse", "MVK_NO_NUMBER");
        assertEquals(expected, actual, "where each gate's own production ends");
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
