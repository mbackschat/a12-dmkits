package io.github.mbackschat.a12.rulekit.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** The {@code describe} verb — emits the model card (F9a) as JSON for an agent to orient on. */
class DescribeVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void describeEmitsModelCardJson(@TempDir Path dir) throws Exception {
        var res = CliTestSupport.run("describe", CliTestSupport.modelWithRule(dir).toString());
        assertEquals(0, res.code(), res::err);
        JsonNode card = MAPPER.readTree(res.out());
        assertTrue(card.has("fields") && card.has("groups"), "model card has fields + groups");
        assertTrue(res.out().contains("/Subscription/Addons/MonthlyFee"), "fields are listed");
    }

    @Test
    void describeMissingArgFails() {
        var res = CliTestSupport.run("describe");
        assertNotEquals(0, res.code(), "no model path is an error");
        assertTrue(res.err().toLowerCase().contains("usage"), "and prints usage");
    }
}
