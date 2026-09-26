package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Guards the shell-portability contracts that every committed script must satisfy.
 *
 * <p><strong>Why this is a guard and not advice.</strong> CI is Linux and every development machine here is
 * macOS, so a one-dialect spelling is green locally and dead — or red — on CI. That is not hypothetical: the
 * doc-guard staleness check in {@code .githooks/pre-commit} read mtimes as
 * {@code stat -f %m F || stat -c %Y F || echo 0} and therefore never fired on any GNU-coreutils machine from
 * the day it landed, because the wrong dialect does not fail cleanly. Measured 2026-08-22 on GNU coreutils
 * 9.11 and macOS BSD {@code stat}, same file:
 *
 * <ul>
 *   <li>{@code stat -c %Y F} — GNU prints the mtime; BSD answers {@code illegal option -- c}, exit 1.</li>
 *   <li>{@code stat -f %m F} — BSD prints the mtime; GNU reads {@code %m} as a FILE, prints F's
 *       <em>filesystem</em> block on <em>stdout</em> and exits 1, so a {@code ||} fallback CONCATENATES that
 *       block with the real number instead of replacing it. Every later {@code [ "$t" -gt … ]} then dies as a
 *       non-integer, and a test on the left of a {@code &&} is exempt from {@code set -e}, so the failure is
 *       silent and the check reads every timestamp as 0.</li>
 * </ul>
 *
 * <p><strong>The rule.</strong> Direct {@code stat} uses must pair the two dialects inside the same structural
 * shell scope — one command, function, or control block. The repaired hook probes one, validates the reading,
 * and refuses when neither answers. Keeping the exemption local matters: one correct function must not clear an
 * unrelated GNU-only call elsewhere in the file. Alternatively, take the timestamp somewhere dialect-free —
 * {@code find -newer} for a comparison, or a cross-platform runtime filesystem API once the stateful logic has
 * moved out of shell (what the typed {@code scripts/test-evidence.sh} implementation does).
 *
 * <p>The other contract is POSIX shell for every new entrypoint. The exact legacy Bash set is a migration
 * ratchet: an added non-POSIX script fails, and a migrated path fails until its exemption is deleted. While that
 * set is non-empty, its scripts retain the stock-macOS Bash 3.2 floor. The scanner rejects measured constructs
 * that Bash 3.2 parses but cannot execute correctly; {@code /bin/bash -n} guards its syntax, while Dash parses
 * every POSIX entrypoint. Java/Kotlin behavior-test launchers must select the applicable absolute oracle path.
 *
 * <p><strong>Denominator + controls.</strong> {@code git ls-files} supplies the paths, so a script added
 * tomorrow is scanned without anyone declaring it; conventional shell extensions and every shell shebang are
 * recognized. One lexical pass joins continued commands and excludes inline comments and heredoc bodies, since
 * the containing shell does not parse those bodies as shell syntax at that source location. The
 * scanners carry positive and negative planted controls, and the tree scan asserts a floor on scripts read, so
 * "no findings" cannot mean "no files".
 */
class ShellPortabilityGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    private static final int TRACKED_SHELL_SCRIPT_FLOOR = 70;
    private static final Set<String> LEGACY_BASH_SCRIPTS = Set.of(
            ".claude/hooks/guard-long-run-antipatterns.sh",
            ".githooks/pre-commit",
            ".githooks/pre-push",
            "eval/prep-sandbox.sh",
            "probe/audit-probe.sh",
            "probe/digest-probe.sh",
            "probe/prep-probe.sh",
            "scripts/assemble-interpreter-release-repo.sh",
            "scripts/assemble-release-repo.sh",
            "scripts/bump-version.sh",
            "scripts/capture-native-config.sh",
            "scripts/check-interpreter-release-repo.sh",
            "scripts/check-native-metadata-removals.sh",
            "scripts/check-release-gaps.sh",
            "scripts/codex-plugin-activation-smoke.sh",
            "scripts/contract-consumers.sh",
            "scripts/gap.sh",
            "scripts/gen-native-reflect-metadata.sh",
            "scripts/interpreter-delivery-footprint.sh",
            "scripts/interpreter-footprint.sh",
            "scripts/interpreter-performance.sh",
            "scripts/interpreter-release-assets.sh",
            "scripts/interpreter-release-github.sh",
            "scripts/interpreter-release-publisher-core.sh",
            "scripts/interpreter-release-rehearsal-host.sh",
            "scripts/native-battery.sh",
            "scripts/native-gate.sh",
            "scripts/native-no-java-smoke.sh",
            "scripts/obligations.sh",
            "scripts/perf-anchor.sh",
            "scripts/perf-history.sh",
            "scripts/perf-keys.sh",
            "scripts/perf-report.sh",
            "scripts/perf-trend.sh",
            "scripts/perf.sh",
            "scripts/prepare-dmtool-source.sh",
            "scripts/publish-interpreter-release.sh",
            "scripts/publish-release.sh",
            "scripts/rehearse-interpreter-release.sh",
            "scripts/release-git-identity.sh",
            "scripts/release-mirror-common.sh",
            "scripts/release-perf-gate.sh",
            "scripts/selftest-dmtool-launcher.sh",
            "scripts/selftest-ensure-dmtool.sh",
            "scripts/selftest-obligations.sh",
            "scripts/selftest-precommit-doc-guards.sh",
            "scripts/selftest-prepare-dmtool-source.sh",
            "scripts/selftest-prepush-ci.sh",
            "scripts/selftest-release-perf-gate.sh",
            "scripts/selftest-test-evidence.sh",
            "scripts/smoke-interpreter-public-release.sh",
            "scripts/smoke-mcp-endpoint.sh",
            "scripts/update-code-statistics.sh",
            "scripts/verify-build-cache.sh");
    private static final Pattern PATH_SELECTED_BASH =
            Pattern.compile("(?:new\\s+)?ProcessBuilder\\s*\\(\\s*\"bash\"");

    private enum StatDialect {
        GNU(Pattern.compile("\\bstat\\s+-c\\b"), "stat -c (GNU only — BSD: illegal option)"),
        BSD(Pattern.compile("\\bstat\\s+-f\\b"), "stat -f (BSD only — GNU reads the format as a file)");

        private final Pattern spelling;
        private final String description;

        StatDialect(Pattern spelling, String description) {
            this.spelling = spelling;
            this.description = description;
        }
    }

    private record Finding(String file, int line, StatDialect dialect, String source) {
        @Override
        public String toString() {
            return file + ":" + line + "  " + dialect.description + "\n      " + source.strip();
        }
    }

    private enum Bash4Construct {
        BULK_READ(Pattern.compile("\\b(?:mapfile|readarray)\\b"), "mapfile/readarray (Bash 4+ only)"),
        ASSOCIATIVE_ARRAY(
                Pattern.compile("\\b(?:declare|local|readonly|typeset)\\b(?:\\s+-[A-Za-z]+)*\\s+-[A-Za-z]*A[A-Za-z]*(?:\\s|$)"),
                "associative-array declaration (Bash 4+ only)"),
        GLOBAL_DECLARATION(
                Pattern.compile("\\b(?:declare|typeset)\\b(?:\\s+-[A-Za-z]+)*\\s+-[A-Za-z]*g[A-Za-z]*(?:\\s|$)"),
                "global declaration option -g (Bash 4+ only)"),
        NAMEREF_DECLARATION(
                Pattern.compile("\\b(?:declare|local|typeset)\\b(?:\\s+-[A-Za-z]+)*\\s+-[A-Za-z]*n[A-Za-z]*(?:\\s|$)"),
                "nameref declaration option -n (Bash 4+ only)"),
        GLOBSTAR_OPTION(Pattern.compile("\\bshopt\\b[^;\\n]*\\bglobstar\\b"), "globstar shell option (Bash 4+ only)"),
        WAIT_NEXT(
                Pattern.compile("\\bwait\\b(?:\\s+-[A-Za-z]+)*\\s+-[A-Za-z]*n[A-Za-z]*(?:\\s|$)"),
                "wait -n (Bash 4+ only)"),
        NEGATIVE_ARRAY_INDEX(
                Pattern.compile("\\$\\{[^}\\n]*\\[-[0-9]+\\][^}\\n]*}"),
                "negative array index (Bash 4+ only)"),
        FRACTIONAL_READ_TIMEOUT(
                Pattern.compile("\\bread\\b[^;\\n]*\\s-t\\s+(?:[0-9]+\\.[0-9]*|[0-9]*\\.[0-9]+)(?:\\s|$)"),
                "fractional read timeout (Bash 4+ only)");

        private final Pattern spelling;
        private final String description;

        Bash4Construct(Pattern spelling, String description) {
            this.spelling = spelling;
            this.description = description;
        }
    }

    private record Bash4Finding(String file, int line, Bash4Construct construct, String source) {
        @Override
        public String toString() {
            return file + ":" + line + "  " + construct.description + "\n      " + source.strip();
        }
    }

    private record ShellLine(int number, String source) {}

    private record HereDocument(String delimiter, boolean stripsLeadingTabs) {}

    private enum ScopeKind {
        FUNCTION,
        IF,
        CASE,
        LOOP,
        GROUP
    }

    private record ShellScope(int id, ScopeKind kind) {}

    @Test
    void noTrackedShellScriptReadsATimestampInOneDialectOnly() throws Exception {
        List<String> scripts = trackedShellScripts();
        assertTrue(scripts.size() >= TRACKED_SHELL_SCRIPT_FLOOR,
                "only " + scripts.size() + " shell scripts found — the scanner lost its denominator, so a "
                        + "green here would mean nothing");

        var findings = new ArrayList<Finding>();
        for (String relative : scripts) {
            findings.addAll(scan(relative, Files.readString(CHECKOUT.resolve(relative), StandardCharsets.UTF_8)));
        }

        assertEquals(List.of(), findings, () -> """
                A shell script reads a timestamp in one `stat` dialect only. CI is Linux, this machine is \
                macOS, so the other dialect is never exercised where you can see it.

                %s

                Two ways out: probe once and validate the reading, the way `.githooks/pre-commit` does (the \
                paired spellings must be in the same command scope), or avoid `stat` entirely — `find -newer` for a \
                comparison, `python3 -c 'os.stat(...)'` for a number.
                Measurements: docs/BUILD-ENVIRONMENT.md "`stat` has two dialects".""".formatted(
                findings.stream().map(Finding::toString).reduce("", (a, b) -> a.isEmpty() ? "  " + b : a + "\n  " + b)));
    }

    @Test
    void noTrackedShellScriptRequiresBash4() throws Exception {
        List<String> scripts = trackedBashScripts();

        var findings = new ArrayList<Bash4Finding>();
        for (String relative : scripts) {
            findings.addAll(scanBash4(relative, Files.readString(CHECKOUT.resolve(relative), StandardCharsets.UTF_8)));
        }

        assertEquals(List.of(), findings, () -> """
                A tracked shell script requires Bash 4+, but stock macOS supplies Bash 3.2 and that is the
                repository baseline. An `#!/usr/bin/env bash` shebang can hide this locally by selecting a
                Homebrew Bash, while Linux CI's newer Bash accepts it.

                %s

                Replace mapfile/readarray with a `while IFS= read -r` loop and replace associative arrays with
                parallel indexed arrays, a linear lookup, or a temporary file.
                Contract and measurements: docs/BUILD-ENVIRONMENT.md "Bash 3.2 is the script baseline".""".formatted(
                findings.stream().map(Bash4Finding::toString)
                        .reduce("", (a, b) -> a.isEmpty() ? "  " + b : a + "\n  " + b)));
    }

    @Test
    void everyTrackedBashScriptParsesWithTheStockInterpreterPath() throws Exception {
        var findings = new ArrayList<String>();
        for (String relative : trackedBashScripts()) {
            var builder = CHECKOUT.command(
                    List.of("/bin/bash", "-n", CHECKOUT.resolve(relative).toString()));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();
            if (status != 0) {
                findings.add(relative + " (exit " + status + ")\n" + output.strip());
            }
        }
        assertEquals(List.of(), findings, () -> """
                A tracked Bash script does not parse under `/bin/bash`. On macOS that path is the stock Bash 3.2
                lower boundary; Linux keeps exercising the same scripts with its newer system Bash.

                %s""".formatted(String.join("\n\n", findings)));
    }

    @Test
    void shellEntrypointsArePosixUnlessTheyBelongToTheShrinkingMigrationSet() throws Exception {
        var actualBash = new TreeSet<>(trackedBashScripts());
        var unexpected = new TreeSet<String>();
        for (String relative : trackedShellScripts()) {
            if (!LEGACY_BASH_SCRIPTS.contains(relative)
                    && !firstLine(CHECKOUT.resolve(relative)).equals("#!/bin/sh")) {
                unexpected.add(relative);
            }
        }
        var migrated = new TreeSet<>(LEGACY_BASH_SCRIPTS);
        migrated.removeAll(actualBash);

        assertEquals(List.of(), unexpected.stream().toList(), () -> """
                A new non-POSIX shell script bypasses the repository contract:

                %s

                New shell entrypoints use `#!/bin/sh`. Keep only environment setup and process handoff in shell;
                move stateful parsing, registries, and report logic to TypeScript under `scripts/typescript/`.
                """.formatted(String.join("\n", unexpected)));
        assertEquals(List.of(), migrated.stream().toList(), () -> """
                These legacy Bash scripts have migrated, but their temporary exemptions remain:

                %s

                Delete the exemptions in the same change so the Bash denominator can only shrink.
                """.formatted(String.join("\n", migrated)));
    }

    @Test
    void everyTrackedPosixScriptParsesWithDash() throws Exception {
        var findings = new ArrayList<String>();
        for (String relative : trackedShellScripts()) {
            if (!firstLine(CHECKOUT.resolve(relative)).equals("#!/bin/sh")) {
                continue;
            }
            var builder = CHECKOUT.command(List.of("/bin/dash", "-n", CHECKOUT.resolve(relative).toString()));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int status = process.waitFor();
            if (status != 0) {
                findings.add(relative + " (exit " + status + ")\n" + output.strip());
            }
        }
        assertEquals(List.of(), findings, () -> """
                A tracked `#!/bin/sh` script does not parse with Dash, the executable POSIX oracle used on macOS
                and Ubuntu. macOS `/bin/sh` accepts Bash extensions, so its success is not portability evidence.

                %s""".formatted(String.join("\n\n", findings)));
    }

    @Test
    void theScannerCatchesEitherSpellingOnItsOwn() {
        assertEquals(List.of(StatDialect.GNU),
                dialectsFound("t=$(stat -c %Y \"$f\")"),
                "the GNU-only spelling must be caught");
        assertEquals(List.of(StatDialect.BSD),
                dialectsFound("newest=$(stat -f %m \"$x\" 2>/dev/null || echo 0)"),
                "the BSD-only spelling must be caught");
    }

    @Test
    void theStatScannerReadsLogicalLinesAndKeepsThePortableExemptionLocal() {
        assertEquals(List.of(StatDialect.GNU),
                dialectsFound("t=$(stat " + '\\' + "\n  -c %Y \"$f\")"),
                "a continued GNU spelling is still one shell command");

        List<Finding> findings = scan("planted.sh", """
                if is_epoch "$(stat -c %Y -- . 2>/dev/null || true)"; then dialect=gnu
                elif is_epoch "$(stat -f %m . 2>/dev/null || true)"; then dialect=bsd
                fi
                unsafe=$(stat -c %Y "$f")
                """);
        assertEquals(1, findings.size(), "a portable probe must not exempt an unrelated one-dialect call");
        assertEquals(StatDialect.GNU, findings.getFirst().dialect());
        assertEquals(4, findings.getFirst().line());

        findings = scan("planted.sh", """
                mtime() {
                  portable=$(stat -c %Y "$f" || stat -f %m "$f")
                  unsafe=$(stat -c %Y "$g")
                }
                """);
        assertEquals(1, findings.size(), "an uneven extra call must not hide inside a portable function");
        assertEquals(StatDialect.GNU, findings.getFirst().dialect());
        assertEquals(3, findings.getFirst().line());
    }

    @Test
    void theScannerClearsWhatIsAlreadyPortable() {
        assertEquals(List.of(),
                dialectsFound("""
                        if is_epoch "$(stat -c %Y -- . 2>/dev/null || true)"; then dialect=gnu
                        elif is_epoch "$(stat -f %m . 2>/dev/null || true)"; then dialect=bsd
                        fi"""),
                "a script that probes both dialects is handling the split, not falling into it");
        assertEquals(List.of(),
                dialectsFound("# GNU's `stat -f %m F` reads the format as a file, so we do not use it"),
                "naming the spelling in a comment is how the trap gets documented");
        assertEquals(List.of(),
                dialectsFound("python3 -c 'import os,sys; print(int(os.stat(sys.argv[1]).st_mtime))' \"$f\""),
                "the dialect-free reading must not be flagged");
    }

    @Test
    void theBashVersionScannerCatchesEveryForbiddenConstruct() {
        assertEquals(List.of(Bash4Construct.BULK_READ), bash4ConstructsFound("mapfile -t files < list"));
        assertEquals(List.of(Bash4Construct.BULK_READ), bash4ConstructsFound("readarray -d '' files < list"));
        assertEquals(List.of(Bash4Construct.ASSOCIATIVE_ARRAY), bash4ConstructsFound("declare -A seen=()"));
        assertEquals(List.of(Bash4Construct.ASSOCIATIVE_ARRAY), bash4ConstructsFound("local -rA labels=()"));
        assertEquals(List.of(Bash4Construct.ASSOCIATIVE_ARRAY, Bash4Construct.GLOBAL_DECLARATION),
                bash4ConstructsFound("typeset -g -A cache=()"));
        assertEquals(List.of(Bash4Construct.ASSOCIATIVE_ARRAY), bash4ConstructsFound("readonly -A cache=()"));
        assertEquals(List.of(Bash4Construct.GLOBAL_DECLARATION), bash4ConstructsFound("declare -g cache=()"));
        assertEquals(List.of(Bash4Construct.GLOBAL_DECLARATION), bash4ConstructsFound("typeset -g cache=()"));
        assertEquals(List.of(Bash4Construct.NAMEREF_DECLARATION), bash4ConstructsFound("declare -n ref=target"));
        assertEquals(List.of(Bash4Construct.NAMEREF_DECLARATION), bash4ConstructsFound("local -n ref=target"));
        assertEquals(List.of(Bash4Construct.GLOBSTAR_OPTION), bash4ConstructsFound("shopt -s globstar"));
        assertEquals(List.of(Bash4Construct.WAIT_NEXT), bash4ConstructsFound("wait -n"));
        assertEquals(List.of(Bash4Construct.NEGATIVE_ARRAY_INDEX),
                bash4ConstructsFound("printf '%s\\n' \"${values[-1]}\""));
        assertEquals(List.of(Bash4Construct.FRACTIONAL_READ_TIMEOUT),
                bash4ConstructsFound("IFS= read -r -t 0.5 value"));
        assertEquals(List.of(Bash4Construct.ASSOCIATIVE_ARRAY),
                bash4ConstructsFound("declare " + '\\' + "\n  -A split=()"));
    }

    @Test
    void theBashVersionScannerAllowsBash32FormsAndDocumentation() {
        assertEquals(List.of(), bash4ConstructsFound("files=(); while IFS= read -r file; do files+=(\"$file\"); done"));
        assertEquals(List.of(), bash4ConstructsFound("declare -a indexed=()"));
        assertEquals(List.of(), bash4ConstructsFound("# mapfile and declare -A require Bash 4"));
        assertEquals(List.of(), bash4ConstructsFound("files=()  # cannot use mapfile on Bash 3.2"));
        assertEquals(List.of(), bash4ConstructsFound(String.join("\n",
                "cat <<'PAYLOAD'",
                "declare -A documentation=()",
                "PAYLOAD")));
        assertEquals(List.of(), bash4ConstructsFound(String.join("\n",
                "documentation=\"$(cat <<'PAYLOAD'",
                "declare -A documentation=()",
                "PAYLOAD",
                ")\"")));
        assertEquals(List.of(), bash4ConstructsFound("mapfile_compat() { :; }"));
    }

    @Test
    void shellBehaviorTestsDoNotSelectBashThroughPath() throws Exception {
        var findings = new ArrayList<String>();
        for (String relative : CHECKOUT.tracked().stream().map(CHECKOUT::relative).toList()) {
            if (!isTestSource(relative)) {
                continue;
            }
            findings.addAll(pathSelectedBashFindings(
                    relative, Files.readString(CHECKOUT.resolve(relative), StandardCharsets.UTF_8)));
        }
        assertEquals(List.of(), findings, () -> """
                A shell behavior test selects `bash` through PATH. On macOS that silently switches from stock
                Bash 3.2 to Homebrew Bash when it is installed and removes the lower-bound oracle. Invoke
                `/bin/bash` explicitly.

                %s""".formatted(String.join("\n", findings)));
    }

    @Test
    void theTestLauncherSourceGuardCatchesJavaKotlinAndContinuedConstruction() {
        assertFalse(pathSelectedBashFindings("JavaTest.java", "new ProcessBuilder(\n  \"bash\", script)").isEmpty());
        assertFalse(pathSelectedBashFindings("KotlinTest.kt", "ProcessBuilder(\"bash\", script)").isEmpty());
        assertEquals(List.of(), pathSelectedBashFindings(
                "PinnedTest.java", "new ProcessBuilder(\"/bin/bash\", script)"));

        assertTrue(isTestSource("src/test/java/RootTest.java"));
        assertTrue(isTestSource("adapter/src/releaseGuardTest/kotlin/ReleaseTest.kt"));
        assertTrue(isTestSource("interpreter/src/jvmTest/kotlin/JvmTest.kt"));
        assertFalse(isTestSource("cli/src/main/java/Product.java"));
    }

    @Test
    void shellScriptSelectionCoversConventionalNamesAndEveryShellShebang() {
        assertTrue(isShellScript("scripts/tool.sh", ""));
        assertTrue(isShellScript("scripts/library.bash", ""));
        assertTrue(isShellScript("scripts/tool.zsh", ""));
        assertTrue(isShellScript(".githooks/custom", ""));
        assertTrue(isShellScript("bin/custom.tool", "#!/usr/bin/env bash"));
        assertFalse(isShellScript("bin/custom.tool", "#!/usr/bin/env python3"));

        assertTrue(isBashScript("scripts/tool.sh", "#!/usr/bin/env bash"));
        assertTrue(isBashScript("scripts/library.bash", ""));
        assertFalse(isBashScript("scripts/tool.sh", "#!/bin/sh"));
        assertFalse(isBashScript("scripts/tool.zsh", "#!/bin/zsh"));
    }

    private static List<StatDialect> dialectsFound(String script) {
        return scan("planted.sh", script).stream().map(Finding::dialect).distinct().toList();
    }

    private static List<Bash4Construct> bash4ConstructsFound(String script) {
        return scanBash4("planted.sh", script).stream().map(Bash4Finding::construct).distinct().toList();
    }

    private static List<String> pathSelectedBashFindings(String file, String content) {
        var findings = new ArrayList<String>();
        var matcher = PATH_SELECTED_BASH.matcher(content);
        while (matcher.find()) {
            int line = 1 + (int) content.substring(0, matcher.start()).chars().filter(value -> value == '\n').count();
            String source = content.lines().skip(line - 1L).findFirst().orElse("").strip();
            findings.add(file + ":" + line + "  " + source);
        }
        return findings;
    }

    private static boolean isTestSource(String relative) {
        if (!(relative.endsWith(".java") || relative.endsWith(".kt"))) {
            return false;
        }
        int source = relative.indexOf("src/");
        if (source < 0) {
            return false;
        }
        int sourceSetStart = source + "src/".length();
        int sourceSetEnd = relative.indexOf('/', sourceSetStart);
        return sourceSetEnd > sourceSetStart
                && relative.substring(sourceSetStart, sourceSetEnd).toLowerCase().contains("test");
    }

    private static List<Bash4Finding> scanBash4(String file, String content) {
        var findings = new ArrayList<Bash4Finding>();
        for (ShellLine line : logicalShellLines(content)) {
            for (Bash4Construct construct : Bash4Construct.values()) {
                if (construct.spelling.matcher(line.source()).find()) {
                    findings.add(new Bash4Finding(file, line.number(), construct, line.source()));
                }
            }
        }
        return findings;
    }

    /**
     * Findings for one script. A structural shell scope is portable only when its direct {@code stat} invocations
     * pair the two dialects evenly. This keeps a deliberate probe or dispatch local: one safe function or {@code if}
     * block cannot exempt an unrelated one-dialect call elsewhere in the file.
     */
    private static List<Finding> scan(String file, String content) {
        var scopes = new ShellScopes();
        var occurrences = new LinkedHashMap<Integer, Map<StatDialect, List<Finding>>>();
        for (ShellLine line : logicalShellLines(content)) {
            int scope = scopes.openFor(line.source());
            for (StatDialect dialect : StatDialect.values()) {
                var matcher = dialect.spelling.matcher(line.source());
                while (matcher.find()) {
                    occurrences.computeIfAbsent(scope, ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(dialect, ignored -> new ArrayList<>())
                            .add(new Finding(file, line.number(), dialect, line.source()));
                }
            }
            scopes.closeAfter(line.source());
        }

        var findings = new ArrayList<Finding>();
        for (Map<StatDialect, List<Finding>> byDialect : occurrences.values()) {
            List<Finding> gnu = byDialect.getOrDefault(StatDialect.GNU, List.of());
            List<Finding> bsd = byDialect.getOrDefault(StatDialect.BSD, List.of());
            int paired = Math.min(gnu.size(), bsd.size());
            if (gnu.size() > paired) {
                findings.addAll(gnu.subList(paired, gnu.size()));
            }
            if (bsd.size() > paired) {
                findings.addAll(bsd.subList(paired, bsd.size()));
            }
        }
        return findings;
    }

    private static List<ShellLine> logicalShellLines(String content) {
        List<String> physicalLines = content.lines().toList();
        var hereDocuments = new ArrayDeque<HereDocument>();
        var logicalLines = new ArrayList<ShellLine>();
        int index = 0;
        while (index < physicalLines.size()) {
            String physical = physicalLines.get(index);
            if (!hereDocuments.isEmpty()) {
                HereDocument hereDocument = hereDocuments.getFirst();
                String candidate = hereDocument.stripsLeadingTabs() ? physical.replaceFirst("^\\t+", "") : physical;
                index++;
                if (candidate.equals(hereDocument.delimiter())) {
                    hereDocuments.removeFirst();
                }
                continue;
            }

            int number = index + 1;
            var logical = new StringBuilder();
            boolean continued;
            do {
                physical = physicalLines.get(index++);
                continued = endsWithContinuation(physical);
                logical.append(continued ? physical.substring(0, physical.length() - 1) : physical);
                if (continued) {
                    logical.append('\n');
                }
            } while (continued && index < physicalLines.size());

            String source = stripInlineComments(logical.toString());
            hereDocuments.addAll(hereDocumentsIn(source));
            if (!source.isBlank()) {
                logicalLines.add(new ShellLine(number, source));
            }
        }
        return logicalLines;
    }

    private static boolean endsWithContinuation(String line) {
        int backslashes = 0;
        for (int index = line.length() - 1; index >= 0 && line.charAt(index) == '\\'; index--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static String stripInlineComments(String source) {
        var clean = new StringBuilder(source.length());
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        boolean comment = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (comment) {
                if (current == '\n') {
                    comment = false;
                    clean.append(current);
                }
                continue;
            }
            if (escaped) {
                clean.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\' && !singleQuoted) {
                clean.append(current);
                escaped = true;
                continue;
            }
            if (current == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                clean.append(current);
                continue;
            }
            if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                clean.append(current);
                continue;
            }
            if (current == '#' && !singleQuoted && !doubleQuoted && startsComment(source, index)) {
                comment = true;
                continue;
            }
            clean.append(current);
        }
        return clean.toString().stripTrailing();
    }

    private static boolean startsComment(String source, int index) {
        if (index == 0) {
            return true;
        }
        char previous = source.charAt(index - 1);
        return Character.isWhitespace(previous) || ";|&(){}".indexOf(previous) >= 0;
    }

    private static List<HereDocument> hereDocumentsIn(String source) {
        var hereDocuments = new ArrayList<HereDocument>();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;
        int commandSubstitutionDepth = 0;
        for (int index = 0; index + 1 < source.length(); index++) {
            char current = source.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && !singleQuoted) {
                escaped = true;
                continue;
            }
            if (current == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (current == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (!singleQuoted && current == '$' && source.charAt(index + 1) == '(') {
                commandSubstitutionDepth++;
                index++;
                continue;
            }
            if (!singleQuoted && commandSubstitutionDepth > 0 && current == '(') {
                commandSubstitutionDepth++;
                continue;
            }
            if (!singleQuoted && commandSubstitutionDepth > 0 && current == ')') {
                commandSubstitutionDepth--;
                continue;
            }
            if (singleQuoted || doubleQuoted && commandSubstitutionDepth == 0
                    || current != '<' || source.charAt(index + 1) != '<'
                    || index + 2 < source.length() && source.charAt(index + 2) == '<') {
                continue;
            }

            int cursor = index + 2;
            boolean stripsLeadingTabs = cursor < source.length() && source.charAt(cursor) == '-';
            if (stripsLeadingTabs) {
                cursor++;
            }
            while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))
                    && source.charAt(cursor) != '\n') {
                cursor++;
            }
            var delimiter = new StringBuilder();
            boolean delimiterSingleQuoted = false;
            boolean delimiterDoubleQuoted = false;
            while (cursor < source.length()) {
                char value = source.charAt(cursor);
                if (!delimiterSingleQuoted && !delimiterDoubleQuoted
                        && (Character.isWhitespace(value) || ";|&()<>".indexOf(value) >= 0)) {
                    break;
                }
                if (value == '\'' && !delimiterDoubleQuoted) {
                    delimiterSingleQuoted = !delimiterSingleQuoted;
                } else if (value == '"' && !delimiterSingleQuoted) {
                    delimiterDoubleQuoted = !delimiterDoubleQuoted;
                } else if (value == '\\' && !delimiterSingleQuoted && cursor + 1 < source.length()) {
                    delimiter.append(source.charAt(++cursor));
                } else {
                    delimiter.append(value);
                }
                cursor++;
            }
            if (!delimiter.isEmpty()) {
                hereDocuments.add(new HereDocument(delimiter.toString(), stripsLeadingTabs));
            }
            index = Math.max(index, cursor - 1);
        }
        return hereDocuments;
    }

    private static final class ShellScopes {
        private static final Pattern FUNCTION_START = Pattern.compile(
                "^\\s*(?:function\\s+)?[A-Za-z_][A-Za-z0-9_]*\\s*(?:\\(\\s*\\))?\\s*\\{");
        private static final Pattern GROUP_START = Pattern.compile("^\\s*\\{");
        private static final Pattern CONTROL_START = Pattern.compile(
                "(?:^|[;|&{}])\\s*(if|case|for|while|until|select)\\b");
        private static final Pattern CONTROL_END = Pattern.compile("\\b(fi|esac|done)\\b");
        private static final Pattern GROUP_END = Pattern.compile("(?:^|;)\\s*}\\s*(?:;|$)");

        private final ArrayDeque<ShellScope> scopes = new ArrayDeque<>();
        private int nextId = 1;

        int openFor(String source) {
            String structure = outsideQuotes(source);
            if (FUNCTION_START.matcher(structure).find()) {
                scopes.addFirst(new ShellScope(nextId++, ScopeKind.FUNCTION));
            } else if (GROUP_START.matcher(structure).find()) {
                scopes.addFirst(new ShellScope(nextId++, ScopeKind.GROUP));
            }
            var starts = CONTROL_START.matcher(structure);
            while (starts.find()) {
                scopes.addFirst(new ShellScope(nextId++, scopeKind(starts.group(1))));
            }
            return scopes.isEmpty() ? nextId++ : scopes.getFirst().id();
        }

        void closeAfter(String source) {
            String structure = outsideQuotes(source);
            var ends = CONTROL_END.matcher(structure);
            while (ends.find()) {
                pop(scopeKind(ends.group(1)));
            }
            if (GROUP_END.matcher(structure).find()) {
                pop(ScopeKind.FUNCTION, ScopeKind.GROUP);
            }
        }

        private void pop(ScopeKind... expected) {
            while (!scopes.isEmpty()) {
                ShellScope scope = scopes.removeFirst();
                for (ScopeKind kind : expected) {
                    if (scope.kind() == kind) {
                        return;
                    }
                }
            }
        }

        private static ScopeKind scopeKind(String word) {
            return switch (word) {
                case "if", "fi" -> ScopeKind.IF;
                case "case", "esac" -> ScopeKind.CASE;
                case "for", "while", "until", "select", "done" -> ScopeKind.LOOP;
                default -> throw new IllegalArgumentException("unknown shell control word: " + word);
            };
        }

        private static String outsideQuotes(String source) {
            var structure = new StringBuilder(source.length());
            boolean singleQuoted = false;
            boolean doubleQuoted = false;
            boolean escaped = false;
            for (int index = 0; index < source.length(); index++) {
                char current = source.charAt(index);
                if (escaped) {
                    structure.append(' ');
                    escaped = false;
                } else if (current == '\\' && !singleQuoted) {
                    structure.append(' ');
                    escaped = true;
                } else if (current == '\'' && !doubleQuoted) {
                    singleQuoted = !singleQuoted;
                    structure.append(' ');
                } else if (current == '"' && !singleQuoted) {
                    doubleQuoted = !doubleQuoted;
                    structure.append(' ');
                } else {
                    structure.append(singleQuoted || doubleQuoted ? ' ' : current);
                }
            }
            return structure.toString();
        }
    }

    /** Extension, location, or shebang — a script is a script whether or not it is named {@code *.sh}. */
    private static List<String> trackedShellScripts() throws IOException {
        var scripts = new ArrayList<String>();
        for (String relative : CHECKOUT.tracked().stream().map(CHECKOUT::relative).toList()) {
            Path file = CHECKOUT.resolve(relative);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            if (isShellScript(relative, firstLine(file))) {
                scripts.add(relative);
            }
        }
        return scripts;
    }

    private static List<String> trackedBashScripts() throws IOException {
        var scripts = new ArrayList<String>();
        for (String relative : trackedShellScripts()) {
            if (isBashScript(relative, firstLine(CHECKOUT.resolve(relative)))) {
                scripts.add(relative);
            }
        }
        return scripts;
    }

    private static boolean isShellScript(String relative, String firstLine) {
        return relative.endsWith(".sh") || relative.endsWith(".bash") || relative.endsWith(".zsh")
                || relative.startsWith(".githooks/") || hasShellShebang(firstLine);
    }

    private static boolean isBashScript(String relative, String firstLine) {
        if (firstLine.startsWith("#!")) {
            return firstLine.contains("bash");
        }
        return relative.endsWith(".sh") || relative.endsWith(".bash");
    }

    private static boolean hasShellShebang(String firstLine) {
        return firstLine.startsWith("#!") && firstLine.contains("sh");
    }

    private static String firstLine(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String first = reader.readLine();
            return first == null ? "" : first;
        } catch (IOException e) {
            return ""; // a binary or non-UTF-8 blob is not a shell script
        }
    }

}
