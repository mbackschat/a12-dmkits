package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The edit gate must report the tier that actually refused. Its {@code catch (RuntimeException)} funnelled
 * <b>three different origins</b> into one {@code ModelConsistencyException}: the kernel's deserialize/expand
 * refusal (which it is for), our own {@code toJson()} serialization, and our own resolver's
 * {@link io.github.mbackschat.a12.dm.core.UnresolvedReferenceException} — so an unresolvable {@code include}
 * hit at an <em>edit</em> was published as a kernel consistency rejection, while the identical model read by
 * {@code model read} was published as {@code RK_UNRESOLVED_REFERENCE} (CLI-SPEC §"exception seam", the
 * documented contract for that exception).
 *
 * <p>Two things made this worse than a wrong code. The rejection basis is derived from the exception's type, so
 * the mislabelled tier also published {@code verification} — a claim that the kernel arbitrated an outcome our
 * own resolver decided. And the contract's own table already said what should happen, which is why the fix is
 * to stop conflating rather than to widen the table.
 *
 * @see InvalidModelLoadDiagnosticTest#strictReaderPreservesAnUnresolvedIncludeAsTheCentralDomainRejection the
 *      read-path sibling this must now agree with — same model, same exception, same envelope
 */
class EditGateRejectionTierTest {

    private static final ObjectMapper M = new ObjectMapper();

    /** A model that deserializes fine but whose {@code include} names an id no workspace can serve. */
    private static Path hostWithUnresolvableInclude(Path dir) throws Exception {
        String json = DocumentModelDraft.named("host").locale("en_US")
                .include("missing-library", "missing-library")
                .rootGroup("Host", host -> host.mount("Missing", "missing-library"))
                .build().toJson();
        Path model = dir.resolve("host.json");
        Files.writeString(model, json);
        return model;
    }

    @Test
    void anUnresolvableIncludeHitAtTheEditGateKeepsItsOwnDiagnostic(@TempDir Path dir) throws Exception {
        Path model = hostWithUnresolvableInclude(dir);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Host", "--name", "Added", "--kind", "STRING");

        assertEquals(1, res.code(), () -> "an unresolvable reference is an input rejection: " + res.out());
        var env = M.readTree(res.out());
        assertEquals("rejected", env.path("outcome").asText(), res::out);
        assertEquals("RK_UNRESOLVED_REFERENCE", env.path("diagnostics").path(0).path("code").asText(),
                () -> "the edit gate must report the tier that refused, not relabel it as a consistency "
                        + "failure: " + res.out());
    }

    /**
     * The half that matters most: our resolver's refusal must not be published as the kernel's arbitration.
     * {@code verification} answers "who arbitrated this invocation", so a tier we decided ourselves may not
     * carry it — the same forged-provenance defect as an ambient mode, arriving by a mislabelled exception.
     */
    @Test
    void andMakesNoVerificationClaimForARefusalTheKernelNeverMade(@TempDir Path dir) throws Exception {
        Path model = hostWithUnresolvableInclude(dir);

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/Host", "--name", "Added", "--kind", "STRING");

        var env = M.readTree(res.out());
        assertFalse(env.has("verification"),
                () -> "our own resolver refused, so there is no gate receipt to publish: " + res.out());
        assertFalse(env.has("valid"),
                () -> "and no consistency verdict was reached either: " + res.out());
    }

    /**
     * The other half of the same gate: a refusal the <b>kernel</b> really did make, which must keep its receipt
     * <em>and</em> agree with it. Without this the fix above could be satisfied by dropping the kernel basis
     * everywhere — trading one wrong claim for a silent one.
     *
     * <p>The case is the <b>zero-notification</b> tier — a duplicate element name, refused while deserializing,
     * before {@code checkConsistency} can name an {@code MVK_*} code. That is the tier the wrapper exists for, and
     * the one whose synthesized diagnostic used to be labelled {@code PRECHECK} while the same envelope published
     * {@code verification: KERNEL_CONFIRMED}. Chosen over a {@code checkConsistency} refusal deliberately: that
     * path is already {@code KERNEL}-sourced by {@code KernelDiagnostics.enriched}, so it would have passed
     * whether or not the origin was derived — a green with a cause you cannot name.
     */
    @Test
    void aRefusalTheKernelDidMakeIsSourcedToTheKernelEvenWithNoMvkCode(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("dup.json");
        Files.writeString(model, """
                { "header": { "id": "dup", "modelType": "document", "modelVersion": "28.4.0",
                    "locales": [ { "code": "en_US" } ], "labels": [], "annotations": [], "modelReferences": [] },
                  "content": {
                    "modelInfo": { "name": "dup", "variant": "cockpit", "revision": "r", "immutable": false },
                    "modelConfig": { "decimalSeparator": ".", "timeZone": "Europe/Berlin",
                      "conditionLanguage": { "code": "en_US" }, "fieldRefByShortNameAllowed": true },
                    "modelRoot": { "rootGroups": [ { "type": "Group", "id": "/R", "name": "R", "Group": {
                      "repeatability": 1, "elements": [
                        { "type": "Field", "id": "/R/A", "name": "A", "Field": { "fieldType": { "type": "StringType" } } },
                        { "type": "Field", "id": "/R/A", "name": "A", "Field": { "fieldType": { "type": "StringType" } } }
                      ] } } ] } } }""");

        CliTestSupport.Result res = CliTestSupport.run("-m", model.toString(),
                "field", "add", "--group", "/R", "--name", "Added", "--kind", "STRING");

        assertEquals(1, res.code(), res::out);
        var env = M.readTree(res.out());
        assertEquals("rejected", env.path("outcome").asText(), res::out);
        assertEquals("KERNEL_CONFIRMED", env.path("verification").asText(),
                () -> "the kernel really did arbitrate this one, so the receipt stays: " + res.out());
        assertEquals("KERNEL", env.path("diagnostics").path(0).path("source").asText(),
                () -> "and the diagnostic names the engine that refused, so it cannot contradict the receipt "
                        + "beside it: " + res.out());
        assertTrue(env.path("diagnostics").path(0).path("code").isMissingNode(),
                () -> "this tier has no MVK_* code to give — that is the point, not a gap: " + res.out());
    }
}
