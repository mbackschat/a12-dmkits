package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The CLI's self-describing help surface (CLI-SPEC §1a, picocli-driven): {@code --help} on the tool
 * and every verb, generated from the same declarations that drive parsing — so a cold agent can
 * explore without the N1 skill. Also locks the bare/unknown-invocation exit codes.
 */
class CliHelpTest {

    @Test
    void rootHelpListsTheVerbs() {
        CliTestSupport.Result res = CliTestSupport.run("--help");
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("validate") && res.out().contains("operators"),
                () -> "root help lists verbs: " + res.out());
    }

    @Test
    void rootHelpShowsAnOrientationFooter() {
        CliTestSupport.Result res = CliTestSupport.run("--help");
        assertEquals(0, res.code(), res::err);
        String out = res.out();
        // R1 (CLI-ORIENTATION-PROPOSAL §3): a top-level orientation footer — the read-when map in
        // miniature — routing first contact to the one-call manifest + the catalog verbs.
        assertTrue(out.contains("Getting started"), () -> "footer heading present: " + out);
        assertTrue(out.contains("Whole verb tree as JSON"), () -> "positions manifest as the one-call map: " + out);
        assertTrue(out.contains("diagnostics <CODE>"), () -> "routes error decoding to diagnostics: " + out);
        assertTrue(out.contains("operators") && out.contains("patterns"),
                () -> "routes the DSL vocabulary/idioms: " + out);
    }

    @Test
    void perVerbHelpShowsParameters() {
        CliTestSupport.Result res = CliTestSupport.run("explain", "--help");
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("--rule"), () -> "explain --help shows --rule: " + res.out());
    }

    @Test
    void noVerbIsAUsageError() {
        assertEquals(2, CliTestSupport.run().code());
    }

    @Test
    void unknownVerbIsAnError() {
        assertEquals(2, CliTestSupport.run("bogus").code());
    }
}
