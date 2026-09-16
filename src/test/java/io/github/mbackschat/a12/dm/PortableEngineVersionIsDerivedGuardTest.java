package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The portable checker's version in a {@code model check} receipt must be DERIVED from the checker, never
 * written down in the CLI.
 *
 * <p><b>Why an equality test cannot carry this claim.</b> {@code PortableEngineSelectionTest} asserts that the
 * receipt's {@code engineVersion} equals the checker's own baked constant — which is the right assertion and is
 * blind to exactly the failure this guard exists for: a literal {@code "0.13.0"} in CLI source has the same
 * value today, so it passes that equality and then reports the wrong build the moment dmtool is linked against
 * a different checker. The whole point of the receipt is to name the artifact that answered, and a constant on
 * this side names an expectation instead.
 *
 * <p>So the check is a source scan for the release version, over the CLI's own main sources. It is deliberately
 * narrow: a version literal elsewhere in the estate may be legitimate (a pinned coordinate, a migration note),
 * and this guard makes a claim about one module's production code only.
 */
class PortableEngineVersionIsDerivedGuardTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** The one source of truth for the release train — {@code build.gradle}'s own {@code version = '…'} line. */
    private static final Pattern PROJECT_VERSION = Pattern.compile("(?m)^version = '([^']+)'");

    @Test
    void noCliSourceWritesDownTheReleaseVersionThePortableReceiptMustDerive() throws IOException {
        String version = projectVersion();
        List<Path> cliSources = cliMainSources();

        // Non-vacuity: the CLI has scores of main sources, so a handful means the walk stopped working and an
        // empty scan would report this property held.
        assertTrue(cliSources.size() >= 50,
                () -> "scanned only " + cliSources.size() + " CLI main sources, so the walk broke and a green "
                        + "result here proves nothing");

        List<String> offenders = new ArrayList<>();
        for (Path source : cliSources) {
            if (Files.readString(source).contains('"' + version + '"')) {
                offenders.add(String.valueOf(source.getFileName()));
            }
        }
        assertTrue(offenders.isEmpty(),
                () -> "these CLI sources hard-code the release version " + version + ": " + offenders
                        + ". An engine version belongs to the engine that answered — read it from there "
                        + "(io.github.mbackschat.a12.dm.consistency.CHECKER_VERSION for the portable checker, "
                        + "VersionInfo.current().kernelRuntime() for the kernel), so a dmtool built against a "
                        + "different engine reports that engine rather than this constant.");
    }

    private static String projectVersion() throws IOException {
        Matcher matcher = PROJECT_VERSION.matcher(Files.readString(CHECKOUT.requireFile("build.gradle")));
        assertTrue(matcher.find(), "build.gradle no longer declares `version = '…'`, so this guard has no pin");
        return matcher.group(1);
    }

    /**
     * The CLI's main sources, walked from the TREE rather than read from git's index.
     *
     * <p>{@code Checkout.tracked(…)} reads tracked files and is the right denominator for most guards, and it
     * is wrong here — measured 2026-09-16, by this guard's own positive control. An untracked file is exactly
     * a file being added, so a tracked-only scan is blind to the change under review: with a version literal
     * planted in a new, uncommitted CLI source this guard reported green. The tree walk needs no {@code build/}
     * exclusion because {@code cli/src/main} contains none.
     */
    private static List<Path> cliMainSources() throws IOException {
        try (var walk = Files.walk(CHECKOUT.resolve("cli/src/main"))) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
        }
    }
}
