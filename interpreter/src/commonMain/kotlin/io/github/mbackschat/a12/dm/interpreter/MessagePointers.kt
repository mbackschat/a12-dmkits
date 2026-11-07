package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
import io.github.mbackschat.a12.dm.interpreter.eval.epochDay
import io.github.mbackschat.a12.dm.interpreter.eval.completeFragmentToIso
import io.github.mbackschat.a12.dm.interpreter.eval.parseDeclaredDateTime
import io.github.mbackschat.a12.dm.interpreter.eval.startOfDayMillis
import io.github.mbackschat.a12.dm.interpreter.eval.zonedInstantOf
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef

/**
 * A prepared message operand projected into A12's partially-known pointer domain, at the firing row [ctx] of a
 * validation pass. The wildcard coordinates belong only to the rich message channel; iteration and suppression
 * deliberately continue to use the star-free reference view.
 */
internal class MessagePointers(private val model: ModelDef, private val paths: ModelPathIndex) {

    fun of(reference: MessageReference, pass: ValidationPass, ctx: EvalContext): A12PartiallyKnownMultiPointer =
        when (reference) {
            is MessageReference.Ordinary ->
                pass.doc.messagePointer(reference.path, pass.doc.messageRepetitions(reference.path, ctx))
            is MessageReference.Selected -> selected(reference, pass, ctx)
            is MessageReference.Wildcard -> {
                val prefixes = paths.groupsThrough(reference.path)
                val bindDepth = starredLevelDepth(reference.path, reference.bindDepth)
                val repetitions = pass.doc.messageRepetitions(reference.path, ctx).mapIndexed { level, repetition ->
                    if (level >= bindDepth && prefixes[level] in model.repeatable) A12PartiallyKnownMultiPointer.WILDCARD
                    else repetition
                }
                pass.doc.messagePointer(reference.path, repetitions)
            }
        }

    /**
     * A possibly-causing entity's pointer: [of], and at a row no key matched, that key as A12 keeps it on a cause
     * member — converted to the index field's basic type, absent for an empty key (KERNEL-FINDINGS KF683,
     * `adapter.laws.UnknownIndexKeyLawsTest`). The key is a selected reference's own key, or on a joined row the
     * value the join iterates, which the side without that row lacks.
     */
    fun causeOf(reference: MessageReference, pass: ValidationPass, ctx: EvalContext): A12PartiallyKnownMultiPointer {
        val pointer = of(reference, pass, ctx)
        if (A12PartiallyKnownMultiPointer.UNKNOWN !in pointer.repetitionIndexes) return pointer
        val (indexField, text) = when (reference) {
            is MessageReference.Selected -> {
                val text = when (val key = reference.key) {
                    is SelectedKey.Literal -> key.value
                    // A12 keeps a selection's key as stored, so `8.50` keeps the scale its evaluation value `8.5` drops.
                    is SelectedKey.Field -> ctx.messageDisplayValue(key.path)
                }?.takeIf { it.isNotEmpty() } ?: return pointer   // an empty field key leaves the member unkeyed
                checkNotNull(reference.indexField) { "a selected reference is expanded before it is resolved" } to text
            }
            // On a joined row, the level no row matched is the side's group, and its key is the joined value.
            is MessageReference.Ordinary, is MessageReference.Wildcard -> {
                val group = paths.groupsThrough(reference.path)[pointer.repetitionIndexes.indexOf(A12PartiallyKnownMultiPointer.UNKNOWN)]
                val indexField = model.indexFields.firstOrNull { parentGroup(it) == group } ?: return pointer
                indexField to (pass.doc.messageIndexValue(indexField, ctx) ?: return pointer)
            }
        }
        return keyOf(indexField, text)?.let(pointer::withUnknownIndexKey) ?: pointer
    }

    /**
     * [text] in [indexField]'s basic type, or null where A12 keeps no key: a text, enumeration or custom-typed key is
     * its text as written; a number its stored text; a temporal key the instant its value names in the model's zone.
     */
    private fun keyOf(indexField: String, text: String): UnknownIndexKey? = when (model.kinds[indexField]) {
        Kind.STRING -> UnknownIndexKey.Text(text)
        Kind.NUMBER -> A12DocumentJson.canonicalNumberOrNull(text)?.let(UnknownIndexKey::Decimal)
        // A date is completed as a stored date is — a missing month or day is the first, a missing year the base year
        // or 2000 — and taken at midnight.
        Kind.DATE -> model.dateFormatOf(indexField)
            ?.let { format -> completeFragmentToIso(text, format, model.baseYear ?: 2000, model.timeZone) }
            ?.let(::epochDay)?.let { day -> UnknownIndexKey.Instant(startOfDayMillis(day, model.timeZone)) }
        // A time is that time of 1970-01-01.
        Kind.TIME, Kind.DATE_TIME -> model.dateLikeFormatOf(indexField)
            ?.let { format -> parseDeclaredDateTime(text, format, model.timeZone) }
            ?.let { iso -> zonedInstantOf(if ('T' in iso) iso else "1970-01-01T$iso", model.timeZone) }
            ?.let { seconds -> UnknownIndexKey.Instant(seconds * 1_000L) }
        Kind.BOOLEAN, Kind.CONFIRM, Kind.DATE_RANGE, null -> null
    }

    /**
     * The coordinates of the row a selected reference's key selects, or null when the key is empty or no admitted
     * row carries it.
     */
    fun selectedRepetitions(reference: MessageReference.Selected, pass: ValidationPass, ctx: EvalContext): List<Int>? {
        val key = when (val written = reference.key) {
            is SelectedKey.Literal -> written.value
            is SelectedKey.Field -> pass.checkedKeyValue(ctx, written.path)
        }?.takeIf { it.isNotEmpty() } ?: return null
        return pass.selectedMessageRepetitions(ctx, reference.path, checkNotNull(reference.indexField), key)
    }

    /**
     * A selected operand's pointer: the firing row's coordinates above the indexed group, the row the key selects
     * at that level — A12's unknown row when no admitted row carries the key, or the key is empty — and below it
     * the firing row's coordinate on every level the rule's iteration binds, and every row at a repeatable level it
     * does not (`adapter.laws.SelectedMessageReferenceDiffTest`, `adapter.laws.RowBoundSelectedReferenceDiffTest`).
     */
    private fun selected(reference: MessageReference.Selected, pass: ValidationPass, ctx: EvalContext) = run {
        val indexField = checkNotNull(reference.indexField) { "a selected reference is expanded before it is resolved" }
        val levels = paths.groupsThrough(reference.path)
        val indexLevel = levels.indexOf(parentGroup(indexField))
        val selected = selectedRepetitions(reference, pass, ctx)
        // The firing row's levels, short of the first one the read's own star reopens.
        val bound = minOf(ctx.boundLevelsOf(reference.path),
            reference.starBindDepth?.let { starredLevelDepth(reference.path, it) } ?: Int.MAX_VALUE)
        val repetitions = pass.doc.messageRepetitions(reference.path, ctx).mapIndexed { level, repetition ->
            when {
                level < indexLevel -> repetition
                level == indexLevel -> selected?.get(level) ?: A12PartiallyKnownMultiPointer.UNKNOWN
                level < bound -> repetition
                levels[level] in model.repeatable -> A12PartiallyKnownMultiPointer.WILDCARD
                else -> repetition
            }
        }
        pass.doc.messagePointer(reference.path, repetitions)
    }

    /** The levels of [path] above its first starred one, from a star's [Expr.Star.bindDepth]; −1, a hand-built
     *  node's default, is the leaf group's parent. */
    private fun starredLevelDepth(path: String, bindDepth: Int): Int {
        val prefixes = paths.groupsThrough(path)
        return if (bindDepth >= 0) minOf(bindDepth, prefixes.size)
        else prefixes.size - if (model.kinds[path] == null) 1 else 2
    }
}
