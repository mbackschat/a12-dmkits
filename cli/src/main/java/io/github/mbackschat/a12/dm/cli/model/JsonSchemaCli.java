package io.github.mbackschat.a12.dm.cli.model;

import io.github.mbackschat.a12.dm.schemakit.TranscodeReport;

import java.io.PrintWriter;

/**
 * Shared output for the JSON-Schema transcode verbs ({@code model import-jsonschema}/{@code export-jsonschema}).
 * Both emit their <em>artifact</em> to stdout (DM-JSON / JSON Schema) and the <b>transcoding report</b> — the
 * audit trail of an inherently lossy, choice-driven mapping — to stderr, so stdout stays a clean, pipeable
 * artifact while the omissions + to-dos are still surfaced (never silent).
 */
final class JsonSchemaCli {

    private JsonSchemaCli() {
    }

    static void printReport(PrintWriter err, TranscodeReport report) {
        long omitted = report.dispositions().stream()
                .filter(d -> d.tier() == TranscodeReport.Tier.OMITTED).count();
        long mapped = report.dispositions().size() - omitted;
        err.println("transcoding report: " + mapped + " mapped, " + omitted + " omitted, "
                + report.diagnostics().size() + " note(s)");
        report.diagnostics().forEach(note -> err.println("  - " + note));
    }
}
