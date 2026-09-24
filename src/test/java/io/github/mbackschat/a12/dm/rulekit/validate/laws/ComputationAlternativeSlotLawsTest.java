package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * The structure of a stored computation's {@code computationAlternatives} — the member the portable checker
 * must read to reach "every alternative", and one the typed builder cannot make degenerate.
 *
 * <p>{@code Computation.Builder} requires at least one alternative and a non-blank operation, so every shape
 * below is one a checker meets in stored DM-JSON and no existing lock covers. Each row moves exactly one
 * member away from the accepted control.
 *
 * <p>Own domain (the shared subscription fixture: a scale-2 {@code EffectiveFee} under {@code /Subscription
 * /Billing}).
 */
class ComputationAlternativeSlotLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String GROUP = "/Subscription/Billing";

    /** A scale-2 constant into the scale-2 target, so no operand law can move a row that is about structure. */
    private static final String OPERATION = "42.00";

    private static final String ALTERNATIVE = "{\"operation\":\"" + OPERATION + "\"}";

    @Test
    void theAlternativeListIsWhereAComputationCarriesItsWork() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one alternative — the control", verdict("[" + ALTERNATIVE + "]"));
        actual.put("the member absent", verdict(null));
        actual.put("an empty list", verdict("[]"));
        actual.put("the member as an explicit null", verdict("null"));
        actual.put("an alternative with no operation", verdict("[{}]"));
        actual.put("an alternative with a blank operation", verdict("[{\"operation\":\"\"}]"));
        actual.put("an alternative with a whitespace operation", verdict("[{\"operation\":\"   \"}]"));
        actual.put("an alternative with an explicit null operation", verdict("[{\"operation\":null}]"));
        actual.put("an alternative that is not an object", verdict("[\"" + OPERATION + "\"]"));
        actual.put("the member as an object", verdict("{\"a\":" + ALTERNATIVE + "}"));
        actual.put("the member as a scalar", verdict("\"" + OPERATION + "\""));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one alternative — the control", KernelLaws.ADMITTED);
        // A computation with nothing to compute reports the incompleteness AND that its condition is
        // missing. Both codes, not one — and the second is NOT explained by "there is no precondition slot
        // then": declaring a `commonPrecondition` does not remove it (see the third test).
        expected.put("the member absent", "MVK_INCOMPLETE_INPUT,MVK_CONDITION_MISSING");
        expected.put("an empty list", "MVK_INCOMPLETE_INPUT,MVK_CONDITION_MISSING");
        // An explicit `null` on the LIST refuses the reader where an omitted list draws a verdict — and the
        // SCALARS inside an alternative go the other way (below), so the null law is per binding site and
        // cannot be carried from one member to its neighbour.
        expected.put("the member as an explicit null", KernelLaws.UNREADABLE);
        // With an alternative PRESENT the condition gate is satisfied, so only the incompleteness remains —
        // which is what separates "no alternative" from "an alternative with no work in it".
        expected.put("an alternative with no operation", "MVK_INCOMPLETE_INPUT");
        expected.put("an alternative with a blank operation", "MVK_INCOMPLETE_INPUT");
        expected.put("an alternative with a whitespace operation", "MVK_INCOMPLETE_INPUT");
        // An explicit `null` is where a rule's members refuse A12's reader outright (KF291), so whether the
        // same holds one level down is a carrier question rather than an inheritance.
        expected.put("an alternative with an explicit null operation", "MVK_INCOMPLETE_INPUT");
        expected.put("an alternative that is not an object", KernelLaws.UNREADABLE);
        // NOT like `errorMessage`, whose reader iterates the node's elements and takes an object's values
        // (KF290). Two list-shaped members, two readers — which is why each is measured rather than inferred.
        expected.put("the member as an object", KernelLaws.UNREADABLE);
        expected.put("the member as a scalar", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "what A12 does with each shape of computationAlternatives");
    }

    /**
     * Which alternatives owe a PRECONDITION — the row that refuted the natural reading.
     *
     * <p>A single unguarded alternative is accepted (the control above), so "an alternative may be
     * unguarded" looked settled. Add a second and it is not: the pair draws
     * {@code MVK_ERROR_EMPTY_PRECONDITION}. The rows below locate which alternative owes one, because
     * "the last is the fallback" and "every one of several owes a guard" both fit the first observation and
     * a checker built on the wrong one either rejects legal models or passes illegal ones.
     *
     * <p>The last two rows separate ABSENT from BLANK, which the sibling slot families had made look
     * settled: a blank {@code errorCode} and a blank message {@code text} each count as absent, and a blank
     * precondition does not — it is a present condition that fails to parse.
     */
    @Test
    void severalAlternativesEachOweAPrecondition() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one, unguarded — the control", verdict("[" + ALTERNATIVE + "]"));
        actual.put("two, both guarded", verdict("[" + guarded("FieldFilled(../Tier)") + ","
                + guarded("FieldNotFilled(../Tier)") + "]"));
        actual.put("two, the LAST unguarded",
                verdict("[" + guarded("FieldFilled(../Tier)") + "," + ALTERNATIVE + "]"));
        actual.put("two, the FIRST unguarded",
                verdict("[" + ALTERNATIVE + "," + guarded("FieldFilled(../Tier)") + "]"));
        actual.put("two, both unguarded", verdict("[" + ALTERNATIVE + "," + ALTERNATIVE + "]"));
        actual.put("two, one precondition blank",
                verdict("[" + guarded("FieldFilled(../Tier)") + "," + guarded("") + "]"));
        actual.put("two, one precondition whitespace",
                verdict("[" + guarded("FieldFilled(../Tier)") + "," + guarded("   ") + "]"));
        actual.put("two, one precondition explicit null", verdict("[" + guarded("FieldFilled(../Tier)")
                + ",{\"precondition\":null,\"operation\":\"" + OPERATION + "\"}]"));
        actual.put("THREE, the middle unguarded", verdict("[" + guarded("FieldFilled(../Tier)") + ","
                + ALTERNATIVE + "," + guarded("FieldNotFilled(../Tier)") + "]"));
        actual.put("THREE, all guarded", verdict("[" + guarded("FieldFilled(../Tier)") + ","
                + guarded("FieldNotFilled(../Tier)") + "," + guarded("FieldFilled(../AutoRenew)") + "]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one, unguarded — the control", KernelLaws.ADMITTED);
        expected.put("two, both guarded", KernelLaws.ADMITTED);
        expected.put("two, the LAST unguarded", "MVK_ERROR_EMPTY_PRECONDITION");
        expected.put("two, the FIRST unguarded", "MVK_ERROR_EMPTY_PRECONDITION");
        expected.put("two, both unguarded", "MVK_ERROR_EMPTY_PRECONDITION");
        // NOT the blank-counts-as-absent law a blank `errorCode` and a blank message `text` both follow: a
        // PRESENT precondition goes to the condition parser whatever it spells, and empty text is a parse
        // failure there. So "empty precondition" names the ABSENT member only, and a checker that reported
        // this code for a blank one would name the wrong defect in the wrong family.
        expected.put("two, one precondition blank", "MVK_UNEXPECTED_TOKEN");
        expected.put("two, one precondition whitespace", "MVK_UNEXPECTED_TOKEN");
        expected.put("two, one precondition explicit null", "MVK_ERROR_EMPTY_PRECONDITION");
        // Every multi row above has exactly TWO alternatives, so "from two on" and "for a pair" were one
        // observation. The checker applies the demand at any size, which is a claim these two rows settle.
        expected.put("THREE, the middle unguarded", "MVK_ERROR_EMPTY_PRECONDITION");
        expected.put("THREE, all guarded", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which alternatives owe a precondition");
    }

    /**
     * …and a COMMON precondition does not pay that debt — the row that breaks the constant every row above
     * shares.
     *
     * <p>None of them declares a {@code commonPrecondition}, so "one alternative may be unguarded" and "an
     * unguarded alternative is fine while nothing else guards the computation" are the same observation
     * there. Declaring one separates them: the single unguarded alternative is still accepted and the pair
     * is still refused, so the demand is per-alternative and the common guard is a different slot rather
     * than a substitute.
     */
    @Test
    void aCommonPreconditionDoesNotStandInForAnAlternativesOwn() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("one unguarded, with a common precondition",
                verdict("[" + ALTERNATIVE + "]", "FieldFilled(../Tier)"));
        actual.put("NO alternative, with a common precondition", verdict(null, "FieldFilled(../Tier)"));
        actual.put("an empty list, with a common precondition", verdict("[]", "FieldFilled(../Tier)"));
        actual.put("two unguarded, with a common precondition",
                verdict("[" + ALTERNATIVE + "," + ALTERNATIVE + "]", "FieldFilled(../Tier)"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("one unguarded, with a common precondition", KernelLaws.ADMITTED);
        // The no-work rows sat on the wrong side of this table's own variable: measured with NO common
        // precondition, "with no alternative there is no precondition slot either" fitted them and was never
        // tested. It is FALSE — a declared common precondition is a condition slot and the code fires anyway.
        // The pair of codes is the observation; why the second one fires is not established here.
        expected.put("NO alternative, with a common precondition", "MVK_INCOMPLETE_INPUT,MVK_CONDITION_MISSING");
        expected.put("an empty list, with a common precondition", "MVK_INCOMPLETE_INPUT,MVK_CONDITION_MISSING");
        expected.put("two unguarded, with a common precondition", "MVK_ERROR_EMPTY_PRECONDITION");
        assertEquals(expected, actual, "whether a common precondition substitutes for an alternative's own");
    }

    /**
     * A BLANK slot on each DSL-bearing member of this carrier, which do NOT answer alike.
     *
     * <p>An alternative's blank {@code precondition} draws {@code MVK_UNEXPECTED_TOKEN} and its blank
     * {@code operation} draws {@code MVK_INCOMPLETE_INPUT} — measured above, in two tests written for other
     * questions, so the disagreement was never put side by side. It matters to a checker: a syntax layer
     * reporting "a blank does not parse" with one code would be right about one member and wrong about the
     * other, and the row that completes the set is the {@code commonPrecondition}, which nothing had
     * measured at all.
     *
     * <p>The filled rows are the controls, so a code below is about the blank and not about the shape this
     * fixture builds to carry it.
     */
    @Test
    void aBlankSlotAnswersDifferentlyOnEachMemberOfThisCarrier() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filled common precondition — the control",
                verdict("[" + ALTERNATIVE + "]", "FieldFilled(../Tier)"));
        actual.put("a BLANK common precondition", verdict("[" + ALTERNATIVE + "]", ""));
        actual.put("a filled precondition on both — the control",
                verdict("[" + guarded("FieldFilled(../Tier)") + "," + guarded("FieldFilled(../Tier)") + "]",
                        null));
        actual.put("a BLANK precondition",
                verdict("[" + guarded("FieldFilled(../Tier)") + "," + guarded("") + "]", null));
        actual.put("a BLANK operation", verdict("[{\"operation\":\"\"}]", null));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a filled common precondition — the control", KernelLaws.ADMITTED);
        // Not admitted: a declared common precondition is a condition slot like any other, so a blank one
        // reaches the parser and fails there. Predicted ADMITTED and measured otherwise.
        expected.put("a BLANK common precondition", "MVK_UNEXPECTED_TOKEN");
        expected.put("a filled precondition on both — the control", KernelLaws.ADMITTED);
        expected.put("a BLANK precondition", "MVK_UNEXPECTED_TOKEN");
        expected.put("a BLANK operation", "MVK_INCOMPLETE_INPUT");
        assertEquals(expected, actual, "what a blank draws on each DSL-bearing member of a computation");
    }

    /**
     * Which spaces make each DSL-bearing computation member BLANK, beside the ASCII blank rows above. Java's
     * {@code Character.isWhitespace} excludes the three no-break spaces and includes the rest.
     */
    @Test
    void whichSpacesMakeAComputationSlotBlank() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("operation: a no-break space", verdict("[{\"operation\":\"\\u00a0\"}]", null));
        actual.put("operation: a figure space", verdict("[{\"operation\":\"\\u2007\"}]", null));
        actual.put("operation: a narrow no-break space", verdict("[{\"operation\":\"\\u202f\"}]", null));
        actual.put("operation: a vertical tab", verdict("[{\"operation\":\"\\u000b\"}]", null));
        actual.put("operation: an em space", verdict("[{\"operation\":\"\\u2003\"}]", null));
        actual.put("operation: an ideographic space", verdict("[{\"operation\":\"\\u3000\"}]", null));
        actual.put("common precondition: a no-break space", verdict("[" + ALTERNATIVE + "]", "\u00a0"));
        actual.put("common precondition: a figure space", verdict("[" + ALTERNATIVE + "]", "\u2007"));
        actual.put("common precondition: a narrow no-break space", verdict("[" + ALTERNATIVE + "]", "\u202f"));
        actual.put("common precondition: a vertical tab", verdict("[" + ALTERNATIVE + "]", "\u000b"));
        actual.put("common precondition: an em space", verdict("[" + ALTERNATIVE + "]", "\u2003"));
        actual.put("common precondition: an ideographic space", verdict("[" + ALTERNATIVE + "]", "\u3000"));

        Map<String, String> expected = new LinkedHashMap<>();
        // No member-level presence test here: each text reaches the lexer, which none of these spaces passes.
        expected.put("operation: a no-break space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("operation: a figure space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("operation: a narrow no-break space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("operation: a vertical tab", "MVK_LEXER_STANDARD_ERROR");
        expected.put("operation: an em space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("operation: an ideographic space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("common precondition: a no-break space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("common precondition: a figure space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("common precondition: a narrow no-break space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("common precondition: a vertical tab", "MVK_LEXER_STANDARD_ERROR");
        expected.put("common precondition: an em space", "MVK_LEXER_STANDARD_ERROR");
        expected.put("common precondition: an ideographic space", "MVK_LEXER_STANDARD_ERROR");
        assertEquals(expected, actual, "which spaces make a computation slot blank");
    }

    /**
     * A shape whose ENVELOPE A12's reader refuses, which is a tier above any verdict about the slot.
     *
     * <p>It is measured because a checker reading `declaration["Computation"]` as an object gets `null` here
     * and cannot tell that from an absent `computationAlternatives` — so the row that keeps the two apart has
     * to exist before the code can be trusted to keep them apart.
     */
    @Test
    void aNonObjectComputationEnvelopeIsRefusedByTheReader() {
        assertEquals(KernelLaws.UNREADABLE, KernelLaws.verdictOf(withEnvelope("\"oops\"")));
        assertEquals(KernelLaws.UNREADABLE, KernelLaws.verdictOf(withEnvelope("[]")));
    }

    /**
     * How many notifications A12 raises for two unguarded alternatives — one shared, or one each.
     *
     * <p>The code table cannot answer it, because {@link KernelLaws#verdictOf} de-duplicates codes and one
     * notification spells the same string as two. A checker reporting one finding per unguarded alternative
     * makes a claim about A12's own report, so it needs this oracle rather than that one.
     */
    @Test
    void oneUnguardedNotificationCoversTheWholeComputation() {
        ModelConsistencyException rejected =
                org.junit.jupiter.api.Assertions.assertThrows(
                        ModelConsistencyException.class,
                        () -> io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel.ofJson(
                                probe("[" + ALTERNATIVE + "," + ALTERNATIVE + "]", null)));
        long raised = rejected.notifications().stream()
                .filter(n -> n.message().contains("MVK_ERROR_EMPTY_PRECONDITION")).count();
        // ONE, for the computation — A12 names the declaration, not the alternative, and its message carries
        // no alternative index at all. A checker emitting one finding per unguarded alternative would tell a
        // consumer A12 said something twice that it said once.
        assertEquals(1, raised, () -> "notifications for two unguarded alternatives: " + rejected.notifications());
    }

    /** The probe with the computation's whole envelope replaced by [rawJson]. */
    private static String withEnvelope(String rawJson) {
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), DraftComputation.builder()
                .groupPath(GROUP).name("AlternativeProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, OPERATION)))
                .messages(List.of(en("x"), de("y")))
                .build(), List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            for (var top : root.path("content").path("modelRoot").path("rootGroups")) {
                if (replaceEnvelope(top, MAPPER.readTree(rawJson))) {
                    return root.toString();
                }
            }
            throw new IllegalStateException("the probe computation is no longer where this test writes it");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    private static boolean replaceEnvelope(JsonNode node,
            JsonNode envelope) {
        if ("AlternativeProbe".equals(node.path("name").asText()) && node.has("Computation")) {
            ((ObjectNode) node).set("Computation", envelope);
            return true;
        }
        for (var child : node.path("Group").path("elements")) {
            if (replaceEnvelope(child, envelope)) {
                return true;
            }
        }
        return false;
    }

    private static String guarded(String precondition) {
        return "{\"precondition\":\"" + precondition + "\",\"operation\":\"" + OPERATION + "\"}";
    }

    /** What A12 says about the probe model with `computationAlternatives` replaced, or removed when null. */
    private static String verdict(String rawJson) {
        return KernelLaws.verdictOf(probe(rawJson, null));
    }

    /** …and with a `commonPrecondition` declared beside it, the variable the rows above hold constant. */
    private static String verdict(String rawJson, String commonPrecondition) {
        return KernelLaws.verdictOf(probe(rawJson, commonPrecondition));
    }

    private static String probe(String rawJson, String commonPrecondition) {
        DraftComputation draft = DraftComputation.builder()
                .groupPath(GROUP).name("AlternativeProbe").computedFieldRelPath("../EffectiveFee")
                .alternatives(List.of(new DraftComputation.Alternative(null, OPERATION)))
                .messages(List.of(en("x"), de("y")))
                .build();
        String written = ModelWriter.addComputation(Fixtures.subscriptionJson(), draft,
                List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ObjectNode computation = computationBody(root);
            if (!computation.has("computationAlternatives")) {
                throw new IllegalStateException("the writer no longer emits `computationAlternatives`");
            }
            if (rawJson == null) {
                computation.remove("computationAlternatives");
            } else {
                computation.set("computationAlternatives", MAPPER.readTree(rawJson));
            }
            if (commonPrecondition != null) {
                computation.put("commonPrecondition", commonPrecondition);
            }
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragment must parse", unreadable);
        }
    }

    /** The probe computation's body, refusing rather than returning a model the substitution never reached. */
    private static ObjectNode computationBody(ObjectNode root) {
        for (var top : root.path("content").path("modelRoot").path("rootGroups")) {
            ObjectNode found = findProbe(top);
            if (found != null) {
                return found;
            }
        }
        throw new IllegalStateException("the probe computation is no longer where this test writes it");
    }

    private static ObjectNode findProbe(JsonNode node) {
        if ("AlternativeProbe".equals(node.path("name").asText()) && node.has("Computation")) {
            return (ObjectNode) node.path("Computation");
        }
        for (var child : node.path("Group").path("elements")) {
            ObjectNode found = findProbe(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
