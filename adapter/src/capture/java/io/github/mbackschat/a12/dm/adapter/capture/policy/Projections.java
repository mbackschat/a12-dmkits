package io.github.mbackschat.a12.dm.adapter.capture.policy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ChannelId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;

/**
 * The IMMUTABLE named comparison projections (proposal §5). Every comparison names its two runners, the
 * projection id + version, and its included/unavailable fields — there is no global {@code agrees}
 * Boolean. Projections compare canonical MULTISETS per channel (the public collections carry no order
 * contract; the raw observations keep the observed sequence); they never reconstruct a channel, and a
 * required channel a runner declares unavailable surfaces as {@code unavailableRequired} — the policies
 * fail closed on it.
 */
public final class Projections {

    /** One channel's comparison outcome. */
    public record ChannelResult(String channel, String result, String detail) {
        static ChannelResult equal(String channel) {
            return new ChannelResult(channel, "equal", null);
        }

        static ChannelResult divergent(String channel, String detail) {
            return new ChannelResult(channel, "divergent", detail);
        }

        static ChannelResult excluded(String channel, String rationale) {
            return new ChannelResult(channel, "excluded", rationale);
        }

        static ChannelResult unavailableRequired(String channel) {
            return new ChannelResult(channel, "unavailableRequired",
                    "a required channel the runner declares notExposedByRunner — fails closed");
        }
    }

    /** One named comparison: two runners, one projection, per-channel results, an overall result. */
    public record Comparison(String caseId, RunnerId runnerA, RunnerId runnerB, ProjectionId projection,
            List<String> includedFields, List<String> unavailableFields, List<ChannelResult> channels,
            String result) {
    }

    /** The interpreter-excluded fields of the portable projection, each with its rationale — named in the
     *  profile so the claim is never mistaken for full rich-result conformance. */
    public static final List<String[]> PORTABLE_EXCLUSIONS = List.of(
            new String[] {"typedValues", "the interpreter exposes rendered form only"},
            new String[] {"structuredCauses", "no interpreter cause transport — never rendered as \"no cause\""},
            new String[] {"changedSubset", "report-all granularity exposes no delta subset"},
            new String[] {"formalErrorsInOperands", "not exposed on the interpreter's compute surface"},
            new String[] {"appliedState.absentVsPresentEmpty",
                    "the interpreter's public read surface has no absent-vs-empty distinction (IG72)"});

    private Projections() {
    }

    // ---- compute-projection-kernel-route-v1 --------------------------------------------------------------

    /** All six channels, typed + rendered values, causes, applied states — canonical multisets between the
     *  two kernel routes. */
    public static Comparison kernelRoute(String caseId, Json.Obj groovy, Json.Obj javaStatic) {
        List<ChannelResult> channels = new ArrayList<>();
        boolean divergent = false;
        for (ChannelId channel : ChannelId.values()) {
            ChannelResult result = compareCanonicalEntries(channel.wire(),
                    entries(groovy, channel.wire()), entries(javaStatic, channel.wire()));
            channels.add(result);
            divergent |= !"equal".equals(result.result());
        }
        return new Comparison(caseId, RunnerId.KERNEL_GROOVY_DYNAMIC, RunnerId.KERNEL_JAVA_STATIC,
                ProjectionId.COMPUTE_KERNEL_ROUTE,
                List.of("allChannels", "typedValues", "renderedValues", "structuredCauses", "appliedStates"),
                List.of(), channels, divergent ? "divergent" : "equal");
    }

    // ---- compute-projection-dmkits-portable-v1 -----------------------------------------------------------

    /**
     * The interpreter-comparable subset against the anchor: target pointers + outcome classification +
     * rendered values; the interpreter's report-all {@code cleared} entries are restricted to
     * INPUT-FILLED cells before comparing (the kernel channel is input-filled-only); applied states are
     * projected to has-value(text) vs no-value. Exclusions per {@link #PORTABLE_EXCLUSIONS}.
     */
    public static Comparison dmkitsPortable(String caseId, Json.Obj anchor, Json.Obj interpreter,
            Set<String> inputFilledPointers) {
        List<ChannelResult> channels = new ArrayList<>();
        boolean divergent = false;
        boolean unavailable = false;
        List<String> unavailableFields = new ArrayList<>();

        for (ChannelId channel : List.of(ChannelId.WITHOUT_ERRORS, ChannelId.WITH_ERRORS, ChannelId.CLEARED,
                ChannelId.APPLIED_STATE)) {
            if (!"available".equals(availability(interpreter, channel.wire()))) {
                channels.add(ChannelResult.unavailableRequired(channel.wire()));
                unavailableFields.add(channel.wire());
                unavailable = true;
                continue;
            }
            ChannelResult result = switch (channel) {
                case WITHOUT_ERRORS -> compareProjected(channel.wire(),
                        renderedValues(anchor, channel.wire()), renderedValues(interpreter, channel.wire()));
                case WITH_ERRORS -> compareProjected(channel.wire(),
                        attemptedValues(anchor), attemptedValues(interpreter));
                case CLEARED -> compareProjected(channel.wire(),
                        clearedTargets(anchor, null), clearedTargets(interpreter, inputFilledPointers));
                case APPLIED_STATE -> compareProjected(channel.wire(),
                        appliedProjection(anchor), appliedProjection(interpreter));
                default -> throw new IllegalStateException("not a portable channel: " + channel);
            };
            channels.add(result);
            divergent |= !"equal".equals(result.result());
        }
        channels.add(ChannelResult.excluded(ChannelId.CHANGED_SUBSET.wire(),
                "report-all granularity exposes no delta subset"));
        channels.add(ChannelResult.excluded(ChannelId.FORMAL_ERRORS_IN_OPERANDS.wire(),
                "not exposed on the interpreter's compute surface"));

        String result = unavailable ? "unavailableRequired" : divergent ? "divergent" : "equal";
        return new Comparison(caseId, RunnerId.KERNEL_GROOVY_DYNAMIC, RunnerId.A12_DMKITS_INTERPRETER,
                ProjectionId.COMPUTE_DMKITS_PORTABLE,
                List.of("targets", "outcomeClassification", "renderedValues", "attemptedRenderedValues",
                        "clearedAtKernelGranularity", "appliedValueText"),
                unavailableFields, channels, result);
    }

    // ---- projection plumbing ------------------------------------------------------------------------------

    private static ChannelResult compareCanonicalEntries(String channel, List<Json> a, List<Json> b) {
        return compareProjected(channel,
                a.stream().map(e -> new String(Json.canonicalBytes(e), StandardCharsets.UTF_8)).toList(),
                b.stream().map(e -> new String(Json.canonicalBytes(e), StandardCharsets.UTF_8)).toList());
    }

    private static ChannelResult compareProjected(String channel, List<String> a, List<String> b) {
        List<String> left = a.stream().sorted().toList();
        List<String> right = b.stream().sorted().toList();
        if (left.equals(right)) {
            return ChannelResult.equal(channel);
        }
        List<String> aOnly = new ArrayList<>(left);
        right.forEach(aOnly::remove);
        List<String> bOnly = new ArrayList<>(right);
        left.forEach(bOnly::remove);
        return ChannelResult.divergent(channel, "a-only=" + aOnly + " b-only=" + bOnly);
    }

    private static String availability(Json.Obj observation, String channel) {
        return VerifiedPacket.str(channelObj(observation, channel), "availability");
    }

    private static Json.Obj channelObj(Json.Obj observation, String channel) {
        return (Json.Obj) ((Json.Obj) observation.members().get("channels")).members().get(channel);
    }

    private static List<Json> entries(Json.Obj observation, String channel) {
        return ((Json.Arr) channelObj(observation, channel).members().get("entries")).items();
    }

    private static List<String> renderedValues(Json.Obj observation, String channel) {
        return entries(observation, channel).stream()
                .map(e -> (Json.Obj) e)
                .map(e -> VerifiedPacket.str(e, "target") + "|" + renderedOf(e, "value"))
                .toList();
    }

    private static List<String> attemptedValues(Json.Obj observation) {
        return entries(observation, ChannelId.WITH_ERRORS.wire()).stream()
                .map(e -> (Json.Obj) e)
                .map(e -> VerifiedPacket.str(e, "target") + "|" + renderedOf(e, "attempted"))
                .toList();
    }

    private static List<String> clearedTargets(Json.Obj observation, Set<String> restrictToPointers) {
        return entries(observation, ChannelId.CLEARED.wire()).stream()
                .map(e -> VerifiedPacket.str((Json.Obj) e, "target"))
                .filter(target -> restrictToPointers == null || restrictToPointers.contains(target))
                .toList();
    }

    /** presentValue → {@code pointer|hasValue|text} (the kernel's typed value in canonical string form vs
     *  the interpreter's rendered raw — the declared string-comparison rule); every no-value state
     *  ({@code absent} / {@code presentEmpty} / {@code noValueIndeterminate}) → {@code pointer|noValue}. */
    private static List<String> appliedProjection(Json.Obj observation) {
        return entries(observation, ChannelId.APPLIED_STATE.wire()).stream()
                .map(e -> (Json.Obj) e)
                .map(e -> {
                    String pointer = VerifiedPacket.str(e, "pointer");
                    String state = VerifiedPacket.str(e, "state");
                    if (!"presentValue".equals(state)) {
                        return pointer + "|noValue";
                    }
                    return pointer + "|hasValue|" + valueText((Json.Obj) e.members().get("value"));
                })
                .toList();
    }

    private static String renderedOf(Json.Obj entry, String valueMember) {
        Json.Obj value = (Json.Obj) entry.members().get(valueMember);
        return valueText(value);
    }

    /** The comparable TEXT of an observed value: the rendered form where available, else the typed value's
     *  string form (the kernel's applied-state read exposes typed only) — the projection's declared rule. */
    private static String valueText(Json.Obj observedValue) {
        Json.Obj rendered = (Json.Obj) observedValue.members().get("rendered");
        if (rendered.members().get("value") instanceof Json.Str s) {
            return s.value();
        }
        Json.Obj typed = (Json.Obj) observedValue.members().get("typed");
        Json typedValue = typed.members().get("value");
        if (typedValue instanceof Json.Str s) {
            return s.value();
        }
        if (typedValue instanceof Json.Bool b) {
            return String.valueOf(b.value());
        }
        return "<none>";
    }
}
