package io.github.mbackschat.a12.dm.cli.group;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.mbackschat.a12.dm.modelkit.ElementDraft;
import io.github.mbackschat.a12.dm.modelkit.SortOrder;

/**
 * Parses a {@code group modify --sort-field} value — {@code child} (ASC by default) or {@code child:asc} /
 * {@code child:desc} — into a typed {@link ElementDraft.SortField}. Shared by the standalone {@code group
 * modify} verb and the {@code apply} {@code group modify} op so the two can't drift (the parity discipline).
 */
public final class SortFieldArg {

    private SortFieldArg() {
    }

    /** Parse one {@code child[:asc|desc]} token; throws {@link IllegalArgumentException} on a bad order/empty name. */
    public static ElementDraft.SortField parse(String arg) {
        int colon = arg.indexOf(':');
        if (colon < 0) {
            return new ElementDraft.SortField(arg, SortOrder.ASC);
        }
        String name = arg.substring(0, colon);
        String dir = arg.substring(colon + 1).trim().toUpperCase(Locale.ROOT);
        if (name.isEmpty()) {
            throw new IllegalArgumentException("sort-field needs a field name before ':' (got '" + arg + "')");
        }
        return switch (dir) {
            case "ASC" -> new ElementDraft.SortField(name, SortOrder.ASC);
            case "DESC" -> new ElementDraft.SortField(name, SortOrder.DESC);
            default -> throw new IllegalArgumentException(
                    "sort-field order must be asc or desc (got '" + arg.substring(colon + 1) + "' in '" + arg + "')");
        };
    }

    /** Parse every {@code child[:asc|desc]} token, preserving order (the multi-key sort). */
    public static List<ElementDraft.SortField> parseAll(List<String> args) {
        List<ElementDraft.SortField> out = new ArrayList<>();
        for (String a : args) {
            out.add(parse(a));
        }
        return out;
    }
}
