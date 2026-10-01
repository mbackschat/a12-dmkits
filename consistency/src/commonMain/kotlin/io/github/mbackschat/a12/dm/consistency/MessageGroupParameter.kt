package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.MessageGroupArgument
import io.github.mbackschat.a12.dm.consistency.syntax.messageGroupArgument
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies

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
 * **The terminal match is on the RAW argument, before quotes are stripped.** `'RootGroup'` and `'RuleGroup'`
 * are looked up as ordinary group names and draw `INVALID_GROUP`, while `/'Policy'/'Cover'` reaches the same
 * group its bare spelling does — so quoting is transparent for a SEGMENT and not for a keyword. That is the
 * separator between the two tempting accounts of the escape, and both halves are measured.
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
 * **The measured argument layers run in order:** lexer faults, incomplete/separator faults, lookup, then
 * tokens left after an available path (MessageGroupArgumentAgreementTest). Parent walks and ambiguous
 * field short-name fallback preserve the message vocabulary rather than collapsing into `INVALID_GROUP`.
 * Unmeasured semantic-index forms and keyword/trailing-token combinations stay silent.
 *
 * **Rules only**, every measured row being a rule's message. The computation carrier was reported to us for
 * the `RuleGroup` locus by a12-kernel-lean and is not measured here.
 */
internal fun TrackedFactBase.messageGroupParameterFindings(): List<Finding> = declarations().flatMap { element ->
    messageContributions(element) { messageGroupParameterFindings(element, it) }.map { it.finding }
}

internal fun TrackedFactBase.messageGroupParameterFindings(element: TrackedDeclaration, message: AuthoredMessage): List<Finding> {
    if (element.declaredKind != DeclaredKind.RULE) return emptyList()

    // The model's ROOT GROUPS, so [TrackedFactBase.allDeclarations]: the walk below narrows to one rule,
    // and a scoped `declarations()` here would offer that rule's own path as the only root.
    val roots = allDeclarations().filter { it.path.count { c -> c == '/' } == 1 }.map { it.path }.toSet()
    val ruleGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    if (message.text.carriesAnOffendingCharacter()) {
        return emptyList()
    }
    return messageParameterBodies(message.text).mapNotNull { parameter ->
        val argument = parameter.trim().removePrefix(GROUP_MARKER).takeIf {
            parameter.trim().startsWith(GROUP_MARKER)
        } ?: return@mapNotNull null
        val defect = groupArgumentDefect(argument, ruleGroup, roots, element)
        defect?.let { code -> finding(code, argument, element) }
    }
}

private const val GROUP_MARKER = "#"

/** The near shorthand, per language — the other language's word is an ordinary group name here. */
private val RULE_GROUP_SPELLING = mapOf(ConditionLanguage.EN to "RuleGroup", ConditionLanguage.DE to "RegelKontext")

private val ROOT_GROUP_SPELLING = mapOf(ConditionLanguage.EN to "RootGroup", ConditionLanguage.DE to "LfdNr")

/** Recognised by the message lexer and refused on the modern route, rather than looked up as a name. */
private val RETIRED_TERMINALS = setOf("Zeile", "Usb")

/** The measured lexer, path, resolution and availability gates, followed by the wildcard gate (KF214). */
private fun TrackedFactBase.groupArgumentDefect(
    argument: String,
    ruleGroup: String,
    roots: Set<String>,
    element: TrackedDeclaration,
): String? {
    val language = conditionLanguage
    val parsed = when (val syntax = messageGroupArgument(argument) {
        it in RETIRED_TERMINALS || it == RULE_GROUP_SPELLING[language] || it == ROOT_GROUP_SPELLING[language]
    }) {
        is MessageGroupArgument.Refused -> return syntax.code
        MessageGroupArgument.Unmeasured -> return null
        is MessageGroupArgument.Path -> syntax
    }
    val path = parsed.written
    val named = when {
        path in RETIRED_TERMINALS -> return RETIRED
        path == RULE_GROUP_SPELLING[language] -> {
            if (ruleGroup.isRootGroup()) return IMMEDIATELY_BELOW_ROOT
            ruleGroup
        }
        path == ROOT_GROUP_SPELLING[language] -> roots.firstOrNull { ruleGroup == it || ruleGroup.startsWith("$it/") }
            ?: return null
        else -> {
            val unquoted = path.split('/').joinToString("/") { it.trim('\'') }
            // The generic resolver ignores turning-point labels (KF465); message checking validates
            // the label before looking up the child, including a missing child (KF214).
            if (turningPointIsWrong(unquoted, ruleGroup)) return "INVALID_ENTITY"
            when (val resolved = resolveEntity(ruleGroup, unquoted, element.origin)) {
                is Reference.Ambiguous -> return "ERROR_TEXT_FIELDNAME_NOT_UNIQUE"
                is Reference.AboveRoot -> return "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"
                is Reference.MisplacedUpStep -> return "MVK_UNEXPECTED_TOKEN"
                is Reference.Undeclared -> return if (resolved.rootUnresolved) ROOT_GROUP else GROUP
                is Reference.Resolved -> resolved.path
            }
        }
    }
    if (declarationAt(named)?.declaredKind != DeclaredKind.GROUP) return GROUP
    val available = ruleGroup == named || ruleGroup.startsWith("$named/") || entitiesReadBy(element).reachesGroup(named)
    // SPEC-2026-09-30-01 leaves availability versus a trailing token on an unread group unmeasured.
    if (!available) return if (parsed.trailing) null else GROUP
    if (parsed.trailing) return "MVK_UNEXPECTED_TOKEN"
    return if (conditionRidesUnstarred(named, ruleGroup, element)) null else STARRED_ITERATION
}

private fun turningPointIsWrong(path: String, ruleGroup: String): Boolean {
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

private const val ROOT_GROUP = "INVALID_ROOT_GROUP"

private const val RETIRED = "PARAM_INVALID_IN_NEW_WORLD"

private const val IMMEDIATELY_BELOW_ROOT = "RULEGROUP_IMMEDIATELY_BELOW_ROOT"

private const val STARRED_ITERATION = "ITER_PARAM_WITH_STARREFERENCE"

private fun finding(code: String, argument: String, element: TrackedDeclaration): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    code,
    "in `errorMessage`: the group parameter `${'$'}#$argument${'$'}` " + when (code) {
        RETIRED -> "names a terminal A12 recognises and no longer admits on this route"
        IMMEDIATELY_BELOW_ROOT ->
            "names the rule's own group with a shorthand A12 refuses where that group IS a root group — " +
                "name it by its absolute path, or use `RootGroup`"
        ROOT_GROUP -> "starts at a root group this model does not declare"
        "MVK_LEXER_STANDARD_ERROR" -> "contains a character or quoted name the message lexer cannot tokenize"
        "MVK_INCOMPLETE_INPUT" -> "ends before a complete group path — supply the name after the separator"
        "MVK_UNEXPECTED_TOKEN" -> "contains an unexpected separator or a token after the resolved path"
        "ERROR_TEXT_FIELDNAME_NOT_UNIQUE" -> "falls back to a field short name shared by more than one field — qualify the path"
        "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS" -> "walks above the rule's root group — remove an up-step"
        "INVALID_ENTITY" -> "uses a turning-point label that does not name the ancestor reached by the up-step"
        STARRED_ITERATION ->
            "names a group this declaration's condition reads only through a repetition wildcard — the " +
                "parameter pins the message to ONE row, so give the condition an unstarred read of that level"
        else -> "does not name an available group — use the rule's own group, one of its ancestors, " +
            "or a group reached by its condition"
    },
    element.path,
)
