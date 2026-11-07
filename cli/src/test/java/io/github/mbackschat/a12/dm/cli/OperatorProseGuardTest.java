package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorCatalog;
import io.github.mbackschat.a12.dm.rulekit.catalog.OperatorEntry;

/**
 * Holds each operator's served entry ({@code dmtool operators <id>}) to its budget and its prose to the current
 * contract. An agent pays for one operator per call, so the budget is per operator: a catalog total would hide one
 * operator's growth behind another's condensation. Between 2026-08-02 and 2026-11-06 the catalog's prose grew 35%
 * while gaining one operator, and single entries grew three- to fourteen-fold.
 *
 * <p>It measures what the verb emits, not the catalog file, because the verb drops the CI-only probes and adds
 * nothing an agent does not read.
 */
class OperatorProseGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Map<String, String> SERVED = new TreeMap<>();

    @BeforeAll
    static void serveEveryOperator() {
        for (OperatorEntry entry : OperatorCatalog.load().entries()) {
            CliTestSupport.Result result = CliTestSupport.run("operators", entry.id());
            assertEquals(0, result.code(), result::err);
            SERVED.put(entry.id(), result.out());
        }
        assertTrue(SERVED.size() > 100, "the catalog walk reached only " + SERVED.size() + " operators");
    }

    @Test
    void everyServedOperatorStaysWithinItsBudget() {
        Map<String, Integer> actual = new TreeMap<>();
        SERVED.forEach((id, out) -> actual.put(id, out.length()));
        List<String> defects = AgentProse.budgetDefects(actual, AgentProse.budget("operators"));
        assertTrue(defects.isEmpty(), () -> AgentProse.budgetFailure("operators", defects));
    }

    @Test
    void servedOperatorProseCarriesNoHistoryAndNoRepeatedSentence() throws Exception {
        List<String> hits = new ArrayList<>();
        for (Map.Entry<String, String> served : SERVED.entrySet()) {
            collect(MAPPER.readTree(served.getValue()), served.getKey(), hits);
        }
        assertTrue(hits.isEmpty(), () -> AgentProse.proseFailure(hits));
    }

    private static void collect(JsonNode node, String where, List<String> hits) {
        if (node.isTextual()) {
            hits.addAll(AgentProse.proseDefects(where, node.asText()));
        }
        node.forEach(child -> collect(child, where, hits));
    }
}
