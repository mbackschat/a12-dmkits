package io.github.mbackschat.a12.dm.cli.config;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldKind;

/**
 * AP3 closed for the one scalar the kernel stores without checking (CLI-CONFORMANCE-SPEC): a multi-character
 * decimal separator is <b>refused</b> ({@code RK_INVALID_CONFIG_VALUE}), not silently accepted at exit 0.
 * (A bad time zone / condition language is already kernel-rejected on re-bind — a structured envelope too.)
 */
class ConfigValidationTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static Path model(Path dir) throws Exception {
        String json = DocumentModelDraft.named("order").locale("en_US")
                .rootGroup("Order", o -> o.field("Note", FieldKind.STRING))
                .build()
                .toJson();
        Path f = dir.resolve("order.dm.json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    void refusesAMultiCharacterDecimalSeparator(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        String before = Files.readString(m);
        var res = CliTestSupport.run("-m", m.toString(), "config", "modify", "--decimal-separator", "XYZ");
        assertEquals(2, res.code());
        JsonNode j = M.readTree(res.out());
        assertEquals("refused", j.path("outcome").asText());
        assertEquals("RK_INVALID_CONFIG_VALUE", j.path("diagnostics").path(0).path("code").asText());
        assertEquals(before, Files.readString(m), "a refused config change mutates nothing");
    }

    @Test
    void acceptsAValidSingleCharacterSeparator(@TempDir Path dir) throws Exception {
        Path m = model(dir);
        var res = CliTestSupport.run("-m", m.toString(), "config", "modify", "--decimal-separator", ",");
        assertEquals(0, res.code(), res::err);
        assertEquals("applied", M.readTree(res.out()).path("outcome").asText());
    }
}
