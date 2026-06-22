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
        // R1 (CLI-SPEC §2): a top-level orientation footer — the read-when map in
        // miniature — routing first contact to the one-call manifest + the catalog verbs.
        assertTrue(out.contains("Getting started"), () -> "footer heading present: " + out);
        assertTrue(out.contains("Whole verb tree as JSON"), () -> "positions manifest as the one-call map: " + out);
        assertTrue(out.contains("diagnostics <CODE>"), () -> "routes error decoding to diagnostics: " + out);
        assertTrue(out.contains("operators") && out.contains("patterns"),
                () -> "routes the DSL vocabulary/idioms: " + out);
    }

    @Test
    void perVerbHelpShowsParameters() {
        CliTestSupport.Result res = CliTestSupport.run("rule", "check", "--help");
        assertEquals(0, res.code(), res::err);
        assertTrue(res.out().contains("--field"), () -> "rule check --help shows --field: " + res.out());
    }

    @Test
    void fieldAddHelpSurfacesTheConfigBearingKindsViaTheSpec() {
        // probe DX: the --kind FLAG binds only the 5 simple kinds (STRING/NUMBER/DATE/BOOLEAN/CONFIRM); the 6
        // config-bearing kinds (TIME/DATETIME/DATERANGE/DATEFRAGMENT/ENUM/CUSTOM) need a <spec.json> (they
        // carry config). The help must name them and point at the spec — else an agent reading --kind alone
        // concludes only 5 kinds exist.
        CliTestSupport.Result res = CliTestSupport.run("field", "add", "--help");
        assertEquals(0, res.code(), res::err);
        String out = res.out();
        assertTrue(out.contains("DATERANGE") && out.contains("ENUM"),
                () -> "field add --help names the config-bearing kinds: " + out);
        assertTrue(out.contains("schema field add") || out.contains("spec.json"),
                () -> "field add --help points to the spec for the config-bearing kinds: " + out);
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
