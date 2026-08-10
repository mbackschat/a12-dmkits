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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.Verification;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel;
import io.github.mbackschat.a12.dm.rulekit.validate.Diagnostic;

/**
 * The result <b>algebra</b> (CLI-SPEC §3b "The result envelope"): what each envelope family may and may not claim.
 *
 * <p>These are the invariants the previous shape could not hold. A {@code record} with a public canonical
 * constructor plus a {@code read(…, boolean valid, …)} factory made every combination of members constructible, and
 * 35 call sites duly passed a literal {@code valid} — seven of them reporting {@code valid:true} on a model the
 * kernel rejects. The point of the typed factories is that those claims are now <em>unrepresentable</em>, so this
 * class asserts the shape of each family and, where the guarantee is structural, asserts the structure itself.
 */
class ResultAlgebraTest {

    private static final ObjectMapper M = new ObjectMapper();

    /**
     * The witness a mutating verb actually holds: a document that has been through {@code validate()}. Obtained,
     * never named — there is no factory that mints a {@code KERNEL_CONFIRMED} receipt out of nothing, which is the
     * property these tests are here to keep.
     */
    private static ConsistencyEvidence gated() {
        return ConsistencyEvidence.of(ModelDocument.read(CliTestSupport.subscriptionJson()).validate());
    }

    // --- structural closure ---------------------------------------------------------------------------

    /**
     * The compiler — not a source-scanning guard — is what prevents a route from assembling an arbitrary envelope.
     * A source scan can only see the call sites it thinks to look at; a private constructor admits none at all.
     */
    @Test
    void theEnvelopeCannotBeConstructedDirectly() {
        for (Constructor<?> ctor : ResultEnvelope.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()),
                    () -> "every ResultEnvelope constructor must be private so the named factories are the whole "
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

        ResultEnvelope env = ResultEnvelope.queryResult(CliTestSupport.identity("field", "read"), "read /Order/Quantity", payload,
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

        ResultEnvelope env = ResultEnvelope.queryResult(CliTestSupport.identity("model", "info"), "read model info", M.createObjectNode(),
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

        ResultEnvelope env = ResultEnvelope.consistencyVerdict(CliTestSupport.identity("model", "check"), "model has 1 error", false,
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
                () -> ResultEnvelope.consistencyVerdict(CliTestSupport.identity("model", "check"), "model is valid", true, List.of(), null,
                        ConsistencyEvidence.none()));
        assertTrue(boom.getMessage().contains("no gate ran"), boom::getMessage);
    }

    /** §2: a verdict may carry {@code data} — otherwise `workspace check` and `--suggest-error-field` regress. */
    @Test
    void aVerdictMayCarryData() {
        ObjectNode data = M.createObjectNode();
        data.putArray("candidates").add("/Order/Quantity");

        ResultEnvelope env = ResultEnvelope.consistencyVerdict(CliTestSupport.identity("rule", "check"), "candidate is kernel-valid", true,
                List.of(), data, ConsistencyEvidence.of(BoundModel.ofJson(CliTestSupport.subscriptionJson())));

        assertEquals(0, env.exitCode());
        assertEquals("/Order/Quantity", env.toJson().path("data").path("candidates").get(0).asText());
    }

    // --- 3. runtime observation: an engine, never a verdict -------------------------------------------

    @Test
    void aRuntimeObservationNamesItsEngineAndClaimsNoValidity() {
        ObjectNode data = M.createObjectNode();
        data.putArray("fired");

        ResultEnvelope env = ResultEnvelope.runtimeObservation(CliTestSupport.identity("model", "eval"), "0 rule(s) fired", data,
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

    /**
     * The exit code is the contract here, and it comes from the typed outcome rather than from the payload.
     *
     * <p>An earlier version of this test hand-built {@code data.comparison.matches=false} and asserted it back — which
     * locked its own fixture, not the envelope, and would have passed with an <em>empty</em> {@code data}, because
     * {@code path()} on a missing node returns a {@code MissingNode} whose {@code asBoolean()} is {@code false}. It
     * also enshrined a key that does not exist: {@code profile compare} publishes {@code matches} at the top of
     * {@code data}. What is actually this class's business is that {@code DIFFERS} exits 1 and claims no validity;
     * the payload's shape belongs to {@code ProfileCompareVerbTest}, which reads the real verb's output.
     */
    @Test
    void aComparisonKeepsExitOneOnDiffersAndClaimsNoValidity() {
        ResultEnvelope differs = ResultEnvelope.comparisonResult(CliTestSupport.identity("profile", "compare"), "profiles differ",
                ComparisonOutcome.DIFFERS, M.createObjectNode());

        assertEquals(1, differs.exitCode(), "CI branches on this code — the reclassification must not move it");
        assertFalse(ComparisonOutcome.DIFFERS.matches(), "and the typed outcome, not the payload, is what says so");
        JsonNode j = differs.toJson();
        assertTrue(j.get("ok").asBoolean(), "the comparison executed as asked");
        assertFalse(j.has("valid"), "profile equality is not model validity");
    }

    @Test
    void anEquivalentComparisonExitsZero() {
        assertEquals(0, ResultEnvelope.comparisonResult(CliTestSupport.identity("profile", "compare"), "profiles match",
                ComparisonOutcome.EQUIVALENT, M.createObjectNode()).exitCode());
    }

    // --- 5. mutation ----------------------------------------------------------------------------------

    @Test
    void anAppliedMutationPublishesItsWriteAndMakesNoVerdictClaim() {
        ObjectNode changed = M.createObjectNode();
        changed.put("added", "/Order/PromoCode").put("kind", "STRING");

        ResultEnvelope env = ResultEnvelope.applied(CliTestSupport.identity("field", "add"),
                "added field /Order/PromoCode (STRING)", changed,
                "order.dm.json", gated());

        assertEquals(0, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("applied", j.get("outcome").asText());
        assertTrue(j.get("written").asBoolean());
        assertEquals("order.dm.json", j.get("output").asText());
        assertFalse(j.has("valid"), "a successful write is not a standing verdict about the model");
    }

    /**
     * A write gated before it wrote, so it says who arbitrated — the same axis a read publishes, on the same terms.
     * {@code verification} is provenance, not a verdict, so it appears here <em>without</em> {@code valid}: the two
     * were conflated once and the whole contract exists to keep them apart.
     */
    @Test
    void anAppliedMutationStatesTheGateThatClearedIt() {
        JsonNode j = ResultEnvelope.applied(CliTestSupport.identity("field", "add"), "added field /Order/PromoCode", M.createObjectNode(),
                "order.dm.json", gated()).toJson();

        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText(),
                "the edit gate ran before the write, so the write can name it");
        assertFalse(j.has("valid"), "and still makes no standing verdict about the model");
    }

    /** The other side, and the reason the parameter is required rather than defaulted: silence must be chosen. */
    @Test
    void anUngatedMutationClaimsNothing() {
        JsonNode j = ResultEnvelope.applied(CliTestSupport.identity("model", "normalize"), "normalized", M.createObjectNode(),
                "order.dm.json", ConsistencyEvidence.none()).toJson();

        assertFalse(j.has("verification"), "this route never consulted the oracle, so it may not name one");
    }

    @Test
    void aPreviewWritesNothing() {
        ResultEnvelope env = ResultEnvelope.preview(CliTestSupport.identity("field", "add"), "would add /Order/PromoCode",
                M.createObjectNode(), gated());
        JsonNode j = env.toJson();
        assertEquals("preview", j.get("outcome").asText());
        assertFalse(j.get("written").asBoolean());
        assertFalse(j.has("output"));
    }

    /**
     * {@code --dry-run} runs the same gate the write would, so "nothing was written" must not read as "nothing was
     * checked". An agent deciding whether to re-run for real needs exactly this distinction.
     */
    @Test
    void aPreviewStillStatesTheGateItRan() {
        JsonNode j = ResultEnvelope.preview(CliTestSupport.identity("field", "add"), "would add /Order/PromoCode", M.createObjectNode(),
                gated()).toJson();

        assertFalse(j.get("written").asBoolean());
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText());
    }

    /** A committed {@code apply} passed its one terminal gate; the aggregate is where that receipt belongs. */
    @Test
    void aCommittedTransactionCarriesTheTerminalGate() {
        JsonNode j = ResultEnvelope.transaction(ResultIdentity.APPLY_RUN, "3 op(s) committed", M.createObjectNode(),
                ResultEnvelope.MutationDisposition.APPLIED, "order.dm.json", gated()).toJson();

        assertEquals("applied", j.get("outcome").asText());
        assertEquals("KERNEL_CONFIRMED", j.path("verification").asText());
    }

    /** A staged step is ungated BY DESIGN — `apply` defers validation to one terminal gate. */
    @Test
    void aStagedStepClaimsNoGateOfItsOwn() {
        ResultEnvelope env = ResultEnvelope.staged(CliTestSupport.identity("field", "add"), "staged field add", M.createObjectNode(),
                List.of());
        JsonNode j = env.toJson();
        assertEquals("staged", j.get("outcome").asText());
        assertTrue(j.get("ok").asBoolean());
        assertEquals(0, env.exitCode());
        assertFalse(j.has("verification"),
                "the transaction's single terminal gate carries the evidence, not each step");
    }

    // --- 6. stateless dispatch ------------------------------------------------------------------------

    @Test
    void aSuccessfulDispatchIsCompletedAndExitsZero() {
        ArrayNode results = M.createArrayNode();
        results.addObject().put("exit", 0);

        ResultEnvelope env = ResultEnvelope.dispatchCompleted(
                ResultIdentity.BATCH_RUN, "dispatched 1 op", results);

        assertEquals(0, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("completed", j.path("outcome").asText());
        assertTrue(j.path("ok").asBoolean());
        assertTrue(j.path("data").path("dispatched").asBoolean());
        assertTrue(j.path("data").path("allExitedZero").asBoolean());
        assertFalse(j.path("data").has("allSucceeded"));
    }

    @Test
    void aCompletedDispatchWithAFailedChildIsNotSuccessful() {
        ArrayNode results = M.createArrayNode();
        results.addObject().put("exit", 0);
        results.addObject().put("exit", 1);

        ResultEnvelope env = ResultEnvelope.dispatchCompleted(
                ResultIdentity.BATCH_RUN, "dispatched 2 ops", results);

        assertEquals(1, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("completed", j.path("outcome").asText(), "dispatch still reached every child");
        assertFalse(j.path("ok").asBoolean(), "completed dispatch is not the same as successful children");
        assertTrue(j.path("data").path("dispatched").asBoolean());
        assertFalse(j.path("data").path("allExitedZero").asBoolean());
        assertFalse(j.path("data").has("allSucceeded"));
        assertEquals(0, j.path("data").path("results").get(0).path("exit").asInt());
        assertEquals(1, j.path("data").path("results").get(1).path("exit").asInt());
    }

    @Test
    void aWrittenObservationArtifactSucceedsAfterFullDispatchEvenWhenAChildExitsNonzero() {
        ArrayNode results = M.createArrayNode();
        results.addObject().put("exit", 0);
        results.addObject().put("exit", 1);

        ResultEnvelope env = ResultEnvelope.dispatchCompletedWithArtifact(
                ResultIdentity.BATCH_RUN, "dispatched 2 ops", results, "/tmp/observations.json");

        assertEquals(0, env.exitCode());
        JsonNode j = env.toJson();
        assertEquals("completed", j.path("outcome").asText());
        assertTrue(j.path("ok").asBoolean(), "full dispatch plus artifact write is observation success");
        assertTrue(j.path("written").asBoolean());
        assertFalse(j.path("data").path("allExitedZero").asBoolean(),
                "child exits remain an independent aggregate axis");
    }

    // --- 7/8/9. refusal, rejection, internal error ----------------------------------------------------

    @Test
    void aRefusalExitsTwoAndAssertsNothingAboutTheSubject() {
        ResultEnvelope env = ResultEnvelope.refused(CliTestSupport.identity("field", "remove"),
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

        ResultEnvelope env = ResultEnvelope.rejected(CliTestSupport.identity("rule", "add"), "condition rejected by the kernel (1 error)",
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
        ResultEnvelope env = ResultEnvelope.rejected(CliTestSupport.identity("rule", "add"), "the spec has 1 unrecognized key", List.of(),
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
        ResultEnvelope env = ResultEnvelope.internalError(CliTestSupport.identity("model", "read"),
                "the tool failed internally", List.of());
        assertEquals(70, env.exitCode());
        assertEquals("error", env.toJson().get("outcome").asText());
        assertFalse(env.toJson().get("ok").asBoolean());
    }

    // --- the nested-verdict boundary ------------------------------------------------------------------

    /**
     * An arbitrary nested verdict must never feed the process's exit status. This is structural rather than
     * conventional: the exit code switches on the family and never reads {@code data}, so a workspace of invalid
     * models still exits 0 from an ungated listing. An aggregate command such as {@code batch} may deliberately
     * fold child exits into its family before constructing the envelope; merely nesting {@code valid:false} cannot.
     */
    @Test
    void aNestedVerdictInDataNeverReachesTheExitCode() {
        ObjectNode data = M.createObjectNode();
        data.putArray("models").addObject().put("path", "broken.dm.json").put("valid", false);

        ResultEnvelope env = ResultEnvelope.queryResult(CliTestSupport.identity("workspace", "list"), "listed 1 model", data,
                ConsistencyEvidence.none());

        assertEquals(0, env.exitCode(), "the listing executed; a per-entry verdict is payload, not process status");
        assertFalse(env.toJson().has("valid"), "and it is not promoted to a top-level claim either");
    }
}
