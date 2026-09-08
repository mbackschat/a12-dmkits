package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MalformedModelPublicContractTest {

    @Test
    void packagedModelCheckClassifiesMalformedInput(@TempDir Path dir) throws Exception {
        var dmtool = new PackagedDmtool(dir);
        var mapper = new ObjectMapper();
        for (var kind : ModelInputFailureTest.Malformation.values()) {
            Path file = dir.resolve(kind.name() + ".json");
            Files.writeString(file, ModelInputFailureTest.malformed(kind));
            var result = dmtool.run("-m", file.toString(), "model", "check");
            assertEquals(2, result.code(), result::out);
            var envelope = mapper.readTree(result.out());
            assertEquals("refused", envelope.path("outcome").asText(), result::out);
            assertEquals("PRECHECK", envelope.at("/diagnostics/0/source").asText(), result::out);
            assertEquals("RK_MODEL_INPUT_INVALID", envelope.at("/diagnostics/0/code").asText(), result::out);
            assertFalse(envelope.has("verification"), result::out);
            assertFalse(envelope.has("valid"), result::out);
        }
    }
}
