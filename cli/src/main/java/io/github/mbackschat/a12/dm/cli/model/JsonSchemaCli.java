package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.schemakit.TranscodeReport;

import java.io.PrintWriter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Shared output for the JSON-Schema transcode verbs ({@code model import-jsonschema}/{@code export-jsonschema}).
 * Both emit their <em>artifact</em> to stdout (DM-JSON / JSON Schema) and the <b>transcoding report</b> — the
 * audit trail of an inherently lossy, choice-driven mapping — to stderr, so stdout stays a clean, pipeable
 * artifact while the omissions + to-dos are still surfaced (never silent). With {@code -o} the artifact goes to
 * the file and the same report rides the result envelope's {@code changed.report} as STRUCTURED data — the
 * agent's actionable to-do list (the omissions to complete by hand) in the machine-readable channel, not only
 * the human stderr lines.
 */
final class JsonSchemaCli {

    private JsonSchemaCli() {
    }

    /** The human-readable report, one omission/to-do per line, on stderr (the clean-artifact-on-stdout case). */
    static void printReport(PrintWriter err, TranscodeReport report) {
        err.println("transcoding report: " + mapped(report) + " mapped, " + omitted(report) + " omitted, "
                + report.diagnostics().size() + " note(s)");
        report.diagnostics().forEach(note -> err.println("  - " + note));
    }

    /** The same report as STRUCTURED data ({@code mapped}/{@code omitted} counts + the {@code notes} to-do list)
     *  — carried in the {@code -o} envelope's {@code changed} so an agent reads the audit trail from stdout. */
    static ObjectNode reportNode(ObjectMapper mapper, TranscodeReport report) {
        ObjectNode node = mapper.createObjectNode();
        node.put("mapped", mapped(report));
        node.put("omitted", omitted(report));
        ArrayNode notes = node.putArray("notes");
        report.diagnostics().forEach(notes::add);
        return node;
    }

    private static long omitted(TranscodeReport report) {
        return report.dispositions().stream().filter(d -> d.tier() == TranscodeReport.Tier.OMITTED).count();
    }

    private static long mapped(TranscodeReport report) {
        return report.dispositions().size() - omitted(report);
    }
}
