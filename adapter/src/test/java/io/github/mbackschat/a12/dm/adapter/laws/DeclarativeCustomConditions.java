package io.github.mbackschat.a12.dm.adapter.laws;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentMultiPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.DocumentPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.PartiallyKnownDocumentMultiPointer;
import com.mgmtp.a12.kernel.md.document.apiV2.immutable.DocumentV2;
import com.mgmtp.a12.kernel.md.rt.api.ICustomCondition;

/**
 * Custom conditions declared in a probe request, bound to the kernel's {@code ICustomCondition} so a
 * {@code CustomCondition} rule is observable, with every invocation the kernel makes of them recorded.
 *
 * <p><b>Why a probe needs this at all.</b> A {@code CustomCondition} name resolves only through the per-call
 * {@code DocumentProcessingConfig.customConditionFactory}, so a request that registers none observes no rule that
 * uses one. a12-kernel-lean asked for the route on 2026-10-14 to calibrate its account of the call itself: which
 * relevant set, which formally incorrect set and which error entity the kernel hands a condition, and how often.
 *
 * <p><b>The behaviours are declarative</b> for the reason {@link DeclarativeCustomFieldTypes} gives: an artifact is
 * pinned by SHA-256, so every input must be reproducible from the request bytes. A condition either answers a
 * constant or answers whether the document holds a value at one A12 pointer, written in A12's own
 * {@code Group[2]/Field} spelling ({@code DocumentPointer.of}). A pointer must name one cell, so a wildcard
 * repetition is refused rather than given a meaning of our own.
 */
final class DeclarativeCustomConditions {

    private static final Set<String> KEYS = Set.of("returns", "whenFilled");

    /** One declared behaviour. */
    sealed interface Declared {

        boolean answer(DocumentV2 document);
    }

    /** A constant answer. */
    record Returns(boolean value) implements Declared {

        @Override
        public boolean answer(DocumentV2 document) {
            return value;
        }
    }

    /** True exactly when the document holds a value at {@code pointer}. */
    record WhenFilled(DocumentPointer pointer) implements Declared {

        @Override
        public boolean answer(DocumentV2 document) {
            return document.fieldValue(pointer) != null;
        }
    }

    private DeclarativeCustomConditions() {
    }

    /** The request's {@code customConditions} object, refused when empty or when any entry is malformed. */
    static Map<String, Declared> parse(JsonNode node) {
        if (!node.isObject() || node.isEmpty()) {
            throw new IllegalArgumentException("'customConditions' must be a non-empty object "
                    + "{\"<Name>\": {\"returns\": true|false} | {\"whenFilled\": \"<A12 pointer>\"}}, or be omitted; "
                    + "an empty one registers nothing and observes exactly what its absence does");
        }
        Map<String, Declared> declared = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> declared.put(entry.getKey(), declared(entry.getKey(), entry.getValue())));
        return declared;
    }

    private static Declared declared(String name, JsonNode spec) {
        String where = "custom condition \"" + name + "\"";
        if (!spec.isObject()) {
            throw new IllegalArgumentException(where + " must be an object with exactly one of returns, whenFilled");
        }
        spec.fieldNames().forEachRemaining(key -> {
            if (!KEYS.contains(key)) {
                throw new IllegalArgumentException(where + ": unknown key \"" + key + "\" (allowed: returns, whenFilled)");
            }
        });
        if (spec.size() != 1) {
            throw new IllegalArgumentException(where + " must declare exactly one of returns, whenFilled");
        }
        if (spec.has("returns")) {
            if (!spec.get("returns").isBoolean()) {
                throw new IllegalArgumentException(where + ": 'returns' must be true or false");
            }
            return new Returns(spec.get("returns").booleanValue());
        }
        return new WhenFilled(cell(where, spec.get("whenFilled")));
    }

    private static DocumentPointer cell(String where, JsonNode text) {
        if (!text.isTextual()) {
            throw new IllegalArgumentException(where + ": 'whenFilled' must be an A12 pointer string, such as "
                    + "Claim/Items[2]/Cost; received " + text);
        }
        DocumentPointer pointer;
        try {
            pointer = DocumentPointer.of(text.asText());
        } catch (RuntimeException unparseable) {
            throw new IllegalArgumentException(where + ": 'whenFilled' is not an A12 pointer: " + text.asText(),
                    unparseable);
        }
        if (pointer.repetitionIndexes().contains(0)) {
            throw new IllegalArgumentException(where + ": 'whenFilled' must be an A12 pointer that names one cell, "
                    + "such as Claim/Items[2]/Cost; received " + text);
        }
        return pointer;
    }

    /**
     * The invocations of one (row, strategy) block, in the order the kernel made them. Each block gets its own,
     * so a call is attributed to the strategy that made it, and {@link #enter} tags it with the channel it came
     * from: {@code validateFull} and {@code compute} both pass no relevant set, so the set cannot tell them apart.
     */
    static final class Recorder {

        private final Map<String, ICustomCondition> conditions = new LinkedHashMap<>();
        private final List<KernelProbeArtifact.ConditionCall> calls = new ArrayList<>();
        private final boolean declared;
        private KernelRuntimeProbe.Observation observation;

        Recorder(Map<String, Declared> declared) {
            this.declared = !declared.isEmpty();
            declared.forEach((name, behaviour) -> conditions.put(name, recording(name, behaviour)));
        }

        /** The kernel binding, empty when the request declares none so the call config is unchanged. */
        Map<String, ICustomCondition> conditions() {
            return conditions;
        }

        void enter(KernelRuntimeProbe.Observation channel) {
            this.observation = channel;
        }

        /** The record, present exactly when the request declares a condition. */
        Optional<List<KernelProbeArtifact.ConditionCall>> calls() {
            return declared ? Optional.of(List.copyOf(calls)) : Optional.empty();
        }

        private ICustomCondition recording(String name, Declared behaviour) {
            return new ICustomCondition() {
                @Override
                public boolean check(DocumentV2 document, Set<? extends DocumentMultiPointer> relevantEntities,
                        Set<DocumentPointer> formallyIncorrectEntities,
                        PartiallyKnownDocumentMultiPointer errorEntityInstance) {
                    boolean result = behaviour.answer(document);
                    // Both pointer sets are sorted: the kernel hands over sets, whose iteration order is no
                    // observation.
                    calls.add(new KernelProbeArtifact.ConditionCall(observation, name,
                            new KernelProbeArtifact.ConditionArguments(
                                    Optional.ofNullable(relevantEntities).map(set -> set.stream()
                                            .map(p -> KernelRuntimeProbe.address(p.fullName(), p.repetitionIndexes()))
                                            .sorted().toList()),
                                    formallyIncorrectEntities.stream()
                                            .map(p -> KernelRuntimeProbe.address(p.fullName(), p.repetitionIndexes()))
                                            .sorted().toList(),
                                    KernelRuntimeProbe.address(errorEntityInstance.fullName(),
                                            errorEntityInstance.repetitionIndexes())),
                            result));
                    return result;
                }
            };
        }
    }
}
