package io.github.mbackschat.a12.dm.consistency

/**
 * The classes A12's `IFieldType.matchBaseType` sorts a declaration into, for every gate that compares two
 * declarations' kinds: the two index fields of a parallel iteration (`CheckEntityUtils`, drawing
 * `MVK_ERROR_FIELD_INVALID_INDEX_TYPE`), a semantic index's key against its group's index field
 * (`CheckSemanticIndexImpl`, drawing `MVK_INDEX_TYPE_INCONSISTENT`), the comparison arms, and the value-list
 * quantifiers' base-type gate.
 *
 * Each type interface admits only itself, with two joins. `IStringType` and `IPredefinedType` admit each other,
 * so text and custom share a class. The model mapper builds a date, a time, a date-time and a date fragment as
 * one `DateTypeImpl`, so those four share one. A date range is its own `DateRangeTypeImpl`, and so its own class.
 *
 * Measured on the index carriers ([KERNEL-FINDINGS KF353], `rulekit.validate.laws.IndexFieldBaseTypeLawsTest`
 * and `…SemanticIndexKeyGateLawsTest`) and on the comparison and value-list carriers ([KF468],
 * `…TemporalAndCustomBaseTypeLawsTest`), where the checker had kept its own copy of this table that split the
 * date fragment out and left time, date-time and custom unclassified. A type definition takes the class of the type it defines only where the parallel iteration
 * compares its two index fields ([KERNEL-FINDINGS KF602]); every other gate reading this stays silent on it.
 */
internal enum class BaseTypeClass { TEXT, ENUMERATION, NUMBER, DATE, DATE_RANGE, YES_NO, YES }

internal val FieldType.baseTypeClass: BaseTypeClass?
    get() = when (this) {
        FieldType.STRING, FieldType.CUSTOM_FIELD -> BaseTypeClass.TEXT
        FieldType.ENUMERATION -> BaseTypeClass.ENUMERATION
        FieldType.NUMBER -> BaseTypeClass.NUMBER
        FieldType.DATE, FieldType.TIME, FieldType.DATE_TIME, FieldType.DATE_FRAGMENT -> BaseTypeClass.DATE
        FieldType.DATE_RANGE -> BaseTypeClass.DATE_RANGE
        FieldType.BOOLEAN -> BaseTypeClass.YES_NO
        FieldType.CONFIRM -> BaseTypeClass.YES
        FieldType.TYPE_DEF -> null
    }
