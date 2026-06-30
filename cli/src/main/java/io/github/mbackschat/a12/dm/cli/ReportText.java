package io.github.mbackschat.a12.dm.cli;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The compact {@code --text} body for {@code model report} (MODEL-REVIEW-SPEC §4/§6): the comprehension
 * **catalog** — every rule and computation as a one-line gloss, then the dead-field set — instead of the
 * generic tree's full field dump. The counts + polarity ride the envelope headline, so this focuses on
 * the part a human actually reads: what each rule/computation *does*.
 */
final class ReportText implements VerbText {

    @Override
    public String render(JsonNode data) {
        StringBuilder sb = new StringBuilder();
        appendCatalog(sb, "rules", data.path("rules"), "rule");
        appendCatalog(sb, "computations", data.path("computations"), "computation");
        JsonNode unreferenced = data.path("unreferenced");
        if (unreferenced.isArray() && !unreferenced.isEmpty()) {
            sb.append("unreferenced (").append(unreferenced.size()).append("):\n");
            unreferenced.forEach(p -> sb.append("  ").append(p.asText()).append('\n'));
        }
        return sb.isEmpty() ? "(empty model)\n" : sb.toString();
    }

    private static void appendCatalog(StringBuilder sb, String label, JsonNode entries, String idKey) {
        if (!entries.isArray() || entries.isEmpty()) {
            return;
        }
        sb.append(label).append(":\n");
        for (JsonNode e : entries) {
            sb.append("  ").append(e.path(idKey).asText()).append(" — ").append(e.path("gloss").asText()).append('\n');
        }
    }
}
