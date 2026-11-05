package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Class-guard (probe DX, root-cause-generalization lock): a locator-bearing READ verb pointed at a MISSING
 * element returns a <b>structured {@code refused} envelope</b> — the matching {@code RK_NO_SUCH_*} code, a
 * {@code where}, and a corrective {@code fix} — never a plain-text stderr line. The reported symptom was
 * {@code field read} of a bad path printing prose + exit 2 (unparseable, no fix), but the defect is the whole
 * read family: {@code field}/{@code group}/{@code typedef}/{@code rule}/{@code computation} <b>read</b>, and
 * (F5) the projection verbs {@code rule}/{@code computation} <b>explain</b> + <b>format</b> and
 * {@code include read} all once fell back to {@code fail()}. The exit code stays non-zero; only the OUTPUT
 * becomes machine-readable.
 */
class ReadMissingTargetEnvelopeTest {

    private static final ObjectMapper M = new ObjectMapper();

    private record Case(String target, String op, String arg, String code) {}

    @Test
    void everyReadOfAMissingTargetIsAStructuredRefusal(@TempDir Path dir) throws Exception {
        Path model = CliTestSupport.orderRuled(dir);   // has /Order + rules; no such field/group/rule/typedef/comp below
        Case[] cases = {
                new Case("field", "read", "/Order/Nope", "RK_NO_SUCH_FIELD"),
                new Case("group", "read", "/Order/Nope", "RK_NO_SUCH_GROUP"),
                new Case("typedef", "read", "Nope", "RK_NO_SUCH_TYPEDEF"),
                new Case("rule", "read", "/Order/Nope", "RK_NO_SUCH_RULE"),
                new Case("computation", "read", "/Order/Nope", "RK_NO_SUCH_COMPUTATION"),
                // F5 — the projection read verbs join the family
                new Case("rule", "explain", "/Order/Nope", "RK_NO_SUCH_RULE"),
                new Case("rule", "format", "/Order/Nope", "RK_NO_SUCH_RULE"),
                new Case("computation", "explain", "/Order/Nope", "RK_NO_SUCH_COMPUTATION"),
                new Case("computation", "format", "/Order/Nope", "RK_NO_SUCH_COMPUTATION"),
                new Case("include", "read", "nope", "RK_NO_SUCH_INCLUDE"),
                new Case("criterion", "read", "NoSuchKey", "RK_NO_SUCH_UNIQUENESS_CRITERION"),
        };
        for (Case c : cases) {
            var res = CliTestSupport.run("-m", model.toString(), c.target(), c.op(), c.arg());
            String label = c.target() + " " + c.op();
            assertTrue(res.code() != 0, () -> label + " of a missing target exits non-zero: " + res.out());
            JsonNode j = M.readTree(res.out());   // MUST parse — was plain text on stderr, out empty
            assertEquals("refused", j.path("outcome").asText(),
                    () -> label + " of a missing target is a structured refusal: " + res.out());
            JsonNode diag = j.path("diagnostics").path(0);
            assertEquals(c.code(), diag.path("code").asText(),
                    () -> label + " refusal carries " + c.code() + ": " + res.out());
            assertTrue(!diag.path("fix").asText().isBlank(),
                    () -> label + " refusal carries a corrective fix: " + res.out());
        }
    }
}
