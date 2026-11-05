package io.github.mbackschat.a12.dm.edit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.UniquenessCriterion;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule;
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator;

/**
 * The kits write a locale as A12 writes it, the parsed locale's key (`adapter.laws.LocaleWriteSpellingLawsTest`,
 * KERNEL-FINDINGS KF676), and match one locale by that key wherever they look an entry up, as A12 compares locales
 * (KF290). So every carrier authored as {@code en-US} is stored {@code en_US}; a spelling A12's parser refuses is
 * stored as given, for the gate to refuse; and an {@code en-US} already in the model is the {@code en_US} an edit
 * names, never a second entry. Own domain (insurance policies).
 */
class LocaleKeyWriteTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String policy(String locale) {
        return DocumentModelDraft.named("policy").locale(locale).label(locale, "Policy")
                .rootGroup("Policy", p -> p
                        .stringField("PolicyNo", f -> f.required().label(locale, "Number").suggestions(locale, "P-1"))
                        .enumField("Tier", e -> e.value("GOLD", l -> l.label(locale, "Gold"))
                                .invalidValueMessage("Unknown tier.")))
                .build().toJson();
    }

    /** Every carrier a kit writes, authored with {@code spelling}. */
    private static String everyCarrier(String spelling) {
        String described = ModelDocument.read(policy(spelling))
                .setRequiredErrorMessages("/Policy/PolicyNo", Map.of(spelling, "Enter a number."))
                .setHelperText("/Policy/PolicyNo", Map.of(spelling, "As printed."))
                .setInternalDescription("/Policy/PolicyNo", spelling, "The register key.")
                .setExternalDescription("/Policy/PolicyNo", spelling, "Your policy number.")
                .toJson();
        return EditSession.read(described)
                .addRule("/Policy", "NumberRequired", "PolicyNo", "NUMBER", "FieldNotFilled(PolicyNo)", "ERROR",
                        List.of(Dsl.text(spelling, "A number is required.")))
                .addUniquenessCriterion(new UniquenessCriterion("PolicyKey", List.of("/Policy/PolicyNo"),
                        Map.of(spelling, "Policy number taken.")))
                .toJson();
    }

    private static List<String> locales(String json) throws Exception {
        List<String> out = new ArrayList<>();
        JsonNode tree = JSON.readTree(json);
        tree.path("header").path("locales").forEach(l -> out.add(l.path("code").asText()));
        collect(tree, out);
        return out;
    }

    private static void collect(JsonNode node, List<String> out) {
        if (node.isObject() && node.path("locale").isTextual()) {
            out.add(node.path("locale").asText());
        }
        node.forEach(child -> collect(child, out));
    }

    @Test
    void everyCarrierIsWrittenAsTheLocalesKey() throws Exception {
        List<String> written = locales(everyCarrier("en-US"));
        assertEquals(List.of("en_US"), written.stream().distinct().toList());
        assertEquals(12, written.size(), "the header code and eleven localized entries: " + written);
    }

    /** A spelling the parser refuses is stored as given: the gate refuses it, the writer never repairs it. */
    @Test
    void aSpellingTheParserRefusesIsStoredAsGiven() throws Exception {
        String json = ModelDocument.read(policy("en_US")).setHelperText("/Policy/PolicyNo", Map.of("en_us", "x"))
                .toJson();
        assertEquals(List.of("en_US", "en_us"), locales(json).stream().distinct().toList());
    }

    /** One locale is one entry: an edit naming {@code en_US} replaces what the model spelled {@code en-US}. */
    @Test
    void anEditMatchesAStoredSpellingByItsKey() throws Exception {
        String stored = policy("en_US").replace("\"locale\" : \"en_US\"", "\"locale\" : \"en-US\"")
                .replace("\"code\" : \"en_US\"", "\"code\" : \"en-US\"");
        ModelDocument doc = ModelDocument.read(stored)
                .addLocale("en_US")
                .setInternalDescription("/Policy/PolicyNo", "en-US", "first")
                .setInternalDescription("/Policy/PolicyNo", "en_US", "second");
        JsonNode tree = JSON.readTree(doc.toJson());
        assertEquals(1, tree.path("header").path("locales").size(), doc.toJson());
        assertEquals("[{\"locale\":\"en_US\",\"text\":\"second\"}]",
                String.valueOf(tree.findValue("internalDescription")));

        doc.removeInternalDescription("/Policy/PolicyNo", "en-US");
        JsonNode removed = JSON.readTree(doc.toJson()).findValue("internalDescription");
        assertTrue(removed == null || removed.isEmpty(), doc.toJson());
    }

    /** A12 reads {@code en-US} as the declared {@code en_US} (KF290), so neither the typed builder nor the
     *  candidate check may call it undeclared or the declared locale uncovered. */
    @Test
    void aMessageSpelledDifferentlyCoversTheDeclaredLocale() {
        String model = policy("en_US");
        EditSession session = EditSession.read(model);
        var policy = session.group("/Policy");
        assertDoesNotThrow(() -> policy.addRule(r -> r.name("NumberRequired").code("NUMBER")
                .errorField(policy.field("PolicyNo")).when(policy.str("PolicyNo").notFilled())
                .message(Dsl.text("en-US", "A number is required.")).build()));
        DraftRule draft = DraftRule.builder().groupPath("/Policy").name("NumberRequired").errorEntityRelPath("PolicyNo")
                .errorCondition("FieldNotFilled(PolicyNo)").errorCode("NUMBER").severity("ERROR")
                .messages(List.of(Dsl.text("en-US", "A number is required."))).build();
        assertEquals(List.of(), new RuleValidator().undeclaredMessageLocales(model, draft));
    }
}
