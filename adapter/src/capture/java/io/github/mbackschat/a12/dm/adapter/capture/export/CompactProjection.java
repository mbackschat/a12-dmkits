package io.github.mbackschat.a12.dm.adapter.capture.export;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Digests;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.packet.CaptureRefusedException;
import io.github.mbackschat.a12.dm.adapter.capture.policy.PolicyEngine;
import io.github.mbackschat.a12.dm.adapter.capture.policy.Projections;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;

/**
 * The compact semantic-observation projection of the retained {@code string-direct-cascade-v1}
 * evidence unit (CONFORMANCE-CORPUS-SPEC §15h; the client proposal's exact wire contract, consumed
 * by a12-kernel-lean's {@code ObservationBundle}/{@code StringCascadeProjection}). The family is
 * CLOSED — {@link CompactObservationTool} pins the copied request byte-exactly through the frozen
 * {@link #REQUEST_SHA256}, so case order, placements, probes, and input values are never hand-coded
 * here; this projection validates the placement SHAPE it derives input from and maps the
 * {@code kernel-groovy-dynamic} anchor's six V1 channels to the five compact members
 * ({@code formalErrorsInOperands} must be available and empty and is omitted). Observation lists
 * are normalized to stable canonical target order (producer {@code mid} before consumer
 * {@code out}) preserving multiplicity.
 */
public final class CompactProjection {

    /** The frozen kernel behavior anchor of the one accepted evidence unit. */
    public static final String KERNEL_VERSION = "30.8.1";
    /** The frozen copied-request identity — pins case order, placements, probes, and every input
     *  value byte-exactly (producer check 2; the proposal's Frozen-inputs table). */
    public static final String REQUEST_SHA256 =
            "4c5d4911ecacde819618b3b921b0bd30aa34b514cb0bbf3f0d2b735f21a0fd43";
    private static final String FAMILY_ID = "string-direct-cascade-v1";
    private static final String FAMILY_VERSION = "1";
    private static final String PROJECTION_ID = "string-direct-cascade-semantic-v1";
    private static final long PROJECTION_VERSION = 1;
    private static final int FAMILY_CASES = 5;
    private static final List<Integer> GROUP_REPS = List.of(1);
    private static final List<Integer> FIELD_REPS = List.of(1, 1);
    private static final List<String> INPUT_PATHS =
            List.of("/Cascade/Source", "/Cascade/Mid", "/Cascade/Out");

    /** The two semantic targets in canonical rank order: producer {@code mid} before consumer
     *  {@code out} — raw target, probe pointer, and declared computation are one closed table. */
    private enum Target {
        MID("mid", "/Cascade[1]/Mid", "/Cascade/MidComputation"),
        OUT("out", "/Cascade[1]/Out", "/Cascade/OutComputation");

        private final String compact;
        private final String raw;
        private final String computation;

        Target(String compact, String raw, String computation) {
            this.compact = compact;
            this.raw = raw;
            this.computation = computation;
        }

        static Target ofRaw(String raw, String context) {
            for (Target target : values()) {
                if (target.raw.equals(raw)) {
                    return target;
                }
            }
            throw refuse(context + ": raw target \"" + raw + "\" is outside the closed family");
        }
    }

    /** One derived compact input; {@code null} means the placement is absent (JSON {@code null}). */
    private record CaseInput(String id, String source, String priorMid, String priorOut) {
    }

    /** The exporter's emitted source identity: its clean commit + the two out-of-band digests that
     *  separately anchor the historical raw capture and qualification. */
    public record SourceIdentity(String revision, String packetReceiptSha256,
            String qualificationReceiptSha256) {

        public SourceIdentity {
            require(revision != null && revision.matches("[0-9a-f]{40}"),
                    "the exporter revision must be a 40-character lowercase Git revision");
            require(Digests.isSha256(packetReceiptSha256) && Digests.isSha256(qualificationReceiptSha256),
                    "the out-of-band receipt digests must be lowercase-hex SHA-256 values");
        }
    }

    private CompactProjection() {
    }

    /** Producer check 4 (guard 5): the packet must re-qualify under {@code kernel-route-confirmed-v1}
     *  with ALL five kernel-route comparisons present and equal BEFORE any anchor projection. */
    public static void requireRouteConfirmed(PolicyEngine.Outcome outcome) {
        require("satisfied".equals(outcome.verdict()),
                "qualification not satisfied under " + PolicyId.KERNEL_ROUTE_CONFIRMED.wire() + ": "
                        + String.join("; ", outcome.reasons()));
        List<Projections.Comparison> route = outcome.comparisons().stream()
                .filter(comparison -> comparison.projection() == ProjectionId.COMPUTE_KERNEL_ROUTE)
                .toList();
        require(route.size() == FAMILY_CASES
                        && route.stream().allMatch(comparison -> "equal".equals(comparison.result())),
                "route confirmation requires all " + FAMILY_CASES + " "
                        + ProjectionId.COMPUTE_KERNEL_ROUTE.wire() + " comparisons present and equal");
    }

    /** The compact bundle (the wire contract): the verified request's cases + their anchor
     *  observations + the source identity, in canonical member order. */
    public static Json.Obj bundle(ScenarioSet request, Map<String, Json.Obj> anchorByCaseId,
            SourceIdentity identity) {
        require(FAMILY_ID.equals(request.scenarioSetId())
                        && FAMILY_VERSION.equals(request.scenarioSetVersion()),
                "the exporter accepts only scenario set " + FAMILY_ID + " version " + FAMILY_VERSION);
        require(request.qualificationPolicy() == PolicyId.KERNEL_ROUTE_CONFIRMED,
                "the family's request-declared policy is " + PolicyId.KERNEL_ROUTE_CONFIRMED.wire());
        require(request.cases().size() == FAMILY_CASES,
                "the family is closed at " + FAMILY_CASES + " cases");
        Json.Arr cases = Json.Arr.of();
        for (ScenarioSet.ScenarioCase scenarioCase : request.cases()) {
            CaseInput input = deriveInput(scenarioCase);
            Json.Obj anchor = anchorByCaseId.get(input.id());
            require(anchor != null, "case " + input.id() + ": no "
                    + RunnerId.KERNEL_GROOVY_DYNAMIC.wire() + " anchor observation");
            cases.add(Json.Obj.of()
                    .put("id", input.id())
                    .put("input", Json.Obj.of()
                            .put("source", nullable(input.source()))
                            .put("priorMid", nullable(input.priorMid()))
                            .put("priorOut", nullable(input.priorOut())))
                    .put("observed", observed(input.id(), anchor)));
        }
        Json.Obj family = Json.Obj.of()
                .put("id", FAMILY_ID)
                .put("projectionId", PROJECTION_ID)
                .put("projectionVersion", PROJECTION_VERSION)
                .put("source", Json.Obj.of()
                        .put("producer", "a12-dmkits")
                        .put("revision", identity.revision())
                        .put("rawCapture",
                                fileDigest("packet/RECEIPT.json", identity.packetReceiptSha256()))
                        .put("qualification", Json.Obj.of()
                                .put("policyId", PolicyId.KERNEL_ROUTE_CONFIRMED.wire())
                                .put("receipt", fileDigest("qualification/RECEIPT.json",
                                        identity.qualificationReceiptSha256()))))
                .put("cases", cases);
        return Json.Obj.of()
                .put("schemaVersion", 1)
                .put("kernelVersion", KERNEL_VERSION)
                .put("families", Json.Arr.of().add(family));
    }

    /** Producer check 6 (guard 4's shape half): the compact input DERIVES from the copied request's
     *  placements — never hand-coded — over the closed placement shape (one {@code /Cascade} group
     *  at repetition 1; optional {@code Source}/{@code Mid}/{@code Out} fields at repetitions
     *  [1, 1]); anything else refuses. */
    private static CaseInput deriveInput(ScenarioSet.ScenarioCase scenarioCase) {
        String context = "case " + scenarioCase.caseId();
        Map<String, String> fields = new LinkedHashMap<>();
        boolean group = false;
        for (ScenarioSet.Placement placement : scenarioCase.placements()) {
            switch (placement.kind()) {
                case GROUP -> {
                    require(!group && "/Cascade".equals(placement.path())
                                    && GROUP_REPS.equals(placement.reps()),
                            context + ": the family's one group placement is /Cascade at repetition 1");
                    group = true;
                }
                case FIELD -> {
                    require(FIELD_REPS.equals(placement.reps()),
                            context + ": the family's field placements carry repetitions [1, 1]");
                    require(INPUT_PATHS.contains(placement.path()), context + ": placement path "
                            + placement.path() + " is outside the closed family");
                    String value = placement.value().orElseThrow(() ->
                            refuse(context + ": field placement " + placement.path() + " has no value"));
                    require(fields.putIfAbsent(placement.path(), value) == null,
                            context + ": duplicate placement " + placement.path());
                }
                case EMPTY -> throw refuse(context + ": EMPTY placements are outside the closed family");
            }
        }
        require(group, context + ": the family requires the /Cascade group placement");
        return new CaseInput(scenarioCase.caseId(), fields.get("/Cascade/Source"),
                fields.get("/Cascade/Mid"), fields.get("/Cascade/Out"));
    }

    /** Producer checks 7+8: the six-channel anchor mapping at exact V1 granularities —
     *  {@code formalErrorsInOperands} must be available and EMPTY and is omitted from the compact
     *  observation (this family omits it only because every retained case is empty). */
    private static Json observed(String caseId, Json.Obj anchor) {
        String at = "case " + caseId;
        Json.Obj channels = obj(anchor, "channels", at);
        require(channel(channels, "formalErrorsInOperands", "operand-formal-errors", at).isEmpty(),
                at + ": the family requires formalErrorsInOperands available and EMPTY");
        return Json.Obj.of()
                .put("clean", valueEntries(
                        channel(channels, "withoutErrors", "all-computed-clean", at), at + ".clean"))
                .put("changed", valueEntries(
                        channel(channels, "changedSubset", "delta-vs-input", at), at + ".changed"))
                .put("errors", errorEntries(
                        channel(channels, "withErrors", "errored-instances", at), at + ".errors"))
                .put("cleared", clearedEntries(
                        channel(channels, "cleared", "input-filled-only", at), at + ".cleared"))
                .put("applied", appliedEntries(
                        channel(channels, "appliedState", "requested-probes", at), at + ".applied"));
    }

    private static List<Json> channel(Json.Obj channels, String name, String granularity, String at) {
        Json.Obj channel = obj(channels, name, at);
        require("available".equals(str(channel, "availability", at + "." + name)),
                at + "." + name + ": the family requires an available channel");
        require(granularity.equals(str(channel, "granularity", at + "." + name)),
                at + "." + name + ": the family requires granularity " + granularity);
        return arr(channel, "entries", at + "." + name);
    }

    private static Json.Arr valueEntries(List<Json> entries, String at) {
        List<Map.Entry<Target, Json>> mapped = new ArrayList<>();
        for (Json entry : entries) {
            Json.Obj observed = asObj(entry, at);
            Target target = target(observed, at);
            String value = agreedString(obj(observed, "value", at), at + " " + target.compact);
            mapped.add(Map.entry(target,
                    Json.Obj.of().put("target", target.compact).put("value", value)));
        }
        return normalized(mapped);
    }

    /** Producer check 10: ONLY the exact retained cause tuple maps to {@code tooLong}; every other
     *  code, type, pointer, missing field, or unavailable cause refuses. */
    private static Json.Arr errorEntries(List<Json> entries, String at) {
        List<Map.Entry<Target, Json>> mapped = new ArrayList<>();
        for (Json entry : entries) {
            Json.Obj observed = asObj(entry, at);
            Target target = target(observed, at);
            String attempted = agreedString(obj(observed, "attempted", at), at + " " + target.compact);
            Json.Obj cause = obj(observed, "cause", at);
            require("available".equals(str(cause, "availability", at))
                            && "stringZuLang".equals(str(cause, "code", at))
                            && "VALUE_ERROR".equals(str(cause, "messageType", at))
                            && "/Cascade[1]/Mid".equals(str(cause, "errorPointer", at)),
                    at + ": only the exact retained cause tuple stringZuLang / VALUE_ERROR /"
                            + " /Cascade[1]/Mid maps to tooLong");
            mapped.add(Map.entry(target, Json.Obj.of()
                    .put("target", target.compact)
                    .put("attempted", attempted)
                    .put("cause", "tooLong")));
        }
        return normalized(mapped);
    }

    private static Json.Arr clearedEntries(List<Json> entries, String at) {
        List<Map.Entry<Target, Json>> mapped = new ArrayList<>();
        for (Json entry : entries) {
            Target target = target(asObj(entry, at), at);
            mapped.add(Map.entry(target, new Json.Str(target.compact)));
        }
        return normalized(mapped);
    }

    /** Producer check 11: applied {@code presentValue} maps to its nonempty String; {@code absent}
     *  and {@code presentEmpty} both map to JSON {@code null} (the stronger distinction stays in
     *  the immutable raw packet — deliberately outside this value-only projection); unknown states
     *  refuse. */
    private static Json.Arr appliedEntries(List<Json> entries, String at) {
        List<Map.Entry<Target, Json>> mapped = new ArrayList<>();
        for (Json entry : entries) {
            Json.Obj observed = asObj(entry, at);
            Target target = Target.ofRaw(str(observed, "pointer", at), at);
            String state = str(observed, "state", at);
            Json value = switch (state) {
                case "presentValue" -> new Json.Str(agreedString(obj(observed, "value", at),
                        at + " " + target.compact));
                case "absent", "presentEmpty" -> Json.Null.NULL;
                default -> throw refuse(at + ": unknown applied state \"" + state + "\"");
            };
            mapped.add(Map.entry(target,
                    Json.Obj.of().put("target", target.compact).put("value", value)));
        }
        return normalized(mapped);
    }

    /** Producer check 9 (the target half): the raw target and its declared computation must agree
     *  with the closed target table. */
    private static Target target(Json.Obj entry, String at) {
        Target target = Target.ofRaw(str(entry, "target", at), at);
        require(target.computation.equals(str(entry, "declaredComputation", at)),
                at + ": the declared computation for " + target.compact + " must be " + target.computation);
        return target;
    }

    /** Producer check 9 (the value half): the typed value must be an available nonempty
     *  {@code STRING}, and typed and rendered text must agree wherever BOTH are available — a
     *  disagreement refuses, never a silent preference. */
    private static String agreedString(Json.Obj value, String at) {
        Json.Obj typed = obj(value, "typed", at);
        require("available".equals(str(typed, "availability", at)),
                at + ": the typed value must be available");
        require("STRING".equals(str(typed, "kind", at)), at + ": the family's typed kind is STRING");
        String text = str(typed, "value", at);
        require(!text.isEmpty(), at + ": empty stored strings are forbidden");
        Json.Obj rendered = obj(value, "rendered", at);
        if ("available".equals(str(rendered, "availability", at))) {
            require(text.equals(str(rendered, "value", at)),
                    at + ": typed and rendered values disagree — refusing, not preferring one");
        }
        return text;
    }

    /** Producer check 12: stable canonical target order — every {@code mid} entry before every
     *  {@code out} entry, same-target entries in raw order, multiplicity preserved (never a set,
     *  map, or value sort). */
    private static Json.Arr normalized(List<Map.Entry<Target, Json>> entries) {
        Json.Arr normalized = Json.Arr.of();
        for (Target target : Target.values()) {
            for (Map.Entry<Target, Json> entry : entries) {
                if (entry.getKey() == target) {
                    normalized.add(entry.getValue());
                }
            }
        }
        return normalized;
    }

    private static Json.Obj fileDigest(String path, String sha256) {
        return Json.Obj.of().put("path", path).put("sha256", sha256);
    }

    private static Json nullable(String value) {
        return value == null ? Json.Null.NULL : new Json.Str(value);
    }

    private static Json.Obj obj(Json.Obj parent, String member, String at) {
        return parent.members().get(member) instanceof Json.Obj value ? value
                : refused(at + ": missing or non-object member \"" + member + "\"");
    }

    private static Json.Obj asObj(Json value, String at) {
        return value instanceof Json.Obj observed ? observed : refused(at + ": expected an object entry");
    }

    private static String str(Json.Obj parent, String member, String at) {
        return parent.members().get(member) instanceof Json.Str value ? value.value()
                : refused(at + ": missing or non-string member \"" + member + "\"");
    }

    private static List<Json> arr(Json.Obj parent, String member, String at) {
        return parent.members().get(member) instanceof Json.Arr value ? value.items()
                : refused(at + ": missing or non-array member \"" + member + "\"");
    }

    private static void require(boolean satisfied, String message) {
        if (!satisfied) {
            throw refuse(message);
        }
    }

    private static <T> T refused(String message) {
        throw refuse(message);
    }

    private static CaptureRefusedException refuse(String message) {
        return new CaptureRefusedException(message);
    }
}
