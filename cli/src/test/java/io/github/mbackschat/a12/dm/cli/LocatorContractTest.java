package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The LOCATOR CONTRACT (CLI-SPEC §5): the canonical locator is the positional {@code <path>}/{@code <id>};
 * every entity read verb — and {@code where-used} — ALSO accepts the flag named after its own target noun as
 * an alias, so an agent's noun-flag habit (learned on {@code rule check --field}) <b>works everywhere</b>
 * instead of mis-firing on a verb that locates positionally. This is the family-wide <b>does-accept</b> guard
 * (the root-cause-generalization rule: cover the siblings, not just the reported case): for each verb the
 * {@code --<noun>} alias resolves <em>identically</em> to the positional. The self-correction for a
 * genuinely-unknown flag (e.g. {@code --path}) stays — {@link CliHelpTest}.
 */
class LocatorContractTest {

    /** {@code <target> <op> <locator>} (positional) must read identically to {@code <target> <op> <nounFlag> <locator>}. */
    private static void sameAsPositional(Path model, String target, String op, String locator, String nounFlag) {
        CliTestSupport.Result pos = CliTestSupport.run("-m", model.toString(), target, op, locator);
        CliTestSupport.Result flag = CliTestSupport.run("-m", model.toString(), target, op, nounFlag, locator);
        assertEquals(0, pos.code(), pos::err);
        assertEquals(0, flag.code(), flag::err);
        assertEquals(pos.out(), flag.out(),
                () -> target + " " + op + " " + nounFlag + " must resolve identically to the positional");
    }

    @Test
    void fieldReadAcceptsTheFieldFlag(@TempDir Path dir) throws Exception {
        sameAsPositional(CliTestSupport.orderRuled(dir), "field", "read", "/Order/CustomerName", "--field");
    }

    @Test
    void ruleReadAcceptsTheRuleFlag(@TempDir Path dir) throws Exception {
        sameAsPositional(CliTestSupport.orderRuled(dir), "rule", "read", "/Order/DeliveryNotBeforeOrder", "--rule");
    }

    @Test
    void groupReadAcceptsTheGroupFlag(@TempDir Path dir) throws Exception {
        sameAsPositional(CliTestSupport.orderRuled(dir), "group", "read", "/Order/ShippingAddress", "--group");
    }

    @Test
    void computationReadAcceptsTheComputationFlag(@TempDir Path dir) throws Exception {
        sameAsPositional(CliTestSupport.subscriptionComputed(dir), "computation", "read",
                "/Subscription/Billing/EffectiveFeeComp", "--computation");
    }

    @Test
    void typedefReadAcceptsTheTypedefAndIdFlags(@TempDir Path dir) throws Exception {
        Path m = CliTestSupport.typedefs(dir);
        sameAsPositional(m, "typedef", "read", "PostalCodeType", "--typedef");
        sameAsPositional(m, "typedef", "read", "PostalCodeType", "--id");
    }

    @Test
    void whereUsedAcceptsTheNounFlags(@TempDir Path dir) throws Exception {
        // where-used is a root verb (no target token): dmtool -m <model> where-used <entity>
        Path m = CliTestSupport.orderRuled(dir);
        CliTestSupport.Result pos = CliTestSupport.run("-m", m.toString(), "where-used", "/Order/CustomerName");
        assertEquals(0, pos.code(), pos::err);
        for (String noun : new String[]{"--entity", "--field"}) {
            CliTestSupport.Result flag = CliTestSupport.run("-m", m.toString(), "where-used", noun, "/Order/CustomerName");
            assertEquals(0, flag.code(), flag::err);
            assertEquals(pos.out(), flag.out(),
                    () -> "where-used " + noun + " must resolve identically to the positional");
        }
    }
}
