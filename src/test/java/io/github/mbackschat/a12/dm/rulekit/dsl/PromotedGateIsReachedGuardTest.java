package io.github.mbackschat.a12.dm.rulekit.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.Checkout;
import io.github.mbackschat.a12.dm.SourceText;

/**
 * The counterpart to {@link ReadPathFormatGateIsolationTest}, which proves the inbound converter does NOT
 * reach a gate. Nothing proved the opposite direction: that a gate a ledger row calls <em>pre-checked</em> is
 * reached by the consumer the promotion exists to serve.
 *
 * <p>A gate can be built, tested, documented and completely unwired, and every artifact still reads as done —
 * the lock passes because it calls the gate directly, and the ledger's evidence column names that lock. Only
 * the CALL SITE distinguishes a wired gate from an unwired one, which makes it a guard's job.
 *
 * <p>The consumer is {@code :interpreter-bridge}: it maps stored condition TEXT for a kernel-free client, so it
 * is the path on which a pre-check turns silence into a verdict. Authoring through the typed API reaches these
 * methods by construction and needs no guard.
 *
 * <p><b>Why the gate list is written out rather than derived from source.</b> Deriving it needs call-graph
 * reachability — which public method can reach a {@code Refusals.of} — and a regex cannot do that. Both
 * approximations were measured here and both are unusable: a ONE-HOP scan misses a gate that refuses through
 * two private hops ({@code firstFilledGroup} → {@code firstFilledGroupScope} → {@code flattenGroup}), and
 * closing the relation transitively over method NAMES explodes, because propagating through a name as ordinary
 * as {@code field(} marked 60 of the 82 public methods as refusing and demanded the bridge call
 * {@code ofJson}. So the denominator is explicit, and {@link #theRefusalSiteCountIsPinned} is what stops it
 * going stale: a new refusal anywhere in the gate-bearing files fails the build and asks whether a new
 * gate appeared.
 */
class PromotedGateIsReachedGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();
    private static final String DSL = "rulekit/src/main/java/io/github/mbackschat/a12/dm/rulekit/dsl/";
    private static final String CONSUMER = "interpreter-bridge/src/main";

    /** Refusing {@code BoundModel} gates, each of which the condition-text consumer must reach. */
    private static final Set<String> GATES = Set.of(
            "baseYear", "baseYearNumber", "startOfBaseYear", "endOfBaseYear",
            "flatten", "flattenGroup", "firstFilledGroup", "firstFilledGroupFor",
            "latestOf", "earliestOf", "latestTimeOf", "earliestTimeOf",
            "latestDateTimeOf", "earliestDateTimeOf", "numberOfDifferentValues",
            "rangesEqual", "rangesNotEqual", "dateRangeOf", "dateOf",
            "valueAsDateFirstDay", "valueAsDateLastDay");

    /**
     * Gates the condition-text consumer legitimately cannot reach, each with the reason. A gate belongs here
     * only when no stored condition can produce its shape — never because wiring it looks awkward.
     */
    private static final Map<String, String> EXEMPT = Map.of();

    /**
     * The refusal sites the gate-bearing files carry today. This is the staleness tripwire for {@link
     * #GATES}, deliberately a count rather than an analysis: it cannot say which method a new refusal belongs
     * to, only that one appeared and the list needs a human look. Over-triggering costs one reading; the
     * alternative is a hand-written denominator nothing checks.
     */
    private static final Map<String, Integer> REFUSAL_SITES =
            Map.of("BoundModel.java", 7, "Dsl.java", 9, "DuplicateGate.java", 2);

    private static final Pattern REFUSAL = Pattern.compile("Refusals\\.of\\(");

    /**
     * A call to {@code gate} on any receiver EXCEPT {@code Dsl}. The exclusion is load-bearing rather than
     * tidy: several gates have an ungated {@code Dsl} twin of the same name, so {@code Dsl.baseYear(} contains
     * {@code .baseYear(} and a plain substring test reads the unchecked call as proof the checked one is wired.
     */
    private static boolean reaches(String text, String gate) {
        return Pattern.compile("(?<!Dsl)\\." + Pattern.quote(gate) + "\\(").matcher(text).find();
    }

    /** THE INVARIANT: a gate nothing calls is not a pre-check, whatever its ledger row says. */
    @Test
    void everyRefusingGateIsReachedByTheConditionTextConsumer() throws IOException {
        String consumer = consumerSource();
        var unreached = new LinkedHashMap<String, String>();
        for (String gate : GATES) {
            if (!EXEMPT.containsKey(gate) && !reaches(consumer, gate)) {
                unreached.put(gate, "no call in " + CONSUMER + " on a receiver other than Dsl");
            }
        }

        assertEquals(Map.of(), unreached, () -> """
                a gate that refuses is not reached by the condition-text consumer, so for a kernel-free client \
                it does not exist — its lock passes by calling it directly and its ledger row cites that lock, \
                which is why this cannot be noticed downstream. Wire it in :interpreter-bridge, or add it to \
                EXEMPT with the reason no stored condition can produce its shape:
                  """ + unreached.keySet());
    }

    /**
     * The staleness tripwire. A refusal added to either file changes this count, and the author then decides
     * whether it created a new public gate that {@link #GATES} must name and the consumer must reach.
     */
    @Test
    void theRefusalSiteCountIsPinned() throws IOException {
        var actual = new LinkedHashMap<String, Integer>();
        for (String file : REFUSAL_SITES.keySet()) {
            String text = SourceText.uncommented(CHECKOUT.resolve(DSL + file));
            int count = 0;
            Matcher m = REFUSAL.matcher(text);
            while (m.find()) {
                count++;
            }
            actual.put(file, count);
        }

        assertEquals(new LinkedHashMap<>(REFUSAL_SITES), actual,
                "the refusal sites moved. If a new PUBLIC gate appeared, name it in GATES and wire it in "
                        + ":interpreter-bridge; if the refusal sits behind an existing gate, just update the "
                        + "count. This exists because the gate list cannot be derived — see the class doc");
    }

    /** An exemption that no longer names a live gate is stale, and a stale exemption hides the next one. */
    @Test
    void everyExemptionStillNamesAGate() {
        for (String exempt : EXEMPT.keySet()) {
            assertTrue(GATES.contains(exempt),
                    () -> "EXEMPT names " + exempt + ", which is not in GATES — drop the row");
        }
    }

    /**
     * The scanner must see the calls it hunts, one planted call per gate rather than one for the list: an
     * {@code anyMatch} over the set is satisfied by a single recognised name and leaves the rest unproven.
     * Each planted call goes through {@link #reaches}, the predicate the guard itself uses.
     */
    @Test
    void theScannerWouldSeeEveryGatedCall() {
        for (String gate : GATES) {
            String planted = "return model." + gate + "(a, b);";
            assertTrue(reaches(planted, gate), () -> "the scanner no longer recognizes a call to " + gate);
        }
    }

    /** The legitimate routes, including the ungated twin whose name the checked gate must not borrow. */
    @Test
    void theScannerIgnoresTheUngatedTwins() {
        for (String legitimate : List.of(
                "var x = Dsl.baseYear().expression();",
                "var x = Dsl.startOfBaseYear();",
                "var x = Dsl.endOfBaseYear();",
                "var x = Dsl.baseYearNumber();",
                "var x = Dsl.dateRange(start, end);",
                "return left.eq(right);")) {
            assertTrue(GATES.stream().noneMatch(g -> reaches(legitimate, g)),
                    () -> "a legitimate ungated route is read as a gate call: " + legitimate);
        }
    }

    private static String consumerSource() throws IOException {
        var joined = new StringBuilder();
        for (Path source : CHECKOUT.tracked(CONSUMER)) {
            String name = source.getFileName().toString();
            if (name.endsWith(".kt") || name.endsWith(".java")) {
                joined.append(SourceText.uncommented(source)).append('\n');
            }
        }
        assertTrue(joined.length() > 0, "read no consumer source, so this guard proved nothing");
        return joined.toString();
    }
}
