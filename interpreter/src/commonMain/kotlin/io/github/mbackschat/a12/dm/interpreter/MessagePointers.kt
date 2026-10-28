package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.EvalContext
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
