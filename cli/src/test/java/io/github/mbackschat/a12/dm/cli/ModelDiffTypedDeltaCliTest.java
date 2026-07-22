package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;

/** The CLI adds typed delta values without changing the established deterministic strings. */
class ModelDiffTypedDeltaCliTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void emitsTypedScalarAndListValuesBesideStableStrings(@TempDir Path dir) throws Exception {
        Path base = dir.resolve("base.dm.json");
        Path head = dir.resolve("head.dm.json");
        Files.writeString(base, model(false));
        Files.writeString(head, model(true));

        var result = CliTestSupport.run("model", "diff", base.toString(), head.toString());

        assertEquals(0, result.code(), result::err);
        JsonNode deltas = JSON.readTree(result.out()).path("data").path("changes").get(0).path("deltas");
        JsonNode locales = delta(deltas, "locales");
        assertEquals("[\"de_DE\",\"en_US\"]", locales.path("after").asText());
        assertEquals("STRING_LIST", locales.path("afterValue").path("type").asText());
        assertEquals("de_DE", locales.path("afterValue").path("value").get(0).asText());
        assertEquals("en_US", locales.path("afterValue").path("value").get(1).asText());

        JsonNode baseYear = delta(deltas, "baseYear");
        assertEquals("2026", baseYear.path("after").asText());
        assertEquals("INTEGER", baseYear.path("afterValue").path("type").asText());
        assertEquals(2026, baseYear.path("afterValue").path("value").asInt());
        assertEquals(true, baseYear.path("beforeValue").isMissingNode());
    }

    private static String model(boolean enriched) {
        var draft = DocumentModelDraft.named("orders").locale("en_US")
                .rootGroup("Order", order -> order.stringField("Number", field -> { }));
        if (enriched) {
            draft.locale("de_DE").baseYear(2026);
        }
        return draft.build().toJson();
    }

    private static JsonNode delta(JsonNode deltas, String aspect) {
        for (JsonNode delta : deltas) {
            if (aspect.equals(delta.path("aspect").asText())) {
                return delta;
            }
        }
        throw new AssertionError("missing " + aspect + " delta: " + deltas);
    }
}
