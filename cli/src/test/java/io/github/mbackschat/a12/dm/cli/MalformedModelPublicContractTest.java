package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /**
     * A model kernel 31.1.1 reads and then cannot build (KERNEL-FINDINGS KF654) is reported once, on stdout. The
     * kernel's expand copy also kills its own reader thread on that input, and left alone the JVM prints that
     * thread's trace to stderr; only the packaged launcher shows it, because the in-process suite never reaches
     * the process's default handler.
     */
    @Test
    void packagedUnbuildableModelIsReportedWithoutAStrayKernelThreadTrace(@TempDir Path dir) throws Exception {
        var dmtool = new PackagedDmtool(dir);
        for (var kind : ModelInputFailureTest.Unbuildable.values()) {
            Path file = dir.resolve(kind.name() + ".json");
            Files.writeString(file, ModelInputFailureTest.unbuildable(kind));
            for (String op : new String[] {"check", "read"}) {
                var result = dmtool.run("-m", file.toString(), "model", op);
                assertEquals(1, result.code(), result::out);
                assertTrue(result.out().contains("the kernel could not build this model"), result::out);
                if (op.equals("check")) {
                    // The corrective rides on A12's text, so the packaged (and native) artifact must still name it.
                    assertTrue(result.out().contains(kind.member), () -> kind + ": " + result.out());
                }
                assertFalse(result.err().contains("Exception in thread"), () -> kind + " " + op + ": " + result.err());
            }
        }
    }
}
