package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Class-guard (probe DX, root-cause-generalization lock): a {@code <target> read} of a MISSING element returns
 * a <b>structured {@code refused} envelope</b> — the matching {@code RK_NO_SUCH_*} code, a {@code where}, and a
 * corrective {@code fix} — never a plain-text stderr line. The reported symptom was {@code field read} of a bad
 * path printing prose + exit 2 (unparseable, no fix), but the defect was the whole read family: {@code field} /
 * {@code group} / {@code typedef} / {@code rule} / {@code computation} read all fell back to {@code fail()}. The
 * exit code stays non-zero; only the OUTPUT becomes machine-readable.
 */
class ReadMissingTargetEnvelopeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private record Case(String target, String arg, String code) {}

    @Test
    void everyReadOfAMissingTargetIsAStructuredRefusal(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // has /Order + rules; no such field/group/rule/typedef/comp below
        Case[] cases = {
                new Case("field", "/Order/Nope", "RK_NO_SUCH_FIELD"),
                new Case("group", "/Order/Nope", "RK_NO_SUCH_GROUP"),
                new Case("typedef", "Nope", "RK_NO_SUCH_TYPEDEF"),
                new Case("rule", "/Order/Nope", "RK_NO_SUCH_RULE"),
                new Case("computation", "/Order/Nope", "RK_NO_SUCH_COMPUTATION"),
        };
        for (Case c : cases) {
            var res = CliTestSupport.run("-m", model.toString(), c.target(), "read", c.arg());
            assertTrue(res.code() != 0, () -> c.target() + " read of a missing target exits non-zero: " + res.out());
            JsonNode j = M.readTree(res.out());   // MUST parse — was plain text on stderr, out empty
            assertEquals("refused", j.path("outcome").asText(),
                    () -> c.target() + " read of a missing target is a structured refusal: " + res.out());
            JsonNode diag = j.path("diagnostics").path(0);
            assertEquals(c.code(), diag.path("code").asText(),
                    () -> c.target() + " read refusal carries " + c.code() + ": " + res.out());
            assertTrue(!diag.path("fix").asText().isBlank(),
                    () -> c.target() + " read refusal carries a corrective fix: " + res.out());
        }
    }
}
