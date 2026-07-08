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

    // --- the machine tier (CATALOG-SPEC §7): the emptyOperand facet, correctives, gotcha kinds ---

    @Test
    void servesTheEmptyOperandFacetAndClassifiedGotchas() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "Length");
        assertEquals(0, res.code(), res::err);
        JsonNode j = MAPPER.readTree(res.out());
        JsonNode claim = j.path("semantics").path("emptyOperand").get(0);
        assertEquals("0", claim.path("operand").asText());
        assertEquals("AS_ZERO", claim.path("behavior").asText(),
                () -> "Length's empty-as-0 facet must be served: " + res.out());
        assertTrue(!claim.path("basis").asText().isBlank(), "the claim's basis (its lock) travels");
        assertTrue(claim.path("probes").isMissingNode(), "probes are CI detail, not consumer payload");
        // gotchas travel WITH their kind (previously dropped)
        JsonNode gotcha = j.path("gotchas").get(0);
        assertTrue(!gotcha.path("text").asText().isBlank(), () -> "gotcha object with text: " + res.out());
        assertTrue(!gotcha.path("kind").asText().isBlank(), () -> "gotcha kind travels: " + res.out());
    }

    @Test
    void servesPerKindClaimsAndCorrectives() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators", "Min");
        assertEquals(0, res.code(), res::err);
        JsonNode perKind = MAPPER.readTree(res.out()).path("semantics").path("emptyOperand").get(0).path("perKind");
        assertEquals("AS_ZERO", perKind.path("number").asText());
        assertEquals("SKIPPED", perKind.path("date").asText());

        CliTestSupport.Result lt = CliTestSupport.run("operators", "LessThan");
        assertEquals(0, lt.code(), lt::err);
        JsonNode correctives = MAPPER.readTree(lt.out()).path("correctives");
        assertEquals(2, correctives.size(), () -> "both operand-conditional fixes served: " + lt.out());
        assertTrue(correctives.get(0).path("whenOperands").isArray());
        assertTrue(!correctives.get(0).path("fix").asText().isBlank());
    }

    @Test
    void bareListCarriesTheEmptyOperandDefaults() throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("operators");
        assertEquals(0, res.code(), res::err);
        JsonNode defaults = MAPPER.readTree(res.out()).path("emptyOperandDefaults");
        assertEquals("AS_ZERO", defaults.path("number").asText());
        assertEquals("NOT_EVALUATED", defaults.path("string").asText());
        assertEquals("AS_FALSE", defaults.path("confirm").asText());
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
    void keywordIsATopicalFilterMatchingMeaningNotJustTheKeyword() throws Exception {
        // eval finding 01b: --keyword is a TOPICAL filter (keyword OR meaning), so `--keyword date` surfaces the
        // date-arithmetic ops whose keyword has no "date" (DifferenceInDays/AddYears) but whose meaning does —
        // not only those with "date" literally in the keyword.
        CliTestSupport.Result res = CliTestSupport.run("operators", "--keyword", "date");
        assertEquals(0, res.code(), res::err);
        boolean hasArithmetic = false;
        for (Iterator<JsonNode> it = MAPPER.readTree(res.out()).path("operators").elements(); it.hasNext();) {
            String id = it.next().path("id").asText();
            hasArithmetic |= "DifferenceInDays".equals(id) || "AddYears".equals(id);
        }
        assertTrue(hasArithmetic, () -> "--keyword date surfaces the date-arithmetic family via meaning: " + res.out());
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
    void searchingTheConceptWordFindsTheCountingOperators() throws Exception {
        // The report (#120) hit `operators count` returning RoundAccounting (substring "count" in "Accounting")
        // but NOT the real counters, whose names say "NumberOf…" not "count". Discovery is by the natural word,
        // so the counters' MEANING now carries "count" — the positional search matches id/keyword/meaning.
        CliTestSupport.Result res = CliTestSupport.run("operators", "count");
        assertEquals(0, res.code(), res::err);
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Iterator<JsonNode> it = MAPPER.readTree(res.out()).path("operators").elements(); it.hasNext();) {
            ids.add(it.next().path("id").asText());
        }
        for (String counter : new String[]{"NumberOfFilledFields", "NumberOfFilledGroups",
                "NumberOfDifferentValues", "NumberOfValueInFields"}) {
            assertTrue(ids.contains(counter),
                    () -> "`operators count` must surface the counting operator " + counter + ": " + ids);
        }
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
