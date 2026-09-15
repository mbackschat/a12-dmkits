package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import io.github.mbackschat.a12.dm.consistency.syntax.referencesIn

/**
 * What an error message's `$#…$` GROUP position may name: a group that CONTAINS the rule's own.
 *
 * This is a different position from the parameter NAME one and resolves the same words differently — there
 * `RootGroup` draws `INVALID_ENTITY` and `RuleGroup` draws `INVALID_PARAMETER`, here both are admitted
 * shorthands ([KERNEL-FINDINGS KF198], `rulekit.validate.laws.RuleMessageGroupParameterLawsTest`).
 * Containment runs the opposite way from most reach laws in this checker: the NAMED group must contain the
 * rule's, not the other way round.
 *
 * | the `$#…$` argument | class |
 * |---|---|
 * | an absolute path whose FIRST segment names no declared root | `INVALID_ROOT_GROUP` |
 * | anything else that does not contain the rule's group | `INVALID_GROUP` |
 * | `Zeile` or `Usb`, the retired terminals | `PARAM_INVALID_IN_NEW_WORLD` |
 * | `RuleGroup`, where the rule sits IN a root group | `RULEGROUP_IMMEDIATELY_BELOW_ROOT` |
 *
 * **Existence is not part of the law, which one measurement settled against a source read.** A source read
 * of `CheckIdNameImpl.checkAndCreateFullName` predicted `INVALID_ENTITY` for an absolute path under a
 * declared root that resolves to nothing; measured, it is `INVALID_GROUP`, because in this position
 * `resolveAndStoreEntity`'s group branch answers. So containment alone decides and this family never asks
 * whether the named group is there — a chain the source suggests and the kernel collapses.
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
fun PreparedModel.messageGroupParameterFindings(): List<Finding> {
    val unsound = declarationsWithUnsoundDsl()
    val roots = elements.filter { it.path.count { c -> c == '/' } == 1 }.map { it.path }.toSet()
    return elements.filter { it.kind == "Rule" }.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val ruleGroup = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.flatMap { message ->
            if (message.text.carriesAnOffendingCharacter()) {
                return@flatMap emptyList()
            }
            messageParameterBodies(message.text).mapNotNull { parameter ->
                val argument = parameter.trim().removePrefix(GROUP_MARKER).takeIf {
                    parameter.trim().startsWith(GROUP_MARKER)
                } ?: return@mapNotNull null
                val defect = groupArgumentDefect(argument, ruleGroup, roots, conditionLanguage)
                    ?: starredIterationDefect(argument, ruleGroup, roots, element)
                defect?.let { code -> finding(code, argument, element) }
            }
        }
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
 * first, then an absolute path's root segment, then containment.
 */
private fun groupArgumentDefect(
    argument: String,
    ruleGroup: String,
    roots: Set<String>,
    language: ConditionLanguage,
): String? {
    if (argument in RETIRED_TERMINALS) {
        return RETIRED
    }
    if (argument == RULE_GROUP_SPELLING[language]) {
        // The refusal is of the WORD at this locus, not of the group it names: the same group is admitted
        // both as `RootGroup` and by its absolute path from the very same rule (measured).
        return if (ruleGroup.count { it == '/' } == 1) IMMEDIATELY_BELOW_ROOT else null
    }
    if (argument == ROOT_GROUP_SPELLING[language]) {
        return null
    }
    if (!argument.startsWith('/')) {
        // A relative spelling of a real ancestor draws the containment class rather than the root one, so
        // the root gate is reached only by an absolute path (measured on `Cover`, a genuine ancestor).
        return GROUP
    }
    val segments = argument.removePrefix("/").split('/')
    if (segments.any { it.isMalformed() }) {
        return null
    }
    val named = "/" + segments.joinToString("/") { it.trim('\'') }
    if ("/${segments.first().trim('\'')}" !in roots) {
        return ROOT_GROUP
    }
    return if (ruleGroup == named || ruleGroup.startsWith("$named/")) null else GROUP
}

/**
 * The gate BEHIND containment: the condition must ride the named group without a repetition wildcard.
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
private fun PreparedModel.starredIterationDefect(
    argument: String,
    ruleGroup: String,
    roots: Set<String>,
    element: DeclaredElement,
): String? {
    val named = namedGroup(argument, ruleGroup, roots, conditionLanguage) ?: return null
    return if (conditionRidesUnstarred(named, ruleGroup, element)) null else STARRED_ITERATION
}

/** The group an ADMITTED argument names, or null for one this family declined to judge. */
private fun namedGroup(
    argument: String,
    ruleGroup: String,
    roots: Set<String>,
    language: ConditionLanguage,
): String? = when {
    argument == RULE_GROUP_SPELLING[language] -> ruleGroup
    argument == ROOT_GROUP_SPELLING[language] -> roots.firstOrNull { ruleGroup == it || ruleGroup.startsWith("$it/") }
    !argument.startsWith('/') -> null
    else -> {
        val segments = argument.removePrefix("/").split('/')
        if (segments.any { it.isMalformed() }) null else "/" + segments.joinToString("/") { it.trim('\'') }
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
private fun PreparedModel.conditionRidesUnstarred(
    group: String,
    ruleGroup: String,
    element: DeclaredElement,
): Boolean {
    val body = element.body ?: return true
    fun resolves(written: String): String? =
        (resolveEntity(ruleGroup, written, element.origin) as? Reference.Resolved)?.path
    return conditionTexts(element.kind, body).any { (_, text) ->
        referencesIn(text, conditionLanguage).any { written ->
            val resolved = resolves(written.replace("*", "")) ?: return@any false
            if (resolved != group && !resolved.startsWith("$group/")) {
                return@any false
            }
            if ('*' !in written) {
                return@any true
            }
            val segments = written.split('/')
            val starred = segments.indexOfFirst { '*' in it }
            val prefix = segments.take(starred + 1).joinToString("/").replace("*", "")
            val starLevel = resolves(prefix) ?: return@any true
            starLevel.startsWith("$group/")
        }
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

private fun finding(code: String, argument: String, element: DeclaredElement): Finding = Finding(
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
        else -> "names a group that does not CONTAIN this rule's group — the position takes the rule's own " +
            "group or one of its ancestors, spelled absolutely"
    },
    element.path,
)
