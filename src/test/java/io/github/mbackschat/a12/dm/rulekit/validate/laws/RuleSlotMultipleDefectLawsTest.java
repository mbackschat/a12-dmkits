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
 * Whether a rule defective in SEVERAL of its own slots draws every class or stops at the first.
 *
 * <p>The sixth and last member of the defect-pairing pass. A rule's slots are four independent members —
 * its `name`, `errorCode`, `severity` and `errorMessage` — each with a law of its own and each measured
 * alone by {@link RuleSlotLawsTest}. Unlike a condition's text or an enum's value list there is no order
 * between them to vary, so the question is only which member A12 examines first and whether it stops.
 *
 * <p>Own domain (a claims policy).
 */
class RuleSlotMultipleDefectLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy.stringField("Reference", f -> { }))
            .build().toJson();

    /** A12 reserves this prefix for the names it generates itself. */
    private static final String RESERVED = "vk_vkProbe";

    private static final String NAME_INVALID = "MVK_RULE_NAME_INVALID";
    private static final String CODE_MISSING = "MVK_ERROR_CODE_MISSING";
    private static final String MESSAGE_MISSING = "MVK_ERROR_MESSAGE_MISSING";
    private static final String NO_ERROR_TEXT = "RULE_HAS_NO_ERRORTEXT";

    /** What a missing message draws: the prefixed class and A12's own unprefixed one beside it. */
    private static final String NO_MESSAGE = MESSAGE_MISSING + "," + NO_ERROR_TEXT;

    @Test
    void aRuleReportsEverySlotDefectItCarries() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("every slot sound — the control", rule("Probe", "C1", "ERROR", true));
        actual.put("a reserved NAME", rule(RESERVED, "C1", "ERROR", true));
        actual.put("a blank errorCode", rule("Probe", "", "ERROR", true));
        actual.put("no errorMessage", rule("Probe", "C1", "ERROR", false));
        actual.put("a reserved name AND a blank errorCode", rule(RESERVED, "", "ERROR", true));
        actual.put("a reserved name AND no errorMessage", rule(RESERVED, "C1", "ERROR", false));
        actual.put("a blank errorCode AND no errorMessage", rule("Probe", "", "ERROR", false));
        actual.put("all three defective", rule(RESERVED, "", "ERROR", false));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("every slot sound — the control", KernelLaws.ADMITTED);
        expected.put("a reserved NAME", NAME_INVALID);
        expected.put("a blank errorCode", CODE_MISSING);
        expected.put("no errorMessage", NO_MESSAGE);
        // Nothing is withheld between the slots: the sixth member measured this way and the fourth to report
        // in full. There is no order to vary here — these are separate members rather than positions in one
        // text — so the pairs are the whole question.
        expected.put("a reserved name AND a blank errorCode", CODE_MISSING + "," + NAME_INVALID);
        expected.put("a reserved name AND no errorMessage",
                MESSAGE_MISSING + "," + NAME_INVALID + "," + NO_ERROR_TEXT);
        expected.put("a blank errorCode AND no errorMessage", CODE_MISSING + "," + NO_MESSAGE);
        // All four classes at once, so the slots are the field type's shape and not the condition's.
        expected.put("all three defective",
                CODE_MISSING + "," + MESSAGE_MISSING + "," + NAME_INVALID + "," + NO_ERROR_TEXT);
        assertEquals(expected, actual, "a rule reports every slot defect it carries");
    }

    /**
     * `severity` is not a slot verdict at all — an unknown spelling makes the source UNREADABLE.
     *
     * <p>Its own test because it moves the buildability tier rather than adding a class, so a row inside the
     * grid above would vary two things at once. It also takes the other slots' verdicts with it, which is
     * what "no consistency verdict exists" means: the model is never judged.
     */
    @Test
    void anUnknownSeverityMakesTheSourceUnreadableAndTakesEveryOtherSlotVerdictWithIt() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an unknown severity alone", rule("Probe", "C1", "URGENT", true));
        actual.put("beside a reserved name", rule(RESERVED, "C1", "URGENT", true));
        actual.put("beside a blank errorCode", rule("Probe", "", "URGENT", true));
        actual.put("beside no errorMessage", rule("Probe", "C1", "URGENT", false));
        actual.put("beside all three", rule(RESERVED, "", "URGENT", false));

        Map<String, String> expected = new LinkedHashMap<>();
        actual.forEach((row, verdict) -> expected.put(row, KernelLaws.UNREADABLE));
        assertEquals(expected, actual, "an unknown severity makes the source unreadable");
    }

    /**
     * WHICH "no message at all" shapes draw the unprefixed second class beside the `MVK_*` one.
     *
     * <p>[KF323] measured two of them and they disagreed — a blank later duplicate draws both, a
     * whitespace-only text draws the prefixed class alone — so the checker left the second class to A12 on
     * the stated ground that the split was unmeasured. The grid above then measured a third shape by
     * accident. This enumerates the rest, with both of KF323's rows inside the set rather than cited from
     * outside it, since a split is exactly the claim a set with all its rows on one side cannot test.
     */
    @Test
    void whichEmptyMessageShapesDrawTheUnprefixedClassBesideTheMvkOne() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a filled text — the control", messages("[{\"locale\":\"en_US\",\"text\":\"see it\"}]"));
        actual.put("no `errorMessage` member at all", messages(null));
        actual.put("an EMPTY errorMessage array", messages("[]"));
        actual.put("one entry with an empty text", messages("[{\"locale\":\"en_US\",\"text\":\"\"}]"));
        actual.put("one entry with a whitespace-only text",
                messages("[{\"locale\":\"en_US\",\"text\":\"   \"}]"));
        actual.put("one entry for an UNDECLARED locale only",
                messages("[{\"locale\":\"de_DE\",\"text\":\"sieh es\"}]"));
        actual.put("a filled entry then a blank one for the same locale",
                messages("[{\"locale\":\"en_US\",\"text\":\"see it\"},"
                        + "{\"locale\":\"en_US\",\"text\":\"\"}]"));

        // The COMPUTATION carrier, because the mirror asserts this law for both kinds and every row above
        // is a rule. The unprefixed class reads as a rule's — it is spelled `RULE_HAS_NO_ERRORTEXT` — which
        // is exactly the shape of name this estate has been caught transferring before.
        actual.put("a computation with no message", computationWithoutAMessage());
        actual.put("a computation with a whitespace text", computationWithAWhitespaceMessage());

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a filled text — the control", KernelLaws.ADMITTED);
        expected.put("no `errorMessage` member at all", NO_MESSAGE);
        expected.put("an EMPTY errorMessage array", NO_MESSAGE);
        expected.put("one entry with an empty text", NO_MESSAGE);
        // The single exception in the whole enumeration, and the row KF323 had on its own: a text that is
        // whitespace rather than empty draws the prefixed class alone.
        expected.put("one entry with a whitespace-only text", MESSAGE_MISSING);
        expected.put("one entry for an UNDECLARED locale only", NO_MESSAGE);
        expected.put("a filled entry then a blank one for the same locale", NO_MESSAGE);
        // The same on the other carrier, so the class spelled `RULE_HAS_NO_ERRORTEXT` is not a rule's.
        expected.put("a computation with no message", NO_MESSAGE);
        expected.put("a computation with a whitespace text", MESSAGE_MISSING);
        assertEquals(expected, actual, "which empty-message shapes draw the unprefixed class");
    }

    /**
     * The same split asked of the shapes the one-locale grid above cannot reach.
     *
     * <p>The rule drawn from that grid — every uncovered shape draws both classes except a whitespace-only
     * text — was measured on a model declaring exactly one locale and building cleanly. A checker applying
     * it to a model with NO declared locale, or one whose declared code A12's reader refuses, would be
     * transferring it across the carrier that decides it.
     */
    @Test
    void theSameSplitOnModelsWhoseDeclaredLocaleSetIsItselfDefective() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("two locales, one empty text and one whitespace text",
                bilingual("[{\"locale\":\"en_US\",\"text\":\"\"},{\"locale\":\"de_DE\",\"text\":\"  \"}]"));
        actual.put("two locales, both texts EMPTY",
                bilingual("[{\"locale\":\"en_US\",\"text\":\"\"},{\"locale\":\"de_DE\",\"text\":\"\"}]"));
        // "every uncovered text is whitespace" and "there is exactly one declared locale and its text is
        // whitespace" both explain the rows above; only two whitespace texts tell them apart.
        actual.put("two locales, BOTH texts whitespace",
                bilingual("[{\"locale\":\"en_US\",\"text\":\" \"},{\"locale\":\"de_DE\",\"text\":\"  \"}]"));
        // The last cell an implementation has to decide: one locale answered with whitespace, the other not
        // answered at all. "every declared locale is whitespace" and "every COLLECTED text is whitespace"
        // agree on every row above and differ only here.
        actual.put("two locales, one whitespace text and one ABSENT",
                bilingual("[{\"locale\":\"en_US\",\"text\":\"  \"}]"));
        actual.put("NO declared locale, one message",
                withLocales("", "[{\"locale\":\"en_US\",\"text\":\"see it\"}]"));
        actual.put("NO declared locale, no message", withLocales("", "[]"));
        actual.put("a declared code A12's reader refuses",
                withLocales("{\"code\":\"en_us\"}", "[{\"locale\":\"en_US\",\"text\":\"see it\"}]"));
        actual.put("an EMPTY declared code",
                withLocales("{\"code\":\"\"}", "[{\"locale\":\"en_US\",\"text\":\"see it\"}]"));
        actual.put("an EMPTY message locale code",
                messages("[{\"locale\":\"\",\"text\":\"see it\"}]"));

        Map<String, String> expected = new LinkedHashMap<>();
        // Whitespace does NOT suppress the class while another declared locale is uncovered by something
        // else — so the exception is over the declared LOCALES, not over the texts that happen to be there.
        expected.put("two locales, one empty text and one whitespace text", NO_MESSAGE);
        // The unprefixed class once per declared locale without a text (KERNEL-FINDINGS KF558).
        expected.put("two locales, both texts EMPTY", NO_MESSAGE + ",RULE_HAS_NO_ERRORTEXT");
        expected.put("two locales, BOTH texts whitespace", MESSAGE_MISSING);
        // …and the cell that refutes "every COLLECTED text is whitespace", which fits every other row.
        expected.put("two locales, one whitespace text and one ABSENT", NO_MESSAGE);
        // A model with no usable declared locale reaches no verdict at all, so neither class is A12's and a
        // checker emitting one there would be stating its own reading as A12's.
        expected.put("NO declared locale, one message", KernelLaws.NOT_BUILDABLE);
        expected.put("NO declared locale, no message", KernelLaws.NOT_BUILDABLE);
        expected.put("a declared code A12's reader refuses", KernelLaws.UNREADABLE);
        expected.put("an EMPTY declared code", KernelLaws.NOT_BUILDABLE);
        // The empty MESSAGE locale is not one of those: the declared set is fine, so a verdict exists.
        expected.put("an EMPTY message locale code", NO_MESSAGE);
        assertEquals(expected, actual, "the same split where the declared locale set is itself defective");
    }

    /**
     * The split on the carrier where ANOTHER declared locale is answered, asked of both declarations.
     *
     * <p>Every row above leaves no declared locale covered, so the message class is the prefixed-missing one.
     * Answering {@code en_US} moves it to the per-locale class, and the unprefixed one then keys on the same
     * question as before — whether the unanswered locale has any text at all: a blank text and an absent entry
     * draw it, a whitespace text does not. `MessageSlotRawShapeLawsTest` measured the rule's three cells; the
     * computation's are the carrier that measurement did not reach.
     */
    @Test
    void theUnprefixedClassKeysOnAnUnansweredLocalesTextBesideAnAnsweredOne() {
        String en = "{\"locale\":\"en_US\",\"text\":\"see it\"}";
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("rule, de_DE BLANK", bilingual("[" + en + ",{\"locale\":\"de_DE\",\"text\":\"\"}]"));
        actual.put("rule, de_DE WHITESPACE", bilingual("[" + en + ",{\"locale\":\"de_DE\",\"text\":\"  \"}]"));
        actual.put("rule, de_DE ABSENT", bilingual("[" + en + "]"));
        actual.put("rule, en_US filled then EMPTY", bilingual("[" + en + ",{\"locale\":\"en_US\",\"text\":\"\"},"
                + "{\"locale\":\"de_DE\",\"text\":\"sieh\"}]"));
        actual.put("computation, both answered — the control",
                bilingualComputation("[" + en + ",{\"locale\":\"de_DE\",\"text\":\"sieh\"}]"));
        actual.put("computation, de_DE BLANK",
                bilingualComputation("[" + en + ",{\"locale\":\"de_DE\",\"text\":\"\"}]"));
        actual.put("computation, de_DE WHITESPACE",
                bilingualComputation("[" + en + ",{\"locale\":\"de_DE\",\"text\":\"  \"}]"));
        actual.put("computation, de_DE ABSENT", bilingualComputation("[" + en + "]"));
        actual.put("computation, en_US filled then EMPTY", bilingualComputation("[" + en
                + ",{\"locale\":\"en_US\",\"text\":\"\"},{\"locale\":\"de_DE\",\"text\":\"sieh\"}]"));

        String perLocale = "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING";
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("rule, de_DE BLANK", perLocale + "," + NO_ERROR_TEXT);
        expected.put("rule, de_DE WHITESPACE", perLocale);
        expected.put("rule, de_DE ABSENT", perLocale + "," + NO_ERROR_TEXT);
        // The LAST entry for a locale is the one A12 keeps, so an empty one after a filled one unanswers it.
        expected.put("rule, en_US filled then EMPTY", perLocale + "," + NO_ERROR_TEXT);
        expected.put("computation, both answered — the control", KernelLaws.ADMITTED);
        expected.put("computation, de_DE BLANK", perLocale + "," + NO_ERROR_TEXT);
        expected.put("computation, de_DE WHITESPACE", perLocale);
        expected.put("computation, de_DE ABSENT", perLocale + "," + NO_ERROR_TEXT);
        expected.put("computation, en_US filled then EMPTY", perLocale + "," + NO_ERROR_TEXT);
        assertEquals(expected, actual, "the unprefixed class beside an answered locale");
    }

    /**
     * The two-locale model, DRAFTED so every element carries the German label A12 demands.
     *
     * <p>Hand-writing the header's second locale is not enough: the model then fails to build for a reason
     * that has nothing to do with messages, and the row measures the fixture instead of the law.
     */
    private static String bilingual(String errorMessageJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(BILINGUAL);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", "FieldFilled(Reference)").put("errorEntityRelPath", "../Reference")
                    .put("errorCode", "C1").put("severity", "ERROR");
            body.set("errorMessage", MAPPER.readTree(errorMessageJson));
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the message fragment must parse", unreadable);
        }
    }

    private static final String BILINGUAL = DocumentModelDraft.named("claims-policy")
            .locale("en_US").locale("de_DE")
            .rootGroup("Policy", policy -> policy.stringField("Reference", f -> { }))
            .build().toJson();

    /** A model whose `header.locales` is written verbatim, so a defective declared set is authorable. */
    private static String withLocales(String localesJson, String errorMessageJson) {
        String model = "{\"header\":{\"id\":\"claims-policy\",\"locales\":[" + localesJson + "]},"
                + "\"content\":{\"modelRoot\":{\"rootGroups\":[{\"type\":\"Group\",\"id\":\"g0\","
                + "\"name\":\"Policy\",\"Group\":{\"elements\":["
                + "{\"type\":\"Field\",\"id\":\"f0\",\"name\":\"Reference\",\"Field\":{"
                + "\"fieldType\":{\"type\":\"StringType\"}}},"
                + "{\"type\":\"Rule\",\"id\":\"rprobe\",\"name\":\"Probe\",\"Rule\":{"
                + "\"errorCondition\":\"FieldFilled(Reference)\","
                + "\"errorEntityRelPath\":\"../Reference\",\"errorCode\":\"C1\","
                + "\"severity\":\"ERROR\",\"errorMessage\":" + errorMessageJson + "}}"
                + "]}}]}}}";
        return KernelLaws.verdictOf(model);
    }

    private static String computationWithoutAMessage() {
        return computation(null);
    }

    private static String computationWithAWhitespaceMessage() {
        return computation("[{\"locale\":\"en_US\",\"text\":\"  \"}]");
    }

    private static String computation(String errorMessageJson) {
        return computation(SCORED, errorMessageJson);
    }

    private static String bilingualComputation(String errorMessageJson) {
        return computation(BILINGUAL_SCORED, errorMessageJson);
    }

    /** A computation computing `Score` in [model], with its `errorMessage` written verbatim or omitted. */
    private static String computation(String model, String errorMessageJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(model);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Computation").put("id", "cprobe").put("name", "ScoreCalc");
            ObjectNode body = node.putObject("Computation");
            body.put("computedFieldRelPath", "../Score");
            body.putArray("computationAlternatives").addObject().put("operation", "1");
            if (errorMessageJson != null) {
                body.set("errorMessage", MAPPER.readTree(errorMessageJson));
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the message fragment must parse", unreadable);
        }
    }

    private static final String SCORED = DocumentModelDraft.named("claims-policy").locale("en_US")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Score", f -> { }))
            .build().toJson();

    private static final String BILINGUAL_SCORED = DocumentModelDraft.named("claims-policy")
            .locale("en_US").locale("de_DE")
            .rootGroup("Policy", policy -> policy
                    .stringField("Reference", f -> { })
                    .numberField("Score", f -> { }))
            .build().toJson();

    /** One rule whose `errorMessage` is written verbatim, or omitted when the JSON is null. */
    private static String messages(String errorMessageJson) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", "Probe");
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", "FieldFilled(Reference)").put("errorEntityRelPath", "../Reference")
                    .put("errorCode", "C1").put("severity", "ERROR");
            if (errorMessageJson != null) {
                body.set("errorMessage", MAPPER.readTree(errorMessageJson));
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture and the message fragment must parse", unreadable);
        }
    }

    /**
     * One rule with each of its four slots set, so a row varies only the slots it names.
     *
     * @param carriesMessage whether the `errorMessage` array is written at all
     */
    private static String rule(String name, String errorCode, String severity, boolean carriesMessage) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ObjectNode node = (ObjectNode) ((ArrayNode) root.path("content").path("modelRoot")
                    .path("rootGroups").get(0).path("Group").path("elements")).addObject();
            node.put("type", "Rule").put("id", "rprobe").put("name", name);
            ObjectNode body = node.putObject("Rule");
            body.put("errorCondition", "FieldFilled(Reference)").put("errorEntityRelPath", "../Reference")
                    .put("errorCode", errorCode).put("severity", severity);
            if (carriesMessage) {
                body.putArray("errorMessage").addObject().put("locale", "en_US").put("text", "see it");
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }
}
