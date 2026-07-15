package io.github.mbackschat.a12.dm.adapter.capture.runner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.capture.dto.Pointers;
import io.github.mbackschat.a12.dm.adapter.capture.schema.ScenarioSet;
import io.github.mbackschat.a12.dm.core.ComputationInfo;
import io.github.mbackschat.a12.dm.core.ModelIndex;

/**
 * The NEUTRAL per-case runner input: the case, its model's exact JSON text, the effective probe scope,
 * and the model-derived target→declaration mapping. The probe scope is the request's explicit probes plus
 * — automatically — every computation target (proposal §4: a packet can never imply whole-document
 * correspondence from a few inspected cells, and every target is always probed). Immutable, so sharing
 * one execution across runners leaks no state; each runner still builds its OWN engine and document.
 */
public record CaseExecution(
        String caseId,
        String modelJson,
        Optional<String> clock,
        String locale,
        List<ScenarioSet.Placement> placements,
        List<String> probePointers,
        Map<String, String> computationByTargetPath) {

    public CaseExecution {
        placements = List.copyOf(placements);
        probePointers = List.copyOf(probePointers);
        computationByTargetPath = Collections.unmodifiableMap(new LinkedHashMap<>(computationByTargetPath));
    }

    /** Build the execution for {@code scenarioCase} over {@code modelJson} (parsed kernel-free). */
    public static CaseExecution of(ScenarioSet.ScenarioCase scenarioCase, String modelJson, String locale) {
        ModelIndex index = ModelIndex.ofSelfContained(modelJson);
        Map<String, String> byTarget = new LinkedHashMap<>();
        Set<String> probes = new LinkedHashSet<>(scenarioCase.probes());
        for (ComputationInfo computation : index.computations()) {
            // v1 rejects repeatable targets, so the all-ones pointer names the one target instance.
            probes.add(Pointers.format(computation.targetFieldPath(),
                    Collections.nCopies(computation.targetFieldPath().substring(1).split("/").length, 1)));
            // The target→declaration mapping is unique when one computation targets the field; a multi-
            // computation target maps to null (recorded as no declared computation, never a guess).
            byTarget.put(computation.targetFieldPath(),
                    byTarget.containsKey(computation.targetFieldPath()) ? null : computation.path());
        }
        return new CaseExecution(scenarioCase.caseId(), modelJson, scenarioCase.clock(), locale,
                scenarioCase.placements(), List.copyOf(probes), byTarget);
    }

    /** The declaring computation of the target at {@code pointer} (indices stripped), or null when the
     *  model does not make it unique. */
    public String declaredComputationOf(String pointer) {
        return computationByTargetPath.get(pointer.replaceAll("\\[\\d+\\]", ""));
    }
}
