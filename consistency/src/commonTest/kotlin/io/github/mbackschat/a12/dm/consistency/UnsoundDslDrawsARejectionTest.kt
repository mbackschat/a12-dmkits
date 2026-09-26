package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That every DSL member A12 cannot even resolve or parse draws a rejecting A12 finding of its own.
 *
 * This is what lets report assembly withhold a declaration's message verdicts on one trigger — a rejecting
 * DSL finding at that declaration — rather than also re-asking which texts are unsound
 * ([KERNEL-FINDINGS KF338]). It is a checker property and not a kernel law: a syntax or reference family that
 * went silent on one of these rows would let the message's class through beside a refused member, and would
 * also leave a refused model without the finding that says so.
 *
 * Each row is checked for the property only; which class it draws is the owning family's own test. The rows
 * are the shapes the retired per-family gate named — every reference outcome other than a resolved one
 * (unresolved, ambiguous, above the root, a misplaced up-step, an unknown root group) and unbalanced
 * delimiters — plus the references an earlier segment's gate answers for before their missing name is reached
 * ([earlierSegmentGate]), in the operand positions and the DSL members that carry them.
 */
class UnsoundDslDrawsARejectionTest {

    private val tab = "see" + 0x09.toChar() + "it"

    /** A parameter no condition below reads, so it draws `INVALID_FIELD` beside every sound one. */
    private val parameter = "see \$Excess\$"

    private val refused = "refused, message withheld"

    @Test
    fun anUnsoundConditionIsRefusedAndItsMessageWithheld() {
        val conditions = listOf(
            "FieldFilled(Nope)",
            "FieldFilled(Reference) And FieldFilled(Nope)",
            "FieldFilled(Reference",
            "FieldFilled(Reference))",
            "FieldFilled(Reference] And FieldFilled(Premium)",
            "FieldFilled(Reference) And [Premium > 0",
            "FieldFilled(Reference) And (FieldFilled(Premium)",
            "FieldFilled(Reference,",
            "FieldFilled(Reference) And FieldFilled(Holder*/Nope)",
            "FieldFilled(Reference) And Sum(Holder*/Nope) > 0",
            "FieldFilled(Reference) And NumberOfFilledGroups(Holder*/Nope) > 0",
            "FieldFilled(Reference) And AtLeastOneFieldFilled(Holder*/Nope)",
            "FieldFilled(Reference) And [Holder*/Nope] > 0",
            "FieldFilled(Reference) And FieldValueIncludedInValueList(Holder*/Nope, \\\"x\\\")",
            "FieldFilled(Reference) And FieldFilled(Claims.*/Nope)",
            "FieldFilled(Reference) And FieldFilled(..*/Nope)",
            "FieldFilled(Reference) And FieldFilled(Note)",
            "FieldFilled(Reference) And FieldFilled(../../../Reference)",
            "FieldFilled(Reference) And FieldFilled(Claims/../Reference)",
            "FieldFilled(Reference) And FieldFilled(/Nope/Reference)",
        )
        val actual = linkedMapOf<String, String>()
        actual["a sound condition, a control character — the control"] =
            verdict(rule("FieldFilled(Reference)", tab))
        actual["a sound condition, a parameter it does not read — the control"] =
            verdict(rule("FieldFilled(Reference)", parameter))
        conditions.forEach { condition ->
            actual["$condition | a control character"] = verdict(rule(condition, tab))
            actual["$condition | a parameter it does not read"] = verdict(rule(condition, parameter))
        }

        val expected = linkedMapOf<String, String>()
        // The positive controls: both message families speak where the DSL beside them is sound, so a silent
        // row below is the withholding and not a family that never fires on this model.
        expected["a sound condition, a control character — the control"] = "sound, message: [INVALID_CHARACTERS]"
        expected["a sound condition, a parameter it does not read — the control"] = "sound, message: [INVALID_FIELD]"
        conditions.forEach { condition ->
            expected["$condition | a control character"] = refused
            expected["$condition | a parameter it does not read"] = refused
        }
        assertEquals(expected, actual, "an unsound condition draws its own rejection")
    }

    @Test
    fun anUnsoundOperationIsRefusedAndItsMessageWithheld() {
        val operations = listOf(
            "[Nope] + 1",
            "Sum(Claims*/Amount",
            "Sum(Claims*/Amount))",
            "[Holder*/Nope] + 1",
            "Sum(Holder*/Nope)",
            "Sum(Claims.*/Nope)",
        )
        val actual = linkedMapOf<String, String>()
        actual["a sound operation — the control"] = verdict(computation("Sum(Claims*/Amount)", tab))
        operations.forEach { operation -> actual[operation] = verdict(computation(operation, tab)) }
        actual["a common precondition naming nothing"] =
            verdict(computation("Sum(Claims*/Amount)", tab, common = "FieldFilled(Nope)"))
        actual["an alternative's precondition naming nothing"] =
            verdict(computation("Sum(Claims*/Amount)", tab, precondition = "FieldFilled(Nope)"))

        val expected = linkedMapOf<String, String>()
        expected["a sound operation — the control"] = "sound, message: [INVALID_CHARACTERS]"
        operations.forEach { operation -> expected[operation] = refused }
        expected["a common precondition naming nothing"] = refused
        expected["an alternative's precondition naming nothing"] = refused
        assertEquals(expected, actual, "an unsound operation draws its own rejection")
    }

    private fun verdict(declaration: String): String {
        val findings = checkRead(ModelSource(model(declaration))).findings.filter { it.at == AT }
        // Classified by the two classes the messages here can draw, not by a code prefix: A12's error-text
        // checker draws `MVK_` keys too, so a prefix would count a leaked one as the DSL's rejection.
        val message = findings.map { it.code }.filter { it in MESSAGE_CLASSES }.sorted()
        val rejected = findings.any { it.justifiesRejection && it.code !in MESSAGE_CLASSES }
        return when {
            !rejected -> "sound, message: $message"
            message.isEmpty() -> refused
            else -> "refused, message LEAKED: $message"
        }
    }

    private fun rule(condition: String, message: String): String =
        """{"type":"Rule","id":"r0","name":"Probe","Rule":{
          "errorCondition":"$condition","errorEntityRelPath":"../Reference",
          "errorCode":"C1","errorMessage":[{"locale":"en_US","text":"$message"}]}}"""

    private fun computation(
        operation: String,
        message: String,
        common: String? = null,
        precondition: String? = null,
    ): String =
        """{"type":"Computation","id":"c0","name":"Probe","Computation":{
          "computedFieldRelPath":"../Premium",${common?.let { "\"commonPrecondition\":\"$it\"," } ?: ""}
          "computationAlternatives":[{${precondition?.let { "\"precondition\":\"$it\"," } ?: ""}"operation":"$operation"}],
          "errorMessage":[{"locale":"en_US","text":"$message"}]}}"""

    /**
     * An insurance policy with fixed holder and insured groups, repeatable claims, and one probe declaration.
     * Short-name references are on and `Note` is declared in both fixed groups, so a bare `Note` is ambiguous.
     */
    private fun model(declaration: String): String = """
        {"header":{"id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelConfig":{"fieldRefByShortNameAllowed":true},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"elements":[
            {"type":"Field","id":"f0","name":"Reference","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Premium","Field":{"fieldType":{"type":"NumberType",
              "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}},
            {"type":"Field","id":"f4","name":"Excess","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Group","id":"g1","name":"Holder","Group":{"elements":[
              {"type":"Field","id":"f2","name":"HolderName","Field":{"fieldType":{"type":"StringType"}}},
              {"type":"Field","id":"f5","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g3","name":"Insured","Group":{"elements":[
              {"type":"Field","id":"f6","name":"Note","Field":{"fieldType":{"type":"StringType"}}}
            ]}},
            {"type":"Group","id":"g2","name":"Claims","Group":{"repeatability":9,"elements":[
              {"type":"Field","id":"f3","name":"Amount","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"minFractionalDigits":0,"maxFractionalDigits":2}}}}
            ]}},
            $declaration
          ]}}
         ]}}}
    """.trimIndent()

    private companion object {
        const val AT = "/Policy/Probe"

        /** What the two defective messages here draw beside a sound member. */
        val MESSAGE_CLASSES = setOf("INVALID_CHARACTERS", "INVALID_FIELD")
    }
}
