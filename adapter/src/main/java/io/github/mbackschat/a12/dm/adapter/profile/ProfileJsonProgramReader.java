package io.github.mbackschat.a12.dm.adapter.profile;

import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonStructureReader.capacity;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.distribution;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.exactObject;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.histogram;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.histogramMap;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.object;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.required;
import static io.github.mbackschat.a12.dm.adapter.profile.ProfileJsonValues.validated;

import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;

/** Strict parser for a rules-or-computations program branch of a current model profile. */
final class ProfileJsonProgramReader {

    private ProfileJsonProgramReader() {}

    static ModelProfile.ProgramProfile read(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("conversion", "syntax", "runtime"));
        var conversion =
                conversion(required(node, "conversion", path), path + ".conversion");
        var syntax = syntax(required(node, "syntax", path), path + ".syntax");
        var runtime = runtime(required(node, "runtime", path), path + ".runtime");
        return validated(
                path, () -> new ModelProfile.ProgramProfile(conversion, syntax, runtime));
    }

    private static ModelProfile.ConversionProfile conversion(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("outcomes", "opaqueReasons"));
        return new ConversionBuilder()
                .outcomes(histogram(required(node, "outcomes", path), path + ".outcomes"))
                .opaqueReasons(histogram(
                        required(node, "opaqueReasons", path), path + ".opaqueReasons"))
                .build(path);
    }

    private static ModelProfile.SyntaxProfile syntax(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("ast", "dispatch"));
        var ast = ast(required(node, "ast", path), path + ".ast");
        var dispatch = dispatch(required(node, "dispatch", path), path + ".dispatch");
        return validated(path, () -> new ModelProfile.SyntaxProfile(ast, dispatch));
    }

    private static ModelProfile.AstProfile ast(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of(
                        "nodeCount",
                        "depth",
                        "booleanArity",
                        "variadicArity",
                        "distinctFanIn"));
        return new AstBuilder()
                .nodeCount(distribution(
                        required(node, "nodeCount", path), path + ".nodeCount"))
                .depth(distribution(required(node, "depth", path), path + ".depth"))
                .booleanArity(distribution(
                        required(node, "booleanArity", path), path + ".booleanArity"))
                .variadicArity(distribution(
                        required(node, "variadicArity", path), path + ".variadicArity"))
                .distinctFanIn(distribution(
                        required(node, "distinctFanIn", path), path + ".distinctFanIn"))
                .build(path);
    }

    private static ModelProfile.DispatchProfile dispatch(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of(
                        "catalogOperators",
                        "intrinsicOperators",
                        "constructs",
                        "constructCombinations",
                        "constructsByScope"));
        return new DispatchBuilder()
                .catalogOperators(histogram(
                        required(node, "catalogOperators", path),
                        path + ".catalogOperators"))
                .intrinsicOperators(histogram(
                        required(node, "intrinsicOperators", path),
                        path + ".intrinsicOperators"))
                .constructs(histogram(
                        required(node, "constructs", path), path + ".constructs"))
                .constructCombinations(histogram(
                        required(node, "constructCombinations", path),
                        path + ".constructCombinations"))
                .constructsByScope(histogramMap(
                        required(node, "constructsByScope", path),
                        path + ".constructsByScope"))
                .build(path);
    }

    private static ModelProfile.RuntimeProfile runtime(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("scope", "evaluation", "resolution"));
        var scope = scope(required(node, "scope", path), path + ".scope");
        var evaluation =
                evaluation(required(node, "evaluation", path), path + ".evaluation");
        var resolution =
                resolution(required(node, "resolution", path), path + ".resolution");
        return validated(
                path, () -> new ModelProfile.RuntimeProfile(scope, evaluation, resolution));
    }

    private static ModelProfile.ScopeProfile scope(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("classes", "capacity"));
        var classes = histogram(required(node, "classes", path), path + ".classes");
        var capacity = capacity(required(node, "capacity", path), path + ".capacity");
        return validated(path, () -> new ModelProfile.ScopeProfile(classes, capacity));
    }

    private static ModelProfile.EvaluationProfile evaluation(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of("scans", "combinedWork", "mechanisms", "outerCorrelation"));
        var scans = scans(required(node, "scans", path), path + ".scans");
        var combinedWork =
                work(required(node, "combinedWork", path), path + ".combinedWork");
        var mechanisms =
                mechanismMap(required(node, "mechanisms", path), path + ".mechanisms");
        var outerCorrelation = outerCorrelation(
                required(node, "outerCorrelation", path), path + ".outerCorrelation");
        return validated(
                path,
                () -> new ModelProfile.EvaluationProfile(
                        scans, combinedWork, mechanisms, outerCorrelation));
    }

    private static ModelProfile.ScanProfile scans(JsonNode source, String path) {
        var node = exactObject(
                source,
                path,
                Set.of("kinds", "starDepth", "capacity", "filterComplexity"));
        var kinds = histogram(required(node, "kinds", path), path + ".kinds");
        var starDepth =
                distribution(required(node, "starDepth", path), path + ".starDepth");
        var capacity = capacity(required(node, "capacity", path), path + ".capacity");
        var filterComplexity = distribution(
                required(node, "filterComplexity", path), path + ".filterComplexity");
        return validated(
                path,
                () -> new ModelProfile.ScanProfile(
                        kinds, starDepth, capacity, filterComplexity));
    }

    private static ModelProfile.WorkProfile work(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("capacity", "scopeByScan"));
        var capacity = capacity(required(node, "capacity", path), path + ".capacity");
        var scopeByScan =
                histogram(required(node, "scopeByScan", path), path + ".scopeByScan");
        return validated(path, () -> new ModelProfile.WorkProfile(capacity, scopeByScan));
    }

    private static SortedMap<String, ModelProfile.MechanismProfile> mechanismMap(
            JsonNode source, String path) {
        var node = object(source, path);
        var result = new TreeMap<String, ModelProfile.MechanismProfile>();
        node.properties().forEach(entry ->
                result.put(entry.getKey(), mechanism(entry.getValue(), path + "." + entry.getKey())));
        return result;
    }

    private static ModelProfile.MechanismProfile mechanism(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("shape", "capacity"));
        var shape = distribution(required(node, "shape", path), path + ".shape");
        var capacity = capacity(required(node, "capacity", path), path + ".capacity");
        return validated(path, () -> new ModelProfile.MechanismProfile(shape, capacity));
    }

    private static ModelProfile.OuterCorrelationProfile outerCorrelation(
            JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("forms", "capacity"));
        var forms = histogram(required(node, "forms", path), path + ".forms");
        var capacity = capacity(required(node, "capacity", path), path + ".capacity");
        return validated(
                path, () -> new ModelProfile.OuterCorrelationProfile(forms, capacity));
    }

    private static ModelProfile.ResolutionProfile resolution(JsonNode source, String path) {
        var node = exactObject(source, path, Set.of("walkUpDistance"));
        var walkUpDistance =
                distribution(required(node, "walkUpDistance", path), path + ".walkUpDistance");
        return validated(
                path, () -> new ModelProfile.ResolutionProfile(walkUpDistance));
    }

    private static final class ConversionBuilder {
        private LongHistogram outcomes;
        private LongHistogram opaqueReasons;

        ConversionBuilder outcomes(LongHistogram value) {
            outcomes = value;
            return this;
        }

        ConversionBuilder opaqueReasons(LongHistogram value) {
            opaqueReasons = value;
            return this;
        }

        ModelProfile.ConversionProfile build(String path) {
            return validated(
                    path,
                    () -> new ModelProfile.ConversionProfile(outcomes, opaqueReasons));
        }
    }

    private static final class AstBuilder {
        private LongDistribution nodeCount;
        private LongDistribution depth;
        private LongDistribution booleanArity;
        private LongDistribution variadicArity;
        private LongDistribution distinctFanIn;

        AstBuilder nodeCount(LongDistribution value) {
            nodeCount = value;
            return this;
        }

        AstBuilder depth(LongDistribution value) {
            depth = value;
            return this;
        }

        AstBuilder booleanArity(LongDistribution value) {
            booleanArity = value;
            return this;
        }

        AstBuilder variadicArity(LongDistribution value) {
            variadicArity = value;
            return this;
        }

        AstBuilder distinctFanIn(LongDistribution value) {
            distinctFanIn = value;
            return this;
        }

        ModelProfile.AstProfile build(String path) {
            return validated(
                    path,
                    () -> new ModelProfile.AstProfile(
                            nodeCount, depth, booleanArity, variadicArity, distinctFanIn));
        }
    }

    private static final class DispatchBuilder {
        private LongHistogram catalogOperators;
        private LongHistogram intrinsicOperators;
        private LongHistogram constructs;
        private LongHistogram constructCombinations;
        private SortedMap<String, LongHistogram> constructsByScope;

        DispatchBuilder catalogOperators(LongHistogram value) {
            catalogOperators = value;
            return this;
        }

        DispatchBuilder intrinsicOperators(LongHistogram value) {
            intrinsicOperators = value;
            return this;
        }

        DispatchBuilder constructs(LongHistogram value) {
            constructs = value;
            return this;
        }

        DispatchBuilder constructCombinations(LongHistogram value) {
            constructCombinations = value;
            return this;
        }

        DispatchBuilder constructsByScope(SortedMap<String, LongHistogram> value) {
            constructsByScope = value;
            return this;
        }

        ModelProfile.DispatchProfile build(String path) {
            return validated(
                    path,
                    () -> new ModelProfile.DispatchProfile(
                            catalogOperators,
                            intrinsicOperators,
                            constructs,
                            constructCombinations,
                            constructsByScope));
        }
    }
}
