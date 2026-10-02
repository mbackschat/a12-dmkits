package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.MessageParameter
import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion
import io.github.mbackschat.a12.dm.consistency.syntax.semanticIndexUsesIn
import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.SEMANTIC_INDEX

/**
 * What an error message's `$#…$` group position may name: an ancestor or a group reached by the condition.
 *
 * This is a different position from the parameter NAME one and resolves the same words differently — there
 * `RootGroup` draws `INVALID_ENTITY` and `RuleGroup` draws `INVALID_PARAMETER`, here both are admitted
 * shorthands ([KERNEL-FINDINGS KF198], `rulekit.validate.laws.RuleMessageGroupParameterLawsTest`).
 * A referenced descendant is admitted too, relatively or absolutely. The earlier ancestor-only grid kept
 * every condition read inside the rule's own group (KF214, corrected by MessageGroupFootprintAgreementTest).
 *
 * | the `$#…$` argument | class |
 * |---|---|
 * | an absolute path whose FIRST segment names no declared root | `INVALID_ROOT_GROUP` |
 * | a group unavailable to the declaration | `INVALID_GROUP` |
 * | `Zeile` or `Usb`, the retired terminals | `PARAM_INVALID_IN_NEW_WORLD` |
 * | `RuleGroup`, where the rule sits IN a root group | `RULEGROUP_IMMEDIATELY_BELOW_ROOT` |
 *
 * An unresolved group under a declared root draws `INVALID_GROUP`, like an unreferenced sibling. That
 * shared diagnostic does not make existence and reference membership the same gate (KF214).
 *
 * Quoting makes a keyword an ordinary name. Unquoted `RootGroup`, `Zeile` and `Usb` are also names
 * before a path separator; the next token decides their grammar alternative. `RuleGroup` is reserved
 * in name positions. MessageGroupEventAgreementTest distinguishes each reading on both languages,
 * including a real group whose name collides with the root keyword (KF214).
 *
 * **Two gates sit behind the wildcard one and are NOT reported, with the reason beside them.** A group that
 * is a PARALLEL ITERATION, or a parameter carrying a semantic index, hands the decision to
 * `RuleErrorTextIterationParamVisitor` walking the PARSED condition — so `INVALID_USE_OF_ITERATION_PARAM`
 * and `INVALID_USE_OF_SEMANTIC_INDEX` need that visitor's acceptance reproduced, not another reading of the
 * model (source-read 30.8.1: `checkSemanticIndexAndParallelIteration` returns true immediately for every
 * other shape, which is why the two gates are invisible on an ordinary parameter).
 *
 * **`RootGroup` is chain-relative**, naming the root of the rule's OWN ancestor chain rather than the model's
 * first declared root — measured from a rule under a second root, where the first root's name is refused.
 *
 * **The shorthands are BILINGUAL and each language admits ONLY ITS OWN spelling**, which is measured in both
 * directions on one model pair (`rulekit.validate.laws.RuleMessageParameterLanguageLawsTest`): `RuleGroup`
 * and `RootGroup` are admitted on an `en_US`-condition model and draw `INVALID_GROUP` on a `de_DE` one,
 * while `RegelKontext` and `LfdNr` are admitted on the German model and refused on the English one — the
 * position looks the other language's word up as a group NAME. So the set is keyed on the model's own
 * language rather than unioned, which an earlier draft did on the reasoning that a wider exclusion can only
 * under-report. It can, and it would also have been the wrong law: the message lexer's file spells the
 * GERMAN terminal (`RULE_GROUP: 'RegelKontext'`) while a running `en_US` model takes `RuleGroup`, so a
 * grammar read alone gets the pair backwards — the one-layer trap [KF412] records.
 *
 * The message grammar reports faults in the order it reaches them: a parent-walk check can precede
 * a child syntax fault, and the completed argument's lookup and availability precede a trailing token.
 * A later lexer fault cannot override an earlier parser or semantic refusal (KF214). Keyed group syntax
 * is measured by MessageParameterDecoderAgreementTest; membership/iteration on a keyed condition and
 * the broader dotted-name grammar remain outside this evidence. The dotted-name reader
 * declines unquoted reserved suffixes; MessageGroupDottedBoundaryAgreementTest locks that boundary.
 *
 * **Rules only**, every measured row being a rule's message. The computation carrier was reported to us for
 * the `RuleGroup` locus by a12-kernel-lean and is not measured here.
 */
internal fun TrackedFactBase.completedGroupDefect(
    parameter: MessageParameter,
    ruleGroup: String,
    roots: Set<String>,
    element: TrackedDeclaration,
): MessageCompletion {
    val named = when (parameter) {
        MessageParameter.Line, MessageParameter.User -> return MessageCompletion.Refused(RETIRED)
        MessageParameter.RootRow -> roots.firstOrNull { ruleGroup == it || ruleGroup.startsWith("$it/") } ?: return MessageCompletion.Declined
        is MessageParameter.Group -> {
            if (parameter.entity.ruleGroup) {
                if (ruleGroup.isRootGroup()) return MessageCompletion.Refused(IMMEDIATELY_BELOW_ROOT)
                ruleGroup
            } else (resolveMessageEntity(ruleGroup, parameter.entity.written.replace("'", ""), element.origin)
                as? Reference.Resolved)?.path ?: return MessageCompletion.Declined
        }
        else -> return MessageCompletion.Declined
    }
    if (declarationAt(named)?.declaredKind != DeclaredKind.GROUP) return MessageCompletion.Refused(GROUP)
    val key = (parameter as? MessageParameter.Group)?.key
    if (key != null) {
        if (key.entity?.ruleGroup == true) return MessageCompletion.Refused("MVK_INTERNAL_ERROR")
        // KF501's keyed-group witnesses carry no keyed condition reads. A keyed condition needs the
        // separate iteration visitor, which this bounded group gate deliberately leaves unjudged.
        val keyword = CONDITION_OPERATOR_KEYWORDS[conditionLanguage]?.get(SEMANTIC_INDEX) ?: return MessageCompletion.Declined
        val keyedReads = element.body?.let { conditionTexts(element.kind, it) }.orEmpty()
            .any { (_, text) -> semanticIndexUsesIn(text, conditionLanguage, keyword).isNotEmpty() }
        return if (keyedReads) MessageCompletion.Declined else MessageCompletion.Refused(GROUP)
    }
    val available = ruleGroup == named || ruleGroup.startsWith("$named/") || entitiesReadBy(element).reachesGroup(named)
    if (!available) return MessageCompletion.Refused(GROUP)
    return if (conditionRidesUnstarred(named, ruleGroup, element)) MessageCompletion.Accepted
        else MessageCompletion.Refused(STARRED_ITERATION)
}

internal fun turningPointIsWrong(path: String, ruleGroup: String): Boolean {
    val groups = ruleGroup.split('/').filter { it.isNotEmpty() }.toMutableList()
    for (step in path.split('/').takeWhile { it.startsWith("..") }) {
        if (groups.size <= 1) return false
        groups.removeAt(groups.lastIndex)
        val label = step.removePrefix("..").trim('\'')
        if (label.isNotEmpty() && label != groups.last()) return true
    }
    return false
}

/**
 * Whether any of this declaration's condition references rides [group] with no wildcard at or above it.
 *
 * A star BELOW the group leaves the group itself ridden per row, which is the row that separates this from
 * "the condition carries no star at all": a rule in an inner group reading its own field, beside a starred
 * read of the outer group's field, is ADMITTED. The star's own level is found by resolving the reference's
 * prefix up to the starred segment, so relative spellings and `..` steps are handled by the resolver rather
 * than by arithmetic here — and a prefix that does not resolve counts as a ride, which under-reports.
 */
private fun TrackedFactBase.conditionRidesUnstarred(
    group: String,
    ruleGroup: String,
    element: TrackedDeclaration,
): Boolean {
    if (element.body == null) return true
    fun resolves(written: String): String? =
        (resolveEntity(ruleGroup, written, element.origin) as? Reference.Resolved)?.path
    return messageConditionReferences(element).any { written ->
        val resolved = resolves(written.replace("*", "")) ?: return@any false
        if (messageReferenceFootprint(resolved).none { it == group || it.startsWith("$group/") }) {
            return@any false
        }
        if ('*' !in written) return@any true
        val segments = written.split('/')
        val starred = segments.indexOfFirst { '*' in it }
        val prefix = segments.take(starred + 1).joinToString("/").replace("*", "")
        val starLevel = resolves(prefix) ?: return@any true
        starLevel.startsWith("$group/")
    }
}

private const val GROUP = "INVALID_GROUP"

private const val RETIRED = "PARAM_INVALID_IN_NEW_WORLD"

private const val IMMEDIATELY_BELOW_ROOT = "RULEGROUP_IMMEDIATELY_BELOW_ROOT"

private const val STARRED_ITERATION = "ITER_PARAM_WITH_STARREFERENCE"
