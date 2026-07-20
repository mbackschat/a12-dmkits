package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Objects;

import io.github.mbackschat.a12.dm.adapter.ModelRead;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.rulekit.read.ReadComputation;
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult;

/** Assembles the current domain-blind performance profile from one strict model snapshot. */
final class ModelProfileExtractor {

    private ModelProfileExtractor() {}

    static ModelProfile extract(String modelJson) {
        return assemble(ProfileSnapshot.load(modelJson));
    }

    static ModelProfile extract(String modelJson, ModelResolver resolver) {
        return assemble(ProfileSnapshot.load(modelJson, resolver));
    }

    private static ModelProfile assemble(ProfileSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ModelRead read = snapshot.read();
        ReferenceGraph references = ReferenceGraph.of(snapshot);
        ModelProfile.ProgramProfile rules = ruleProfile(snapshot);
        ModelProfile.ProgramProfile computationPrograms = computationProfile(snapshot);
        ModelProfile.ComputationProfile computations = new ModelProfile.ComputationProfile(
                computationPrograms,
                ComputationShapeProfiler.profile(snapshot),
                references.dependencyProfile());
        return new ModelProfile(
                withProgramElements(snapshot.topology().structureProfile(), read),
                rules,
                computations,
                references.wiringProfile());
    }

    private static ModelProfile.ProgramProfile ruleProfile(ProfileSnapshot snapshot) {
        ProgramProfiler.Builder programs = ProgramProfiler.builder(snapshot.topology());
        snapshot.read().rules().forEach((fullName, outcome) -> {
            String scope = ProfileTopology.parentOf(fullName);
            switch (outcome) {
                case ReadResult.Converted converted -> programs.converted(
                        scope,
                        ConditionComplexity.of(converted.condition(), scope));
                case ReadResult.Opaque opaque -> programs.opaque(
                        scope,
                        OpaqueReasonCategory.of(opaque.reason()));
            }
        });
        return programs.build();
    }

    private static ModelProfile.ProgramProfile computationProfile(ProfileSnapshot snapshot) {
        ProgramProfiler.Builder programs = ProgramProfiler.builder(snapshot.topology());
        snapshot.read().computations().forEach((fullName, outcome) -> {
            String target = snapshot.computationTargets().get(fullName);
            if (target == null) {
                throw new IllegalStateException(
                        "shared computation read has no target for " + fullName);
            }
            String scope = ProfileTopology.parentOf(target);
            switch (outcome) {
                case ReadComputation.Converted converted ->
                        programs.add(convertedComputation(scope, converted));
                case ReadComputation.Opaque opaque -> programs.opaque(
                        scope,
                        OpaqueReasonCategory.of(opaque.reason()));
            }
        });
        return programs.build();
    }

    private static ProgramProfiler.ConvertedProgram convertedComputation(
            String scope,
            ReadComputation.Converted converted) {
        var parts = ProgramProfiler.ConvertedProgram.builder(scope);
        var computation = converted.computation();
        computation.commonPrecondition()
                .map(condition -> ConditionComplexity.of(condition, scope))
                .ifPresent(parts::addPart);
        for (var alternative : computation.alternatives()) {
            alternative.precondition()
                    .map(condition -> ConditionComplexity.of(condition, scope))
                    .ifPresent(parts::addPart);
            parts.addPart(ConditionComplexity.of(alternative.operation()));
        }
        return parts.build();
    }

    private static ModelProfile.StructureProfile withProgramElements(
            ModelProfile.StructureProfile structure,
            ModelRead read) {
        LongHistogram elements = LongHistogram.builder()
                .add("FIELD", structure.elements().counts().getOrDefault("FIELD", 0L))
                .add("GROUP", structure.elements().counts().getOrDefault("GROUP", 0L))
                .add("RULE", read.rules().size())
                .add("COMPUTATION", read.computations().size())
                .build();
        return new ModelProfile.StructureProfile(
                elements,
                structure.topology(),
                structure.fields(),
                structure.repeatability());
    }
}
