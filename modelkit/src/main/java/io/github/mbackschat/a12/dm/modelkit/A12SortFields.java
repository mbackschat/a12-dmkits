package io.github.mbackschat.a12.dm.modelkit;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mbackschat.a12.dm.core.DmKeys;
import io.github.mbackschat.a12.dm.predicates.A12SortOrderBinding;
import io.github.mbackschat.a12.dm.predicates.SortOrderBindingPredicatesKt;

/**
 * A group's stored {@code sortFields}, read as A12's reader binds them: the order by the shared law in
 * {@code :predicates}, the name as text with a scalar coerced. Kernel 31.3.1 refuses an entry with either member
 * missing or {@code null}, an entry that is no object, and an order outside its binding (KERNEL-FINDINGS KF680), so
 * this refuses them too rather than inventing an order or a name.
 */
final class A12SortFields {

    private A12SortFields() {}

    /** @throws IllegalArgumentException naming the entry member A12's reader refuses */
    static List<ElementDraft.SortField> read(JsonNode sortFields) {
        List<ElementDraft.SortField> entries = new ArrayList<>();
        int position = 0;
        for (JsonNode entry : sortFields) {
            String at = DmKeys.SORT_FIELDS + "[" + position++ + "]";
            if (!entry.isObject()) {
                throw refused(at, "is not an object");
            }
            JsonNode name = entry.path(DmKeys.NAME);
            if (!name.isValueNode() || name.isNull()) {
                throw refused(at + "." + DmKeys.NAME, "must be text");
            }
            entries.add(new ElementDraft.SortField(name.asText(), order(entry.path(DmKeys.ORDER), at)));
        }
        return entries;
    }

    private static SortOrder order(JsonNode order, String at) {
        A12SortOrderBinding binding = order.isTextual() ? SortOrderBindingPredicatesKt.a12SortOrderOfString(order.textValue())
                : order.isNumber() ? SortOrderBindingPredicatesKt.a12SortOrderOfNumber(order.toString())
                : A12SortOrderBinding.UNREADABLE;
        return switch (binding) {
            case ASC -> SortOrder.ASC;
            case DESC -> SortOrder.DESC;
            case UNREADABLE -> throw refused(at + "." + DmKeys.ORDER, "must be ASC or DESC");
        };
    }

    private static IllegalArgumentException refused(String member, String requirement) {
        return new IllegalArgumentException("`" + member + "` " + requirement + "; A12's reader refuses this model");
    }
}
