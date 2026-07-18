package io.github.mbackschat.a12.dm.adapter.capture.export;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ChannelId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.packet.SourceState;
import io.github.mbackschat.a12.dm.adapter.capture.policy.PolicyEngine;
import io.github.mbackschat.a12.dm.adapter.capture.policy.Projections;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * The compact semantic-observation exporter's committed guards (CONFORMANCE-CORPUS-SPEC §15h; the
 * client proposal's §"Red/green and mutation sensitivity" guards 1–2 and 4–10, plus the Z5-R2
 * frozen receipt-identity closure, guard 11, and the Z5-R3 rendered-availability + error-relation
 * closures, guards 12–13) over the smallest
 * five-case typed fixture — the anchor channel contents mirror the retained qualified packet,
 * INCLUDING its incidental Out-before-Mid raw enumeration order. Guard 3 (the frozen V1
 * capabilities golden) is {@code CapabilitiesTest.theFrozenV1CapabilitiesDigestHolds}.
 */
class CompactObservationExportTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";
    private static final String PACKET_SHA =
            "7e38744283cf17f7f57fe0bce342084a8e2896fb99aa60c2a9f4fb52e185dc17";
    private static final String QUALIFICATION_SHA =
            "f581d82d7eb04929b38d4663bbb48a28f09c7399273cd5974cd59e739eca5d64";
    private static final String MODEL_SHA =
            "3d21add02d259a8d1ad2e14475582513aec2f4e60176f1c02c81d40de88a895d";
    private static final String MODEL_REF = "models/string-direct-cascade.json";
    private static final CompactProjection.SourceIdentity IDENTITY =
            new CompactProjection.SourceIdentity(REVISION, PACKET_SHA, QUALIFICATION_SHA);

    // ---- the five-case typed fixture -----------------------------------------------------------

    private static ScenarioSet.Placement field(String path, String value) {
        return new ScenarioSet.Placement(ScenarioSet.PlacementKind.FIELD, path, List.of(1, 1),
                Optional.of(value));
    }

    private static ScenarioSet.ScenarioCase kase(String id, String source, String mid, String out) {
        List<ScenarioSet.Placement> placements = new ArrayList<>();
        placements.add(new ScenarioSet.Placement(ScenarioSet.PlacementKind.GROUP, "/Cascade",
                List.of(1), Optional.empty()));
        if (source != null) {
            placements.add(field("/Cascade/Source", source));
        }
        if (mid != null) {
            placements.add(field("/Cascade/Mid", mid));
        }
        if (out != null) {
            placements.add(field("/Cascade/Out", out));
        }
        return new ScenarioSet.ScenarioCase(id, MODEL_REF, Optional.empty(), placements,
                List.of("/Cascade[1]/Mid", "/Cascade[1]/Out"));
    }

    private static final List<ScenarioSet.ScenarioCase> CASES = List.of(
            kase("source-abc-mid-old", "ABC", "OLD", "STALE"),
            kase("source-abc-mid-abc", "ABC", "ABC", "STALE"),
            kase("source-absent-mid-old", null, "OLD", "STALE"),
            kase("source-absent-mid-absent", null, null, "STALE"),
            kase("source-abcd-mid-old", "ABCD", "OLD", "STALE"));

    private static ScenarioSet request(List<ScenarioSet.ScenarioCase> cases) {
        return new ScenarioSet("string-direct-cascade-v1", "1", SchemaIds.COMPUTE_OBSERVATION,
                SchemaIds.CAPABILITIES, SchemaIds.CAPABILITIES_VERSION,
                SchemaIds.WORLD_PROFILE_LOCAL_WALLCLOCK, "en_US",
                List.of(new ScenarioSet.ModelRef(MODEL_REF, MODEL_SHA)),
                List.of(RunnerId.KERNEL_GROOVY_DYNAMIC, RunnerId.KERNEL_JAVA_STATIC,
                        RunnerId.A12_DMKITS_INTERPRETER),
                PolicyId.KERNEL_ROUTE_CONFIRMED,
                new ScenarioSet.ObservationRequirement("string-direct-cascade-kernel-route-v1", "1",
                        List.of(ChannelId.values()),
                        List.of(ProjectionId.COMPUTE_KERNEL_ROUTE,
                                ProjectionId.COMPUTE_DMKITS_PORTABLE)),
                cases);
    }

    private static Json.Obj parse(String json) {
        return (Json.Obj) Json.parse(json.getBytes(UTF_8));
    }

    private static Map<String, Json.Obj> anchors() {
        Map<String, Json.Obj> anchors = new LinkedHashMap<>();
        anchors.put("source-abc-mid-old", parse(OBS_ABC_MID_OLD));
        anchors.put("source-abc-mid-abc", parse(OBS_ABC_MID_ABC));
        anchors.put("source-absent-mid-old", parse(OBS_ABSENT_MID_OLD));
        anchors.put("source-absent-mid-absent", parse(OBS_ABSENT_MID_ABSENT));
        anchors.put("source-abcd-mid-old", parse(OBS_ABCD_MID_OLD));
        return anchors;
    }

    private static String export(List<ScenarioSet.ScenarioCase> cases, Map<String, Json.Obj> anchors) {
        return new String(Json.canonicalBytes(
                CompactProjection.bundle(request(cases), anchors, IDENTITY)), UTF_8);
    }

    private static List<Json> entries(Json.Obj observation, String channel) {
        Json.Obj channels = (Json.Obj) observation.members().get("channels");
        return ((Json.Arr) ((Json.Obj) channels.members().get(channel)).members().get("entries")).items();
    }

    private static List<Json> cases(String output) {
        Json.Obj family = (Json.Obj) ((Json.Arr) parse(output).members().get("families")).items().get(0);
        return ((Json.Arr) family.members().get("cases")).items();
    }

    private static void assertRefuses(String messagePart, Executable action) {
        CaptureRefusedException refusal = assertThrows(CaptureRefusedException.class, action);
        assertTrue(refusal.getMessage().contains(messagePart),
                "expected a refusal mentioning \"" + messagePart + "\" but was: " + refusal.getMessage());
    }

    // ---- guard 1: exact canonical compact output for the five-case typed fixture ---------------

    @Test
    void exactCanonicalCompactOutputForTheFiveCaseTypedFixture() {
        assertEquals(EXPECTED, export(CASES, anchors()),
                "the compact bundle must byte-equal the proposal's wire contract for the family matrix");
    }

    // ---- guard 2: two runs with the same injected source identity are byte-identical -----------

    @Test
    void twoRunsWithTheSameInjectedSourceIdentityAreByteIdentical() {
        assertEquals(export(CASES, anchors()), export(CASES, anchors()));
    }

    // ---- guard 4: a changed request placement or input matrix refuses --------------------------

    @Test
    void aChangedRequestPlacementOrInputMatrixRefuses() {
        // The shape half: a placement outside the closed family refuses at the projection.
        List<ScenarioSet.ScenarioCase> changedPlacement = new ArrayList<>(CASES);
        changedPlacement.set(0, new ScenarioSet.ScenarioCase("source-abc-mid-old", MODEL_REF,
                Optional.empty(), List.of(field("/Cascade/Elsewhere", "ABC")),
                List.of("/Cascade[1]/Mid", "/Cascade[1]/Out")));
        assertRefuses("outside the closed family", () -> export(changedPlacement, anchors()));
        // The value half: ANY changed placement/probe/case-order/input value changes the copied
        // request bytes, so the envelope-bound digest leaves the frozen identity and refuses.
        assertRefuses("not the frozen", () -> CompactObservationTool.requireFrozenRequest(
                "0000000000000000000000000000000000000000000000000000000000000000"));
        CompactObservationTool.requireFrozenRequest(CompactProjection.REQUEST_SHA256);
    }

    // ---- guard 5: a non-satisfied route comparison refuses before anchor projection ------------

    private static List<Projections.Comparison> comparisons(String result, int count) {
        List<Projections.Comparison> comparisons = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            comparisons.add(new Projections.Comparison("case-" + i, RunnerId.KERNEL_GROOVY_DYNAMIC,
                    RunnerId.KERNEL_JAVA_STATIC, ProjectionId.COMPUTE_KERNEL_ROUTE, List.of(),
                    List.of(), List.of(), result));
        }
        return comparisons;
    }

    @Test
    void aNonSatisfiedRouteComparisonRefusesBeforeAnchorProjection() {
        CompactProjection.requireRouteConfirmed(
                new PolicyEngine.Outcome("satisfied", List.of(), comparisons("equal", 5)));
        assertRefuses("not satisfied", () -> CompactProjection.requireRouteConfirmed(
                new PolicyEngine.Outcome("rejected", List.of("a divergence"), comparisons("equal", 5))));
        assertRefuses("comparisons present and equal", () -> CompactProjection.requireRouteConfirmed(
                new PolicyEngine.Outcome("satisfied", List.of(), comparisons("divergent", 5))));
        assertRefuses("comparisons present and equal", () -> CompactProjection.requireRouteConfirmed(
                new PolicyEngine.Outcome("satisfied", List.of(), comparisons("equal", 4))));
    }

    // ---- guard 6: attempted-value / cause mutations refuse or change the exact golden ----------

    @Test
    void attemptedValueAndCauseMutationsRefuseOrChangeTheGoldenAsPredicted() {
        Map<String, Json.Obj> changed = anchors();
        changed.put("source-abcd-mid-old",
                parse(OBS_ABCD_MID_OLD.replace("\"value\": \"ABCD\"", "\"value\": \"ABQD\"")));
        assertEquals(EXPECTED.replace("\"attempted\": \"ABCD\"", "\"attempted\": \"ABQD\""),
                export(CASES, changed), "a changed attempted value changes exactly the predicted golden");
        Map<String, Json.Obj> dropped = anchors();
        dropped.put("source-abcd-mid-old", parse(OBS_ABCD_MID_OLD.replace("\"attempted\"", "\"tried\"")));
        assertRefuses("attempted", () -> export(CASES, dropped));
        Map<String, Json.Obj> wrongCause = anchors();
        wrongCause.put("source-abcd-mid-old", parse(OBS_ABCD_MID_OLD.replace("stringZuLang", "stringZuKurz")));
        assertRefuses("cause tuple", () -> export(CASES, wrongCause));
    }

    // ---- guard 7: an applied state/value change refuses or changes only the predicted case -----

    @Test
    void changingAnAppliedStateChangesOnlyThePredictedCaseAndUnknownStatesRefuse() {
        Json.Obj collapsed = parse(OBS_ABC_MID_OLD);
        Json.Obj midProbe = (Json.Obj) entries(collapsed, "appliedState").get(0);
        midProbe.members().put("state", new Json.Str("absent"));
        midProbe.members().remove("value");
        Map<String, Json.Obj> changed = anchors();
        changed.put("source-abc-mid-old", collapsed);
        List<Json> base = cases(export(CASES, anchors()));
        List<Json> mutated = cases(export(CASES, changed));
        for (int i = 1; i < base.size(); i++) {
            assertEquals(base.get(i), mutated.get(i), "only the mutated case may change");
        }
        assertNotEquals(base.get(0), mutated.get(0), "the mutated case's applied value becomes null");
        Json.Obj unknown = parse(OBS_ABC_MID_OLD);
        ((Json.Obj) entries(unknown, "appliedState").get(0)).members()
                .put("state", new Json.Str("presentWeird"));
        Map<String, Json.Obj> unknownState = anchors();
        unknownState.put("source-abc-mid-old", unknown);
        assertRefuses("unknown applied state", () -> export(CASES, unknownState));
    }

    // ---- guard 8: reversed incidental raw order still emits canonical Mid-before-Out -----------

    @Test
    void reversedIncidentalRawOrderStillEmitsCanonicalMidBeforeOutOrder() {
        // The BASE fixture already carries the retained packet's incidental Out-before-Mid order;
        // reversing it must yield the identical canonical bytes.
        Json.Obj reordered = parse(OBS_ABC_MID_OLD);
        Collections.reverse(entries(reordered, "withoutErrors"));
        Collections.reverse(entries(reordered, "changedSubset"));
        Map<String, Json.Obj> anchors = anchors();
        anchors.put("source-abc-mid-old", reordered);
        assertEquals(EXPECTED, export(CASES, anchors));
    }

    // ---- guard 9: a duplicated raw observation survives — never a set or map -------------------

    @Test
    void aDuplicatedRawObservationSurvivesIntoTheCompactOutput() {
        Json.Obj duplicated = parse(OBS_ABC_MID_OLD);
        List<Json> clean = entries(duplicated, "withoutErrors");
        clean.add(clean.get(1));
        Map<String, Json.Obj> anchors = anchors();
        anchors.put("source-abc-mid-old", duplicated);
        Json.Obj first = (Json.Obj) cases(export(CASES, anchors)).get(0);
        List<Json> compactClean =
                ((Json.Arr) ((Json.Obj) first.members().get("observed")).members().get("clean")).items();
        assertEquals(3, compactClean.size(), "the duplicate must never disappear through a set or map");
        assertEquals(compactClean.get(0), compactClean.get(1), "both mid entries survive, in order");
    }

    // ---- guard 10: tool-level refusals over the real command path ------------------------------

    private static String refusal(Path packet, String packetSha, Path qualification,
            String qualificationSha, Path output, boolean clean, Path repoRoot) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exit = CompactObservationTool.run(new String[] {
                "--packet", packet.toString(), "--packet-receipt-sha256", packetSha,
                "--qualification", qualification.toString(),
                "--qualification-receipt-sha256", qualificationSha,
                "--output", output.toString()},
                new CompactObservationTool.Env(new PrintStream(OutputStream.nullOutputStream()),
                        new PrintStream(err), () -> new SourceState(REVISION, clean, "tree-digest"),
                        repoRoot));
        assertEquals(1, exit, "the command must refuse (exit 1), never write");
        return err.toString(UTF_8);
    }

    @Test
    void wrongDigestsDirtyStateBadOutputAndOversizeRefuse(@TempDir Path temp) throws IOException {
        assertTrue(refusal(temp, PACKET_SHA, temp, QUALIFICATION_SHA, temp.resolve("out"), false, temp)
                        .contains("CLEAN committed"),
                "a dirty exporter working tree refuses");
        Path packet = Files.createDirectory(temp.resolve("packet"));
        Files.writeString(packet.resolve("RECEIPT.json"), "{}");
        assertTrue(refusal(packet, PACKET_SHA, temp, QUALIFICATION_SHA, temp.resolve("out"), true, temp)
                        .contains("does not match the supplied out-of-band digest"),
                "a wrong packet receipt digest refuses");
        Path occupied = Files.createDirectory(temp.resolve("occupied"));
        Files.writeString(occupied.resolve("stray.txt"), "occupied");
        assertTrue(refusal(packet, PACKET_SHA, temp, QUALIFICATION_SHA, occupied, true, temp)
                        .contains("NOT empty"),
                "a nonempty output directory refuses");
        Path qualification = Files.createDirectory(temp.resolve("qualification"));
        Files.writeString(qualification.resolve("RECEIPT.json"), "{}");
        assertRefuses("does not match the supplied out-of-band digest",
                () -> CompactObservationTool.requireReceiptTree(qualification, QUALIFICATION_SHA));
        assertRefuses("compact-export ceiling", () -> CompactObservationTool.write(
                new byte[CompactObservationTool.MAX_BYTES + 1], temp.resolve("oversize"), temp));
    }

    // ---- guard 11 (Z5-R2): non-frozen receipt identities refuse before any tree verification ---

    private static final String ALT_PACKET_SHA =
            "7e38744283cf17f7f57fe0bce342084a8e2896fb99aa60c2a9f4fb52e185dc18";
    private static final String ALT_QUALIFICATION_SHA =
            "f581d82d7eb04929b38d4663bbb48a28f09c7399273cd5974cd59e739eca5d65";

    @Test
    void aNonFrozenReceiptIdentityRefusesBeforeAnyTreeVerification(@TempDir Path temp) {
        // Valid 64-hex identities that are NOT the retained unit's, against paths that do not even
        // exist: a refusal naming the frozen identity proves the gate fires BEFORE any
        // filesystem/tree verification could run (a load attempt would fail differently).
        String packet = refusal(temp.resolve("no-packet"), ALT_PACKET_SHA,
                temp.resolve("no-qualification"), QUALIFICATION_SHA, temp.resolve("out"), true, temp);
        assertTrue(packet.contains("not the frozen retained-unit identity"), packet);
        String qualification = refusal(temp.resolve("no-packet"), PACKET_SHA,
                temp.resolve("no-qualification"), ALT_QUALIFICATION_SHA, temp.resolve("out"), true, temp);
        assertTrue(qualification.contains("not the frozen retained-unit identity"), qualification);
        assertRefuses("not the frozen retained-unit identity", () ->
                new CompactProjection.SourceIdentity(REVISION, ALT_PACKET_SHA, ALT_QUALIFICATION_SHA));
    }

    // ---- guards 12+13 (Z5-R3): rendered availability + the closed error relation ----------------

    private static Json.Obj withUnexposedRendering(String observation, String channel, String member) {
        Json.Obj mutated = parse(observation);
        Json.Obj entry = (Json.Obj) entries(mutated, channel).get(0);
        ((Json.Obj) entry.members().get(member)).members()
                .put("rendered", parse("{\"availability\": \"notExposedByRunner\"}"));
        return mutated;
    }

    @Test
    void anUnavailableRenderedValueInACleanOrChangedChannelRefuses() {
        Map<String, Json.Obj> clean = anchors();
        clean.put("source-abc-mid-old",
                withUnexposedRendering(OBS_ABC_MID_OLD, "withoutErrors", "value"));
        assertRefuses("rendered value must be available", () -> export(CASES, clean));
        Map<String, Json.Obj> changed = anchors();
        changed.put("source-abc-mid-old",
                withUnexposedRendering(OBS_ABC_MID_OLD, "changedSubset", "value"));
        assertRefuses("rendered value must be available", () -> export(CASES, changed));
    }

    @Test
    void anUnavailableRenderedAttemptedValueInTheErrorChannelRefuses() {
        Map<String, Json.Obj> anchors = anchors();
        anchors.put("source-abcd-mid-old",
                withUnexposedRendering(OBS_ABCD_MID_OLD, "withErrors", "attempted"));
        assertRefuses("rendered value must be available", () -> export(CASES, anchors));
    }

    @Test
    void anOutTargetPairedWithTheRetainedMidCauseRefuses() {
        // An INTERNALLY valid Out target/computation pair — only the relation to the retained Mid
        // cause pointer is broken, so a per-field check cannot catch it.
        Json.Obj mutated = parse(OBS_ABCD_MID_OLD);
        Json.Obj error = (Json.Obj) entries(mutated, "withErrors").get(0);
        error.members().put("target", new Json.Str("/Cascade[1]/Out"));
        error.members().put("declaredComputation", new Json.Str("/Cascade/OutComputation"));
        Map<String, Json.Obj> anchors = anchors();
        anchors.put("source-abcd-mid-old", mutated);
        assertRefuses("cause pointer must be the errored target", () -> export(CASES, anchors));
    }

    // ---- inline JSON fixture data (anchor observations mirroring the retained packet) ----------

    private static final String OBS_ABC_MID_OLD = """
            {"channels": {
              "withoutErrors": {"availability": "available", "granularity": "all-computed-clean", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC-X"}, "rendered": {"availability": "available", "value": "ABC-X"}}},
                {"target": "/Cascade[1]/Mid", "declaredComputation": "/Cascade/MidComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC"}, "rendered": {"availability": "available", "value": "ABC"}}}]},
              "changedSubset": {"availability": "available", "granularity": "delta-vs-input", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC-X"}, "rendered": {"availability": "available", "value": "ABC-X"}}},
                {"target": "/Cascade[1]/Mid", "declaredComputation": "/Cascade/MidComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC"}, "rendered": {"availability": "available", "value": "ABC"}}}]},
              "withErrors": {"availability": "available", "granularity": "errored-instances", "entries": []},
              "cleared": {"availability": "available", "granularity": "input-filled-only", "entries": []},
              "formalErrorsInOperands": {"availability": "available", "granularity": "operand-formal-errors", "entries": []},
              "appliedState": {"availability": "available", "granularity": "requested-probes", "entries": [
                {"pointer": "/Cascade[1]/Mid", "state": "presentValue", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC"}, "rendered": {"availability": "notExposedByRunner"}}},
                {"pointer": "/Cascade[1]/Out", "state": "presentValue", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC-X"}, "rendered": {"availability": "notExposedByRunner"}}}]}
            }}""";

    private static final String OBS_ABC_MID_ABC = """
            {"channels": {
              "withoutErrors": {"availability": "available", "granularity": "all-computed-clean", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC-X"}, "rendered": {"availability": "available", "value": "ABC-X"}}},
                {"target": "/Cascade[1]/Mid", "declaredComputation": "/Cascade/MidComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC"}, "rendered": {"availability": "available", "value": "ABC"}}}]},
              "changedSubset": {"availability": "available", "granularity": "delta-vs-input", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC-X"}, "rendered": {"availability": "available", "value": "ABC-X"}}}]},
              "withErrors": {"availability": "available", "granularity": "errored-instances", "entries": []},
              "cleared": {"availability": "available", "granularity": "input-filled-only", "entries": []},
              "formalErrorsInOperands": {"availability": "available", "granularity": "operand-formal-errors", "entries": []},
              "appliedState": {"availability": "available", "granularity": "requested-probes", "entries": [
                {"pointer": "/Cascade[1]/Mid", "state": "presentValue", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC"}, "rendered": {"availability": "notExposedByRunner"}}},
                {"pointer": "/Cascade[1]/Out", "state": "presentValue", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "ABC-X"}, "rendered": {"availability": "notExposedByRunner"}}}]}
            }}""";

    private static final String OBS_ABSENT_MID_OLD = """
            {"channels": {
              "withoutErrors": {"availability": "available", "granularity": "all-computed-clean", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "-X"}, "rendered": {"availability": "available", "value": "-X"}}}]},
              "changedSubset": {"availability": "available", "granularity": "delta-vs-input", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "-X"}, "rendered": {"availability": "available", "value": "-X"}}}]},
              "withErrors": {"availability": "available", "granularity": "errored-instances", "entries": []},
              "cleared": {"availability": "available", "granularity": "input-filled-only", "entries": [
                {"target": "/Cascade[1]/Mid", "declaredComputation": "/Cascade/MidComputation"}]},
              "formalErrorsInOperands": {"availability": "available", "granularity": "operand-formal-errors", "entries": []},
              "appliedState": {"availability": "available", "granularity": "requested-probes", "entries": [
                {"pointer": "/Cascade[1]/Mid", "state": "presentEmpty"},
                {"pointer": "/Cascade[1]/Out", "state": "presentValue", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "-X"}, "rendered": {"availability": "notExposedByRunner"}}}]}
            }}""";

    private static final String OBS_ABSENT_MID_ABSENT = """
            {"channels": {
              "withoutErrors": {"availability": "available", "granularity": "all-computed-clean", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "-X"}, "rendered": {"availability": "available", "value": "-X"}}}]},
              "changedSubset": {"availability": "available", "granularity": "delta-vs-input", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "-X"}, "rendered": {"availability": "available", "value": "-X"}}}]},
              "withErrors": {"availability": "available", "granularity": "errored-instances", "entries": []},
              "cleared": {"availability": "available", "granularity": "input-filled-only", "entries": []},
              "formalErrorsInOperands": {"availability": "available", "granularity": "operand-formal-errors", "entries": []},
              "appliedState": {"availability": "available", "granularity": "requested-probes", "entries": [
                {"pointer": "/Cascade[1]/Mid", "state": "absent"},
                {"pointer": "/Cascade[1]/Out", "state": "presentValue", "value": {"typed": {"availability": "available", "kind": "STRING", "value": "-X"}, "rendered": {"availability": "notExposedByRunner"}}}]}
            }}""";

    private static final String OBS_ABCD_MID_OLD = """
            {"channels": {
              "withoutErrors": {"availability": "available", "granularity": "all-computed-clean", "entries": []},
              "changedSubset": {"availability": "available", "granularity": "delta-vs-input", "entries": []},
              "withErrors": {"availability": "available", "granularity": "errored-instances", "entries": [
                {"target": "/Cascade[1]/Mid", "declaredComputation": "/Cascade/MidComputation", "attempted": {"typed": {"availability": "available", "kind": "STRING", "value": "ABCD"}, "rendered": {"availability": "available", "value": "ABCD"}}, "cause": {"availability": "available", "code": "stringZuLang", "messageType": "VALUE_ERROR", "errorPointer": "/Cascade[1]/Mid"}}]},
              "cleared": {"availability": "available", "granularity": "input-filled-only", "entries": [
                {"target": "/Cascade[1]/Out", "declaredComputation": "/Cascade/OutComputation"}]},
              "formalErrorsInOperands": {"availability": "available", "granularity": "operand-formal-errors", "entries": []},
              "appliedState": {"availability": "available", "granularity": "requested-probes", "entries": [
                {"pointer": "/Cascade[1]/Mid", "state": "presentEmpty"},
                {"pointer": "/Cascade[1]/Out", "state": "presentEmpty"}]}
            }}""";

    // ---- the exact expected canonical bytes (the proposal's wire contract × the family matrix) --

    private static final String EXPECTED = """
            {
              "schemaVersion": 1,
              "kernelVersion": "30.8.1",
              "families": [
                {
                  "id": "string-direct-cascade-v1",
                  "projectionId": "string-direct-cascade-semantic-v1",
                  "projectionVersion": 1,
                  "source": {
                    "producer": "a12-dmkits",
                    "revision": "0123456789abcdef0123456789abcdef01234567",
                    "rawCapture": {
                      "path": "packet/RECEIPT.json",
                      "sha256": "7e38744283cf17f7f57fe0bce342084a8e2896fb99aa60c2a9f4fb52e185dc17"
                    },
                    "qualification": {
                      "policyId": "kernel-route-confirmed-v1",
                      "receipt": {
                        "path": "qualification/RECEIPT.json",
                        "sha256": "f581d82d7eb04929b38d4663bbb48a28f09c7399273cd5974cd59e739eca5d64"
                      }
                    }
                  },
                  "cases": [
                    {
                      "id": "source-abc-mid-old",
                      "input": {
                        "source": "ABC",
                        "priorMid": "OLD",
                        "priorOut": "STALE"
                      },
                      "observed": {
                        "clean": [
                          {
                            "target": "mid",
                            "value": "ABC"
                          },
                          {
                            "target": "out",
                            "value": "ABC-X"
                          }
                        ],
                        "changed": [
                          {
                            "target": "mid",
                            "value": "ABC"
                          },
                          {
                            "target": "out",
                            "value": "ABC-X"
                          }
                        ],
                        "errors": [],
                        "cleared": [],
                        "applied": [
                          {
                            "target": "mid",
                            "value": "ABC"
                          },
                          {
                            "target": "out",
                            "value": "ABC-X"
                          }
                        ]
                      }
                    },
                    {
                      "id": "source-abc-mid-abc",
                      "input": {
                        "source": "ABC",
                        "priorMid": "ABC",
                        "priorOut": "STALE"
                      },
                      "observed": {
                        "clean": [
                          {
                            "target": "mid",
                            "value": "ABC"
                          },
                          {
                            "target": "out",
                            "value": "ABC-X"
                          }
                        ],
                        "changed": [
                          {
                            "target": "out",
                            "value": "ABC-X"
                          }
                        ],
                        "errors": [],
                        "cleared": [],
                        "applied": [
                          {
                            "target": "mid",
                            "value": "ABC"
                          },
                          {
                            "target": "out",
                            "value": "ABC-X"
                          }
                        ]
                      }
                    },
                    {
                      "id": "source-absent-mid-old",
                      "input": {
                        "source": null,
                        "priorMid": "OLD",
                        "priorOut": "STALE"
                      },
                      "observed": {
                        "clean": [
                          {
                            "target": "out",
                            "value": "-X"
                          }
                        ],
                        "changed": [
                          {
                            "target": "out",
                            "value": "-X"
                          }
                        ],
                        "errors": [],
                        "cleared": [
                          "mid"
                        ],
                        "applied": [
                          {
                            "target": "mid",
                            "value": null
                          },
                          {
                            "target": "out",
                            "value": "-X"
                          }
                        ]
                      }
                    },
                    {
                      "id": "source-absent-mid-absent",
                      "input": {
                        "source": null,
                        "priorMid": null,
                        "priorOut": "STALE"
                      },
                      "observed": {
                        "clean": [
                          {
                            "target": "out",
                            "value": "-X"
                          }
                        ],
                        "changed": [
                          {
                            "target": "out",
                            "value": "-X"
                          }
                        ],
                        "errors": [],
                        "cleared": [],
                        "applied": [
                          {
                            "target": "mid",
                            "value": null
                          },
                          {
                            "target": "out",
                            "value": "-X"
                          }
                        ]
                      }
                    },
                    {
                      "id": "source-abcd-mid-old",
                      "input": {
                        "source": "ABCD",
                        "priorMid": "OLD",
                        "priorOut": "STALE"
                      },
                      "observed": {
                        "clean": [],
                        "changed": [],
                        "errors": [
                          {
                            "target": "mid",
                            "attempted": "ABCD",
                            "cause": "tooLong"
                          }
                        ],
                        "cleared": [
                          "out"
                        ],
                        "applied": [
                          {
                            "target": "mid",
                            "value": null
                          },
                          {
                            "target": "out",
                            "value": null
                          }
                        ]
                      }
                    }
                  ]
                }
              ]
            }
            """;
}
