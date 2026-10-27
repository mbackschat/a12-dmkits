package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.MessageCompletion
import io.github.mbackschat.a12.dm.consistency.syntax.MessageParameter
import io.github.mbackschat.a12.dm.consistency.syntax.MessageParameterActions
import io.github.mbackschat.a12.dm.consistency.syntax.MessageParameterRead
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import io.github.mbackschat.a12.dm.consistency.syntax.readMessageParameter

/** Each parameter reports its first reached event; message order survives (KF343, KF501). */
internal fun TrackedFactBase.messageParameterFindings(element: TrackedDeclaration, message: AuthoredMessage): List<Finding> {
    if (message.text.carriesAnOffendingCharacter()) return emptyList()
    val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
    var completed: MessageParameter? = null
    val actions = object : MessageParameterActions {
        override fun climb(steps: Int): String? =
            if (steps >= group.count { it == '/' }) "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS" else null

        override fun parent(written: String): String? = when {
            turningPointIsWrong(written.replace("'", ""), group) -> "INVALID_ENTITY"
            resolveMessageEntity(group, written.replace("'", ""), element.origin) is Reference.AboveRoot ->
                "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"
            else -> null
        }

        override fun name(written: String, groupPosition: Boolean, indexPosition: Boolean): String? {
            if (indexPosition) return null
            return when (val resolved = resolveMessageEntity(group, written.replace("'", ""), element.origin)) {
                is Reference.Ambiguous -> "ERROR_TEXT_FIELDNAME_NOT_UNIQUE"
                is Reference.AboveRoot -> "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS"
                is Reference.MisplacedUpStep -> "MVK_UNEXPECTED_TOKEN"
                is Reference.Undeclared -> when {
                    resolved.rootUnresolved -> "INVALID_ROOT_GROUP"
                    groupPosition -> "INVALID_GROUP"
                    else -> "INVALID_ENTITY"
                }
                is Reference.Resolved -> null
                is Reference.IllegalRelPath -> error("only a declared target is resolved as a relative path")
            }
        }

        override fun completed(parameter: MessageParameter): MessageCompletion {
            completed = parameter
            return when (parameter) {
                is MessageParameter.Field -> completedFieldDefect(parameter, element)
                is MessageParameter.Index -> completedIndexDefect(parameter, element)
                MessageParameter.BaseYear -> messageBaseYearDefect()?.let { MessageCompletion.Refused(it) }
                    ?: MessageCompletion.Accepted
                else -> {
                    val roots = allDeclarations().filter { it.path.count { c -> c == '/' } == 1 }.map { it.path }.toSet()
                    completedGroupDefect(parameter, group, roots, element)
                }
            }
        }
    }
    var reportedBaseYear = false
    return messageParameterBodies(message.text).mapNotNull { body ->
        // Only an EMPTY body is the dollar escape: a space between the dollars is a parameter A12 parses and
        // refuses (KERNEL-FINDINGS KF310's blank rows, `CheckerUnicodeBlankAgreementTest`).
        if (body.isEmpty()) return@mapNotNull null
        completed = null
        val refusal = readMessageParameter(body, conditionLanguage, actions) as? MessageParameterRead.Refused
            ?: return@mapNotNull null
        // Group messages are measured on rule declarations (KF214); on a computation only the rule-group shorthand
        // in a root group is, and it draws the rule's class (KF563). Every other refusal there stays unreported.
        if (body.javaTrimmedStart().startsWith('#') && element.declaredKind != DeclaredKind.RULE &&
            refusal.code != "RULEGROUP_IMMEDIATELY_BELOW_ROOT") return@mapNotNull null
        // The owner composes NO_BASE_YEAR without a parameter argument, so repeated uses share one
        // diagnostic; the decoder's cross-family controls lock this existing message-family boundary.
        if (refusal.code == "NO_BASE_YEAR") {
            if (reportedBaseYear) return@mapNotNull null
            reportedBaseYear = true
        }
        Finding(FindingCategory.A12_CONSTRAINT, FindingSeverity.ERROR, refusal.code,
            "in `errorMessage`: `${'$'}$body${'$'}`: ${messageParameterReason(refusal.code, completed, element)}", element.path)
    }
}

private fun TrackedFactBase.messageParameterReason(code: String, parameter: MessageParameter?, element: TrackedDeclaration): String = when (code) {
    "INVALID_ENTITY", "INVALID_ROOT_GROUP" -> "names an entity this model does not declare — check the path"
    "ERROR_TEXT_FIELDNAME_NOT_UNIQUE" -> "names more than one field — qualify the path"
    "ERROR_TEXT_PARAMETER_TOO_MANY_PARENT_GROUPS" -> "walks above the rule's root group — remove an up-step"
    "INVALID_FIELD" -> "names an entity this declaration does not read — add it to the ${entitiesReadBy(element).memberName} or drop the parameter"
    "INVALID_FIELD_IN_PARAMETER" -> "puts a form-position suffix on a group — name a field"
    "INVALID_PARAMETER" -> "puts the rule-group shorthand in a field position — use the group form"
    "INVALID_GROUP" -> "does not name a group available to the declaration's condition"
    "RULEGROUP_IMMEDIATELY_BELOW_ROOT" -> "uses the rule-group shorthand at a root group — name the root explicitly"
    "PARAM_INVALID_IN_NEW_WORLD" -> "uses a retired message terminal"
    "INVALID_USE_OF_SEMANTIC_INDEX" -> "requires positive presence of the same keyed field or group: FieldFilled/GroupFilled or an all-filled quantifier, in one AND arm or every OR arm"
    "ITER_PARAM_WITH_STARREFERENCE", "PARAM_VALUE_WITH_STARREFERENCE" -> "requires an unstarred condition read of this entity"
    "INVALID_NO_VALUE_VALIDATION" -> "requests a validated value from a field with no value validation — drop the suffix"
    "ILLEGAL_SEMANTIC_INDEX" -> "keys a semantic-index key — use one key per parameter"
    "MVK_INDEX_FOR_ERROR_TEXT_INVALID" -> {
        val field = (parameter as? MessageParameter.Field)?.entity?.written
        val group = element.path.substringBeforeLast('/', missingDelimiterValue = "")
        val path = field?.let { (resolveMessageEntity(group, it.replace("'", ""), element.origin) as? Reference.Resolved)?.path }
        "uses a field/key pair for `$path` the condition does not read"
    }
    "NO_CATEGORY" -> "requires a category name after the arrow"
    "FIELD_NO_ENUM" -> "projects a category from a field that is not an enumeration"
    "INVALID_CATEGORY" -> "names a category the enumeration does not declare"
    "NO_BASE_YEAR" -> "requires the model's base year — set modelInfo.baseYear or write a literal year"
    "INVALID_INDEX_FIELD" -> "names no index field on the error entity's path — available short names: " +
        availableMessageIndexNames(element).joinToString().ifEmpty { "none" }
    "INVALID_INDEX_FIELD_AMBIGUOUS" -> "matches more than one index field on the error entity's path"
    "INVALID_INDEX_FIELD_REP_ITER" -> "names an index field reached through a wildcard or key rather than iteration"
    "INVALID_INDEX_FIELD_NONREP_ENUM" -> "requires a single-valued enumeration with an sValue for a fixed group's index"
    "INVALID_INDEX_FIELD_PARAMETER" -> "requires a value or category suffix after the index terminal"
    "MVK_LEXER_STANDARD_ERROR" -> "contains a character or quoted name the message lexer cannot tokenize"
    "MVK_INCOMPLETE_INPUT", "MVK_EXPECTED_TOKEN_NOT_FOUND" -> "ends before the required name, delimiter or offset"
    "MVK_UNEXPECTED_TOKEN" -> "contains a token the message grammar does not admit at this position"
    else -> "is refused by the message-parameter check ($code)"
}
