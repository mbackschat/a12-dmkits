package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.distribution;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.exactObject;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.histogram;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.integer;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.required;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.validated;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Strict parser for the structure branch of a current model profile. */
final class ProfileJsonStructureReader {

    private ProfileJsonStructureReader() {}

    static ModelProfile.StructureProfile read(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of("elements", "topology", "fields", "repeatability"));
        var elements = histogram(required(node, "elements", path), path + ".elements");
        var topology = topology(required(node, "topology", path), path + ".topology");
        var fields = fields(required(node, "fields", path), path + ".fields");
        var repeatability =
                repeatability(required(node, "repeatability", path), path + ".repeatability");
        return validated(
                path,
                () -> new ModelProfile.StructureProfile(
                        elements, topology, fields, repeatability));
    }

    private static ModelProfile.TopologyProfile topology(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("maxGroupDepth", "fieldsPerGroup"));
        int maxGroupDepth = integer(node, "maxGroupDepth", path);
        var fieldsPerGroup =
                distribution(required(node, "fieldsPerGroup", path), path + ".fieldsPerGroup");
        return validated(
                path, () -> new ModelProfile.TopologyProfile(maxGroupDepth, fieldsPerGroup));
    }

    private static ModelProfile.FieldProfile fields(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of("kinds", "constrainedFields", "numberScale", "enumCardinality"));
        return new FieldBuilder()
                .kinds(histogram(required(node, "kinds", path), path + ".kinds"))
                .constrainedFields(histogram(
                        required(node, "constrainedFields", path),
                        path + ".constrainedFields"))
                .numberScale(distribution(
                        required(node, "numberScale", path), path + ".numberScale"))
                .enumCardinality(distribution(
                        required(node, "enumCardinality", path),
                        path + ".enumCardinality"))
                .build(path);
    }

    private static ModelProfile.RepeatabilityProfile repeatability(
            JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("outcomes", "topology", "capacities"));
        var outcomes = histogram(required(node, "outcomes", path), path + ".outcomes");
        var topology =
                repeatableTopology(required(node, "topology", path), path + ".topology");
        var capacities =
                repeatableCapacities(required(node, "capacities", path), path + ".capacities");
        return validated(
                path,
                () -> new ModelProfile.RepeatabilityProfile(
                        outcomes, topology, capacities));
    }

    private static ModelProfile.RepeatableTopology repeatableTopology(
            JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("maxDepth", "exactDepth", "siblingFan"));
        int maxDepth = integer(node, "maxDepth", path);
        var exactDepth = histogram(required(node, "exactDepth", path), path + ".exactDepth");
        var siblingFan =
                distribution(required(node, "siblingFan", path), path + ".siblingFan");
        return validated(
                path,
                () -> new ModelProfile.RepeatableTopology(
                        maxDepth, exactDepth, siblingFan));
    }

    private static ModelProfile.RepeatableCapacities repeatableCapacities(
            JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of("maxRepetitions", "groupChains", "fieldChains"));
        return new RepeatableCapacitiesBuilder()
                .maxRepetitions(capacity(
                        required(node, "maxRepetitions", path),
                        path + ".maxRepetitions"))
                .groupChains(capacity(
                        required(node, "groupChains", path), path + ".groupChains"))
                .fieldChains(capacity(
                        required(node, "fieldChains", path), path + ".fieldChains"))
                .build(path);
    }

    static ModelProfile.CapacityProfile capacity(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("bounded", "unboundedCauses"));
        var bounded = distribution(required(node, "bounded", path), path + ".bounded");
        var unboundedCauses =
                histogram(required(node, "unboundedCauses", path), path + ".unboundedCauses");
        return validated(
                path, () -> new ModelProfile.CapacityProfile(bounded, unboundedCauses));
    }

    private static final class FieldBuilder {
        private LongHistogram kinds;
        private LongHistogram constrainedFields;
        private LongDistribution numberScale;
        private LongDistribution enumCardinality;

        FieldBuilder kinds(LongHistogram value) {
            kinds = value;
            return this;
        }

        FieldBuilder constrainedFields(LongHistogram value) {
            constrainedFields = value;
            return this;
        }

        FieldBuilder numberScale(LongDistribution value) {
            numberScale = value;
            return this;
        }

        FieldBuilder enumCardinality(LongDistribution value) {
            enumCardinality = value;
            return this;
        }

        ModelProfile.FieldProfile build(String path) {
            return validated(
                    path,
                    () -> new ModelProfile.FieldProfile(
                            kinds, constrainedFields, numberScale, enumCardinality));
        }
    }

    private static final class RepeatableCapacitiesBuilder {
        private ModelProfile.CapacityProfile maxRepetitions;
        private ModelProfile.CapacityProfile groupChains;
        private ModelProfile.CapacityProfile fieldChains;

        RepeatableCapacitiesBuilder maxRepetitions(ModelProfile.CapacityProfile value) {
            maxRepetitions = value;
            return this;
        }

        RepeatableCapacitiesBuilder groupChains(ModelProfile.CapacityProfile value) {
            groupChains = value;
            return this;
        }

        RepeatableCapacitiesBuilder fieldChains(ModelProfile.CapacityProfile value) {
            fieldChains = value;
            return this;
        }

        ModelProfile.RepeatableCapacities build(String path) {
            return validated(
                    path,
                    () -> new ModelProfile.RepeatableCapacities(
                            maxRepetitions, groupChains, fieldChains));
        }
    }
}
