package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionCall
import io.github.mbackschat.a12.dm.consistency.syntax.OPERATOR_SPELLINGS
import io.github.mbackschat.a12.dm.consistency.syntax.OperatorId
import io.github.mbackschat.a12.dm.consistency.syntax.Token
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.decodeAuthoredStringLiteral
import io.github.mbackschat.a12.dm.consistency.syntax.isName
import io.github.mbackschat.a12.dm.consistency.syntax.normalizedPath

/**
 * What each of the two validity conditions demands, which the keyword alone does not tell you.
 *
 * `Valid` and `Invalid` spell two different grammar rules and the OPERAND SHAPE is what separates them:
 * `Valid(Field, "Type")` is `predefinedTypeValidityCondition` and `Valid(Date(…))` is
 * `dateValidityCondition`. Two checker classes that share nothing ask them
 * ([KERNEL-FINDINGS KF402], `rulekit.validate.laws.ValidityConditionOperandLawsTest`):
 *
 * | the arm | the gate, in order | class |
 * |---|---|---|
 * | `Valid(Field, "Type")` | the subject names a group | `MVK_NO_GROUPS_ALLOWED` |
 * | | it carries a repetition star | `MVK_NO_WILDCARDS_ALLOWED` |
 * | | it waives value validation | `MVK_INVALID_RAW_TYPE` |
 * | | it is neither text nor enumeration | `MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM` |
 * | | the type name is empty | `MVK_NO_EMPTY` |
 * | `Valid(Date(…))` | no slot names an entity | `MVK_ONLY_CONSTANTS_USED` |
 *
 * **The kind gate's admitted set is narrower than its code's name.** `isStringEnumOrExtensibleEnum` is
 * `isString || isEnum || isExtensibleEnum`, and the third disjunct is a FLAG on a string declaration rather
 * than a kind of its own — so a `CustomFieldType` is refused, measured, where the name reads as if a custom
 * code type might be exactly what a code-validity condition wants.
 *
 * **The type name is opaque at model-check time.** Predefined types are a runtime client SPI, not a
 * model-time registry, so A12 stores whatever was written and admits a name nothing registers; only the
 * EMPTY one is refused, and only after the field has passed all four of its gates. A reader checking the
 * cheap string first would name it on a model A12 refuses for its field, so both orderings are measured.
 *
 * **The date arm's test is `entitySpec() != null` PER SLOT**, which an EXTRACT operator does not satisfy —
 * so `Valid(Date(DayFromDate(X), "02", "2024"))` plainly reads a field and is still "only constants" to
 * A12. Measured, because a reader asking "does this construction mention a field" gets it backwards.
 *
 * **The slot gates answer before this arm's question**, so the construction is read through
 * [readDateConstruction] rather than re-examined here: one live statement of what a slot demands, and the
 * arm declines wherever a slot already fails.
 *
 * **All five gates of the first arm are reported here** rather than left to a pre-gate family, because
 * nothing else reads this carrier: the mirror's empty cells are what established that, and declining on the
 * first three would have left a model A12 refuses with no finding at all.
 *
 * **Silent on an unresolvable type definition or an `UnspecifiedType` subject**, whose type this reader cannot know, and on a subject shape it cannot reduce to one bare path.
 */
internal fun TrackedFactBase.validityConditionOperandFindings(): List<Finding> = declarations().flatMap { element ->
    validityConditionContributions(element).take(1).map { it.finding }
}

internal fun TrackedFactBase.validityConditionContributions(
    element: TrackedDeclaration,
    execution: ConditionExecution? = conditionExecution(element),
): List<ConditionFinding> {
    val keywords = OPERATOR_SPELLINGS[conditionLanguage] ?: return emptyList()
    val carriers = VALIDITY_CONDITIONS.mapNotNull { keywords[it] }.toSet()
    val dateConstruction = keywords[OperatorId.DATE] ?: return emptyList()
    if (carriers.isEmpty()) {
        return emptyList()
    }
    if (execution == null) return emptyList()
    val group = element.path.substringBeforeLast('/')
    return callContributions(element, execution) { member, call ->
        if (call.operator !in carriers) return@callContributions null
        val shape = execution.callsByStart.getValue(call.at)
        validityDefect(shape, execution.recognition.calls, dateConstruction, group, element, call)
            ?.let { finding(it, shape, element, member) }
    }
}

/** Both spellings of the keyword; the two arms share it and are told apart by the operand shape. */
private val VALIDITY_CONDITIONS = listOf(OperatorId.VALID_TYPE, OperatorId.INVALID_TYPE)

/** The two declarations `isStringEnumOrExtensibleEnum` admits — the third disjunct is a string-type FLAG. */
private val CODE_BEARING_TYPES = setOf(FieldType.STRING, FieldType.ENUMERATION)

/** What a BARE path may carry besides names; a star, an arrow or a filter each brings another family's gate. */
private val SUBJECT_PATH_PUNCTUATION = setOf("/", "..")

private class ValidityDefect(val code: String, val at: String, val because: String)

/** The first gate this call fails, in A12's own order, or null where none fails and where it may not judge. */
private fun TrackedFactBase.validityDefect(
    shape: CallShape,
    shapes: List<CallShape>,
    dateConstruction: String,
    declaringGroup: String,
    element: TrackedDeclaration,
    call: ConditionCall,
): ValidityDefect? {
    val sole = shape.operands.singleOrNull()
    if (sole != null) {
        return dateArmDefect(sole, shapes, dateConstruction, element)
    }
    if (shape.operands.size != 2) {
        return null
    }
    val name = shape.operands[1].singleOrNull()?.takeIf { it.kind == TokenKind.STRING } ?: return null
    return predefinedTypeArmDefect(shape.operands[0], name, declaringGroup, element, call)
}

/**
 * The predefined-type arm's five gates in A12's own order, or null where none fails.
 *
 * All five belong to this family: nothing else reads this carrier, so declining on the first three would
 * leave a model A12 refuses with no finding at all. The mirror measured that and the gates were added here
 * rather than disclosed.
 */
private fun TrackedFactBase.predefinedTypeArmDefect(
    subject: List<Token>,
    name: Token,
    declaringGroup: String,
    element: TrackedDeclaration,
    call: ConditionCall,
): ValidityDefect? {
    // A keyed subject meets every gate below as the row its key selects, star included, and an index the
    // semantic-index chain refuses answers before them (KERNEL-FINDINGS KF476,
    // `adapter.laws.CheckerKeyedStarOperandAgreementTest`).
    val keyed = call.operands.firstOrNull()?.text?.takeIf { writesASemanticIndex(it) }
    val written = if (keyed != null) {
        operandReferenceIn(keyed, declaringGroup, element, call.inAFilterBody) { null }?.reference ?: return null
    } else {
        if (!subject.all { it.isName() || it.text in SUBJECT_PATH_PUNCTUATION || it.text == "*" }) {
            return null
        }
        normalizedPath(subject)
    }
    val resolved = resolveEntity(declaringGroup, written.replace("*", ""), element.origin)
        as? Reference.Resolved ?: return null
    val target = declarationAt(resolved.path) ?: return null
    if (target.declaredKind != DeclaredKind.FIELD) {
        return ValidityDefect(
            "MVK_NO_GROUPS_ALLOWED", target.path,
            "names a group, and a code-validity condition judges one field's value",
        )
    }
    if ('*' in written) {
        return ValidityDefect(
            "MVK_NO_WILDCARDS_ALLOWED", target.path,
            "is read across a repetition, and this condition judges a single value — let the rule iterate " +
                "the repeating group instead",
        )
    }
    val field = target
    if (waivesValueValidation(field)) {
        return ValidityDefect(
            "MVK_INVALID_RAW_TYPE", field.path,
            "waives value validation, so A12 holds its content unparsed and has nothing to judge against " +
                "a predefined type",
        )
    }
    val type = effectiveFieldType(field) ?: return null
    // A type this reader cannot resolve has no base-type class, so the kind gate may not judge it.
    if (type.baseTypeClass == null) {
        return null
    }
    if (type !in CODE_BEARING_TYPES) {
        return ValidityDefect(
            "MVK_NO_STRING_OR_ENUM_OR_EXT_ENUM", field.path,
            "is declared `$type`, and a code-validity condition reads a text or an enumeration — the " +
                "extensible-enum spelling the code's name mentions is a flag on a text declaration, not a " +
                "kind of its own",
        )
    }
    return if (name.text.decodeAuthoredStringLiteral().isEmpty()) {
        ValidityDefect(
            "MVK_NO_EMPTY", field.path,
            "is judged against a predefined type whose name is empty — name the registered type, which " +
                "A12 stores verbatim and only the runtime resolves",
        )
    } else {
        null
    }
}

/**
 * The date arm's one class, or null where a slot gate answers first or the shape is not one judged here.
 *
 * The sole operand must BE a `Date` construction; the validity condition also spells a `Time` one, whose
 * grammar rule this reader does not cover, and anything else under the keyword is another law's.
 */
private fun TrackedFactBase.dateArmDefect(
    sole: List<Token>,
    shapes: List<CallShape>,
    dateConstruction: String,
    element: TrackedDeclaration,
): ValidityDefect? {
    val head = sole.firstOrNull()?.takeIf { it.text == dateConstruction } ?: return null
    val construction = shapes.firstOrNull { it.operator == dateConstruction && it.at == head.start }
        ?: return null
    val reading = readDateConstruction(construction, element) ?: return null
    return if (!reading.aSlotAnswersFirst && !reading.namesAnEntity) {
        ValidityDefect(
            "MVK_ONLY_CONSTANTS_USED", element.path,
            "judges a date built from constants alone, which is decided before any document is filled in " +
                "— name a field in one of the slots, or drop the rule",
        )
    } else {
        null
    }
}

private fun finding(
    defect: ValidityDefect,
    shape: CallShape,
    element: TrackedDeclaration,
    member: String,
): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    defect.code,
    "in `$member`: `${shape.operator}` judges `${defect.at}`, which ${defect.because}",
    element.path,
    shape.spanIn(member),
)
