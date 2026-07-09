package io.github.mbackschat.a12.dm.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The compact {@code --text} body for {@code model diff} (MODEL-REVIEW-SPEC §5f): the change-set
 * grouped by risk (HIGH → MEDIUM), one scannable line per change, with LOW collapsed to a count — so a
 * reviewer reads the dangerous changes first instead of wading through the verbose JSON tree. The full
 * detail (per-aspect deltas) stays in the JSON the headline already points at.
 */
final class DiffText implements VerbText {

    @Override
    public String render(JsonNode data) {
        List<JsonNode> high = new ArrayList<>();
        List<JsonNode> medium = new ArrayList<>();
        List<JsonNode> low = new ArrayList<>();
        List<JsonNode> reordered = new ArrayList<>();
        for (JsonNode c : data.path("changes")) {
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
        appendReorders(sb, reordered);
        if (!low.isEmpty()) {
            sb.append("LOW: ").append(low.size()).append(" low-risk change(s)\n");
        }
        return sb.isEmpty() ? "(no changes)\n" : sb.toString();
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
                    .append("  ").append(reasons(c))
                    .append('\n');
        }
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
