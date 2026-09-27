package io.github.mbackschat.a12.dm.adapter

import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblem
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule
import com.mgmtp.a12.kernel.md.model.internal.service.codegen.GroovyCodeGeneratorConfig
import com.mgmtp.a12.model.notification.RankedNotification
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/** R5 measures raw/public notification boundaries and member correspondence before report assembly is replaced. */
class A12ReportStreamTest {
    @Test
    fun rawSemanticPositionsIdentifyAuthoredMembers() {
        val rows = siteRows()
        assertEquals(60, rows.size)
        assertEquals(60, rows.map { it.id }.distinct().size)
        assertEquals(60, rows.map { it.model }.distinct().size)
        assertEquals(20, rows.count { it.code == null })
        val misses = mutableListOf<String>()
        val observations = mutableListOf<String>()
        val differences = mutableListOf<String>()
        var admitted = 0
        var repertoires = 0
        for (row in rows) {
            fun expect(label: String, expected: Any?, actual: Any?) {
                if (expected != actual) misses += "${row.id}: $label expected=$expected actual=$actual"
            }
            val expanded = KernelModelPreparation.expand(row.model, ReferenceResolvers.selfContained())
            val raw = mutableListOf<RankedNotification>()
            expanded.service().generateValidationCode(expanded.model(), GroovyCodeGeneratorConfig(null), null) { raw += it }
            val whole = KernelConsistencyOracle().checkConsistency(row.model, ReferenceResolvers.selfContained())
            val ours = check(ModelSource(row.model)).findings.filter { it.justifiesRejection }
            val copies = if (row.member == "errorCondition") 1 else 2
            val unsupported = row.text.contains("😀")
            val noCode = if (unsupported) "(no code)" else null
            val postParse = if (row.code == null && row.member == "errorCondition" && !row.text.contains("FieldFilled"))
                "MVK_ERROR_FIELD_NOT_REFERENCED" else null
            val expectedRaw = if (copies == 1) listOfNotNull(noCode, row.code, postParse)
                else listOfNotNull(row.code, noCode, row.code)
            val expectedWhole = if (copies == 1) expectedRaw else listOfNotNull(row.code, noCode)
            expect("raw classes", expectedRaw, raw.map { code(it.message) })
            expect("whole classes", expectedWhole, whole.map { code(it.message()) })
            val repertoireText = "The field contains one or several unsupported signs."
            val publicRepertoireText = "Rule with path '/Order/SiteProbe': $repertoireText"
            val publicCodes = whole.map {
                if (it.message() == publicRepertoireText)
                    "RK_UNSUPPORTED_CHARACTER" else code(it.message())
            }
            expect("checker classes and multiplicity", publicCodes.sorted(), ours.map { it.code }.sorted())
            if (publicCodes != ours.map { it.code }) differences += "${row.id}\torder\t$publicCodes\t${ours.map { it.code }}"
            if (whole.isEmpty()) admitted++
            repertoires += publicCodes.count { it == "RK_UNSUPPORTED_CHARACTER" }
            for (notification in raw.filter { code(it.message) == "(no code)" }) {
                expect("repertoire message", repertoireText, notification.message)
                expect("repertoire coordinates", Range(-1, -1, -1, -1), (notification as? IProblem)?.let(::range))
            }
            if (row.code != null) {
                val commentOrigin = row.code == "MVK_NO_NUMBER_FOR_CALC" &&
                    row.member.endsWith(".operation") && row.text.startsWith(";;")
                val token = if (row.code == "MVK_INVALID_ENTITY") "Missing" else if (commentOrigin) ";;😀" else "["
                val local = if (commentOrigin) 0 else if (row.code == "MVK_INVALID_ENTITY") row.text.indexOf(token)
                    else row.text.lastIndexOf("[Label]")
                expect("target token exists", true, local >= 0)
                val semantic = raw.filter { code(it.message) == row.code }
                for (notification in semantic) {
                    val source = notification.source as? IRule
                    expect("IRule source", true, source != null)
                    if (source == null) continue
                    expect("source declaration", "/Order/SiteProbe", source.fullName)
                    val joined = source.errorConditionText
                    val origin = joined.indexOf(row.text)
                    expect("unique authored interval", true, origin >= 0 && origin == joined.lastIndexOf(row.text))
                    if (origin >= 0 && local >= 0) {
                        val start = origin + local
                        val end = start + token.length
                        fun line(offset: Int) = joined.take(offset).count { it == '\n' } + 1
                        fun column(offset: Int) = joined.codePointCount(joined.lastIndexOf('\n', offset - 1) + 1, offset)
                        expect("raw token range", Range(line(start), column(start), line(end), column(end)),
                            (notification as? IProblem)?.let(::range))
                    }
                    observations += "${row.id}\traw\t${source.fullName}\t${(notification as? IProblem)?.let(::range)}\t" +
                        joined.replace("\r", "\\r").replace("\n", "\\n")
                }
                if (copies == 2 && semantic.size == 2) expect("raw pair equality", true, semantic[0] == semantic[1])
                for (finding in ours.filter { it.code == row.code }) {
                    expect("checker declaration", "/Order/SiteProbe", finding.at)
                    expect("checker member", row.member, finding.span?.member)
                    val span = finding.span
                    expect("checker member bounds", true, span != null && span.start >= 0 &&
                        span.end >= span.start && span.end <= row.text.length)
                    if (span != null && !(span.start <= local && span.end >= local + token.length))
                        differences += "${row.id}\tspan\t${row.member}:$local:${local + token.length}\t$span"
                }
            }
            observations += "${row.id}\twhole\t" + whole.map { it.message().replace("\r", "\\r").replace("\n", "\\n") }
            observations += "${row.id}\tchecker\t" + ours.map { "${it.code}:${it.at}:${it.span}" }
        }
        // Disposable trace detail; the finite matrix and assertions retain the observations across clean builds.
        val directory = File("build/checker-near-miss").apply { mkdirs() }
        File(directory, "report-sites.tsv").writeText(observations.joinToString("\n", postfix = "\n"))
        val ledger = differences.joinToString("\n", postfix = if (differences.isEmpty()) "" else "\n")
        File(directory, "report-site-differences.tsv").writeText(ledger)
        assertEquals(emptyList<String>(), misses, "frozen source/member predictions")
        assertEquals(12, admitted)
        assertEquals(21, repertoires)
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/report-site-differences.tsv")).readText(), ledger)
    }

    private enum class Member(val key: String) {
        RULE("errorCondition"), COMMON("commonPrecondition"),
        PRE_FIRST("computationAlternatives[0].precondition"), OP_FIRST("computationAlternatives[0].operation"),
        PRE_SECOND("computationAlternatives[1].precondition"), OP_SECOND("computationAlternatives[1].operation")
    }

    private enum class Leaf(val code: String?) {
        VALID(null), STRING("MVK_NO_NUMBER_FOR_CALC"), MISSING("MVK_INVALID_ENTITY");

        fun mutate(text: String): String = when (this) {
            VALID -> text
            STRING -> text.replace("[Left]", "[Label]")
            MISSING -> text.replace("[Left]", "[Missing]")
        }
    }

    private data class SiteRow(val id: String, val model: String, val member: String, val text: String, val code: String?)

    private fun siteRows(): List<SiteRow> = buildList {
        val host = host()
        val model = BoundModel.ofJson(host)
        val gate = model.num("/Order/Gate")
        val left = model.num("/Order/Left")
        val right = model.num("/Order/Right")
        val result = model.num("/Order/Result")
        val label = model.str("/Order/Label")
        val expression = left.times(2)
        val predicate = expression.gt(0)
        val validator = RuleValidator()
        fun addRows(member: Member, prefix: String, inline: String? = null) {
            val rule = model.rule(model.group("/Order")).name("SiteProbe").errorField(gate)
                .`when`((inline?.let { label.eq(it) } ?: gate.filled()).and(predicate)).code("SITE_PROBE")
                .message(Dsl.en("Check order"), Dsl.de("Check order")).build().draft()
            val computation = model.computation(result).name("SiteProbe")
                .commonPrecondition(if (member == Member.COMMON) predicate else gate.filled())
                .`when`(gate.le(0).and(if (member == Member.PRE_FIRST) predicate else right.gt(0)))
                .calc(if (member == Member.OP_FIRST) expression else right.plus(1))
                .`when`(gate.gt(0).and(if (member == Member.PRE_SECOND) predicate else right.gt(0)))
                .calc(if (member == Member.OP_SECOND) expression else right.plus(2))
                .message(Dsl.en("Check result"), Dsl.de("Check result")).build().draft()
            val original = when (member) {
                Member.RULE -> rule.errorCondition()
                Member.COMMON -> computation.commonPrecondition()
                Member.PRE_FIRST -> computation.alternatives()[0].precondition()
                Member.OP_FIRST -> computation.alternatives()[0].operation()
                Member.PRE_SECOND -> computation.alternatives()[1].precondition()
                Member.OP_SECOND -> computation.alternatives()[1].operation()
            }
            require(original.split("[Left]").size == 2)
            for (leaf in Leaf.entries) {
                val text = prefix + leaf.mutate(original)
                val json = if (member == Member.RULE) validator.inject(host, rule.withErrorCondition(text)) else {
                    val alternatives = computation.alternatives().mapIndexed { index, alternative ->
                        DraftComputation.Alternative(
                            if (member.key == "computationAlternatives[$index].precondition") text else alternative.precondition(),
                            if (member.key == "computationAlternatives[$index].operation") text else alternative.operation())
                    }
                    validator.inject(host, DraftComputation(computation.groupPath(), computation.name(),
                        computation.computedFieldRelPath(), if (member == Member.COMMON) text else computation.commonPrecondition(),
                        alternatives, computation.errorCodesToSuppress(), computation.roundingMode(), computation.messages()))
                }
                add(SiteRow("$member:${prefix.replace("\r", "\\r").replace("\n", "\\n")}:${inline ?: "none"}:$leaf",
                    json, member.key, text, leaf.code))
            }
        }
        for (member in Member.entries) for (prefix in listOf("", "\r\n", ";;😀\r\n")) addRows(member, prefix)
        for (inline in listOf("x", "😀")) addRows(Member.RULE, "", inline)
    }

    @Test
    fun theReportOraclesPreserveTheirMeasuredInformation() {
        val rows = rows()
        assertEquals(15, rows.size)
        assertEquals(15, rows.map { it.id }.distinct().size)
        assertEquals(15, rows.map { it.model }.distinct().size)
        val misses = mutableListOf<String>()
        val measurements = mutableListOf<String>()
        val differences = mutableListOf<String>()
        for (row in rows) {
            val generation = KernelModelPreparation.expand(row.model, ReferenceResolvers.selfContained())
            val raw = mutableListOf<RankedNotification>()
            generation.service().generateValidationCode(generation.model(), GroovyCodeGeneratorConfig(null), null) { raw += it }
            val consistency = KernelModelPreparation.expand(row.model, ReferenceResolvers.selfContained())
            val whole = mutableListOf<RankedNotification>()
            consistency.service().checkConsistency(consistency.model()) { whole += it }
            val projected = KernelConsistencyOracle().checkConsistency(row.model, ReferenceResolvers.selfContained())
            val ours = check(ModelSource(row.model)).findings.filter { it.justifiesRejection }.map { it.code }
            fun expect(label: String, expected: Any?, actual: Any?) {
                if (expected != actual) misses += "${row.id}: $label expected=$expected actual=$actual"
            }
            val rawCodes = raw.map { code(it.message) }
            val wholeCodes = whole.map { code(it.message) }
            expect("raw classes", row.raw, rawCodes)
            expect("whole-model classes", row.whole, wholeCodes)
            expect("dmkits messages", whole.map { it.message }, projected.map { it.message() })
            expect("dmkits severities", whole.map { it.severity.name }, projected.map { it.severity().name })
            expect("whole-model coordinates unknown", List(whole.size) { Range(-1, -1, -1, -1) },
                whole.filterIsInstance<IProblem>().map(::range))
            row.range?.let { expect("raw problem range", List(raw.size) { _ -> it }, raw.filterIsInstance<IProblem>().map(::range)) }
            val repeated = when (row.id) {
                "computation", "mixed-zeta-alpha", "mixed-alpha-zeta" -> raw.filter { code(it.message) == "MVK_NO_STRING_OR_NUMBER_FOR_ADD" }
                "messages-identical" -> raw
                else -> emptyList()
            }
            if (repeated.isNotEmpty()) {
                expect("pair size", 2, repeated.size)
                val pair = repeated.first() to repeated.last()
                val sameProblem = row.id != "messages-identical"
                expect("problem identity", false, pair.first === pair.second)
                expect("problem equality", sameProblem, pair.first == pair.second)
                expect("message equality", sameProblem, pair.first.message == pair.second.message)
                expect("source identity", true, pair.first.source === pair.second.source)
                measurements += "${row.id}\tidentity\t${pair.first === pair.second}\tequality\t${pair.first == pair.second}" +
                    "\tmessageEquality\t${pair.first.message == pair.second.message}\tsourceIdentity\t${pair.first.source === pair.second.source}"
            }
            for ((layer, values) in listOf("raw" to raw, "whole" to whole)) {
                measurements += "${row.id}\t$layer\t${values.map { code(it.message) }}\t" +
                    values.map { "${it.javaClass.simpleName}:${it.source?.javaClass?.simpleName}:${(it as? IProblem)?.let(::range)}" }
                measurements += "${row.id}\t$layer-messages\t" + values.map { it.message.replace("\n", "\\n").replace("\t", "\\t") }
            }
            measurements += "${row.id}\tchecker\t$ours"
            if (wholeCodes != ours) differences += "${row.id}\t$wholeCodes\t$ours"
        }
        // Disposable observations; the committed ledger and plan retain the evidence needed across runs.
        val directory = File("build/checker-near-miss").apply { mkdirs() }
        File(directory, "report-stream-observations.tsv").writeText(measurements.joinToString("\n", postfix = "\n"))
        val ledger = differences.joinToString("\n", postfix = if (differences.isEmpty()) "" else "\n")
        File(directory, "report-stream.tsv").writeText(ledger)
        assertEquals(emptyList<String>(), misses, "frozen kernel predictions")
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/report-stream.tsv")).readText(), ledger)
    }

    private data class Range(val line: Int, val start: Int, val lineEnd: Int, val end: Int)
    private fun range(problem: IProblem) = Range(problem.line, problem.sourceStart, problem.lineEnd, problem.sourceEnd)
    private fun code(message: String) = Regex("\\[([A-Z][A-Z0-9_]+)]\\s*$").find(message)?.groupValues?.get(1) ?: "(no code)"
    private data class Row(val id: String, val model: String, val raw: List<String>, val whole: List<String> = raw,
        val range: Range? = null)

    private fun host() = DocumentModelDraft.named("order-report-stream").locale("en_US").locale("de_DE")
            .rootGroup("Order") { it
                .numberField("Gate") { n -> n.fractionDigits(0) }
                .numberField("Left") { n -> n.fractionDigits(0) }
                .numberField("Right") { n -> n.fractionDigits(0) }
                .numberField("Result") { n -> n.fractionDigits(0) }
                .stringField("Label") { } }.build().toJson()

    private fun rows(): List<Row> {
        val host = host()
        val model = BoundModel.ofJson(host)
        val gate = model.num("/Order/Gate")
        val left = model.num("/Order/Left")
        val right = model.num("/Order/Right")
        val validator = RuleValidator()
        fun rule(name: String, condition: Condition) = model.rule(model.group("/Order")).name(name).errorField(gate)
            .`when`(condition).code("REPORT_$name").message(Dsl.en("Check order"), Dsl.de("Check order")).build().draft()
        val valid = rule("Probe", gate.filled())
        val numeric = rule("Zeta", gate.filled().and(left.times(2).gt(0)))
        val zeta = numeric.withErrorCondition(numeric.errorCondition().replace("[Left]", "[Label]"))
        val reference = rule("Alpha", gate.filled().and(left.gt(0)))
        val alpha = reference.withErrorCondition(reference.errorCondition().replace("[Left]", "[Missing]"))
        val twin = rule("Zeta", gate.filled().and(left.gt(0))).withErrorCondition(alpha.errorCondition())
        val calculation = model.computation(model.num("/Order/Result")).name("Computed").calc(left.plus(right))
            .message(Dsl.en("Check result"), Dsl.de("Check result")).build().draft()
        val computation = DraftComputation(calculation.groupPath(), calculation.name(), calculation.computedFieldRelPath(),
            calculation.commonPrecondition(), calculation.alternatives().map {
                DraftComputation.Alternative(it.precondition(), it.operation().replace("[Right]", "[Label]"))
            }, calculation.errorCodesToSuppress(), calculation.roundingMode(), calculation.messages())
        val zetaFirst = validator.inject(validator.inject(host, zeta), alpha)
        val alphaFirst = validator.inject(validator.inject(host, alpha), zeta)
        fun messages(english: String, german: String) = validator.inject(host,
            valid.withMessages(listOf(Dsl.en(english), Dsl.de(german))))
        val match = valid.errorCondition().dropLast(1) + "]"
        val eof = valid.errorCondition() + "\nAnd FieldFilled"
        val calc = "MVK_NO_NUMBER_FOR_CALC"
        val entity = "MVK_INVALID_ENTITY"
        val add = "MVK_NO_STRING_OR_NUMBER_FOR_ADD"
        val dollars = "WRONG_NUMBER_OF_DOLLARS"
        val token = "MVK_UNEXPECTED_TOKEN"
        val unknown = Range(-1, -1, -1, -1)
        return listOf(
            Row("empty", host, emptyList()),
            Row("valid-rule", validator.inject(host, valid), emptyList()),
            Row("arithmetic-rule", validator.inject(host, zeta), listOf(calc)),
            Row("reference-rule", validator.inject(host, alpha), listOf(entity)),
            Row("computation", validator.inject(host, computation), listOf(add, add), listOf(add)),
            Row("rules-zeta-alpha", zetaFirst, listOf(calc, entity)),
            Row("rules-alpha-zeta", alphaFirst, listOf(entity, calc)),
            Row("mixed-zeta-alpha", validator.inject(validator.inject(validator.inject(host, zeta), computation), alpha),
                listOf(add, calc, add, entity), listOf(add, calc, entity)),
            Row("mixed-alpha-zeta", validator.inject(validator.inject(validator.inject(host, alpha), computation), zeta),
                listOf(add, entity, add, calc), listOf(add, entity, calc)),
            Row("same-class-two-rules", validator.inject(validator.inject(host, alpha), twin), listOf(entity, entity)),
            Row("messages-one", messages("Check \$Left", "Check order"), listOf(dollars), range = unknown),
            Row("messages-identical", messages("Check \$Left", "Check \$Left"), listOf(dollars, dollars), range = unknown),
            Row("messages-different", messages("Check \$Left", "Check \$Left/../Right\$"), listOf(dollars, token), range = unknown),
            Row("syntax-match", validator.inject(host, valid.withErrorCondition(match)), listOf(token),
                range = Range(1, match.length - 1, 1, match.length)),
            Row("syntax-eof", validator.inject(host, valid.withErrorCondition(eof)), listOf("MVK_EXPECTED_TOKEN_NOT_FOUND"),
                range = Range(2, eof.substringAfter('\n').length, 2, eof.substringAfter('\n').length)),
        )
    }
}
