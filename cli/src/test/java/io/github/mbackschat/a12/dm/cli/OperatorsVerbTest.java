package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Iterator;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The {@code operators} verb — the CLI exposing the DSL operator catalog so an agent can explore the
 * vocabulary on demand (CLI-SPEC §1a, the self-explaining requirement; answers "can the LLM browse
 * available operators?"). List (filterable) + one operator's full semantics.
 */
class OperatorsVerbTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void listsTheCatalog() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.path("count").asInt() > 0, () -> "non-empty catalog: " + res.out());
        boolean hasFieldFilled = false;
        for (JsonNode op : j.path("operators")) {
            if ("FieldFilled".equals(op.path("id").asText())) {
                hasFieldFilled = true;
                assertTrue(!op.path("meaning").asText().isBlank(), "each entry carries its meaning");
            }
        }
        assertTrue(hasFieldFilled, "lists a known operator (FieldFilled)");
    }

    @Test
    void oneOperatorCarriesFullSemantics() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "DifferenceInDays");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertEquals("DifferenceInDays", j.path("id").asText());
        assertEquals("FUNCTION", j.path("kind").asText());
        assertEquals("DifferenceInDays", j.path("keyword").path("en").asText());
        assertTrue(!j.path("keyword").path("de").asText().isBlank(), "carries the DE keyword too");
        assertTrue(!j.path("meaning").asText().isBlank(), "carries the meaning");
    }

    @Test
    void unknownOperatorFails() {
        CliTestSupport.Result res = CliTestSupport.run("operators", "NoSuchOp");
        assertEquals(2, res.code());
        assertTrue(res.err().contains("no operator"), res::err);
    }

    @Test
    void filtersByKind() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "--kind", "PREDICATE");
        assertEquals(0, res.code(), res::err);
        JsonNode ops = MAPPER.readTree(res.out()).path("operators");
        assertTrue(ops.size() > 0, "some predicates exist");
        for (Iterator<JsonNode> it = ops.elements(); it.hasNext();) {
            assertEquals("PREDICATE", it.next().path("kind").asText());
        }
    }

    @Test
    void aNonIdPositionalFiltersTheCatalogBySubstring() throws Exception {
        // G13: an agent's natural reach — `operators <substring>` — narrows the list instead of failing
        // (an exact id still gives the full single operator; a non-id positional is a substring needle)
        CliTestSupport.Result res = CliTestSupport.run("operators", "Difference");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        assertTrue(j.has("operators"), () -> "a substring returns the LIST shape, not one full operator: " + res.out());
        assertTrue(j.path("count").asInt() >= 2, () -> "lists every match: " + res.out());
        boolean hasDays = false;
        boolean hasYears = false;
        for (Iterator<JsonNode> it = j.path("operators").elements(); it.hasNext();) {
            String id = it.next().path("id").asText();
            hasDays |= "DifferenceInDays".equals(id);
            hasYears |= "DifferenceInYears".equals(id);
        }
        assertTrue(hasDays && hasYears, () -> "the substring surfaces the matching operators: " + res.out());
    }

    @Test
    void aSubstringComposesWithKind() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "filled", "--kind", "PREDICATE");
        assertEquals(0, res.code(), res::err);
        JsonNode ops = MAPPER.readTree(res.out()).path("operators");
        assertTrue(ops.size() > 0, () -> "filled predicates exist: " + res.out());
        for (Iterator<JsonNode> it = ops.elements(); it.hasNext();) {
            assertEquals("PREDICATE", it.next().path("kind").asText(), "the --kind filter still applies");
        }
    }
}
