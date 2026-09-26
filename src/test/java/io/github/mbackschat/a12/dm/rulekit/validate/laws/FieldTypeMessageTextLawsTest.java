package io.github.mbackschat.a12.dm.rulekit.validate.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldModification;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;
import io.github.mbackschat.a12.dm.modelkit.StringFieldBuilder;
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * What A12's check of a field type's OWN message asks beyond its characters, and which messages it reaches.
 *
 * <p>{@code FieldErrortextChecker} (source-read, kernel {@code 30.8.1}) asks, in order and stopping at the first
 * that fails: whether UTF-16 encodes the text, the two character gates of {@link ErrorTextCharacterGateLawsTest},
 * an odd {@code $} count, and that every part between two {@code $} is {@code field} or {@code field.value}
 * ({@code Feld}, {@code Feld.Wert} in a German condition language). {@code FieldUtils.getErrorText} hands it a
 * String type's and an Enumeration type's {@code errorMessage}, one declared locale at a time. Each row here
 * puts one of those claims to the Kernel, and they held: the gates answer in that order, the parameter names
 * follow the CONDITION language rather than the message's locale, the locales are asked in their declared order
 * and the first that fails is the only one reported, a String message is judged with or without a pattern, and
 * neither a required field's message nor a text for an undeclared locale is reached. The parts between two
 * {@code $} are Java's {@code split}, so an empty pair at the very end names no parameter; the later of two
 * entries for one locale is the one examined; and a field whose path A12's alphabet refuses, its own name's or an
 * enclosing group's, is never reached at all ({@code NoMetaModelChecks.checkEntity} returns before it).
 *
 * <p>Own domain (insurance claims).
 */
class FieldTypeMessageTextLawsTest {

    /** A claims model whose {@code Code} field declares the pattern {@code [A-Z]+} with the given messages. */
    private static String patterned(Consumer<StringFieldBuilder> message, String... locales) {
        DocumentModelDraft.Builder model = DocumentModelDraft.named("claims-field-messages");
        for (String locale : locales) {
            model.locale(locale);
        }
        return model.rootGroup("Claim", c -> c
                        .stringField("Reference", f -> { })
                        .stringField("Code", message))
                .build().toJson();
    }

    private static String onThePattern(String text) {
        return KernelLaws.verdictOf(patterned(f -> f.pattern("[A-Z]+", text), "en_US"));
    }

    private static String inGerman(String text) {
        EditSession session = EditSession.read(patterned(f -> f.pattern("[A-Z]+", text), "en_US"));
        session.config().conditionLanguage("de_DE");
        return KernelLaws.verdictOf(session.toJson());
    }

    @Test
    void whatThePatternsMessageMayCarryBeyondItsCharacters() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("plain, the control", onThePattern("capitals only"));
        actual.put("$field$", onThePattern("$field$ takes capitals"));
        actual.put("$field.value$", onThePattern("$field.value$ is not capitals"));
        actual.put("an odd dollar count", onThePattern("$field takes capitals"));
        actual.put("$Field$, the wrong case", onThePattern("$Field$ takes capitals"));
        actual.put("the field's own name", onThePattern("$Code$ takes capitals"));
        actual.put("an empty pair", onThePattern("cost $$ in capitals"));
        actual.put("the German spelling in an English model", onThePattern("$Feld$ takes capitals"));
        actual.put("a tab beside an odd dollar count", onThePattern("$field\ttakes capitals"));
        actual.put("an odd count beside a wrong name", onThePattern("$Code$ and $field"));
        actual.put("German: $Feld$", inGerman("$Feld$ takes capitals"));
        actual.put("German: $Feld.Wert$", inGerman("$Feld.Wert$ is not capitals"));
        actual.put("German: $field$", inGerman("$field$ takes capitals"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("plain, the control", KernelLaws.ADMITTED);
        expected.put("$field$", KernelLaws.ADMITTED);
        expected.put("$field.value$", KernelLaws.ADMITTED);
        expected.put("an odd dollar count", "WRONG_NUMBER_OF_DOLLARS");
        expected.put("$Field$, the wrong case", "INVALID_PARAMETER");
        expected.put("the field's own name", "INVALID_PARAMETER");
        expected.put("an empty pair", "INVALID_PARAMETER");
        expected.put("the German spelling in an English model", "INVALID_PARAMETER");
        expected.put("a tab beside an odd dollar count", "INVALID_CHARACTERS");
        expected.put("an odd count beside a wrong name", "WRONG_NUMBER_OF_DOLLARS");
        expected.put("German: $Feld$", KernelLaws.ADMITTED);
        expected.put("German: $Feld.Wert$", KernelLaws.ADMITTED);
        expected.put("German: $field$", "INVALID_PARAMETER");
        assertEquals(expected, actual, "the pattern message's parameter gates");
    }

    /**
     * Which messages the checker reaches, and how many locales one field reports.
     *
     * <p>The two "both locales" rows differ only in the order the locales are declared, and the reported class
     * follows it: {@code en_US} carries a tab and {@code de_DE} a line feed, so the class names the locale asked.
     */
    @Test
    void whichMessagesItReachesAndHowManyLocalesReport() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("both locales defective", KernelLaws.verdictOf(patterned(f -> f.pattern("[A-Z]+", "a\tb")
                .patternMessage("de_DE", "a\nb"), "en_US", "de_DE")));
        actual.put("the second locale alone defective", KernelLaws.verdictOf(patterned(f -> f.pattern("[A-Z]+", "ab")
                .patternMessage("de_DE", "a\tb"), "en_US", "de_DE")));
        actual.put("the enum's invalid-value message, a tab", enumerated("a\tb"));
        actual.put("the enum's invalid-value message, a wrong name", enumerated("$Status$ is not allowed"));
        actual.put("the enum's invalid-value message, $field$", enumerated("$field$ is not allowed"));
        actual.put("a required field's message, a tab", required("a\tb"));
        actual.put("both locales defective, declared the other way round", KernelLaws.verdictOf(patterned(
                f -> f.pattern("[A-Z]+", "a\tb").patternMessage("de_DE", "a\nb"), "de_DE", "en_US")));
        actual.put("a message with no pattern, a tab", unpatterned("a\tb"));
        actual.put("a tab in an undeclared locale's message", inAnUndeclaredLocale("a\tb"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("both locales defective", "INVALID_CHARACTERS");
        expected.put("the second locale alone defective", "INVALID_CHARACTERS");
        expected.put("the enum's invalid-value message, a tab", "INVALID_CHARACTERS");
        expected.put("the enum's invalid-value message, a wrong name", "INVALID_PARAMETER");
        expected.put("the enum's invalid-value message, $field$", KernelLaws.ADMITTED);
        expected.put("a required field's message, a tab", KernelLaws.ADMITTED);
        expected.put("both locales defective, declared the other way round", "INVALID_LINEBREAK");
        expected.put("a message with no pattern, a tab", "INVALID_CHARACTERS,MVK_ERROR_MESSAGE_AND_NO_PATTERN");
        expected.put("a tab in an undeclared locale's message", KernelLaws.ADMITTED);
        assertEquals(expected, actual, "which field messages the checker reaches");
    }

    /**
     * The locales are asked in the order the model DECLARES them, not the order the entries are written in.
     *
     * <p>The two rows of {@link #whichMessagesItReachesAndHowManyLocalesReport} flip both orders together, since
     * the typed builder writes the entries in declared order; these reverse the entries alone, so the four rows
     * together are the two-by-two that separates the two orders.
     */
    @Test
    void theLocalesAreAskedInDeclaredOrderNotEntryOrder() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("de_DE declared first, the en_US entry written first", edited(patterned(f -> f
                .pattern("[A-Z]+", "a\tb").patternMessage("de_DE", "a\nb"), "de_DE", "en_US"), FieldTypeMessageTextLawsTest::reversed));
        actual.put("en_US declared first, the de_DE entry written first", edited(patterned(f -> f
                .pattern("[A-Z]+", "a\tb").patternMessage("de_DE", "a\nb"), "en_US", "de_DE"), FieldTypeMessageTextLawsTest::reversed));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("de_DE declared first, the en_US entry written first", "INVALID_LINEBREAK");
        expected.put("en_US declared first, the de_DE entry written first", "INVALID_CHARACTERS");
        assertEquals(expected, actual, "which order the locales are asked in");
    }

    /**
     * How the text is cut into parameters, and which of two entries for one locale is examined.
     *
     * <p>The parts between two {@code $} are what Java's {@code String.split} returns, and that drops TRAILING
     * empty parts: an empty pair at the end of a text is no parameter at all, where the same pair inside it is
     * an empty one. A reader pairing the dollars itself refuses the first.
     */
    @Test
    void howTheTextIsCutAndWhichEntryIsExamined() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("an empty pair at the end", onThePattern("capitals only $$"));
        actual.put("an empty pair alone", onThePattern("$$"));
        actual.put("an empty pair at the start", onThePattern("$$ capitals only"));
        actual.put("two parameters back to back", onThePattern("$field$$field.value$"));
        actual.put("a lone low surrogate", onThePattern("a\uDE00b"));
        actual.put("two en_US entries, the first defective", edited(patterned(f -> f.pattern("[A-Z]+", "capitals only"), "en_US"),
                fieldType -> ((ArrayNode) fieldType.path("StringType").path("errorMessage")).insertObject(0)
                        .put("locale", "en_US").put("text", "a\tb")));
        actual.put("two en_US entries, the second defective", edited(patterned(f -> f.pattern("[A-Z]+", "capitals only"), "en_US"),
                fieldType -> ((ArrayNode) fieldType.path("StringType").path("errorMessage")).addObject()
                        .put("locale", "en_US").put("text", "a\tb")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("an empty pair at the end", KernelLaws.ADMITTED);
        expected.put("an empty pair alone", KernelLaws.ADMITTED);
        expected.put("an empty pair at the start", "INVALID_PARAMETER");
        expected.put("two parameters back to back", KernelLaws.ADMITTED);
        expected.put("a lone low surrogate", "WRONG_ENCODING");
        // The later entry for one locale replaces the earlier, so only the second row's tab is examined.
        expected.put("two en_US entries, the first defective", KernelLaws.ADMITTED);
        expected.put("two en_US entries, the second defective", "INVALID_CHARACTERS");
        assertEquals(expected, actual, "how the text is cut, and which entry is examined");
    }

    /**
     * A12 walks the model's elements and stops at one whose PATH its alphabet refuses, beneath it included, so
     * the message of a field it never reaches is never judged. A dotted field name is the discriminating row:
     * it draws a name class of its own and its path passes, so the message is still asked.
     */
    @Test
    void anInvalidPathStopsTheCheck() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a field name outside the path alphabet, a tab", placed(null, "Code\u00E9", "a\tb"));
        actual.put("a field name outside the path alphabet, plain", placed(null, "Code\u00E9", "capitals only"));
        actual.put("a digit-initial field name, a tab", placed(null, "1Code", "a\tb"));
        actual.put("a dotted field name, a tab", placed(null, "Co.de", "a\tb"));
        actual.put("beneath a group outside the path alphabet, a tab", placed("Part\u00E9", "Code", "a\tb"));
        actual.put("beneath a group outside the path alphabet, plain", placed("Part\u00E9", "Code", "capitals only"));
        actual.put("beneath a group with an umlaut, a tab", placed("Teil\u00E4", "Code", "a\tb"));
        actual.put("a root group with an umlaut, a tab", placed("Schaden\u00E4", null, "Code", "a\tb"));
        actual.put("a digit-initial root group, a tab", placed("1Claim", null, "Code", "a\tb"));
        actual.put("a field name with a colon, a tab", placed(null, "Co:de", "a\tb"));
        actual.put("a field name with a leading underscore, a tab", placed(null, "_Code", "a\tb"));
        actual.put("beneath a digit-initial group, a tab", placed("1Part", "Code", "a\tb"));
        actual.put("a field name with a digit after its dot, a tab", placed(null, "Co.1de", "a\tb"));
        actual.put("a field name carrying a slash, a tab",
                KernelLaws.verdictOf(renamed(placedJson("Claim", null, "Code", "a\tb"), "Code", "Co/de")));
        actual.put("beneath a group whose name carries a slash, a tab",
                KernelLaws.verdictOf(renamed(placedJson("Claim", "Part", "Code", "a\tb"), "Part", "Pa/rt")));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a field name outside the path alphabet, a tab", "MVK_PATH_INVALID");
        expected.put("a field name outside the path alphabet, plain", "MVK_PATH_INVALID");
        expected.put("a digit-initial field name, a tab", "MVK_PATH_INVALID,MVK_NAME_STARTS_WITH_DIGIT_A12_DV");
        expected.put("a dotted field name, a tab", "INVALID_CHARACTERS,MVK_NAME_INVALID_DOT_COLON_A12_DV");
        expected.put("beneath a group outside the path alphabet, a tab", "MVK_PATH_INVALID");
        expected.put("beneath a group outside the path alphabet, plain", "MVK_PATH_INVALID");
        // A group's path alphabet takes the German umlauts, so this group's field is reached and judged.
        expected.put("beneath a group with an umlaut, a tab", "INVALID_CHARACTERS");
        expected.put("a root group with an umlaut, a tab", "INVALID_CHARACTERS");
        // The root group's own alphabet takes a leading digit; the digit class beside it stops nothing.
        expected.put("a digit-initial root group, a tab", "INVALID_CHARACTERS,MVK_NAME_STARTS_WITH_DIGIT");
        expected.put("a field name with a colon, a tab", "INVALID_CHARACTERS,MVK_NAME_INVALID_DOT_COLON_A12_DV");
        expected.put("a field name with a leading underscore, a tab", "INVALID_CHARACTERS");
        expected.put("beneath a digit-initial group, a tab", "MVK_PATH_INVALID");
        // After a dot the path alphabet wants a letter, so this dot fails the PATH where `Co.de` passes it.
        expected.put("a field name with a digit after its dot, a tab", "MVK_PATH_INVALID");
        // A name carrying the separator makes the path disagree with the tree, and that stops the walk too.
        expected.put("a field name carrying a slash, a tab", "MVK_PATHS_INCONSISTENT");
        expected.put("beneath a group whose name carries a slash, a tab", "MVK_PATHS_INCONSISTENT");
        assertEquals(expected, actual, "whether an invalid path stops the check");
    }

    /**
     * A field has no condition, so nothing a RULE's condition does can withhold its message: A12's field walk is
     * its own pass. Each row pairs a defective field message with a rule in the same model whose condition
     * A12 refuses or whose error target names nothing, and the field's class stands beside the rule's.
     */
    @Test
    void aRulesRefusalElsewhereWithholdsNothing() {
        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("a refused condition beside it", besideARule("FieldFilled(Reference) And And FieldFilled(Code)",
                "../Reference"));
        actual.put("a broken error target beside it", besideARule("FieldFilled(Reference)", "../NoSuchField"));

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("a refused condition beside it", "INVALID_CHARACTERS,MVK_UNEXPECTED_TOKEN");
        expected.put("a broken error target beside it", "INVALID_CHARACTERS,MVK_ERROR_FIELD_MISSING,MVK_MISSING_ERROR_FIELD");
        assertEquals(expected, actual, "whether a rule's refusal withholds a field's message class");
    }

    private static String besideARule(String condition, String target) {
        List<Diagnostic> diagnostics = new RuleValidator().check(patterned(f -> f.pattern("[A-Z]+", "a\tb"), "en_US"),
                new DraftRule("/Claim", "ProbeRule", target, condition, "PROBE", "ERROR",
                        List.of(new LocalizedText("en_US", "see the reference")))).diagnostics();
        return diagnostics.stream().map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(","));
    }

    /** A patterned field of the given name, in the root group or in a group of the given name beneath it. */
    private static String placed(String group, String field, String message) {
        return placed("Claim", group, field, message);
    }

    private static String placed(String root, String group, String field, String message) {
        return KernelLaws.verdictOf(placedJson(root, group, field, message));
    }

    private static String placedJson(String root, String group, String field, String message) {
        Consumer<StringFieldBuilder> patterned = f -> f.pattern("[A-Z]+", message);
        return DocumentModelDraft.named("claims-field-messages").locale("en_US")
                .rootGroup(root, c -> {
                    c.stringField("Reference", f -> { });
                    if (group == null) {
                        c.stringField(field, patterned);
                    } else {
                        c.group(group, g -> g.stringField(field, patterned));
                    }
                })
                .build().toJson();
    }

    /**
     * The model with the element named {@code from} renamed to {@code to}, wherever it sits: a name carrying
     * {@code /} is written into the tree, since the typed builder composes paths from names.
     */
    private static String renamed(String json, String from, String to) {
        try {
            JsonNode root = MAPPER.readTree(json);
            rename(root, from, to);
            return root.toString();
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the builder's own output must parse", unreadable);
        }
    }

    private static void rename(JsonNode node, String from, String to) {
        if (node instanceof ObjectNode object && from.equals(object.path("name").asText(null))
                && object.has("type")) {
            object.put("name", to);
        }
        node.forEach(child -> rename(child, from, to));
    }

    /** The field type's {@code errorMessage} entries in reverse, leaving the declared locales alone. */
    private static void reversed(ObjectNode fieldType) {
        ArrayNode messages = (ArrayNode) fieldType.path("StringType").path("errorMessage");
        List<JsonNode> entries = new ArrayList<>();
        messages.forEach(entries::add);
        Collections.reverse(entries);
        messages.removeAll();
        messages.addAll(entries);
    }

    /**
     * The String type's own message with no pattern beside it, written into the tree because the typed builder
     * drops a message that has no pattern (a modelkit defect found by this very row, tracked on its own).
     */
    private static String unpatterned(String text) {
        return edited(patterned(f -> { }, "en_US"), fieldType -> fieldType.putObject("StringType")
                .putArray("errorMessage").addObject().put("locale", "en_US").put("text", text));
    }

    /** A plain pattern message, and a defective one for {@code fr_FR}, which the model does not declare. */
    private static String inAnUndeclaredLocale(String text) {
        return edited(patterned(f -> f.pattern("[A-Z]+", "capitals only"), "en_US"), fieldType ->
                ((ArrayNode) fieldType.path("StringType").path("errorMessage")).addObject()
                        .put("locale", "fr_FR").put("text", text));
    }

    private static String edited(String json, Consumer<ObjectNode> codeFieldType) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(json);
            for (JsonNode element : root.path("content").path("modelRoot").path("rootGroups").get(0)
                    .path("Group").path("elements")) {
                if ("Code".equals(element.path("name").asText())) {
                    codeFieldType.accept((ObjectNode) element.path("Field").path("fieldType"));
                }
            }
            return KernelLaws.verdictOf(root.toString());
        } catch (IOException unreadable) {
            throw new UncheckedIOException("the builder's own output must parse", unreadable);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String enumerated(String text) {
        return KernelLaws.verdictOf(DocumentModelDraft.named("claims-field-messages").locale("en_US")
                .rootGroup("Claim", c -> c
                        .stringField("Reference", f -> { })
                        .enumField("Status", e -> e.value("OPEN").value("CLOSED").invalidValueMessage(text)))
                .build().toJson());
    }

    private static String required(String text) {
        EditSession session = EditSession.read(patterned(f -> { }, "en_US"));
        session.modifyField("/Claim/Code", FieldModification.builder().requiredness(Requiredness.REQUIRED)
                .requiredMessages(Map.of("en_US", text)).build());
        return KernelLaws.verdictOf(session.toJson());
    }
}
