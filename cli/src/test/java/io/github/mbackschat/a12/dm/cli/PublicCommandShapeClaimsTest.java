package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** The target-first {@code -m} form belongs to model-bound routes, not every dmtool route. */
class PublicCommandShapeClaimsTest {

    private static final Pattern UNIVERSAL_TARGET_FIRST = Pattern.compile(
            "(?i)\\b(every|all)\\s+(command|invocation|verb)s?\\b[^\\n]{0,100}"
                    + "(dmtool\\s+-m|use(s|d)?\\s+`?-m|take(s|n)?[^\\n]{0,20}`?-m)");
    private static final Pattern UNIVERSAL_MODEL_OPTION = Pattern.compile(
            "(?i)universal[^\\n]{0,30}`?-m`?[^\\n]{0,60}every\\s+other\\s+verb");
    private static final Pattern UNQUALIFIED_HEADING = Pattern.compile(
            "(?i)^#{1,6}\\s+.*invocation shape[^\\n]{0,60}dmtool\\s+-m");

    @Test
    void livePublicDocsDoNotUniversalizeTheModelBoundCommandShape() throws Exception {
        List<String> findings = new ArrayList<>();
        for (Path surface : PublicMarkdownSurfaces.live()) {
            List<String> lines = Files.readAllLines(surface);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (containsUniversalClaim(line)) {
                    findings.add(surface + ":" + (index + 1) + ": " + line.strip());
                }
            }
        }
        assertTrue(findings.isEmpty(), () -> "blanket target-first/-m command-shape claim(s):\n"
                + String.join("\n", findings));
    }

    @Test
    void matcherRejectsUniversalClaimsAndAllowsScopedRoutes() {
        for (String universal : List.of(
                "Every command is dmtool -m <model> <target> <op>.",
                "All invocations use -m before the verb.",
                "Export takes the universal `-m` like every other verb.",
                "### Invocation shape — dmtool -m <model> <target> <op>",
                "Model-bound operations use dmtool -m; every command is dmtool -m.",
                "Every command in this CLI is dmtool -m <model> <target> <op>.")) {
            assertTrue(containsUniversalClaim(universal), () -> "negative control escaped: " + universal);
        }
        for (String scoped : List.of(
                "Model-bound operations use dmtool -m <model> <target> <op>.",
                "Every command in this model-edit tour uses dmtool -m <model> <target> <op>.",
                "### Model-bound invocation shape — dmtool -m <model> <target> <op>")) {
            assertTrue(!containsUniversalClaim(scoped), () -> "scoped command shape was rejected: " + scoped);
        }
    }

    private static boolean containsUniversalClaim(String line) {
        return Arrays.stream(line.split("(?<=[.;])\\s+"))
                .anyMatch(PublicCommandShapeClaimsTest::isUniversalClause);
    }

    private static boolean isUniversalClause(String clause) {
        boolean scoped = clause.matches("(?i).*\\bevery\\s+command\\s+in\\s+(this|the)\\s+"
                + "(?:[\\w-]+\\s+){0,3}(tour|demo|walkthrough)\\b.*")
                || clause.matches("(?i).*\\bmodel-bound\\b.*");
        return !scoped && (UNIVERSAL_TARGET_FIRST.matcher(clause).find()
                || UNIVERSAL_MODEL_OPTION.matcher(clause).find()
                || UNQUALIFIED_HEADING.matcher(clause).find());
    }
}
