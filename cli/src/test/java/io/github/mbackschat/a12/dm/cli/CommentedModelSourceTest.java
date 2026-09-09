package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A <b>commented DM-JSON model</b> is a legal A12 artifact, so every {@code dmtool} model verb reads one.
 *
 * <p>A12 builds both of its mappers with {@code JsonParser.Feature.ALLOW_COMMENTS} —
 * {@code ObjectMapperProvider.createPreconfiguredObjectMapper} for the model serializer and
 * {@code createObjectMapperForDocSerializer} for documents — so refusing a comment makes a file the A12 tooling
 * round-trips unreadable here (KERNEL-FINDINGS "The canonical Document JSON …"; INTERPRETER-FINDINGS IF228).
 *
 * <p><b>Why this test exists separately from the interpreter's.</b> IF228's first pass fixed the six
 * {@code :interpreter} parse sites behind one shared {@code A12_JSON} and reported the class closed. It was not:
 * {@code core.ModelReader} is a seventh site in the same class, on the JVM path every model verb uses, and it
 * carried A12's number configuration while missing the comment one — the give-away that the mapper had been
 * matched for one concern and not audited for the others. This locks the JVM half end-to-end through the binary.
 *
 * <p>A trailing comma stays a parse error, because A12 refuses one too: the parity is two-sided, never a general
 * leniency.
 */
class CommentedModelSourceTest {

    @TempDir
    Path dir;

    /** The project's ruled order fixture with comments inserted at three structural positions. */
    private Path commentedOrderModel() throws Exception {
        String json = Files.readString(CliTestSupport.orderRuled(dir));
        String commented = "// the order model this workspace prepares\n"
                + json.replaceFirst("\\{", "{\n  /* header first, as A12 writes it */");
        Path file = dir.resolve("commented.dm.json");
        Files.writeString(file, commented);
        return file;
    }

    @Test
    void aCommentedModelDescribesExactlyLikeTheUncommentedOne() throws Exception {
        Path plain = CliTestSupport.orderRuled(dir);
        Path commented = commentedOrderModel();

        CliTestSupport.Result plainRun = CliTestSupport.run("-m", plain.toString(), "model", "describe");
        CliTestSupport.Result commentedRun = CliTestSupport.run("-m", commented.toString(), "model", "describe");

        assertEquals(0, commentedRun.code(), () -> "a commented model must read: " + commentedRun.err());
        assertEquals(plainRun.out(), commentedRun.out(),
                "comments are concrete syntax — they leave no trace in the described model");
    }

    @Test
    void aCommentedModelStillPassesTheKernelConsistencyGate() throws Exception {
        // `model check` is the kernel-backed oracle, so this proves the comment survives all the way through
        // preparation into the kernel's own reader rather than merely past our parse.
        Path commented = commentedOrderModel();

        CliTestSupport.Result res = CliTestSupport.run("-m", commented.toString(), "model", "check");

        assertEquals(0, res.code(), () -> "the kernel accepts the commented model too: " + res.err() + res.out());
    }

    @Test
    void everyVerbThatParsesTheModelReadsACommentedOne() throws Exception {
        // The first pass at this class locked `model describe` and `model check` only, and declared the defect class
        // closed. It was not: four more sites each held their own default mapper, and an independent review found
        // them all through the binary. These are its discriminating cases, so the class is locked by ROLE (parse an
        // A12-owned shape) rather than by the sites someone happened to look at (IF228).
        Path plain = CliTestSupport.orderRuled(dir);
        Path commented = commentedOrderModel();

        // rulekit.validate.RuleValidator — the worst of them: it reported `verification: KERNEL_CONFIRMED` and
        // blamed the candidate rule for a model the kernel had never been handed.
        CliTestSupport.Result check = CliTestSupport.run("-m", commented.toString(), "rule", "check",
                "/Order/DeliveryNotBeforeOrder");
        assertEquals(CliTestSupport.run("-m", plain.toString(), "rule", "check", "/Order/DeliveryNotBeforeOrder").code(),
                check.code(), () -> "rule check must not depend on comments: " + check.out() + check.err());

        // cli.ExportCommand — both readTree sites.
        for (String artifact : new String[] {"rules", "card"}) {
            CliTestSupport.Result export =
                    CliTestSupport.run("-m", commented.toString(), "export", artifact);
            assertEquals(0, export.code(), () -> "export " + artifact + ": " + export.err());
        }
    }

    @Test
    void aTrailingCommaIsStillRefusedBecauseA12RefusesOneToo() throws Exception {
        String json = Files.readString(CliTestSupport.orderRuled(dir));
        Path broken = dir.resolve("trailing-comma.dm.json");
        Files.writeString(broken, json.replaceFirst("\\}\\s*$", ",}"));

        CliTestSupport.Result res = CliTestSupport.run("-m", broken.toString(), "model", "describe");

        assertEquals(2, res.code(), () -> "a trailing comma is not part of A12's leniency: " + res.out());
        var envelope = new ObjectMapper().readTree(res.out());
        assertEquals("RK_MODEL_INPUT_INVALID", envelope.at("/diagnostics/0/code").asText(), res::out);
        assertTrue(res.err().isBlank(), res::err);
    }
}
