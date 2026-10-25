package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The checker's half of "computations may not depend on each other in a circle".
 *
 * Every row mirrors one measured against the real Kernel by
 * `rulekit.validate.laws.ComputationDependencyCycleLawsTest`, including the two that keep the family honest:
 * a PRECONDITION read builds an edge exactly like an operation read, and a one-node circle is A12's other
 * law, so this family stays silent there and the self-read family answers.
 */
class ComputationCycleTest {

    private val cycle = "MVK_ERROR_CALC_CYCLE"

    private fun codes(vararg calcs: Calc): List<String> =
        checkRead(ModelSource(model(calcs.toList()))).findings.map { it.code }

    @Test
    fun whichReadsBuildTheDependencyCircle() {
        val actual = mutableMapOf<String, List<String>>()
        actual["an acyclic chain — the control"] = codes(
            calc("W", "../Weight", "[Base] + 1"),
            calc("V", "../Volume", "[Weight] * 2"),
        )
        actual["two computations reading each other"] = codes(
            calc("W", "../Weight", "[Volume] + 1"),
            calc("V", "../Volume", "[Weight] * 2"),
        )
        actual["a three-step circle"] = codes(
            calc("W", "../Weight", "[Volume] + 1"),
            calc("V", "../Volume", "[Base] * 2"),
            calc("B", "../Base", "[Weight] - 1"),
        )
        actual["one computation reading its own target"] = codes(calc("W", "../Weight", "[Weight] + 1"))
        actual["the back edge carried by a PRECONDITION"] = codes(
            calc("W", "../Weight", "[Base] + 1", precondition = "FieldFilled(Volume)"),
            calc("V", "../Volume", "[Weight] * 2"),
        )
        actual["the back edge through a field nothing computes — the control"] = codes(
            calc("W", "../Weight", "[Declared] + 1"),
            calc("V", "../Volume", "[Weight] * 2"),
        )
        actual["a circle whose two ends sit in different groups"] = codes(
            calc("W", "../Weight", "Sum(Crates*/Cost)"),
            calc("C", "../Cost", "[/Depot/Weight] + 1", group = "Crates"),
        )

        val expected = mutableMapOf<String, List<String>>()
        expected["an acyclic chain — the control"] = emptyList()
        expected["two computations reading each other"] = listOf(cycle)
        // ONE finding for one circle, not one per member: three computations, one cause.
        expected["a three-step circle"] = listOf(cycle)
        // A one-node circle is A12's self-read class, never this family's; the self-read family reports it
        // here because both scales are 0, so the scale gate admits (KF181).
        expected["one computation reading its own target"] = listOf("MVK_ERROR_REFERENCE_TO_CALCULATED_FIELD")
        expected["the back edge carried by a PRECONDITION"] = listOf(cycle)
        expected["the back edge through a field nothing computes — the control"] = emptyList()
        expected["a circle whose two ends sit in different groups"] = listOf(cycle)
        assertEquals(expected, actual, "which reads build the dependency circle")
    }

    /** The finding names the whole circle, since no single computation in it is the one to change. */
    @Test
    fun theFindingNamesEveryFieldInTheCircle() {
        val finding = checkRead(
            ModelSource(
                model(
                    listOf(
                        calc("W", "../Weight", "[Volume] + 1"),
                        calc("V", "../Volume", "[Weight] * 2"),
                    ),
                ),
            ),
        ).findings.single { it.code == cycle }
        assertEquals(true, finding.explain.contains("/Depot/Volume"), finding.explain)
        assertEquals(true, finding.explain.contains("/Depot/Weight"), finding.explain)
    }

    /**
     * A circle ENTERED at one member is still reported from the same place, whichever member that was.
     *
     * Here the walk reaches the circle through `Base`, so it meets `Weight` first and `Volume` second — and
     * the report must still start at `Volume`, the smaller of the two. Without that, a circle's anchor and
     * its printed order would depend on which unrelated computation happened to point into it, so two
     * reports of one model could disagree about where the problem is.
     */
    @Test
    fun aCircleIsReportedFromItsSmallestMemberWhicheverOneTheWalkMeetsFirst() {
        val finding = checkRead(
            ModelSource(
                model(
                    listOf(
                        calc("B", "../Base", "[Weight] + 1"),
                        calc("W", "../Weight", "[Volume] + 1"),
                        calc("V", "../Volume", "[Weight] * 2"),
                    ),
                ),
            ),
        ).findings.single { it.code == cycle }
        assertEquals("/Depot/V", finding.at, finding.explain)
        assertEquals(true, finding.explain.contains("/Depot/Volume → /Depot/Weight → /Depot/Volume"),
            finding.explain)
    }

    /** Two disjoint circles are two causes, so they are two findings rather than one merged report. */
    @Test
    fun twoDisjointCirclesAreTwoFindings() {
        val findings = checkRead(
            ModelSource(
                model(
                    listOf(
                        calc("W", "../Weight", "[Volume] + 1"),
                        calc("V", "../Volume", "[Weight] * 2"),
                        calc("C", "../Cost", "[Units] + 1", group = "Crates"),
                        calc("U", "../Units", "[Cost] * 2", group = "Crates"),
                    ),
                ),
            ),
        ).findings.filter { it.code == cycle }
        assertEquals(2, findings.size, findings.map { it.explain }.toString())
        // Ordered by the circle itself, not by which one the walk reached first, so two runs over one model
        // produce the same report even when both circles start at the same field.
        assertEquals(listOf("/Depot/Crates/C", "/Depot/V"), findings.map { it.at })
    }

    /**
     * Two circles through ONE field are ordered by the circles, not by where their fields appear in the text.
     *
     * `Base` reads `Weight` before `Volume`, so the walk meets the `Weight` circle first — and both circles
     * begin at `Base`, which is what makes their order a real choice rather than a consequence of it. Sorting
     * on the first member alone would hand back the text order, so reversing two operands inside one
     * operation would reorder a consumer's report without changing anything about the model.
     */
    @Test
    fun twoCirclesThroughOneFieldAreOrderedByTheCircleNotByTheText() {
        val findings = checkRead(
            ModelSource(
                model(
                    listOf(
                        calc("B", "../Base", "[Weight] + [Volume]"),
                        calc("W", "../Weight", "[Base] * 2"),
                        calc("V", "../Volume", "[Base] - 1"),
                    ),
                ),
            ),
        ).findings.filter { it.code == cycle }
        assertEquals(2, findings.size, findings.map { it.explain }.toString())
        assertEquals(
            listOf("/Depot/Base → /Depot/Volume", "/Depot/Base → /Depot/Weight"),
            findings.map { it.explain.substringAfter("refuses the model: ").substringBefore(" → /Depot/Base.") },
        )
    }

    private data class Calc(
        val name: String,
        val target: String,
        val operation: String,
        val precondition: String? = null,
        val group: String? = null,
    )

    private fun calc(
        name: String,
        target: String,
        operation: String,
        precondition: String? = null,
        group: String? = null,
    ) = Calc(name, target, operation, precondition, group)

    private fun declaration(calc: Calc, id: Int): String {
        val guard = calc.precondition?.let { """"precondition":"$it",""" } ?: ""
        return """{"type":"Computation","id":"c$id","name":"${calc.name}","Computation":{
             "computedFieldRelPath":"${calc.target}",
             "computationAlternatives":[{$guard"operation":"${calc.operation}"}],
             "errorMessage":[{"locale":"en_US","text":"x"}]}}"""
    }

    /** A courier depot carrying [calcs], each in the group its own row names. */
    private fun model(calcs: List<Calc>): String {
        val atRoot = calcs.withIndex().filter { it.value.group == null }
            .joinToString("") { (index, calc) -> "," + declaration(calc, index) }
        val inCrates = calcs.withIndex().filter { it.value.group == "Crates" }
            .joinToString("") { (index, calc) -> "," + declaration(calc, index) }
        return """
            {"header":{"id":"depot","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Depot","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Weight","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f1","name":"Volume","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f2","name":"Base","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Field","id":"f3","name":"Declared","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Crates","Group":{"repeatability":10,"elements":[
                  {"type":"Field","id":"f4","name":"Cost","Field":{"fieldType":{"type":"NumberType"}}},
                  {"type":"Field","id":"f5","name":"Units","Field":{"fieldType":{"type":"NumberType"}}}
                  $inCrates
                ]}}
                $atRoot
              ]}}
             ]}}}
        """.trimIndent()
    }
}
