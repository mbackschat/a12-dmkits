package io.github.mbackschat.a12.dm;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Class guard for <b>path containment</b>: production code asks whether one model entity path lies inside
 * another through {@link io.github.mbackschat.a12.dm.core.EntityPaths}, never by hand.
 *
 * <p><b>Why a census rather than a fix.</b> The hand-spelled relation shipped as a defect twice in one week, in
 * opposite directions — {@code startsWith(ancestor)} crossing a segment boundary ({@code CG16}) and
 * {@code startsWith(ancestor + "/")} excluding the subtree root from its own subtree ({@code CG17}) — while
 * fifteen more copies of the same two lines sat across five modules, each individually correct and each one
 * edit away from being wrong. Fixing the two reported sites would have left the class live; that is the
 * whack-a-mole this repo's root-cause rule exists to prevent, so the guard covers the siblings rather than the
 * reported case.
 *
 * <p>The scanner detects the spelling that is mechanically recognizable — a {@code startsWith} whose argument
 * appends the path separator. Its blind spot is the naive {@code startsWith(ancestor)} with no separator, which
 * is indistinguishable from any other string-prefix test; that half is covered by {@code EntityPathsTest}
 * owning the relation and by this guard leaving no in-repo copy for it to be introduced beside.
 */
class PathContainmentCensusTest {

    private static final List<String> SOURCE_ROOTS = List.of(
            "core/src/main",
            "modelkit/src/main",
            "rulekit/src/main",
            "edit/src/main",
            "schemakit/src/main",
            "cli/src/main",
            "adapter/src/main",
            "interpreter/src/commonMain",
            "interpreter/src/jvmMain",
            "interpreter/src/jsMain");

    /** {@code x.startsWith(anything + "/")} — the separator-appending spelling of "lies below". */
    private static final Pattern SEPARATOR_PREFIX = Pattern.compile(
            "\\.startsWith\\(\\s*[^;()]*\\+\\s*\"/\"\\s*\\)");

    private static final String CANONICAL =
            "the canonical implementation itself — the one place the relation is spelled out, and the reason "
                    + "every other module can ask for it by name.";

    private static final Map<String, String> ALLOWLIST = Map.ofEntries(
            entry("core/src/main/java/io/github/mbackschat/a12/dm/core/EntityPaths.java"
                    + "::separator-prefix#1", CANONICAL));

    @Test
    void everyPathContainmentTestGoesThroughEntityPaths() throws IOException {
        Map<String, Path> actual = scan();

        List<String> missing = actual.keySet().stream()
                .filter(site -> !ALLOWLIST.containsKey(site))
                .toList();
        List<String> stale = ALLOWLIST.keySet().stream()
                .filter(site -> !actual.containsKey(site))
                .toList();

        assertEquals(List.of(), missing,
                () -> "hand-spelled path containment. Use EntityPaths.isInside (the subtree root counts as "
                        + "inside) or EntityPaths.isStrictlyInside (it does not) — picking between those two is "
                        + "the whole defect class, which is why they are named:\n  " + String.join("\n  ", missing));
        assertEquals(List.of(), stale,
                () -> "stale allowlist row(s) — the site moved or went away, so remove the row:\n  "
                        + String.join("\n  ", stale));
    }

    /**
     * Positive control. A regex that silently stops matching turns this guard into a green that proves nothing,
     * and the allowlist above is small enough that "no sites found" would otherwise look like success.
     */
    @Test
    void theScannerStillFindsTheCanonicalImplementation() throws IOException {
        assertTrue(scan().containsKey("core/src/main/java/io/github/mbackschat/a12/dm/core/EntityPaths.java"
                        + "::separator-prefix#1"),
                "the scanner found no containment spelling at all, including EntityPaths' own — it has stopped "
                        + "matching, and every other assertion here is vacuous until it does again");
    }

    private static Map<String, Path> scan() throws IOException {
        Path root = repoRoot();
        Map<String, Path> sites = new LinkedHashMap<>();
        for (String sourceRoot : SOURCE_ROOTS) {
            try (Stream<Path> files = Files.walk(root.resolve(sourceRoot))) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(PathContainmentCensusTest::isJavaOrKotlin)
                        .sorted()
                        .toList()) {
                    String source = maskComments(Files.readString(file));
                    Matcher matcher = SEPARATOR_PREFIX.matcher(source);
                    int ordinal = 0;
                    while (matcher.find()) {
                        ordinal++;
                        String relative = root.relativize(file).toString().replace('\\', '/');
                        sites.put(relative + "::separator-prefix#" + ordinal, file);
                    }
                }
            }
        }
        return sites;
    }

    private static boolean isJavaOrKotlin(Path file) {
        String name = file.getFileName().toString();
        return name.endsWith(".java") || name.endsWith(".kt");
    }

    /**
     * Blank out comments only, keeping string literals — the opposite choice from
     * {@code A12JsonConstructionGuardTest}, and deliberate: the pattern this guard hunts <em>contains</em> a
     * string literal, so masking literals would blind it entirely. Comments must still go, because
     * {@link io.github.mbackschat.a12.dm.core.EntityPaths}' own javadoc quotes both wrong spellings in order to
     * explain them, and a documentation example is not a call site.
     */
    private static String maskComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        boolean lineComment = false;
        boolean blockComment = false;
        boolean string = false;
        boolean escaped = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';

            if (lineComment) {
                if (current == '\n') {
                    lineComment = false;
                    out.append(current);
                } else {
                    out.append(' ');
                }
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    out.append("  ");
                    index++;
                } else {
                    out.append(current == '\n' ? '\n' : ' ');
                }
                continue;
            }
            if (string) {
                out.append(current);
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == '"') {
                    string = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                lineComment = true;
                out.append("  ");
                index++;
                continue;
            }
            if (current == '/' && next == '*') {
                blockComment = true;
                out.append("  ");
                index++;
                continue;
            }
            if (current == '"') {
                string = true;
            }
            out.append(current);
        }
        return out.toString();
    }

    private static Path repoRoot() {
        Path directory = Path.of("").toAbsolutePath().normalize();
        while (directory != null) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))
                    && Files.isRegularFile(directory.resolve("CLAUDE.md"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("repository root not found from " + Path.of("").toAbsolutePath());
    }
}
