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
 * The BOUNDARY of "a message parameter must name something the condition reads" — the rows a portable
 * checker needs before it may report on one.
 *
 * <p>{@link RuleMessageParameterLawsTest} measures the parameter surface itself: which forms parse, how the
 * suffixes and keys gate, what the quoting rule admits. This file asks the one question that decides an
 * IMPLEMENTATION of the membership gate — for each shape a checker would meet, WHICH class A12 answers with,
 * so a reader that recognises only the plain form can fall silent on the rest instead of reporting the plain
 * form's class for all of them. Over-rejection is the one direction that is never acceptable, and every row
 * below is a shape that looks like a bad field reference and is not.
 *
 * <p><b>The control field is both read by the condition and the rule's error target, and those two cannot be
 * separated.</b> Moving the error target off the field the condition reads does not produce a second
 * variable — it produces a different refusal, {@code MVK_ERROR_FIELD_NOT_REFERENCED}, because A12 requires
 * every rule's error field to appear among its condition's references ({@link ErrorFieldReferenceReachLawsTest}).
 * So on a rule the membership set is exactly the condition's references, with the error target necessarily
 * inside it, and there is no "plus the error target" term for an implementation to discover. Measured while
 * trying to build the separating pair, which is the only way this kind of impossibility shows up.
 *
 * <p>Own domain (the claims policy shared with the parameter-surface file, rebuilt here so the two tests
 * cannot drift into depending on each other's fixture).
 */
class MessageParameterMembershipLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Policy";
    private static final String ERROR_FIELD = "../Reference";
    private static final String ADMITTED = KernelLaws.ADMITTED;

    /** The condition every row holds fixed: it reads `Reference` and nothing else. */
    private static final String READS_REFERENCE = "FieldFilled(Reference)";

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Premium", f -> f.fractionDigits(0, 2))
                    .stringField("Adjuster", f -> { })
                    .numberField("Bonus", f -> f.fractionDigits(0, 2))
                    .group("Claims", claims -> claims.repeatable(20)
                            .stringField("ClaimNo", f -> { })
                            .stringField("Payout", f -> { })))
            .build().toJson();

    /** Which class each shape draws, so a checker knows which ones are not its business. */
    @Test
    void whichClassEachParameterShapeDrawsWhenTheConditionDoesNotReadIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field the condition DOES read — the control", message("see $Reference$"));
        actual.put("no parameter at all — the control", message("see nothing"));
        actual.put("a sibling field the condition does not read", message("see $Premium$"));
        actual.put("the same field with a value suffix", message("see $Premium.value$"));
        actual.put("a field that does not exist", message("see $NoSuchField$"));
        actual.put("a GROUP the condition does not read", message("see $Claims$"));
        actual.put("a pathed field the condition does not read", message("see $Claims/Payout$"));
        actual.put("an escaped dollar, which is no parameter", message("cost 5$$"));
        actual.put("a lone dollar", message("cost 5$"));
        // The row that separates "unterminated" from "a bad reference": the NAME here is a real field the
        // condition does not read, so a reader that guessed where the parameter ended would report the
        // membership class on a text A12 refuses for an entirely different reason.
        actual.put("an unterminated parameter around a real unread field", message("see $Premium"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field the condition DOES read — the control", ADMITTED);
        expected.put("no parameter at all — the control", ADMITTED);
        expected.put("a sibling field the condition does not read", "INVALID_FIELD");
        expected.put("the same field with a value suffix", "INVALID_FIELD");
        // A name that resolves to NOTHING is a different class, so a checker answering unresolvable
        // parameters with the membership code would report a verdict A12 did not give.
        expected.put("a field that does not exist", "INVALID_ENTITY");
        // …and a GROUP is NOT `INVALID_GROUP`: the membership gate does not care what kind it found, so
        // keying the class on the resolved element's kind would be wrong in the other direction.
        expected.put("a GROUP the condition does not read", "INVALID_FIELD");
        expected.put("a pathed field the condition does not read", "INVALID_FIELD");
        expected.put("an escaped dollar, which is no parameter", ADMITTED);
        expected.put("a lone dollar", "WRONG_NUMBER_OF_DOLLARS");
        expected.put("an unterminated parameter around a real unread field", "WRONG_NUMBER_OF_DOLLARS");
        assertEquals(expected, actual, "which class each parameter shape draws");
    }

    /**
     * …and whether a COMPUTATION's message answers to the same gate, which decides whether the law is the
     * rule's or the message member's.
     *
     * <p>A computation has no condition to be a member of — its DSL members are a guard and an operation —
     * so an implementation reading `errorMessage` wherever it finds one would have to invent a membership
     * set for this carrier. The row says whether it must.
     */
    @Test
    void whetherAComputationsMessageAnswersToTheSameGate() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("it names the computed TARGET", computing("1", "see $Premium$"));
        actual.put("it names a field the computation never touches", computing("1", "see $Adjuster$"));
        actual.put("it names the field the operation reads", computing("[Bonus] + 1", "see $Bonus$"));
        actual.put("it names a field that does not exist", computing("1", "see $NoSuchField$"));
        // The vacuity control: ADMITTED rows prove nothing unless A12 is shown to be LOOKING at this
        // computation, and an injected declaration nobody reads would produce exactly the same table. It is
        // in the grid rather than beside it because the first version of this test had no such row, injected
        // by a regex that matched nothing, and read as a clean measurement of an unmodified model.
        actual.put("a broken operation — proof the computation is examined",
                computing("[NoSuchField] + 1", "see $Premium$"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("it names the computed TARGET", ADMITTED);
        // The row that decides whether the gate reaches this carrier at all: it does. The membership set is
        // the computation's own — its computed target and what its operation reads — so the law belongs to
        // the MESSAGE member rather than to the rule, and a family skipping computations under-reports.
        expected.put("it names a field the computation never touches", "INVALID_FIELD");
        expected.put("it names the field the operation reads", ADMITTED);
        // Existence IS asked, and under the same class a rule gives an unresolvable parameter.
        expected.put("it names a field that does not exist", "INVALID_ENTITY");
        expected.put("a broken operation — proof the computation is examined", "MVK_INVALID_ENTITY");
        assertEquals(expected, actual, "whether a computation's message answers to the same gate");
    }

    /**
     * The policy with one computation into `Premium`, carrying [operation] and [text].
     *
     * <p>Injected through the parsed tree rather than by a string substitution: the first attempt used a
     * regex against the drafted JSON, matched nothing, and returned the UNMODIFIED policy — so every row
     * read ADMITTED and the table said computations are ungated. The positive control above is what caught
     * it, which is why it is in the grid and not beside it.
     */
    private static String computing(String operation, String text) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cmsg").put("name", "PremiumCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Premium");
            body.putArray("computationAlternatives").addObject().put("operation", operation);
            body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", text);
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static String message(String text) {
        return messageReporting(ERROR_FIELD, text);
    }

    private static String messageReporting(String errorField, String text) {
        return KernelLaws.messageVerdict(POLICY, GROUP, errorField, READS_REFERENCE, "en_US", text);
    }
}
