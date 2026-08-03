package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Keeps maintainer-only Tokei at the local publisher boundary, never in portable assembly. */
class SourceStatisticsReleaseBoundaryTest {

    private static final String DMTOOL_CHECK =
            "./scripts/update-code-statistics.sh --check --artifact dmtool";
    private static final String INTERPRETER_CHECK =
            "\"$source_root/scripts/update-code-statistics.sh\" --check --artifact interpreter";

    @Test
    void bothPublishersCheckFreshCountsBeforeCreatingPublicState() throws IOException {
        String dmtool = read("scripts/publish-release.sh");
        assertTrue(precedes(dmtool, DMTOOL_CHECK, "gh auth status"),
                "dmtool must check source statistics before reaching its first public precondition");
        assertFalse(precedes(dmtool.replace(DMTOOL_CHECK, ""), DMTOOL_CHECK, "gh auth status"),
                "the dmtool discriminator must fail when the check is removed");

        String interpreter = function(read("scripts/interpreter-release-publisher-core.sh"),
                "interpreter_release_qualify() {", "interpreter_release_stage() {");
        String receipt = "interpreter_release_write_qualification_receipt \"$receipt\"";
        assertTrue(precedes(interpreter, INTERPRETER_CHECK, receipt),
                "interpreter qualification must check source statistics before writing its receipt");
        assertFalse(precedes(interpreter.replace(INTERPRETER_CHECK, ""), INTERPRETER_CHECK, receipt),
                "the interpreter discriminator must fail when the check is removed");
    }

    @Test
    void portableAssemblersNeverRequireTheLocalTokeiUpdater() throws IOException {
        assertFalse(read("scripts/assemble-release-repo.sh").contains("update-code-statistics.sh"));
        assertFalse(read("scripts/assemble-interpreter-release-repo.sh").contains("update-code-statistics.sh"));
    }

    private static boolean precedes(String text, String first, String second) {
        int firstIndex = text.indexOf(first);
        int secondIndex = text.indexOf(second);
        return firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex;
    }

    private static String function(String text, String start, String end) {
        int startIndex = text.indexOf(start);
        int endIndex = text.indexOf(end, startIndex + start.length());
        return text.substring(startIndex, endIndex);
    }

    private static String read(String relative) throws IOException {
        for (String base : List.of(".", "..", "../..")) {
            Path candidate = Path.of(base, relative);
            if (Files.exists(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IllegalStateException(relative + " not found from " + Path.of("").toAbsolutePath());
    }
}
