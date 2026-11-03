package io.github.mbackschat.a12.dm.adapter.laws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mgmtp.a12.kernel.md.model.api.services.DocumentModelExpansionException;

import tools.jackson.core.JacksonException;

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle;
import io.github.mbackschat.a12.dm.adapter.KernelExpansionFixture;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.UnresolvedReferenceException;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;

/**
 * How kernel 31.1.1 reads the DM 29.4 include shape, a mount group's {@code includeConfig}
 * (KERNEL-FINDINGS KF651). Every row runs the real expansion and the real consistency check on one
 * entry that varies only the mount body or the header references, so each row moves one variable against
 * {@link #canonical}. The rows are the measured basis for the kits' reference-only include model (owner
 * decision D3, KERNEL-31-UPGRADE-PROPOSAL) and for the one deliberate over-strictness the kits keep: a stale
 * {@code modelAlias}, which A12 drops without a word.
 */
class IncludeConfigShapeLawsTest {

    private static final String MODEL_INFO = """
            "modelInfo":{"name":"%s","revision":"r","immutable":false},
            "modelConfig":{"decimalSeparator":".","timeZone":"Europe/Berlin","conditionLanguage":{"code":"en_US"},
              "fieldRefByShortNameAllowed":true},""";

    private static final String COMPONENT = """
            {"header":{"id":"component","modelType":"document","modelVersion":"29.4.0","locales":[{"code":"en_US"}]},
             "content":{%s"modelRoot":{"rootGroups":[{"type":"Group","id":"/Component","name":"Component",
               "Group":{"repeatability":1,"elements":[{"type":"Field","id":"/Component/Description",
                 "name":"Description","Field":{"fieldType":{"type":"StringType"}}}]}}]}}}"""
            .formatted(MODEL_INFO.formatted("component"));

    private static final ModelResolver RESOLVER = ref -> "component".equals(ref) ? COMPONENT : null;

    private static final String HEADER_REF =
            "{\"modelType\":\"document\",\"purpose\":\"include\",\"reference\":\"component\"}";

    private static final String MOUNT = "\"includeConfig\":{\"reference\":\"component\"}";

    private static final String LOCAL_FIELD = "\"elements\":[{\"type\":\"Field\",\"id\":\"/Permit/Details/Local\","
            + "\"name\":\"Local\",\"Field\":{\"fieldType\":{\"type\":\"StringType\"}}}]";

    private static String entry(String headerRefs, String mountBody) {
        return """
                {"header":{"id":"permit","modelType":"document","modelVersion":"29.4.0","locales":[{"code":"en_US"}],
                  "modelReferences":[%s]},
                 "content":{%s"modelRoot":{"rootGroups":[{"type":"Group","id":"/Permit","name":"Permit",
                   "Group":{"repeatability":1,"elements":[{"type":"Group","id":"/Permit/Details","name":"Details",
                     "Group":{"repeatability":1,%s}}]}}]}}}"""
                .formatted(headerRefs, MODEL_INFO.formatted("permit"), mountBody);
    }

    private static String expanded(String json) {
        return KernelExpansionFixture.expandedJson(json, RESOLVER).replaceAll("\\s+", "");
    }

    private static List<?> notifications(String json) {
        return new KernelConsistencyOracle().checkConsistency(json, RESOLVER);
    }

    private static final String MOUNTED_FIELD = "\"name\":\"Description\"";

    private final String canonical = entry(HEADER_REF, MOUNT);

    @Test
    void anAbsentIncludeLevelMountsTheChildrenOfTheSingleRootGroup() {
        String result = expanded(canonical);
        assertTrue(result.contains(MOUNTED_FIELD), result);
        assertFalse(result.contains("\"name\":\"Component\""), "SINGLE_RG drops the provider's root group: " + result);
        assertEquals(List.of(), notifications(canonical));
        assertEquals(result, expanded(entry(HEADER_REF,
                "\"includeConfig\":{\"reference\":\"component\",\"includeLevel\":\"SINGLE_RG\","
                        + "\"excludeRules\":false,\"excludeComputations\":false}")));
    }

    @Test
    void theHeaderIncludeReferenceTakesNoPartInResolvingTheMount() {
        String result = expanded(canonical);
        assertEquals(result, expanded(entry("", MOUNT)));
        assertEquals(result, expanded(entry(
                "{\"modelType\":\"document\",\"purpose\":\"include\",\"reference\":\"other\"}", MOUNT)));
        assertEquals(result, expanded(entry(HEADER_REF + "," + HEADER_REF, MOUNT)));
        assertEquals(List.of(), notifications(entry("", MOUNT)));
        assertTrue(result.contains("\"modelReferences\":[]"), "expansion removes the managed include entries: " + result);
    }

    @Test
    void anAliasLeftOnAHeaderReferenceIsIgnored() {
        String withAlias = entry("{\"modelType\":\"document\",\"purpose\":\"include\",\"reference\":\"component\","
                + "\"alias\":\"c\"}", MOUNT);
        assertEquals(expanded(canonical), expanded(withAlias));
        assertEquals(List.of(), notifications(withAlias));
    }

    @Test
    void aStaleModelAliasIsDroppedSilentlyAndLeavesAnEmptyGroup() {
        String stale = entry(HEADER_REF, "\"modelAlias\":\"component\"");
        String result = expanded(stale);
        assertFalse(result.contains(MOUNTED_FIELD), result);
        assertTrue(result.contains("\"name\":\"Details\",\"Group\":{\"repeatability\":1}}"), result);
        assertEquals(List.of(), notifications(stale), "A12 reports nothing for the lost mount");
    }

    @Test
    void aMissingOrEmptyReferenceRefusesTheExpansion() {
        for (String body : List.of("\"includeConfig\":{\"reference\":\"\"}", "\"includeConfig\":{}")) {
            var refused = assertThrows(DocumentModelExpansionException.class, () -> expanded(entry(HEADER_REF, body)));
            assertTrue(refused.getMessage().contains("'/Permit/Details'"), refused.getMessage());
            assertTrue(refused.getMessage().contains("is not specified"), refused.getMessage());
        }
    }

    @Test
    void includeConfigBindsOnlyAsAnObjectAndItsReferenceAsAScalarsText() {
        for (String body : List.of("\"includeConfig\":\"component\"", "\"includeConfig\":[]",
                "\"includeConfig\":{\"reference\":null}", "\"includeConfig\":{\"reference\":{}}")) {
            assertThrows(JacksonException.class, () -> expanded(entry(HEADER_REF, body)), body);
        }
        for (String scalar : List.of("5", "true")) {
            var unresolved = assertThrows(UnresolvedReferenceException.class, () -> expanded(entry(HEADER_REF,
                    "\"includeConfig\":{\"reference\":" + scalar + "}")));
            assertTrue(unresolved.getMessage().contains("\"" + scalar + "\""), unresolved.getMessage());
        }
        var javaBlank = assertThrows(DocumentModelExpansionException.class,
                () -> expanded(entry(HEADER_REF, "\"includeConfig\":{\"reference\":\" \"}")));
        assertTrue(javaBlank.getMessage().contains("is not specified"), javaBlank.getMessage());
        var noBreak = assertThrows(UnresolvedReferenceException.class,
                () -> expanded(entry(HEADER_REF, "\"includeConfig\":{\"reference\":\"\\u00a0component\"}")));
        assertTrue(noBreak.getMessage().contains("\u00a0component"), "a no-break space is part of the id");
        String plain = entry(HEADER_REF, "\"includeConfig\":null");
        assertFalse(expanded(plain).contains(MOUNTED_FIELD), "a null includeConfig declares no mount");
        assertEquals(List.of(), notifications(plain));
    }

    /**
     * A component carrying one rule, so a row can tell whether {@code excludeRules} took effect. Its condition is
     * authored through the kit, and {@code MODEL_ROOT} is told apart by the provider's root group surviving.
     */
    private static final String RULED_COMPONENT = ruledComponent();

    private static String ruledComponent() {
        String model = DocumentModelDraft.named("component").locale("en_US")
                .rootGroup("Component", component -> component.field("Description", FieldKind.STRING))
                .build().toJson();
        return RuntimeLaws.withRule(model, "/Component", "DescriptionGiven", "../Description", "E1",
                BoundModel.ofJson(model).field("/Component/Description").filled());
    }

    private static String expandedRuled(String mountBody) {
        return KernelExpansionFixture.expandedJson(entry(HEADER_REF, mountBody),
                ref -> "component".equals(ref) ? RULED_COMPONENT : null).replaceAll("\\s+", "");
    }

    private static String option(String key, String jsonValue) {
        return "\"includeConfig\":{\"reference\":\"component\",\"" + key + "\":" + jsonValue + "}";
    }

    /**
     * The exclusion flags bind as a Java {@code boolean} through Jackson's default coercion: the JSON literals, an
     * integer token by whether its value is zero, and one of six spellings after a Java {@code trim()} (which strips
     * every char up to U+0020 but not a no-break space). Every other value refuses the whole model. This replaced
     * kernel 30.8.1's {@code booleanValue()} reading, which turned every non-boolean into {@code false}.
     */
    @Test
    void anExclusionFlagBindsByJacksonBooleanCoercion() {
        String rule = "\"name\":\"DescriptionGiven\"";
        for (String excluding : List.of("true", "1", "-5", "100000000000000000000000", "\"true\"", "\"True\"",
                "\"TRUE\"", "\" true \"", "\"\\ttrue\"", "\"true\\n\"", "\"\\u0001true\"")) {
            assertFalse(expandedRuled(option("excludeRules", excluding)).contains(rule), excluding);
        }
        for (String keeping : List.of("false", "0", "-0", "\"false\"", "\"False\"", "\"FALSE\"")) {
            assertTrue(expandedRuled(option("excludeRules", keeping)).contains(rule), keeping);
        }
        for (String refused : List.of("\"yes\"", "\"tRUE\"", "\"1\"", "\"\"", "\"\\u00a0true\"", "1.0", "1e0",
                "null", "[]", "{}")) {
            assertThrows(JacksonException.class, () -> expandedRuled(option("excludeRules", refused)), refused);
            assertThrows(JacksonException.class, () -> expandedRuled(option("excludeComputations", refused)), refused);
        }
    }

    /**
     * {@code includeLevel} binds as an enum: a constant's exact name or its ordinal ({@code SINGLE_RG} is 0), as an
     * integer token or as the ordinal's digits, all after the same Java {@code trim()}. Case, sign, a leading zero,
     * an out-of-range ordinal, a fraction, a boolean, {@code null} and {@code ""} refuse the model.
     */
    @Test
    void anIncludeLevelBindsByNameOrOrdinal() {
        String providerRoot = "\"name\":\"Component\"";
        for (String modelRoot : List.of("\"MODEL_ROOT\"", "\" MODEL_ROOT \"", "\"\\tMODEL_ROOT\"", "1", "\"1\"",
                "\" 1\"", "\"1 \"")) {
            assertTrue(expandedRuled(option("includeLevel", modelRoot)).contains(providerRoot), modelRoot);
        }
        for (String singleRg : List.of("\"SINGLE_RG\"", "0", "-0", "\"0\"")) {
            assertFalse(expandedRuled(option("includeLevel", singleRg)).contains(providerRoot), singleRg);
        }
        for (String refused : List.of("\"model_root\"", "\"Model_Root\"", "\"\\u00a0MODEL_ROOT\"", "\"01\"",
                "\"+1\"", "\"-0\"", "\"2\"", "\"1e0\"", "2", "-1", "1.0", "true", "null", "\"\"")) {
            assertThrows(JacksonException.class, () -> expandedRuled(option("includeLevel", refused)), refused);
        }
    }

    /** The DM 28 mount options left on the group body, beside an {@code includeConfig}, do nothing. */
    @Test
    void aDm28MountOptionBesideIncludeConfigIsIgnored() {
        String canonicalRuled = expandedRuled(MOUNT);
        assertEquals(canonicalRuled, expandedRuled(MOUNT + ",\"excludeRules\":true"));
        assertEquals(canonicalRuled, expandedRuled(MOUNT + ",\"excludeComputations\":true"));
        assertEquals(canonicalRuled, expandedRuled(MOUNT + ",\"includeLevel\":\"MODEL_ROOT\""));
        assertEquals(canonicalRuled, expandedRuled(option("unknownOption", "1")));
    }

    /** A scalar reference is resolved by its JSON text, untrimmed: a fraction keeps its zeros. */
    @Test
    void aReferenceIsTheScalarsTextUntrimmed() {
        for (String reference : List.of("1.50", "\" component \"")) {
            var unresolved = assertThrows(UnresolvedReferenceException.class,
                    () -> expanded(entry(HEADER_REF, "\"includeConfig\":{\"reference\":" + reference + "}")));
            assertTrue(unresolved.getMessage().contains("\"" + reference.replace("\"", "") + "\""),
                    unresolved.getMessage());
        }
    }

    @Test
    void localElementsBesideAnIncludeConfigAreReplacedByTheIncludedContent() {
        String result = expanded(entry(HEADER_REF, MOUNT + "," + LOCAL_FIELD));
        assertTrue(result.contains(MOUNTED_FIELD), result);
        assertFalse(result.contains("\"name\":\"Local\""), result);
    }
}
