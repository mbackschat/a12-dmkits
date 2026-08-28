package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CLI-SPEC §7's "`apply` tolerates transient-invalid states between ops" is true but <b>bounded</b>, and this
 * locks where the boundary runs. An op whose own work needs a {@code KernelAdapter} builds one from the session
 * <em>as it stands at its turn</em>, so it surfaces a mid-sequence inconsistency there instead of at the terminal
 * gate. The pure-JSON reads go through {@code ModelDocument} and stay tolerant.
 *
 * <p>The discriminator is one transiently-inconsistent session — a computation whose message covers only one of
 * the model's two locales, which the kernel rejects with {@code MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING} — read
 * once by each kind of following op. The spec paragraph quotes this measurement, so it needs a test that fails
 * if the boundary moves; the tolerant arm is the positive control that keeps the binding arm from passing
 * vacuously (a build where <em>everything</em> failed early would satisfy the first assertion alone).
 */
class ApplyMidSequenceBindBoundaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A computation carrying only en_US on the bilingual subscription fixture — kernel-inconsistent, addable. */
    private static final String TRANSIENTLY_INCONSISTENT_ADD = """
            {"target":"computation","op":"add","computedField":"/Subscription/Billing/EffectiveFee",
             "alternatives":[{"operation":"[/Subscription/Billing/BaseFee] / 3"}],
             "errorCodesToSuppress":["MVK_INVALID_COMPARE_DEC_PLACES"],
             "messages":[{"locale":"en_US","text":"Only English."}]}""";

    private JsonNode applyWithFollowingOp(Path dir, String name, String followingOp) throws Exception {
        Path model = dir.resolve(name + ".dm.json");
        Files.writeString(model, CliTestSupport.subscriptionJson());
        Path ops = dir.resolve(name + "-ops.json");
        Files.writeString(ops, "[" + TRANSIENTLY_INCONSISTENT_ADD + "," + followingOp + "]");
        return MAPPER.readTree(CliTestSupport.run("-m", model.toString(), "apply", ops.toString(), "--dry-run")
                .out()).path("data");
    }

    @Test
    void anOpThatBindsTheSessionSurfacesTheInconsistencyAtItsOwnTurn(@TempDir Path dir) throws Exception {
        JsonNode data = applyWithFollowingOp(dir, "binding",
                "{\"target\":\"field\",\"op\":\"remove\",\"fieldPathInModel\":\"/Subscription/AutoRenew\"}");

        assertEquals(1, data.path("failedAt").asInt(-1), () -> "the binding op fails at its own turn: " + data);
        assertFalse(data.path("committed").asBoolean(), "the sequence rolled back");
        JsonNode diagnostic = data.path("results").get(1).path("diagnostics").get(0);
        assertEquals("RK_APPLY_OP_FAILED", diagnostic.path("code").asText());
        assertTrue(diagnostic.path("summary").asText().contains("MVK_ERROR_MESSAGE_FOR_LANGUAGE_MISSING"),
                () -> "the kernel's own cause rides the mid-sequence failure: " + diagnostic);
    }

    @Test
    void aPureJsonReadStaysTolerantOfTheSameTransientState(@TempDir Path dir) throws Exception {
        JsonNode data = applyWithFollowingOp(dir, "tolerant",
                "{\"target\":\"field\",\"op\":\"read\",\"fieldPathInModel\":\"/Subscription/AutoRenew\"}");

        assertTrue(data.path("failedAt").isNull(),
                () -> "a ModelDocument read does not gate the transient state: " + data);
        assertTrue(data.path("results").get(1).path("data").has("kind"),
                () -> "and it still returns its payload: " + data);
    }

    /**
     * The rule/computation reads bind, because their payload is an AST conversion — so they sit on the binding
     * side of the boundary, not with their pure-JSON read siblings. Stated in §7; measured here.
     */
    @Test
    void aComputationReadSitsOnTheBindingSideOfTheBoundary(@TempDir Path dir) throws Exception {
        JsonNode data = applyWithFollowingOp(dir, "reading",
                "{\"target\":\"computation\",\"op\":\"read\","
                        + "\"computationPathInModel\":\"/Subscription/Billing/EffectiveFeeComp\"}");

        assertEquals(1, data.path("failedAt").asInt(-1),
                () -> "the AST-converting read binds and therefore gates: " + data);
    }
}
