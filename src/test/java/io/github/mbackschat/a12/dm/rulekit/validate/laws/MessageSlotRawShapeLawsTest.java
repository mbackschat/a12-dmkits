package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.core.ModelInputException;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter;

/**
 * The message-slot and declared-locale shapes the typed writer cannot author — measured, because a checker
 * reading stored DM-JSON meets every one of them and {@link RuleSlotLawsTest}'s rows all go through
 * {@code ModelWriter}, which emits exactly one canonical spelling.
 *
 * <p><b>Three verdicts, not two, and the third is the one that matters.</b> A shape A12's reader REFUSES
 * makes the model unreadable, so A12 never judges it at all — no consistency verdict is produced. Reporting
 * an {@code MVK_*} finding there presents the reader's own opinion as A12's, and reporting nothing presents
 * an unbuildable model as clean, so the two must be told apart rather than collapsed into "invalid".
 *
 * <p>Own domain (the shared subscription fixture, whose header declares {@code en_US} and {@code de_DE}).
 */
class MessageSlotRawShapeLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ADMITTED = "ADMITTED";

    /** A12's reader refused the bytes, so no rule was ever examined — not a verdict about the model. */
    private static final String UNREADABLE = "UNREADABLE";

    /** The kernel's own codes, lifted from the notification stream rather than matched against a guess. */
    private static final Pattern MVK = Pattern.compile("MVK_[A-Z0-9_]+");

    private static final String EN = "{\"locale\":\"en_US\",\"text\":\"x\"}";
    private static final String DE = "{\"locale\":\"de_DE\",\"text\":\"y\"}";
    private static final String EN_ONLY = "[{\"code\":\"en_US\"}]";

    /**
     * The LOCALE-SPELLING rows. A12 reads a locale code through `LocaleUtils.toLocale` on both sides, so the
     * question a string comparison cannot answer is whether two different spellings of one locale match.
     */
    @Test
    void aDeclaredLocaleAndAMessageLocaleAreMatchedAsParsedLocalesNotAsText() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("header en_US · message en_US — the control", verdict(EN_ONLY, "[" + EN + "]"));
        actual.put("header en-US · message en_US", verdict("[{\"code\":\"en-US\"}]", "[" + EN + "]"));
        actual.put("header en_US · message en-US", verdict(EN_ONLY, "[{\"locale\":\"en-US\",\"text\":\"x\"}]"));
        actual.put("header en_us", verdict("[{\"code\":\"en_us\"}]", "[" + EN + "]"));
        actual.put("header as bare strings", verdict("[\"en_US\",\"de_DE\"]", "[" + EN + "," + DE + "]"));
        actual.put("header as bare strings, one message", verdict("[\"en_US\",\"de_DE\"]", "[" + EN + "]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("header en_US · message en_US — the control", ADMITTED);
        expected.put("header en-US · message en_US", ADMITTED);
        expected.put("header en_US · message en-US", ADMITTED);
        expected.put("header en_us", UNREADABLE);
        expected.put("header as bare strings", ADMITTED);
        expected.put("header as bare strings, one message", "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING");
        assertEquals(expected, actual, "how A12 matches a message's locale against the declared set");
    }

    /** The message-ENTRY rows: shapes A12's own {@code LocalizedTextMap} reader meets before any check runs. */
    @Test
    void aMessageEntryA12sReaderRefusesMakesTheModelUnreadableRatherThanInvalid() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("entry without a locale", verdict(EN_ONLY, "[{\"text\":\"x\"}]"));
        actual.put("entry without a text", verdict(EN_ONLY, "[{\"locale\":\"en_US\"}]"));
        actual.put("locale as a number", verdict(EN_ONLY, "[{\"locale\":5,\"text\":\"x\"}]"));
        actual.put("text as a number", verdict(EN_ONLY, "[{\"locale\":\"en_US\",\"text\":5}]"));
        actual.put("errorMessage as an object", verdict(EN_ONLY, "{\"a\":" + EN + "}"));
        actual.put("errorMessage as a string", verdict(EN_ONLY, "\"x\""));
        actual.put("errorMessage as a number", verdict(EN_ONLY, "5"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("entry without a locale", UNREADABLE);
        expected.put("entry without a text", UNREADABLE);
        expected.put("locale as a number", UNREADABLE);
        expected.put("text as a number", UNREADABLE);
        // NOT unreadable, and the row that keeps this table from being stated one notch too wide: A12's
        // reader iterates the node's ELEMENTS, which for an object are its values, so an object-wrapped
        // entry is read exactly as an array-wrapped one. It is the entry's own members that must be strings.
        expected.put("errorMessage as an object", ADMITTED);
        expected.put("errorMessage as a string", "MVK_ERROR_MESSAGE_MISSING");
        expected.put("errorMessage as a number", "MVK_ERROR_MESSAGE_MISSING");
        assertEquals(expected, actual, "what A12 does with a message entry its reader cannot take");
    }

    /**
     * The rows about which of several entries for ONE locale survives, and what counts as a text.
     *
     * <p>The duplicate-with-blank row is the discriminator: a reader that keeps the FIRST usable entry sees a
     * message and reports nothing, where A12 keeps the LAST and finds none. The plain duplicate above it is
     * the control that the order, not the duplication, is what decides.
     */
    @Test
    void theLastEntryForALocaleWinsAndABlankTextIsNoText() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("duplicate, both filled", verdict(EN_ONLY, "[" + EN + ",{\"locale\":\"en_US\",\"text\":\"z\"}]"));
        actual.put("duplicate, LATER blank", verdict(EN_ONLY, "[" + EN + ",{\"locale\":\"en_US\",\"text\":\"\"}]"));
        actual.put("duplicate, EARLIER blank", verdict(EN_ONLY, "[{\"locale\":\"en_US\",\"text\":\"\"}," + EN + "]"));
        actual.put("whitespace-only text", verdict(EN_ONLY, "[{\"locale\":\"en_US\",\"text\":\"   \"}]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("duplicate, both filled", ADMITTED);
        expected.put("duplicate, LATER blank", "MVK_ERROR_MESSAGE_MISSING");
        expected.put("duplicate, EARLIER blank", ADMITTED);
        expected.put("whitespace-only text", "MVK_ERROR_MESSAGE_MISSING");
        assertEquals(expected, actual, "which entry for one locale A12 keeps, and what counts as its text");
    }

    /**
     * What A12 says about the probe model with the given raw `header.locales` and rule `errorMessage`.
     *
     * <p>The two refusals arrive on DIFFERENT exception types, which is the distinction this whole class is
     * about: {@code ModelInputException} is A12's READER refusing the bytes, before any rule is examined;
     * {@code ModelConsistencyException} is its verdict on a model it did read. A checker that folds the first
     * into the second reports its own opinion as A12's about a model A12 never judged.
     */
    private static String verdict(String localesJson, String errorMessageJson) {
        try {
            BoundModel.ofJson(probe(localesJson, errorMessageJson));
            return ADMITTED;
        } catch (ModelInputException unreadable) {
            return UNREADABLE;
        } catch (ModelConsistencyException rejected) {
            String said = rejected.notifications().stream()
                    .map(n -> n.message())
                    .reduce("", (a, b) -> a + " " + b);
            Matcher codes = MVK.matcher(said);
            StringBuilder found = new StringBuilder();
            while (codes.find()) {
                if (found.indexOf(codes.group()) < 0) {
                    found.append(found.isEmpty() ? "" : ",").append(codes.group());
                }
            }
            // A rejection carrying no MVK_ code is the kernel refusing to BUILD the model from inside
            // checkConsistency — still not a verdict about its rules, and still not an input refusal.
            return found.isEmpty() ? "NOT BUILDABLE" : found.toString();
        }
    }

    /** The shared probe rule, with both raw fragments substituted verbatim into the writer's own output. */
    private static String probe(String localesJson, String errorMessageJson) {
        String written = ModelWriter.addRule(Fixtures.subscriptionJson(), "/Subscription", "MessageShapeProbe",
                "../Tier", "PROBE", "FieldNotFilled(Tier)", "ERROR", List.of(en("x"), de("y")));
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(written);
            ((ObjectNode) root.path("header")).set("locales", MAPPER.readTree(localesJson));
            ObjectNode rule = ruleBody(root);
            rule.set("errorMessage", MAPPER.readTree(errorMessageJson));
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the writer's own output and the raw fragments must parse", unreadable);
        }
    }

    /** The probe rule's body, refusing rather than silently returning a model the substitution never reached. */
    private static ObjectNode ruleBody(ObjectNode root) {
        for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                .path("Group").path("elements")) {
            if ("MessageShapeProbe".equals(element.path("name").asText())) {
                return (ObjectNode) element.path("Rule");
            }
        }
        throw new IllegalStateException("the probe rule is no longer where this test writes it");
    }
}
