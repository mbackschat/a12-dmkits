package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.distribution;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.exactObject;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.histogram;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.integer;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.integer64;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.required;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.validated;

import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** Strict parser for the computation-shape, dependency, and wiring profile branches. */
final class ProfileJsonComputationReader {

    private ProfileJsonComputationReader() {}

    static ModelProfile.ComputationProfile computations(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("programs", "shape", "dependencies"));
        var programs =
                ProfileJsonProgramReader.read(required(node, "programs", path), path + ".programs");
        var shape = shape(required(node, "shape", path), path + ".shape");
        var dependencies =
                dependencies(required(node, "dependencies", path), path + ".dependencies");
        return validated(
                path,
                () -> new ModelProfile.ComputationProfile(
                        programs, shape, dependencies));
    }

    static ModelProfile.WiringProfile wiring(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("referencedFields", "fieldFanOut"));
        var referencedFields =
                histogram(required(node, "referencedFields", path), path + ".referencedFields");
        var fieldFanOut =
                distribution(required(node, "fieldFanOut", path), path + ".fieldFanOut");
        return validated(
                path, () -> new ModelProfile.WiringProfile(referencedFields, fieldFanOut));
    }

    private static ModelProfile.ComputationShape shape(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of(
                        "computedFieldKinds",
                        "tables",
                        "archetypes",
                        "aggregateScaleMatch"));
        return new ShapeBuilder()
                .computedFieldKinds(histogram(
                        required(node, "computedFieldKinds", path),
                        path + ".computedFieldKinds"))
                .tables(tables(required(node, "tables", path), path + ".tables"))
                .archetypes(histogram(
                        required(node, "archetypes", path), path + ".archetypes"))
                .aggregateScaleMatch(histogram(
                        required(node, "aggregateScaleMatch", path),
                        path + ".aggregateScaleMatch"))
                .build(path);
    }

    private static ModelProfile.TableProfile tables(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of("alternatives", "preconditions", "toleranceBands"));
        return new TableBuilder()
                .alternatives(distribution(
                        required(node, "alternatives", path), path + ".alternatives"))
                .preconditions(distribution(
                        required(node, "preconditions", path), path + ".preconditions"))
                .toleranceBands(histogram(
                        required(node, "toleranceBands", path), path + ".toleranceBands"))
                .build(path);
    }

    private static ModelProfile.DependencyProfile dependencies(
            JsonNode source, String path) {
        var node =
                exactObject(source, path, Set.of("maxCascadeDepth", "feedingComputations"));
        int maxCascadeDepth = integer(node, "maxCascadeDepth", path);
        long feedingComputations = integer64(node, "feedingComputations", path);
        return validated(
                path,
                () -> new ModelProfile.DependencyProfile(
                        maxCascadeDepth, feedingComputations));
    }

    private static final class ShapeBuilder {
        private LongHistogram computedFieldKinds;
        private ModelProfile.TableProfile tables;
        private LongHistogram archetypes;
        private LongHistogram aggregateScaleMatch;

        ShapeBuilder computedFieldKinds(LongHistogram value) {
            computedFieldKinds = value;
            return this;
        }

        ShapeBuilder tables(ModelProfile.TableProfile value) {
            tables = value;
            return this;
        }

        ShapeBuilder archetypes(LongHistogram value) {
            archetypes = value;
            return this;
        }

        ShapeBuilder aggregateScaleMatch(LongHistogram value) {
            aggregateScaleMatch = value;
            return this;
        }

        ModelProfile.ComputationShape build(String path) {
            return validated(
                    path,
                    () -> new ModelProfile.ComputationShape(
                            computedFieldKinds, tables, archetypes, aggregateScaleMatch));
        }
    }

    private static final class TableBuilder {
        private LongDistribution alternatives;
        private LongDistribution preconditions;
        private LongHistogram toleranceBands;

        TableBuilder alternatives(LongDistribution value) {
            alternatives = value;
            return this;
        }

        TableBuilder preconditions(LongDistribution value) {
            preconditions = value;
            return this;
        }

        TableBuilder toleranceBands(LongHistogram value) {
            toleranceBands = value;
            return this;
        }

        ModelProfile.TableProfile build(String path) {
            return validated(
                    path,
                    () -> new ModelProfile.TableProfile(
                            alternatives, preconditions, toleranceBands));
        }
    }
}
