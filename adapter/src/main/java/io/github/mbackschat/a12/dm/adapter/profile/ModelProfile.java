package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The current domain-blind model-performance profile.
 *
 * <p>The shape intentionally contains no identifier, literal, message, pattern, or source expression.
 * Every map is canonicalized by key so equal models produce byte-stable JSON in separate JVMs.
 */
record ModelProfile(
        StructureProfile structure,
        ProgramProfile rules,
        ComputationProfile computations,
        WiringProfile wiring) {

    ModelProfile {
        Objects.requireNonNull(structure, "structure");
        Objects.requireNonNull(rules, "rules");
        Objects.requireNonNull(computations, "computations");
        Objects.requireNonNull(wiring, "wiring");
    }

    record StructureProfile(
            LongHistogram elements,
            TopologyProfile topology,
            FieldProfile fields,
            RepeatabilityProfile repeatability) {
        StructureProfile {
            Objects.requireNonNull(elements, "elements");
            Objects.requireNonNull(topology, "topology");
            Objects.requireNonNull(fields, "fields");
            Objects.requireNonNull(repeatability, "repeatability");
        }
    }

    record TopologyProfile(int maxGroupDepth, LongDistribution fieldsPerGroup) {
        TopologyProfile {
            if (maxGroupDepth < 0) {
                throw new IllegalArgumentException(
                        "maximum group depth must be non-negative: " + maxGroupDepth);
            }
            Objects.requireNonNull(fieldsPerGroup, "fieldsPerGroup");
        }
    }

    record FieldProfile(
            LongHistogram kinds,
            LongHistogram constrainedFields,
            LongDistribution numberScale,
            LongDistribution enumCardinality) {
        FieldProfile {
            Objects.requireNonNull(kinds, "kinds");
            Objects.requireNonNull(constrainedFields, "constrainedFields");
            Objects.requireNonNull(numberScale, "numberScale");
            Objects.requireNonNull(enumCardinality, "enumCardinality");
        }
    }

    record RepeatabilityProfile(
            LongHistogram outcomes,
            RepeatableTopology topology,
            RepeatableCapacities capacities) {
        RepeatabilityProfile {
            Objects.requireNonNull(outcomes, "outcomes");
            Objects.requireNonNull(topology, "topology");
            Objects.requireNonNull(capacities, "capacities");
        }
    }

    record RepeatableTopology(
            int maxDepth,
            LongHistogram exactDepth,
            LongDistribution siblingFan) {
        RepeatableTopology {
            if (maxDepth < 0) {
                throw new IllegalArgumentException(
                        "maximum repeatable depth must be non-negative: " + maxDepth);
            }
            Objects.requireNonNull(exactDepth, "exactDepth");
            Objects.requireNonNull(siblingFan, "siblingFan");
        }
    }

    record RepeatableCapacities(
            CapacityProfile maxRepetitions,
            CapacityProfile groupChains,
            CapacityProfile fieldChains) {
        RepeatableCapacities {
            Objects.requireNonNull(maxRepetitions, "maxRepetitions");
            Objects.requireNonNull(groupChains, "groupChains");
            Objects.requireNonNull(fieldChains, "fieldChains");
        }
    }

    record CapacityProfile(
            LongDistribution bounded,
            LongHistogram unboundedCauses) {
        CapacityProfile {
            Objects.requireNonNull(bounded, "bounded");
            Objects.requireNonNull(unboundedCauses, "unboundedCauses");
        }

        long count() {
            return Math.addExact(bounded.count(), unboundedCauses.total());
        }
    }

    record ProgramProfile(
            ConversionProfile conversion,
            SyntaxProfile syntax,
            RuntimeProfile runtime) {
        ProgramProfile {
            Objects.requireNonNull(conversion, "conversion");
            Objects.requireNonNull(syntax, "syntax");
            Objects.requireNonNull(runtime, "runtime");
        }
    }

    record ConversionProfile(LongHistogram outcomes, LongHistogram opaqueReasons) {
        ConversionProfile {
            Objects.requireNonNull(outcomes, "outcomes");
            Objects.requireNonNull(opaqueReasons, "opaqueReasons");
            long opaque = outcomes.counts().getOrDefault("OPAQUE", 0L);
            if (opaqueReasons.total() != opaque) {
                throw new IllegalArgumentException(
                        "opaque-reason total " + opaqueReasons.total()
                                + " does not equal OPAQUE outcome count " + opaque);
            }
        }

        long total() {
            return outcomes.total();
        }
    }

    record SyntaxProfile(AstProfile ast, DispatchProfile dispatch) {
        SyntaxProfile {
            Objects.requireNonNull(ast, "ast");
            Objects.requireNonNull(dispatch, "dispatch");
        }
    }

    record AstProfile(
            LongDistribution nodeCount,
            LongDistribution depth,
            LongDistribution booleanArity,
            LongDistribution variadicArity,
            LongDistribution distinctFanIn) {
        AstProfile {
            Objects.requireNonNull(nodeCount, "nodeCount");
            Objects.requireNonNull(depth, "depth");
            Objects.requireNonNull(booleanArity, "booleanArity");
            Objects.requireNonNull(variadicArity, "variadicArity");
            Objects.requireNonNull(distinctFanIn, "distinctFanIn");
            long count = nodeCount.count();
            if (depth.count() != count
                    || booleanArity.count() != count
                    || variadicArity.count() != count
                    || distinctFanIn.count() != count) {
                throw new IllegalArgumentException(
                        "all AST distributions must describe the same converted-program count");
            }
        }
    }

    record DispatchProfile(
            LongHistogram catalogOperators,
            LongHistogram intrinsicOperators,
            LongHistogram constructs,
            LongHistogram constructCombinations,
            SortedMap<String, LongHistogram> constructsByScope) {
        DispatchProfile {
            Objects.requireNonNull(catalogOperators, "catalogOperators");
            Objects.requireNonNull(intrinsicOperators, "intrinsicOperators");
            Objects.requireNonNull(constructs, "constructs");
            Objects.requireNonNull(constructCombinations, "constructCombinations");
            constructsByScope = immutableNested(constructsByScope);
        }
    }

    record RuntimeProfile(
            ScopeProfile scope,
            EvaluationProfile evaluation,
            ResolutionProfile resolution) {
        RuntimeProfile {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(evaluation, "evaluation");
            Objects.requireNonNull(resolution, "resolution");
        }
    }

    record ScopeProfile(LongHistogram classes, CapacityProfile capacity) {
        ScopeProfile {
            Objects.requireNonNull(classes, "classes");
            Objects.requireNonNull(capacity, "capacity");
            if (classes.total() != capacity.count()) {
                throw new IllegalArgumentException(
                        "scope-class total " + classes.total()
                                + " does not equal scope-capacity count " + capacity.count());
            }
        }
    }

    record EvaluationProfile(
            ScanProfile scans,
            WorkProfile combinedWork,
            SortedMap<String, MechanismProfile> mechanisms,
            OuterCorrelationProfile outerCorrelation) {
        EvaluationProfile {
            Objects.requireNonNull(scans, "scans");
            Objects.requireNonNull(combinedWork, "combinedWork");
            mechanisms = immutableNested(mechanisms);
            Objects.requireNonNull(outerCorrelation, "outerCorrelation");
        }
    }

    record ScanProfile(
            LongHistogram kinds,
            LongDistribution starDepth,
            CapacityProfile capacity,
            LongDistribution filterComplexity) {
        ScanProfile {
            Objects.requireNonNull(kinds, "kinds");
            Objects.requireNonNull(starDepth, "starDepth");
            Objects.requireNonNull(capacity, "capacity");
            Objects.requireNonNull(filterComplexity, "filterComplexity");
            if (kinds.total() != starDepth.count() || kinds.total() != capacity.count()) {
                throw new IllegalArgumentException(
                        "scan kind, star-depth, and capacity counts must agree");
            }
            if (filterComplexity.count() > kinds.total()) {
                throw new IllegalArgumentException(
                        "filtered-scan count cannot exceed total scan count");
            }
        }
    }

    record WorkProfile(CapacityProfile capacity, LongHistogram scopeByScan) {
        WorkProfile {
            Objects.requireNonNull(capacity, "capacity");
            Objects.requireNonNull(scopeByScan, "scopeByScan");
        }
    }

    record MechanismProfile(LongDistribution shape, CapacityProfile capacity) {
        MechanismProfile {
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(capacity, "capacity");
            if (shape.count() != capacity.count()) {
                throw new IllegalArgumentException(
                        "mechanism shape and capacity counts must agree");
            }
        }
    }

    record OuterCorrelationProfile(LongHistogram forms, CapacityProfile capacity) {
        OuterCorrelationProfile {
            Objects.requireNonNull(forms, "forms");
            Objects.requireNonNull(capacity, "capacity");
            if (forms.total() != capacity.count()) {
                throw new IllegalArgumentException(
                        "outer-correlation form and capacity counts must agree");
            }
        }
    }

    record ResolutionProfile(LongDistribution walkUpDistance) {
        ResolutionProfile {
            Objects.requireNonNull(walkUpDistance, "walkUpDistance");
        }
    }

    record ComputationProfile(
            ProgramProfile programs,
            ComputationShape shape,
            DependencyProfile dependencies) {
        ComputationProfile {
            Objects.requireNonNull(programs, "programs");
            Objects.requireNonNull(shape, "shape");
            Objects.requireNonNull(dependencies, "dependencies");
        }
    }

    record ComputationShape(
            LongHistogram computedFieldKinds,
            TableProfile tables,
            LongHistogram archetypes,
            LongHistogram aggregateScaleMatch) {
        ComputationShape {
            Objects.requireNonNull(computedFieldKinds, "computedFieldKinds");
            Objects.requireNonNull(tables, "tables");
            Objects.requireNonNull(archetypes, "archetypes");
            Objects.requireNonNull(aggregateScaleMatch, "aggregateScaleMatch");
        }
    }

    record TableProfile(
            LongDistribution alternatives,
            LongDistribution preconditions,
            LongHistogram toleranceBands) {
        TableProfile {
            Objects.requireNonNull(alternatives, "alternatives");
            Objects.requireNonNull(preconditions, "preconditions");
            Objects.requireNonNull(toleranceBands, "toleranceBands");
            if (alternatives.count() != preconditions.count()) {
                throw new IllegalArgumentException(
                        "alternative and precondition distributions must have equal counts");
            }
        }
    }

    record DependencyProfile(int maxCascadeDepth, long feedingComputations) {
        DependencyProfile {
            if (maxCascadeDepth < 0 || feedingComputations < 0) {
                throw new IllegalArgumentException(
                        "computation dependency facts must be non-negative");
            }
        }
    }

    record WiringProfile(LongHistogram referencedFields, LongDistribution fieldFanOut) {
        WiringProfile {
            Objects.requireNonNull(referencedFields, "referencedFields");
            Objects.requireNonNull(fieldFanOut, "fieldFanOut");
            long any = referencedFields.counts().getOrDefault("ANY", 0L);
            if (fieldFanOut.count() != any) {
                throw new IllegalArgumentException(
                        "field-fan-out count " + fieldFanOut.count()
                                + " does not equal ANY referenced-field count " + any);
            }
        }
    }

    private static <V> SortedMap<String, V> immutableNested(Map<String, V> source) {
        Objects.requireNonNull(source, "source");
        var copy = new TreeMap<String, V>();
        source.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("profile map key must not be blank");
            }
            if (copy.putIfAbsent(key, Objects.requireNonNull(value, "profile map value")) != null) {
                throw new IllegalArgumentException("duplicate profile map key: " + key);
            }
        });
        return Collections.unmodifiableSortedMap(copy);
    }
}
