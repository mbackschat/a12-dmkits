package io.github.mbackschat.a12.dm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code scripts/selftest-bash-hook.sh}, so the PreToolUse Bash hook's controls execute in the build
 * rather than only when someone remembers to run them.
 *
 * <p><b>Why this class exists.</b> The hook was the only guard script in the repository with no selftest, and
 * a guard nobody has watched fail cannot be told apart from one that cannot fail. Its third pattern also
 * refuses a command CLAUDE.md still names as a fallback ({@code rm -rf build/test-results/…}), so the
 * does-not-fire half carries as much weight as the does-fire half: a guard that refuses justified work gets
 * switched off rather than repaired.
 *
 * <p><b>What is asserted.</b> A wiring test: the selftest exits 0 AND reports at least a floor of checks, so a
 * selftest that stopped running its cases would fail here instead of exiting 0 and reading as green.
 */
class BashHookScriptTest {

    private static final Checkout CHECKOUT = Checkout.locate();

    /** Deliberately well below the live count: it answers "did the cases run", not "how many are there". */
    private static final int MIN_CHECKS = 15;

    @Test
    void theBashHookSelftestPassesAndRunsItsChecks() throws Exception {
        Path script = CHECKOUT.requireFile("scripts/selftest-bash-hook.sh");
        var builder = CHECKOUT.command(List.of("/bin/bash", script.toString()));
        builder.redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes());
        int code = process.waitFor();

        assertEquals(0, code, () -> "scripts/selftest-bash-hook.sh failed:\n" + output);

        int checks = checksReported(output);
        assertTrue(checks >= MIN_CHECKS, () -> "the selftest reported only " + checks + " checks (floor "
                + MIN_CHECKS + ") — a selftest that stopped running its cases still exits 0:\n" + output);
    }

    /** The hook must stay wired, or the guard is inert however green its selftest is. */
    @Test
    void theHookIsStillRegisteredAsAPreToolUseBashHook() throws Exception {
        String settings = Files.readString(CHECKOUT.requireFile(".claude/settings.json"));
        assertTrue(settings.contains("guard-long-run-antipatterns.sh"),
                () -> "the Bash hook is no longer referenced from .claude/settings.json, so it cannot fire. "
                        + "A guard whose logic is locked while its WIRING is not reads as green forever.\n"
                        + settings);
        assertTrue(settings.contains("PreToolUse"), "the hook is registered, but not as a PreToolUse hook");
    }

    private static int checksReported(String output) {
        for (String line : output.split("\n")) {
            if (line.startsWith("selftest-bash-hook:")) {
                return Integer.parseInt(line.replaceAll("\\D+(\\d+).*", "$1"));
            }
        }
        return 0;
    }
}
