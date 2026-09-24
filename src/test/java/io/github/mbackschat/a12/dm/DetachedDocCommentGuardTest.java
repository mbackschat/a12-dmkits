package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * No doc comment may be separated from the declaration it documents.
 *
 * <p>A doc comment belongs to the declaration that follows it, so javadoc, Dokka and every IDE drop one that
 * something else stands in front of — silently: it compiles, it reads as documented in the source, and neither
 * JDK 21's javac nor the Kotlin compiler has a lint for it. Two shapes were measured, both left by edits that
 * inserted text just below a comment instead of just above it. An import (2026-09-24: fourteen kit sources, all
 * one mechanical migration that put the {@code core.KernelCode} and {@code Refusals} imports below the class
 * comment). And a second doc comment (2026-09-24: 61 sites across all eight modules, among them the public
 * {@code DmInterpreter} and {@code Document} class docs, each where a new member was inserted between an
 * existing comment and its member). The shapes recur wherever members are added, which is why this reads every
 * tracked source rather than the files that carried them.
 */
class DetachedDocCommentGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void noDocCommentIsSeparatedFromItsTypeByAnImport() {
        List<Path> sources = scannedSources();
        List<String> detached = sources.stream()
                .filter(source -> importFollowsDocComment(read(source)))
                .map(CHECKOUT::relative)
                .toList();
        assertEquals(List.of(), detached, "a doc comment sits above an import, so it documents no declaration;"
                + " move the import up into the import block");
    }

    @Test
    void noDocCommentIsFollowedByAnotherDocComment() {
        List<String> detached = new ArrayList<>();
        for (Path source : scannedSources()) {
            for (int line : docCommentsFollowedByADocComment(read(source))) {
                detached.add(CHECKOUT.relative(source) + ":" + line);
            }
        }
        assertEquals(List.of(), detached, "a doc comment is followed directly by another, so it documents no"
                + " declaration; move it to the member it describes, or delete it if its member is gone");
    }

    /** The detector's own controls: the measured shape is found, and a comment on its type is left alone. */
    @Test
    void theImportDetectorFindsTheMigratedShapeAndSparesAnAttachedComment() {
        assertTrue(importFollowsDocComment("""
                package p;

                import java.util.List;

                /** The type. */
                import p.core.KernelCode;

                public record R(List<String> names) {}
                """));
        assertFalse(importFollowsDocComment("""
                package p;

                import java.util.List;
                import p.core.KernelCode;

                /** The type. */
                @Deprecated
                public record R(List<String> names) {
                    /** A member, after which a text block may hold a line that starts like an import. */
                    static final String SOURCE = \"""
                import q.Other;
                \""";
                }
                """));
    }

    /**
     * The detector's own controls. The positive rows are the two layouts the sweep found — a one-line comment
     * and a block comment above a newly inserted member's own. The negative rows are what a text scan would
     * mistake for them: comments separated by a declaration, and doc-comment TEXT inside a string or a text
     * block, which is data rather than a comment. The fixtures build that text by concatenation so this file does
     * not carry the shape it looks for.
     */
    @Test
    void theAdjacencyDetectorFindsBothLayoutsAndSparesCommentTextInStrings() {
        String open = "/" + "**";
        assertEquals(List.of(2), docCommentsFollowedByADocComment(
                "class C {\n    " + open + " Orphaned. */\n    " + open + " The member's own. */\n    int x;\n}\n"));
        assertEquals(List.of(1), docCommentsFollowedByADocComment(
                open + "\n * Orphaned.\n */\n" + open + "\n * The type's own.\n */\nclass C {}\n"));

        assertEquals(List.of(), docCommentsFollowedByADocComment(
                "class C {\n    " + open + " One. */\n    int x;\n    " + open + " Two. */\n    int y;\n}\n"));
        assertEquals(List.of(), docCommentsFollowedByADocComment(
                "class C {\n    String s = \"" + open + " a */ " + open + " b */\";\n    char q = '\"';\n}\n"));
        assertEquals(List.of(), docCommentsFollowedByADocComment(
                "class C {\n    String t = \"\"\"\n        " + open + " a */\n        " + open + " b */\n"
                        + "        \"\"\";\n}\n"));
        assertEquals(List.of(), docCommentsFollowedByADocComment(
                "class C {\n    /**/\n    " + open + " The member's own. */\n    int x;\n}\n"));
    }

    private static List<Path> scannedSources() {
        List<Path> sources = CHECKOUT.sources();
        assertTrue(sources.size() > 1000, "scanned only " + sources.size() + " sources, so the enumeration broke");
        return sources;
    }

    /**
     * Whether an import line follows a doc comment in the file's header, which ends at the first line that is
     * none of a package or import statement, a comment, or blank: the type declaration or its annotation.
     */
    static boolean importFollowsDocComment(String text) {
        boolean docSeen = false;
        for (String line : text.lines().toList()) {
            String trimmed = line.strip();
            if (line.startsWith("import ")) {
                if (docSeen) {
                    return true;
                }
            } else if (trimmed.startsWith("/**")) {
                docSeen = true;
            } else if (!(trimmed.isEmpty() || line.startsWith("package ") || trimmed.startsWith("*")
                    || trimmed.startsWith("/*") || trimmed.startsWith("//"))) {
                return false;
            }
        }
        return false;
    }

    /**
     * The 1-based line of every doc comment whose next non-blank text is another doc comment.
     *
     * <p>Lexes just far enough to know what is a comment: line comments, block comments, string and character
     * literals, and Java text blocks / Kotlin raw strings are stepped over whole, so comment-shaped text inside
     * data is never read as a comment. {@code /**}{@code /} is an empty block comment, not a doc comment.
     */
    static List<Integer> docCommentsFollowedByADocComment(String text) {
        List<Integer> detached = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            if (text.startsWith("//", i)) {
                i = endOf(text, "\n", i + 2);
            } else if (text.startsWith("/**", i) && !text.startsWith("/**/", i)) {
                int end = endOf(text, "*/", i + 3);
                int next = end;
                while (next < n && Character.isWhitespace(text.charAt(next))) {
                    next++;
                }
                if (text.startsWith("/**", next) && !text.startsWith("/**/", next)) {
                    detached.add(lineOf(text, i));
                }
                i = end;
            } else if (text.startsWith("/*", i)) {
                i = endOf(text, "*/", i + 2);
            } else if (text.startsWith("\"\"\"", i)) {
                i = endOf(text, "\"\"\"", i + 3);
            } else if (text.charAt(i) == '"' || text.charAt(i) == '\'') {
                i = endOfLiteral(text, i);
            } else {
                i++;
            }
        }
        return detached;
    }

    /** The index just past the first {@code terminator} at or after {@code from}, or the end of the text. */
    private static int endOf(String text, String terminator, int from) {
        int at = text.indexOf(terminator, from);
        return at < 0 ? text.length() : at + terminator.length();
    }

    /** The index just past a one-line string or character literal opened at {@code start}. */
    private static int endOfLiteral(String text, int start) {
        char quote = text.charAt(start);
        for (int i = start + 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == quote || c == '\n') {
                return i + 1;
            }
        }
        return text.length();
    }

    private static int lineOf(String text, int index) {
        return (int) text.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
