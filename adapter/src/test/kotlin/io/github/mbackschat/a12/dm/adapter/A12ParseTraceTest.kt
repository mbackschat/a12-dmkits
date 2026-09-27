package io.github.mbackschat.a12.dm.adapter

import com.mgmtp.a12.kernel.core.parser.internal.ParserService
import com.mgmtp.a12.kernel.core.parser.internal.error.MVKDefaultErrorStrategy
import com.mgmtp.a12.kernel.core.parser.internal.error.MVKParserError
import com.mgmtp.a12.kernel.core.parser.internal.grammar.gen.Lenient_ConditionParser
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule
import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblemReporter
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.IMVK_Service
import com.mgmtp.a12.kernel.md.model.a12internal.services.DocumentModelService
import com.mgmtp.a12.kernel.md.model.a12internal.services.ElementContainer
import com.mgmtp.a12.kernel.md.serializer.model.internal.service.ExternalDocumentModelSerializerService
import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation
import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation.Family
import io.github.mbackschat.a12.dm.adapter.laws.HarvestedSyntaxPopulation
import io.github.mbackschat.a12.dm.adapter.laws.FieldReadPopulation
import io.github.mbackschat.a12.dm.adapter.laws.ledgerClass
import io.github.mbackschat.a12.dm.consistency.syntax.ConditionLanguage
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxEntry
import io.github.mbackschat.a12.dm.consistency.syntax.SyntaxFailureKind
import io.github.mbackschat.a12.dm.consistency.syntax.shapeFailure
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.CharStream
import org.antlr.v4.runtime.InputMismatchException
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.LexerNoViableAltException
import org.antlr.v4.runtime.NoViableAltException
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.DecisionState
import org.antlr.v4.runtime.misc.Interval
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import io.github.mbackschat.a12.dm.adapter.laws.ParserCorpora
import io.github.mbackschat.a12.dm.consistency.syntax.lexicalFailure
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * A12's own condition parser, instrumented, on every near-miss condition: plan item R2 of
 * [CONFORMANCE-PLAN](../../../../../../../../../../docs/CONFORMANCE-PLAN.md), which confirms the source-read parse
 * model of [CHECKER-RECONSTRUCTION-PROPOSAL](../../../../../../../../../../docs/CHECKER-RECONSTRUCTION-PROPOSAL.md)
 * on A12 itself before the checker's parser is rebuilt from it.
 *
 * **Why an instrument.** A12 keeps nothing of a syntax failure but a message and a span: `MVKParserError` has no
 * cause, and the parse tree is dropped after a failure. So the trace records at the moment of failure.
 * `ParserService.checkSnippet`'s init hook runs after A12 installs its error strategy and before the parse, and
 * installs a subclass that records the recognition exception and then defers to A12's own report, so the
 * behaviour under test is unchanged. With `bedingung_start` it is the parser class, lexer, strictness and entry
 * rule `checkConsistency` uses, without the post-parse checks; the rule context is the seed's probe rule as
 * written, which an edit of its condition does not change.
 *
 * **What it locks** is each claim of the proposal's §2.3–§2.4 and Appendix B that a trace can falsify, over the
 * whole population, and that the instrument reproduces A12's production verdict on every input. A signature the
 * model does not predict fails the run, and is fixed in the model, not here. The signature counts and examples
 * go to `build/parse-trace/report.tsv`.
 */
class A12ParseTraceTest {

    private val validator = RuleValidator()

    @Test
    fun completedFieldReadsFailBeforeTheirEnclosingConstructs() {
        val rows = FieldReadPopulation.rows()
        assertEquals(22, rows.size)
        assertEquals(2, rows.count { it.expected == null })
        val service = mvkService(validator.inject(FieldReadPopulation.host, rows.first().rule))
        val rule = (service.iec as ElementContainer).allRules.single { it.fullName == "/Order/ReadProbe" }
        val observed = rows.map { row ->
            val observed = trace(service, rule, row.rule.errorCondition())
            Triple(row.rule.errorCondition(), observed.code, a12Classes(FieldReadPopulation.host, row.rule))
        }
        assertEquals(rows.map { Triple(it.rule.errorCondition(), it.expected, setOfNotNull(it.expected)) }, observed)
    }

    @Test
    fun aFieldReadDemandsTheNextTokenBeforeItsAction() {
        val rows = FieldReadPopulation.boundaryRows()
        assertEquals(8, rows.size)
        assertEquals(1, rows.count { it.expected == null })
        val service = mvkService(validator.inject(FieldReadPopulation.host, rows.last().rule))
        val rule = (service.iec as ElementContainer).allRules.single { it.fullName == "/Order/ReadProbe" }
        val observations = rows.map { row ->
            val text = row.rule.errorCondition()
            val measured = trace(service, rule, text)
            Triple(text, measured.code, a12Classes(FieldReadPopulation.host, row.rule)) to measured.problemRange
        }
        assertEquals(rows.map { Triple(it.rule.errorCondition(), it.expected, setOfNotNull(it.expected)) },
            observations.map { it.first })
        observations.filter { it.first.second == "MVK_NO_WILDCARDS_ALLOWED" }.forEach { (row, range) ->
            assertEquals(ProblemRange(1, 1, row.first.indexOf('['), row.first.indexOf('[') + 1), range, row.first)
        }
    }


    @Test
    fun theCheckersParserAcceptsTheKernelMmCorpus() {
        val root = ParserCorpora.kernelMm()
        assumeTrue(root != null, "a12-kernel-mm not present — skipping checker parser sweep")
        sweepCorpus("kernel-mm", root!!)
    }

    @Test
    fun theCheckersParserAcceptsTheWorkspacesCorpus() {
        val dir = ParserCorpora.workspaces()
        assumeTrue(dir != null, "a12.corpus1.dir not set / absent — skipping checker parser sweep")
        sweepCorpus("workspaces", dir!!)
    }

    private fun sweepCorpus(label: String, root: Path) {
        val harvested = ParserCorpora.harvest(root)
        val unlexable = mutableListOf<String>()
        val refused = mutableListOf<String>()
        val unsupported = mutableSetOf<String>()
        var swept = 0
        var conditions = 0
        var operations = 0
        for ((text, code) in harvested.conditions) {
            val language = ConditionLanguage.of(code)
            if (language == null) {
                unsupported += code
                continue
            }
            if (lexicalFailure(text) != null) {
                unlexable += text
                continue
            }
            swept++; conditions++
            shapeFailure(text, language, SyntaxEntry.CONDITION)?.let {
                refused += "[$code] at ${it.at} found '${it.found}' in: $text"
            }
        }
        for ((text, code) in harvested.operations) {
            val language = ConditionLanguage.of(code)
            if (language == null) {
                unsupported += code
                continue
            }
            if (lexicalFailure(text) != null) {
                unlexable += text
                continue
            }
            swept++; operations++
            shapeFailure(text, language, SyntaxEntry.OPERATION)?.let {
                refused += "[$code] at ${it.at} found '${it.found}' in: $text"
            }
        }
        println(
            "[checker-parser-sweep] $label: ${harvested.conditions.size} conditions + " +
                "${harvested.operations.size} operations, $swept parsed, ${refused.size} refused, " +
                "${unlexable.size} unlexable",
        )
        // A corpus that yielded nothing would make the sweep pass while measuring nothing, and an empty
        // directory looks exactly like a corpus that parses perfectly. `swept` rather than the harvest size
        // is what carries that, because every exclusion below it also drains the population silently.
        assertTrue(conditions > 0 && operations > 0) { "$label put no text through the parser at all — the sweep would pass vacuously" }
        // A12 supports exactly two condition languages and refuses a model declaring a third, so a code the
        // checker does not know is a harvest defect — and dropping its text would shrink the sweep invisibly.
        assertTrue(unsupported.isEmpty()) {
            "$label declares condition language(s) the checker does not know: $unsupported"
        }
        assertTrue(refused.isEmpty()) {
            "$label: the checker's parser refused ${refused.size} text(s) the Kernel stores, which is an " +
                "OVER-REJECTION — widen the parser, never the expectation:\n" +
                refused.take(20).joinToString("\n") { "  $it" }
        }
        assertTrue(unlexable.isEmpty()) {
            "$label: ${unlexable.size} stored text(s) the checker's LEXER refuses, which it must not — this " +
                "is a tokenizer defect, not a parser one:\n" +
                unlexable.take(20).joinToString("\n") { "  ${it.take(200)}" }
        }
    }



    @Test
    fun scannerHostsExposeWhichCallsCanComplete() {
        val rows = HarvestedSyntaxPopulation.scannerRows()
        assertEquals(7, rows.size)
        val observations = rows.map { row ->
            val service = mvkService(row.model())
            val rule = (service.iec as ElementContainer).allRules.single()
            val measured = trace(service, rule, row.text)
            if (measured.failure?.kind != Kind.CHECK) assertPortable(row.text, measured)
            "${row.id}\t${measured.failure?.kind ?: "PARSED"}\t${measured.code}\t${measured.failure?.location}"
        }.joinToString("\n", postfix = "\n")
        File("build/parse-trace/scanner-hosts.tsv").apply { parentFile.mkdirs(); writeText(observations) }
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/scanner-trace.tsv")).readText(), observations)
    }

    @Test
    fun harvestedEntriesMatchTheKernelTraceRecord() {
        val rows = HarvestedSyntaxPopulation.fixedRows()
        assertEquals(77, rows.size)
        assertEquals(8, rows.count { it.entry == SyntaxEntry.OPERATION })
        val differences = mutableListOf<String>()
        val observed = rows.map { row ->
            val service = mvkService(row.model())
            val rule = (service.iec as ElementContainer).allRules.single()
            val kernel = trace(service, rule, row.text, row.entry)
            val kind = when (kernel.failure?.kind) {
                Kind.MISMATCH -> "MATCH"
                Kind.NO_VIABLE -> "DECISION"
                Kind.LEXICAL -> "LEXICAL"
                Kind.CHECK -> "CHECK"
                null -> "PARSED"
                else -> error("uncontracted failure: $kernel")
            }
            if (kind != "CHECK") {
                val portable = shapeFailure(row.text, row.language, row.entry)
                val actual = listOf(portable?.kind?.name ?: "PARSED", portable?.code,
                    portable?.let { Location(it.at, it.end, it.found) })
                val expected = listOf(kind, kernel.code, kernel.failure?.location)
                if (actual != expected) differences += "${row.id}: $expected vs $actual"
            }
            mapOf("id" to row.id, "kind" to kind, "code" to kernel.code,
                "start" to kernel.failure?.location?.start, "end" to kernel.failure?.location?.end,
                "found" to kernel.failure?.location?.text)
        }
        val mapper = ObjectMapper()
        val actual = File("build/parse-trace/harvested-verdicts.json").apply { parentFile.mkdirs() }
        mapper.writerWithDefaultPrettyPrinter().writeValue(actual, observed)
        assertEquals(emptyList<String>(), differences)
        val expected = checkNotNull(javaClass.getResource("/checker-near-miss/harvested-verdicts.json"))
        assertEquals(mapper.readTree(expected.readText()), mapper.readTree(actual), "the kernel record changed")
    }

    @Test
    fun computationsAreRecognizedInTheirActualJoinedText() {
        val model = DocumentModelDraft.named("order-computation-syntax").locale("en_US")
            .rootGroup("Order") { o -> o.stringField("Tier") { }
                .group("Billing") { b -> b.numberField("Total") { n -> n.fractionDigits(2) } } }.build().toJson()
        val guard = "FieldFilled(../Tier)"
        val rows = listOf(
            Triple(null, listOf(null to "42.00"), null),
            Triple(null, listOf(guard to "42.00 +"), "MVK_INCOMPLETE_INPUT"),
            Triple(null, listOf(guard to "42.00 +", guard to "42.00"), "MVK_UNEXPECTED_TOKEN"),
            Triple(null, listOf("FieldFilled(../Tier" to "42.00"), "MVK_EXPECTED_TOKEN_NOT_FOUND"),
            Triple(null, listOf("$guard And" to "42.00"), "MVK_UNEXPECTED_TOKEN"),
            Triple("$guard And", listOf(null to "42.00"), "MVK_UNEXPECTED_TOKEN"),
            Triple("", listOf(null to "42.00"), "MVK_UNEXPECTED_TOKEN"),
            Triple(null, listOf("$guard ;;note" to "42.00"), null),
            Triple(null, listOf(guard to ""), "MVK_INCOMPLETE_INPUT"),
        )
        val captured = rows.mapIndexed { index, (common, alternatives, expected) ->
            val draft = DraftComputation("/Order/Billing", "SyntaxProbe", "../Total", common,
                alternatives.map { (precondition, operation) -> DraftComputation.Alternative(precondition, operation) })
            val service = mvkService(validator.inject(model, draft))
            val rule = (service.iec as ElementContainer).allRules.single()
            val joined = rule.errorConditionText
            val measured = trace(service, rule, joined)
            assertEquals(expected, measured.code, "row $index: $joined")
            val portable = shapeFailure(joined, ConditionLanguage.EN, SyntaxEntry.CONDITION)
            assertEquals(expected, portable?.code, "row $index: $joined")
            assertEquals(measured.failure?.location, portable?.let { Location(it.at, it.end, it.found) }, "row $index")
            mapOf("common" to common, "alternatives" to alternatives.map { (pre, op) ->
                mapOf("precondition" to pre, "operation" to op) }, "joined" to joined)
        }
        val mapper = ObjectMapper()
        val actual = File("build/parse-trace/computation-inputs.json").apply { parentFile.mkdirs() }
        mapper.writerWithDefaultPrettyPrinter().writeValue(actual, captured)
        val expected = checkNotNull(javaClass.getResource("/checker-near-miss/computation-inputs.json")).readText()
        assertEquals(mapper.readTree(expected), mapper.readTree(actual))
    }

    @Test
    fun portableVerdictsMatchEveryUncensoredTrace() {
        val differences = mutableListOf<String>()
        var compared = 0
        var censored = 0
        for (seed in NearMissPopulation.seeds()) {
            if (a12Classes(seed.host, seed.rule).isNotEmpty()) continue
            val service = mvkService(validator.inject(seed.host, seed.rule))
            val probe = (service.iec as ElementContainer).allRules
                .single { it.fullName.endsWith("/${NearMissPopulation.PROBE}") }
            for (edit in NearMissPopulation.edits(seed)) {
                if (edit.family == Family.MESSAGE) continue
                val text = edit.rule.errorCondition()
                val kernel = trace(service, probe, text)
                if (kernel.failure?.kind == Kind.CHECK) { censored++; continue }
                compared++
                val portable = shapeFailure(text, ConditionLanguage.EN, SyntaxEntry.CONDITION)
                val expectedKind = when (kernel.failure?.kind) {
                    Kind.MISMATCH -> SyntaxFailureKind.MATCH
                    Kind.NO_VIABLE -> SyntaxFailureKind.DECISION
                    Kind.LEXICAL -> SyntaxFailureKind.LEXICAL
                    null -> null
                    else -> error("uncontracted trace: $kernel")
                }
                val expected = listOf(expectedKind, kernel.code, kernel.failure?.location)
                val actual = listOf(portable?.kind, portable?.code,
                    portable?.let { Location(it.at, it.end, it.found) })
                if (expected != actual) differences +=
                    "${text.replace("\n", "\\n")}\t$expected\t$actual\t${kernel.failure?.site()}"
            }
        }
        val report = File("build/parse-trace/syntax.tsv").apply { parentFile.mkdirs() }
        report.writeText(differences.joinToString("\n"))
        assertEquals(9063, compared + censored)
        assertTrue(compared > 1000)
        assertTrue(censored > 100)
        assertEquals(emptyList<String>(), differences.take(20), "${differences.size} differences in ${report.path}")
    }

    @Test
    fun tracedPositionsUseUtf16OffsetsAndPreserveOffendingText() {
        val model = DocumentModelDraft.named("order-trace-positions").locale("en_US")
            .rootGroup("Order") { it.numberField("Total") { n -> n.fractionDigits(2) } }.build().toJson()
        val draft = DraftRule.builder().groupPath("/Order").name(NearMissPopulation.PROBE)
            .errorEntityRelPath("../Total").errorCondition("FieldFilled(Total)")
            .errorCode(NearMissPopulation.PROBE_CODE).severity("ERROR").build()
        val service = mvkService(validator.inject(model, draft))
        val rule = (service.iec as ElementContainer).allRules.single()
        for (prefix in listOf("", "\r\n", ";;😀\r\n")) {
            assertEquals(null, trace(service, rule, prefix + "FieldFilled(Total)").code)
            for ((body, kind) in listOf("FieldFilled" to Kind.MISMATCH,
                    "FieldFilled(Total]" to Kind.MISMATCH, "[Total] > ٢" to Kind.LEXICAL)) {
                val text = prefix + body
                val expectedText = when (body) {
                    "FieldFilled" -> ""
                    "FieldFilled(Total]" -> "]"
                    else -> "٢"
                }
                val start = text.length - expectedText.length
                val actual = trace(service, rule, text)
                assertEquals(kind, actual.failure?.kind, text)
                assertEquals(Location(start, text.length, expectedText), actual.failure?.location, text)
            }
        }
    }

    @Test
    fun everyParseFailureHasTheShapeTheModelPredicts() {
        val tally = Tally()
        for (seed in NearMissPopulation.seeds()) {
            if (a12Classes(seed.host, seed.rule).isNotEmpty()) continue
            val service = mvkService(validator.inject(seed.host, seed.rule))
            val probe = (service.iec as ElementContainer).allRules
                .single { it.fullName.endsWith("/${NearMissPopulation.PROBE}") }
            for (edit in NearMissPopulation.edits(seed)) {
                if (edit.family == Family.MESSAGE) continue
                val condition = edit.rule.errorCondition()
                tally.add(condition, trace(service, probe, condition), a12Classes(seed.host, edit.rule))
            }
        }
        REPORT.apply { parentFile.mkdirs() }.writeText(tally.report())

        assertEquals(listOf<String>(), tally.productionMismatches.take(20),
            "the trace must see the class A12's production check reports")
        assertEquals(listOf<String>(), tally.modelDefects.take(20),
            "signatures the model does not predict (all in ${REPORT.path})")
        // Floors, set below this class's first run: each answers whether one kind of failure was reached at all,
        // so a population that stopped producing it cannot pass as a confirmed model.
        assertTrue(tally.count(Kind.MISMATCH) >= 500, "mismatch failures traced: ${tally.kinds}")
        assertTrue(tally.count(Kind.NO_VIABLE) >= 500, "no-viable-alternative failures traced: ${tally.kinds}")
        assertTrue(tally.count(Kind.CHECK) >= 100, "check-action failures traced: ${tally.kinds}")
        // Witnesses of what KF493 states, each well below the first run's count: the entity spec failing deep in a
        // path, the simple condition failing past its first token, and every switch the model says can fail.
        assertTrue(tally.witnessed(56, 5) >= 10, "deep entity-spec failures: ${tally.witnesses[56]}")
        assertTrue(tally.witnessed(6, 1) >= 5, "simple-condition failures past the first token: ${tally.witnesses[6]}")
        assertEquals(FALLIBLE_SWITCHES, tally.switchesFailed, "the switches observed failing")
    }

    /**
     * The rows a review of batch S predicted from the model (the proposal's Appendix C), measured: each names the
     * exception kind A12's parser fails with and the class it reports, or, for an admitted control, that the
     * slot takes the shape without a syntax failure. The walked path that stops at `.` separates the model from
     * [KF491](../../../../../../../../../../docs/KERNEL-FINDINGS.md#kf491)'s reading that a walked path is resolved
     * segment by segment as it is read: that reading predicts `MVK_INVALID_ENTITY` for `NoSuch`, the model a
     * mismatch at `)` before `NoSuch`'s segment check runs.
     */
    @Test
    fun theReviewsPredictedRowsFailWhereTheModelSays() {
        val order = DocumentModelDraft.named("order-trace").locale("en_US")
            .rootGroup("Order") { o -> o
                .stringField("Code") { }
                .stringField("Reference") { }
                .numberField("Total") { n -> n.fractionDigits(2) }
                .numberField("Premium") { n -> n.fractionDigits(2) }
                .numberField("Qty") { n -> n.fractionDigits(0) }
                .dateField("Placed") { d -> d.format("yyyy-MM-dd") }
                .dateField("Estimated") { d -> d.format("dd.MM.yyyy").datePrecision(DatePrecision.DAY_OPTIONAL) }
                .group("Items") { i -> i.repeatable(9).numberField("Count") { n -> n.fractionDigits(0) }
                    .group("Lots") { l -> l.repeatable(9).numberField("Qty") { n -> n.fractionDigits(0) } } } }
            .build().toJson()
        val probe = DraftRule.builder().groupPath("/Order").name(NearMissPopulation.PROBE).errorEntityRelPath("../Total")
            .errorCondition("FieldFilled(Total)").errorCode(NearMissPopulation.PROBE_CODE).severity("ERROR").build()
        val service = mvkService(validator.inject(order, probe))
        val rule = (service.iec as ElementContainer).allRules
            .single { it.fullName.endsWith("/${NearMissPopulation.PROBE}") }
        val observed = PREDICTED_ROWS.map { (condition, _) ->
            val trace = trace(service, rule, condition)
            if (trace.failure?.kind != Kind.CHECK) assertPortable(condition, trace)
            condition to (trace.failure?.let { "${it.site()} ${trace.code}" } ?: "PARSED")
        }
        assertEquals(PREDICTED_ROWS, observed)
    }

    private fun assertPortable(text: String, measured: Trace) {
        val failure = shapeFailure(text, ConditionLanguage.EN, SyntaxEntry.CONDITION)
        val kind = when (measured.failure?.kind) {
            Kind.MISMATCH -> SyntaxFailureKind.MATCH
            Kind.NO_VIABLE -> SyntaxFailureKind.DECISION
            Kind.LEXICAL -> SyntaxFailureKind.LEXICAL
            null -> null
            else -> error("censored or uncontracted trace: $measured")
        }
        assertEquals(kind, failure?.kind, text)
        assertEquals(measured.code, failure?.code, text)
        assertEquals(measured.failure?.location, failure?.let { Location(it.at, it.end, it.found) }, text)
    }

    /** A12's classes for the host with [rule] injected: its ERROR findings, as the near-miss sweep reads them. */
    private fun a12Classes(host: String, rule: DraftRule): Set<String> =
        validator.check(host, rule).diagnostics().filter { it.severity() == Severity.ERROR }
            .map { it.ledgerClass() }.toSet()

    /** The service `KernelAdapter.buildService` builds, for a self-contained model. */
    private fun mvkService(json: String): IMVK_Service {
        val expanded = KernelModelPreparation.expand(json, ReferenceResolvers.selfContained())
        val intern = ExternalDocumentModelSerializerService.getInternDocumentModel(expanded.model())
        return DocumentModelService().getMvkServiceForModel(intern, expanded.variant(), IProblemReporter { })
    }

    /** One parse of [condition] in [rule]'s context, with the failure recorded as A12's parser met it. */
    private fun trace(service: IMVK_Service, rule: IRule, condition: String,
        entry: SyntaxEntry = SyntaxEntry.CONDITION): Trace {
        val problems = mutableListOf<String>()
        var recorder: Recorder? = null
        var lexical: Location? = null
        var problemRange: ProblemRange? = null
        ParserService(service).checkSnippet(rule, condition, {
            when (entry) {
                SyntaxEntry.CONDITION -> it.bedingung_start()
                SyntaxEntry.OPERATION -> it.operationSnippet()
            }
        },
            {
                problems += (it as? Throwable)?.message ?: it.toString()
                if (problemRange == null && it is MVKParserError)
                    problemRange = ProblemRange(it.line, it.lineEnd, it.sourceStart, it.sourceEnd)
            }) { parser ->
            recorder = Recorder(rule, parser).also { parser.errorHandler = it }
            (parser.tokenStream.tokenSource as Lexer).addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(r: Recognizer<*, *>?, symbol: Any?, line: Int, column: Int, msg: String?,
                        e: RecognitionException?) {
                    if (lexical == null) {
                        val lexer = r as Lexer
                        val start = (e as LexerNoViableAltException).startIndex
                        lexical = location(lexer.inputStream, start, lexer.inputStream.index() + 1)
                    }
                }
            })
        }
        val code = problems.firstNotNullOfOrNull { MVK.find(it)?.value }
        val failure = recorder?.failure
        return when {
            code == null -> Trace(null, null)
            lexical != null -> Trace(code, Failure(Kind.LEXICAL, "-", -1, "-", 0, false, lexical))
            failure != null -> Trace(code, failure)
            else -> Trace(code, Failure(Kind.CHECK, code, -1, "-", 0, false), problemRange)
        }
    }

    /** A12's error strategy, recording the first recognition exception before A12 reports it. */
    private class Recorder(rule: IRule, parser: Lenient_ConditionParser) : MVKDefaultErrorStrategy(rule, parser) {
        var failure: Failure? = null

        override fun reportError(recognizer: Parser, e: RecognitionException) {
            if (failure == null) failure = Failure.of(recognizer, e)
            super.reportError(recognizer, e)
        }
    }

    enum class Kind { MISMATCH, NO_VIABLE, OTHER, LEXICAL, CHECK }

    /**
     * Where a parse failed: the exception [kind], the [rule] it failed in, the ATN [decision] for a failed
     * prediction (-1 otherwise), the offending token's type, and for a failed prediction how many tokens past its
     * start the offending token lies ([depth]).
     */
    data class Failure(val kind: Kind, val rule: String, val decision: Int, val offending: String, val depth: Int,
            val atEnd: Boolean, val location: Location? = null) {
        fun signature() = "$kind\t$rule\t${if (decision < 0) "-" else "dec $decision"}\t$offending\t" +
            (if (kind == Kind.NO_VIABLE) "depth $depth" else "-")

        /** Where a syntax failure happened, as a predicted row states it; a check or lexical failure has no site. */
        fun site() = when (kind) {
            Kind.MISMATCH, Kind.OTHER -> "$kind $rule at $offending"
            Kind.NO_VIABLE -> "$kind $rule dec $decision at $offending depth $depth"
            Kind.LEXICAL, Kind.CHECK -> "$kind"
        }

        companion object {
            fun of(parser: Parser, e: RecognitionException): Failure {
                val offending = e.offendingToken
                val rule = e.ctx?.let { parser.ruleNames[it.ruleIndex] } ?: "?"
                val type = if (offending.type == Token.EOF) "EOF" else parser.vocabulary.getSymbolicName(offending.type)
                    ?: parser.vocabulary.getLiteralName(offending.type) ?: offending.type.toString()
                val position = location(offending.inputStream, offending.startIndex,
                    if (offending.type == Token.EOF) offending.startIndex else offending.stopIndex + 1)
                return when (e) {
                    is NoViableAltException -> Failure(Kind.NO_VIABLE, rule,
                        (parser.atn.states[e.offendingState] as? DecisionState)?.decision ?: -1, type,
                        offending.tokenIndex - e.startToken.tokenIndex, offending.type == Token.EOF, position)
                    is InputMismatchException -> Failure(Kind.MISMATCH, rule, -1, type, 0, offending.type == Token.EOF, position)
                    else -> Failure(Kind.OTHER, rule, -1, type, 0, offending.type == Token.EOF, position)
                }
            }
        }
    }

    /** The code A12 reported for one parse ([code], null when it parsed), and how the parse failed. */
    data class Trace(val code: String?, val failure: Failure?, val problemRange: ProblemRange? = null)

    data class ProblemRange(val line: Int, val lineEnd: Int, val start: Int, val end: Int)

    data class Location(val start: Int, val end: Int, val text: String)

    /** Every traced condition, filed by signature, with the defects found against production and the model. */
    private class Tally {
        val signatures = sortedMapOf<String, MutableList<String>>()
        val kinds = Kind.entries.associateWith { 0 }.toMutableMap()
        val productionMismatches = mutableListOf<String>()
        val modelDefects = mutableListOf<String>()
        var parsed = 0
        var beyondTheParse = 0
        val witnesses = sortedMapOf<Int, MutableList<Int>>()
        val switchesFailed = mutableSetOf<String>()

        /** How many failed predictions of [decision] lay at least [depth] tokens past its start. */
        fun witnessed(decision: Int, depth: Int) = witnesses[decision].orEmpty().count { it >= depth }

        fun count(kind: Kind) = kinds.getValue(kind)

        fun add(condition: String, trace: Trace, a12: Set<String>) {
            val failure = trace.failure
            if (trace.code == null || failure == null) {
                parsed++
                if (a12.isNotEmpty()) beyondTheParse++
                (a12 intersect SYNTAX_CLASSES).takeIf { it.isNotEmpty() }?.let {
                    productionMismatches += "the trace parsed what A12 refuses as syntax $it · $condition"
                }
                return
            }
            if (trace.code !in a12) productionMismatches += "${trace.code} not in $a12 · $condition"
            kinds[failure.kind] = count(failure.kind) + 1
            if (failure.kind == Kind.NO_VIABLE) {
                witnesses.getOrPut(failure.decision) { mutableListOf() } += failure.depth
            }
            if (failure.rule in FALLIBLE_SWITCHES) switchesFailed += failure.rule
            signatures.getOrPut(failure.signature()) { mutableListOf() } += condition
            modelViolation(failure, trace.code)?.let { modelDefects += "$it · ${failure.signature()} · $condition" }
        }

        fun report(): String =
            "# parsed $parsed (refused later: $beyondTheParse); parse failures $kinds; signatures ${signatures.size}\n" +
                signatures.entries.sortedByDescending { it.value.size }.joinToString("") {
                    "${it.value.size}\t${it.key}\t${it.value.take(3).joinToString(" || ")}\n"
                }
    }

    private companion object {
        // The stream owns its index units; measuring its prefix avoids assuming UTF-16 or code-point indices.
        fun location(stream: CharStream, from: Int, until: Int): Location {
            val start = from.coerceIn(0, stream.size())
            val end = until.coerceIn(start, stream.size())
            fun prefixLength(index: Int) = if (index == 0) 0 else stream.getText(Interval(0, index - 1)).length
            return Location(prefixLength(start), prefixLength(end),
                if (start == end) "" else stream.getText(Interval(start, end - 1)))
        }
        val REPORT = File("build/parse-trace/report.tsv")

        private const val AFTER = "FieldFilled(Total) And "

        /**
         * Each Appendix C condition with where A12's parser fails and the class it answers: the exception kind, the
         * rule it failed in, for a failed prediction the decision and how far past its start, and the offending
         * token. `PARSED` is a text that reaches the end without a syntax failure, and `CHECK` a check action's
         * class. The kind and class were predicted before the first run; the sites were measured afterwards and each
         * is the construct the model names for its row. Measured 2026-09-26: of the 21
         * rows predicted before the run, 18 held. The three misses were misapplications of the model, and each row
         * now carries what the model gives when applied in full. `$` then a name ends the entity-spec window, so
         * `$Code/` fails at a match. A completed spec's check wins over the later mismatch, so the unstarred
         * `CurrentRepetition(/Order/Items, …)` is `MVK_NO_WILDCARD`. The control `Abs(-1)` was replaced by
         * `Abs([Qty])`, because a check refuses a constant there. The two comment rows had no prediction: they
         * settle that a comment is a token the grammar admits only at a condition's or an operation's edge.
         */
        val PREDICTED_ROWS = listOf(
            AFTER + "FieldFilled" to "MISMATCH feldListenBedingung at EOF MVK_EXPECTED_TOKEN_NOT_FOUND",
            AFTER + "Valid" to "NO_VIABLE einfacheBedingung dec 6 at EOF depth 1 MVK_INCOMPLETE_INPUT",
            AFTER + "RoundUpValue(Total," to "MISMATCH rundenOp at EOF MVK_EXPECTED_TOKEN_NOT_FOUND",
            AFTER + "RangeAsNumber(Code, 1," to "MISMATCH feldOpMitParams at EOF MVK_EXPECTED_TOKEN_NOT_FOUND",
            AFTER + "ValueAsDate(Estimated," to "MISMATCH tagSpec at EOF MVK_EXPECTED_TOKEN_NOT_FOUND",
            AFTER + "Max([Total]," to "NO_VIABLE operation dec 20 at EOF depth 0 MVK_INCOMPLETE_INPUT",
            AFTER + "AddDays(Placed," to "NO_VIABLE operation dec 20 at EOF depth 0 MVK_INCOMPLETE_INPUT",
            AFTER + "FieldFilled(Items*/NoSuch.)" to "MISMATCH idNameRef at KLAMMER_ZU MVK_UNEXPECTED_TOKEN",
            AFTER + "FieldFilled(Items*/Count.)" to "MISMATCH idNameRef at KLAMMER_ZU MVK_UNEXPECTED_TOKEN",
            AFTER + "FieldFilled(Items*/Lots/Qty/)" to "MISMATCH nameOhnePunkt at KLAMMER_ZU MVK_UNEXPECTED_TOKEN",
            AFTER + "FieldFilled(Items*/Lots/Qty)" to "CHECK MVK_NO_WILDCARD",
            AFTER + "FieldFilled(${'$'}Code/)" to "MISMATCH nameOhnePunkt at KLAMMER_ZU MVK_UNEXPECTED_TOKEN",
            AFTER + "CurrentRepetition(/Order/Items, /Order/Items/Lots) > 0" to "CHECK MVK_NO_WILDCARD",
            AFTER + "CurrentRepetition(/Order/Items*, /Order/Items/Lots) > 0" to "MISMATCH groupOp at KOMMA MVK_UNEXPECTED_TOKEN",
            AFTER + "AddDays([Premium], 1) > 0" to "NO_VIABLE datumAngabe2 dec 39 at ECKIGE_KLAMMER_AUF depth 0 MVK_UNEXPECTED_TOKEN",
            AFTER + "RoundUpValue(Total In NoSuch) > 0" to "MISMATCH rundenOp at IN1 MVK_UNEXPECTED_TOKEN",
            AFTER + "Abs(RuleGroup) > 0" to "NO_VIABLE operation dec 20 at REGEL_KONTEXT depth 0 MVK_UNEXPECTED_TOKEN",
            "[Total] > \u0662" to "LEXICAL MVK_LEXER_STANDARD_ERROR",
            AFTER + "[Total] > Abs Qty" to "MISMATCH feldOperOp at NameOhnePunktSTRAmAnfang MVK_UNEXPECTED_TOKEN",
            AFTER + "Abs({[Total]}) > 0" to "PARSED",
            AFTER + "Max([Total], RoundUp([Qty], 0)) > 0" to "PARSED",
            AFTER + "[Placed] < AddDays(Placed, Abs([Qty]))" to "PARSED",
            AFTER + "RoundUpValue(Total, ;;c\n 2) > 0" to "MISMATCH rundenOp at Comment MVK_UNEXPECTED_TOKEN",
            AFTER + ";;c\n FieldFilled(Qty)" to "PARSED",
        )
        val MVK = Regex("MVK_[A-Z0-9_]+")

        /** The adaptive decisions whose alternatives share no prefix (proposal §2.4). */
        val PREDICATE_ONLY = setOf(0, 4, 7, 9, 16, 23, 25, 31, 37, 38, 39, 40, 41, 57, 59, 69)

        /** The adaptive decisions whose alternatives share a prefix, with the proposal's window where it is bounded. */
        val OVERLAP = mapOf(3 to null, 6 to 3, 17 to null, 19 to null, 20 to 2, 24 to null, 55 to null, 56 to null,
            58 to 2, 63 to 3, 66 to 3, 71 to null, 74 to 2, 79 to 2, 81 to 2, 83 to 2)

        const val INTERNAL_ERROR = "MVK_INTERNAL_ERROR"

        /** The classes only the parse stage raises, so a text the trace parses cannot draw one downstream. */
        val SYNTAX_CLASSES = setOf("MVK_UNEXPECTED_TOKEN", "MVK_INCOMPLETE_INPUT", "MVK_EXPECTED_TOKEN_NOT_FOUND",
            "MVK_LEXER_STANDARD_ERROR", "MVK_COMMON_PARSER_ERROR")

        /** The LL(1) switches whose default can fire, by rule (Appendix B). */
        val FALLIBLE_SWITCHES = setOf("wertListenSpezifier2", "commonKonstante", "vglOp")

        /**
         * Which of the model's claims [failure] breaks, or null: the class is the exception kind crossed with the
         * end of text (§2.3); no in-body predicate fails; a decision with no shared prefix fails at its first
         * token; a bounded window is never exceeded; an LL(1) switch fails only where Appendix B says it can.
         */
        fun modelViolation(failure: Failure, code: String): String? {
            val expected = when (failure.kind) {
                Kind.MISMATCH -> if (failure.atEnd) "MVK_EXPECTED_TOKEN_NOT_FOUND" else "MVK_UNEXPECTED_TOKEN"
                Kind.NO_VIABLE -> if (failure.atEnd) "MVK_INCOMPLETE_INPUT" else "MVK_UNEXPECTED_TOKEN"
                Kind.OTHER -> return "an in-body predicate failed"
                Kind.LEXICAL -> "MVK_LEXER_STANDARD_ERROR"
                Kind.CHECK -> return if (code == INTERNAL_ERROR) "an internal error, not a check action" else null
            }
            if (code != expected) return "class $code, the model says $expected"
            if (failure.kind != Kind.NO_VIABLE) return null
            val d = failure.decision
            return when {
                d in PREDICATE_ONLY -> if (failure.depth == 0) null else "a prefix-free decision failed past its start"
                d in OVERLAP -> OVERLAP.getValue(d)?.let { window ->
                    if (failure.depth < window) null else "window $window exceeded"
                }
                failure.rule in FALLIBLE_SWITCHES -> if (failure.depth == 0) null else "a switch failed past its start"
                else -> "a decision the model says cannot fail"
            }
        }
    }
}
