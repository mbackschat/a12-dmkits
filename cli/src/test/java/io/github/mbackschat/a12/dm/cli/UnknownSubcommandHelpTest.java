package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * An unknown subcommand is rejected <b>whether or not</b> {@code --help} is also passed.
 *
 * <p>Reported from the field: {@code dmtool rule nosuchverb --help} exited <b>0</b> and printed the {@code rule}
 * group's help, while the same command without {@code --help} correctly exited 2. So the exit code depended on a flag
 * that has nothing to do with whether the verb exists — and 0 is the one answer an agent reads as "that verb is
 * fine". It then proceeds to use a verb that does not exist, and the help text it was handed does not say otherwise,
 * because parent help is a plausible response to a request it does not recognise as malformed.
 *
 * <p>The defect is in the <em>precedence</em>, not in either behaviour alone: help-on-request and reject-unknown are
 * both right, and an unknown verb has to win, because there is no command whose help was asked for. Locked across
 * <b>every</b> command group rather than the one reported: the class is picocli's arg handling, so it cannot be
 * specific to {@code rule}, and fixing only the reported group is how a defect class survives.
 */
class UnknownSubcommandHelpTest {

    /** Every group with subcommands — the enumeration is the point, since the mechanism is shared. */
    private static final List<String> GROUPS =
            List.of("rule", "computation", "model", "field", "group", "typedef", "include", "config", "workspace",
                    "profile");

    @Test
    void anUnknownSubcommandIsRejectedInEveryGroup() {
        for (String group : GROUPS) {
            var res = CliTestSupport.run(group, "nosuchverb");
            assertEquals(2, res.code(),
                    () -> "`" + group + " nosuchverb` must be a usage error: " + res.err() + res.out());
        }
    }

    /** The reported case: adding {@code --help} must not turn the rejection into a success. */
    @Test
    void anUnknownSubcommandStaysRejectedWhenHelpIsAlsoRequested() {
        for (String group : GROUPS) {
            var res = CliTestSupport.run(group, "nosuchverb", "--help");
            assertEquals(2, res.code(),
                    () -> "`" + group + " nosuchverb --help` must NOT exit 0 with parent help — an agent reads 0 as "
                            + "\"the verb exists\": " + res.err() + res.out());
            assertTrue(res.err().contains("nosuchverb"),
                    () -> "and it must name the unknown verb, not silently show group help: " + res.err());
        }
    }

    /** Flag order must not matter either — the same request, written the other way round. */
    @Test
    void helpBeforeTheUnknownSubcommandIsAlsoRejected() {
        var res = CliTestSupport.run("rule", "--help", "nosuchverb");
        assertEquals(2, res.code(), () -> "unknown verb wins regardless of position: " + res.err() + res.out());
    }

    /** The behaviours being preserved: real group help still works, and so does a real verb's help. */
    @Test
    void legitimateHelpRequestsStillSucceed() {
        assertEquals(0, CliTestSupport.run("rule", "--help").code());
        assertEquals(0, CliTestSupport.run("rule", "check", "--help").code());
        assertEquals(0, CliTestSupport.run("--help").code());
    }

    @Test
    void computationCheckGuessPointsToTheStructuredDryRun() {
        var result = CliTestSupport.run("computation", "check", "--help");
        assertEquals(2, result.code());
        assertTrue(result.err().contains("computation add <computation-spec.json> --dry-run"), result::err);
        assertTrue(result.err().contains("schema computation add"), result::err);
    }
}
