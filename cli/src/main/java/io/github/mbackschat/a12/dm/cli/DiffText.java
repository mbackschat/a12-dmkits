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
        for (JsonNode c : data.path("changes")) {
            switch (c.path("risk").asText("")) {
                case "HIGH" -> high.add(c);
                case "MEDIUM" -> medium.add(c);
                default -> low.add(c);
            }
        }
        StringBuilder sb = new StringBuilder();
        appendTier(sb, "HIGH", high);
        appendTier(sb, "MEDIUM", medium);
        if (!low.isEmpty()) {
            sb.append("LOW: ").append(low.size()).append(" low-risk change(s)\n");
        }
        return sb.isEmpty() ? "(no changes)\n" : sb.toString();
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
