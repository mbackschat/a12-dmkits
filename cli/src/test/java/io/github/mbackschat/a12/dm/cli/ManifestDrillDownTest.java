package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The tiered manifest: a bare {@code manifest} is a one-line-per-verb index an agent can afford to read first, a
 * {@code <target> [<op>]} selector returns those verbs' full entries, and {@code --full} returns every entry. Until
 * 2026-11-06 the bare call returned all of it, 338 KB pretty-printed, as the first read the help recommended.
 */
class ManifestDrillDownTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> INDEX_KEYS = Set.of("verb", "schema", "description");

    @Test
    void theBareManifestIsAnIndexThatNamesTheDrillDown() throws Exception {
        CliTestSupport.Result index = CliTestSupport.run("manifest");
        assertEquals(0, index.code(), index::err);
        JsonNode root = MAPPER.readTree(index.out());
        assertEquals(verbNames(full()), verbNames(root), "the index lists every verb --full lists");
        for (JsonNode verb : root.path("verbs")) {
            verb.fieldNames().forEachRemaining(key -> assertTrue(INDEX_KEYS.contains(key),
                    () -> verb.path("verb").asText() + " carries " + key + " in the index"));
            assertTrue(!verb.path("description").asText().isBlank(), () -> verb + " has no description");
        }
        assertTrue(root.path("detail").asText().contains("dmtool manifest <target> [<op>]"), root::toString);
        assertTrue(root.path("version").has("rulekit"), "the index keeps the version surface");
        assertTrue(index.out().length() * 8 < CliTestSupport.run("manifest", "--full").out().length(),
                "the index is not an eighth of the full document");
    }

    @Test
    void aSelectorReturnsExactlyTheFullEntriesItNames() throws Exception {
        JsonNode full = full();
        assertSelects(full, List.of("field"), "field ");
        assertSelects(full, List.of("field", "add"), "field add");
        assertSelects(full, List.of("export"), "export");
        JsonNode target = MAPPER.readTree(CliTestSupport.run("manifest", "rule").out());
        assertEquals(full.path("contractVocabulary"), target.path("contractVocabulary"),
                "a drill-down keeps the vocabulary its operational profiles use");
        assertEquals(full.path("globalOptions"), target.path("globalOptions"));
    }

    @Test
    void anUnknownSelectorFailsAndListsWhatExists() {
        CliTestSupport.Result unknown = CliTestSupport.run("manifest", "feld");
        assertEquals(2, unknown.code(), unknown::out);
        assertTrue(unknown.err().contains("feld") && unknown.err().contains("field")
                && unknown.err().contains("export"), unknown::err);
        CliTestSupport.Result noOp = CliTestSupport.run("manifest", "field", "frobnicate");
        assertEquals(2, noOp.code(), noOp::out);
        assertTrue(noOp.err().contains("field add"), noOp::err);
    }

    @Test
    void fullTakesNoSelector() {
        CliTestSupport.Result both = CliTestSupport.run("manifest", "--full", "field");
        assertEquals(2, both.code(), both::out);
        assertTrue(both.err().contains("--full"), both::err);
    }

    private static void assertSelects(JsonNode full, List<String> selector, String prefix) throws Exception {
        List<String> args = new ArrayList<>(List.of("manifest"));
        args.addAll(selector);
        CliTestSupport.Result result = CliTestSupport.run(args.toArray(String[]::new));
        assertEquals(0, result.code(), result::err);
        List<JsonNode> expected = new ArrayList<>();
        for (JsonNode verb : full.path("verbs")) {
            String name = verb.path("verb").asText();
            if (prefix.endsWith(" ") ? name.startsWith(prefix) : name.equals(prefix)) {
                expected.add(verb);
            }
        }
        assertTrue(!expected.isEmpty(), prefix);
        List<JsonNode> actual = new ArrayList<>();
        MAPPER.readTree(result.out()).path("verbs").forEach(actual::add);
        assertEquals(expected, actual, () -> "manifest " + selector);
    }

    private static JsonNode full() throws Exception {
        CliTestSupport.Result full = CliTestSupport.run("manifest", "--full");
        assertEquals(0, full.code(), full::err);
        return MAPPER.readTree(full.out());
    }

    private static List<String> verbNames(JsonNode root) {
        List<String> names = new ArrayList<>();
        root.path("verbs").forEach(verb -> names.add(verb.path("verb").asText()));
        return names;
    }
}
