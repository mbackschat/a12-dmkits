package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * <b>The two legs of the CLI-level runtime differential must publish the SAME key set</b> — otherwise a channel
 * dropped by both agrees by construction, and the differential defends the defect instead of catching it.
 *
 * <p>This guard exists because that happened. {@code rule eval} projected a firing as a rendered line only, losing
 * the message {@code type} and the operands; {@link KernelEval}'s kernel leg was written to mirror the CLI, so it
 * dropped exactly the same channels — and every parity test over that verb passed while neither leg could answer
 * "value or omission error?". The values are the differential's job; the KEY SET is this one's, because a missing
 * key is invisible to a value comparison.
 *
 * <p>Two exemptions, each named rather than tolerated. {@code unsupported} is an interpreter-only channel by
 * construction (G29: the kernel has no "could not evaluate this construct" report — it evaluates or throws), and
 * {@code engine}-family provenance lives on the envelope, not in {@code data}. Everything else must appear on both
 * sides or the guard fails with the channel named.
 */
class RuntimeLegKeyParityTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * Keys one leg may legitimately publish alone, with the reason. A new entry here is a claim that the kernel
     * genuinely has no counterpart — not a place to park a channel someone forgot to project.
     */
    private static final Map<String, String> INTERPRETER_ONLY = Map.of(
            "unsupported", "G29 — the kernel has no 'could not evaluate this construct' report to mirror",
            "validationMode", "--relevant partial validation is an interpreter-only projection");

    private static Path instance(Path dir, String name, String json) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    @Test
    void bothLegsPublishTheSameRuntimeChannels(@TempDir Path dir) throws Exception {
        Path order = CliTestSupport.orderRuled(dir);
        Path computed = CliTestSupport.modelWithRuleAndComputation(dir);
        Path violation = instance(dir, "violation.json",
                "{ \"Order\": { \"OrderDate\": \"2026-01-10\", \"DeliveryDate\": \"2026-01-01\" } }");
        Path clean = instance(dir, "clean.json",
                "{ \"Order\": { \"OrderDate\": \"2026-01-01\", \"DeliveryDate\": \"2026-01-10\" } }");
        Path malformed = instance(dir, "malformed.json",
                "{ \"Order\": { \"OrderDate\": \"2026-01-10\", \"DeliveryDate\": \"not-a-date\" } }");
        Path billing = instance(dir, "billing.json",
                "{ \"Subscription\": { \"Billing\": { \"BaseFee\": \"250.00\" } } }");

        record Scenario(String label, String[] args) { }
        List<Scenario> scenarios = List.of(
                new Scenario("model eval (a firing)", new String[]{"-m", order.toString(), "model", "eval",
                        "--instance", violation.toString()}),
                new Scenario("model compute", new String[]{"-m", computed.toString(), "model", "compute",
                        "--instance", billing.toString()}),
                new Scenario("rule eval (fired)", new String[]{"-m", order.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", violation.toString()}),
                new Scenario("rule eval (passed)", new String[]{"-m", order.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", clean.toString()}),
                new Scenario("rule eval (suppressed)", new String[]{"-m", order.toString(), "rule", "eval",
                        "/Order/DeliveryNotBeforeOrder", "--instance", malformed.toString()}));

        for (Scenario scenario : scenarios) {
            CliTestSupport.Result interpreter = CliTestSupport.run(scenario.args());
            CliTestSupport.Result kernel = CliTestSupport.runKernel(scenario.args());
            assertEquals(0, interpreter.code(), () -> scenario.label() + ": " + interpreter.err());
            assertEquals(0, kernel.code(), () -> scenario.label() + ": " + kernel.err());

            Set<String> interpreted = channels(M.readTree(interpreter.out()).path("data"));
            Set<String> oracle = channels(M.readTree(kernel.out()).path("data"));
            interpreted.removeAll(INTERPRETER_ONLY.keySet());
            assertEquals(oracle, interpreted,
                    () -> scenario.label() + ": the two legs disagree about WHICH channels exist, so a value "
                            + "comparison over them cannot see the difference. interpreter=" + interpreter.out()
                            + " kernel=" + kernel.out());
        }
    }

    /**
     * The regression this guard was built from, stated as its own assertion so the reason survives a refactor of
     * the loop above: a fired focused rule really does carry the message channels on BOTH legs.
     */
    @Test
    void aFiredRuleEvalCarriesTheMessageChannelsOnBothLegs(@TempDir Path dir) throws Exception {
        Path order = CliTestSupport.orderRuled(dir);
        Path violation = instance(dir, "violation.json",
                "{ \"Order\": { \"OrderDate\": \"2026-01-10\", \"DeliveryDate\": \"2026-01-01\" } }");
        String[] args = {"-m", order.toString(), "rule", "eval", "/Order/DeliveryNotBeforeOrder",
                "--instance", violation.toString()};

        Set<String> interpreted = channels(M.readTree(CliTestSupport.run(args).out()).path("data"));
        Set<String> oracle = channels(M.readTree(CliTestSupport.runKernel(args).out()).path("data"));

        for (String channel : List.of("code", "field", "severity", "type", "message", "referenced")) {
            assertTrue(interpreted.contains(channel), () -> "interpreter leg publishes " + channel + ": " + interpreted);
            assertTrue(oracle.contains(channel), () -> "kernel leg publishes " + channel + ": " + oracle);
        }
        assertFalse(interpreted.contains("fillToFix"),
                () -> "a VALUE error has no fill-to-fix set, so publishing one would be fabricated: " + interpreted);
    }

    /** The emitted key names of one {@code data} payload, one level deep — the channel inventory. */
    private static Set<String> channels(JsonNode data) {
        Set<String> keys = new LinkedHashSet<>();
        data.fieldNames().forEachRemaining(keys::add);
        return new TreeSet<>(keys);
    }
}
