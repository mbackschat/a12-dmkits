package io.github.mbackschat.a12.dm.consistency

/**
 * The classes A12's `IFieldType.matchBaseType` sorts an index field into, for the two gates that compare one
 * with another: the two index fields of a parallel iteration (`CheckEntityUtils`, drawing
 * `MVK_ERROR_FIELD_INVALID_INDEX_TYPE`) and a semantic index's key against its group's index field
 * (`CheckSemanticIndexImpl`, drawing `MVK_INDEX_TYPE_INCONSISTENT`). Both call the one method, so one table
 * serves both.
 *
 * Each type interface admits only itself, with two joins. `IStringType` and `IPredefinedType` admit each other,
 * so text and custom share a class. The model mapper builds a date, a time, a date-time and a date fragment as
 * one `DateTypeImpl`, so those four share one. A date range is its own `DateRangeTypeImpl`, and so its own class.
 * Deliberately not [BaseTypeClass], which splits the temporal kinds for the comparisons that do.
 *
 * Measured on both carriers ([KERNEL-FINDINGS KF353], `rulekit.validate.laws.IndexFieldBaseTypeLawsTest` and
 * `…SemanticIndexKeyGateLawsTest`): every class against text from both sides, the temporal joins, a date range
 * against a date both ways, and the two confirm kinds apart. A type definition or an unspecified type has no
 * class this reader can know, so both gates stay silent on it.
 */
internal enum class IndexKeyClass { TEXT, ENUMERATION, NUMBER, TEMPORAL, DATE_RANGE, BOOLEAN, CONFIRM }

internal val FieldType.indexKeyClass: IndexKeyClass?
    get() = when (this) {
        FieldType.STRING, FieldType.CUSTOM_FIELD -> IndexKeyClass.TEXT
        FieldType.ENUMERATION -> IndexKeyClass.ENUMERATION
        FieldType.NUMBER -> IndexKeyClass.NUMBER
        FieldType.DATE, FieldType.TIME, FieldType.DATE_TIME, FieldType.DATE_FRAGMENT -> IndexKeyClass.TEMPORAL
        FieldType.DATE_RANGE -> IndexKeyClass.DATE_RANGE
        FieldType.BOOLEAN -> IndexKeyClass.BOOLEAN
        FieldType.CONFIRM -> IndexKeyClass.CONFIRM
        FieldType.TYPE_DEF, FieldType.UNSPECIFIED -> null
    }
