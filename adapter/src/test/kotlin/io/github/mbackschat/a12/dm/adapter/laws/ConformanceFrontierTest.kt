package io.github.mbackschat.a12.dm.adapter.laws

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.mbackschat.a12.dm.adapter.KernelAdapter
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.interpreter.conformance.InterpreterReplay
import io.github.mbackschat.a12.dm.interpreter.conformance.Op
import io.github.mbackschat.a12.dm.interpreter.conformance.Placement
import io.github.mbackschat.a12.dm.interpreter.conformance.PlacementKind
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.parse.parseCondition
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.ConditionLanguage
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import java.io.File
import java.security.MessageDigest

/** O2 and O3 over M1's admitted inputs; the finite matrix is CONFORMANCE-PLAN's M2 acceptance matrix. */
class ConformanceFrontierTest {
    private val validator = RuleValidator()

    @Test
    fun numericFrontierExceptionsSeparateSignTokensFromTheirMagnitude() {
        val witnesses = mapOf(
            "corpus/models/order-code-length-ge.json#/Order/CodeLenGe" to 16,
            "corpus/models/order-code-length-lt.json#/Order/CodeLenLt" to 8,
            "corpus/models/order-stock-ne-one.json#/Order/StockNeOne" to 22,
            "cli/src/test/resources/models/order-variant.dm.json#/Order/DeliveryNotBeforeOrder" to 20,
            "catalog#GreaterThan" to 2, "catalog#MinValue" to 22, "catalog#DifferenceInSeconds" to 15,
            "catalog#AddSeconds" to 8, "catalog#MonthFromDate" to 14, "catalog#HoursFromTime" to 24,
            "catalog#MinutesFromTime" to 20, "catalog#FieldValueAsNumber" to 29, "catalog#FirstFilledValue" to 24,
        )
        val acceptedRows = resource("accepted.tsv").lines().filter { it.isNotEmpty() }
        val accepted = acceptedRows.associate { it.substringBefore('\t') to it.substringAfter('\t') }
        assertEquals(acceptedRows.size, accepted.size, "duplicate accepted-input identities")
        val misses = mutableListOf<String>()
        var count = 0
        for (seed in NearMissPopulation.seeds().filter { it.label in witnesses }) {
            val ordinal = witnesses.getValue(seed.label)
            val edit = NearMissPopulation.edits(seed)[ordinal - 1]
            val id = "${seed.label}#$ordinal:${edit.family}"
            val original = validator.inject(seed.host, edit.rule)
            assertEquals(accepted.getValue(id), digest(original), id)
            val text = edit.rule.errorCondition()
            val normalized = text.replace(Regex("-\\s+(?=[0-9])"), "-")
            println("M3-N $id: $text")
            if (text == normalized) {
                misses += "$id: no sign-space boundary in $text"
                continue
            }
            assertEquals(emptySet<String>(), refusals(seed.host, edit.rule), id)
            val control = edit.rule.withErrorCondition(normalized)
            assertEquals(emptySet<String>(), refusals(seed.host, control), "$id control")
            assertEquals(parseCondition(normalized, edit.rule.groupPath()), parseCondition(text, edit.rule.groupPath()), id)
            assertDoesNotThrow({ parseCondition(normalized, edit.rule.groupPath()) }, "$id control parse")
            assertDoesNotThrow({ ModelLoader.load(original) }, "$id load")
            val changed = validator.inject(seed.host, control)
            for (document in documents(original)) {
                val locale = CorpusShrinkSupport.modelLocale(original)
                assertEquals(CorpusEngines.groovyDynamic(original, document, Op.ValidateFull, CLOCK, locale),
                    CorpusEngines.groovyDynamic(changed, document, Op.ValidateFull, CLOCK, locale), "$id whitespace")
                assertEquals(InterpreterReplay.run(changed, document, Op.ValidateFull, CLOCK),
                    InterpreterReplay.run(original, document, Op.ValidateFull, CLOCK), "$id interpreter whitespace")
            }
            count++
        }
        assertEquals(emptyList<String>(), misses)
        assertEquals(13, count)
    }

    @Test
    fun numericTokenBoundariesDistinguishWhitespaceFromComments() {
        val model = BoundModel.ofJson(DocumentModelDraft.named("numeric-boundaries").locale("en_US")
            .rootGroup("Order") { it.numberField("Amount") { n -> n.fractionDigits(1) } }.build().toJson())
        val amount = model.num("/Order/Amount")
        val template = model.rule(model.group("/Order")).name("Boundary").errorField(amount)
            .`when`(amount.gt(java.math.BigDecimal("-2.5"))).code("BOUNDARY")
            .message(Dsl.en("amount above threshold")).build().draft()
        val documents = listOf(emptyList<Placement>()) + listOf("-3", "-2.5", "-2", "3").map {
            listOf(Placement(PlacementKind.FIELD, amount.field().path().toString(), listOf(1, 1), it))
        }
        val misses = mutableListOf<String>()
        var count = 0
        for (negative in listOf(false, true)) {
            val literal = if (negative) "-2.5" else "2.5"
            val rule = template.withErrorCondition(template.errorCondition().replace("-2.5", literal))
            val json = validator.inject(model.toJson(), rule)
            assertEquals(emptySet<String>(), refusals(model.toJson(), rule), literal)
            val expected = documents.map { document ->
                CorpusEngines.groovyDynamic(json, document, Op.ValidateFull, CLOCK)
            }
            assertEquals(listOf(emptyList<String>(), emptyList(), emptyList(),
                if (negative) listOf("BOUNDARY|VALUE_ERROR|/Order[1]/Amount") else emptyList(),
                listOf("BOUNDARY|VALUE_ERROR|/Order[1]/Amount")), expected, literal)
            val sites = if (negative) listOf(1, 2, 3) else listOf(1, 2)
            val rows = listOf("" to literal) + sites.flatMap { at ->
                listOf(" ", "\t", "\n", "\r", "\u000C").map { gap ->
                    "$at:${gap[0].code}" to (literal.take(at) + gap + literal.drop(at))
                }
            }
            for ((label, edited) in rows) {
                val changed = rule.withErrorCondition(rule.errorCondition().replace(literal, edited))
                val actual = refusals(model.toJson(), changed)
                if (actual.isNotEmpty()) misses += "$literal/$label: $actual"
                else {
                    val changedJson = validator.inject(model.toJson(), changed)
                    assertEquals(parseCondition(rule.errorCondition(), rule.groupPath()),
                        parseCondition(changed.errorCondition(), changed.groupPath()), "$literal/$label AST")
                    for ((i, document) in documents.withIndex()) {
                        assertEquals(expected[i], CorpusEngines.groovyDynamic(changedJson, document, Op.ValidateFull, CLOCK), "$literal/$label")
                        assertEquals(expected[i], CorpusEngines.javaStatic(changedJson, document, Op.ValidateFull, CLOCK), "$literal/$label Java")
                        val interpreted = InterpreterReplay.run(changedJson, document, Op.ValidateFull, CLOCK)
                        assertEquals(emptyList<String>(), interpreted.unsupported, "$literal/$label")
                        assertEquals(expected[i], interpreted.signatures, "$literal/$label interpreter")
                    }
                }
                count++
            }
        }
        for (at in listOf(1, 2, 3)) {
            val literal = "-2.5"
            val changed = template.withErrorCondition(template.errorCondition()
                .replace(literal, literal.take(at) + ";; boundary\n" + literal.drop(at)))
            val actual = refusals(model.toJson(), changed)
            if (actual.isEmpty()) misses += "comment@$at unexpectedly admitted"
            count++
        }
        assertEquals(30, count)
        assertEquals(emptyList<String>(), misses)
    }

    @Test
    fun aTypedPresenceRuleDistinguishesTheSameDocumentWithAndWithoutItsField() {
        val model = BoundModel.ofJson(DocumentModelDraft.named("frontier-control").locale("en_US")
            .rootGroup("Order") { root -> root.stringField("Reference") {}.stringField("Other") {} }.build().toJson())
        val field = model.field("/Order/Reference")
        fun jsonOf(condition: Condition) = model.add(model.rule(model.group("/Order")).name("Observed").errorField(field)
            .`when`(condition).code("OBSERVED").message(Dsl.en("reference present")).build()).toJson()
        val json = jsonOf(field.filled())
        assertEquals(emptySet<String>(), kernelModelClasses(json))
        val empty = emptyList<Placement>()
        val filled = listOf(Placement(PlacementKind.FIELD, field.path().toString(), listOf(1, 1), "A"))
        for ((document, expected) in listOf(empty to emptyList(),
                filled to listOf("OBSERVED|VALUE_ERROR|/Order[1]/Reference"))) {
            assertEquals(expected, CorpusEngines.groovyDynamic(json, document, Op.ValidateFull, CLOCK))
            assertEquals(expected, CorpusEngines.javaStatic(json, document, Op.ValidateFull, CLOCK))
            val interpreted = InterpreterReplay.run(json, document, Op.ValidateFull, CLOCK)
            assertEquals(emptyList<String>(), interpreted.unsupported)
            assertEquals(expected, interpreted.signatures)
        }
        val altered = jsonOf(field.notFilled())
        assertEquals(emptySet<String>(), kernelModelClasses(altered))
        val oracles = Oracles()
        oracles.compareDocument(json, altered, filled, "polarity")
        assertEquals("", oracles.runtime.render())
        assertEquals("", oracles.strategies.render())
        assertEquals(1, oracles.authoring.counts.values.sum(), "re-admitted polarity loss must be detected")
        val wider = jsonOf(field.filled().or(model.field("/Order/Other").filled()))
        assertEquals(emptySet<String>(), kernelModelClasses(wider))
        val path = "/Order/Observed"
        val before = KernelAdapter.ofJson(wider)
        val after = KernelAdapter.ofJson(json)
        val projection = Frontier()
        projection.compareProjection(before.referencedFields(path), after.referencedFields(path), "first", "first", "reference")
        projection.compareProjection(after.referencedFields(path), after.referencedFields(path), "first", "second", "render")
        assertEquals(mapOf("REFERENCE_LOSS" to 1, "NON_CANONICAL" to 1), projection.counts)
    }

    @Test
    fun admittedInputsHaveOnlyLedgeredRuntimeAndAuthoringDifferences() {
        val oracles = Oracles()
        val runtime = oracles.runtime
        val authoring = oracles.authoring
        val strategies = oracles.strategies
        val accepted = mutableListOf<String>()
        var examined = 0
        var seeds = 0
        var documents = 0
        var unavailableDocuments = 0
        var groovyMessages = 0
        for (seed in NearMissPopulation.seeds()) {
            if (refusals(seed.host, seed.rule).isNotEmpty()) continue
            seeds++
            val edits = listOf(NearMissPopulation.Edit("as written", seed.rule, NearMissPopulation.Family.AS_WRITTEN)) +
                NearMissPopulation.edits(seed)
            for ((ordinal, edit) in edits.withIndex()) {
                examined++
                if (refusals(seed.host, edit.rule).isNotEmpty()) continue
                val id = "${seed.label}#$ordinal:${edit.family}"
                val json = validator.inject(seed.host, edit.rule)
                accepted += "$id\t${digest(json)}"
                val rendered = authoring.capture(id, "READ") { readBack(seed, edit.rule, json, id, authoring) }
                val scenarios = runtime.capture(id, "LOAD_OR_SEED") { documents(json) }
                if (scenarios == null) {
                    unavailableDocuments += 5
                    if (rendered != null) authoring.add("DOCUMENTS_UNAVAILABLE", id)
                    strategies.add("DOCUMENTS_UNAVAILABLE", id)
                    continue
                }
                assertEquals(5, scenarios.size, "$id must contribute all Document scenarios")
                for ((scenario, placements) in scenarios.withIndex()) {
                    documents++
                    groovyMessages += oracles.compareDocument(json, rendered, placements, "$id:document$scenario")
                }
                println("M2 ${accepted.size}: $id")
            }
        }
        OUTPUT.mkdirs()
        val admission = accepted.joinToString("\n", postfix = "\n")
        File(OUTPUT, "accepted.tsv").writeText(admission)
        runtime.write("runtime")
        authoring.write("authoring")
        strategies.write("strategies")
        File(OUTPUT, "summary.txt").writeText("seeds=$seeds examined=$examined accepted=${accepted.size} " +
            "documents=$documents groovyMessages=$groovyMessages\n")
        assertEquals(139, seeds)
        assertEquals(9362, examined)
        assertEquals(346, accepted.size)
        assertEquals(accepted.size * 5, documents + unavailableDocuments, "every admitted input reaches all scenarios")
        assertEquals(accepted.size, accepted.map { it.substringBefore('\t') }.distinct().size)
        assertTrue(groovyMessages > 0, "the normative engine emitted no signature across the population")
        assertEquals(resource("accepted.tsv"), admission, "admission changed; inspect $OUTPUT")
        runtime.verify("runtime")
        authoring.verify("authoring")
        strategies.verify("strategies")
    }

    @Test
    fun frontierLedgerCannotExchangeWhichCaseFails() {
        val original = Frontier().apply {
            add("UNSUPPORTED", "order-a")
            add("UNSUPPORTED", "order-b")
        }
        val reordered = Frontier().apply {
            add("UNSUPPORTED", "order-b")
            add("UNSUPPORTED", "order-a")
        }
        val exchanged = Frontier().apply {
            add("UNSUPPORTED", "order-a")
            add("UNSUPPORTED", "order-c")
        }
        val duplicated = Frontier().apply {
            add("UNSUPPORTED", "order-a")
            add("UNSUPPORTED", "order-a")
        }
        assertEquals(original.render(), reordered.render())
        assertEquals(original.counts, exchanged.counts)
        assertEquals(original.counts, duplicated.counts)
        assertNotEquals(original.render(), exchanged.render())
        assertNotEquals(original.render(), duplicated.render())
        original.add("UNSUPPORTED", "order-a")
        reordered.add("UNSUPPORTED", "order-b")
        assertEquals(original.counts, reordered.counts)
        assertNotEquals(original.render(), reordered.render(), "equal distinct IDs must not erase multiplicity")
    }

    @Test
    fun frontierComparisonRetainsMultiplicityAndBothDirections() {
        val frontier = Frontier()
        val signature = "REQUIRED|VALIDATION_ERROR|/Order[1]/Reference"
        frontier.compare(listOf(signature), listOf(signature), "agreement")
        assertEquals("", frontier.render())
        frontier.compare(listOf(signature), emptyList(), "missing")
        frontier.compare(emptyList(), listOf(signature), "extra")
        frontier.compare(listOf(signature, signature), listOf(signature), "duplicate")
        assertEquals(2, frontier.counts.getValue("SIGNATURES\t$signature\t(none)"))
        assertEquals(1, frontier.counts.getValue("SIGNATURES\t(none)\t$signature"))
        frontier.add("UNSUPPORTED", "unsupported", "construct")
        assertEquals(1, frontier.counts.getValue("UNSUPPORTED"))
        assertEquals(null, frontier.capture<String>("failure", "READ") { throw IllegalArgumentException("control") })
        assertEquals(1, frontier.counts.getValue("READ_EXCEPTION:java.lang.IllegalArgumentException"))
    }

    private fun readBack(seed: NearMissPopulation.Seed, rule: DraftRule, json: String,
                         id: String, frontier: Frontier): String? {
        val adapter = KernelAdapter.ofJson(json)
        val path = "${rule.groupPath()}/${rule.name()}"
        val read = adapter.read(path)
        if (read !is ReadResult.Converted) {
            val kind = when (read) {
                is ReadResult.Opaque -> "OPAQUE"
                is ReadResult.Refused -> "REFUSED"
                else -> error("unhandled read result $read")
            }
            frontier.add(kind, id, read.toString())
            return null
        }
        val language = if (MAPPER.readTree(json).path("header").path("conditionLanguage").path("code")
                .asText() == "de_DE") ConditionLanguage.DE else ConditionLanguage.EN
        val text = RuntimeLaws.render(rule.groupPath(), read.condition(), language)
        val draft = rule.withErrorCondition(text)
        val rejected = refusals(seed.host, draft)
        if (rejected.isNotEmpty()) {
            frontier.add("RENDER_REFUSED:${rejected.sorted().joinToString(",")}", id, text)
            return null
        }
        val rendered = validator.inject(seed.host, draft)
        val after = KernelAdapter.ofJson(rendered)
        val reread = after.read(path)
        if (reread !is ReadResult.Converted) frontier.add("REREAD_NOT_TYPED", id, reread.toString())
        frontier.compareProjection(adapter.referencedFields(path), after.referencedFields(path), text,
            (reread as? ReadResult.Converted)?.let { RuntimeLaws.render(rule.groupPath(), it.condition(), language) }, id)
        return rendered
    }

    private fun documents(json: String): List<List<Placement>> {
        val model = ModelLoader.load(json)
        return listOf(emptyList<Placement>()) + (0..2).map { rows ->
            CorpusSeeding.seedPlacementsFixed(model, rows.toLong(), rows)
        } + listOf(CorpusSeeding.seedPlacements(model, 3L, 2, HostileValues))
    }

    private fun refusals(host: String, rule: DraftRule): Set<String> =
        validator.check(host, rule).diagnostics().filter { it.severity() == Severity.ERROR }.map { it.ledgerClass() }.toSet()

    private class Oracles {
        val runtime = Frontier()
        val authoring = Frontier()
        val strategies = Frontier()

        fun compareDocument(json: String, rendered: String?, placements: List<Placement>, caseId: String): Int {
            val locale = CorpusShrinkSupport.modelLocale(json)
            val kernel = runtime.capture(caseId, "KERNEL") {
                CorpusEngines.groovyDynamic(json, placements, Op.ValidateFull, CLOCK, locale)
            }
            val ours = runtime.capture(caseId, "INTERPRETER") {
                InterpreterReplay.run(json, placements, Op.ValidateFull, CLOCK)
            }
            if (ours != null) {
                if (ours.unsupported.isNotEmpty()) runtime.add("UNSUPPORTED", caseId, ours.unsupported.joinToString("; "))
                if (kernel != null) runtime.compare(kernel, ours.signatures, caseId)
            }

            val java = strategies.capture(caseId, "JAVA") {
                CorpusEngines.javaStatic(json, placements, Op.ValidateFull, CLOCK, locale)
            }
            if (kernel != null && java != null) strategies.compare(kernel, java, caseId)
            if (kernel == null) strategies.add("GROOVY_UNAVAILABLE", caseId)
            if (rendered != null) {
                val after = authoring.capture(caseId, "RENDERED_RUNTIME") {
                    CorpusEngines.groovyDynamic(rendered, placements, Op.ValidateFull, CLOCK, locale)
                }
                if (kernel != null && after != null) authoring.compare(kernel, after, caseId)
                if (kernel == null) authoring.add("ORIGINAL_RUNTIME_UNAVAILABLE", caseId)
            }
            return kernel?.size ?: 0
        }
    }

    private class Frontier {
        private val cases = sortedMapOf<String, MutableList<String>>()
        val counts: Map<String, Int> get() = cases.mapValues { it.value.size }
        private val examples = mutableListOf<String>()

        fun add(key: String, id: String, detail: String = "") {
            cases.getOrPut(key) { mutableListOf() }.add(id)
            examples += "$key\t$id\t${detail.replace("\n", "\\n")}"
        }

        fun compare(expected: List<String>, actual: List<String>, id: String) {
            if (expected.sorted() == actual.sorted()) return
            val missing = expected.toMutableList()
            val extra = actual.filterNot { missing.remove(it) }
            add("SIGNATURES\t${column(missing)}\t${column(extra)}", id)
        }

        fun compareProjection(before: Set<String>, after: Set<String>, first: String, second: String?, id: String) {
            if (before != after) add("REFERENCE_LOSS", id, first)
            if (second != null && first != second) add("NON_CANONICAL", id, first)
        }

        fun <T> capture(id: String, stage: String, action: () -> T): T? = try { action() } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.last()
            add("${stage}_EXCEPTION:${cause.javaClass.name}", id, cause.message.orEmpty())
            null
        }

        fun render(): String = cases.entries.joinToString("") { (key, ids) ->
            "${ids.size}\t$key\t${MAPPER.writeValueAsString(ids.sorted())}\n"
        }
        fun write(name: String) {
            File(OUTPUT, "$name.tsv").writeText(render())
            File(OUTPUT, "$name-examples.tsv").writeText(examples.joinToString("\n"))
        }
        fun verify(name: String) = assertEquals(resource("$name.tsv"), render(), "$name changed; inspect $OUTPUT")
        private fun column(values: List<String>) = values.sorted().joinToString(",").ifEmpty { "(none)" }
    }

    private companion object {
        const val CLOCK = "2026-09-27T12:00:00"
        val OUTPUT = File("build/conformance-frontier")
        val MAPPER = ObjectMapper()
        fun resource(name: String): String = checkNotNull(ConformanceFrontierTest::class.java
            .getResource("/conformance-frontier/$name")).readText()
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
