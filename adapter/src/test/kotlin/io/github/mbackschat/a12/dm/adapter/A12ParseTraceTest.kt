package io.github.mbackschat.a12.dm.adapter

import com.mgmtp.a12.kernel.core.parser.internal.ParserService
import com.mgmtp.a12.kernel.core.parser.internal.error.MVKDefaultErrorStrategy
import com.mgmtp.a12.kernel.core.parser.internal.grammar.gen.Lenient_ConditionParser
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule
import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblemReporter
import com.mgmtp.a12.kernel.core.tool.a12internal.api.services.IMVK_Service
import com.mgmtp.a12.kernel.md.model.a12internal.services.DocumentModelService
import com.mgmtp.a12.kernel.md.model.a12internal.services.ElementContainer
import com.mgmtp.a12.kernel.md.serializer.model.internal.service.ExternalDocumentModelSerializerService
import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation
import io.github.mbackschat.a12.dm.adapter.laws.NearMissPopulation.Family
import io.github.mbackschat.a12.dm.adapter.laws.ledgerClass
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DatePrecision
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.rulekit.validate.DraftRule
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.antlr.v4.runtime.BaseErrorListener
import org.antlr.v4.runtime.InputMismatchException
import org.antlr.v4.runtime.Lexer
import org.antlr.v4.runtime.NoViableAltException
import org.antlr.v4.runtime.Parser
import org.antlr.v4.runtime.RecognitionException
import org.antlr.v4.runtime.Recognizer
import org.antlr.v4.runtime.Token
import org.antlr.v4.runtime.atn.DecisionState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

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
            condition to (trace.failure?.let { "${it.site()} ${trace.code}" } ?: "PARSED")
        }
        assertEquals(PREDICTED_ROWS, observed)
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
    private fun trace(service: IMVK_Service, rule: IRule, condition: String): Trace {
        val problems = mutableListOf<String>()
        var recorder: Recorder? = null
        var lexical: String? = null
        ParserService(service).checkSnippet(rule, condition, { it.bedingung_start() },
            { problems += (it as? Throwable)?.message ?: it.toString() }) { parser ->
            recorder = Recorder(rule, parser).also { parser.errorHandler = it }
            (parser.tokenStream.tokenSource as Lexer).addErrorListener(object : BaseErrorListener() {
                override fun syntaxError(r: Recognizer<*, *>?, symbol: Any?, line: Int, column: Int, msg: String?,
                        e: RecognitionException?) {
                    if (lexical == null) lexical = "column $column"
                }
            })
        }
        val code = problems.firstNotNullOfOrNull { MVK.find(it)?.value }
        val failure = recorder?.failure
        return when {
            code == null -> Trace(null, null)
            lexical != null -> Trace(code, Failure(Kind.LEXICAL, "-", -1, "-", 0, false))
            failure != null -> Trace(code, failure)
            else -> Trace(code, Failure(Kind.CHECK, code, -1, "-", 0, false))
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
            val atEnd: Boolean) {
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
                return when (e) {
                    is NoViableAltException -> Failure(Kind.NO_VIABLE, rule,
                        (parser.atn.states[e.offendingState] as? DecisionState)?.decision ?: -1, type,
                        offending.tokenIndex - e.startToken.tokenIndex, offending.type == Token.EOF)
                    is InputMismatchException -> Failure(Kind.MISMATCH, rule, -1, type, 0, offending.type == Token.EOF)
                    else -> Failure(Kind.OTHER, rule, -1, type, 0, offending.type == Token.EOF)
                }
            }
        }
    }

    /** The code A12 reported for one parse ([code], null when it parsed), and how the parse failed. */
    data class Trace(val code: String?, val failure: Failure?)

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
