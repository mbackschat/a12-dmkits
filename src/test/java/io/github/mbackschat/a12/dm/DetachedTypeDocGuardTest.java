package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * No type's doc comment may sit above an import, because it then documents nothing.
 *
 * <p>A doc comment belongs to the declaration that follows it, and an import is not one, so javadoc and every
 * IDE drop a class comment that an import separates from its type — silently: it compiles, it reads as
 * documented in the source, and JDK 21's javac has no lint for it. Measured 2026-09-24: fourteen kit sources
 * carried it, all the same shape, the {@code core.KernelCode} and {@code Refusals} imports inserted just above
 * the type declaration and below its comment by one mechanical migration. A second bulk import edit would
 * repeat it, which is why this reads every tracked source rather than the fourteen.
 */
class DetachedTypeDocGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    @Test
    void noDocCommentIsSeparatedFromItsTypeByAnImport() {
        List<Path> sources = CHECKOUT.sources();
        assertTrue(sources.size() > 1000, "scanned only " + sources.size() + " sources, so the enumeration broke");
        List<String> detached = sources.stream()
                .filter(source -> importFollowsDocComment(read(source)))
                .map(CHECKOUT::relative)
                .toList();
        assertEquals(List.of(), detached, "a doc comment sits above an import, so it documents no declaration;"
                + " move the import up into the import block");
    }

    /** The detector's own controls: the measured shape is found, and a comment on its type is left alone. */
    @Test
    void theDetectorFindsTheMigratedShapeAndSparesAnAttachedComment() {
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

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
