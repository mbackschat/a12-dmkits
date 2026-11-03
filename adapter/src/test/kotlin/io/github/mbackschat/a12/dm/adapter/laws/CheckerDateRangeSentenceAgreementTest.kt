package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `DateRangeType`'s three verdicts on the range format A12 composes from `format + rangeSeparator + format`
 * (KERNEL-FINDINGS KF581), against the portable checker, field by field with the path each attaches to.
 *
 * The grid crosses every format the range table names, an omitted, an empty and two blank formats with an omitted
 * separator, the sweep's seven and ten further separators. That separates the blank composite (the coded
 * `MVK_FORMAT_DATE_RANGE_MISSING`), the separator's own sentence (which trims, so `/ ` passes it), and the pair
 * sentence (which does not, so `/ ` still draws it). Beside the grid: a field the default variant deletes, and the
 * two carriers the checker leaves to A12 with a coverage gap — a supplementary character, where A12 answers with its
 * charset sentence alone, and a format the range table does not name, where the checker states the separator's
 * sentence and no pair verdict.
 *
 * Own domain (an insurance policy's cover period), built by editing the drafted model's JSON, because the typed
 * builder refuses every defective range here outright.
 */
class CheckerDateRangeSentenceAgreementTest {

    private data class Row(val format: String?, val separator: String?)

    @Test
    fun theCheckerStatesA12sRangeVerdictsOnEveryPair() {
        val grid = FORMATS.flatMap { format -> SEPARATORS.map { Row(format, it) } }
        val observed = grid.associateWith { model(it).let { json -> kernel(json) to ours(json) } }
        assertEquals(FORMATS.size * SEPARATORS.size, observed.size, "every grid member is a distinct model")
        assertEquals(observed.mapValues { (_, verdict) -> verdict.first to verdict.first }, observed, "each row as (A12, ours)")
        // A12's own answer on the rows that separate the three verdicts, so the agreement cannot follow a drift on
        // both sides.
        assertEquals(linkedMapOf(
            Row("yyyy-MM-dd", "/") to emptyList(),
            Row("yyyy-MM-dd", null) to emptyList(),
            Row("MM", "") to emptyList(),
            Row("dd.MM.yyyy", "/") to listOf(FORMAT),
            Row("yyyy-MM-dd", "x") to listOf(FORMAT, SEPARATOR),
            Row("yyyy-MM-dd", "/ ") to listOf(FORMAT),
            Row("MM", "\u00A0") to listOf(FORMAT, SEPARATOR),
            Row(null, null) to listOf(FORMAT),
            // Both members empty read as both absent on kernel 31.1.1 (KERNEL-FINDINGS KF658), so this composes
            // `null/null` and is the range-format verdict; a blank pair still composes a blank one.
            Row("", "") to listOf(FORMAT),
            Row(" ", "\t") to listOf(MISSING),
            Row("", "/") to listOf(FORMAT),
        ).mapValues { (_, classes) -> classes.map { "$it@$FIELD" }.sorted() },
            observed.filterKeys { it in PINNED }.mapValues { it.value.first }.let { pinned -> PINNED.associateWith { pinned.getValue(it) } })
    }

    @Test
    fun theCarriersLeftToA12DrawAGapAndNoPairVerdict() {
        for (row in listOf(Row("MM\uD83D\uDE00", "/"), Row("MM", "\uD83D\uDE00"), Row("yyyy", "x\uD83D\uDE00"),
            Row("MM.yyyy", "/"), Row("MM.yyyy", "x"), Row("yyyy-MM-dd", "~"))) {
            val json = model(row)
            val report = check(ModelSource(json))
            val ours = report.findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }
            val a12 = kernel(json)
            assertTrue(a12.isNotEmpty(), "$row: A12 refuses it")
            assertTrue(a12.containsAll(ours), "$row: what the checker states is A12's, $ours against $a12")
            assertTrue("$FORMAT@$FIELD" !in ours, "$row: no pair verdict outside the measured domain")
            assertEquals(1, report.gaps.count { it.at == FIELD }, "$row: the silence is disclosed")
        }
    }

    /** A member A12 cannot bind makes the source unreadable, so a refused pair beside it reaches no verdict. */
    @Test
    fun aRangeA12CannotReadDrawsNoVerdict() {
        val tree = mapper.readTree(model(Row("dd.MM.yyyy", "x")))
        (tree.at("/content/modelRoot/rootGroups/0/Group/elements/1/Field/fieldType/DateRangeType") as ObjectNode)
            .put("youngerThan1900Check", "yes")
        val json = mapper.writeValueAsString(tree)
        assertEquals(setOf("CK_MODEL_NOT_BUILDABLE"), kernelModelClasses(json), "A12 cannot read the source")
        assertEquals(listOf("CK_MODEL_NOT_BUILDABLE@$FIELD"), ours(json))
    }

    private val oracle = KernelConsistencyOracle()
    private val mapper = ObjectMapper()

    private val base: String = DocumentModelDraft.named("cover-periods").locale("en_US")
        .rootGroup("Policy") { policy -> policy.stringField("Reference") { }.stringField("Cover") { } }
        .build().toJson()

    private fun model(row: Row): String {
        val tree = mapper.readTree(base)
        val field = tree.at("/content/modelRoot/rootGroups/0/Group/elements/1/Field") as ObjectNode
        val body = field.putObject("fieldType").put("type", "DateRangeType").putObject("DateRangeType")
        row.format?.let { body.put("format", it) }
        row.separator?.let { body.put("rangeSeparator", it) }
        return mapper.writeValueAsString(tree)
    }

    private fun kernel(json: String): List<String> = oracle.checkConsistency(json, ReferenceResolvers.selfContained())
        .map { notification ->
            val message = notification.message().trim()
            val at = Regex("^Field with path '([^']*)': ").find(message)?.groupValues?.get(1) ?: "(unattached)"
            val code = legibleNotificationClass(message)
            "$code@$at"
        }.sorted()

    private fun ours(json: String): List<String> =
        check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { "${it.code}@${it.at}" }.sorted()

    private companion object {
        const val FIELD = "/Policy/Cover"
        const val FORMAT = "RK_DATE_RANGE_FORMAT_INVALID"
        const val SEPARATOR = "RK_DATE_RANGE_SEPARATOR_INVALID"
        const val MISSING = "MVK_FORMAT_DATE_RANGE_MISSING"
        val FORMATS = listOf("MM", "MM-dd", "dd.MM", "dd.MM.yyyy", "yyyy", "yyyy-MM", "yyyy-MM-dd", "HH:mm:ss", "MMdd",
            "ddMM", "dd.MM.", "yyyy-MM-dd'T'HH:mm:ss", "yyyyMM", "yyyyMMdd", null, "", " ", "\t")
        val SEPARATORS = listOf(null, "/", "-", ".", "", " - ", ":", "_", " ", "\t", "x", "\u2013", "//", "/ ", " /",
            "/\t", "\u00A0", "|", "\u00E4", "ab")
        val PINNED = listOf(Row("yyyy-MM-dd", "/"), Row("yyyy-MM-dd", null), Row("MM", ""), Row("dd.MM.yyyy", "/"),
            Row("yyyy-MM-dd", "x"), Row("yyyy-MM-dd", "/ "), Row("MM", "\u00A0"), Row(null, null), Row("", ""),
            Row(" ", "\t"), Row("", "/"))
    }
}
