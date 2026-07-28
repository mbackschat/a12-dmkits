package io.github.mbackschat.a12.dm.cli.field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A declared {@code minValue}/{@code maxValue} keeps its authored SCALE — through the read-back, and through an
 * edit that has nothing to do with it.
 *
 * <p>{@code core.ModelReader} goes out of its way to preserve this on its own read path: its mapper enables
 * {@code USE_BIG_DECIMAL_FOR_FLOATS} and disables {@code STRIP_TRAILING_BIGDECIMAL_ZEROES} so that "a declared
 * {@code minValue}/{@code maxValue} reads back at the authored literal's exact scale, matching the kernel". The
 * <em>authoring</em> surface has to hold the same line — otherwise the tool destroys on every edit exactly what
 * that mapper is careful to preserve.
 *
 * <p><b>The defect this locks (measured through the binary, 2026-07-28).</b> {@code modelkit.ModelDocument}
 * parsed the whole model with a DEFAULT {@code ObjectMapper}, so every model number became a {@code double} on
 * load. Two consequences, the second much worse than the first: {@code field read} echoed {@code min} as
 * {@code "0.5"} for an authored {@code 0.50} (via {@code DocumentModelDraft.readTypeConfig}'s
 * {@code asText()}), and — because the authoring surface writes the tree back — adding an <em>unrelated</em>
 * STRING field silently rewrote a pre-existing {@code "minValue" : 0.50} to {@code 0.5} on disk. That is silent
 * corruption of A12-owned content by an edit the user never asked for, on every write verb that goes through
 * this surface.
 *
 * <p>Same class as the instance-reader defect fixed in {@code 4b144f89} and the comment sites of
 * INTERPRETER-FINDINGS IF228: a default Jackson mapper standing where A12's configuration is the contract.
 */
class NumberSpecScaleFidelityTest {

    private static final ObjectMapper M = new ObjectMapper();

    @TempDir
    Path dir;

    private Path numberFieldModel(String minLiteral, String maxLiteral) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("spec.json");
        Files.writeString(spec, """
                {"group":"/Order","name":"Threshold","kind":"NUMBER",
                 "number":{"min":%s,"max":%s,"maxFractionalDigits":2,"unit":"AMOUNT"}}"""
                .formatted(minLiteral, maxLiteral));

        CliTestSupport.Result add =
                CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString());
        assertEquals(0, add.code(), () -> "field add succeeds: " + add.err() + add.out());
        return model;
    }

    private JsonNode readNumberConfig(Path model, String fieldPath) {
        CliTestSupport.Result read = CliTestSupport.run("-m", model.toString(), "field", "read", fieldPath);
        assertEquals(0, read.code(), () -> "field read succeeds: " + read.err());
        try {
            return M.readTree(read.out()).path("data").path("number");
        } catch (Exception e) {
            throw new AssertionError("unreadable field read output: " + read.out(), e);
        }
    }

    @Test
    void theAuthoredScaleSurvivesTheReadBack() throws Exception {
        Path model = numberFieldModel("\"0.50\"", "\"100.00\"");

        JsonNode number = readNumberConfig(model, "/Order/Threshold");

        assertEquals("0.50", number.path("min").asText(), "field read must echo the authored scale, not 0.5");
        assertEquals("100.00", number.path("max").asText(), "likewise 100.00, not 100.0");
    }

    @Test
    void anUnrelatedEditDoesNotRewriteAnExistingBound() throws Exception {
        // The sharp half: the edit below touches a different field entirely. A declared bound must not change
        // because the authoring surface re-serialized the model around it.
        Path model = numberFieldModel("\"0.50\"", "\"100.00\"");
        Path other = dir.resolve("other.json");
        Files.writeString(other, """
                {"group":"/Order","name":"Note9","kind":"STRING"}""");

        CliTestSupport.Result add =
                CliTestSupport.run("-m", model.toString(), "field", "add", other.toString());
        assertEquals(0, add.code(), () -> "the unrelated add succeeds: " + add.err());

        assertTrue(Files.readString(model).contains("0.50"),
                "an unrelated edit must not rewrite an existing minValue's scale");
        assertEquals("0.50", readNumberConfig(model, "/Order/Threshold").path("min").asText());
    }

    @Test
    void anImportedJsonSchemaBoundKeepsItsAuthoredScale() throws Exception {
        // `model import-jsonschema` is a production verb whose input numbers land in the DM-JSON it writes, so its
        // mapper owes the same fidelity even though a JSON Schema is not itself an A12 shape. It used to emit
        // `"minValue" : 0.5` for an authored 0.50, and `1.2345678901234568E+21` — the DoubleNode signature — for a
        // bound beyond double precision, silently destroying a constraint the user had declared. API-FINDINGS 103.
        Path schema = dir.resolve("schema.json");
        Files.writeString(schema, """
                {"$schema":"http://json-schema.org/draft-07/schema#","title":"Ord","type":"object",
                 "properties":{"Amount":{"type":"number","minimum":0.50,"maximum":10.00},
                               "Huge":{"type":"number","maximum":1234567890123456789012.5}}}""");

        CliTestSupport.Result out = CliTestSupport.run("model", "import-jsonschema", "--schema", schema.toString());
        assertEquals(0, out.code(), () -> "import succeeds: " + out.err());

        assertTrue(out.out().contains("0.50"), () -> "an authored 0.50 bound must not degrade to 0.5: " + out.out());
        assertTrue(out.out().contains("10.00"), () -> "nor 10.00 to 10.0: " + out.out());
        assertTrue(out.out().contains("1234567890123456789012.5"),
                () -> "a beyond-double bound must survive, never as scientific notation: " + out.out());
    }

    @Test
    void aNativeJsonNumberIsRefusedWithATypeCorrective() throws Exception {
        // The spec contract declares these as strings, and the precheck says so rather than coercing — worth
        // pinning, because a silent coercion is exactly where a scale would be lost without a diagnostic.
        Path model = CliTestSupport.orderRuled(dir);
        Path spec = dir.resolve("native.json");
        Files.writeString(spec, """
                {"group":"/Order","name":"Threshold","kind":"NUMBER","number":{"min":0.50,"unit":"AMOUNT"}}""");

        CliTestSupport.Result add =
                CliTestSupport.run("-m", model.toString(), "field", "add", spec.toString());

        assertEquals(1, add.code(), () -> "a native number is refused: " + add.out());
        assertTrue(add.out().contains("RK_INVALID_VALUE"), () -> "with a typed corrective: " + add.out());
        assertTrue(add.out().contains("expected a value of type string"), () -> add.out());
    }
}
