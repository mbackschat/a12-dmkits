package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

/**
 * {@link DiffText} — the compact, risk-sorted {@code --text} body for {@code model diff} (MODEL-REVIEW
 * §5f, LEDGER MR5). Groups changes HIGH → MEDIUM, one scannable line each, and collapses LOW to a count
 * so the dangerous changes are read first.
 */
class DiffTextTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode data(String changesJson) throws Exception {
        return M.readTree("{\"changes\":" + changesJson + "}");
    }

    @Test
    void groupsByRiskHighFirstOnePerLine() throws Exception {
        String out = new DiffText().render(data("""
            [ {"element":"RULE","id":"/Order/A","change":"MODIFIED","risk":"HIGH","riskReasons":["POLARITY_INVERTED"],"deltas":[]},
              {"element":"FIELD","id":"/Order/B","change":"ADDED","risk":"MEDIUM","riskReasons":["STRUCTURE_ADDED"],"deltas":[]} ]"""));
        int high = out.indexOf("HIGH (1)");
        int med = out.indexOf("MEDIUM (1)");
        assertTrue(high >= 0 && med > high, () -> "HIGH group precedes MEDIUM:\n" + out);
        assertTrue(out.contains("MODIFIED") && out.contains("rule") && out.contains("/Order/A")
                && out.contains("POLARITY_INVERTED"), () -> "a scannable HIGH line:\n" + out);
        // one line per change — no verbose nested aspect tree
        assertFalse(out.contains("riskReasons") || out.contains("deltas"), () -> "compact, not the JSON tree:\n" + out);
    }

    @Test
    void collapsesLowRiskToACount() throws Exception {
        String out = new DiffText().render(data("""
            [ {"element":"FIELD","id":"/Order/A","change":"MODIFIED","risk":"LOW","riskReasons":["METADATA_CHANGED"],"deltas":[]},
              {"element":"FIELD","id":"/Order/B","change":"MODIFIED","risk":"LOW","riskReasons":["CODE_CHANGED"],"deltas":[]} ]"""));
        assertTrue(out.contains("LOW: 2"), () -> "LOW collapsed to a count:\n" + out);
        assertFalse(out.contains("/Order/A"), () -> "collapsed LOW does not list each id:\n" + out);
    }

    @Test
    void emptyChangeSetReadsAsNoChanges() throws Exception {
        assertEquals("(no changes)\n", new DiffText().render(data("[]")));
    }
}
