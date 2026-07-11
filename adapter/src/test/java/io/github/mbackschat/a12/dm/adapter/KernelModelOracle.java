package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.IElement;
import com.mgmtp.a12.kernel.md.model.api.IField;
import com.mgmtp.a12.kernel.md.model.api.IGroup;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IFieldType;

import io.github.mbackschat.a12.dm.core.ModelLoader;
import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;

/**
 * The <b>kernel-as-oracle</b> name-path index — the preserved kernel-typed read-back that
 * {@code dm.core.ModelIndex} carried before the decoupling Stage 2 pivot made it kernel-free. It loads a model
 * through the REAL kernel ({@code deserialize → expand → checkConsistency}) and exposes the kernel's
 * {@link IField}/{@link IGroup}/{@link IFieldType}/{@link IDocumentModel} by name-path, so the kernel-honored-slot
 * verification tests (a slot the silent-ignore deserializer would otherwise hide is only proven honored by
 * reading it back through the kernel's typed getter) keep proving exactly what they proved — now on the
 * {@code :adapter} (kernel-available) side. It is also the oracle the Stage-2 parity net compares the kernel-free
 * reader against ({@code ModelIndexParityTest}). Lives in {@code :adapter} test scope only; never shipped.
 */
final class KernelModelOracle {

    private final IDocumentModel model;
    private final Map<String, IElement> byPath;

    private KernelModelOracle(IDocumentModel model, Map<String, IElement> byPath) {
        this.model = model;
        this.byPath = byPath;
    }

    /** Load + index a <em>self-contained</em> model through the real kernel. */
    static KernelModelOracle ofSelfContained(String dmJson) {
        return of(dmJson, ReferenceResolvers.selfContained());
    }

    /** Load + index a model through the real kernel, resolving includes / imported type-defs against {@code resolver}. */
    static KernelModelOracle of(String dmJson, ModelResolver resolver) {
        try {
            IDocumentModel model = ModelLoader.load(new StringReader(dmJson), resolver);
            Map<String, IElement> byPath = new LinkedHashMap<>();
            index(model.getContent().getDocumentModelRoot(), "", byPath);
            return new KernelModelOracle(model, byPath);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load model for the kernel oracle", e);
        }
    }

    /** Key every element by its name-path — the join of {@code getName()} segments (F-IDX), matching the reader. */
    private static void index(IGroup group, String prefix, Map<String, IElement> byPath) {
        for (IElement element : group.getElements()) {
            String path = prefix + "/" + element.getName();
            byPath.put(path, element);
            if (element instanceof IGroup childGroup) {
                index(childGroup, path, byPath);
            }
        }
    }

    /** The underlying expanded kernel model. */
    IDocumentModel model() {
        return model;
    }

    /** The kernel's resolved {@link IFieldType} for the field at {@code fieldPath} (type-definitions resolved). */
    IFieldType fieldType(String fieldPath) {
        IField field = field(fieldPath);
        return field.getEffectiveType().orElseGet(field::getFieldType);
    }

    /** The kernel's {@link IField} at {@code fieldPath}. */
    IField field(String path) {
        if (require(path) instanceof IField field) {
            return field;
        }
        throw new IllegalArgumentException("not a field: " + path);
    }

    /** The kernel's {@link IGroup} at {@code groupPath}. */
    IGroup group(String path) {
        if (require(path) instanceof IGroup group) {
            return group;
        }
        throw new IllegalArgumentException("not a group: " + path);
    }

    /** Whether the group at {@code groupPath} can repeat (repeatability other than exactly 1). */
    boolean isRepeating(String groupPath) {
        return group(groupPath).getRepeatability() != 1;
    }

    /** Paths of the fields directly contained in the group at {@code groupPath}, in model order. */
    java.util.stream.Stream<String> fieldPaths(String groupPath) {
        return group(groupPath).getElements().stream()
                .filter(IField.class::isInstance)
                .map(e -> groupPath + "/" + e.getName());
    }

    private IElement require(String path) {
        IElement element = byPath.get(path);
        if (element == null) {
            throw new IllegalArgumentException("no element at path: " + path);
        }
        return element;
    }
}
