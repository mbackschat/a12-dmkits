package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.de;
import static io.github.mbackschat.a12.dm.rulekit.dsl.Dsl.en;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;
import io.github.mbackschat.a12.dm.core.ModelConsistencyException;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.ValidationResult;
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

    private static final String ADMITTED = KernelLaws.ADMITTED;

    private static final String UNREADABLE = KernelLaws.UNREADABLE;

    /**
     * The unprefixed class that co-fires with a message verdict, and the reason it is spelled out per row.
     *
     * <p>A12 reports the rule's own "no error text" class beside the message code on some of these rows and
     * not on others, and the split is measured rather than left open: it co-fires exactly when the locale has
     * NO TEXT AT ALL — an absent row, or an empty string — and not when a text exists but is whitespace only.
     * {@link #whenTheUnprefixedNoErrorTextClassCoFiresWithAPerLocaleVerdict} holds the three-way comparison
     * that establishes it, on the carrier where the message class is the per-locale one, so the rule is not a
     * property of either message class. The rows that declare no locale at all are outside it: they draw
     * neither, because nothing is collected for them at all.
     *
     * <p>Every row here read {@code MVK_ERROR_MESSAGE_MISSING} alone until the harness stopped lifting
     * classes by an {@code MVK_} prefix, which silently dropped this one — see {@link KernelLaws#verdictOf}.
     */
    private static final String AND_NO_ERROR_TEXT = ",RULE_HAS_NO_ERRORTEXT";

    private static final String EN = "{\"locale\":\"en_US\",\"text\":\"x\"}";
    private static final String DE = "{\"locale\":\"de_DE\",\"text\":\"y\"}";
    private static final String EN_ONLY = "[{\"code\":\"en_US\"}]";

    private static final String BOTH_LOCALES = "[{\"code\":\"en_US\"},{\"code\":\"de_DE\"}]";

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
        actual.put("no declared locale, one message", verdict("[]", "[" + EN + "]"));
        actual.put("no declared locale, no message", verdict("[]", "[]"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("header en_US · message en_US — the control", ADMITTED);
        expected.put("header en-US · message en_US", ADMITTED);
        expected.put("header en_US · message en-US", ADMITTED);
        expected.put("header en_us", UNREADABLE);
        expected.put("header as bare strings", ADMITTED);
        expected.put("header as bare strings, one message",
                "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING" + AND_NO_ERROR_TEXT);
        // A model declaring NO locale is invalid in its own right (MVK_SUPP_LANGUAGES_MISSING) AND collects
        // no message at all, so its rules are message-less whatever they carry. The pair is the row that
        // separates "this locale has no text" from "there is no text": a message for an undeclared locale is
        // never collected, so it cannot cover anything.
        String noLocales = "MVK_SUPP_LANGUAGES_MISSING,MVK_ERROR_MESSAGE_MISSING";
        expected.put("no declared locale, one message", noLocales);
        expected.put("no declared locale, no message", noLocales);
        assertEquals(expected, actual, "how A12 matches a message's locale against the declared set");
    }

    /**
     * Which locale SPELLINGS A12's reader takes at all — the accept/refuse boundary a checker must mirror,
     * since normalizing a spelling A12 refuses would analyse a model that does not load.
     *
     * <p>The message carries the same code as the header in every row, so the only thing under test is
     * whether the code is readable; a row that admits says nothing about matching, which the table above
     * covers.
     */
    @Test
    void aLocaleCodeIsTakenOnlyInTheShapeA12sParserAccepts() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String code : List.of("en_US", "en-US", "en", "fr_CA", "en_US_POSIX", "en_us", "EN_US", "EN",
                "e", "eng", "en_U", "en_USA", "en_123", "", "klingon")) {
            actual.put(code.isEmpty() ? "(empty)" : code, verdict("[{\"code\":\"" + code + "\"}]",
                    "[{\"locale\":\"" + code + "\",\"text\":\"x\"}]"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("en_US", ADMITTED);
        expected.put("en-US", ADMITTED);
        expected.put("en", ADMITTED);
        expected.put("fr_CA", ADMITTED);
        expected.put("en_US_POSIX", ADMITTED);
        expected.put("en_us", UNREADABLE);
        expected.put("EN_US", UNREADABLE);
        expected.put("EN", UNREADABLE);
        expected.put("e", UNREADABLE);
        expected.put("eng", ADMITTED);
        expected.put("en_U", UNREADABLE);
        expected.put("en_USA", UNREADABLE);
        expected.put("en_123", ADMITTED);
        // The empty code READS — it is the "any locale" value — and is then refused a tier later, by the
        // kernel's own model build rather than by its reader. Kept distinct for the same reason as UNREADABLE.
        expected.put("(empty)", "NOT BUILDABLE");
        expected.put("klingon", UNREADABLE);
        assertEquals(expected, actual, "which locale spellings A12's reader accepts");
    }

    /**
     * The same spellings on the MESSAGE side alone — the table above moves both sides at once, so every one
     * of its refusals is attributable to the header and says nothing about a message's own `locale`.
     *
     * <p>That confound matters to a checker rather than being pedantry: if only the header were parsed, a
     * message locale could be compared as text and a refused spelling would merely fail to match. The rows
     * below hold the header at the canonical {@code en_US} and vary only the message.
     */
    @Test
    void aMessageLocaleIsReadByTheSameParserAsADeclaredCode() {
        Map<String, String> actual = new LinkedHashMap<>();
        for (String code : List.of("en_us", "EN_US", "klingon", "en_U", "")) {
            actual.put(code.isEmpty() ? "(empty)" : code,
                    verdict(EN_ONLY, "[{\"locale\":\"" + code + "\",\"text\":\"x\"}]"));
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("en_us", UNREADABLE);
        expected.put("EN_US", UNREADABLE);
        expected.put("klingon", UNREADABLE);
        expected.put("en_U", UNREADABLE);
        // The empty code reads on this side too, and covers no declared language, so the rule is left with
        // no message at all rather than the model being unbuildable — the header's own empty-code row is
        // what makes a model unbuildable, and only there.
        expected.put("(empty)", "MVK_ERROR_MESSAGE_MISSING" + AND_NO_ERROR_TEXT);
        assertEquals(expected, actual, "which locale spellings a message entry accepts");
    }

    /**
     * A model that declares no locale is refused for THAT, with no rule involved — the row that keeps the
     * code attributable.
     *
     * <p>Every other no-locale row in this class carries the probe rule, so {@code MVK_SUPP_LANGUAGES_MISSING}
     * and {@code MVK_ERROR_MESSAGE_MISSING} always arrive together and neither can be attributed. The base
     * fixture declares no rule at all, which removes the second.
     */
    @Test
    void aModelDeclaringNoLocaleIsRefusedWithoutAnyRuleInvolved() {
        String noLocales = withoutDeclaredLocales(Fixtures.subscriptionJson());
        ModelConsistencyException rejected =
                assertThrows(ModelConsistencyException.class, () -> BoundModel.ofJson(noLocales));
        assertTrue(rejected.getMessage().contains("MVK_SUPP_LANGUAGES_MISSING"),
                () -> "expected MVK_SUPP_LANGUAGES_MISSING, kernel said: " + rejected.getMessage());
        assertFalse(rejected.getMessage().contains("MVK_ERROR_MESSAGE"),
                () -> "no rule is declared, so no message code may appear: " + rejected.getMessage());
    }

    /**
     * The BARE locale, which [KF158](KERNEL-FINDINGS.md#kf158) records as "not supported" at the validation
     * tier — measured here because the table above admits it, and a disagreement with a clause we already
     * hold is the hypothesis to test rather than a row to quietly overwrite.
     *
     * <p><b>Both paths admit it.</b> A stored model whose header and messages both say {@code en} passes
     * {@code checkConsistency}, and {@code RuleValidator.check} — which injects a placeholder error text per
     * declared locale, the mechanism most likely to need a full code — accepts the same model. So the
     * incumbent row does not hold for this shape on either path.
     *
     * <p><b>What this does NOT establish.</b> The original fixture is not reproduced here; it may have paired
     * a bare header locale with FULL message codes, which is a locale-MATCHING question and would draw
     * {@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING} for a reason that has nothing to do with support. That
     * cell is the second row below. The consequence for a reader is the same either way: refusing a bare code
     * outright would reject a model A12 admits.
     */
    @Test
    void aBareLocaleIsAdmittedByBothTheStoredAndTheDraftPath() {
        String model = probe("[{\"code\":\"en\"}]", "[{\"locale\":\"en\",\"text\":\"x\"}]");

        assertEquals(ADMITTED, verdict("[{\"code\":\"en\"}]", "[{\"locale\":\"en\",\"text\":\"x\"}]"),
                "a stored model declaring a bare locale is consistency-valid");
        ValidationResult viaDraft = KernelLaws.checkModel(model, "/Subscription", "../Tier",
                "FieldNotFilled(Tier)");
        assertTrue(viaDraft.ok(),
                () -> "the draft validator injects a text per declared locale and accepts a bare one: "
                        + viaDraft.diagnostics());

        // …and the matching cell, which answers with the OTHER code: a message whose locale is not declared
        // is never collected at all, so the rule has no message for any declared language rather than one
        // missing for a particular language. That is the rule the two codes actually split on.
        assertEquals("MVK_ERROR_MESSAGE_MISSING" + AND_NO_ERROR_TEXT,
                verdict("[{\"code\":\"en\"}]", "[" + EN + "]"),
                "a bare declared locale is not matched by a full message code — they are different locales");
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
        expected.put("errorMessage as a string", "MVK_ERROR_MESSAGE_MISSING" + AND_NO_ERROR_TEXT);
        expected.put("errorMessage as a number", "MVK_ERROR_MESSAGE_MISSING" + AND_NO_ERROR_TEXT);
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
        expected.put("duplicate, LATER blank", "MVK_ERROR_MESSAGE_MISSING" + AND_NO_ERROR_TEXT);
        expected.put("duplicate, EARLIER blank", ADMITTED);
        expected.put("whitespace-only text", "MVK_ERROR_MESSAGE_MISSING");
        assertEquals(expected, actual, "which entry for one locale A12 keeps, and what counts as its text");
    }

    /** What A12 says about the probe model with the given raw `header.locales` and rule `errorMessage`. */
    /**
     * WHEN the unprefixed no-error-text class co-fires, on the carrier where a SECOND locale is answered.
     *
     * <p>The rows above leave the split unexplained because they all sit on a one-locale header, where the
     * verdict is {@code MVK_ERROR_MESSAGE_MISSING}. Declaring a second locale moves the class to
     * {@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING} and the co-fire travels with it, so the two carriers
     * together say the split is not a property of either message class.
     *
     * <p><b>It keys on whether the locale has any text at all.</b> An absent row and an empty string both
     * draw the second class; a whitespace-only text does not, and that row is the discriminator — a reading
     * that keyed on "the row is missing" predicts the blank row draws the class alone, and it draws both. The
     * absent-row cell is also measured on the bare-string header above; it earns its place here because the
     * mechanism needs all three cells side by side on one carrier, not because the verdict is new.
     */
    @Test
    void whenTheUnprefixedNoErrorTextClassCoFiresWithAPerLocaleVerdict() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both locales answered — the control", verdict(BOTH_LOCALES, "[" + EN + "," + DE + "]"));
        actual.put("the second locale's text is BLANK",
                verdict(BOTH_LOCALES, "[" + EN + ",{\"locale\":\"de_DE\",\"text\":\"\"}]"));
        actual.put("the second locale's text is WHITESPACE",
                verdict(BOTH_LOCALES, "[" + EN + ",{\"locale\":\"de_DE\",\"text\":\"   \"}]"));
        actual.put("the second locale's ROW is absent", verdict(BOTH_LOCALES, "[" + EN + "]"));

        String perLocale = "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING";
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both locales answered — the control", ADMITTED);
        expected.put("the second locale's text is BLANK", perLocale + AND_NO_ERROR_TEXT);
        // The discriminator: a text exists, so the rule is not textless — only the locale is unanswered.
        expected.put("the second locale's text is WHITESPACE", perLocale);
        expected.put("the second locale's ROW is absent", perLocale + AND_NO_ERROR_TEXT);
        assertEquals(expected, actual, "when the unprefixed no-error-text class co-fires");
    }

    private static String verdict(String localesJson, String errorMessageJson) {
        return KernelLaws.verdictOf(probe(localesJson, errorMessageJson));
    }

    /** [model] with an empty `header.locales`, refusing rather than passing input through unchanged. */
    private static String withoutDeclaredLocales(String model) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(model);
            ObjectNode header = (ObjectNode) root.path("header");
            if (!header.has("locales")) {
                throw new IllegalStateException("the fixture no longer declares `header.locales`");
            }
            header.set("locales", MAPPER.createArrayNode());
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
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
