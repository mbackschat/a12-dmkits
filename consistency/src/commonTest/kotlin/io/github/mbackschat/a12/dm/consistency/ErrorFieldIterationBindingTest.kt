package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a starred reference names the error field without binding the rule's iteration, on both checker targets.
 *
 * Each row is one A12 measured in `rulekit.validate.laws.IterationBindingLawsTest` (a fixed root reporting on a
 * repeatable claim) or `…RootRepetitionIterationAgreementLawsTest` (a repeatable root), and the WHOLE code list is
 * compared, so a row A12 admits also proves no other family speaks there. The rows A12 refuses with
 * `MVK_NEG_CONDITION_IN_ITERATION` belong to the iteration guard and are judged against A12 in
 * `CheckerIterationBindingAgreementTest`, where this family must not borrow that class.
 */
class ErrorFieldIterationBindingTest {

    @Test
    fun aSiblingPerRowReadBesideAStarredErrorReferenceIsRefused() {
        val rows = linkedMapOf(
            "Sum, plain" to policy("Claims/Amount", "Sum(Claims*/Amount) > 0"),
            "Sum, sibling guard" to policy("Claims/Amount", "FieldFilled(Claims/Reserve) And Sum(Claims*/Amount) > 0"),
            "Sum, own guard" to policy("Claims/Amount", "FieldFilled(Claims/Amount) And Sum(Claims*/Amount) > 0"),
            "group Sum, sibling guard" to
                policy("Claims/Detail/Units", "FieldFilled(Claims/Reserve) And Sum(Claims*/Detail) > 0"),
            "group overlap, plain" to policy("Claims/Windows/Window",
                "AtLeastOneDateRangeOverlaps(Claims/CoverSpan In Claims*/Windows)"),
            "group overlap, own guard" to policy("Claims/Windows/Window",
                "FieldFilled(Claims/Windows/Window) And AtLeastOneDateRangeOverlaps(Claims/CoverSpan In Claims*/Windows)"),
            "a marked root filter, sibling guard" to policy("Claims/Amount", "FieldFilled(Claims/Reserve) And " +
                "Sum(Claims*/Amount Having (GroupFilled(\$/Policy) And CurrentRepetition(Claims) > 0)) > 0"),
            "a marked root filter, own guard" to policy("Claims/Amount", "FieldFilled(Claims/Amount) And " +
                "Sum(Claims*/Amount Having (GroupFilled(\$/Policy) And CurrentRepetition(Claims) > 0)) > 0"),
        )
        assertEquals(
            linkedMapOf(
                "Sum, plain" to emptyList(),
                "Sum, sibling guard" to listOf(BINDS),
                "Sum, own guard" to emptyList(),
                "group Sum, sibling guard" to listOf(BINDS),
                "group overlap, plain" to listOf(BINDS),
                "group overlap, own guard" to emptyList(),
                "a marked root filter, sibling guard" to listOf(BINDS),
                "a marked root filter, own guard" to emptyList(),
            ),
            rows.mapValues { codes(it.value) },
        )
    }

    /**
     * A `$`-marked filter read of the claim's own detail binds the rule where the error field is that detail, and an
     * unmarked one binds nothing; A12 refuses neither with this class. The marked row is the iteration guard's
     * instead: its marked read iterates the claim and guards nothing (row "AtLeastOneFieldFilled marked" of
     * `CheckerIterationBindingAgreementTest`, KERNEL-FINDINGS KF608).
     */
    @Test
    fun aMarkedFilterReadOfTheErrorFieldBindsItAndAnUnmarkedOneBindsNothing() {
        val marked = "AtLeastOneFieldFilled(Claims*/Amount Having (FieldFilled(\$Claims/Detail/Units) And " +
            "CurrentRepetition(Claims) > 0))"
        val candidate = "AtLeastOneFieldFilled(Claims*/Amount Having (FieldFilled(Claims/Detail/Units) And " +
            "CurrentRepetition(Claims) > 0))"
        assertEquals(listOf("MVK_NEG_CONDITION_IN_ITERATION"), codes(policy("Claims/Detail/Units", marked)))
        assertEquals(emptyList(), codes(policy("Claims/Amount", candidate)))
    }

    @Test
    fun onARepeatableRootBothArmsAnswer() {
        assertEquals(
            linkedMapOf(
                "the root group starred" to listOf(BINDS),
                "the error field starred" to listOf(BINDS),
                "the error field per row — the control" to emptyList(),
            ),
            linkedMapOf(
                "the root group starred" to codes(fleet("NumberOfFilledGroups(/Vehicles*) > 0 And FieldFilled(Mileage)")),
                "the error field starred" to
                    codes(fleet("AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Mileage)")),
                "the error field per row — the control" to
                    codes(fleet("AtLeastOneFieldFilled(/Vehicles*/Plate) And FieldFilled(Plate)")),
            ),
        )
    }

    /**
     * Which containing group names the error field decides the CLASS: only a root group sends the rule to the
     * from-above classes, while a non-root group is asked whether it iterates the repetition — so it draws this
     * one, and the repeatable group itself admits (`…RootOnlyErrorTargetIterationLawsTest`).
     */
    @Test
    fun theContainingGroupThatNamesTheErrorFieldPicksTheClass() {
        assertEquals(
            linkedMapOf(
                "rule at the root, naming the root" to listOf("MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"),
                "rule at the claims, naming the root" to listOf("MVK_ERROR_FIELDGROUP_ITERATION4_INVALID"),
                "rule at the claims, naming the branch above it" to listOf(BINDS),
                "rule at the branch, naming the branch" to listOf(BINDS),
                "rule at the claims, naming the claims" to emptyList(),
            ),
            linkedMapOf(
                "rule at the root, naming the root" to codes(region(ROOT, "Branch/Claims/Amount",
                    "GroupFilled(/Region) And FieldFilled(Branch/Claims/Reserve)")),
                "rule at the claims, naming the root" to
                    codes(region(CLAIMS, "Amount", "GroupFilled(/Region) And [Reserve] > 0")),
                "rule at the claims, naming the branch above it" to
                    codes(region(CLAIMS, "Amount", "GroupFilled(/Region/Branch) And [Reserve] > 0")),
                "rule at the branch, naming the branch" to codes(region(BRANCH, "Claims/Amount",
                    "GroupFilled(/Region/Branch) And FieldFilled(Claims/Reserve)")),
                "rule at the claims, naming the claims" to
                    codes(region(CLAIMS, "Amount", "GroupFilled(/Region/Branch/Claims) And [Reserve] > 0")),
            ),
        )
    }

    private fun codes(json: String): List<String> = checkRead(ModelSource(json)).findings.map { it.code }

    /** An insurance policy whose claims repeat, with one rule at the fixed root. */
    private fun policy(errorField: String, condition: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"policy","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Policy","Group":{"repeatability":1,"elements":[
            {"type":"Field","id":"f0","name":"Premium","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Group","id":"g1","name":"Claims","Group":{"repeatability":3,"elements":[
              {"type":"Group","id":"g2","name":"Detail","Group":{"repeatability":1,"elements":[
                {"type":"Field","id":"f1","name":"Units","Field":{"fieldType":{"type":"NumberType"}}}]}},
              {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
              {"type":"Field","id":"f3","name":"Reserve","Field":{"fieldType":{"type":"NumberType",
                "NumberType":{"maxFractionalDigits":2}}}},
              {"type":"Field","id":"f4","name":"CoverSpan","Field":{"fieldType":{"type":"DateRangeType",
                "DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}},
              {"type":"Group","id":"g3","name":"Windows","Group":{"repeatability":1,"elements":[
                {"type":"Field","id":"f5","name":"Window","Field":{"fieldType":{"type":"DateRangeType",
                  "DateRangeType":{"format":"yyyy-MM-dd","rangeSeparator":"/"}}}}]}}
            ]}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":${quoted(condition)},
              "errorEntityRelPath":"../$errorField",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}}
         ]}}}
    """.trimIndent()

    /** A fleet whose vehicles are the repeatable root, reporting on the plate. */
    private fun fleet(condition: String): String = """
        {"header":{"modelVersion":"29.4.0","id":"fleet","locales":[{"code":"en_US"}]},
         "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
          {"type":"Group","id":"g0","name":"Vehicles","Group":{"repeatability":50,"elements":[
            {"type":"Field","id":"f0","name":"Plate","Field":{"fieldType":{"type":"StringType"}}},
            {"type":"Field","id":"f1","name":"Mileage","Field":{"fieldType":{"type":"NumberType"}}},
            {"type":"Rule","id":"r0","name":"Probe","Rule":{
              "errorCondition":${quoted(condition)},
              "errorEntityRelPath":"../Plate",
              "errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}
          ]}},
          {"type":"Group","id":"g1","name":"Header","Group":{"repeatability":1,"elements":[
            {"type":"Field","id":"f2","name":"Title","Field":{"fieldType":{"type":"StringType"}}}]}}
         ]}}}
    """.trimIndent()

    /** A fixed root, a fixed branch and its repeatable claims, with one rule placed at [at]. */
    private fun region(at: String, errorField: String, condition: String): String {
        val rule = """{"type":"Rule","id":"r0","name":"Probe","Rule":{"errorCondition":${quoted(condition)},
            "errorEntityRelPath":"../$errorField","errorCode":"C0","errorMessage":[{"locale":"en_US","text":"x"}]}}"""
        fun placed(level: String) = if (at == level) ",$rule" else ""
        return """
            {"header":{"modelVersion":"29.4.0","id":"region","locales":[{"code":"en_US"}]},
             "content":{"modelInfo":{"name":"model"},"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Region","Group":{"repeatability":1,"elements":[
                {"type":"Group","id":"g1","name":"Branch","Group":{"repeatability":1,"elements":[
                  {"type":"Group","id":"g2","name":"Claims","Group":{"repeatability":5,"elements":[
                    {"type":"Field","id":"f0","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}},
                    {"type":"Field","id":"f1","name":"Reserve","Field":{"fieldType":{"type":"NumberType"}}}
                    ${placed(CLAIMS)}]}}${placed(BRANCH)}]}}${placed(ROOT)}]}}
             ]}}}
        """.trimIndent()
    }

    private fun quoted(text: String) = "\"" + text.replace("\"", "\\\"") + "\""

    private companion object {
        const val BINDS = "MVK_ERROR_FIELD_AND_ITERATION_INVALID"
        const val ROOT = "/Region"
        const val BRANCH = "/Region/Branch"
        const val CLAIMS = "/Region/Branch/Claims"
    }
}
