package io.github.mbackschat.a12.dm.cli.computation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * A computation's {@code roundingMode} through every CLI surface that carries a computation: the spec it is
 * authored in, the stored member, and the read, explain and report outputs a reader re-derives it from.
 *
 * <p>The kernel is the oracle for what is admitted: the mode beside a band persists, and beside an unbanded
 * alternative is refused with A12's own class (KERNEL-FINDINGS KF297), which the CLI publishes rather than
 * pre-empts, because the spec path never builds the typed object.
 */
class ComputationRoundingModeCliTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COMPUTATION = "/Subscription/Billing/EffectiveFeeComp";

    @Test
    void theModeIsStoredAndEveryReadSurfaceShowsIt(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        var add = CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "spec.json", spec("\"DiffersWithToleranceRange1\"", "\"RoundUp\"")).toString());
        assertEquals(0, add.code(), add::err);
        assertTrue(Files.readString(model).contains("\"roundingMode\" : \"RoundUp\""), "the member is stored");

        JsonNode read = data(CliTestSupport.run("-m", model.toString(), "computation", "read", COMPUTATION));
        assertTrue(read.path("converted").asBoolean(), () -> "a rounding computation reads typed: " + read);
        assertEquals("RoundUp", read.path("roundingMode").asText(), read::toString);

        JsonNode explain = data(CliTestSupport.run("-m", model.toString(), "computation", "explain", COMPUTATION));
        assertEquals("RoundUp", explain.path("roundingMode").asText(), explain::toString);

        JsonNode report = data(CliTestSupport.run("-m", model.toString(), "model", "report"));
        assertEquals("RoundUp", report.path("computations").findValue("roundingMode").asText(), report::toString);
    }

    /** The spec is a whole re-expression, as its schema says of every optional property. */
    @Test
    void aModifyThatOmitsTheModeMakesTheComputationExact(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        assertEquals(0, CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "spec.json", spec("\"DiffersWithToleranceRange1\"", "\"RoundDown\"")).toString()).code());

        var modify = CliTestSupport.run("-m", model.toString(), "computation", "modify", COMPUTATION, "--spec",
                write(dir, "respec.json", spec("\"DiffersWithToleranceRange1\"", null)).toString());
        assertEquals(0, modify.code(), modify::err);
        assertFalse(Files.readString(model).contains("roundingMode"), "Exact is never stored");
        JsonNode read = data(CliTestSupport.run("-m", model.toString(), "computation", "read", COMPUTATION));
        assertTrue(read.path("roundingMode").isMissingNode(), read::toString);
    }

    @Test
    void theKernelRefusesTheModeBesideAnUnbandedAlternative(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        String before = Files.readString(model);
        var add = CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "spec.json", spec(null, "\"RoundUp\"")).toString());
        assertNotEquals(0, add.code(), add::out);
        assertTrue(add.out().contains("MVK_ERROR_WRONG_ROUNDING_MODE"), add::out);
        assertEquals(before, Files.readString(model), "a refused add writes nothing");
    }

    /** A12 matches the spelling exactly; the constant NAME is not one, so the published schema refuses it. */
    @Test
    void aConstantNameIsNotASpelling(@TempDir Path dir) throws Exception {
        Path model = write(dir, "model.json", CliTestSupport.subscriptionJson());
        var add = CliTestSupport.run("-m", model.toString(), "computation", "add",
                write(dir, "spec.json", spec("\"DiffersWithToleranceRange1\"", "\"ROUND_UP\"")).toString());
        assertNotEquals(0, add.code(), add::out);
        assertTrue(add.out().contains("roundingMode"), () -> "the refusal names the member: " + add.out());
    }

    /** A spec for {@code EffectiveFee = BaseFee}, with the band and mode given as raw JSON, or omitted when null. */
    private static String spec(String toleranceRangeOp, String roundingMode) {
        String band = toleranceRangeOp == null ? "" : ", \"toleranceRangeOp\": " + toleranceRangeOp;
        String mode = roundingMode == null ? "" : "\"roundingMode\": " + roundingMode + ",";
        return """
                { "computedField": "/Subscription/Billing/EffectiveFee",
                  "alternatives": [ { "operation": "[BaseFee]"%s } ],
                  %s
                  "messages": [
                    {"locale":"en_US","text":"Effective fee follows base."},
                    {"locale":"de_DE","text":"Effektivgebühr folgt Basis."}
                  ] }
                """.formatted(band, mode);
    }

    private static Path write(Path dir, String name, String content) throws Exception {
        Path path = dir.resolve(name);
        Files.writeString(path, content);
        return path;
    }

    private static JsonNode data(CliTestSupport.Result result) throws Exception {
        assertEquals(0, result.code(), result::err);
        return MAPPER.readTree(result.out()).path("data");
    }
}
