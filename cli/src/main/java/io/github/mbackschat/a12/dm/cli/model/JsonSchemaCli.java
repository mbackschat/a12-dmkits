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

    /** A fixed, terminal-independent wrap width for note lines — keeps the stderr audit trail free of
     *  horizontal scroll for a real user, and keeps the captured demo output deterministic. */
    private static final int NOTE_WIDTH = 96;
    private static final String NOTE_BULLET = "  - ";
    private static final String NOTE_HANGING_INDENT = "    ";

    /** The human-readable report on stderr: a header, then each omission/to-do as a bulleted note,
     *  word-wrapped with a hanging indent so a long to-do never scrolls off the right edge. */
    static void printReport(PrintWriter err, TranscodeReport report) {
        err.println("transcoding report: " + mapped(report) + " mapped, " + omitted(report) + " omitted, "
                + report.diagnostics().size() + " note(s)");
        report.diagnostics().forEach(note -> printWrappedNote(err, note));
    }

    /** Greedy word-wrap of one note: the {@link #NOTE_BULLET} leads the first line, continuations hang
     *  under the text at {@link #NOTE_HANGING_INDENT}, every line kept within {@link #NOTE_WIDTH}. */
    private static void printWrappedNote(PrintWriter err, String note) {
        int budget = NOTE_WIDTH - NOTE_BULLET.length();
        StringBuilder line = new StringBuilder();
        boolean firstLine = true;
        for (String word : note.split(" ")) {
            if (line.length() > 0 && line.length() + 1 + word.length() > budget) {
                err.println((firstLine ? NOTE_BULLET : NOTE_HANGING_INDENT) + line);
                firstLine = false;
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        err.println((firstLine ? NOTE_BULLET : NOTE_HANGING_INDENT) + line);
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
