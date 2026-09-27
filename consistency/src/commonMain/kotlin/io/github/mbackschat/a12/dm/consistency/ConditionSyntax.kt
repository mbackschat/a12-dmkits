package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionRecognizer
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxFailureKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Authored intervals and generated wrappers retain the member that owns their source position. */
internal class ConditionText {
    data class Part(val member: String?, val start: Int, val end: Int, val authored: Boolean)
    private val buffer = StringBuilder()
    val text: String get() = buffer.toString()
    val parts = mutableListOf<Part>()

    fun append(text: String, member: String? = null, authored: Boolean = false) {
        val start = buffer.length
        buffer.append(text)
        parts += Part(member, start, buffer.length, authored)
    }

    fun at(offset: Int): Part? = parts.firstOrNull { offset >= it.start && offset < it.end }
}

/** KF471's measured joined text; origin intervals are the checker's member-attribution contract. */
internal fun computationCondition(body: JsonObject, target: String, language: ConditionLanguage): ConditionText? {
    val alternatives = body["computationAlternatives"] as? JsonArray ?: return null
    if (alternatives.isEmpty() || alternatives.any { it !is JsonObject }) return null
    val words = CONDITION_OPERATOR_KEYWORDS.getValue(language)
    val out = ConditionText()
    val and = words.getValue("And")
    val or = words.getValue("Or")
    fun text(objectValue: JsonObject, key: String): String? = (objectValue[key] as? JsonPrimitive)?.contentOrNull
    fun precondition(member: String, value: String?) {
        if (value == null) return
        out.append("(", member)
        out.append(value, member, authored = true)
        out.append("\n) $and ", member)
    }
    val suppressions = body["errorCodesToSuppress"] as? JsonArray
    if (!suppressions.isNullOrEmpty()) {
        val keyword = if (language == ConditionLanguage.EN) "SuppressWarning" else "UnterdrueckeWarnung"
        out.append("@$keyword(${suppressions.joinToString(",") { (it as? JsonPrimitive)?.contentOrNull ?: "null" }})\n")
    }
    out.append("${words.getValue("FieldFilled")}($target) $and ")
    precondition("commonPrecondition", text(body, "commonPrecondition"))
    val grouped = alternatives.size > 1
    if (grouped) out.append("(\n")
    alternatives.forEachIndexed { index, value ->
        val alternative = value as JsonObject
        val prefix = "computationAlternatives[$index]"
        val preconditionText = text(alternative, "precondition")
        if (index > 0) out.append(" $or\n")
        if (grouped) out.append("(", "$prefix.${if (preconditionText == null) "operation" else "precondition"}")
        precondition("$prefix.precondition", preconditionText)
        val operator = alternative.loweredComparisonOperator(language) ?: return null
        out.append("[$target] $operator ", "$prefix.operation")
        out.append(text(alternative, "operation").orEmpty(), "$prefix.operation", authored = true)
        if (grouped) out.append("\n)", "$prefix.operation")
    }
    if (grouped) out.append("\n)")
    return out
}

/** Exactly one recognizer chooses the syntax code and the selected family that owns it. */
internal fun TrackedFactBase.conditionSyntaxFindings(element: TrackedDeclaration, family: CheckFamily): List<Finding> {
    val body = element.body ?: return emptyList()
    val carrier = if (element.kind == "Computation") {
        val target = computedTargetOf(element) ?: return emptyList()
        val group = element.path.substringBeforeLast('/')
        computationCondition(body, target.removePrefix("$group/"), conditionLanguage) ?: return emptyList()
    } else {
        val condition = conditionTexts(element.kind, body).singleOrNull() ?: return emptyList()
        ConditionText().apply { append(condition.second, condition.first, authored = true) }
    }
    val recognition = ConditionRecognizer(carrier.text, conditionLanguage).recognize(SyntaxEntry.CONDITION)
    val failure = recognition.failure ?: return emptyList()
    val at = carrier.at(failure.at)
    val origin = if (at?.authored == true) at else if (failure.found.isEmpty()) {
        recognition.unfinishedAt?.let(carrier::at)?.takeIf { it.member != null }
            ?: carrier.parts.lastOrNull { it.authored }
    } else at
    val member = origin?.member ?: return emptyList()
    val authored = carrier.parts.single { it.authored && it.member == member }
    val text = carrier.text.substring(authored.start, authored.end)
    // Declaration-presence families retain ownership of blank rule conditions and operations (proposal §5).
    if (failure.kind != SyntaxFailureKind.LEXICAL && text.isBlank() &&
        (member == "errorCondition" || member.endsWith("operation"))) return emptyList()
    val generated = at?.authored != true
    val owner = when (failure.kind) {
        SyntaxFailureKind.LEXICAL -> CheckFamily.CONDITION_LEXICALITY
        SyntaxFailureKind.MATCH -> CheckFamily.CONDITION_GRAMMAR
        SyntaxFailureKind.DECISION -> if (failure.found.isEmpty() || generated)
            CheckFamily.CONDITION_COMPLETENESS else CheckFamily.CONDITION_GRAMMAR
    }
    if (owner != family) return emptyList()
    val start = if (generated) text.length else (failure.at - authored.start).coerceIn(0, text.length)
    val end = if (generated) start else (failure.end - authored.start).coerceIn(start, text.length)
    val context = if (failure.found.isEmpty() || generated) "ends after `${text.trimEnd().takeLast(40)}`"
        else "carries `${text.substring(start, end)}`"
    val explanation = if (failure.kind == SyntaxFailureKind.LEXICAL) {
        if (failure.found.firstOrNull() in listOf('\'', '"'))
            "`$member` opens a quote at offset $start that is never closed or cannot form a quoted name"
        else "`$member` holds `${failure.found}` at offset $start, a character A12's condition lexer cannot read"
    } else "`$member` $context where A12's parser cannot complete the text"
    return listOf(Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, failure.code,
        explanation, element.path, TextSpan(member, start, end)))
}
