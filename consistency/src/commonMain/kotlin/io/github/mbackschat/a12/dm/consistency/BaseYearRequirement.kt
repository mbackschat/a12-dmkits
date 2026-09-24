package io.github.mbackschat.a12.dm.consistency

import io.github.mbackschat.a12.dm.consistency.syntax.CONDITION_OPERATOR_KEYWORDS
import io.github.mbackschat.a12.dm.consistency.syntax.CallShape
import io.github.mbackschat.a12.dm.consistency.syntax.DATE_CONSTRUCTION
import io.github.mbackschat.a12.dm.consistency.syntax.TokenKind
import io.github.mbackschat.a12.dm.consistency.syntax.NameTerminal
import io.github.mbackschat.a12.dm.consistency.syntax.callShapes
import io.github.mbackschat.a12.dm.consistency.syntax.messageParameterBodies
import io.github.mbackschat.a12.dm.consistency.syntax.nameTerminalOf
import io.github.mbackschat.a12.dm.consistency.syntax.tokenize

/**
 * The places a model's declared BASE YEAR is not optional.
 *
 * Two unrelated kernel checkers ask the same one-line question of the model, and the pairing is what makes
 * this one family rather than two branches ([KERNEL-FINDINGS KF403],
 * `rulekit.validate.laws.BaseYearRequirementLawsTest`):
 *
 * | the shape | without a declared base year |
 * |---|---|
 * | the `BaseYear` constant, anywhere | `MVK_NO_BASE_YEAR` |
 * | a `Date` construction with no year parameter | `MVK_MISSING_YEAR` |
 *
 * **The constant is refused by its own checker wherever it stands**, so the carrier does not matter —
 * measured on three (a comparison, an extraction, a shift) rather than one, because "the constant is
 * refused" is a claim about the constant and a single carrier cannot be told apart from that carrier's own
 * operand gate.
 *
 * **The day and month parameters are checked BEFORE the year one**, so a year-less construction that is also
 * out of range answers with the day's class. That gate is [dateConstructionSlotFindings]' and is read
 * through [aDateSlotAnswersFirst] rather than restated here.
 *
 * **The `Time` construction has no year to omit** and takes any prefix of its three slots, so the same
 * two-argument shape is admitted there. That row is the discriminator against the tempting reading "a short
 * construction needs a base year".
 *
 * **A declared base year does not simply relax the model.** It DISABLES the year-agreement walk of the
 * range-overlap operators ([dateRangeOperandListFindings]) while ENABLING these two shapes, so a family
 * treating it as one switch in one direction would get the other half backwards.
 *
 * **The error MESSAGE is a third place, and A12 answers it from a different vocabulary.** The same constant
 * written as a message parameter — `${'$'}BaseYear${'$'}`, or with the signed offset it admits unbounded — draws the
 * unprefixed `NO_BASE_YEAR` rather than `MVK_NO_BASE_YEAR`, measured on both language spellings
 * (`rulekit.validate.laws.RuleMessageParameterLanguageLawsTest`). The carrier really does decide the class
 * here, so the two arms cannot share a code however alike the question is.
 *
 * **A declaration whose CONDITION already names the constant is reported once, on the condition.** Which of
 * the two A12 answers first is not measured, and both are true of the model, so reporting the condition's is
 * an under-report rather than a wrong class.
 *
 * **Silent on everything when the model declares a base year**, which is the whole family's precondition,
 * and on a `Date` construction this reader cannot parse into slots.
 */
internal fun TrackedFactBase.baseYearRequirementFindings(): List<Finding> {
    if (hasBaseYear) {
        return emptyList()
    }
    val keywords = CONDITION_OPERATOR_KEYWORDS[conditionLanguage] ?: return emptyList()
    val baseYear = keywords[BASE_YEAR_CONSTANT] ?: return emptyList()
    val dateConstruction = keywords[DATE_CONSTRUCTION] ?: return emptyList()
    val unsound = declarationsWithUnsoundDsl()
    return declarations().mapNotNull { element ->
        val body = element.body ?: return@mapNotNull null
        conditionTexts(element.kind, body).firstNotNullOfOrNull { (member, text) ->
            if (namesTheConstant(text, baseYear)) {
                return@firstNotNullOfOrNull constantFinding(baseYear, element, member)
            }
            callShapes(text, conditionLanguage, syntaxEntryOf(member))
                .filter { it.operator == dateConstruction }
                .firstNotNullOfOrNull { shape -> yearlessFinding(shape, element, member) }
        } ?: messageConstantFinding(element, body, unsound)
    }
}

/** The same constant in an error message, which A12 refuses from its own error-text vocabulary. */
private fun TrackedFactBase.messageConstantFinding(
    element: TrackedDeclaration,
    body: kotlinx.serialization.json.JsonObject,
    unsound: Set<String>,
): Finding? {
    if (element.path in unsound) {
        return null
    }
    val messages = body.authoredMessages() as? AuthoredMessages.Read ?: return null
    return messages.entries.firstNotNullOfOrNull { message ->
        if (message.text.carriesAnOffendingCharacter()) {
            return@firstNotNullOfOrNull null
        }
        messageParameterBodies(message.text)
            .firstOrNull { nameTerminalOf(it, conditionLanguage) == NameTerminal.BASE_YEAR }
            ?.let { parameter ->
                Finding(
                    FindingCategory.A12_CONSTRAINT,
                    FindingSeverity.ERROR,
                    "NO_BASE_YEAR",
                    "in `errorMessage`: `${'$'}$parameter${'$'}` interpolates the model's reference year, and " +
                        "this model declares none — set `modelInfo.baseYear`, or write the year the message " +
                        "means",
                    element.path,
                )
            }
    }
}

private const val BASE_YEAR_CONSTANT = "BaseYear"

/**
 * Whether the text names the constant as a NAME token of its own.
 *
 * Read off the token stream rather than the text so a field or a rule whose name merely contains the word
 * is not mistaken for it — the reason this is not a substring search.
 */
private fun TrackedFactBase.namesTheConstant(text: String, baseYear: String): Boolean =
    tokenize(text).any { it.kind == TokenKind.NAME && it.text == baseYear }

private fun constantFinding(baseYear: String, element: TrackedDeclaration, member: String): Finding = Finding(
    FindingCategory.A12_CONSTRAINT,
    FindingSeverity.ERROR,
    "MVK_NO_BASE_YEAR",
    "in `$member`: `$baseYear` stands for the model's reference year, and this model declares none — " +
        "set `modelInfo.baseYear`, or write the year the condition means",
    element.path,
)

/**
 * The year-less construction's finding, or null where it carries its year or an earlier slot answers.
 *
 * A12 reads the year from the model only when the parameter is absent, so a construction that spells one is
 * this family's business in neither direction.
 */
private fun TrackedFactBase.yearlessFinding(
    shape: CallShape,
    element: TrackedDeclaration,
    member: String,
): Finding? {
    if (shape.operands.size != YEARLESS_ARITY || aDateSlotAnswersFirst(shape.operands, element)) {
        return null
    }
    return Finding(
        FindingCategory.A12_CONSTRAINT,
        FindingSeverity.ERROR,
        "MVK_MISSING_YEAR",
        "in `$member`: `${shape.operator}` is built from a day and a month alone, which needs the model's " +
            "reference year to complete it, and this model declares none — set `modelInfo.baseYear`, or " +
            "give the construction its own year",
        element.path,
    )
}

/** A day and a month and nothing else: the one `Date` shape whose year A12 takes from the model. */
private const val YEARLESS_ARITY = 2
