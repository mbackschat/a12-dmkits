package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void aWrongLocatorFlagOnALocatorVerbSelfCorrectsToThePositional() {
        // The locator contract makes the TARGET-NOUN flag valid (--field on field read, etc. — see
        // LocatorContractTest); the self-correction now covers OTHER guesses like --path. The parse error must
        // NAME the positional form, not leave a bare "Unknown option".
        CliTestSupport.Result fieldRead = CliTestSupport.run("-m", "x.json", "field", "read", "--path", "/Doc/Age");
        assertEquals(2, fieldRead.code());
        assertTrue(fieldRead.err().contains("POSITIONAL") && fieldRead.err().contains("fieldPathInModel")
                        && fieldRead.err().contains("--path"),
                () -> "field read corrects --path → the positional: " + fieldRead.err());
        CliTestSupport.Result ruleRead = CliTestSupport.run("-m", "x.json", "rule", "read", "--path", "/Doc/X");
        assertEquals(2, ruleRead.code());
        assertTrue(ruleRead.err().contains("rulePathInModel"),
                () -> "rule read corrects --path → the positional: " + ruleRead.err());
        // probe 04-whereused-locator: `where-used` locates by a POSITIONAL <entityPath>; a wrong locator flag
        // (not one of the accepted noun aliases) must self-correct here too.
        CliTestSupport.Result whereUsed = CliTestSupport.run("-m", "x.json", "where-used", "--path", "/Order/Buyer");
        assertEquals(2, whereUsed.code());
        assertTrue(whereUsed.err().contains("POSITIONAL") && whereUsed.err().contains("entityPath")
                        && whereUsed.err().contains("--path"),
                () -> "where-used corrects --path → the positional <entityPath>: " + whereUsed.err());
    }

    @Test
    void aWrongInstanceFlagSelfCorrectsToInstance() {
        // agent-first: the runtime verbs (model eval/compute, rule test) describe their input as the document
        // INSTANCE, so an agent naturally guesses --doc / --document / --data. A wrong guess must NAME the real
        // flag (--instance), not leave a bare "Unknown option".
        for (String wrong : new String[]{"--doc", "--document", "--data"}) {
            CliTestSupport.Result r = CliTestSupport.run("-m", "x.json", "model", "eval", wrong, "d.json");
            assertEquals(2, r.code());
            assertTrue(r.err().contains("--instance") && r.err().contains(wrong),
                    () -> "model eval must correct " + wrong + " → --instance: " + r.err());
        }
        // rule test takes BOTH --instance AND a positional locator — the instance hint must win for --doc
        // (the locator hint would wrongly say "pass it as the positional <rulePathInModel>").
        CliTestSupport.Result rt = CliTestSupport.run("-m", "x.json", "rule", "test", "/Order/R", "--doc", "d.json");
        assertEquals(2, rt.code());
        assertTrue(rt.err().contains("--instance"),
                () -> "rule test must correct --doc → --instance (not the locator hint): " + rt.err());
    }

    @Test
    void aWrongFlagOnASpecInputVerbDoesNotGetTheLocatorHint() {
        // does-not-misfire: `field add` takes a <spec.json> positional (NOT a locator path/id), so an unknown
        // flag must NOT be told to "pass it as the positional" — only the default unknown-option + usage.
        CliTestSupport.Result res = CliTestSupport.run("-m", "x.json", "field", "add", "--bogus", "v");
        assertEquals(2, res.code());
        assertFalse(res.err().contains("locates its target by a POSITIONAL"),
                () -> "no locator hint on a spec-input verb: " + res.err());
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
