package io.github.mbackschat.a12.dm.adapter.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioDecoder;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioRejectedException;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;

/**
 * The CLOSED {@code capture-scenario-set-v1} decoder (proposal §1): closed member sets at every level,
 * digest/traversal shape checks, pinned IDs (never "latest"), and — the design's load-bearing negative —
 * NO expected-result member exists in the schema at all; supplying one is refused with the no-oracle
 * diagnostic. Structural rejection happens before any engine and collects EVERY problem.
 */
class ScenarioDecoderTest {

    private static final String VALID_SHA = "a".repeat(64);

    private static ScenarioSet decode(String text) {
        return ScenarioDecoder.decode(Json.parse(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<String> rejectionOf(String text) {
        return assertThrows(ScenarioRejectedException.class, () -> decode(text)).problems();
    }

    private static String valid() {
        return CaptureFixtures.validScenarioText(VALID_SHA);
    }

    @Test
    void decodesTheValidScenario() {
        ScenarioSet set = decode(valid());
        assertEquals("capture-selftest-quote-v1", set.scenarioSetId());
        assertEquals(List.of(RunnerId.KERNEL_GROOVY_DYNAMIC, RunnerId.KERNEL_JAVA_STATIC,
                RunnerId.A12_DMKITS_INTERPRETER), set.requiredRunners());
        assertEquals(PolicyId.KERNEL_ROUTE_CONFIRMED, set.qualificationPolicy());
        assertEquals(1, set.cases().size());
        ScenarioSet.ScenarioCase c = set.cases().get(0);
        assertEquals("premium-computes", c.caseId());
        assertEquals(3, c.placements().size());
        assertEquals(List.of("/Quote[1]/Premium"), c.probes());
    }

    @Test
    void rejectsAnUnknownMemberAnywhere() {
        List<String> problems = rejectionOf(valid().replace("\"operation\": \"compute\",",
                "\"operation\": \"compute\", \"surprise\": 1,"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown member \"surprise\"")), problems::toString);
    }

    @Test
    void refusesAnExpectedResultMemberWithTheNoOracleDiagnostic() {
        // The schema has NO expected member — a request carrying one is refused explicitly, naming why:
        // the request asks questions; expected outcomes belong in a separately reviewed conformance artifact.
        List<String> problems = rejectionOf(valid().replace("\"probes\": [ \"/Quote[1]/Premium\" ]",
                "\"probes\": [ \"/Quote[1]/Premium\" ], \"expected\": [ \"/Quote[1]/Premium|VALUE|20.50\" ]"));
        assertTrue(problems.stream().anyMatch(p ->
                        p.contains("\"expected\"") && p.contains("never carries expected runner results")),
                problems::toString);
    }

    @Test
    void rejectsAnUnknownSchemaVersionInsteadOfTreatingItAsLatest() {
        List<String> problems = rejectionOf(valid().replace("capture-scenario-set-v1", "capture-scenario-set-v2"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("$.schema") && p.contains("never")
                && p.contains("latest")), problems::toString);
    }

    @Test
    void rejectsAnUnknownRunnerNamingTheRegistry() {
        List<String> problems = rejectionOf(valid().replace("\"kernel-java-static\"", "\"kernel-js\""));
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown runner \"kernel-js\"")
                && p.contains("kernel-groovy-dynamic")), problems::toString);
    }

    @Test
    void rejectsAnUnknownQualificationPolicy() {
        List<String> problems = rejectionOf(valid().replace("kernel-route-confirmed-v1", "route-confirmed-v9"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("unknown policy")), problems::toString);
    }

    @Test
    void rejectsATraversalModelRef() {
        List<String> problems = rejectionOf(
                valid().replace("models/quote-premium.json", "models/../../quote-premium.json"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("unsafe reference")), problems::toString);
    }

    @Test
    void rejectsAMalformedDigest() {
        List<String> problems = rejectionOf(valid().replace(VALID_SHA, "not-a-digest"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("sha256")), problems::toString);
    }

    @Test
    void rejectsRepsNotMatchingThePathSegments() {
        List<String> problems = rejectionOf(valid().replace(
                "{ \"kind\": \"FIELD\", \"path\": \"/Quote/BaseRate\", \"reps\": [1, 1], \"value\": \"10.25\" }",
                "{ \"kind\": \"FIELD\", \"path\": \"/Quote/BaseRate\", \"reps\": [1], \"value\": \"10.25\" }"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("one 1-based index per segment")), problems::toString);
    }

    @Test
    void rejectsAValueOnANonFieldPlacement() {
        List<String> problems = rejectionOf(valid().replace(
                "{ \"kind\": \"GROUP\", \"path\": \"/Quote\", \"reps\": [1] }",
                "{ \"kind\": \"GROUP\", \"path\": \"/Quote\", \"reps\": [1], \"value\": \"x\" }"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("only legal on a FIELD placement")),
                problems::toString);
    }

    @Test
    void rejectsAMalformedClock() {
        List<String> problems = rejectionOf(valid().replace("\"operation\": \"compute\",",
                "\"operation\": \"compute\", \"clock\": \"2026-07-15 12:00\","));
        assertTrue(problems.stream().anyMatch(p -> p.contains("not a local wall-clock instant")),
                problems::toString);
    }

    @Test
    void rejectsDuplicateCaseIds() {
        String twoCases = valid().replace("\"cases\": [\n    {",
                "\"cases\": [\n    { \"caseId\": \"premium-computes\", \"modelRef\": \"models/quote-premium.json\","
                        + " \"operation\": \"compute\", \"placements\": ["
                        + " { \"kind\": \"GROUP\", \"path\": \"/Quote\", \"reps\": [1] } ] },\n    {");
        List<String> problems = rejectionOf(twoCases);
        assertTrue(problems.stream().anyMatch(p -> p.contains("duplicate case id")), problems::toString);
    }

    @Test
    void collectsEveryProblemNotJustTheFirst() {
        List<String> problems = rejectionOf(valid()
                .replace("\"kernel-java-static\"", "\"kernel-js\"")
                .replace("kernel-route-confirmed-v1", "route-confirmed-v9"));
        assertTrue(problems.size() >= 2, () -> "expected both problems, got: " + problems);
    }
}
