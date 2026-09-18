package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * That the portable-consistency plan's stage narrative states the same status as its own row table.
 *
 * <p>That plan is the state carrier for work spanning sessions — the next session reads a row to decide what
 * to pick up — so a row and its narrative disagreeing sends it either at blocked work or past finished work.
 * Both directions were measured on 2026-09-16, in one day and in three rows: stage 10 still said two rows
 * owed a decision that stage 11 recorded as answered; stage 11 said "what remains is I-b" directly above its
 * own table marking I-b landed; stage 5 read {@code doing} against a row that says nothing in it is
 * schedulable. A landing updates the table and the narrative sentence around it is what gets missed, because
 * nothing reads the two together.
 *
 * <p>It compares the leading status word only. The narrative legitimately qualifies it ({@code **part, and
 * nothing in it is schedulable**}), and asserting the whole lead would force the two surfaces to be one
 * sentence, which would defeat the point of having a narrative at all.
 *
 * <p>The floor is what keeps this from passing on a corpus it failed to parse: a heading or table format
 * change would otherwise leave zero pairs compared and report green. It is deliberately below the live count
 * so it answers "did the scan happen" rather than tracking the document's growth.
 *
 * <p>This guard retires with the document. When the plan is archived, delete it rather than re-pointing it:
 * the convention it locks is that plan's, not the estate's.
 */
class PlanStageStatusAgreementGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final String PLAN = "docs/PORTABLE-CONSISTENCY-IMPLEMENTATION-PLAN.md";

    /**
     * The status table itself, located by its own header rather than by the shape of a row.
     *
     * <p>Anchoring is load-bearing, not tidiness. This read a bare {@code | <digits> | <cell> |} anywhere in
     * the document, so ANY other numbered table was parsed as the status table and its later rows silently
     * replaced the real ones — measured 2026-09-18, when a six-item acceptance matrix added near the end of
     * the plan overwrote stages 4, 5 and 6 with the first word of its own cells. The floor below cannot catch
     * that: the scan ran, found plenty of rows, and compared the wrong ones.
     */
    private static final Pattern STATUS_TABLE = Pattern.compile(
            "^\\| Order \\| Status \\|[^\\n]*\\n\\|[-| ]+\\|\\n((?:\\|[^\\n]*\\n)+)", Pattern.MULTILINE);

    /** A row of the status table: its ordinal, then the status cell that follows it. */
    private static final Pattern ROW = Pattern.compile("^\\| (\\d+) \\| ([^|]+)\\|", Pattern.MULTILINE);

    /** A stage heading, and the bolded lead of the paragraph that opens it. */
    private static final Pattern STAGE =
            Pattern.compile("^#### Stage (\\d+) [^\\n]*\\n\\n(\\*\\*[^\\n]+)", Pattern.MULTILINE);

    /** Eight stage sections exist at 2026-09-16; the floor answers whether the scan ran, not how big it is. */
    private static final int MINIMUM_PAIRS = 6;

    @Test
    void everyStageNarrativeStatesTheStatusItsOwnRowStates() throws IOException {
        String plan = Files.readString(CHECKOUT.requireFile(PLAN));
        Matcher table = STATUS_TABLE.matcher(plan);
        assertTrue(table.find(),
                "found no status table in " + PLAN + " — its header is what locates the rows, so without it "
                        + "this guard would read some other table's rows as stage statuses");
        Map<Integer, String> rows = statusesOf(ROW, table.group(1));
        Map<Integer, String> stages = statusesOf(STAGE, plan);

        assertTrue(rows.size() >= MINIMUM_PAIRS,
                "parsed only " + rows.size() + " table rows in " + PLAN + ", so the row format changed and "
                        + "this guard would have compared nothing");
        assertTrue(stages.size() >= MINIMUM_PAIRS,
                "parsed only " + stages.size() + " stage narratives in " + PLAN + ", so the heading or lead "
                        + "format changed and this guard would have compared nothing");

        Map<Integer, String> expected = new LinkedHashMap<>();
        Map<Integer, String> actual = new LinkedHashMap<>();
        stages.forEach((stage, status) -> {
            String row = rows.get(stage);
            // A stage with no row of its own is the other drift this catches, and it reads better as a
            // mismatch against a named absence than as a separate assertion nobody would connect to it.
            expected.put(stage, row == null ? "<no row " + stage + " in the status table>" : row);
            actual.put(stage, status);
        });
        assertEquals(expected, actual,
                "a stage narrative in " + PLAN + " states a different status from its own row. The row table "
                        + "and the narrative are two statements of one fact, and the next session reads "
                        + "whichever it opens first");
    }

    /** Ordinal to leading status word, for whichever surface [pattern] addresses. */
    private static Map<Integer, String> statusesOf(Pattern pattern, String plan) {
        Map<Integer, String> found = new LinkedHashMap<>();
        Matcher matcher = pattern.matcher(plan);
        while (matcher.find()) {
            found.put(Integer.parseInt(matcher.group(1)), leadingStatusWord(matcher.group(2)));
        }
        return found;
    }

    /**
     * The first word of a status lead, with the bold markers and any qualifier dropped.
     *
     * <p>Row 13 carries a bare {@code open} rather than a bolded lead, so the markers are stripped where
     * present instead of being required — a guard that demanded the emphasis would be asserting typography.
     */
    private static String leadingStatusWord(String cell) {
        String lead = cell.trim();
        if (lead.startsWith("**")) {
            lead = lead.substring(2);
        }
        int end = lead.length();
        for (int index = 0; index < lead.length(); index++) {
            if (" ,*—".indexOf(lead.charAt(index)) >= 0) {
                end = index;
                break;
            }
        }
        return lead.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }
}
