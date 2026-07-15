package io.github.mbackschat.a12.dm.adapter.capture.schema;

import java.util.List;
import java.util.Optional;

import io.github.mbackschat.a12.dm.adapter.capture.dto.ChannelId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.PolicyId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.ProjectionId;
import io.github.mbackschat.a12.dm.adapter.capture.dto.RunnerId;

/**
 * The decoded {@code capture-scenario-set-v1} request (proposal §1) — closed and expectation-free: the
 * request asks questions (models + instances + operations + probes + a predeclared observation
 * requirement); it NEVER carries expected runner results. The schema has no expected-result member at
 * all — {@link ScenarioDecoder} rejects one as an unknown member.
 */
public record ScenarioSet(
        String scenarioSetId,
        String scenarioSetVersion,
        String operationSchema,
        String requiredCapabilitiesSchema,
        String requiredCapabilitiesVersion,
        String worldProfile,
        String worldLocale,
        List<ModelRef> models,
        List<RunnerId> requiredRunners,
        PolicyId qualificationPolicy,
        ObservationRequirement observationRequirement,
        List<ScenarioCase> cases) {

    public ScenarioSet {
        models = List.copyOf(models);
        requiredRunners = List.copyOf(requiredRunners);
        cases = List.copyOf(cases);
    }

    /** A model input: its scenario-root-relative {@code ref} and the declared SHA-256 of its exact bytes. */
    public record ModelRef(String ref, String sha256) {
    }

    /** The predeclared observation requirement: required channels + comparison projections, pinned BEFORE
     *  execution (a comparison can never be weakened after seeing a split). */
    public record ObservationRequirement(
            String id,
            String version,
            List<ChannelId> requiredChannels,
            List<ProjectionId> comparisonProjections) {

        public ObservationRequirement {
            requiredChannels = List.copyOf(requiredChannels);
            comparisonProjections = List.copyOf(comparisonProjections);
        }
    }

    /** One explicit case: instance placements, the operation, an optional pinned clock, and probe pointers
     *  (every computation target is additionally auto-included in the applied-state probe scope). */
    public record ScenarioCase(
            String caseId,
            String modelRef,
            Optional<String> clock,
            List<Placement> placements,
            List<String> probes) {

        public ScenarioCase {
            placements = List.copyOf(placements);
            probes = List.copyOf(probes);
        }
    }

    /** One instance placement — the corpus placement vocabulary (one 1-based repetition index per path
     *  segment, the addressed segment included); re-declared here so the request schema is versioned
     *  independently of any engine's internal types. */
    public record Placement(PlacementKind kind, String path, List<Integer> reps, Optional<String> value) {

        public Placement {
            reps = List.copyOf(reps);
        }
    }

    public enum PlacementKind { GROUP, FIELD, EMPTY }
}
