package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link SourceText}'s correctness, measured rather than assumed. One shared lexer serves every census, so a
 * defect here is a defect in all of them at once — and the failure it must not repeat is silent: a mis-scanned
 * file is blanked, and a rule then finds nothing in code it never saw.
 */
class SourceTextTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Each case: what the stripper must NOT see, and what it must still see. */
    private static final Map<String, String[]> CASES = new LinkedHashMap<>();

    static {
        // {source, must-not-contain, must-contain}
        CASES.put("kotlin nested block comment", new String[] {
            "/* outer /* inner */ HIDDEN */\nval b = KEPT\n", "HIDDEN", "KEPT"});
        CASES.put("block delimiters inside a string", new String[] {
            "val s = \"/* not a comment */\"\nval b = KEPT\n", "", "KEPT"});
        CASES.put("line delimiter inside a string", new String[] {
            "val s = \"// not a comment\"\nval b = KEPT\n", "", "KEPT"});
        CASES.put("line delimiter inside a raw string", new String[] {
            "val s = \"\"\"\n// still text\n\"\"\"\nval b = KEPT\n", "", "still text"});
        CASES.put("delimiter in a char literal", new String[] {
            "char c = '/';\nint kept = KEPT;\n", "", "KEPT"});
        CASES.put("escaped quote before a delimiter", new String[] {
            "String s = \"he said \\\"/*\\\"\";\nint kept = KEPT;\n", "", "KEPT"});
        CASES.put("plain line comment", new String[] {
            "int a = 1; // HIDDEN\nint b = KEPT;\n", "HIDDEN", "KEPT"});
    }

    @Test
    void everyDelimiterCaseIsClassifiedCorrectly() {
        for (var entry : CASES.entrySet()) {
            String[] c = entry.getValue();
            String stripped = SourceText.uncommented(c[0], true);
            if (!c[1].isEmpty()) {
                assertTrue(!stripped.contains(c[1]),
                        () -> entry.getKey() + ": comment content survived — " + stripped);
            }
            assertTrue(stripped.contains(c[2]),
                    () -> entry.getKey() + ": real code was blanked — " + stripped);
        }
    }

    @Test
    void javaDoesNotNestSoTheFirstCloseEndsTheComment() {
        String java = "/* outer /* inner */ int kept = KEPT;\n";
        assertTrue(SourceText.uncommented(java, false).contains("KEPT"),
                "Java closes on the first delimiter; treating it as nesting swallows the rest of the file");
        assertThrows(IllegalStateException.class, () -> SourceText.uncommented(java, true),
                "under Kotlin's rule the same text is an unterminated comment, and an unterminated scan must be "
                        + "refused loudly rather than returned partly blanked");
    }

    @Test
    void characterPositionsArePreserved() {
        for (String[] c : CASES.values()) {
            assertEquals(c[0].length(), SourceText.uncommented(c[0], true).length(),
                    "stripping must blank in place, so an offset into the result still points at the original");
        }
    }

    @Test
    void strippingIsIdempotent() {
        for (String[] c : CASES.values()) {
            String once = SourceText.uncommented(c[0], true);
            assertEquals(once, SourceText.uncommented(once, true), "stripping twice must equal stripping once");
        }
    }

    /**
     * The corpus check, and the one that would have caught the original incident: it was found as ten Java files
     * ending mid-comment. Every tracked source must leave the lexer outside every comment and literal.
     */
    @Test
    void everyTrackedSourceEndsInANeutralLexerState() throws IOException {
        var offenders = new ArrayList<String>();
        int scanned = 0;
        for (Path source : CHECKOUT.sources()) {
            scanned++;
            SourceText.Scan scan = SourceText.scan(Files.readString(source), SourceText.nestsBlockComments(source));
            if (!scan.endedNeutral()) {
                String head = Files.readString(source).substring(0, Math.max(0, scan.openedAt()));
                offenders.add(CHECKOUT.relative(source) + " ended in " + scan.endedIn()
                        + " opened at line " + (head.chars().filter(ch -> ch == '\n').count() + 1));
            }
        }
        int read = scanned;
        assertTrue(read > 1500, () -> "scanned only " + read + " sources — a green result would mean the file "
                + "selection broke, not that the estate is clean");
        assertEquals(List.of(), offenders, () -> "these sources leave the lexer inside a comment or literal, so "
                + "every census over them scanned blanked text: " + offenders);
    }
}
