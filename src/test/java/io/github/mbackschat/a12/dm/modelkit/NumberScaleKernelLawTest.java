package io.github.mbackschat.a12.dm.modelkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.mbackschat.a12.dm.core.ModelLoader;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.Iterator;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Kernel law (KERNEL-FINDINGS): an a12 NUMBER is <b>always scale-bounded</b> — there is no unbounded-scale
 * decimal. {@code maxFractionalDigits} is effectively mandatory, confirmed by three independent oracles:
 * <ul>
 *   <li>the typed kernel API models it as a primitive {@code int} ({@code INumberType.getMaxFractionalDigits()},
 *       no {@code Optional} — unlike the genuinely-optional {@code getMinValue}/{@code getMaxLength} slots);</li>
 *   <li>the metamodel {@code MM_SMM_FieldTypeChecks} declares it "a required Field in case of a number";</li>
 *   <li>behaviourally, a DM-JSON that <em>omits</em> the slot is <b>accepted</b> with the scale silently
 *       defaulting to 0 — never unbounded, never rejected.</li>
 * </ul>
 * modelkit therefore makes the default explicit ({@code null → 0}), which is correct, not a gap. (This
 * resolves the DEV-API-GAPS "unbounded-scale NUMBER" investigation: it is a kernel fact, so there is no
 * modelkit capability to add.)
 */
class NumberScaleKernelLawTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void aNumberAuthoredWithNoExplicitScaleDefaultsToZeroAndKernelAccepts() {
        String json = DocumentModelDraft.named("Probe").locale("en_US")
                .rootGroup("G", g -> g.numberField("N", n -> { }))   // no scale set → modelkit defaults to 0
                .build().toJson();
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(json));
    }

    @Test
    void omittingMaxFractionalDigitsFromTheJsonStillLoadsBecauseTheKernelDefaultsScaleToZero() throws Exception {
        String json = DocumentModelDraft.named("Probe").locale("en_US")
                .rootGroup("G", g -> g.numberField("N", n -> n.fractionDigits(2)))
                .build().toJson();

        ObjectNode tree = (ObjectNode) JSON.readTree(json);
        assertEquals(1, stripKey(tree, "maxFractionalDigits"), "the fixture must have exactly one scale to strip");

        // No 'unbounded scale' exists in a12: the slot-less number is accepted, defaulting scale to 0.
        assertDoesNotThrow(() -> ModelLoader.loadSelfContained(tree.toString()));
    }

    private static int stripKey(JsonNode node, String key) {
        int count = 0;
        if (node.isObject()) {
            ObjectNode obj = (ObjectNode) node;
            if (obj.remove(key) != null) {
                count++;
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = obj.fields(); it.hasNext(); ) {
                count += stripKey(it.next().getValue(), key);
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                count += stripKey(child, key);
            }
        }
        return count;
    }
}
