package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.edit.GroupEditor;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * A message token is written in the model's condition language and quotes a name that collides with a keyword, as
 * the condition beside it is (DEV-API-GAPS DG131). The message grammar has five bilingual terminals, and a token
 * spelled in the other language is refused (KERNEL-FINDINGS KF198), so {@code GroupEditor.messageTokens()} renders
 * through the model's own language. A12 splits a message at every {@code $} ({@code FehlertextUtil}), so a key
 * holding one cannot be written as a token at all.
 *
 * <p>The kernel decides every row, and each verdict is pinned so that a kernel admitting everything could not pass.
 * The English token on the German model is the control that the language is what admits the German one.
 */
class MessageTokensTest {

    private static String model(String conditionLanguage) {
        String json = DocumentModelDraft.named("policy-message-tokens").locale("en_US")
                .rootGroup("Policy", p -> p.stringField("Holder", f -> { })
                        .group("Claims", c -> c.repeatable(5).indexField("Ref")
                                .stringField("Ref", f -> { }).stringField("Note", f -> { })
                                .enumField("Status", e -> e.value("OPEN").value("SHUT")
                                        .category("Band", "HIGH", "LOW").category("For", "A", "B"))))
                .build().toJson();
        return ModelDocument.read(json).setConditionLanguage(conditionLanguage).toJson();
    }

    private static final String EN = model("en_US");
    private static final String DE = model("de_DE");

    /** The verdict of one rule whose condition reads the keyed row and whose message is {@code token}. */
    private static String verdict(String json, String key, String token) {
        GroupEditor policy = EditSession.read(json).group("/Policy");
        StringRef holder = policy.str("Holder");
        SemanticIndex note = policy.str("Claims/Note").semanticIndex(key);
        SemanticIndex status = policy.enumOf("Claims/Status").semanticIndex(key);
        Rule rule = policy.rule(r -> r.name("KeyedNote").code("KEYED_NOTE").errorField(holder)
                .when(holder.filled().and(note.filled()).and(status.filled()))
                .message(Dsl.en("Claim " + token)).build());
        var result = new RuleValidator().check(json, rule.draft(EditSession.read(json).conditionLanguage()));
        return result.ok() ? "admitted" : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(" + "));
    }

    @Test
    void eachTokenIsWrittenInTheModelsConditionLanguage() {
        GroupEditor en = EditSession.read(EN).group("/Policy");
        // tag::message-tokens-language[]
        GroupEditor de = EditSession.read(DE).group("/Policy");      // the model's conditions are German
        SemanticIndex deNote = de.str("Claims/Note").semanticIndex("c1");
        String token = de.messageTokens().fieldValue(deNote);        // "$/Policy/Claims/Note.Wert Fuer \"c1\"$"
        // end::message-tokens-language[]
        CategoryRef deBand = de.enumOf("Claims/Status").category("Band");

        assertEquals("$/Policy/Claims/Note.Wert Fuer \"c1\"$", token);
        assertEquals("$/Policy/Holder.Wert$", de.messageTokens().fieldValue(de.field("Holder")));
        assertEquals("$/Policy/Claims/Note.value For \"c1\"$",
                en.messageTokens().fieldValue(en.str("Claims/Note").semanticIndex("c1")));

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("keyed value", verdict(DE, "c1", de.messageTokens().fieldValue(deNote)));
        actual.put("keyed value, English spelling", verdict(DE, "c1", Dsl.fieldValue(deNote)));
        actual.put("keyed label", verdict(DE, "c1", de.messageTokens().fieldLabel(deNote)));
        actual.put("keyed category", verdict(DE, "c1", de.messageTokens().fieldCategory(deBand, "c1")));
        actual.put("plain value", verdict(DE, "c1", de.messageTokens().fieldValue(de.field("Holder"))));
        actual.put("plain value, English spelling", verdict(DE, "c1", Dsl.fieldValue(de.field("Holder"))));
        actual.put("keyed row", verdict(DE, "c1",
                de.messageTokens().repetitionIndex(de.flattenGroup("Claims").forIndex("c1"))));
        assertEquals(Map.of(
                "keyed value", "admitted",
                "keyed value, English spelling", "MVK_UNEXPECTED_TOKEN",
                "keyed label", "admitted",
                "keyed category", "admitted",
                "plain value", "admitted",
                "plain value, English spelling", "INVALID_ENTITY",
                "keyed row", "admitted"), actual);
    }

    /** The verdict of a rule iterating {@code Claims}, where the firing row's index is readable (KF280). */
    private static String rowVerdict(String json, String token) {
        GroupEditor claims = EditSession.read(json).group("/Policy/Claims");
        StringRef note = claims.str("Note");
        Rule rule = claims.rule(r -> r.name("RowNote").code("ROW_NOTE").errorField(note).when(note.filled())
                .message(Dsl.en("Claim " + token)).build());
        var result = new RuleValidator().check(json, rule.draft(EditSession.read(json).conditionLanguage()));
        return result.ok() ? "admitted" : result.diagnostics().stream()
                .map(d -> d.code().orElse("(no code)")).collect(Collectors.joining(" + "));
    }

    @Test
    void theFiringRowsIndexValueIsWrittenInTheModelsLanguageToo() {
        GroupEditor en = EditSession.read(EN).group("/Policy");
        GroupEditor de = EditSession.read(DE).group("/Policy");
        assertEquals("$index('Ref').Wert$", de.messageTokens().indexValue(de.field("Claims/Ref")));

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("English model", rowVerdict(EN, en.messageTokens().indexValue(en.field("Claims/Ref"))));
        actual.put("German model", rowVerdict(DE, de.messageTokens().indexValue(de.field("Claims/Ref"))));
        actual.put("German model, English spelling", rowVerdict(DE, Dsl.indexValue(de.field("Claims/Ref"))));
        assertEquals(Map.of(
                "English model", "admitted",
                "German model", "admitted",
                "German model, English spelling", "MVK_UNEXPECTED_TOKEN"), actual);
    }

    @Test
    void aCategoryNamedLikeAKeywordIsQuotedAndAnOrdinaryOneIsNot() {
        GroupEditor en = EditSession.read(EN).group("/Policy");
        EnumRef status = en.enumOf("Claims/Status");
        assertEquals("$/Policy/Claims/Status->'For' For \"c1\"$", Dsl.fieldCategory(status.category("For"), "c1"));
        assertEquals("$/Policy/Claims/Status->Band For \"c1\"$", Dsl.fieldCategory(status.category("Band"), "c1"));

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("quoted keyword", verdict(EN, "c1", Dsl.fieldCategory(status.category("For"), "c1")));
        actual.put("unquoted keyword", verdict(EN, "c1", "$/Policy/Claims/Status->For For \"c1\"$"));
        actual.put("quoted ordinary", verdict(EN, "c1", "$/Policy/Claims/Status->'Band' For \"c1\"$"));
        assertEquals(Map.of(
                "quoted keyword", "admitted",
                "unquoted keyword", "MVK_UNEXPECTED_TOKEN",
                "quoted ordinary", "admitted"), actual);
    }

    @Test
    void aKeyHoldingADollarIsRefusedAsATokenBecauseA12SplitsTheMessageThere() {
        GroupEditor en = EditSession.read(EN).group("/Policy");
        SemanticIndex note = en.str("Claims/Note").semanticIndex("c$1");
        assertThrows(IllegalArgumentException.class, () -> Dsl.fieldValue(note));
        assertThrows(IllegalArgumentException.class, () -> Dsl.fieldLabel(note));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.fieldCategory(en.enumOf("Claims/Status").category("Band"), "c$1"));
        assertThrows(IllegalArgumentException.class,
                () -> Dsl.repetitionIndex(en.flattenGroup("Claims").forIndex("c$1")));

        Map<String, String> actual = new LinkedHashMap<>();
        actual.put("the key in the condition alone", verdict(EN, "c$1", "no token"));
        actual.put("the key in a token", verdict(EN, "c$1", "$/Policy/Claims/Note.value For \"c$1\"$"));
        assertEquals(Map.of("the key in the condition alone", "admitted", "the key in a token", "WRONG_NUMBER_OF_DOLLARS"), actual);
    }

    @Test
    void aTokenKeyedOtherwiseThanItsConditionIsRefused() {
        GroupEditor en = EditSession.read(EN).group("/Policy");
        assertEquals("MVK_INDEX_FOR_ERROR_TEXT_INVALID",
                verdict(EN, "c1", Dsl.fieldValue(en.str("Claims/Note").semanticIndex("c2"))));
    }
}
