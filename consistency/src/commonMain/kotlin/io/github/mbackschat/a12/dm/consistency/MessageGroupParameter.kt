package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
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
 * **Silent on a malformed spelling**, which A12 answers LEXICALLY before either resolution gate and on three
 * distinct classes (`MVK_LEXER_STANDARD_ERROR`, `MVK_INCOMPLETE_INPUT`, `MVK_UNEXPECTED_TOKEN`) — a
 * disclosed under-report, since naming a resolution class there would name one A12 never reached.
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
            ?: starredIterationDefect(argument, ruleGroup, roots, element)
        defect?.let { code -> finding(code, argument, element) }
    }
}

private const val GROUP_MARKER = "#"

/** The near shorthand, per language — the other language's word is an ordinary group name here. */
private val RULE_GROUP_SPELLING = mapOf(ConditionLanguage.EN to "RuleGroup", ConditionLanguage.DE to "RegelKontext")

private val ROOT_GROUP_SPELLING = mapOf(ConditionLanguage.EN to "RootGroup", ConditionLanguage.DE to "LfdNr")

/** Recognised by the message lexer and refused on the modern route, rather than looked up as a name. */
private val RETIRED_TERMINALS = setOf("Zeile", "Usb")

/**
 * The class this argument draws, or null where it is admitted or is a shape this family may not judge.
 *
 * The gates run in A12's order: the retired terminals and the two shorthands are matched on the RAW text
 * first, then an absolute path's root segment, then the group's availability.
 */
private fun TrackedFactBase.groupArgumentDefect(
    argument: String,
    ruleGroup: String,
    roots: Set<String>,
    element: TrackedDeclaration,
): String? {
    val language = conditionLanguage
    if (argument in RETIRED_TERMINALS) {
        return RETIRED
    }
    if (argument == RULE_GROUP_SPELLING[language]) {
        // The refusal is of the WORD at this locus, not of the group it names: the same group is admitted
        // both as `RootGroup` and by its absolute path from the very same rule (measured).
        return if (ruleGroup.isRootGroup()) IMMEDIATELY_BELOW_ROOT else null
    }
    if (argument == ROOT_GROUP_SPELLING[language]) {
        return null
    }
    val segments = argument.removePrefix("/").split('/')
    if (segments.any { it.isMalformed() }) {
        return null
    }
    if (argument.startsWith('/') && "/${segments.first().trim('\'')}" !in roots) {
        return ROOT_GROUP
    }
    val named = namedGroup(argument, ruleGroup, roots, element) ?: return GROUP
    if (ruleGroup == named || ruleGroup.startsWith("$named/")) return null
    // The condition's footprint, not just the declaring group's ancestors, admits a group parameter
    // (MessageGroupFootprintAgreementTest's relative/absolute descendant and unread-sibling controls).
    return if (entitiesReadBy(element).reachesGroup(named)) null else GROUP
}

/**
 * Once the group is available, the condition must ride it without a repetition wildcard.
 *
 * A12 asks `refEntities.isNotReferencedWithoutWildcard` of the group the parameter resolved to, so the law
 * belongs to the POSITION and not to the shorthand its comment names — measured on both spellings of one
 * group, which draw the same class, and against the root shorthand on the same condition, which is admitted
 * because every reference into the rule's own group rides the root unstarred
 * (`rulekit.validate.laws.MessageGroupParameterIterationLawsTest`).
 *
 * **Reached only where the gates before it ADMITTED the argument**, which is why the named group is resolved
 * a second time here rather than carried out of [groupArgumentDefect]: a malformed segment is declined
 * rather than admitted, and a declined argument must stay silent through this gate too.
 */
private fun TrackedFactBase.starredIterationDefect(
    argument: String,
    ruleGroup: String,
    roots: Set<String>,
    element: TrackedDeclaration,
): String? {
    val named = namedGroup(argument, ruleGroup, roots, element) ?: return null
    return if (conditionRidesUnstarred(named, ruleGroup, element)) null else STARRED_ITERATION
}

/** The group an ADMITTED argument names, or null for one this family declined to judge. */
private fun TrackedFactBase.namedGroup(
    argument: String,
    ruleGroup: String,
    roots: Set<String>,
    element: TrackedDeclaration,
): String? = when {
    argument == RULE_GROUP_SPELLING[conditionLanguage] -> ruleGroup
    argument == ROOT_GROUP_SPELLING[conditionLanguage] -> roots.firstOrNull { ruleGroup == it || ruleGroup.startsWith("$it/") }
    else -> {
        if (argument.removePrefix("/").split('/').any { it.isMalformed() }) null
        else {
            val path = argument.split('/').joinToString("/") { it.trim('\'') }
            val resolved = (resolveEntity(ruleGroup, path, element.origin) as? Reference.Resolved)?.path
            resolved?.takeIf { declarationAt(it)?.declaredKind == DeclaredKind.GROUP }
        }
    }
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

/** A segment A12 refuses while LEXING — an empty one, or one whose quoting does not balance. */
private fun String.isMalformed(): Boolean =
    isEmpty() || trim('\'').isEmpty() || (count { it == '\'' } % 2 != 0)

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
        STARRED_ITERATION ->
            "names a group this declaration's condition reads only through a repetition wildcard — the " +
                "parameter pins the message to ONE row, so give the condition an unstarred read of that level"
        else -> "does not name an available group — use the rule's own group, one of its ancestors, " +
            "or a group reached by its condition"
    },
    element.path,
)
