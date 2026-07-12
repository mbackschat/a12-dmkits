package io.github.mbackschat.a12.dm.adapter;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mgmtp.a12.kernel.md.facade.DocumentModelServiceFactory;
import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.IElement;
import com.mgmtp.a12.kernel.md.model.api.IField;
import com.mgmtp.a12.kernel.md.model.api.IGroup;
import com.mgmtp.a12.kernel.md.model.api.services.IDocumentModelService;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IBooleanType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IConfirmType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.ICustomFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateFragmentType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateRangeType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateTimeType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IDateType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IEnumerationType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IFieldType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.INumberType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.IStringType;
import com.mgmtp.a12.kernel.md.model.api.fieldtypes.ITimeType;

import io.github.mbackschat.a12.dm.core.ModelResolver;
import io.github.mbackschat.a12.dm.core.ReferenceResolvers;
import io.github.mbackschat.a12.dm.core.ValueKind;

/**
 * The <b>kernel-as-oracle</b> name-path index — the preserved kernel-typed read-back that
 * {@code dm.core.ModelIndex} carried before the decoupling Stage 2 pivot made it kernel-free. It loads a model
 * through the REAL kernel ({@code deserialize → expand}) and exposes the kernel's
 * {@link IField}/{@link IGroup}/{@link IFieldType}/{@link IDocumentModel} by name-path, so the kernel-honored-slot
 * verification tests (a slot the silent-ignore deserializer would otherwise hide is only proven honored by
 * reading it back through the kernel's typed getter) keep proving exactly what they proved — now on the
 * {@code :adapter} (kernel-available) side. It is also the oracle the Stage-2 parity net compares the kernel-free
 * reader against ({@code ModelIndexParityTest}). Lives in {@code :adapter} test scope only; never shipped.
 */
final class KernelModelOracle {

    private static final DocumentModelServiceFactory FACTORY = new DocumentModelServiceFactory();

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
            IDocumentModelService service = FACTORY.createDocumentModelService();
            IDocumentModel model = FACTORY.createDocumentModelSerializer().deserialize(new StringReader(dmJson));
            service.expand(model, KernelResolvers.of(resolver));
            Map<String, IElement> byPath = new LinkedHashMap<>();
            index(model.getContent().getDocumentModelRoot(), "", byPath);
            return new KernelModelOracle(model, byPath);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to load model for the kernel oracle", e);
        }
    }

    /** Key every FIELD/GROUP by its name-path — the join of {@code getName()} segments (F-IDX), matching the
     *  reader; rules/computations are not queryable elements (ModelIndex indexes fields + groups only). */
    private static void index(IGroup group, String prefix, Map<String, IElement> byPath) {
        for (IElement element : group.getElements()) {
            if (!(element instanceof IField) && !(element instanceof IGroup)) {
                continue;   // a rule / computation is not indexed
            }
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

    // ── parity facts (the kernel side of ModelIndexParityTest) ────────────────────────────────────────────

    /** Every indexed name-path (fields + groups). */
    Set<String> paths() {
        return byPath.keySet();
    }

    /** The complete read surface of the element at {@code path}, read through the kernel (the oracle). */
    ElementFacts factsAt(String path) {
        IElement element = require(path);
        List<String> annotations = element.getAnnotations().stream()
                .map(a -> a.getName() + "=" + a.getValue()).toList();
        if (element instanceof IGroup g) {
            return new ElementFacts(true, null, null, null, null, null, null, null, null, null,
                    g.getRepeatability(), annotations);
        }
        IField field = (IField) element;
        IFieldType type = field.getEffectiveType().orElseGet(field::getFieldType);
        Integer scale = null;
        String min = null;
        String max = null;
        Integer minLength = null;
        Integer maxLength = null;
        String pattern = null;
        List<String> enumValues = null;
        Map<String, List<String>> enumCategories = null;
        if (type instanceof INumberType number) {
            scale = number.getMaxFractionalDigits();
            min = number.getMinValue().map(BigDecimal::toPlainString).orElse(null);
            max = number.getMaxValue().map(BigDecimal::toPlainString).orElse(null);
        } else if (type instanceof IEnumerationType enumeration) {
            enumValues = enumeration.getValues().stream().map(IEnumerationType.IEnumValue::getValue).toList();
            enumCategories = new LinkedHashMap<>();
            for (IEnumerationType.IEnumCategory category : enumeration.getCategories()) {
                enumCategories.put(category.getName(), category.getValues().stream().distinct().toList());
            }
        } else if (type instanceof IStringType string) {
            minLength = string.getMinLength().orElse(null);
            maxLength = string.getMaxLength().orElse(null);
            pattern = string.getPattern().filter(p -> !p.isEmpty()).orElse(null);
        }
        return new ElementFacts(false, valueKind(type).name(), scale, min, max, minLength, maxLength, pattern,
                enumValues, enumCategories, null, annotations);
    }

    /** Classify a kernel {@link IFieldType} into a {@link ValueKind} (subtype-first: DateTime/Time before Date). */
    private static ValueKind valueKind(IFieldType type) {
        if (type instanceof IDateTimeType) {
            return ValueKind.DATE_TIME;
        }
        if (type instanceof ITimeType) {
            return ValueKind.TIME;
        }
        if (type instanceof IDateType) {
            return ValueKind.DATE;
        }
        if (type instanceof IDateFragmentType) {
            return ValueKind.DATE_FRAGMENT;
        }
        if (type instanceof IDateRangeType) {
            return ValueKind.DATE_RANGE;
        }
        if (type instanceof INumberType) {
            return ValueKind.NUMBER;
        }
        if (type instanceof IStringType) {
            return ValueKind.STRING;
        }
        if (type instanceof IEnumerationType) {
            return ValueKind.ENUMERATION;
        }
        if (type instanceof IConfirmType) {
            return ValueKind.CONFIRM;
        }
        if (type instanceof IBooleanType) {
            return ValueKind.BOOLEAN;
        }
        if (type instanceof ICustomFieldType) {
            return ValueKind.CUSTOM;
        }
        return ValueKind.UNKNOWN;
    }
}
