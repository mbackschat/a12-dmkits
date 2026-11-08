package io.github.mbackschat.a12.dm.cli.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

/**
 * {@code MVK_INVALID_ENTITY} names the token the kernel could not resolve as a group or field. When that token
 * is a bare number it is a constant written without quotes ({@code Time(9)} for {@code Time("9")}), and an
 * absolute path cannot help: the 2026-11-08 task 08 probe read only that advice and concluded {@code Time} did
 * not exist (CG29). The code's one corrective names the numeric case, so the portable checker's projection of
 * it carries the same sentence.
 */
class UnquotedConstantCorrectiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) throws Exception {
        Path m = dir.resolve("rental.dm.json");
        CliTestSupport.run("model", "new", "--id", "rental", "--locale", "en_US", "--root", "Rental", "-o", m.toString());
        Path spec = dir.resolve("pickup.json");
        Files.writeString(spec, """
                {"group":"/Rental","name":"PickupTime","kind":"TIME","time":{"format":"HH:mm:ss"}}""");
        assertEquals(0, CliTestSupport.run("-m", m.toString(), "field", "add", spec.toString()).code());
        return m;
    }

    private static JsonNode check(Path m, String condition) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("-m", m.toString(), "rule", "check",
                "--field", "/Rental/PickupTime", "--condition", condition, "--code", "EARLY_PICKUP");
        return MAPPER.readTree(res.out()).path("diagnostics").path(0);
    }

    @Test
    void aBareNumberGetsTheQuotedConstantCorrective(@TempDir Path dir) throws Exception {
        JsonNode d = check(model(dir), "FieldFilled(PickupTime) And [PickupTime] < Time(9)");

        assertEquals("MVK_INVALID_ENTITY", d.path("code").asText(), d::toString);
        assertTrue(d.path("fix").asText().contains("If the reported name is a NUMBER"), d::toString);
        assertTrue(d.path("fix").asText().contains("Time(\"9\")"), d::toString);
    }

    @Test
    void thePortableCheckerCarriesTheSameCorrective(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = CliTestSupport.run("-m", model(dir).toString(), "rule", "check",
                "--field", "/Rental/PickupTime", "--condition", "FieldFilled(PickupTime) And [PickupTime] < Time(9)",
                "--code", "EARLY_PICKUP", "--engine", "portable");
        JsonNode finding = MAPPER.readTree(res.out()).path("data").path("introduced").path(0);

        assertEquals("MVK_INVALID_ENTITY", finding.path("code").asText(), res::out);
        assertTrue(finding.path("corrective").path("fix").asText().contains("Time(\"9\")"), res::out);
    }

    @Test
    void theQuotedConstantIsAccepted(@TempDir Path dir) throws Exception {
        assertTrue(check(model(dir), "FieldFilled(PickupTime) And [PickupTime] < Time(\"9\")").isMissingNode(),
                "positive control: the corrective's own spelling is kernel-valid");
    }
}
