package io.github.mbackschat.a12.dm.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The two checks every agent-read surface shares: its prose states the current contract once, and its size stays
 * within a committed budget. The surfaces and why they need it are listed in
 * <a href="../../../../../../../../../../docs/CLI-SPEC.md#agent-prose-budgets">CLI-SPEC "Agent prose budgets"</a>.
 */
final class AgentProse {

    /** One budget file for every surface, sectioned by surface, so a raise anywhere shows in one diff. */
    static final Path BUDGET = Path.of("src/test/resources/agent-prose-budget.json");

    /** How far a budget may sit above the actual size before it must be lowered; one short sentence. */
    static final int SLACK = 200;

    /**
     * Prose true only of a past release, or a pointer an agent with only the binary cannot follow: a finding or ticket
     * id, a repository document or section, a test class. Words are matched in any case; ids only as written, so a
     * lower-case {@code g1} in an example path is not a gap id, and a {@code /G1} path segment never is.
     */
    private static final Pattern HISTORY = Pattern.compile("(?i:\\bMEASURED:|\\(measured[^)]*\\)|\\bmeasured (?:on|in) (?:kernel|\\d)"
            + "|\\bused to (?:be|mean|read|say|share|accept|allow|refuse|reject|fire|report|take)\\b|\\bno longer\\b"
            + "|\\bformerly\\b|\\bcorrected \\d{4}-|\\b(?:since|through|before|until|in|on) kernel \\d|\\bkernel \\d+\\.\\d+"
            + "|\\b(?:since|on|through|until) \\d{2}\\.\\d+(?:\\.\\d+)?\\b|\\blocked(?: by)?:)"
            + "|\\bA12K-\\d+\\b|\\b(?:KF|IF|LF|SG|DG|CG|IG|MG|MR|IAG|IRG)\\d+\\b|(?<![/\\w])G\\d+\\b"
            + "|\\b[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*-(?:SPEC|FINDINGS|GAPS|SEMANTICS|LEDGER|VOCABULARY|GUIDE|NOTES|DESIGN|MAP"
            + "|INDEX|PROPOSAL|CERTIFICATE)\\b|§\\s?\\d|\\b(?:[a-z]+\\.)+[A-Z]\\w*Test\\b"
            + "|(?:^|[.;]\\s+)Note\\b");

    /** Sentences long enough that a repeat is a paste, not a shared short phrase. */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentProse() {
    }

    /** History markers and repeated sentences in one piece of prose, each named with {@code where}. */
    static List<String> proseDefects(String where, String text) {
        List<String> defects = new ArrayList<>();
        Matcher history = HISTORY.matcher(text);
        if (history.find()) {
            defects.add(where + ": history \"" + history.group() + "\"");
        }
        List<String> sentences = Stream.of(SENTENCE_END.split(text)).map(String::strip)
                .filter(sentence -> sentence.length() > 25).toList();
        sentences.stream().filter(sentence -> sentences.indexOf(sentence) != sentences.lastIndexOf(sentence))
                .findFirst().ifPresent(sentence -> defects.add(where + ": repeated \"" + sentence + "\""));
        return defects;
    }

    /** Growth past a budget, a budget left more than {@link #SLACK} above a reduction, and a key in one map only. */
    static List<String> budgetDefects(Map<String, Integer> actual, Map<String, Integer> budget) {
        List<String> defects = new ArrayList<>();
        TreeSet<String> names = new TreeSet<>(actual.keySet());
        names.addAll(budget.keySet());
        for (String name : names) {
            Integer used = actual.get(name);
            Integer allowed = budget.get(name);
            if (allowed == null) {
                defects.add(name + ": no budget (actual " + used + ")");
            } else if (used == null) {
                defects.add(name + ": budgeted, but no such entry");
            } else if (used > allowed) {
                defects.add(name + ": " + used + " > budget " + allowed);
            } else if (allowed - used > SLACK) {
                defects.add(name + ": " + used + " leaves budget " + allowed + " slack; lower it");
            }
        }
        return defects;
    }

    /** One section of the budget file. */
    static Map<String, Integer> budget(String section) {
        try {
            Map<String, TreeMap<String, Integer>> all = MAPPER.readValue(BUDGET.toFile(),
                    new TypeReference<TreeMap<String, TreeMap<String, Integer>>>() { });
            Map<String, Integer> found = all.get(section);
            if (found == null) {
                throw new IllegalStateException(BUDGET + " has no section \"" + section + "\"");
            }
            return found;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The failure message every budget guard shares, so the corrective reads the same on every surface. */
    static String budgetFailure(String section, List<String> defects) {
        return "agent prose is off its committed budget (" + BUDGET + ", section \"" + section + "\"). Growth: "
                + "condense first, rewriting rather than annotating; raise a budget only for a new contract fact, in "
                + "the same change. Slack: lower the budget to the actual size.\n  " + String.join("\n  ", defects);
    }

    /** The failure message every history guard shares. */
    static String proseFailure(List<String> defects) {
        return "agent prose must state the current contract once — rewrite the sentence instead of annotating "
                + "it, and leave history and finding ids to KERNEL-FINDINGS and git: " + defects;
    }
}
