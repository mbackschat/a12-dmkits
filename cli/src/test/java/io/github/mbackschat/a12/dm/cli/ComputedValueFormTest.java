package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.edit.EditSession;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;

/**
 * <b>What the {@code value} string in {@code model compute}'s output MEANS</b> — its form is the TARGET field's
 * stored form, and nothing about the operand's spelling or the JSON input's type survives into it.
 *
 * <p>The gap this closes (CG7): the published contract said only "the computed value", so a consumer reading
 * {@code "250"} where its operand held {@code 250.00} could not tell whether dmtool had normalized the number,
 * lost a scale, or reported exactly what the field stores. Guessing wrong here is expensive in both directions —
 * a consumer that re-pads is wrong on a scale-0 target, and one that trusts the operand's zeros is wrong on
 * every target that declares fewer.
 *
 * <p><b>The number arm is oracled against {@code java.math.BigDecimal} itself</b>, the way
 * {@code A12NumberPlainFormTest} oracles the lexical plain form: the schema claims plain notation padded to the
 * target's declared minimum, so the expectation is computed from that claim rather than transcribed from a run.
 * A transcribed expectation would agree with any rendering the code happens to produce, including a wrong one.
 *
 * <p><b>The date arm needs the two formats to DIFFER</b> to say anything: a source and target sharing one format
 * make target-governs and source-passes-through indistinguishable, so {@code IssuedOn} declares
 * {@code yyyy-MM-dd} and {@code DueOn} declares {@code dd.MM.yyyy} and the computed value must read in the
 * target's.
 *
 * <p>The last test holds the published schema to the same statements, so the prose cannot drift away from the
 * behaviour these cases measure.
 */
class ComputedValueFormTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** The scale the padded target declares as its minimum — the pad this fixture is built to expose. */
    private static final int TARGET_MIN_SCALE = 2;

    /**
     * An invoicing model whose targets differ from their operands in exactly the two ways that make the stored
     * form observable: {@code NetAmount} declares a minimum scale its operand does not, and {@code DueOn}
     * declares a different date format than the {@code IssuedOn} it copies. {@code CappedAmount} shares
     * {@code NetAmount}'s declaration plus a value ceiling, so the same computed number lands as an ERROR there
     * and a VALUE next to it — the pair that shows the attempted value is rendered by the same rules.
     *
     * <p>{@code Units} carries the operand's own {@code maxFractionalDigits}, matched to the targets': the kernel
     * rejects a numeric assignment whose derived scales cannot reconcile (MVK_INVALID_COMPARE_DEC_PLACES), so a
     * scale-0 operand could not be authored into a scale-2 target at all.
     */
    private static Path invoiceForms(Path dir) throws Exception {
        String base = DocumentModelDraft.named("invoice-forms").locale("en_US")
                .rootGroup("Invoice", g -> g
                        .numberField("Units", n -> n.fractionDigits(TARGET_MIN_SCALE))
                        .numberField("NetAmount", n -> n.fractionDigits(TARGET_MIN_SCALE, TARGET_MIN_SCALE))
                        .numberField("CappedAmount", n -> n
                                .fractionDigits(TARGET_MIN_SCALE, TARGET_MIN_SCALE).max("5"))
                        .dateField("IssuedOn", d -> d.format("yyyy-MM-dd"))
                        .dateField("DueOn", d -> d.format("dd.MM.yyyy")))
                .build().toJson();
        EditSession s = EditSession.read(base);
        s.addComputation(m -> m.computation(m.num("/Invoice/NetAmount"))
                .name("NetAmountComp")
                .calc(m.num("/Invoice/Units").value())
                .message(Dsl.en("The net amount restates the billed units.")).build());
        s.addComputation(m -> m.computation(m.num("/Invoice/CappedAmount"))
                .name("CappedAmountComp")
                .calc(m.num("/Invoice/Units").value())
                .message(Dsl.en("The capped amount restates the billed units.")).build());
        s.addComputation(m -> m.computation(m.date("/Invoice/DueOn"))
                .name("DueOnComp")
                .calc(m.date("/Invoice/IssuedOn").expression())
                .message(Dsl.en("The due date restates the issue date.")).build());
        Path file = dir.resolve("invoice-forms.dm.json");
        Files.writeString(file, s.toJson());
        return file;
    }

    /** {@code model compute} over one instance document, as the entry for {@code fieldPath} (null if absent). */
    private static JsonNode computed(Path model, Path instance, String fieldPath) throws Exception {
        var res = CliTestSupport.run("-m", model.toString(), "model", "compute", "--instance", instance.toString());
        assertEquals(0, res.code(), res::err);
        for (JsonNode c : M.readTree(res.out()).get("data").get("computed")) {
            if (c.get("field").asText().equals(fieldPath)) {
                return c;
            }
        }
        return null;
    }

    private static Path instance(Path dir, String name, String json) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, json);
        return file;
    }

    /**
     * The claim, as its own function: plain notation, fraction digits = the natural scale raised to the target's
     * declared minimum, no cap. Identical to {@code A12DocumentJson.runtimeNumberForm}'s documented rule, written
     * here against {@code java.math} so the test does not oracle the implementation with itself.
     */
    private static String storedForm(String plain, int minFractionalDigits) {
        BigDecimal bd = new BigDecimal(plain);
        return bd.setScale(Math.max(bd.stripTrailingZeros().scale(), minFractionalDigits)).toPlainString();
    }

    @Test
    void aNumberTargetsDeclaredMinimumScaleGovernsTheComputedForm(@TempDir Path dir) throws Exception {
        Path model = invoiceForms(dir);
        // Each operand spelling is a way a consumer might expect to see its own input echoed back: a bare
        // integer, a JSON number, exponent notation, trailing zeros, leading zeros. The target's declaration
        // answers for all of them.
        for (String units : List.of("\"3\"", "3", "\"3.5\"", "3.5", "1e3", "\"4.50\"", "\"04\"", "\"0\"")) {
            Path doc = instance(dir, "units.json", "{ \"Invoice\": { \"Units\": " + units + " } }");
            JsonNode net = computed(model, doc, "Invoice/NetAmount");
            String expected = storedForm(units.replace("\"", ""), TARGET_MIN_SCALE);
            assertTrue(net != null && "value".equals(net.get("outcome").asText()),
                    () -> "Units=" + units + " computes a value: " + net);
            assertEquals(expected, net.get("value").asText(),
                    () -> "Units=" + units + " stores in the target's declared form, not the operand's spelling");
        }
    }

    @Test
    void anEmptyNumberOperandStoresZeroInTheTargetsDeclaredForm(@TempDir Path dir) throws Exception {
        // The empty-number-operand rule (ComputeDocVerbTest: empty reads as 0) meets the pad: the published
        // value is the target's rendering of 0, not a bare "0" — so the pad is unconditional, not a property of
        // having received an operand.
        Path model = invoiceForms(dir);
        Path doc = instance(dir, "empty.json", "{ \"Invoice\": { } }");
        JsonNode net = computed(model, doc, "Invoice/NetAmount");
        assertTrue(net != null && "value".equals(net.get("outcome").asText()), () -> "an empty operand: " + net);
        assertEquals(storedForm("0", TARGET_MIN_SCALE), net.get("value").asText(),
                () -> "an empty number operand stores 0 in the target's form: " + net);
    }

    @Test
    void aDateTargetRendersTheComputedValueInItsOwnDeclaredFormat(@TempDir Path dir) throws Exception {
        Path model = invoiceForms(dir);
        Path doc = instance(dir, "issued.json", "{ \"Invoice\": { \"IssuedOn\": \"2026-03-01\" } }");
        JsonNode due = computed(model, doc, "Invoice/DueOn");
        assertTrue(due != null && "value".equals(due.get("outcome").asText()), () -> "DueOn computes: " + due);
        assertEquals("01.03.2026", due.get("value").asText(),
                () -> "a dd.MM.yyyy target renders the yyyy-MM-dd source date in ITS format: " + due);
    }

    @Test
    void anErroredComputationPublishesTheAttemptedStoredForm(@TempDir Path dir) throws Exception {
        // CappedAmount's ceiling is 5, so 10 is formally invalid for it while NetAmount takes the same number.
        // The error entry must still carry the value, rendered identically — a consumer diagnosing the failure
        // needs to see WHAT was attempted, and a bare "error" would not tell it.
        Path model = invoiceForms(dir);
        Path doc = instance(dir, "over.json", "{ \"Invoice\": { \"Units\": \"10\" } }");
        JsonNode capped = computed(model, doc, "Invoice/CappedAmount");
        JsonNode net = computed(model, doc, "Invoice/NetAmount");
        assertTrue(capped != null && "error".equals(capped.get("outcome").asText()),
                () -> "10 exceeds CappedAmount's declared maximum: " + capped);
        assertEquals(storedForm("10", TARGET_MIN_SCALE), capped.get("value").asText(),
                () -> "the attempted value is rendered by the same rules: " + capped);
        assertEquals(capped.get("value").asText(), net.get("value").asText(),
                () -> "the error's attempted form equals the sibling target's stored form: " + capped + " / " + net);
    }

    @Test
    void aClearedComputationPublishesNoValueAtAll(@TempDir Path dir) throws Exception {
        // An empty DATE operand computes nothing (unlike an empty number, which is 0) — so this is the arm where
        // `value` is absent, and the schema's "absent when cleared" has a witness rather than only a claim.
        Path model = invoiceForms(dir);
        Path doc = instance(dir, "nodate.json", "{ \"Invoice\": { } }");
        JsonNode due = computed(model, doc, "Invoice/DueOn");
        assertTrue(due != null && "cleared".equals(due.get("outcome").asText()),
                () -> "an empty date operand clears: " + due);
        assertFalse(due.has("value"), () -> "a cleared entry publishes no value key at all: " + due);
    }

    @Test
    void thePublishedSchemaStatesTheFormContractTheseCasesMeasure(@TempDir Path dir) throws Exception {
        // The lock's other half: the behaviour above is only discoverable if `schema model compute` says it.
        // Read the LIVE published schema (not the resource) so this fails when the served contract drifts.
        var res = CliTestSupport.run("schema", "model", "compute");
        assertEquals(0, res.code(), res::err);
        String value = M.readTree(res.out())
                .path("dataSchema").path("$defs").path("computedField").path("properties").path("value")
                .path("description").asText();

        // Case-insensitive on purpose: the schema uppercases its load-bearing words (TARGET, ATTEMPTED) for a
        // human reader, and a guard that demanded one casing would be asserting typography, not content.
        String stated = value.toLowerCase();
        for (String claim : List.of("minfractionaldigits", "plain", "format", "attempted", "cleared")) {
            assertTrue(stated.contains(claim),
                    () -> "the published `value` contract states '" + claim + "': " + value);
        }
    }
}
