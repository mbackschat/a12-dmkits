package io.github.mbackschat.a12.rulekit.model;

import java.util.LinkedHashMap;
import java.util.Map;

import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.IElement;
import com.mgmtp.a12.kernel.md.model.api.IField;
import com.mgmtp.a12.kernel.md.model.api.IGroup;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.INumberType;

/**
 * A queryable index over a fully-expanded DocumentModel — the foundation everything else binds to
 * (proposal §5a / F0). Maps each element's absolute path to its model node so the AST, renderer,
 * and pre-checks can resolve paths, operand kinds, and repetition structure.
 *
 * <p>Build via {@link #of(IDocumentModel)} on a model already expanded + consistency-checked by
 * {@link ModelLoader}. Lookups fail fast with {@link IllegalArgumentException} on an unknown path or
 * a path that resolves to the wrong kind of node.
 */
public final class ModelIndex {

    private final IDocumentModel model;
    private final Map<String, IElement> byPath;

    private ModelIndex(IDocumentModel model, Map<String, IElement> byPath) {
        this.model = model;
        this.byPath = byPath;
    }

    /** Index an already-expanded, consistency-checked model (see {@link ModelLoader}). */
    public static ModelIndex of(IDocumentModel expandedModel) {
        Map<String, IElement> byPath = new LinkedHashMap<>();
        index(expandedModel.getContent().getDocumentModelRoot(), byPath);
        return new ModelIndex(expandedModel, byPath);
    }

    private static void index(IGroup group, Map<String, IElement> byPath) {
        for (IElement element : group.getElements()) {
            byPath.put(element.getId(), element);
            if (element instanceof IGroup childGroup) {
                index(childGroup, byPath);
            }
        }
    }

    /** The underlying expanded model. */
    public IDocumentModel model() {
        return model;
    }

    /** The {@link ValueKind} of the field at {@code fieldPath} (type-definitions resolved). */
    public ValueKind kindOf(String fieldPath) {
        return ValueKind.of(effectiveType(field(fieldPath)));
    }

    /**
     * The declared decimal scale (max fractional digits) of the number field at {@code fieldPath}.
     *
     * @throws IllegalArgumentException if the field is not a number
     */
    public int numberScale(String fieldPath) {
        IFieldType type = effectiveType(field(fieldPath));
        if (!(type instanceof INumberType number)) {
            throw new IllegalArgumentException(
                    fieldPath + " is not a number field (kind=" + ValueKind.of(type) + ")");
        }
        return number.getMaxFractionalDigits();
    }

    /** Whether the group at {@code groupPath} can repeat (repeatability other than exactly 1). */
    public boolean isRepeating(String groupPath) {
        return group(groupPath).getRepeatability() != 1;
    }

    private static IFieldType effectiveType(IField field) {
        return field.getEffectiveType().orElseGet(field::getFieldType);
    }

    private IField field(String path) {
        if (require(path) instanceof IField field) {
            return field;
        }
        throw new IllegalArgumentException("not a field: " + path);
    }

    private IGroup group(String path) {
        if (require(path) instanceof IGroup group) {
            return group;
        }
        throw new IllegalArgumentException("not a group: " + path);
    }

    private IElement require(String path) {
        IElement element = byPath.get(path);
        if (element == null) {
            throw new IllegalArgumentException("no element at path: " + path);
        }
        return element;
    }
}
