package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.util.List;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ChannelId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerFidelity;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * The CLOSED shape of one {@code compute-observation-v1} artifact — the read-side mirror of
 * {@code ObservationJson} (audit Finding 2): every object's member set is exactly enumerated (an unknown
 * member is a problem), every enum-like value must be in its closed vocabulary, and the channel
 * availabilities/granularities of a SUCCESS observation must EQUAL the compile-time registry fidelity of
 * the claiming runner — a forged availability (fidelity claimed away, or claimed beyond the registry) is
 * an integrity problem, refused before any policy reads the observation. Problems are collected with
 * their location, never first-error-only.
 */
final class ObservationShape {

    private static final Set<String> STATUS_WIRES = Set.of("success", "unsupported", "timeout",
            "technicalFailure");
    private static final Set<String> AVAILABILITY_WIRES = Set.of("available", "notExposedByRunner",
            "notApplicable");
    private static final Set<String> APPLIED_STATE_WIRES = Set.of("absent", "presentEmpty", "presentValue",
            "noValueIndeterminate");
    private static final Set<String> VALUE_KINDS = Set.of("STRING", "DECIMAL", "BOOLEAN", "INSTANT",
            "INSTANT_RANGE");

    private final List<String> problems;
    private final String at;

    ObservationShape(List<String> problems, String at) {
        this.problems = problems;
        this.at = at;
    }

    /** Validate {@code artifact} as the closed observation of ({@code runner}, case) — shape only; the
     *  caller cross-checks the relational claims (caseId, status, model/document identities). */
    void check(Json.Obj artifact, RunnerId runner) {
        closed(artifact, "", "schema", "caseId", "runner", "status", "statusDetail", "consumedModel",
                "consumedDocument", "channels");
        String schema = str(artifact, "", "schema");
        if (schema != null && !SchemaIds.COMPUTE_OBSERVATION.equals(schema)) {
            problems.add(at + ".schema: \"" + schema + "\" — this verifier supports exactly \""
                    + SchemaIds.COMPUTE_OBSERVATION + "\" (unknown versions are rejected)");
        }
        String status = str(artifact, "", "status");
        if (status != null && !STATUS_WIRES.contains(status)) {
            problems.add(at + ".status: unknown status \"" + status + "\"");
            return;
        }
        boolean success = "success".equals(status);
        Json statusDetail = artifact.members().get("statusDetail");
        if (success && !(statusDetail instanceof Json.Null)) {
            problems.add(at + ".statusDetail: a success observation carries no status detail");
        }
        if (!success && !(statusDetail instanceof Json.Str)) {
            problems.add(at + ".statusDetail: a non-success observation names its sanitized detail");
        }

        if (artifact.members().get("consumedModel") instanceof Json.Obj consumedModel) {
            closed(consumedModel, ".consumedModel", "ref", "suppliedSha256", "adaptedSha256");
        } else {
            problems.add(at + ".consumedModel: expected an object");
        }

        Json consumedDocument = artifact.members().get("consumedDocument");
        if (success) {
            if (consumedDocument instanceof Json.Obj obj) {
                closed(obj, ".consumedDocument", "placements", "canonicalSha256");
                if (!(obj.members().get("placements") instanceof Json.Int)) {
                    problems.add(at + ".consumedDocument.placements: expected an integer");
                }
                String sha = str(obj, ".consumedDocument", "canonicalSha256");
                if (sha != null && !Digests.isSha256(sha)) {
                    problems.add(at + ".consumedDocument.canonicalSha256: not a lowercase-hex SHA-256");
                }
            } else {
                problems.add(at + ".consumedDocument: a success observation binds the consumed-document"
                        + " identity");
            }
        } else if (!(consumedDocument instanceof Json.Null)) {
            problems.add(at + ".consumedDocument: only a completed run testifies to what it consumed");
        }

        if (!(artifact.members().get("channels") instanceof Json.Obj channels)) {
            problems.add(at + ".channels: expected an object");
            return;
        }
        closed(channels, ".channels",
                java.util.Arrays.stream(ChannelId.values()).map(ChannelId::wire).toArray(String[]::new));
        RunnerFidelity fidelity = RunnerFidelity.of(runner);
        for (ChannelId channel : ChannelId.values()) {
            if (channels.members().get(channel.wire()) instanceof Json.Obj channelObj) {
                checkChannel(channel, channelObj, success, fidelity);
            } else {
                problems.add(at + ".channels." + channel.wire() + ": expected an object");
            }
        }
    }

    private void checkChannel(ChannelId channel, Json.Obj obj, boolean success, RunnerFidelity fidelity) {
        String where = ".channels." + channel.wire();
        closed(obj, where, "availability", "granularity", "order", "entries");
        String availability = str(obj, where, "availability");
        String granularity = str(obj, where, "granularity");
        String order = str(obj, where, "order");
        if (availability != null && !AVAILABILITY_WIRES.contains(availability)) {
            problems.add(at + where + ".availability: unknown availability \"" + availability + "\"");
            return;
        }
        boolean available = "available".equals(availability);
        if (order != null && !order.equals(available ? "observed" : "notApplicable")) {
            problems.add(at + where + ".order: \"" + order + "\" contradicts the availability");
        }
        RunnerFidelity.ChannelFidelity declared = fidelity.channel(channel);
        if (success) {
            // The registry is the fidelity ground truth: a success observation may neither claim away a
            // declared channel nor claim one the runner does not expose.
            if (availability != null && !availability.equals(declared.availability().wire())) {
                problems.add(at + where + ".availability: \"" + availability + "\" contradicts the"
                        + " registry-declared fidelity \"" + declared.availability().wire() + "\" of this"
                        + " runner — fidelity is never forged");
            }
            String expectedGranularity = declared.availability() == Availability.AVAILABLE
                    ? declared.granularity() : RunnerFidelity.NOT_EXPOSED;
            if (granularity != null && !granularity.equals(expectedGranularity)) {
                problems.add(at + where + ".granularity: \"" + granularity + "\" contradicts the"
                        + " registry-declared granularity \"" + expectedGranularity + "\"");
            }
        } else if (availability != null && !"notApplicable".equals(availability)) {
            problems.add(at + where + ".availability: a non-success run carries no semantic channels"
                    + " (every channel is notApplicable — a failure is never serialized as an observation)");
        }
        if (!(obj.members().get("entries") instanceof Json.Arr entries)) {
            problems.add(at + where + ".entries: expected an array");
            return;
        }
        if (!available && !entries.items().isEmpty()) {
            problems.add(at + where + ".entries: an unavailable channel carries no entries");
        }
        for (int i = 0; i < entries.items().size(); i++) {
            if (entries.items().get(i) instanceof Json.Obj entry) {
                checkEntry(channel, entry, where + ".entries[" + i + "]");
            } else {
                problems.add(at + where + ".entries[" + i + "]: expected an object");
            }
        }
    }

    private void checkEntry(ChannelId channel, Json.Obj entry, String where) {
        switch (channel) {
            case WITHOUT_ERRORS, CHANGED_SUBSET -> {
                closed(entry, where, "target", "declaredComputation", "value");
                checkPointer(entry, where, "target");
                checkObservedValue(entry.members().get("value"), where + ".value");
            }
            case WITH_ERRORS -> {
                closed(entry, where, "target", "declaredComputation", "attempted", "cause");
                checkPointer(entry, where, "target");
                checkObservedValue(entry.members().get("attempted"), where + ".attempted");
                checkCause(entry.members().get("cause"), where + ".cause");
            }
            case CLEARED -> {
                closed(entry, where, "target", "declaredComputation");
                checkPointer(entry, where, "target");
            }
            case FORMAL_ERRORS_IN_OPERANDS -> {
                closed(entry, where, "code", "messageType", "pointer");
                checkPointer(entry, where, "pointer");
            }
            case APPLIED_STATE -> {
                String state = str(entry, where, "state");
                boolean presentValue = "presentValue".equals(state);
                closed(entry, where, presentValue
                        ? new String[] {"pointer", "state", "value"} : new String[] {"pointer", "state"});
                checkPointer(entry, where, "pointer");
                if (state != null && !APPLIED_STATE_WIRES.contains(state)) {
                    problems.add(at + where + ".state: unknown applied state \"" + state + "\"");
                }
                if (presentValue) {
                    checkObservedValue(entry.members().get("value"), where + ".value");
                }
            }
        }
    }

    private void checkObservedValue(Json value, String where) {
        if (!(value instanceof Json.Obj obj)) {
            problems.add(at + where + ": expected an observed-value object");
            return;
        }
        closed(obj, where, "typed", "rendered");
        if (obj.members().get("typed") instanceof Json.Obj typed) {
            String kind = typed.members().get("kind") instanceof Json.Str k ? k.value() : null;
            if ("INSTANT_RANGE".equals(kind)) {
                closed(typed, where + ".typed", "availability", "kind", "start", "end");
            } else if (kind != null) {
                closed(typed, where + ".typed", "availability", "kind", "value");
                if (!VALUE_KINDS.contains(kind)) {
                    problems.add(at + where + ".typed.kind: unknown value kind \"" + kind + "\"");
                }
            } else {
                closed(typed, where + ".typed", "availability");
            }
            checkAvailability(typed, where + ".typed");
        } else {
            problems.add(at + where + ".typed: expected an object");
        }
        if (obj.members().get("rendered") instanceof Json.Obj rendered) {
            closed(rendered, where + ".rendered", rendered.members().containsKey("value")
                    ? new String[] {"availability", "value"} : new String[] {"availability"});
            checkAvailability(rendered, where + ".rendered");
        } else {
            problems.add(at + where + ".rendered: expected an object");
        }
    }

    private void checkCause(Json cause, String where) {
        if (!(cause instanceof Json.Obj obj)) {
            problems.add(at + where + ": expected a cause object");
            return;
        }
        if (obj.members().containsKey("code")) {
            closed(obj, where, "availability", "code", "messageType", "errorPointer");
        } else {
            closed(obj, where, "availability");
        }
        checkAvailability(obj, where);
    }

    private void checkAvailability(Json.Obj obj, String where) {
        String availability = str(obj, where, "availability");
        if (availability != null && !AVAILABILITY_WIRES.contains(availability)) {
            problems.add(at + where + ".availability: unknown availability \"" + availability + "\"");
        }
    }

    private void checkPointer(Json.Obj entry, String where, String member) {
        String pointer = str(entry, where, member);
        if (pointer != null && !pointer.startsWith("/")) {
            problems.add(at + where + "." + member + ": pointer \"" + pointer + "\" must be absolute");
        }
    }

    // ---- closed-object plumbing (problem-collecting, mirroring the scenario decoder) -------------------

    private void closed(Json.Obj obj, String where, String... known) {
        Set<String> knownSet = Set.of(known);
        for (String member : obj.members().keySet()) {
            if (!knownSet.contains(member)) {
                problems.add(at + where + ": unknown member \"" + member + "\" — "
                        + SchemaIds.COMPUTE_OBSERVATION + " is closed");
            }
        }
        for (String member : known) {
            if (!obj.members().containsKey(member)) {
                problems.add(at + where + ": missing required member \"" + member + "\"");
            }
        }
    }

    String str(Json.Obj obj, String where, String member) {
        if (obj.members().get(member) instanceof Json.Str s) {
            return s.value();
        }
        if (obj.members().containsKey(member)) {
            problems.add(at + where + "." + member + ": expected a string");
        }
        return null;
    }
}
