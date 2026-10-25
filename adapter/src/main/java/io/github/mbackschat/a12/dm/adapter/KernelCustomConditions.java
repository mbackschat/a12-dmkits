/*
 * SPDX-License-Identifier: EUPL-1.2 OR LicenseRef-commercial
 *
 * Copyright (c) 2012-2026 mgm technology partners GmbH
 */
package io.github.mbackschat.a12.dm.adapter;

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mgmtp.a12.kernel.core.rt.a12internal.validation.ICustomCondition;
import com.mgmtp.a12.kernel.core.rt.a12internal.validation.IData;
import com.mgmtp.a12.kernel.core.rt.a12internal.validation.IIdentifier;

import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition;
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionData;
import io.github.mbackschat.a12.dm.interpreter.model.CustomConditionFieldPointer;

/**
 * Bridges a project's EXISTING kernel <em>core</em> {@link ICustomCondition} (the {@code IData}-based
 * contract — the very one the kernel's own metamodel conditions and the JS/TS codegen runtime use) onto the
 * interpreter's kernel-free {@link CustomCondition} SPI. This is the "works with existing implementations"
 * seam: a JVM consumer hands us the same condition object the kernel runs, and the kernel-free interpreter
 * runs it unchanged — so the interpreter can replace the kernel's generated code. Once ordinary condition
 * evaluation reaches the leaf, the condition receives the same relevance, formal-invalid, and exact error-field
 * inputs and owns its hidden dependency decision; the interpreter does not infer that footprint. A true result
 * contributes VALUE polarity. KERNEL-FINDINGS "CustomCondition".
 *
 * <p><b>Boundary.</b> This is JVM-only adapter glue (it links the kernel core API) — the SPI it targets is
 * pure and multiplatform, so a Kotlin/JS or TypeScript consumer implements {@link CustomCondition} directly
 * without this bridge. Per the N8/D15 adapter guardrails the bridge constructs <b>no</b> kernel {@code …Impl}:
 * it supplies its own {@link IData} / {@link IIdentifier} (the kernel API documents that the <em>caller</em>
 * provides these — with content {@code equals}/{@code hashCode} — and that the runtime re-wraps them in its
 * own internal representation for its access scheme).
 */
public final class KernelCustomConditions {

    private KernelCustomConditions() {
    }

    /** Adapt one kernel core condition to the interpreter's {@link CustomCondition} SPI. */
    public static CustomCondition fromCore(ICustomCondition kernelCondition) {
        return (data, relevant, formallyIncorrect, errorField) -> kernelCondition.check(
                dataView(data),
                toIds(relevant),
                toIds(formallyIncorrect),
                toId(errorField));
    }

    /** Adapt a {@code name → condition} map — the shape a {@code DmInterpreter}'s registry expects. */
    public static Map<String, CustomCondition> fromCore(Map<String, ? extends ICustomCondition> kernelConditions) {
        return kernelConditions.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> fromCore(e.getValue())));
    }

    /** A read-only kernel {@link IData} backed by the interpreter's document-wide {@link CustomConditionData}. */
    private static IData dataView(CustomConditionData data) {
        return new IData() {
            // A12's IData never answers null: an absent or empty field reads ValidationData.VALUE_FOR_ABSENT_AND_EMPTY,
            // so a shipped condition may call isEmpty() on it (CustomConditionEmptyCellDiffTest).
            @Override public String getValue(IIdentifier entity) {
                return valueOrEmpty(entity);
            }

            @Override public String getValueForDisplay(IIdentifier entity) {
                return valueOrEmpty(entity);
            }

            private String valueOrEmpty(IIdentifier entity) {
                String value = data.getValue(toPointer(entity));
                return value == null ? "" : value;
            }

            @Override public Iterator<IIdentifier> iterator() {
                return Stream.concat(data.groups().stream(), data.fields().stream())
                        .map(KernelCustomConditions::toId).iterator();
            }

            @Override public String identifierToString(IIdentifier id) {
                return id.getName();
            }

            @Override public void addValue(IIdentifier field, String value) {
                throw new UnsupportedOperationException("a custom condition must not mutate the validation data");
            }
        };
    }

    private static Set<IIdentifier> toIds(Set<CustomConditionFieldPointer> pointers) {
        return pointers == null ? null
                : pointers.stream().map(KernelCustomConditions::toId).collect(Collectors.toSet());
    }

    private static IIdentifier toId(CustomConditionFieldPointer p) {
        return new PointerIdentifier(p.getPath(), p.getIndices().stream().mapToLong(Integer::longValue).toArray());
    }

    private static CustomConditionFieldPointer toPointer(IIdentifier id) {
        List<Integer> indices = Arrays.stream(id.getIndices()).mapToObj(l -> (int) l).toList();
        return new CustomConditionFieldPointer(id.getName(), indices);
    }

    /**
     * The caller-provided {@link IIdentifier} (a path + its 1-based repetition indices). The kernel requires
     * the caller's identifier to carry content {@code equals}/{@code hashCode} (it caches wrong values by it),
     * and re-wraps it in the runtime's own representation for all other uses.
     */
    private static final class PointerIdentifier implements IIdentifier {
        private final String name;
        private final long[] indices;

        PointerIdentifier(String name, long[] indices) {
            this.name = name;
            this.indices = indices;
        }

        @Override public String getName() {
            return name;
        }

        @Override public long[] getIndices() {
            return indices;
        }

        @Override public long getUsb() {
            return USB_NOT_SPECIFIED;
        }

        @Override public boolean equals(Object o) {
            return o instanceof PointerIdentifier other
                    && name.equals(other.name) && Arrays.equals(indices, other.indices);
        }

        @Override public int hashCode() {
            return 31 * name.hashCode() + Arrays.hashCode(indices);
        }
    }
}
