package io.github.mbackschat.a12.dm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/**
 * The index reads a header's locale codes as A12 keys them, never by a stricter grammar of its own.
 *
 * It once parsed each code as a BCP 47 language tag and threw on codes A12 builds, `de_CH_x` and `ée` among
 * them, so a model the Kernel gate had admitted failed in our own reader after it (KERNEL-FINDINGS KF649,
 * a12-kernel-lean's LF224). Which codes A12 builds is `adapter.laws.CheckerLocaleSpellingAgreementTest`'s.
 *
 * Own domain (an insurance policy with a reference).
 */
class ModelIndexHeaderLocalesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String POLICY = DocumentModelDraft.named("policy-locales").locale("en_US")
            .rootGroup("Policy", p -> p.stringField("Ref", f -> { }))
            .build().toJson();

    @Test
    void aCodeA12BuildsIsReadNotRefused() {
        assertEquals(List.of("de_CH_x", "ée", "de__1996", "en_US_abcdefghi"),
                localesOf(objects("de_CH_x", "ée", "de__1996", "en_US_abcdefghi")));
    }

    @Test
    void aCodeIsKeyedAsA12ComparesIt() {
        assertEquals(List.of("en_US", "he", "xx_123"), localesOf(objects("en-US", "iw", "XX_123")));
    }

    @Test
    void aBareTextEntryIsReadAsAnObjectEntryIs() {
        ArrayNode locales = MAPPER.createArrayNode().add("fr_CH").add(MAPPER.createObjectNode().put("code", "de_CH"));
        assertEquals(List.of("fr_CH", "de_CH"), localesOf(locales));
    }

    @Test
    void aCodeNamingNoLanguageOrRefusedByTheParserIsSkipped() {
        // A12 builds no model from any of these, so the gate refuses them; the read accessor answers without them.
        assertEquals(List.of("fr_CH"), localesOf(objects("fr_CH", "US", "_CH", "", "en_us", "zh_Hans_CN")));
    }

    private static ArrayNode objects(String... codes) {
        ArrayNode locales = MAPPER.createArrayNode();
        for (String code : codes) {
            locales.addObject().put("code", code);
        }
        return locales;
    }

    private static List<String> localesOf(JsonNode locales) {
        try {
            ObjectNode root = (ObjectNode) MAPPER.readTree(POLICY);
            ((ObjectNode) root.path("header")).set("locales", locales);
            return ModelIndex.of(root.toString(), ReferenceResolvers.selfContained()).locales();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("the fixture must parse", e);
        }
    }
}
