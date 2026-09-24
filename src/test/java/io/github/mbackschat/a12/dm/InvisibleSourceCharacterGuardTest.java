package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * No tracked Java or Kotlin source may hold a raw character a reader cannot tell from a space or from
 * nothing; such a character is spelled as a {@code \}{@code u} escape.
 *
 * <p><b>Why this exists, measured 2026-09-24.</b> A raw no-break space renders exactly like a space, so a
 * test meant to feed A12 a U+00A0 can hold a different character than it shows, still compile, still pass,
 * and measure another row than the one its name states. It happened twice in one session, in
 * {@code MemberBlanknessTest} and {@code A12Blank.kt}, where the whole point of each row is which space it
 * holds. The cause sits upstream of every tool: an agent's tool-call input decodes a backslash-u escape into
 * its character before the shell command or the file write sees it, while a doubled backslash passes through
 * as two (TESTING-SPEC). A byte scan saw both; neither the compiler nor a read of the diff did.
 *
 * <p><b>The set is derived from Unicode categories, not listed</b>, so a character nobody thought of is
 * covered: every space separator except U+0020, the line and paragraph separators, every format character
 * (the zero-width and bidirectional controls among them), and every control character except tab, line feed
 * and carriage return. Markdown is out of scope on purpose: a typographic narrow no-break space in a figure
 * is legitimate prose there, and no escape exists to spell it.
 */
class InvisibleSourceCharacterGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void noSourceHoldsARawInvisibleCharacter() {
        List<String> found = new ArrayList<>();
        for (Path source : CHECKOUT.sources()) {
            found.addAll(invisibleIn(CHECKOUT.relative(source), read(source)));
        }
        assertEquals(List.of(), found,
                "a source holds a character that reads as a space or as nothing, so what it contains is not "
                        + "what a reader sees. Spell it as a \\u escape (Java and Kotlin both take one in a "
                        + "string or character literal).");
    }

    /**
     * The detector against characters written as escapes here, so this file stays clean while testing it.
     *
     * <p>The negative rows are the ones a looser rule would flag: the permitted controls, visible non-ASCII
     * letters and punctuation this estate writes routinely, and an escape spelled as text.
     */
    @Test
    void theDetectorSeesWhatAReaderCannot() {
        assertEquals(List.of(
                        "probe:1 U+00A0 NO-BREAK SPACE",
                        "probe:1 U+200B ZERO WIDTH SPACE",
                        "probe:1 U+202E RIGHT-TO-LEFT OVERRIDE",
                        "probe:2 U+000B LINE TABULATION",
                        "probe:2 U+2028 LINE SEPARATOR",
                        "probe:2 U+001B ESCAPE",
                        "probe:3 U+3000 IDEOGRAPHIC SPACE",
                        "probe:3 U+FEFF ZERO WIDTH NO-BREAK SPACE"),
                invisibleIn("probe", "a\u00a0b\u200bc\u202ed\nx\u000by\u2028z\u001b\r\np\u3000q\ufeff"));
        assertEquals(List.of(), invisibleIn("probe", "tab\there\r\nÄ — ü … \\u00a0 → ß"));
    }

    /** …and the scan reaches the estate, so an empty result means clean rather than a lost tree. */
    @Test
    void theScanReachesTheEstatesSources() {
        int sources = CHECKOUT.sources().size();
        assertTrue(sources >= 1000, "the scan reached only " + sources + " Java and Kotlin sources");
    }

    /** Each raw invisible character in {@code text}, as {@code name:line U+XXXX UNICODE NAME}. */
    private static List<String> invisibleIn(String name, String text) {
        List<String> found = new ArrayList<>();
        int line = 1;
        for (int index = 0; index < text.length(); ) {
            int codePoint = text.codePointAt(index);
            if (isInvisible(codePoint)) {
                found.add(name + ":" + line + " U+" + String.format(Locale.ROOT, "%04X", codePoint) + " "
                        + Character.getName(codePoint));
            }
            if (codePoint == '\n') {
                line++;
            }
            index += Character.charCount(codePoint);
        }
        return found;
    }

    private static boolean isInvisible(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.SPACE_SEPARATOR -> codePoint != ' ';
            case Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.FORMAT -> true;
            case Character.CONTROL -> codePoint != '\t' && codePoint != '\n' && codePoint != '\r';
            default -> false;
        };
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException unreadable) {
            throw new UncheckedIOException("cannot read " + path, unreadable);
        }
    }
}
