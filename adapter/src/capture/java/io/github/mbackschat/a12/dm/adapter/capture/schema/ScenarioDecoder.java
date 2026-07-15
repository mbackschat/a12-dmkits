package io.github.mbackschat.a12.dm.adapter.capture.schema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ChannelId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;

/**
 * The ONE closed structural decoder for {@code capture-scenario-set-v1} — shared verbatim by
 * {@code validate-scenarios} and {@code capture}, so both make the SAME acceptance decision
 * ({@code DecoderParityTest} guards it). Closed means: every object's member set is exactly enumerated
 * and an unknown member is a rejection — which is also what makes the schema expectation-free by
 * construction (there IS no {@code expected} member to fill; {@link #decode} names that case explicitly
 * in its diagnostic).
 *
 * <p>Decoding collects EVERY problem with its location, then rejects once — never a first-error-only
 * refusal, never a partial result.
 */
public final class ScenarioDecoder {

    private static final Pattern CLOCK = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
    private static final Set<String> EXPECTATION_MEMBERS = Set.of("expected", "computeExpected", "expectedResults");

    private final List<String> problems = new ArrayList<>();

    private ScenarioDecoder() {
    }

    /** Decode {@code root} or throw {@link ScenarioRejectedException} naming every problem. */
    public static ScenarioSet decode(Json root) {
        ScenarioDecoder decoder = new ScenarioDecoder();
        ScenarioSet set = decoder.decodeRoot(root);
        if (!decoder.problems.isEmpty()) {
            throw new ScenarioRejectedException(decoder.problems);
        }
        return set;
    }

    private ScenarioSet decodeRoot(Json root) {
        Closed obj = closed(root, "$", "schema", "scenarioSet", "operationSchema", "requiredCapabilities",
                "world", "models", "requiredRunners", "qualificationPolicy", "observationRequirement", "cases");
        if (obj == null) {
            return null;
        }
        expectExact(obj, "schema", SchemaIds.SCENARIO_SET);
        expectExact(obj, "operationSchema", SchemaIds.COMPUTE_OBSERVATION);

        Closed identity = closed(obj.member("scenarioSet"), "$.scenarioSet", "id", "version");
        String setId = str(identity, "id");
        String setVersion = str(identity, "version");

        Closed capability = closed(obj.member("requiredCapabilities"), "$.requiredCapabilities", "schema", "version");
        expectExact(capability, "schema", SchemaIds.CAPABILITIES);
        expectExact(capability, "version", SchemaIds.CAPABILITIES_VERSION);

        Closed world = closed(obj.member("world"), "$.world", "profile", "locale");
        expectExact(world, "profile", SchemaIds.WORLD_PROFILE_LOCAL_WALLCLOCK);
        expectExact(world, "locale", "en_US");

        List<ScenarioSet.ModelRef> models = decodeModels(obj.member("models"));
        List<RunnerId> runners = decodeRunners(obj.member("requiredRunners"));
        PolicyId policy = decodePolicy(str(obj, "qualificationPolicy"));
        ScenarioSet.ObservationRequirement requirement = decodeRequirement(obj.member("observationRequirement"));
        List<ScenarioSet.ScenarioCase> cases = decodeCases(obj.member("cases"), models);

        if (!problems.isEmpty()) {
            return null;
        }
        return new ScenarioSet(setId, setVersion, SchemaIds.COMPUTE_OBSERVATION,
                SchemaIds.CAPABILITIES, SchemaIds.CAPABILITIES_VERSION,
                SchemaIds.WORLD_PROFILE_LOCAL_WALLCLOCK, "en_US",
                models, runners, policy, requirement, cases);
    }

    private List<ScenarioSet.ModelRef> decodeModels(Json node) {
        List<ScenarioSet.ModelRef> models = new ArrayList<>();
        List<Json> items = arr(node, "$.models", true);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String at = "$.models[" + i + "]";
            Closed model = closed(items.get(i), at, "ref", "sha256");
            String ref = str(model, "ref");
            String sha = str(model, "sha256");
            if (ref != null) {
                checkRefShape(ref, at + ".ref");
                if (!seen.add(ref)) {
                    problems.add(at + ".ref: duplicate model ref \"" + ref + "\"");
                }
            }
            if (sha != null && !Digests.isSha256(sha)) {
                problems.add(at + ".sha256: not a lowercase-hex SHA-256");
            }
            if (ref != null && sha != null) {
                models.add(new ScenarioSet.ModelRef(ref, sha));
            }
        }
        return models;
    }

    /** Reference shape safety (resolution against the root happens at load): relative, forward slashes,
     *  under {@code models/}, no traversal step, no backslash, no empty segment. */
    private void checkRefShape(String ref, String at) {
        if (ref.startsWith("/") || ref.contains("\\") || ref.contains("//")
                || List.of(ref.split("/")).contains("..") || List.of(ref.split("/")).contains(".")) {
            problems.add(at + ": unsafe reference \"" + ref + "\" — refs are scenario-root-relative, forward-slash,"
                    + " and may not traverse (no \"..\", no absolute path)");
        } else if (!ref.startsWith("models/") || ref.length() <= "models/".length()) {
            problems.add(at + ": reference \"" + ref + "\" must lie under \"models/\"");
        }
    }

    private List<RunnerId> decodeRunners(Json node) {
        List<RunnerId> runners = new ArrayList<>();
        List<Json> items = arr(node, "$.requiredRunners", true);
        for (int i = 0; i < items.size(); i++) {
            String at = "$.requiredRunners[" + i + "]";
            String wire = strValue(items.get(i), at);
            if (wire == null) {
                continue;
            }
            Optional<RunnerId> id = RunnerId.ofWire(wire);
            if (id.isEmpty()) {
                problems.add(at + ": unknown runner \"" + wire + "\" — registered: " + wires(RunnerId.values()));
            } else if (runners.contains(id.get())) {
                problems.add(at + ": duplicate runner \"" + wire + "\"");
            } else {
                runners.add(id.get());
            }
        }
        return runners;
    }

    private PolicyId decodePolicy(String wire) {
        if (wire == null) {
            return null;
        }
        return PolicyId.ofWire(wire).orElseGet(() -> {
            problems.add("$.qualificationPolicy: unknown policy \"" + wire + "\" — published: "
                    + java.util.Arrays.stream(PolicyId.values()).map(PolicyId::wire).toList());
            return null;
        });
    }

    private ScenarioSet.ObservationRequirement decodeRequirement(Json node) {
        Closed req = closed(node, "$.observationRequirement", "id", "version", "requiredChannels",
                "comparisonProjections");
        String id = str(req, "id");
        String version = str(req, "version");

        List<ChannelId> channels = new ArrayList<>();
        List<Json> channelItems = arr(req == null ? null : req.member("requiredChannels"),
                "$.observationRequirement.requiredChannels", true);
        for (int i = 0; i < channelItems.size(); i++) {
            String at = "$.observationRequirement.requiredChannels[" + i + "]";
            String wire = strValue(channelItems.get(i), at);
            if (wire == null) {
                continue;
            }
            Optional<ChannelId> channel = ChannelId.ofWire(wire);
            if (channel.isEmpty()) {
                problems.add(at + ": unknown channel \"" + wire + "\" — the compute-observation-v1 channels: "
                        + java.util.Arrays.stream(ChannelId.values()).map(ChannelId::wire).toList());
            } else if (channels.contains(channel.get())) {
                problems.add(at + ": duplicate channel \"" + wire + "\"");
            } else {
                channels.add(channel.get());
            }
        }

        List<ProjectionId> projections = new ArrayList<>();
        List<Json> projectionItems = arr(req == null ? null : req.member("comparisonProjections"),
                "$.observationRequirement.comparisonProjections", true);
        for (int i = 0; i < projectionItems.size(); i++) {
            String at = "$.observationRequirement.comparisonProjections[" + i + "]";
            String wire = strValue(projectionItems.get(i), at);
            if (wire == null) {
                continue;
            }
            Optional<ProjectionId> projection = ProjectionId.ofWire(wire);
            if (projection.isEmpty()) {
                problems.add(at + ": unknown projection \"" + wire + "\" — published: "
                        + java.util.Arrays.stream(ProjectionId.values()).map(ProjectionId::wire).toList());
            } else if (projections.contains(projection.get())) {
                problems.add(at + ": duplicate projection \"" + wire + "\"");
            } else {
                projections.add(projection.get());
            }
        }

        if (id == null || version == null) {
            return null;
        }
        return new ScenarioSet.ObservationRequirement(id, version, channels, projections);
    }

    private List<ScenarioSet.ScenarioCase> decodeCases(Json node, List<ScenarioSet.ModelRef> models) {
        List<ScenarioSet.ScenarioCase> cases = new ArrayList<>();
        List<Json> items = arr(node, "$.cases", true);
        Set<String> ids = new HashSet<>();
        Set<String> declaredRefs = new HashSet<>(models.stream().map(ScenarioSet.ModelRef::ref).toList());
        for (int i = 0; i < items.size(); i++) {
            String at = "$.cases[" + i + "]";
            Closed c = closed(items.get(i), at, "caseId", "modelRef", "operation", "clock", "placements", "probes");
            if (c == null) {
                continue;
            }
            String caseId = str(c, "caseId");
            if (caseId != null && !ids.add(caseId)) {
                problems.add(at + ".caseId: duplicate case id \"" + caseId + "\"");
            }
            String modelRef = str(c, "modelRef");
            if (modelRef != null && !declaredRefs.contains(modelRef)) {
                problems.add(at + ".modelRef: \"" + modelRef + "\" is not declared in $.models");
            }
            expectExact(c, "operation", "compute");
            Optional<String> clock = optStr(c, "clock");
            clock.ifPresent(value -> {
                if (!CLOCK.matcher(value).matches()) {
                    problems.add(at + ".clock: \"" + value + "\" is not a local wall-clock instant"
                            + " (yyyy-MM-dd'T'HH:mm:ss, no zone)");
                }
            });
            List<ScenarioSet.Placement> placements = decodePlacements(c.member("placements"), at + ".placements");
            List<String> probes = decodeProbes(c.memberOrNull("probes"), at + ".probes");
            if (caseId != null && modelRef != null) {
                cases.add(new ScenarioSet.ScenarioCase(caseId, modelRef, clock, placements, probes));
            }
        }
        return cases;
    }

    private List<ScenarioSet.Placement> decodePlacements(Json node, String at) {
        List<ScenarioSet.Placement> placements = new ArrayList<>();
        List<Json> items = arr(node, at, true);
        for (int i = 0; i < items.size(); i++) {
            String itemAt = at + "[" + i + "]";
            Closed p = closed(items.get(i), itemAt, "kind", "path", "reps", "value");
            if (p == null) {
                continue;
            }
            String kindWire = str(p, "kind");
            ScenarioSet.PlacementKind kind = null;
            if (kindWire != null) {
                try {
                    kind = ScenarioSet.PlacementKind.valueOf(kindWire);
                } catch (IllegalArgumentException e) {
                    problems.add(itemAt + ".kind: unknown placement kind \"" + kindWire + "\" (GROUP/FIELD/EMPTY)");
                }
            }
            String path = str(p, "path");
            if (path != null && !path.startsWith("/")) {
                problems.add(itemAt + ".path: \"" + path + "\" must be an absolute name-path");
            }
            List<Integer> reps = decodeReps(p.member("reps"), itemAt + ".reps");
            if (path != null && path.startsWith("/") && reps != null
                    && reps.size() != path.substring(1).split("/").length) {
                problems.add(itemAt + ".reps: " + reps.size() + " indices for " + path.substring(1).split("/").length
                        + " path segments — one 1-based index per segment, the addressed segment included");
            }
            Optional<String> value = optStr(p, "value");
            if (kind == ScenarioSet.PlacementKind.FIELD && value.isEmpty()) {
                problems.add(itemAt + ": a FIELD placement requires \"value\"");
            }
            if (kind != null && kind != ScenarioSet.PlacementKind.FIELD && value.isPresent()) {
                problems.add(itemAt + ": \"value\" is only legal on a FIELD placement (an empty cell is EMPTY)");
            }
            if (kind != null && path != null && reps != null) {
                placements.add(new ScenarioSet.Placement(kind, path, reps, value));
            }
        }
        return placements;
    }

    private List<Integer> decodeReps(Json node, String at) {
        if (!(node instanceof Json.Arr arr)) {
            problems.add(at + ": expected an array of 1-based repetition indices");
            return null;
        }
        List<Integer> reps = new ArrayList<>();
        for (int i = 0; i < arr.items().size(); i++) {
            if (arr.items().get(i) instanceof Json.Int n && n.value() >= 1) {
                reps.add((int) n.value());
            } else {
                problems.add(at + "[" + i + "]: repetition indices are integers >= 1");
                return null;
            }
        }
        return reps;
    }

    private List<String> decodeProbes(Json node, String at) {
        if (node == null) {
            return List.of();
        }
        List<String> probes = new ArrayList<>();
        List<Json> items = arr(node, at, false);
        for (int i = 0; i < items.size(); i++) {
            String pointer = strValue(items.get(i), at + "[" + i + "]");
            if (pointer != null) {
                if (!pointer.startsWith("/")) {
                    problems.add(at + "[" + i + "]: probe pointer \"" + pointer + "\" must be absolute");
                } else {
                    probes.add(pointer);
                }
            }
        }
        return probes;
    }

    // ---- closed-object plumbing -----------------------------------------------------------------------

    /** A closed object view: known members enumerated, unknown members rejected (expectation members get
     *  the explicit no-oracle diagnostic). */
    private final class Closed {
        private final Json.Obj obj;
        private final String at;

        Closed(Json.Obj obj, String at) {
            this.obj = obj;
            this.at = at;
        }

        Json member(String key) {
            Json value = obj.members().get(key);
            if (value == null) {
                problems.add(at + ": missing required member \"" + key + "\"");
            }
            return value;
        }

        Json memberOrNull(String key) {
            return obj.members().get(key);
        }
    }

    private Closed closed(Json node, String at, String... knownMembers) {
        if (!(node instanceof Json.Obj obj)) {
            problems.add(at + ": expected an object");
            return null;
        }
        Set<String> known = Set.of(knownMembers);
        for (String member : obj.members().keySet()) {
            if (known.contains(member)) {
                continue;
            }
            if (EXPECTATION_MEMBERS.contains(member)) {
                problems.add(at + ": member \"" + member + "\" is forbidden — a capture request asks questions and"
                        + " never carries expected runner results (capture-scenario-set-v1 has no such member;"
                        + " expected outcomes belong in a separately reviewed conformance artifact)");
            } else {
                problems.add(at + ": unknown member \"" + member + "\" (capture-scenario-set-v1 is closed; known: "
                        + known.stream().sorted().toList() + ")");
            }
        }
        return new Closed(obj, at);
    }

    private String str(Closed obj, String key) {
        if (obj == null) {
            return null;
        }
        return strValue(obj.member(key), obj.at + "." + key);
    }

    private Optional<String> optStr(Closed obj, String key) {
        Json value = obj.memberOrNull(key);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(strValue(value, obj.at + "." + key));
    }

    private String strValue(Json node, String at) {
        if (node instanceof Json.Str s && !s.value().isEmpty()) {
            return s.value();
        }
        if (node != null) {
            problems.add(at + ": expected a non-empty string");
        }
        return null;
    }

    private void expectExact(Closed obj, String key, String expected) {
        String actual = str(obj, key);
        if (actual != null && !expected.equals(actual)) {
            problems.add((obj.at.equals("$") ? "$." + key : obj.at + "." + key) + ": unsupported value \"" + actual
                    + "\" — this tool supports exactly \"" + expected + "\" (unknown versions are rejected, never"
                    + " treated as \"latest\")");
        }
    }

    private List<Json> arr(Json node, String at, boolean nonEmpty) {
        if (!(node instanceof Json.Arr arr)) {
            if (node != null) {
                problems.add(at + ": expected an array");
            }
            return List.of();
        }
        if (nonEmpty && arr.items().isEmpty()) {
            problems.add(at + ": must not be empty");
        }
        return arr.items();
    }

    private static List<String> wires(RunnerId[] values) {
        return java.util.Arrays.stream(values).map(RunnerId::wire).toList();
    }
}
