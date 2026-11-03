package io.github.mbackschat.a12.dm.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import com.mgmtp.a12.kernel.md.model.api.IDocumentModel;
import com.mgmtp.a12.kernel.md.model.api.IDocumentUniquenessCriterion;

import io.github.mbackschat.a12.dm.core.EntityPaths;

/**
 * The document uniqueness criteria of an expanded model, indexed by the fields they name (KERNEL-FINDINGS KF671).
 *
 * <p>A12 addresses a criterion only by its name and gives it no path, so the index maps each field to criterion
 * names rather than adding them to the adapter's rule and computation referrers, whose values are element paths. It
 * is read from the model A12 expands, which keeps only the entry model's criteria: an included model's own are gone,
 * and an entry criterion may name a mounted field.
 */
public final class UniquenessCriteria {

    private final Map<String, Set<String>> byField;

    private UniquenessCriteria(Map<String, Set<String>> byField) {
        this.byField = Collections.unmodifiableMap(byField);
    }

    /** The criteria of {@code expanded}, the model A12 built from the entry model and its includes. */
    static UniquenessCriteria of(IDocumentModel expanded) {
        Map<String, Set<String>> byField = new LinkedHashMap<>();
        for (IDocumentUniquenessCriterion criterion : expanded.getContent().getDocumentUniquenessCriteria()) {
            for (String field : criterion.getFieldFullNames()) {
                byField.computeIfAbsent(field, k -> new LinkedHashSet<>()).add(criterion.getName());
            }
        }
        byField.replaceAll((field, names) -> Collections.unmodifiableSet(names));
        return new UniquenessCriteria(byField);
    }

    /** Every field a criterion names, mapped to the names of the criteria naming it, in model order. */
    public Map<String, Set<String>> byField() {
        return byField;
    }

    /**
     * The criteria naming {@code entityPath} or a field below it. A criterion names fields only and never reaches
     * one through a group, so a field's answer is exact and a group's is its subtree, compared segment-wise.
     */
    public Set<String> within(String entityPath) {
        Set<String> within = new LinkedHashSet<>();
        byField.forEach((field, names) -> {
            if (EntityPaths.isInside(field, entityPath)) {
                within.addAll(names);
            }
        });
        return within;
    }
}
