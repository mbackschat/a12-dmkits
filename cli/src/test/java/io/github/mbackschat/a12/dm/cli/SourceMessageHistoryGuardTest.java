package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Refuses a finding id, ticket id, kernel version or "used to" clause in any main-source string literal, the class
 * the per-surface prose guards cannot reach: an error message, a refusal's corrective, a checker coverage note. Each
 * of those reaches an agent that has only the binary and cannot follow the pointer. On 2026-11-06 the diagnostic
 * sources alone carried 74 of them, and six further files leaked the same class through ad-hoc messages.
 *
 * <p>Comments are exempt and should keep citing their evidence. The scan therefore tokenizes each file once, so a
 * {@code //} inside a string (a URL) cannot be mistaken for a comment and a quote inside a comment cannot open a
 * string. Its denominator is {@code git ls-files} over the main source sets, which no Gradle input can express; the
 * {@code referentGuardTest} lane declares a superset tree for that reason.
 */
class SourceMessageHistoryGuardTest {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();

    @Test
    void noMainSourceStringLiteralCarriesHistory() throws IOException, InterruptedException {
        List<Path> files = mainSources();
        assertTrue(files.size() > 500, "the main-source scan reached only " + files.size() + " files");
        List<String> hits = new ArrayList<>();
        for (Path file : files) {
            for (String literal : stringLiterals(Files.readString(ROOT.resolve(file)))) {
                AgentProse.proseDefects(file.toString(), literal).stream()
                        .filter(defect -> defect.contains(": history ")).forEach(hits::add);
            }
        }
        assertTrue(hits.isEmpty(), () -> AgentProse.proseFailure(hits));
    }

    @Test
    void theTokenizerReadsStringsAndSkipsComments() {
        String source = """
                // KF1 in a line comment
                /* KF2 in a block comment with a "quote" */
                val url = "https://example.org/a"; // KF3
                String s = "refused (KF4)" + 'x' + "next";
                val raw = \"\"\"multi "quoted" KF5\"\"\"
                """;
        assertEquals(List.of("https://example.org/a", "refused (KF4)", "next", "multi \"quoted\" KF5"),
                stringLiterals(source));
    }

    /** The contents of every string literal in {@code source}, Kotlin raw strings included, comments skipped. */
    static List<String> stringLiterals(String source) {
        List<String> literals = new ArrayList<>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            if (source.startsWith("//", i)) {
                i = indexOrEnd(source, "\n", i);
            } else if (source.startsWith("/*", i)) {
                i = indexOrEnd(source, "*/", i + 2) + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                int end = indexOrEnd(source, "\"\"\"", i + 3);
                literals.add(source.substring(i + 3, Math.min(end, n)));
                i = end + 3;
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                char quote = source.charAt(i);
                int j = i + 1;
                StringBuilder text = new StringBuilder();
                while (j < n && source.charAt(j) != quote && source.charAt(j) != '\n') {
                    if (source.charAt(j) == '\\' && j + 1 < n) {
                        text.append(source.charAt(j + 1));
                        j += 2;
                    } else {
                        text.append(source.charAt(j++));
                    }
                }
                if (quote == '"') {
                    literals.add(text.toString());
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return literals;
    }

    private static int indexOrEnd(String text, String needle, int from) {
        int at = text.indexOf(needle, from);
        return at < 0 ? text.length() : at;
    }

    /** Tracked Java and Kotlin files of every main source set, relative to the checkout root. */
    private static List<Path> mainSources() throws IOException, InterruptedException {
        Process git = new ProcessBuilder("git", "ls-files", "-z", "--",
                "*/src/main/*.java", "*/src/main/*.kt", "*/src/commonMain/*.kt", "*/src/jvmMain/*.kt",
                "*/src/jsMain/*.kt").directory(ROOT.toFile()).redirectErrorStream(true).start();
        String out = new String(git.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, git.waitFor(), out);
        List<Path> files = new ArrayList<>();
        for (String name : out.split("\0")) {
            if (!name.isBlank() && Files.exists(ROOT.resolve(name))) {
                files.add(Path.of(name));
            }
        }
        return files;
    }
}
