package io.github.mbackschat.a12.dm.cli.field;

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
 * A spec's {@code kind} has one spelling, the one {@code field read} and {@code typedef read} report
 * ({@code ValueKind}), so a read-back value can be pasted into a spec. The compact spellings the schema once
 * documented ({@code DATERANGE}, {@code ENUM}, …) and case variants were accepted as silent aliases (CG28);
 * they are refused with a corrective naming the one spelling, on every spec route.
 */
class FieldKindSpellingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path model(Path dir) {
        Path m = dir.resolve("shop.dm.json");
        CliTestSupport.run("model", "new", "--id", "shop", "--locale", "en_US", "--root", "Order", "-o", m.toString());
        return m;
    }

    private static CliTestSupport.Result fieldAdd(Path m, Path dir, String spec) throws Exception {
        Path file = Files.createTempFile(dir, "spec", ".json");
        Files.writeString(file, spec);
        return CliTestSupport.run("-m", m.toString(), "field", "add", file.toString(), "--dry-run");
    }

    @Test
    void theReadBackSpellingIsAccepted(@TempDir Path dir) throws Exception {
        CliTestSupport.Result res = fieldAdd(model(dir), dir, """
                {"group":"/Order","name":"Stay","kind":"DATE_RANGE",
                 "dateRange":{"format":"dd.MM.yyyy","separator":"-"}}""");

        assertEquals(0, res.code(), res::out);
        assertEquals("DATE_RANGE", MAPPER.readTree(res.out()).path("changed").path("kind").asText());
    }

    @Test
    void eachCompactSpellingIsRefusedNamingTheOneSpelling(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        assertRefused(fieldAdd(m, dir, """
                {"group":"/Order","name":"Stay","kind":"DATERANGE",
                 "dateRange":{"format":"dd.MM.yyyy","separator":"-"}}"""), "DATE_RANGE");
        assertRefused(fieldAdd(m, dir, """
                {"group":"/Order","name":"Seen","kind":"DATETIME","dateTime":{"format":"yyyy-MM-dd'T'HH:mm:ss"}}"""),
                "DATE_TIME");
        assertRefused(fieldAdd(m, dir, """
                {"group":"/Order","name":"Month","kind":"DATEFRAGMENT","dateFragment":{"format":"MM"}}"""),
                "DATE_FRAGMENT");
        assertRefused(fieldAdd(m, dir, """
                {"group":"/Order","name":"Tier","kind":"ENUM","enum":{"values":[{"value":"GOLD"}]}}"""),
                "ENUMERATION");
    }

    @Test
    void aCaseVariantIsRefusedNamingTheOneSpelling(@TempDir Path dir) throws Exception {
        assertRefused(fieldAdd(model(dir), dir, """
                {"group":"/Order","name":"Note","kind":"string"}"""), "STRING");
    }

    @Test
    void typedefAddRefusesTheSameWay(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        Path file = dir.resolve("type.json");
        Files.writeString(file, """
                {"kind":"ENUM","enum":{"values":[{"value":"GOLD"}]}}""");

        assertRefused(CliTestSupport.run("-m", m.toString(), "typedef", "add", "--id", "Tier", file.toString(),
                "--dry-run"), "ENUMERATION");
    }

    private static void assertRefused(CliTestSupport.Result res, String spelling) throws Exception {
        assertEquals(1, res.code(), res::out);
        JsonNode d = MAPPER.readTree(res.out()).path("diagnostics").path(0);
        assertEquals("RK_INVALID_VALUE", d.path("code").asText(), res::out);
        assertTrue(d.path("summary").asText().contains("\"" + spelling + "\""), res::out);
    }
}
