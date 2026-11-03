package io.github.mbackschat.a12.dm.adapter.review;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.mbackschat.a12.dm.adapter.KernelAdapter;
import io.github.mbackschat.a12.dm.core.ValueKind;
import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.FieldMeta;
import io.github.mbackschat.a12.dm.modelkit.FieldTypeSpec;
import io.github.mbackschat.a12.dm.modelkit.ModelDocument;
import io.github.mbackschat.a12.dm.modelkit.Requiredness;

/** Projects complete typed authored field state into deterministic public diff aspects. */
final class FieldDiffProjector {

    private FieldDiffProjector() {}

    static Map<String, Map<String, String>> project(ModelDiffState state) {
        Map<String, ValueKind> effectiveKinds = new LinkedHashMap<>();
        state.model().fields().forEach(field -> effectiveKinds.put(field.path(), field.kind()));
        Map<String, Map<String, String>> fields = new LinkedHashMap<>();
        state.authored().fields().forEach((path, field) ->
                fields.put(path, aspects(field, effectiveKinds.getOrDefault(path, ValueKind.UNKNOWN))));
        return fields;
    }

    static List<String> reasons(ModelDiff.ChangeKind change, String id, List<ModelDiff.Delta> deltas,
            Map<String, Set<String>> baseReferrers, Map<String, Set<String>> headReferrers,
            Map<String, Set<String>> baseCriteria) {
        List<String> reasons = new ArrayList<>();
        switch (change) {
            case ADDED -> reasons.add("STRUCTURE_ADDED");
            case REMOVED -> {
                reasons.add("STRUCTURE_REMOVED");
                // a uniqueness criterion names the field too; removing it leaves the model invalid (KF671)
                if (hasReaders(baseReferrers, id) || baseCriteria.containsKey(id)) {
                    reasons.add("REFERENCED_ELEMENT_REMOVED");
                }
            }
            case MODIFIED -> {
                deltas.forEach(delta -> reasons.add(modifiedReason(delta)));
                if (deltas.stream().anyMatch(FieldDiffProjector::isRetype)
                        && (hasReaders(baseReferrers, id) || hasReaders(headReferrers, id))) {
                    reasons.add("RETYPE_AFFECTS_READERS");
                }
            }
            case REORDERED, RENAMED, MOVED -> { }
        }
        return reasons;
    }

    static String modifiedReason(ModelDiff.Delta delta) {
        String aspect = delta.aspect();
        if (aspect.equals("kind")) {
            return "KIND_CHANGED";
        }
        if (aspect.equals("declaredType") || aspect.equals("typeDefinition")) {
            return "TYPE_CHANGED";
        }
        if (aspect.equals("requiredness")) {
            return requirednessRank(delta.after()) < requirednessRank(delta.before())
                    ? "CONSTRAINT_LOOSENED" : "CONSTRAINT_TIGHTENED";
        }
        if (aspect.startsWith("enum.value.")
                && ("present".equals(delta.before()) || "present".equals(delta.after()))) {
            return delta.before() == null ? "CONSTRAINT_LOOSENED" : "CONSTRAINT_TIGHTENED";
        }
        if (aspect.equals("min") || aspect.equals("minLength")) {
            return ModelDiff.constraintReason(true, delta);
        }
        if (aspect.equals("max") || aspect.equals("maxLength")) {
            return ModelDiff.constraintReason(false, delta);
        }
        if (aspect.equals("pattern")) {
            return ModelDiff.patternChangeReason(delta);
        }
        if (aspect.equals("rejectBefore1900")) {
            return Boolean.parseBoolean(delta.after()) ? "CONSTRAINT_TIGHTENED" : "CONSTRAINT_LOOSENED";
        }
        if (aspect.equals("scale") || aspect.equals("minFractionalDigits")) {
            return "SCALE_CHANGED";
        }
        if (isMessage(aspect)) {
            return "MESSAGE_CHANGED";
        }
        return isMetadata(aspect) ? "METADATA_CHANGED" : "FIELD_CONFIG_CHANGED";
    }

    /** Whether anything READS {@code id} — including through an ancestor group operand, which the kernel expands
     *  recursively to every field below it. An exact key lookup missed exactly those readers, so neither
     *  {@code REFERENCED_ELEMENT_REMOVED} nor {@code RETYPE_AFFECTS_READERS} fired for a field a rule reaches only
     *  through its group ({@code CG16}). */
    private static boolean hasReaders(Map<String, Set<String>> referrers, String id) {
        return KernelAdapter.isReadByAnything(referrers, id);
    }

    private static boolean isRetype(ModelDiff.Delta delta) {
        return delta.aspect().equals("kind") || delta.aspect().equals("declaredType")
                || delta.aspect().equals("typeDefinition");
    }

    private static int requirednessRank(String name) {
        if (name == null) {
            return 0;
        }
        return switch (Requiredness.valueOf(name)) {
            case OPTIONAL -> 0;
            case REQUIRED_IF_PARENT_PRESENT -> 1;
            case REQUIRED -> 2;
        };
    }

    private static boolean isMessage(String aspect) {
        return aspect.startsWith("requiredMessage.") || aspect.startsWith("patternMessage.")
                || aspect.startsWith("enum.invalidMessage.");
    }

    private static boolean isMetadata(String aspect) {
        return aspect.startsWith("label.") || aspect.startsWith("helperText.")
                || aspect.startsWith("internalDescription.") || aspect.startsWith("externalDescription.")
                || aspect.startsWith("annotation.") || aspect.startsWith("enum.label.")
                || aspect.startsWith("enum.categoryDescription.")
                || aspect.equals("global") || aspect.equals("transient") || aspect.equals("alphabeticalSorting");
    }

    private static Map<String, String> aspects(ModelDocument.FieldState field, ValueKind effectiveKind) {
        Map<String, String> aspects = new LinkedHashMap<>();
        FieldTypeSpec type = field.type();
        aspects.put("declaredType", type.jsonType());
        aspects.put("kind", effectiveKind.name());
        putType(aspects, type);
        aspects.put("requiredness", field.requirements().requiredness().name());
        putSorted(aspects, "requiredMessage.", field.requirements().messages());
        putMeta(aspects, field.meta());
        return aspects;
    }

    static void putType(Map<String, String> aspects, FieldTypeSpec type) {
        switch (type) {
            case FieldTypeSpec.StringSpec string -> putString(aspects, string);
            case FieldTypeSpec.NumberSpec number -> putNumber(aspects, number);
            case FieldTypeSpec.DateSpec date -> {
                put(aspects, "format", date.format());
                put(aspects, "datePrecision", date.datePrecision());
                aspects.put("rejectBefore1900", Boolean.toString(date.rejectBefore1900()));
            }
            case FieldTypeSpec.TimeSpec time -> put(aspects, "format", time.format());
            case FieldTypeSpec.DateTimeSpec dateTime -> put(aspects, "format", dateTime.format());
            case FieldTypeSpec.DateRangeSpec range -> {
                put(aspects, "format", range.format());
                put(aspects, "rangeSeparator", range.separator());
                // Without this a diff between two models differing ONLY in the endpoint-year interpretation
                // reports no change — the record component is new, and adding one does not break the sealed
                // switch's exhaustiveness, so the compiler gave no signal.
                aspects.put("interpretationOfYear", range.interpretationOfYear().name());
                aspects.put("rejectBefore1900", Boolean.toString(range.rejectBefore1900()));
            }
            case FieldTypeSpec.DateFragmentSpec fragment -> {
                put(aspects, "format", fragment.fragmentFormat());
                aspects.put("rejectBefore1900", Boolean.toString(fragment.rejectBefore1900()));
            }
            case FieldTypeSpec.TypeDefRef reference -> aspects.put("typeDefinition", reference.typeDefinitionId());
            case FieldTypeSpec.BooleanSpec ignored -> { }
            case FieldTypeSpec.ConfirmSpec ignored -> { }
            case FieldTypeSpec.EnumSpec enumeration -> putEnumeration(aspects, enumeration);
            case FieldTypeSpec.CustomSpec custom -> {
                aspects.put("customType", custom.name());
                put(aspects, "minLength", custom.minLength());
                put(aspects, "maxLength", custom.maxLength());
            }
        }
    }

    private static void putString(Map<String, String> aspects, FieldTypeSpec.StringSpec string) {
        put(aspects, "minLength", string.minLength());
        put(aspects, "maxLength", string.maxLength());
        put(aspects, "pattern", string.pattern());
        aspects.put("lineBreaks", Boolean.toString(string.lineBreaksPermitted()));
        aspects.put("alphabeticalSorting", Boolean.toString(string.alphabeticalSorting()));
        aspects.put("noValueValidation", Boolean.toString(string.noValueValidation()));
        putSorted(aspects, "patternMessage.", string.patternMessages());
        string.suggestions().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            for (int i = 0; i < entry.getValue().size(); i++) {
                aspects.put("suggestion." + entry.getKey() + "[" + i + "]", entry.getValue().get(i));
            }
        });
    }

    private static void putNumber(Map<String, String> aspects, FieldTypeSpec.NumberSpec number) {
        aspects.put("minFractionalDigits", Integer.toString(defaultZero(number.minFractionalDigits())));
        aspects.put("scale", Integer.toString(defaultZero(number.maxFractionalDigits())));
        putDecimal(aspects, "min", number.minValue());
        putDecimal(aspects, "max", number.maxValue());
        aspects.put("zeroNotAllowed", Boolean.toString(number.zeroNotAllowed()));
        aspects.put("leadingZerosAllowed", Boolean.toString(number.leadingZerosAllowed()));
        aspects.put("positivesOnly", Boolean.toString(number.positivesOnly()));
        if (number.trait() != null) {
            aspects.put("unit", number.trait().name());
        }
    }

    private static void putEnumeration(Map<String, String> aspects, FieldTypeSpec.EnumSpec enumeration) {
        for (int i = 0; i < enumeration.values().size(); i++) {
            FieldTypeSpec.EnumValue value = enumeration.values().get(i);
            aspects.put("enum.value." + value.value(), "present");
            aspects.put("enum.position." + value.value(), Integer.toString(i + 1));
            putSorted(aspects, "enum.label." + value.value() + ".", value.labels());
        }
        for (FieldTypeSpec.EnumCategory category : enumeration.categories()) {
            for (int i = 0; i < category.values().size(); i++) {
                aspects.put("enum.category." + category.name() + "[" + i + "]", category.values().get(i));
            }
            put(aspects, "enum.categoryDescription." + category.name(), category.description());
        }
        aspects.put("alphabeticalSorting", Boolean.toString(enumeration.alphabeticalSorting()));
        putSorted(aspects, "enum.invalidMessage.", enumeration.invalidValueMessages());
    }

    private static void putMeta(Map<String, String> aspects, FieldMeta meta) {
        putSorted(aspects, "label.", meta.labels());
        putSorted(aspects, "helperText.", meta.helperText());
        putDescriptive(aspects, meta.internalDescription(), meta.externalDescription(), meta.annotations());
        aspects.put("global", Boolean.toString(meta.global()));
        aspects.put("transient", Boolean.toString(meta.notPersisted()));
    }

    static void putDescriptive(Map<String, String> aspects, ModelDocument.DescriptiveState meta) {
        putDescriptive(aspects, meta.internalDescription(), meta.externalDescription(), meta.annotations());
    }

    private static void putDescriptive(Map<String, String> aspects, Map<String, String> internal,
            Map<String, String> external, List<ElementDraft.Annotation> annotations) {
        putSorted(aspects, "internalDescription.", internal);
        putSorted(aspects, "externalDescription.", external);
        annotations.stream().sorted(Comparator.comparing(ElementDraft.Annotation::name))
                .forEach(annotation -> aspects.put("annotation." + annotation.name(), annotation.value()));
    }

    static void putSorted(Map<String, String> aspects, String prefix, Map<String, String> values) {
        values.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> aspects.put(prefix + entry.getKey(), entry.getValue()));
    }

    private static void put(Map<String, String> aspects, String key, Object value) {
        if (value != null) {
            aspects.put(key, value.toString());
        }
    }

    private static int defaultZero(Integer value) {
        return value == null ? 0 : value;
    }

    private static void putDecimal(Map<String, String> aspects, String key, BigDecimal value) {
        if (value != null) {
            aspects.put(key, value.stripTrailingZeros().toPlainString());
        }
    }
}
