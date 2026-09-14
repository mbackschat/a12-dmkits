package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies

/**
 * The argument A12's `$index(…)$` message terminal takes: an index field on the ERROR ENTITY's own path.
 *
 * The terminal pins a message to the firing ROW's declared index-field value, so its argument has to name an
 * index field the error entity is actually reached through. `checkIndexFieldRef` collects
 * `AnalyseService.getIndexFields(errorEntity, ec)`, filters them by SHORT NAME, and answers
 * `INVALID_INDEX_FIELD` when nothing matches
 * ([KERNEL-FINDINGS KF198], `rulekit.validate.laws.RuleMessageIndexTerminalLawsTest`).
 *
 * **Five gates run in this terminal and the first TWO are reproduced here, which is what makes the family safe
 * rather than what limits it.** The argument gate is first, so nothing pre-empts it; ambiguity is second, so
 * reproducing the argument gate is the whole precondition for reporting it — `INVALID_INDEX_FIELD_AMBIGUOUS`
 * when the short name matches the index field of more than one group on the path, measured on a `/Policy`
 * shape whose `Claims` and `Items` both declare `Ref`. After those come a composed index, two repeatability
 * classes computed from the CONDITION's own references to the same index field, and finally the
 * missing-suffix class. Reporting any of THOSE would mean reproducing the repeatability machinery to know it
 * was reached — and the measured ordering proves the hazard is real: an unknown name with NO suffix draws the
 * argument class and not `INVALID_INDEX_FIELD_PARAMETER`, because the last gate never runs. So the three
 * later classes are a disclosed under-report, not a judgement.
 *
 * **A non-repeatable indexed group still passes both gates** — its index field is on the path and matches
 * once, and A12 answers `INVALID_INDEX_FIELD_NONREP_ENUM` from the repeatability gate instead. That is
 * silence here for the reason the gate order gives: the argument matched, exactly once.
 */
fun PreparedModel.errorTextIndexTerminalFindings(): List<Finding> {
    val targets = declaredReferences()
        .filter { it.role == ReferenceRole.ERROR_TARGET }
        .associate { it.declaredAt to (it.target as? Reference.Resolved)?.path }
    val unsound = declarationsWithUnsoundDsl()
    return elements.flatMap { element ->
        val body = element.body ?: return@flatMap emptyList()
        if (element.path in unsound) {
            return@flatMap emptyList()
        }
        val errorTarget = targets[element.path] ?: return@flatMap emptyList()
        val reachable = indexFieldNamesOnThePathTo(errorTarget)
        val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return@flatMap emptyList()
        messages.entries.flatMap { message ->
            if (message.text.carriesAnOffendingCharacter()) {
                return@flatMap emptyList()
            }
            messageParameterBodies(message.text).mapNotNull { parameter ->
                val argument = indexTerminalArgument(parameter) ?: return@mapNotNull null
                when (reachable.count { it == argument }) {
                    0 -> finding(argument, reachable.toSet(), element)
                    1 -> null
                    // The SECOND gate, and the argument gate is the only one before it — so reporting this
                    // class needs no more of the terminal's machinery than the first one did.
                    else -> ambiguousFinding(argument, element)
                }
            }
        }
    }
}

private const val INDEX_TERMINAL = "index("

/**
 * The text between `index(` and its closing parenthesis, or null when the parameter is not this terminal.
 *
 * The terminal is a LEXER token spelled exactly lowercase — `$Index(…)$` draws `MVK_UNEXPECTED_TOKEN` rather
 * than reaching any of these gates — so matching it literally is the whole recognition step.
 */
private fun indexTerminalArgument(parameter: String): String? {
    val trimmed = parameter.trim()
    if (!trimmed.startsWith(INDEX_TERMINAL)) {
        return null
    }
    val close = trimmed.indexOf(')', INDEX_TERMINAL.length)
    if (close < 0) {
        return null
    }
    return trimmed.substring(INDEX_TERMINAL.length, close).trim().takeIf { it.isNotEmpty() }
}

/**
 * The SHORT names of every index field declared by a group on the path to [fieldPath].
 *
 * Every ancestor group counts, repeatable or not: a non-repeatable indexed group's field is on the path and
 * passes this gate, which is why the class it eventually draws belongs to the repeatability gate instead.
 */
private fun PreparedModel.indexFieldNamesOnThePathTo(fieldPath: String): List<String> {
    val names = mutableListOf<String>()
    var group = fieldPath.substringBeforeLast('/', missingDelimiterValue = "")
    while (group.isNotEmpty()) {
        elements.firstOrNull { it.path == group }
            ?.let { indexFieldNameOf(it) }
            ?.let { names += it }
        group = group.substringBeforeLast('/', missingDelimiterValue = "")
    }
    return names
}

private fun ambiguousFinding(argument: String, element: DeclaredElement): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "INVALID_INDEX_FIELD_AMBIGUOUS",
    "in `errorMessage`: `index($argument)` matches the index field of more than one group on the error " +
        "entity's path — the argument is matched by SHORT NAME, so A12 cannot say which repetition to pin " +
        "the message to",
    element.path,
)

private fun finding(argument: String, reachable: Set<String>, element: DeclaredElement): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "INVALID_INDEX_FIELD",
    "in `errorMessage`: `index($argument)` names no index field on the error entity's own path — " +
        if (reachable.isEmpty()) {
            "no group on that path declares one, so the terminal has nothing to pin the message to"
        } else {
            "that path offers ${reachable.sorted().joinToString { "`$it`" }}, and the argument is matched " +
                "by SHORT NAME rather than by path"
        },
    element.path,
)
