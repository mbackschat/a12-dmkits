package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Every authored {@code .ts} in a consumer project must be type-checked by a config that {@code check} runs.
 *
 * <p>The sibling guards refuse authored JavaScript ({@code verifyConsistencyBrowserTypeScriptSources} and its
 * interpreter twin), which asserts what the sources ARE and says nothing about whether the compiler ever sees
 * them. The gap was live: {@code consistency/consumer-tests/browser/vite.config.ts} — the file deciding which
 * HTML entries get emitted — sat outside both of that project's {@code include} lists, so a typo in
 * {@code rollupOptions.input} would have dropped the cold page and surfaced as a 404 in the middle of a
 * measurement rather than as a build failure. Found by a cold review on 2026-09-17.
 *
 * <p><b>Coverage is judged against the configs {@code check} actually invokes</b>, not against the ones on
 * disk. A {@code tsconfig} nobody runs buys exactly nothing, and that is a cheaper mistake to make than the
 * original one — adding the file and forgetting the script line leaves every symptom identical.
 */
class ConsumerTypeScriptCoverageGuardTest {

    /** Where a consumer project's authored TypeScript lives, relative to the repository root. */
    private static final List<String> CONSUMER_PROJECTS = List.of(
            "consistency/consumer-tests/browser",
            "interpreter/consumer-tests/browser");

    /** Generated or installed trees: their content is output, and none of it is authored here. */
    private static final List<String> NOT_AUTHORED = List.of("node_modules", "build", "dist", ".vite");

    @Test
    void everyAuthoredTypeScriptFileIsCoveredByAConfigThatCheckRuns() throws IOException {
        var uncovered = new TreeSet<String>();
        for (String project : CONSUMER_PROJECTS) {
            Path root = repoDirectory(project);
            var configs = configsInvokedByCheck(root);
            assertTrue(!configs.isEmpty(),
                    project + "'s package.json `check` script invokes no tsconfig, so nothing type-checks it");

            var patterns = new ArrayList<Pattern>();
            for (Path config : configs) {
                var includes = includePatterns(config);
                assertTrue(!includes.isEmpty(),
                        config + " declares no `include`, so what it covers is implicit and unreviewable");
                patterns.addAll(includes);
            }

            for (Path source : authoredTypeScript(root)) {
                String relative = root.relativize(source).toString();
                if (patterns.stream().noneMatch(p -> p.matcher(relative).matches())) {
                    uncovered.add(project + "/" + relative);
                }
            }
        }

        assertEquals(Set.of(), uncovered,
                "these authored TypeScript files are type-checked by no config that `check` runs, so a type "
                        + "error in them reaches a consumer at runtime: add them to an existing `include`, or "
                        + "give them a config and add that config to the `check` script — both halves, since a "
                        + "tsconfig nobody invokes changes nothing");
    }

    /**
     * The configs a project's {@code check} script actually runs.
     *
     * A bare {@code tsc} means {@code tsconfig.json}; {@code -p <file>} names one explicitly. Anything else in
     * the script is ignored, because this guard's subject is type-checking rather than the whole pipeline.
     */
    private static List<Path> configsInvokedByCheck(Path root) throws IOException {
        String manifest = Files.readString(root.resolve("package.json"));
        Matcher script = Pattern.compile("\"check\"\\s*:\\s*\"([^\"]*)\"").matcher(manifest);
        assertTrue(script.find(), root + "/package.json declares no `check` script");
        String command = script.group(1);

        var configs = new ArrayList<Path>();
        var explicit = Pattern.compile("-p\\s+(\\S+)").matcher(command);
        while (explicit.find()) {
            configs.add(root.resolve(explicit.group(1)));
        }
        // A bare `tsc` — one not followed by `-p` before the next `&&` — takes the default config.
        for (String stage : command.split("&&")) {
            String trimmed = stage.strip();
            if (trimmed.startsWith("tsc") && !trimmed.contains("-p ")) {
                configs.add(root.resolve("tsconfig.json"));
            }
        }
        return configs;
    }

    /**
     * The {@code include} globs of one config, as regexes over its project-relative paths.
     *
     * TypeScript's {@code **} crosses directory boundaries while {@code *} does not, and a pattern with no
     * wildcard names one file exactly — the three cases these projects use.
     */
    private static List<Pattern> includePatterns(Path config) throws IOException {
        // Comments are legal in a tsconfig and these carry them, so strip line comments before matching.
        String text = Files.readString(config).replaceAll("(?m)^\\s*//.*$", "");
        Matcher block = Pattern.compile("\"include\"\\s*:\\s*\\[([^]]*)]").matcher(text);
        if (!block.find()) {
            return List.of();
        }
        var patterns = new ArrayList<Pattern>();
        var globs = Pattern.compile("\"([^\"]+)\"").matcher(block.group(1));
        while (globs.find()) {
            patterns.add(Pattern.compile(globToRegex(globs.group(1))));
        }
        return patterns;
    }

    private static String globToRegex(String glob) {
        var regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*' && i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                // `**/` may match nothing at all, which is what lets `src/**/*.ts` cover `src/main.ts`.
                if (i + 2 < glob.length() && glob.charAt(i + 2) == '/') {
                    regex.append("(?:.*/)?");
                    i += 2;
                } else {
                    regex.append(".*");
                    i++;
                }
            } else if (c == '*') {
                regex.append("[^/]*");
            } else if (c == '?') {
                regex.append("[^/]");
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }

    private static List<Path> authoredTypeScript(Path root) throws IOException {
        try (Stream<Path> tree = Files.walk(root)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".ts"))
                    .filter(path -> NOT_AUTHORED.stream()
                            .noneMatch(excluded -> root.relativize(path).startsWith(excluded)))
                    .sorted()
                    .toList();
        }
    }

    private static Path repoDirectory(String relative) {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            Path resolved = candidate.resolve(relative);
            if (Files.isDirectory(resolved)) {
                return resolved;
            }
        }
        throw new IllegalStateException("no " + relative + " above " + here);
    }
}
