package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The JDK-21 pin is decided in exactly ONE place, and the denominator of that claim is <b>derived</b>.
 *
 * <p>{@code BuildPerformancePolicyTest} already checks that each of a handful of NAMED consumers resolves the
 * pin through {@code a12_pinned_jdk21_home}. That is the positive half and it stays there. The negative half —
 * <em>nobody else re-encodes it</em> — cannot live in an authored list: a list only sees the files someone
 * remembered to add, so it reports a clean bill of health for the one consumer nobody thought of. That is not
 * hypothetical. On 2026-08-19 the authored list held six entries and read as a complete guard, while the
 * committed {@code .envrc} — which decides {@code JAVA_HOME} for every {@code cd} into this repository under
 * direnv — captured {@code java_home -v 21} itself and had never been in it. A cold review found it by running
 * the list's own regex over every tracked file, which is what this class now does on every build.
 *
 * <p>It lives in {@code leakGuardTest} rather than beside its sibling for the same reason
 * {@code PrivateMarkerLeakGuardTest} does: "the git-tracked set" is not something a Gradle input can express,
 * so a newly tracked file would leave an input-declared task UP-TO-DATE and the sweep silently unrun. That task
 * declares {@code upToDateWhen false} and {@code cacheIf false}, so this scan cannot go stale.
 */
class JdkPinSingleSourceGuardTest {

    /**
     * An unescaped {@code VAR=$(…java_home…)} capture on a non-comment line. Identical to
     * {@code BuildPerformancePolicyTest.RE_ENCODED_PIN} on purpose: the two halves of one claim must agree on
     * what "re-encoding the pin" means, or a consumer could satisfy one and violate the other.
     */
    private static final Pattern RE_ENCODED_PIN = Pattern.compile(
            "(?m)^[^#\n]*\\b(?:export\\s+)?[A-Za-z_][A-Za-z0-9_]*=\"?\\$\\(/usr/libexec/java_home");

    /** The one file allowed to decide the pin. Any other entry here would be the defect this guard prevents. */
    private static final String OWNER = "scripts/gradle-environment.sh";

    /**
     * Markdown is excluded, and the exclusion is the point rather than a convenience: this guard's subject is a
     * file that DECIDES the pin when something runs it. A README, a spec, or a probe note showing a reader the
     * command to type is not a second decision site — five of them do, correctly, and the shared helper's whole
     * purpose is that a human can still be told what it resolves to. Anything executable or sourced is scanned,
     * `.envrc` included.
     */
    private static boolean isProse(String trackedPath) {
        return trackedPath.endsWith(".md");
    }

    @Test
    void noTrackedFileOutsideTheSharedHelperCapturesTheJdkPin() throws Exception {
        Path root = repositoryRoot();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        for (String tracked : trackedFiles(root)) {
            if (tracked.equals(OWNER) || isProse(tracked)) {
                continue;
            }
            Path file = root.resolve(tracked);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            String text;
            try {
                text = Files.readString(file, StandardCharsets.UTF_8);
            } catch (IOException binaryOrUndecodable) {
                continue;
            }
            scanned++;
            if (RE_ENCODED_PIN.matcher(text).find()) {
                offenders.add(tracked);
            }
        }
        assertTrue(scanned > 100,
                "the sweep read only " + scanned + " tracked text files, so it would pass vacuously");
        assertEquals(List.of(), offenders,
                () -> "these tracked files capture `/usr/libexec/java_home` into a variable themselves: "
                        + offenders + ". Two sites deciding one policy is the defect the shared helper exists "
                        + "to remove — the build pinning a JDK while a launcher check kept the caller's is how "
                        + "a stale JAVA_HOME became a source-provenance verdict. Source " + OWNER + " and call "
                        + "a12_pinned_jdk21_home instead. (Naming the command in a comment or a corrective "
                        + "message is fine; capturing its output is not.)");
    }

    /**
     * The owner really does own it — otherwise the sweep above could pass by watching a file the pin had moved
     * out of, and every other file would then be clean by default.
     *
     * <p>Note what this does NOT assert: that the owner matches {@link #RE_ENCODED_PIN}. It does not, and that
     * is correct — {@code a12_pinned_jdk21_home} RUNS {@code java_home} and lets its stdout be the function's
     * result rather than capturing it into a variable. The first version of this control asserted the match and
     * failed on the one file that is supposed to pass, which is a useful reminder that the regex describes the
     * defect shape, not the legitimate one.
     */
    @Test
    void theSharedHelperStillCarriesThePin() throws Exception {
        String helper = Files.readString(repositoryRoot().resolve(OWNER), StandardCharsets.UTF_8);
        assertTrue(helper.contains("a12_pinned_jdk21_home()"), OWNER + " must declare the pin function");
        assertTrue(helper.contains("/usr/libexec/java_home"),
                OWNER + " must be the file that resolves the pin; if the resolution moved, this guard is "
                        + "watching the wrong owner and every other file would pass by default");
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.isRegularFile(candidate.resolve("settings.gradle"))) {
            candidate = candidate.getParent();
        }
        assertTrue(candidate != null, "could not locate the repository root from " + Path.of("").toAbsolutePath());
        return candidate;
    }

    /** {@code git ls-files} is the published set. Failing loudly beats scanning nothing and passing. */
    private static List<String> trackedFiles(Path root) throws IOException, InterruptedException {
        var builder = new ProcessBuilder("git", "ls-files").directory(root.toFile());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> lines;
        try (var reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            lines = reader.lines().toList();
        }
        assertEquals(0, process.waitFor(),
                "git ls-files failed, so the scanned set is unknown rather than clean: " + lines);
        assertFalse(lines.isEmpty(),
                "git ls-files returned nothing, so this guard would scan an empty set and pass vacuously");
        return lines;
    }
}
