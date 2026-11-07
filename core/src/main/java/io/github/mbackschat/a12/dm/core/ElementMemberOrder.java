package io.github.mbackschat.a12.dm.core;

import java.util.List;

/**
 * A12's member order for the nodes an edit appends members to, the one statement every kit that writes such a node
 * reorders by, so a model it writes re-serializes byte-stable ({@code adapter.ElementMemberOrderTest}). Only the
 * names cross the kit boundary; each kit reorders its own tree, since no Jackson type may.
 */
public final class ElementMemberOrder {

    private ElementMemberOrder() {
    }

    /**
     * The members a describable element opens with, every one before the element's body member
     * ({@code ElementSerializerUtil.serializeElementBase}, kernel 31.3.1).
     */
    public static final List<String> ELEMENT_HEAD = List.of(DmKeys.TYPE, DmKeys.ID, DmKeys.NAME, DmKeys.ANNOTATIONS,
            DmKeys.EXTERNAL_DESCRIPTION, DmKeys.INTERNAL_DESCRIPTION, DmKeys.CHANGE_NOTE);

    /**
     * A type definition's members, its descriptions the other way round, as A12's serializer re-emitted them
     * (measured 2026-11-07, {@code adapter.ElementMemberOrderTest}).
     */
    public static final List<String> TYPE_DEFINITION = List.of(DmKeys.ID, DmKeys.NAME, DmKeys.INTERNAL_DESCRIPTION,
            DmKeys.EXTERNAL_DESCRIPTION, DmKeys.ANNOTATIONS, DmKeys.FIELD_TYPE, "revision", DmKeys.CHANGE_NOTE);
}
