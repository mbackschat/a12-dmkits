package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mbackschat.a12.dm.rulekit.validate.RkCode;

/** The {@code describe} verb — emits the model card (F9a) as JSON for an agent to orient on. */
class DescribeVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void describeEmitsModelCardJson(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("-m", CliTestSupport.modelWithRule(dir).toString(), "model", "describe");
        assertEquals(0, res.code(), res::err);
        JsonNode card = MAPPER.readTree(res.out()).get("data");
        assertTrue(card.has("fields") && card.has("groups"), "model card has fields + groups");
        assertTrue(res.out().contains("/Subscription/Addons/MonthlyFee"), "fields are listed");
    }

    @Test
    void describeSurfacesARepeatableGroupsMaxRows(@TempDir Path dir) throws Exception {
        // The report (#121) had to parse raw JSON to confirm a repeatable group's max — the card showed only
        // `repeatable:true`. The card now carries the numeric `maxRepetitions` for a repeatable group (and
        // omits it for a non-repeatable one, to keep the card clean).
        var res = CliTestSupport.run("-m", CliTestSupport.modelWithRule(dir).toString(), "model", "describe");
        assertEquals(0, res.code(), res::err);
        JsonNode groups = MAPPER.readTree(res.out()).get("data").get("groups");
        boolean sawRepeatableWithMax = false;
        for (JsonNode g : groups) {
            if (g.get("repeatable").asBoolean()) {
                assertTrue(g.has("maxRepetitions") && g.get("maxRepetitions").asInt() >= 2,
                        () -> "a repeatable group carries its numeric max (>= 2): " + g);
                sawRepeatableWithMax = true;
            } else {
                assertTrue(!g.has("maxRepetitions"), () -> "a non-repeatable group omits maxRepetitions: " + g);
            }
        }
        assertTrue(sawRepeatableWithMax, "the fixture has a repeatable group to surface");
    }

    @Test
    void describeMissingArgFails() {
        var res = CliTestSupport.run("model", "describe");
        assertNotEquals(0, res.code(), "no model path is an error");
        // On STDOUT as a coded refusal since G61, not as a bare stderr line: an agent parsing the result for a
        // reason used to get an empty stream. The whole class is swept in UnreadableModelRefusalTest.
        assertTrue(res.out().contains(RkCode.MODEL_NOT_NAMED.code()) && res.out().contains("-m <model.json>"),
                () -> "and names the missing model, coded: " + res.out() + res.err());
    }
}
