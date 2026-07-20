package io.github.mbackschat.a12.dm.adapter.profile;

import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Canonical serializer for the current domain-blind model profile. */
final class ProfileJsonWriter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DefaultPrettyPrinter PRETTY = prettyPrinter();

    private ProfileJsonWriter() {}

    static String write(ModelProfile profile) {
        Objects.requireNonNull(profile, "profile");
        return canonicalJson(profile(profile));
    }

    static String canonicalJson(ObjectNode root) {
        Objects.requireNonNull(root, "root");
        try {
            return JSON.writer(PRETTY).writeValueAsString(root) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize profile JSON", e);
        }
    }

    private static ObjectNode profile(ModelProfile profile) {
        var root = JSON.createObjectNode();
        root.set("structure", structure(profile.structure()));
        root.set("rules", program(profile.rules()));
        root.set("computations", computations(profile.computations()));
        root.set("wiring", wiring(profile.wiring()));
        return root;
    }

    private static ObjectNode structure(ModelProfile.StructureProfile profile) {
        var node = JSON.createObjectNode();
        node.set("elements", histogram(profile.elements()));
        node.set("topology", topology(profile.topology()));
        node.set("fields", fields(profile.fields()));
        node.set("repeatability", repeatability(profile.repeatability()));
        return node;
    }

    private static ObjectNode topology(ModelProfile.TopologyProfile profile) {
        var node = JSON.createObjectNode();
        node.put("maxGroupDepth", profile.maxGroupDepth());
        node.set("fieldsPerGroup", distribution(profile.fieldsPerGroup()));
        return node;
    }

    private static ObjectNode fields(ModelProfile.FieldProfile profile) {
        var node = JSON.createObjectNode();
        node.set("kinds", histogram(profile.kinds()));
        node.set("constrainedFields", histogram(profile.constrainedFields()));
        node.set("numberScale", distribution(profile.numberScale()));
        node.set("enumCardinality", distribution(profile.enumCardinality()));
        return node;
    }

    private static ObjectNode repeatability(ModelProfile.RepeatabilityProfile profile) {
        var node = JSON.createObjectNode();
        node.set("outcomes", histogram(profile.outcomes()));
        node.set("topology", repeatableTopology(profile.topology()));
        node.set("capacities", repeatableCapacities(profile.capacities()));
        return node;
    }

    private static ObjectNode repeatableTopology(ModelProfile.RepeatableTopology profile) {
        var node = JSON.createObjectNode();
        node.put("maxDepth", profile.maxDepth());
        node.set("exactDepth", histogram(profile.exactDepth()));
        node.set("siblingFan", distribution(profile.siblingFan()));
        return node;
    }

    private static ObjectNode repeatableCapacities(ModelProfile.RepeatableCapacities profile) {
        var node = JSON.createObjectNode();
        node.set("maxRepetitions", capacity(profile.maxRepetitions()));
        node.set("groupChains", capacity(profile.groupChains()));
        node.set("fieldChains", capacity(profile.fieldChains()));
        return node;
    }

    private static ObjectNode capacity(ModelProfile.CapacityProfile profile) {
        var node = JSON.createObjectNode();
        node.set("bounded", distribution(profile.bounded()));
        node.set("unboundedCauses", histogram(profile.unboundedCauses()));
        return node;
    }

    private static ObjectNode program(ModelProfile.ProgramProfile profile) {
        var node = JSON.createObjectNode();
        node.set("conversion", conversion(profile.conversion()));
        node.set("syntax", syntax(profile.syntax()));
        node.set("runtime", runtime(profile.runtime()));
        return node;
    }

    private static ObjectNode conversion(ModelProfile.ConversionProfile profile) {
        var node = JSON.createObjectNode();
        node.set("outcomes", histogram(profile.outcomes()));
        node.set("opaqueReasons", histogram(profile.opaqueReasons()));
        return node;
    }

    private static ObjectNode syntax(ModelProfile.SyntaxProfile profile) {
        var node = JSON.createObjectNode();
        node.set("ast", ast(profile.ast()));
        node.set("dispatch", dispatch(profile.dispatch()));
        return node;
    }

    private static ObjectNode ast(ModelProfile.AstProfile profile) {
        var node = JSON.createObjectNode();
        node.set("nodeCount", distribution(profile.nodeCount()));
        node.set("depth", distribution(profile.depth()));
        node.set("booleanArity", distribution(profile.booleanArity()));
        node.set("variadicArity", distribution(profile.variadicArity()));
        node.set("distinctFanIn", distribution(profile.distinctFanIn()));
        return node;
    }

    private static ObjectNode dispatch(ModelProfile.DispatchProfile profile) {
        var node = JSON.createObjectNode();
        node.set("catalogOperators", histogram(profile.catalogOperators()));
        node.set("intrinsicOperators", histogram(profile.intrinsicOperators()));
        node.set("constructs", histogram(profile.constructs()));
        node.set("constructCombinations", histogram(profile.constructCombinations()));
        var byScope = node.putObject("constructsByScope");
        profile.constructsByScope()
                .forEach((key, value) -> byScope.set(key, histogram(value)));
        return node;
    }

    private static ObjectNode runtime(ModelProfile.RuntimeProfile profile) {
        var node = JSON.createObjectNode();
        node.set("scope", scope(profile.scope()));
        node.set("evaluation", evaluation(profile.evaluation()));
        node.set("resolution", resolution(profile.resolution()));
        return node;
    }

    private static ObjectNode scope(ModelProfile.ScopeProfile profile) {
        var node = JSON.createObjectNode();
        node.set("classes", histogram(profile.classes()));
        node.set("capacity", capacity(profile.capacity()));
        return node;
    }

    private static ObjectNode evaluation(ModelProfile.EvaluationProfile profile) {
        var node = JSON.createObjectNode();
        node.set("scans", scans(profile.scans()));
        node.set("combinedWork", work(profile.combinedWork()));
        var mechanisms = node.putObject("mechanisms");
        profile.mechanisms()
                .forEach((key, value) -> mechanisms.set(key, mechanism(value)));
        node.set("outerCorrelation", outerCorrelation(profile.outerCorrelation()));
        return node;
    }

    private static ObjectNode scans(ModelProfile.ScanProfile profile) {
        var node = JSON.createObjectNode();
        node.set("kinds", histogram(profile.kinds()));
        node.set("starDepth", distribution(profile.starDepth()));
        node.set("capacity", capacity(profile.capacity()));
        node.set("filterComplexity", distribution(profile.filterComplexity()));
        return node;
    }

    private static ObjectNode work(ModelProfile.WorkProfile profile) {
        var node = JSON.createObjectNode();
        node.set("capacity", capacity(profile.capacity()));
        node.set("scopeByScan", histogram(profile.scopeByScan()));
        return node;
    }

    private static ObjectNode mechanism(ModelProfile.MechanismProfile profile) {
        var node = JSON.createObjectNode();
        node.set("shape", distribution(profile.shape()));
        node.set("capacity", capacity(profile.capacity()));
        return node;
    }

    private static ObjectNode outerCorrelation(ModelProfile.OuterCorrelationProfile profile) {
        var node = JSON.createObjectNode();
        node.set("forms", histogram(profile.forms()));
        node.set("capacity", capacity(profile.capacity()));
        return node;
    }

    private static ObjectNode resolution(ModelProfile.ResolutionProfile profile) {
        var node = JSON.createObjectNode();
        node.set("walkUpDistance", distribution(profile.walkUpDistance()));
        return node;
    }

    private static ObjectNode computations(ModelProfile.ComputationProfile profile) {
        var node = JSON.createObjectNode();
        node.set("programs", program(profile.programs()));
        node.set("shape", computationShape(profile.shape()));
        node.set("dependencies", dependencies(profile.dependencies()));
        return node;
    }

    private static ObjectNode computationShape(ModelProfile.ComputationShape profile) {
        var node = JSON.createObjectNode();
        node.set("computedFieldKinds", histogram(profile.computedFieldKinds()));
        node.set("tables", tables(profile.tables()));
        node.set("archetypes", histogram(profile.archetypes()));
        node.set("aggregateScaleMatch", histogram(profile.aggregateScaleMatch()));
        return node;
    }

    private static ObjectNode tables(ModelProfile.TableProfile profile) {
        var node = JSON.createObjectNode();
        node.set("alternatives", distribution(profile.alternatives()));
        node.set("preconditions", distribution(profile.preconditions()));
        node.set("toleranceBands", histogram(profile.toleranceBands()));
        return node;
    }

    private static ObjectNode dependencies(ModelProfile.DependencyProfile profile) {
        var node = JSON.createObjectNode();
        node.put("maxCascadeDepth", profile.maxCascadeDepth());
        node.put("feedingComputations", profile.feedingComputations());
        return node;
    }

    private static ObjectNode wiring(ModelProfile.WiringProfile profile) {
        var node = JSON.createObjectNode();
        node.set("referencedFields", histogram(profile.referencedFields()));
        node.set("fieldFanOut", distribution(profile.fieldFanOut()));
        return node;
    }

    static ObjectNode histogram(LongHistogram histogram) {
        var node = JSON.createObjectNode();
        histogram.counts().forEach(node::put);
        return node;
    }

    static ObjectNode distribution(LongDistribution distribution) {
        var node = JSON.createObjectNode();
        node.put("count", distribution.count());
        node.put("min", distribution.min());
        node.put("median", distribution.median());
        node.put("p90", distribution.p90());
        node.put("max", distribution.max());
        var buckets = node.putArray("buckets");
        for (LongDistribution.Bucket bucket : distribution.buckets()) {
            var entry = buckets.addObject();
            entry.put("lower", bucket.lower());
            entry.put("upper", bucket.upper());
            entry.put("count", bucket.count());
        }
        return node;
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        var printer = new DefaultPrettyPrinter();
        var indenter = new DefaultIndenter("  ", "\n");
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }
}
