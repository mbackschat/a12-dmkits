package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.Fixtures;

/**
 * A declared string type's {@code pattern} and its own {@code errorMessage} are ONE law with two directions.
 *
 * <p>{@link DeclaredPatternGateLawsTest} measures WHICH patterns A12 admits, and its fixture supplies an
 * error message beside every pattern with a comment saying one is required — a claim nothing in that grid
 * could have tested, because no row varies the message. This grid varies it, on both arms, which is what
 * separates a co-requirement from two independent slots.
 *
 * <p>The two codes are near-anagrams of each other and neither name says which direction it carries, so the
 * rows are the only statement of that: {@code MVK_ERROR_MESSAGE_AND_NO_PATTERN} rides the message, and
 * {@code MVK_ERROR_MESSAGE_AND_PATTERN_INVALID} rides the pattern.
 *
 * <p>Own domain (the shared order fixture), on {@code /Order/CustomerName}.
 */
class StringTypeMessageAndPatternLawsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The fixture's one declared locale, so a message reaches the locale A12 collects for. */
    private static final String DECLARED_LOCALE = "en_US";

    private static final String MESSAGE_WITHOUT_PATTERN = "MVK_ERROR_MESSAGE_AND_NO_PATTERN";
    private static final String PATTERN_WITHOUT_MESSAGE = "MVK_ERROR_MESSAGE_AND_PATTERN_INVALID";

    /**
     * The second tier: the message exists for SOME declared locale but not for all of them.
     *
     * <p>The same code a rule's own {@code errorMessage} draws, which is why this carrier had to be measured
     * rather than assumed to answer with the pattern-specific code beside it.
     */
    private static final String LANGUAGE_MISSING = "MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING";

    /** The order fixture with {@code CustomerName} carrying a {@code StringType} [body] builds. */
    private static String storedModelWith(Consumer<ObjectNode> body) {
        return storedModelWith(body, DECLARED_LOCALE);
    }

    /** …and the header declaring exactly [locales], so a message's reach can be varied against them. */
    private static String storedModelWith(Consumer<ObjectNode> body, String... locales) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(Fixtures.orderJson());
            var declared = ((ObjectNode) root.path("header")).putArray("locales");
            for (String locale : locales) {
                declared.addObject().put("code", locale);
            }
            for (var element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("CustomerName".equals(element.path("name").asText()) && element.has("Field")) {
                    ObjectNode fieldType = MAPPER.createObjectNode();
                    fieldType.put("type", "StringType");
                    body.accept(fieldType.putObject("StringType"));
                    ((ObjectNode) element.path("Field")).set("fieldType", fieldType);
                    return root.toString();
                }
            }
            throw new IllegalStateException("the fixture no longer declares the root field `CustomerName`");
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the fixture must parse", unreadable);
        }
    }

    private static void message(ObjectNode stringType, String locale, String text) {
        stringType.putArray("errorMessage").addObject().put("locale", locale).put("text", text);
    }

    /** Each row's string-type body, keyed by what it varies. */
    private static Map<String, Consumer<ObjectNode>> grid() {
        Map<String, Consumer<ObjectNode>> rows = new LinkedHashMap<>();
        rows.put("neither member — the control", stringType -> stringType.put("maxLength", 40));
        rows.put("an empty pattern without a message", stringType -> stringType.put("pattern", ""));
        rows.put("an empty pattern with a message", stringType -> {
            stringType.put("pattern", "");
            message(stringType, DECLARED_LOCALE, "the reference is malformed");
        });
        rows.put("a pattern and its error message", stringType -> {
            stringType.put("pattern", "[A-Z]{2}[0-9]+");
            message(stringType, DECLARED_LOCALE, "the reference is malformed");
        });
        rows.put("a pattern with NO error message",
                stringType -> stringType.put("pattern", "[A-Z]{2}[0-9]+"));
        rows.put("an error message with NO pattern",
                stringType -> message(stringType, DECLARED_LOCALE, "the reference is malformed"));

        // What "carries a message" means, once the pair is known to be a co-requirement. Each row holds the
        // pattern fixed and weakens the message differently, so an implementation reading only the member's
        // PRESENCE and one reading its CONTENT disagree here and nowhere else.
        rows.put("a pattern with an EMPTY message list", stringType -> {
            stringType.put("pattern", "[A-Z]{2}[0-9]+");
            stringType.putArray("errorMessage");
        });
        rows.put("a pattern with a BLANK message text", stringType -> {
            stringType.put("pattern", "[A-Z]{2}[0-9]+");
            message(stringType, DECLARED_LOCALE, "   ");
        });
        rows.put("a pattern with a message for an UNDECLARED locale", stringType -> {
            stringType.put("pattern", "[A-Z]{2}[0-9]+");
            message(stringType, "de_DE", "die Referenz ist fehlerhaft");
        });

        // The same three weakenings on the OTHER arm, and they settle what the message-side code is
        // triggered by: not the member being THERE, but its answering a locale A12 collects for. All three
        // are ADMITTED, so both arms read the member through one predicate and a checker needs only that
        // one. The rows above cannot show this, because every one of them carries a pattern.
        rows.put("an EMPTY message list and no pattern",
                stringType -> stringType.putArray("errorMessage"));
        rows.put("a BLANK message text and no pattern",
                stringType -> message(stringType, DECLARED_LOCALE, "   "));
        rows.put("a message for an UNDECLARED locale and no pattern",
                stringType -> message(stringType, "de_DE", "die Referenz ist fehlerhaft"));

        // Where the reader stops and the verdict starts, which is not where the member's SHAPE suggests.
        // A scalar at the member BINDS — A12 collects no message from it and answers with the ordinary
        // consistency code — while a non-object ENTRY refuses the source outright, so the model never loads
        // and no verdict about it exists. Measured on this carrier rather than carried over from the rule
        // carrier that shares the member name; they agree, and that agreement is the reason one reader can
        // serve both.
        rows.put("a pattern with a message member that is not a list", stringType -> {
            stringType.put("pattern", "[A-Z]{2}[0-9]+");
            stringType.put("errorMessage", "the reference is malformed");
        });
        rows.put("a pattern with a message entry that is not an object", stringType -> {
            stringType.put("pattern", "[A-Z]{2}[0-9]+");
            stringType.putArray("errorMessage").add("the reference is malformed");
        });
        return rows;
    }

    @Test
    void aDeclaredPatternAndItsOwnErrorMessageRequireEachOther() {
        Map<String, String> actual = new LinkedHashMap<>();
        grid().forEach((row, body) -> actual.put(row, KernelLaws.verdictOf(storedModelWith(body))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("neither member — the control", KernelLaws.ADMITTED);
        expected.put("an empty pattern without a message", KernelLaws.ADMITTED);
        expected.put("an empty pattern with a message", MESSAGE_WITHOUT_PATTERN);
        expected.put("a pattern and its error message", KernelLaws.ADMITTED);
        expected.put("a pattern with NO error message", PATTERN_WITHOUT_MESSAGE);
        expected.put("an error message with NO pattern", MESSAGE_WITHOUT_PATTERN);
        expected.put("a pattern with an EMPTY message list", PATTERN_WITHOUT_MESSAGE);
        expected.put("a pattern with a BLANK message text", PATTERN_WITHOUT_MESSAGE);
        expected.put("a pattern with a message for an UNDECLARED locale", PATTERN_WITHOUT_MESSAGE);
        expected.put("an EMPTY message list and no pattern", KernelLaws.ADMITTED);
        expected.put("a BLANK message text and no pattern", KernelLaws.ADMITTED);
        expected.put("a message for an UNDECLARED locale and no pattern", KernelLaws.ADMITTED);
        expected.put("a pattern with a message member that is not a list", PATTERN_WITHOUT_MESSAGE);
        expected.put("a pattern with a message entry that is not an object", KernelLaws.UNREADABLE);
        assertEquals(expected, actual, "how A12 pairs a declared string pattern with its error message");
    }

    /** A bilingual body carrying [texts] keyed by locale, beside the ordinary pattern. */
    private static Consumer<ObjectNode> patternWithMessagesFor(Map<String, String> texts) {
        return stringType -> {
            stringType.put("pattern", "[A-Z]{2}[0-9]+");
            var messages = stringType.putArray("errorMessage");
            texts.forEach((locale, text) -> messages.addObject().put("locale", locale).put("text", text));
        };
    }

    private static Map<String, String> textsFor(String... localeThenText) {
        Map<String, String> texts = new LinkedHashMap<>();
        for (int i = 0; i < localeThenText.length; i += 2) {
            texts.put(localeThenText[i], localeThenText[i + 1]);
        }
        return texts;
    }

    /**
     * WHICH locales the message must answer — the axis every row of the grid above holds constant.
     *
     * <p>That grid's undeclared-locale row proves the message is read against the header's locale set rather
     * than merely counted, and on a one-locale header "every declared locale" and "some declared locale" are
     * the same claim. A checker built from those rows alone would have had to guess between them, and the
     * two disagree on the shape a bilingual model most often carries: a message translated for one language
     * and not yet for the other.
     *
     * <p>What the rows actually say is that the pair is a TWO-TIER law, which no amount of reading the two
     * code names would give. Answering no declared locale at all is the pattern's own code; answering some
     * but not all is {@link #LANGUAGE_MISSING}, the same code a rule's {@code errorMessage} draws — so a
     * consumer keying on the pattern code alone sees a bilingual gap as clean.
     */
    @Test
    void whichDeclaredLocalesThePatternMessageMustAnswer() {
        Map<String, Consumer<ObjectNode>> rows = new LinkedHashMap<>();
        rows.put("both declared locales answered",
                patternWithMessagesFor(textsFor(DECLARED_LOCALE, "malformed", "de_DE", "fehlerhaft")));
        rows.put("only the FIRST declared locale answered",
                patternWithMessagesFor(textsFor(DECLARED_LOCALE, "malformed")));
        rows.put("only the SECOND declared locale answered",
                patternWithMessagesFor(textsFor("de_DE", "fehlerhaft")));
        rows.put("one locale answered, the other BLANK",
                patternWithMessagesFor(textsFor(DECLARED_LOCALE, "malformed", "de_DE", "   ")));
        rows.put("NEITHER declared locale answered, and one undeclared one is",
                patternWithMessagesFor(textsFor("fr_FR", "mal forme")));
        // The other arm, on the same two-locale header. It draws BOTH codes, which is the row that says the
        // locale-completeness check is not a branch of the pattern law: it runs on the message member alone,
        // whether or not the pattern that makes the member legal is even there.
        rows.put("a partial message set with NO pattern", stringType ->
                message(stringType, DECLARED_LOCALE, "malformed"));

        Map<String, String> actual = new LinkedHashMap<>();
        rows.forEach((row, body) -> actual.put(row,
                KernelLaws.verdictOf(storedModelWith(body, DECLARED_LOCALE, "de_DE"))));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both declared locales answered", KernelLaws.ADMITTED);
        expected.put("only the FIRST declared locale answered", LANGUAGE_MISSING);
        expected.put("only the SECOND declared locale answered", LANGUAGE_MISSING);
        expected.put("one locale answered, the other BLANK", LANGUAGE_MISSING);
        expected.put("NEITHER declared locale answered, and one undeclared one is", PATTERN_WITHOUT_MESSAGE);
        expected.put("a partial message set with NO pattern", LANGUAGE_MISSING + "," + MESSAGE_WITHOUT_PATTERN);
        assertEquals(expected, actual, "which declared locales a pattern's error message must answer");
    }
}
