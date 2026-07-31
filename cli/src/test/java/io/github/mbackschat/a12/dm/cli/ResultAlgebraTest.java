package io.github.mbackschat.a12.dm.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * The result <b>algebra</b> (RESULT-CONTRACT-PROPOSAL §2/§3): what each envelope family may and may not claim.
 *
 * <p>These are the invariants the previous shape could not hold. A {@code record} with a public canonical
 * constructor plus a {@code read(…, boolean valid, …)} factory made every combination of members constructible, and
 * 35 call sites duly passed a literal {@code valid} — seven of them reporting {@code valid:true} on a model the
 * kernel rejects. The point of the eight factories is that those claims are now <em>unrepresentable</em>, so this
 * class asserts the shape of each family and, where the guarantee is structural, asserts the structure itself.
 */
class ResultAlgebraTest {

    private static final ObjectMapper M = new ObjectMapper();

    // --- structural closure ---------------------------------------------------------------------------

    /**
     * The compiler — not a source-scanning guard — is what prevents a route from assembling an arbitrary envelope.
     * A source scan can only see the call sites it thinks to look at; a private constructor admits none at all.
     */
    @Test
    void theEnvelopeCannotBeConstructedDirectly() {
        for (Constructor<?> ctor : ResultEnvelope.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                    () -> "every ResultEnvelope constructor must be private so the eight factories are the whole "
                            + "construction surface; found " + ctor);
        }
    }

    /** {@code withVerification} let evidence be attached after the fact, by a caller no longer holding the gate. */
    @Test
    void thereIsNoWayToBoltEvidenceOntoAFinishedEnvelope() {
        assertTrue(java.util.Arrays.stream(ResultEnvelope.class.getMethods())
                        .noneMatch(m -> m.getName().equals("withVerification")),
                "withVerification must stay deleted — evidence comes from the gate, not from a later copy");
    }

    // --- 1. query: no verdict, ever -------------------------------------------------------------------

    @Test
    void aQueryMakesNoValidityClaimAndCannotExitNonZero() {
        ObjectNode payload = M.createObjectNode();
        payload.put("field", "/Order/Quantity").put("kind", "NUMBER");

        ResultEnvelope env = ResultEnvelope.queryResult("field", "read", "read /Order/Quantity", payload,
                ConsistencyEvidence.none());

        assertEquals(0, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("read", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        assertFalse(j.has("valid"), "a read is not a verdict — this is the key seven verbs used to fabricate");
        assertFalse(j.has("verification"), "no gate ran on this path, so no provenance may be claimed");
        assertEquals("/Order/Quantity", j.path("data").path("field").asText());
        assertFalse(j.has("changed"), "a query carries its payload in `data`, not `changed`");
    }

    /** A gated read publishes honest provenance in place of the verdict it must not invent. */
    @Test
    void aGatedQueryPublishesItsGateWithoutInventingAVerdict() {
        BoundModel gated = BoundModel.ofJson(CliTestSupport.subscriptionJson());

        ResultEnvelope env = ResultEnvelope.queryResult("model", "info", "read model info", M.createObjectNode(),
                ConsistencyEvidence.of(gated));

        JsonNode j = env.toJson();
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText());
        assertFalse(j.has("valid"), "the gate ran, but a read still reports no verdict of its own");
    }

    /**
     * The derivation rule, exercised on the discriminating pair: two bindings of the SAME type, differing only in
     * which factory produced them. A lenient binding never gated, so the evidence it hands out is empty and the
     * key disappears — with no second edit at the call site and no way to forget.
     */
    @Test
    void evidenceFollowsTheExecutedPathNotTheCallSitesIntent() {
        String json = CliTestSupport.subscriptionJson();
        ConsistencyEvidence fromGated = ConsistencyEvidence.of(BoundModel.ofJson(json));
        ConsistencyEvidence fromLenient =
                ConsistencyEvidence.of(BoundModel.ofJsonLenient(json, ReferenceResolvers.selfContained()));

        assertEquals(Optional.of(Verification.KERNEL_CONFIRMED), fromGated.verification());
        assertEquals(Optional.empty(), fromLenient.verification(),
                "ofJsonLenient runs no gate, so it can attest nothing — the same call site loses the key");
    }

    // --- 2. verdict: valid and its provenance, inseparably --------------------------------------------

    @Test
    void aVerdictCarriesValidAndItsGateTogether() {
        Diagnostic d = Diagnostic.precheck(Optional.of("MVK_X"), "bad",
                new Diagnostic.Where("/Order", Optional.empty()), Optional.empty(), Optional.empty());

        ResultEnvelope env = ResultEnvelope.consistencyVerdict("model", "check", "model has 1 error", false,
                List.of(d), null, ConsistencyEvidence.of(BoundModel.ofJson(CliTestSupport.subscriptionJson())));

        assertEquals(1, env.exitCode());
        JsonNode j = env.toJson();
        assertTrue(j.get("ok").asBoolean(), "the check op itself executed fine");
        assertFalse(j.get("valid").asBoolean(), "the subject model is invalid");
        assertEquals("KERNEL_CONFIRMED", j.get("verification").asText());
    }

    /** A verdict with nothing behind it is a programming error, not a shape to serialize. */
    @Test
    void aVerdictWithoutAGateCannotBeBuilt() {
        IllegalStateException boom = assertThrows(IllegalStateException.class,
                () -> ResultEnvelope.consistencyVerdict("model", "check", "model is valid", true, List.of(), null,
                        ConsistencyEvidence.none()));
        assertTrue(boom.getMessage().contains("no gate ran"), boom::getMessage);
    }

    /** §2: a verdict may carry {@code data} — otherwise `workspace check` and `--suggest-error-field` regress. */
    @Test
    void aVerdictMayCarryData() {
        ObjectNode data = M.createObjectNode();
        data.putArray("candidates").add("/Order/Quantity");

        ResultEnvelope env = ResultEnvelope.consistencyVerdict("rule", "check", "candidate is kernel-valid", true,
                List.of(), data, ConsistencyEvidence.of(BoundModel.ofJson(CliTestSupport.subscriptionJson())));

        assertEquals(0, env.exitCode());
        assertEquals("/Order/Quantity", env.toJson().path("data").path("candidates").get(0).asText());
    }

    // --- 3. runtime observation: an engine, never a verdict -------------------------------------------

    @Test
    void aRuntimeObservationNamesItsEngineAndClaimsNoValidity() {
        ObjectNode data = M.createObjectNode();
        data.putArray("fired");

        ResultEnvelope env = ResultEnvelope.runtimeObservation("model", "eval", "0 rule(s) fired", data,
                RuntimeEvidence.interpreter());

        assertEquals(0, env.exitCode());
        JsonNode j = env.toJson();
        assertFalse(j.has("valid"),
                "eval/compute were measured claiming valid:true on a kernel-INVALID model; an evaluation "
                        + "produces observations, never a verdict about the model");
        assertFalse(j.has("verification"), "no consistency verdict was produced, so nothing arbitrated one");
        assertEquals("DM_INTERPRETER", j.get("engine").asText());
        assertTrue(j.has("engineVersion"), "which build of the engine computed this");
        assertTrue(j.has("toolVersion"), "which build of dmtool projected it");
    }

    /** All three keys are load-bearing: equal today, independently movable tomorrow. */
    @Test
    void runtimeEvidenceRefusesAnIncompleteTriple() {
        assertThrows(IllegalArgumentException.class,
                () -> new RuntimeEvidence(RuntimeEvidence.Engine.DM_INTERPRETER, null, "0.10.1"));
    }

    // --- 4. comparison: its own kind of result, with its own exit code --------------------------------

    @Test
    void aComparisonReportsMatchesAndKeepsExitOneOnDiffers() {
        ObjectNode data = M.createObjectNode();
        data.putObject("comparison").put("matches", false);

        ResultEnvelope differs = ResultEnvelope.comparisonResult("profile", "compare", "profiles differ",
                ComparisonOutcome.DIFFERS, data);

        assertEquals(1, differs.exitCode(), "CI branches on this code — the reclassification must not move it");
        JsonNode j = differs.toJson();
        assertTrue(j.get("ok").asBoolean(), "the comparison executed as asked");
        assertFalse(j.has("valid"), "profile equality is not model validity");
        assertFalse(j.path("data").path("comparison").path("matches").asBoolean());
    }

    @Test
    void anEquivalentComparisonExitsZero() {
        assertEquals(0, ResultEnvelope.comparisonResult("profile", "compare", "profiles match",
                ComparisonOutcome.EQUIVALENT, M.createObjectNode()).exitCode());
    }

    // --- 5. mutation ----------------------------------------------------------------------------------

    @Test
    void anAppliedMutationPublishesItsWriteAndMakesNoVerdictClaim() {
        ObjectNode changed = M.createObjectNode();
        changed.put("added", "/Order/PromoCode").put("kind", "STRING");

        ResultEnvelope env = ResultEnvelope.applied("field", "add", "added field /Order/PromoCode (STRING)", changed,
                "order.dm.json");

        assertEquals(0, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("written").asBoolean());
        assertEquals("order.dm.json", j.get("output").asText());
        assertFalse(j.has("valid"), "a successful write is not a standing verdict about the model");
    }

    @Test
    void aPreviewWritesNothing() {
        ResultEnvelope env = ResultEnvelope.preview("field", "add", "would add /Order/PromoCode",
                M.createObjectNode());
        JsonNode j = env.toJson();
        assertEquals("preview", j.get("outcome").asText());
        assertFalse(j.get("written").asBoolean());
        assertFalse(j.has("output"));
    }

    /** A staged step is ungated BY DESIGN — `apply` defers validation to one terminal gate. */
    @Test
    void aStagedStepClaimsNoGateOfItsOwn() {
        ResultEnvelope env = ResultEnvelope.staged("field", "add", "staged field add", M.createObjectNode(),
                List.of());
        JsonNode j = env.toJson();
        assertEquals("staged", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        assertEquals(0, env.exitCode());
        assertFalse(j.has("verification"),
                "the transaction's single terminal gate carries the evidence, not each step");
    }

    // --- 6/7/8. refusal, rejection, internal error ----------------------------------------------------

    @Test
    void aRefusalExitsTwoAndAssertsNothingAboutTheSubject() {
        ResultEnvelope env = ResultEnvelope.refused("field", "remove",
                "/Order/DeliveryDate is referenced by 1 rule", List.of());
        assertEquals(2, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("refused", j.get("outcome").asText());
        assertFalse(j.get("ok").asBoolean());
        assertFalse(j.has("valid"));
        assertFalse(j.has("verification"), "the kernel was never asked for a verdict here");
    }

    /**
     * The sharpest form of the provenance defect: an invalid-model rejection published neither {@code valid} nor
     * {@code verification}, although the kernel had just arbitrated. {@code KERNEL_CONFIRMED} means "the kernel
     * arbitrated this invocation", never "the model was valid", so it must stay meaningful on a rejection.
     */
    @Test
    void aKernelRejectionReportsBothTheVerdictAndTheArbitrator() {
        Diagnostic d = Diagnostic.precheck(Optional.of("MVK_UNKNOWN_FIELD"), "/Order/Quanity is not a field",
                new Diagnostic.Where("/Order/MinQuantity", Optional.empty()),
                Optional.of("replace /Order/Quanity with /Order/Quantity"), Optional.empty());

        ResultEnvelope env = ResultEnvelope.rejected("rule", "add", "condition rejected by the kernel (1 error)",
                List.of(d), RejectionBasis.kernel(
                        ConsistencyEvidence.of(BoundModel.ofJson(CliTestSupport.subscriptionJson()))));

        assertEquals(1, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("rejected", j.get("outcome").asText());
        assertFalse(j.get("ok").asBoolean());
        assertFalse(j.get("valid").asBoolean(), "the kernel's rejection IS a negative verdict");
        assertEquals("KERNEL_CONFIRMED", j.get("verification").asText(),
                "the kernel arbitrated this invocation — that is what the marker means");
        assertEquals("MVK_UNKNOWN_FIELD", j.path("diagnostics").get(0).path("code").asText());
    }

    /** A pre-flight rejection never consulted the kernel, so it has neither verdict nor evidence to give. */
    @Test
    void aPreflightRejectionReportsNeitherVerdictNorEvidence() {
        ResultEnvelope env = ResultEnvelope.rejected("rule", "add", "the spec has 1 unrecognized key", List.of(),
                RejectionBasis.preflight());

        assertEquals(1, env.exitCode());
        JsonNode j = env.toJson();
        assertFalse(j.has("valid"), "no gate ran, so there is no verdict");
        assertFalse(j.has("verification"), "and nothing to attribute one to");
    }

    /** A kernel rejection needs the receipt that produced it; the empty evidence is a caller bug. */
    @Test
    void aKernelRejectionBasisRefusesEmptyEvidence() {
        assertThrows(IllegalStateException.class, () -> RejectionBasis.kernel(ConsistencyEvidence.none()));
    }

    @Test
    void anInternalErrorExitsSeventy() {
        ResultEnvelope env = ResultEnvelope.internalError("model", "read", "the tool failed internally", List.of());
        assertEquals(70, env.exitCode());
        assertEquals("error", env.toJson().get("outcome").asText());
        assertFalse(env.toJson().get("ok").asBoolean());
    }

    // --- the nested-verdict boundary ------------------------------------------------------------------

    /**
     * A nested verdict must never feed the process's exit status. This is structural rather than conventional: the
     * exit code switches on the family, and nothing in it reads {@code data} — so a workspace of invalid models
     * still exits 0 from an ungated listing, and no future edit to the payload can change that.
     */
    @Test
    void aNestedVerdictInDataNeverReachesTheExitCode() {
        ObjectNode data = M.createObjectNode();
        data.putArray("models").addObject().put("path", "broken.dm.json").put("valid", false);

        ResultEnvelope env = ResultEnvelope.queryResult("workspace", "list", "listed 1 model", data,
                ConsistencyEvidence.none());

        assertEquals(0, env.exitCode(), "the listing executed; a per-entry verdict is payload, not process status");
        assertFalse(env.toJson().has("valid"), "and it is not promoted to a top-level claim either");
    }
}
