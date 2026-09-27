package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxSite
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.isRuleContext
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

internal data class PathFinding(val family: CheckFamily, val contribution: ConditionFinding)

/** KF356 and R5-PE measure resolution, suffix and wildcard gates in that order on each segment. */
internal fun TrackedFactBase.pathContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<PathFinding> {
    if (execution == null) return emptyList()
    val tokens = tokenize(execution.carrier.text)
    val group = element.path.substringBeforeLast('/')
    val checked = mutableMapOf<Int, Int>()
    return buildList {
        for (site in execution.recognition.sites.filterIsInstance<SyntaxSite.Completion>()) {
            if (site.kind != SyntaxSite.Kind.PATH_STEP) continue
            val run = tokens.filter { it.start >= site.start && it.end <= site.end }
                .dropWhile { it.text == "$" }
            val segments = normalizedPath(run).split('/')
            val fullEnd = execution.recognition.completedSteps.getValue(site.start)
            val written = normalizedPath(tokens.filter { it.start >= run.first().start && it.end <= fullEnd })
            val span = execution.carrier.semanticSpan(run.first().start, fullEnd) ?: continue
            fun report(family: CheckFamily, gate: PathCheck, finding: Finding) {
                add(PathFinding(family, ConditionFinding(element.key, PathCheckSite(site, gate), finding)))
            }
            val context = run.first().kind != TokenKind.QUOTED_NAME &&
                isRuleContext(segments.first().removeSuffix("*"), conditionLanguage)
            if (context) {
                if (segments.first().endsWith("*")) report(CheckFamily.REPETITION_WILDCARD_TARGET, PathCheck.WILDCARD,
                    pathError("MVK_INVALID_WILDCARD_FOR_RULEGROUP", element.path,
                        "this ${span.member} puts a repetition wildcard on the rule's own group — a `*` belongs only " +
                            "on a repeatable group, and the rule context is not one", span))
                continue
            }
            val reached = resolveEntity(group, normalizedPath(run), element.origin)
            val missing = reached.asFinding(ReferenceRole.CONDITION_ENTITY, element.path, span.member, span)
            if (missing != null) {
                report(CheckFamily.CONDITION_REFERENCES, PathCheck.ADDRESS, missing)
            }
            for (at in (checked[site.start] ?: 0) until segments.size) {
                val segment = segments[at]
                if (segment.isEmpty()) continue
                val prefix = segments.take(at + 1).joinToString("/")
                val target = resolveEntity(group, prefix, element.origin)
                if (!segment.endsWith("*")) continue
                val stepped = segment.removeSuffix("*")
                if (stepped.endsWith(".") && !stepped.startsWith(".")) {
                    report(CheckFamily.H_MODEL_PATH_SUFFIX, PathCheck.SUFFIX, pathError("MVK_POINT_IN_A12_MODEL", element.path,
                        "in `${span.member}`: `$written` carries the `.*` repetition suffix, " +
                            "which is H-model spelling — an A12 model stars the segment instead, `*`", span))
                }
                val offence = when {
                    stepped == ".." && segments.take(at).all { it == ".." } ->
                        "MVK_INVALID_WILDCARD_REL" to "a relative up-step"
                    stepped.isEmpty() || stepped.startsWith(".") -> null
                    target is Reference.Resolved && declarationAt(target.path)?.isRepeatable == false ->
                        "MVK_INVALID_WILDCARD" to "`$prefix`, which has no repetitions"
                    else -> null
                }
                if (offence != null) {
                    report(CheckFamily.REPETITION_WILDCARD_TARGET, PathCheck.WILDCARD, pathError(offence.first, element.path,
                        "this ${span.member} writes `$written` with a repetition wildcard on ${offence.second} — " +
                            "a `*` belongs only on a repeatable group", span))
                }
            }
            checked[site.start] = segments.size
        }
    }
}

private fun pathError(code: String, at: String, explain: String, span: TextSpan): Finding =
    Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, code, explain, at, span)
