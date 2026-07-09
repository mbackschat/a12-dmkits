package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The compact {@code --text} body for {@code model diff} (MODEL-REVIEW-SPEC §5f): the change-set
 * grouped by risk (HIGH → MEDIUM), one scannable line per change, with LOW collapsed to a count — so a
 * reviewer reads the dangerous changes first instead of wading through the verbose JSON tree. A
 * reference-rewriting refactor is collapsed under a single REFACTORS headline (its rename + rewritten
 * referrers folded, MR4); a pure reorder gets its own section (MR2). The full detail stays in the JSON.
 */
final class DiffText implements VerbText {

    @Override
    public String render(JsonNode data) {
        Set<String> foldedIntoRefactor = refactorMemberIds(data.path("refactors"));
        List<JsonNode> high = new ArrayList<>();
        List<JsonNode> medium = new ArrayList<>();
        List<JsonNode> low = new ArrayList<>();
        List<JsonNode> reordered = new ArrayList<>();
        for (JsonNode c : data.path("changes")) {
            // a folded refactor member is shown under the REFACTORS headline instead of its risk tier
            if (foldedIntoRefactor.contains(c.path("id").asText())) {
                continue;
            }
            // a pure reorder is LOW risk but is surfaced in its own section (display-order is semantic, MR2),
            // never swallowed by the collapsed LOW count
            if ("REORDERED".equals(c.path("change").asText())) {
                reordered.add(c);
                continue;
            }
            switch (c.path("risk").asText("")) {
                case "HIGH" -> high.add(c);
                case "MEDIUM" -> medium.add(c);
                default -> low.add(c);
            }
        }
        StringBuilder sb = new StringBuilder();
        appendTier(sb, "HIGH", high);
        appendTier(sb, "MEDIUM", medium);
        appendRefactors(sb, data.path("refactors"));
        appendReorders(sb, reordered);
        if (!low.isEmpty()) {
            sb.append("LOW: ").append(low.size()).append(" low-risk change(s)\n");
        }
        return sb.isEmpty() ? "(no changes)\n" : sb.toString();
    }

    /** The ids folded into a refactor (each rename anchor + its referrers) — shown under the headline, not a tier. */
    private static Set<String> refactorMemberIds(JsonNode refactors) {
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode r : refactors) {
            ids.add(r.path("from").asText());
            r.path("referrers").forEach(n -> ids.add(n.asText()));
        }
        return ids;
    }

    /** The refactor section: one headline per grouped rename/move with the count of referrers it rewrote. */
    private static void appendRefactors(StringBuilder sb, JsonNode refactors) {
        if (refactors.isEmpty()) {
            return;
        }
        sb.append("REFACTORS (").append(refactors.size()).append(")\n");
        for (JsonNode r : refactors) {
            int referrers = r.path("referrers").size();
            sb.append("  ↻ ").append(r.path("kind").asText()).append(' ')
                    .append(r.path("from").asText()).append(" → ").append(r.path("to").asText())
                    .append("  (").append(referrers).append(referrers == 1 ? " referrer" : " referrers")
                    .append(" rewritten)\n");
        }
    }

    /** The reorder section: one line per moved element with its {@code position} before→after move. */
    private static void appendReorders(StringBuilder sb, List<JsonNode> reordered) {
        if (reordered.isEmpty()) {
            return;
        }
        sb.append("REORDERED (").append(reordered.size()).append(")\n");
        for (JsonNode c : reordered) {
            sb.append("  ")
                    .append(pad(c.path("element").asText().toLowerCase(Locale.ROOT), 5)).append(' ')
                    .append(c.path("id").asText())
                    .append("  position ").append(positionMove(c))
                    .append('\n');
        }
    }

    private static String positionMove(JsonNode change) {
        for (JsonNode d : change.path("deltas")) {
            if ("position".equals(d.path("aspect").asText())) {
                return d.path("before").asText("?") + "→" + d.path("after").asText("?");
            }
        }
        return "?";
    }

    private static void appendTier(StringBuilder sb, String tier, List<JsonNode> changes) {
        if (changes.isEmpty()) {
            return;
        }
        sb.append(tier).append(" (").append(changes.size()).append(")\n");
        for (JsonNode c : changes) {
            sb.append("  ")
                    .append(pad(c.path("change").asText(), 8)).append(' ')
                    .append(pad(c.path("element").asText().toLowerCase(Locale.ROOT), 5)).append(' ')
                    .append(c.path("id").asText())
                    .append(relocationSuffix(c))
                    .append("  ").append(reasons(c))
                    .append('\n');
        }
    }

    /** For a RENAMED/MOVED change, the {@code → newPath} target read off its {@code path} delta; else empty. */
    private static String relocationSuffix(JsonNode change) {
        String kind = change.path("change").asText();
        if (!"RENAMED".equals(kind) && !"MOVED".equals(kind)) {
            return "";
        }
        for (JsonNode d : change.path("deltas")) {
            if ("path".equals(d.path("aspect").asText())) {
                return " → " + d.path("after").asText();
            }
        }
        return "";
    }

    private static String reasons(JsonNode change) {
        List<String> r = new ArrayList<>();
        change.path("riskReasons").forEach(n -> r.add(n.asText()));
        return String.join(" + ", r);
    }

    private static String pad(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }
}
