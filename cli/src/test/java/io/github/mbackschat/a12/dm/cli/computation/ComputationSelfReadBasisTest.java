package io.github.mbackschat.a12.dm.cli.computation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mbackschat.a12.dm.cli.CliTestSupport;
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl;
import io.github.mbackschat.a12.dm.rulekit.validate.PrecheckException;

/**
 * <b>The same refused shape has two different refusers, and only one of them is the kernel.</b> A computation
 * whose operation reads its own computed field is refused by {@code Computation.Builder.build()} in the typed
 * library — a local pre-check that throws before a draft exists — and independently by the kernel. Which one
 * answers depends on the ROUTE, and the two arms are asserted together here so they cannot drift apart.
 *
 * <p><b>Why this test exists.</b> A 2026-08-29 handback told a12-kernel-lean that the
 * {@code MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD} they observed from {@code computation add --dry-run} was
 * "ours, not the kernel's", which contradicted the envelope's own {@code source: KERNEL} +
 * {@code verification: KERNEL_CONFIRMED}. The envelope was right. {@code computation add} never constructs a
 * typed {@code Computation}: {@code ComputationSpec.parse} builds a {@code DraftComputation} of raw DSL
 * strings, and {@code RuleValidator} serializes that straight into DM-JSON for {@code checkConsistency}, so
 * the builder's pre-check is not on that path at all (source-verified 2026-08-30).
 *
 * <p><b>The generalization worth keeping</b>, and the reason the mistake was easy: the MVK-LEDGER's
 * {@code pre-checked} bucket describes the TYPED library surface ({@code build()} / handle creation). dmtool's
 * DSL-bearing authoring verbs bypass those pre-checks by construction, because they carry DSL text rather than
 * typed objects — so a ledger row saying "pre-checked" says nothing about what the CLI reports for that code.
 *
 * <p><b>One verb is the exception, and stating the rule without it was the review's finding:</b>
 * {@code patterns} instantiates the typed object, so the same pre-check DOES fire there — see
 * {@code field.SpecValueRefusalEnvelopeTest.theScaffoldingVerbPublishesItsPrecheckInsteadOfPrintingAKernelCode},
 * which pins that it now publishes rather than prints.
 */
class ComputationSelfReadBasisTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PROBE = DocumentModelDraft.named("Probe_DM").locale("en_US")
            .rootGroup("Probe", p -> p
                    .group("Store", s -> s.numberField("RangeTarget", n -> n.fractionDigits(0)))
                    .group("Alpha", a -> a.numberField("AlphaOne", n -> n.fractionDigits(0))))
            .build().toJson();

    /** The CLI route: the kernel arbitrates, and the envelope says so. */
    @Test
    void theCliRoutePublishesAKernelBasisBecauseTheBuilderIsNotOnIt(@TempDir Path dir) throws Exception {
        Path model = dir.resolve("Probe_DM.json");
        Files.writeString(model, PROBE);
        Path spec = dir.resolve("selfread.json");
        Files.writeString(spec, """
                { "computedField": "/Probe/Store/RangeTarget",
                  "group": "/Probe/Store",
                  "alternatives": [ { "operation":
                      "FirstFilledValue(/Probe/Alpha/AlphaOne, /Probe/Store/RangeTarget)" } ],
                  "messages": [ {"locale":"en_US","text":"probe"} ] }""");

        CliTestSupport.Result result = CliTestSupport.run(
                "-m", model.toString(), "computation", "add", spec.toString(), "--dry-run");
        JsonNode envelope = MAPPER.readTree(result.out());
        JsonNode diagnostic = envelope.path("diagnostics").get(0);

        assertEquals("rejected", envelope.path("outcome").asText(), result::out);
        assertEquals(false, envelope.path("valid").asBoolean(), result::out);
        assertEquals("KERNEL_CONFIRMED", envelope.path("verification").asText(),
                () -> "a gate ran, so the basis is the kernel's: " + result.out());
        assertEquals("KERNEL", diagnostic.path("source").asText(),
                () -> "the attribution this test exists to pin: " + result.out());
        assertEquals("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD", diagnostic.path("code").asText(), result::out);
        assertTrue(diagnostic.path("where").path("rule").asText().endsWith("RangeTargetComp"),
                () -> "the kernel locates it at the generated rule: " + result.out());
    }

    /**
     * The typed route: the same shape never reaches a kernel, because the builder refuses to construct it. The
     * two arms together are what make "which refuser" a measured fact rather than a reading of one envelope.
     */
    @Test
    void theTypedRouteRefusesTheSameShapeLocallyAndNeverReachesAGate() {
        BoundModel m = BoundModel.ofJson(PROBE);
        PrecheckException refusal = assertThrows(PrecheckException.class, () -> m
                .computation(m.num("/Probe/Store/RangeTarget"))
                .name("RangeTargetComp")
                .calc(Dsl.firstFilledNumber(m.num("/Probe/Alpha/AlphaOne").field(),
                        m.num("/Probe/Store/RangeTarget").field()).expression())
                .message(Dsl.en("probe"))
                .build());
        assertTrue(refusal.getMessage().contains("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD"), refusal::getMessage);
    }
}
