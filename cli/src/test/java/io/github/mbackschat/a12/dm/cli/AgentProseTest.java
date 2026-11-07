package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The positive controls for the two checks every agent-prose guard delegates to {@link AgentProse}. */
class AgentProseTest {

    @Test
    void theProseCheckCatchesHistoryAndARepeatedSentence() {
        assertEquals(1, AgentProse.proseDefects("t", "Admitted. MEASURED: refused on 31.1.1.").size());
        assertEquals(1, AgentProse.proseDefects("t", "Labels moved to `meta` (G45).").size(), "a bare gap id");
        assertEquals(1, AgentProse.proseDefects("t", "Cited from KF556.").size(), "a finding id");
        assertEquals(1, AgentProse.proseDefects("t", "Refused (locked: rulekit.validate.laws.ArityLawsTest).").size(),
                "an evidence pointer");
        assertEquals(1, AgentProse.proseDefects("t", "The check is no longer evaluated.").size());
        assertEquals(1, AgentProse.proseDefects("t",
                "Copy a spelling from the list exactly. Copy a spelling from the list exactly.").size());
        assertEquals(List.of(), AgentProse.proseDefects("t", "Copy a spelling from the list exactly, e.g. 2026-01-01."));
        assertEquals(List.of(), AgentProse.proseDefects("t", "a loosened constraint admits data previously rejected"),
                "'previously' describes data, not the contract's history");
        assertEquals(1, AgentProse.proseDefects("t", "Here * used to mean ALL.").size());
        assertEquals(List.of(), AgentProse.proseDefects("t", "the pickup hour, used to bucket collections by shift"),
                "'used to' as purpose is not history");
    }

    @Test
    void theProseCheckCatchesEveryPointerForm() {
        for (String pointer : List.of("Ignores unspecified fields (KERNEL-SEMANTICS §9).", "Differs across leap years (§6).",
                "Refused, see TEMPORAL-FORMAT-VOCABULARY.", "Pinned by a12k-30.".toUpperCase(), "Reported as LF89.",
                "A CUSTOM field is refused (measured 2026-09-14).", "Admitted as measured on kernel 31.3.1.",
                "Eight pairs (corrected 2026-11-03).", "Refused on 31.1.1 and admitted later.",
                "Admitted. Note: the editor shows labels.", "Refused (Measured).")) {
            assertEquals(1, AgentProse.proseDefects("t", pointer).size(), pointer);
        }
        for (String plain : List.of("Use /G1/Amount as the operand.", "the row g1 of the example",
                "before the start of the Gregorian calendar (1583-10-16)", "a corrected value is read back",
                "Formats are case-sensitive; note the minutes.")) {
            assertEquals(List.of(), AgentProse.proseDefects("t", plain), plain);
        }
    }

    @Test
    void theBudgetCatchesGrowthSlackAndAnUnbudgetedEntry() {
        Map<String, Integer> budget = Map.of("a", 1000);
        assertEquals(List.of(), AgentProse.budgetDefects(Map.of("a", 1000), budget));
        assertEquals(List.of(), AgentProse.budgetDefects(Map.of("a", 1000 - AgentProse.SLACK), budget));
        assertEquals(1, AgentProse.budgetDefects(Map.of("a", 1001), budget).size(), "one character of growth");
        assertEquals(1, AgentProse.budgetDefects(Map.of("a", 1000 - AgentProse.SLACK - 1), budget).size(),
                "a reduction the budget does not lock in");
        assertEquals(2, AgentProse.budgetDefects(Map.of("new", 5), budget).size(),
                "an entry without a budget and a budget without an entry");
    }
}
