package io.github.mbackschat.a12.dm.cli.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mbackschat.a12.dm.schemakit.TranscodeReport;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The human-readable transcoding report rides stderr; long omission to-dos used to print as one
 * unbounded line that scrolls horizontally in a terminal (and in the captured demos). It now
 * word-wraps each note at a fixed, terminal-independent width with a hanging indent — readable for
 * a real user and deterministic for the showboat captures.
 */
class JsonSchemaCliReportTest {

    /** The fixed wrap width the report keeps every line within. */
    private static final int MAX_WIDTH = 96;

    private static List<String> render(TranscodeReport report) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            JsonSchemaCli.printReport(pw, report);
        }
        return sw.toString().lines().toList();
    }

    @Test
    void longNoteWrapsUnderTheFixedWidthWithHangingIndent() {
        String longNote = "number 'price' has no scale in the schema — imported at the default "
                + "scale 2 (a guess; set --default-scale, --number-scale, or the field's scale by "
                + "hand if a different precision is needed)";
        TranscodeReport report = TranscodeReport.builder()
                .mapped("price", "/Root/price", TranscodeReport.Tier.TIER_A)
                .diagnostic(longNote)
                .build();

        List<String> lines = render(report);

        assertTrue(lines.get(0).startsWith("transcoding report:"), () -> "header first: " + lines);
        for (String line : lines) {
            assertTrue(line.length() <= MAX_WIDTH,
                    () -> "no line scrolls past " + MAX_WIDTH + " cols: [" + line.length() + "] " + line);
        }

        List<String> noteLines = lines.subList(1, lines.size());
        assertTrue(noteLines.size() >= 2, () -> "a long note must wrap onto several lines: " + noteLines);
        assertTrue(noteLines.get(0).startsWith("  - "), () -> "the bullet leads the note: " + noteLines.get(0));
        for (String continuation : noteLines.subList(1, noteLines.size())) {
            assertTrue(continuation.startsWith("    ") && !continuation.startsWith("  - "),
                    () -> "continuations hang under the text, not the bullet: " + continuation);
        }

        // wrapping is loss-free: stripping the prefixes and rejoining reproduces the original note
        String rejoined = noteLines.stream()
                .map(line -> line.replaceFirst("^(  - |    )", ""))
                .reduce("", (a, b) -> a.isEmpty() ? b : a + " " + b);
        assertEquals(longNote, rejoined, "the wrapped note carries the full text, nothing dropped");
    }

    @Test
    void shortNoteStaysOnOneBulletLine() {
        TranscodeReport report = TranscodeReport.builder()
                .diagnostic("dialect: OPENAPI_30 (auto-detected)")
                .build();

        List<String> lines = render(report);

        assertEquals(2, lines.size(), () -> "header + a single note line: " + lines);
        assertEquals("  - dialect: OPENAPI_30 (auto-detected)", lines.get(1));
    }
}
