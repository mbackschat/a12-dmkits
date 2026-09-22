package io.github.mbackschat.a12.dm.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.mbackschat.a12.dm.core.ModelIndex;
import io.github.mbackschat.a12.dm.core.ModelReader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader;

/**
 * Test-only, representation-neutral observation of raw model preparation through the real Kernel and the JVM
 * reader. Raw JSON is deliberately not compared: Kernel expansion retains opaque ids and named types, while
 * {@link ModelReader} emits interpreter-ready path ids and effective concrete types.
 */
final class PreparationParityHarness {

    private PreparationParityHarness() {
    }

    static PreparationObservation observe(String entryJson, ModelResolver resolver) {
        var kernel = KernelModelOracle.of(entryJson, resolver);
        var kernelAdapter = KernelAdapter.ofJson(entryJson, resolver);
        var reader = ModelReader.expand(entryJson, resolver);
        var readerIndex = ModelIndex.of(reader);
        var readerModel = ModelLoader.load(reader.toJson());

        return new PreparationObservation(
                new PreparedSurface(
                        kernelFacts(kernel),
                        kernelAdapter.ruleFullNames(),
                        kernelAdapter.computationTargetFields()),
                new PreparedSurface(
                        readerFacts(readerIndex),
                        readerModel.getRules().stream()
                                .map(rule -> rule.getPath())
                                .collect(Collectors.toCollection(LinkedHashSet::new)),
                        readerModel.getComputations().stream()
                                .collect(Collectors.toMap(
                                        computation -> computation.getGroup() + "/" + requireName(computation.getName()),
                                        computation -> computation.getTargetPath(),
                                        (left, right) -> left,
                                LinkedHashMap::new))));
    }

    static ElementObservation observeElements(String entryJson, ModelResolver resolver) {
        return new ElementObservation(
                observeKernelElements(entryJson, resolver),
                observeReaderElements(entryJson, resolver));
    }

    static Map<String, ElementFacts> observeKernelElements(String entryJson, ModelResolver resolver) {
        return kernelFacts(KernelModelOracle.of(entryJson, resolver));
    }

    static Map<String, ElementFacts> observeReaderElements(String entryJson, ModelResolver resolver) {
        return readerFacts(ModelIndex.of(entryJson, resolver));
    }

    static void assertEquivalent(PreparationObservation observation) {
        assertEquals(observation.kernel(), observation.reader(),
                "the JVM reader preparation diverged from real-Kernel expansion");
    }

    static void assertElementsEquivalent(ElementObservation observation) {
        assertEquals(observation.kernel(), observation.reader(),
                "the JVM reader element preparation diverged from real-Kernel expansion");
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("prepared computation has no name");
        }
        return name;
    }

    private static Map<String, ElementFacts> kernelFacts(KernelModelOracle kernel) {
        var facts = new LinkedHashMap<String, ElementFacts>();
        for (var path : kernel.paths()) {
            facts.put(path, kernel.factsAt(path));
        }
        return facts;
    }

    private static Map<String, ElementFacts> readerFacts(ModelIndex index) {
        var facts = new LinkedHashMap<String, ElementFacts>();
        index.elementPaths().forEach(path -> facts.put(path, readerFactsAt(index, path)));
        return facts;
    }

    private static ElementFacts readerFactsAt(ModelIndex index, String path) {
        if (index.isGroup(path)) {
            return new ElementFacts(true, null, null, null, null, null, null, null, null, null,
                    index.maxRepetitions(path), index.usageTypeOfGroup(path).orElse(null),
                    annotations(index.annotationsOfGroup(path)));
        }
        var kind = index.kindOf(path);
        Integer scale = kind == ValueKind.NUMBER
                ? index.numberScale(path)
                : null;
        var min = index.numberMin(path).map(BigDecimal::toPlainString).orElse(null);
        var max = index.numberMax(path).map(BigDecimal::toPlainString).orElse(null);
        var minLength = index.stringMinLength(path).isPresent()
                ? index.stringMinLength(path).getAsInt()
                : null;
        var maxLength = index.stringMaxLength(path).isPresent()
                ? index.stringMaxLength(path).getAsInt()
                : null;
        var pattern = index.stringPattern(path).orElse(null);
        List<String> enumValues = null;
        Map<String, List<String>> enumCategories = null;
        if (kind == ValueKind.ENUMERATION) {
            enumValues = index.enumValues(path);
            enumCategories = index.enumCategories(path);
        }
        return new ElementFacts(false, kind.name(), scale, min, max, minLength, maxLength, pattern,
                enumValues, enumCategories, null, null, annotations(index.annotationsOf(path)));
    }

    private static List<String> annotations(List<io.github.mbackschat.a12.dm.core.Annotation> annotations) {
        return annotations.stream().map(annotation -> annotation.name() + "=" + annotation.value()).toList();
    }

    record PreparationObservation(PreparedSurface kernel, PreparedSurface reader) {

        PreparationObservation withReader(PreparedSurface replacement) {
            return new PreparationObservation(kernel, replacement);
        }
    }

    record ElementObservation(Map<String, ElementFacts> kernel, Map<String, ElementFacts> reader) {

        ElementObservation {
            kernel = Collections.unmodifiableMap(new LinkedHashMap<>(kernel));
            reader = Collections.unmodifiableMap(new LinkedHashMap<>(reader));
        }
    }

    record PreparedSurface(
            Map<String, ElementFacts> elements,
            Set<String> rules,
            Map<String, String> computations) {

        PreparedSurface {
            elements = Collections.unmodifiableMap(new LinkedHashMap<>(elements));
            rules = Collections.unmodifiableSet(new LinkedHashSet<>(rules));
            computations = Collections.unmodifiableMap(new LinkedHashMap<>(computations));
        }

        Set<String> fieldPaths() {
            var paths = new LinkedHashSet<String>();
            elements.forEach((path, facts) -> {
                if (!facts.group()) {
                    paths.add(path);
                }
            });
            return Collections.unmodifiableSet(paths);
        }

        PreparedSurface withoutElement(String path) {
            var remaining = new LinkedHashMap<>(elements);
            remaining.remove(path);
            return new PreparedSurface(remaining, rules, computations);
        }
    }
}
