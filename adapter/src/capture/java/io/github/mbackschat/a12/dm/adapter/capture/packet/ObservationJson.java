package io.github.mbackschat.a12.dm.adapter.capture.packet;

import io.github.mbackschat.a12.dm.adapter.capture.dto.AppliedState;
import io.github.mbackschat.a12.dm.adapter.capture.dto.Availability;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ChannelId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ComputeObservation;
import io.github.mbackschat.a12.dm.adapter.capture.dto.EvidenceValue;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ObservedValue;
import io.github.mbackschat.a12.dm.adapter.capture.json.Json;
import io.github.mbackschat.a12.dm.adapter.capture.schema.SchemaIds;

/**
 * The {@code compute-observation-v1} artifact serializer — the neutral {@code ComputeObservation} to its
 * closed, canonical JSON form. Member order is the schema's declared order; entries stay in OBSERVED
 * enumeration order; every availability travels explicitly.
 */
public final class ObservationJson {

    private ObservationJson() {
    }

    /** Serialize {@code observation}, binding the exact model digests the runner consumed. */
    public static Json toJson(ComputeObservation observation, String modelRef, String suppliedSha256,
            String adaptedSha256) {
        Json.Obj root = Json.Obj.of()
                .put("schema", SchemaIds.COMPUTE_OBSERVATION)
                .put("caseId", observation.caseId())
                .put("runner", observation.runner().wire())
                .put("status", observation.status().wire())
                .put("statusDetail", observation.statusDetail() == null
                        ? Json.Null.NULL : new Json.Str(observation.statusDetail()))
                .put("consumedModel", Json.Obj.of()
                        .put("ref", modelRef)
                        .put("suppliedSha256", suppliedSha256)
                        .put("adaptedSha256", adaptedSha256))
                // The runner-boundary document binding (only a completed run testifies to what it consumed).
                .put("consumedDocument", observation.consumedDocument() == null
                        ? Json.Null.NULL : Json.Obj.of()
                                .put("placements", observation.consumedDocument().placements())
                                .put("canonicalSha256", observation.consumedDocument().canonicalSha256()));
        Json.Obj channels = Json.Obj.of();
        channels.put(ChannelId.WITHOUT_ERRORS.wire(), valueChannel(observation.withoutErrors()));
        channels.put(ChannelId.CHANGED_SUBSET.wire(), valueChannel(observation.changedSubset()));
        channels.put(ChannelId.WITH_ERRORS.wire(), errorChannel(observation.withErrors()));
        channels.put(ChannelId.CLEARED.wire(), clearedChannel(observation.cleared()));
        channels.put(ChannelId.FORMAL_ERRORS_IN_OPERANDS.wire(),
                diagnosticChannel(observation.formalErrorsInOperands()));
        channels.put(ChannelId.APPLIED_STATE.wire(), appliedChannel(observation.appliedState()));
        root.put("channels", channels);
        return root;
    }

    private static Json.Obj channelHeader(ComputeObservation.Channel<?> channel) {
        return Json.Obj.of()
                .put("availability", channel.availability().wire())
                .put("granularity", channel.granularity())
                .put("order", channel.availability() == Availability.AVAILABLE ? "observed" : "notApplicable");
    }

    private static Json valueChannel(ComputeObservation.Channel<ComputeObservation.ValueEntry> channel) {
        Json.Obj obj = channelHeader(channel);
        Json.Arr entries = Json.Arr.of();
        for (ComputeObservation.ValueEntry e : channel.entries()) {
            entries.add(Json.Obj.of()
                    .put("target", e.target())
                    .put("declaredComputation", nullable(e.declaredComputation()))
                    .put("value", observedValue(e.value())));
        }
        return obj.put("entries", entries);
    }

    private static Json errorChannel(ComputeObservation.Channel<ComputeObservation.ErrorEntry> channel) {
        Json.Obj obj = channelHeader(channel);
        Json.Arr entries = Json.Arr.of();
        for (ComputeObservation.ErrorEntry e : channel.entries()) {
            entries.add(Json.Obj.of()
                    .put("target", e.target())
                    .put("declaredComputation", nullable(e.declaredComputation()))
                    .put("attempted", observedValue(e.attempted()))
                    .put("cause", cause(e.cause())));
        }
        return obj.put("entries", entries);
    }

    private static Json clearedChannel(ComputeObservation.Channel<ComputeObservation.ClearedEntry> channel) {
        Json.Obj obj = channelHeader(channel);
        Json.Arr entries = Json.Arr.of();
        for (ComputeObservation.ClearedEntry e : channel.entries()) {
            entries.add(Json.Obj.of()
                    .put("target", e.target())
                    .put("declaredComputation", nullable(e.declaredComputation())));
        }
        return obj.put("entries", entries);
    }

    private static Json diagnosticChannel(ComputeObservation.Channel<ComputeObservation.DiagnosticEntry> channel) {
        Json.Obj obj = channelHeader(channel);
        Json.Arr entries = Json.Arr.of();
        for (ComputeObservation.DiagnosticEntry e : channel.entries()) {
            entries.add(Json.Obj.of()
                    .put("code", e.code())
                    .put("messageType", e.messageType())
                    .put("pointer", e.pointer()));
        }
        return obj.put("entries", entries);
    }

    private static Json appliedChannel(ComputeObservation.Channel<ComputeObservation.AppliedEntry> channel) {
        Json.Obj obj = channelHeader(channel);
        Json.Arr entries = Json.Arr.of();
        for (ComputeObservation.AppliedEntry e : channel.entries()) {
            Json.Obj entry = Json.Obj.of()
                    .put("pointer", e.pointer())
                    .put("state", e.state().wire());
            if (e.state() instanceof AppliedState.PresentValue present) {
                entry.put("value", observedValue(present.value()));
            }
            entries.add(entry);
        }
        return obj.put("entries", entries);
    }

    private static Json observedValue(ObservedValue value) {
        Json.Obj typed = Json.Obj.of().put("availability", value.typedAvailability().wire());
        if (value.typed() != null) {
            typed.put("kind", value.typed().kind());
            switch (value.typed()) {
                case EvidenceValue.StringValue s -> typed.put("value", s.value());
                case EvidenceValue.DecimalValue d -> typed.put("value", d.plainValue());
                case EvidenceValue.BooleanValue b -> typed.put("value", b.value());
                case EvidenceValue.InstantValue i -> typed.put("value", i.iso());
                case EvidenceValue.InstantRangeValue r -> {
                    typed.put("start", r.startIso());
                    typed.put("end", r.endIso());
                }
            }
        }
        Json.Obj rendered = Json.Obj.of().put("availability", value.renderedAvailability().wire());
        if (value.rendered() != null) {
            rendered.put("value", value.rendered());
        }
        return Json.Obj.of().put("typed", typed).put("rendered", rendered);
    }

    private static Json cause(ComputeObservation.CauseObservation cause) {
        Json.Obj obj = Json.Obj.of().put("availability", cause.availability().wire());
        if (cause.availability() == Availability.AVAILABLE) {
            obj.put("code", cause.code())
                    .put("messageType", cause.messageType())
                    .put("errorPointer", cause.errorPointer());
        }
        return obj;
    }

    private static Json nullable(String value) {
        return value == null ? Json.Null.NULL : new Json.Str(value);
    }
}
