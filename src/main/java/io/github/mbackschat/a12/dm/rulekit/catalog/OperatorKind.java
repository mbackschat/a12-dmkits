package io.github.mbackschat.a12.dm.rulekit.catalog;

import java.util.Locale;

import com.fasterxml.jackson.annotation.JsonCreator;

/**
 * The family a catalog entry belongs to (proposal §9). JSON uses the lower-case / hyphenated form
 * ({@code "operator"}, {@code "path-op"}, …); {@link #fromJson(String)} maps it to the constant.
 */
public enum OperatorKind {
    PREDICATE,
    OPERATOR,
    FUNCTION,
    AGGREGATE,
    PATH_OP,
    /** Keyword constants with localized spellings, e.g. {@code True}/{@code Wahr}. */
    CONSTANT,
    STRUCTURAL;

    @JsonCreator
    public static OperatorKind fromJson(String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
