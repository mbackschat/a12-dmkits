package io.github.mbackschat.a12.dm.adapter

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.ArrayNode
import io.github.mbackschat.a12.dm.adapter.laws.HarvestedModels
import io.github.mbackschat.a12.dm.adapter.laws.kernelModelClasses
import io.github.mbackschat.a12.dm.adapter.laws.checkerVerdict
import io.github.mbackschat.a12.dm.core.ModelInputException
import io.github.mbackschat.a12.dm.core.ModelConsistencyException
import com.mgmtp.a12.kernel.core.tool.a12internal.api.error.IProblem
import com.mgmtp.a12.kernel.core.tool.a12internal.api.ado.IRule
import com.mgmtp.a12.kernel.md.model.internal.service.codegen.GroovyCodeGeneratorConfig
import com.mgmtp.a12.model.notification.RankedNotification
import io.github.mbackschat.a12.dm.consistency.TextSpan
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.validate.DraftComputation
import io.github.mbackschat.a12.dm.rulekit.validate.RuleValidator
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.File

/** R5 measures raw/public notification boundaries and member correspondence before report assembly is replaced. */
class A12ReportStreamTest {
    @Test
    fun conditionFailuresWithholdOnlyTheMessageContentStage() {
        val host = DocumentModelDraft.named("order-message-stages").locale("en_US")
            .rootGroup("Order") { it.stringField("Reference") { }.numberField("Total") { n -> n.fractionDigits(0) } }
            .build().toJson()
        val model = BoundModel.ofJson(host)
        val reference = model.str("/Order/Reference")
        fun probe(condition: Condition) = model.rule(model.group("/Order")).name("MessageProbe").errorField(reference)
            .`when`(condition).code("ORDER_MESSAGE").message(Dsl.en("Check order reference")).build().draft()
        val rule = probe(reference.filled())
        val valid = rule.errorCondition()
        val unreferenced = probe(model.num("/Order/Total").filled()).errorCondition()
        val conditions = linkedMapOf(
            valid to null,
            "$valid And % $valid" to "MVK_LEXER_STANDARD_ERROR",
            "$valid And FieldFilled(Missing)" to "MVK_INVALID_ENTITY",
            "FieldFilled(Reference" to "MVK_EXPECTED_TOKEN_NOT_FOUND",
            "$valid And Abs([Total], [Total]) > 0" to "MVK_UNEXPECTED_TOKEN",
            "$valid And AddDays([Total], 1) > 0" to "MVK_UNEXPECTED_TOKEN",
            unreferenced to "MVK_ERROR_FIELD_NOT_REFERENCED",
        )
        val messages = linkedMapOf("plain" to rule.messages(), "tab" to listOf(LocalizedText("en_US", "Check\torder")),
            "missing" to emptyList())
        val predictions = mutableListOf<String>()
        val clusters = sortedMapOf<String, Int>()
        val examples = mutableListOf<String>()
        val models = mutableSetOf<String>()
        val validator = RuleValidator()
        for ((condition, failure) in conditions) for ((message, texts) in messages) {
            val id = "$condition:$message"
            val injected = validator.inject(host, rule.withErrorCondition(condition).withMessages(texts))
            // RuleValidator.inject supplies placeholders for omitted locales; missing input is a probe mutation.
            val tree = ObjectMapper().readTree(injected)
            val body = tree.at("/content/modelRoot/rootGroups/0/Group/elements")
                .single { it.path("type").asText() == "Rule" }.get("Rule") as ObjectNode
            if (message == "missing") body.remove("errorMessage")
            assertEquals(message != "missing", body.has("errorMessage"), "$id message presence")
            val json = tree.toString()
            models += json
            val expected = setOfNotNull(failure) + when {
                message == "missing" -> setOf("MVK_ERROR_MESSAGE_MISSING") +
                    if (failure == null) setOf("RULE_HAS_NO_ERRORTEXT") else emptySet()
                message == "tab" && failure == null -> setOf("INVALID_CHARACTERS")
                else -> emptySet()
            }
            val kernel = kernelModelClasses(json)
            if (expected != kernel) predictions += "$id: predicted $expected, observed $kernel"
            val portable = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            checkerVerdict(kernel, portable)?.let { verdict ->
                val key = "$verdict\t${kernel.sorted().joinToString(",").ifEmpty { "(none)" }}\t" +
                    portable.sorted().joinToString(",").ifEmpty { "(none)" }
                clusters[key] = clusters.getOrDefault(key, 0) + 1
                examples += "$key\t$id"
            }
        }
        assertEquals(21, models.size, "every population member is a distinct whole-model input")
        val ledger = clusters.entries.joinToString("") { "${it.value}\t${it.key}\n" }
        val directory = File("build/checker-near-miss").apply { mkdirs() }
        File(directory, "message-stages.tsv").writeText(ledger)
        File(directory, "message-stages-examples.tsv").writeText(examples.joinToString("\n"))
        assertEquals(emptyList<String>(), predictions, "kernel predictions")
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/message-stages.tsv")).readText(), ledger)
    }

    @Test
    fun blankOperationsFollowEarlierReachedFailures() {
        val host = DocumentModelDraft.named("order-blank-work").locale("en_US")
            .rootGroup("Order") { it.numberField("Gate") { n -> n.fractionDigits(0) }
                .numberField("Result") { n -> n.fractionDigits(0) } }.build().toJson()
        val model = BoundModel.ofJson(host)
        val gate = model.num("/Order/Gate")
        val calculation = model.computation(model.num("/Order/Result")).name("Total")
            .commonPrecondition(gate.filled()).calc(gate.plus(1)).message(Dsl.en("Check total")).build().draft()
        val validator = RuleValidator()
        val mismatches = mutableListOf<String>()
        var count = 0
        for (missing in listOf(false, true)) for (first in listOf(false, true)) for (second in listOf(false, true)) {
            val common = calculation.commonPrecondition().let { if (missing) it.replace("Gate", "Missing") else it }
            val work = calculation.alternatives().single().operation()
            val computation = DraftComputation(calculation.groupPath(), calculation.name(), calculation.computedFieldRelPath(),
                common, listOf(first, second).map { blank ->
                    DraftComputation.Alternative(calculation.commonPrecondition(), if (blank) "" else work)
                }, calculation.errorCodesToSuppress(), calculation.roundingMode(), calculation.messages())
            val json = validator.inject(host, computation)
            val expected = when {
                missing -> listOf("MVK_INVALID_ENTITY")
                first || second -> listOf("MVK_UNEXPECTED_TOKEN")
                else -> emptyList()
            }
            val whole = originalOutcome(json)
            val id = "$missing:$first:$second"
            assertEquals(null, whole.failure, id)
            assertEquals(expected, whole.callbacks.map { code(it.message) }.filter { it.startsWith("MVK_") }, "$id kernel")
            val portable = check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }
            if (expected != portable) mismatches += "$id: expected $expected, portable $portable"
            count++
        }
        assertEquals(8, count)
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun repeatedLocalFailuresStopAtTheirFirstReachedAction() {
        val host = DocumentModelDraft.named("order-report-compatibility").locale("en_US")
            .rootGroup("Order") { it
                .numberField("Gate") { n -> n.fractionDigits(0) }
                .numberField("Left") { n -> n.fractionDigits(0) }
                .numberField("Right") { n -> n.fractionDigits(0) }
                .numberField("Result") { n -> n.fractionDigits(0) }
                .group("Shipping") { g -> g.stringField("City") { } }
                .group("Billing") { g -> g.stringField("City") { } } }.build().toJson()
        val model = BoundModel.ofJson(host)
        val gate = model.num("/Order/Gate")
        val rule = model.rule(model.group("/Order")).name("Addresses").errorField(gate)
            .`when`(gate.filled().and(Dsl.allGroupsFilled(model.group("/Order/Shipping"), model.group("/Order/Billing"))))
            .code("ADDRESSES").message(Dsl.en("Check addresses")).build().draft()
        val calculation = model.computation(model.num("/Order/Result")).name("Total")
            .commonPrecondition(gate.filled().and(model.num("/Order/Left").gt(0)))
            .calc(model.num("/Order/Right").plus(1)).message(Dsl.en("Check total")).build().draft()
        val validator = RuleValidator()
        var count = 0
        for (first in listOf(false, true)) for (second in listOf(false, true)) {
            val firstCall = rule.errorCondition().let { if (first) it.replace("Billing", "Shipping") else it }
            val secondCall = rule.errorCondition().let { if (second) it.replace("Shipping", "Billing") else it }
            val computation = DraftComputation(calculation.groupPath(), calculation.name(), calculation.computedFieldRelPath(),
                calculation.commonPrecondition().let { if (first) it.replace("[Left]", "[MissingOne]") else it },
                calculation.alternatives().map { alternative -> DraftComputation.Alternative(alternative.precondition(),
                    alternative.operation().let { if (second) it.replace("[Right]", "[MissingTwo]") else it }) },
                calculation.errorCodesToSuppress(), calculation.roundingMode(), calculation.messages())
            for ((carrier, json, code) in listOf(
                Triple("rule", validator.inject(host, rule.withErrorCondition("$firstCall And $secondCall")), "MVK_DUPLICATE_PARAM1"),
                Triple("computation", validator.inject(host, computation), "MVK_INVALID_ENTITY"))) {
                val id = "$carrier:$first:$second"
                val expected = if (first || second) listOf(code) else emptyList()
                val prepared = KernelModelPreparation.expand(json, ReferenceResolvers.selfContained())
                val raw = mutableListOf<RankedNotification>()
                prepared.service().generateValidationCode(prepared.model(), GroovyCodeGeneratorConfig(null), null) { raw += it }
                val whole = originalOutcome(json)
                assertEquals(null, whole.failure, id)
                assertEquals(if (carrier == "computation") expected + expected else expected, raw.map { code(it.message) }, "$id raw")
                assertEquals(expected, whole.callbacks.map { code(it.message) }, "$id public")
                assertEquals(expected, check(ModelSource(json)).findings.filter { it.justifiesRejection }.map { it.code }, "$id portable")
                count++
            }
        }
        assertEquals(8, count)
    }

    @Test
    fun versionCompletedReportHostsKeepTheirDeclarations() = reportHosts(HostCompletion.VERSION)

    @Test
    fun metadataCompletedReportHostsKeepTheirDeclarations() = reportHosts(HostCompletion.METADATA)

    private fun reportHosts(completion: HostCompletion) {
        val mapper = ObjectMapper()
        val harvested = HarvestedModels.index(mapper.readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/harvested-report-checks.json")).readText()))
        val cases = listOf(
            HostCase("repeat", "ConditionMultipleDefectTest.aRepeatOfOneCodeIsKeptForEachPlaceToFix#1", HostControl.CONDITION,
                listOf("MVK_INVALID_ENTITY"), emptyList()),
            HostCase("twins-valid", "ConditionMultipleDefectTest.twoRulesAtOnePathAreNeverPlacedAgainstEachOther#1", HostControl.FIRST_RULE_NAME,
                null, listOf("MVK_INVALID_WILDCARD")),
            HostCase("twins-invalid", "ConditionMultipleDefectTest.twoRulesAtOnePathAreNeverPlacedAgainstEachOther#2", HostControl.FIRST_RULE_NAME,
                null, listOf("MVK_INVALID_WILDCARD", "MVK_INVALID_ENTITY")),
            HostCase("field-rule", "RuleMemberWithholdingTest.aFieldSharingARulesPathKeepsItsOwnFindings#1", HostControl.FIRST_RULE_NAME,
                null, listOf("MVK_FRACT_DIGITS_INVALID", "MVK_ERROR_FIELD_MISSING", "MVK_MISSING_ERROR_FIELD")),
            HostCase("dispatch", "DispatchOrderTest.aPerDeclarationFamilyReportsInDeclarationOrderAndFamiliesStayProducerMajor#1", HostControl.CONDITION,
                listOf("MVK_INCOMPLETE_INPUT", "MVK_BRACKET_MISSING", "MVK_INCOMPLETE_INPUT", "MVK_BRACKET_MISSING"), emptyList()),
        )
        val version = mapper.readTree(host())["header"]["modelVersion"]
        val misses = mutableListOf<String>()
        val ledger = mutableListOf<String>()
        for (case in cases) {
            val original = mapper.readTree(harvested.getValue(case.source)["model"].asText()) as ObjectNode
            val completed = original.deepCopy()
            val header = completed["header"] as ObjectNode
            check(!header.has("modelVersion"))
            header.set<JsonNode>("modelVersion", version)
            val restored = completed.deepCopy().apply { (get("header") as ObjectNode).remove("modelVersion") }
            assertEquals(original, restored, "${case.id}: only the version is added")
            if (completion == HostCompletion.METADATA) {
                val template = mapper.readTree(DocumentModelDraft.named(original["header"]["id"].asText())
                    .locale("en_US").rootGroup(original["content"]["modelRoot"]["rootGroups"][0]["name"].asText()) { }
                    .build().toJson())
                for (member in metadataHeaderMembers) {
                    check(!original["header"].has(member))
                    header.set<JsonNode>(member, template["header"][member])
                }
                for (member in listOf("modelInfo", "modelConfig")) {
                    check(!original["content"].has(member))
                    (completed["content"] as ObjectNode).set<JsonNode>(member, template["content"][member])
                }
                assertMetadataCompletion(original, template, completed)
                val lostDeclaration = completed.deepCopy().apply {
                    (get("content")["modelRoot"]["rootGroups"][0]["Group"]["elements"] as ArrayNode).remove(0)
                }
                assertThrows(AssertionError::class.java) { assertMetadataCompletion(original, template, lostDeclaration) }
            }
            val control = completed.deepCopy()
            val elements = control["content"]["modelRoot"]["rootGroups"][0]["Group"]["elements"]
            val rules = elements.filter { it["type"].asText() == "Rule" }
            when (case.control) {
                HostControl.FIRST_RULE_NAME -> (rules.first() as ObjectNode).put("name",
                    if (case.id == "field-rule") "OtherTwin" else "OtherProbe")
                HostControl.CONDITION -> rules.forEach { (it["Rule"] as ObjectNode).put("errorCondition",
                    if (case.id == "dispatch") "FieldFilled(Code)" else "FieldFilled(Reference)") }
            }
            assertHostControl(case, completed, control)
            val extraEdit = control.deepCopy().apply { (get("header") as ObjectNode).put("unexpected", true) }
            assertThrows(AssertionError::class.java) { assertHostControl(case, completed, extraEdit) }
            for ((suffix, model) in listOf("completed" to completed, "control" to control)) {
                val id = "${case.id}/$suffix"
                val json = model.toString()
                val outcome = originalOutcome(json)
                val reported = outcome.callbacks.map { code(it.message) }
                val a12 = kernelModelClasses(json)
                val report = check(ModelSource(json))
                val findings = report.findings.filter { it.justifiesRejection }
                val ours = findings.map { it.code }
                if (completion == HostCompletion.METADATA && outcome.failure == null) {
                    if (ours != reported) misses += "$id: completed report differs: A12=$reported, portable=$ours"
                    if (case.id == "repeat" && suffix == "completed")
                        assertEquals(listOf(TextSpan("errorCondition", 12, 23)), findings.map { it.span }, id)
                }
                val expectedCodes = if (suffix == "completed") case.completedCodes else case.controlCodes
                val failureType = when {
                    suffix == "completed" && case.control == HostControl.FIRST_RULE_NAME -> IllegalArgumentException::class.java
                    completion == HostCompletion.VERSION -> NullPointerException::class.java
                    else -> null
                }
                val expectedReports = if (failureType != null) emptyList() else checkNotNull(expectedCodes)
                val expectedClasses = if (failureType != null) setOf("CK_MODEL_NOT_BUILDABLE") else expectedReports.toSet()
                if (outcome.phase != OriginalPhase.CHECK || outcome.failure?.javaClass != failureType ||
                    reported != expectedReports || a12 != expectedClasses)
                    misses += "$id: ${outcome.phase}, ${outcome.failure}, reports=$reported, O1=$a12"
                ledger += listOf(id, outcome.phase.name, outcome.failure?.javaClass?.name ?: "completed",
                    reported.toString(), a12.sorted().toString(), ours.toString()).joinToString("\t")
            }
        }
        assertEquals(10, ledger.size)
        val text = ledger.joinToString("\n", postfix = "\n")
        val output = when (completion) {
            HostCompletion.VERSION -> File("build/checker-near-miss/report-hosts.tsv")
            HostCompletion.METADATA -> File("build/checker-near-miss/report-hosts-metadata.tsv")
        }
        // Disposable observations; the harvest, fixed transformations and committed ledgers retain both populations.
        output.apply { parentFile.mkdirs(); writeText(text) }
        assertEquals(emptyList<String>(), misses, "$completion completion predictions")
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/${output.name}")).readText(), text)
    }

    private fun assertHostControl(case: HostCase, completed: ObjectNode, control: ObjectNode) {
        val undone = control.deepCopy()
        fun rulesIn(model: ObjectNode) = model["content"]["modelRoot"]["rootGroups"][0]["Group"]["elements"]
            .filter { it["type"].asText() == "Rule" }
        val before = rulesIn(completed)
        val after = rulesIn(undone)
        assertEquals(before.size, after.size, "${case.id}: control keeps every rule")
        when (case.control) {
            HostControl.FIRST_RULE_NAME -> {
                assertEquals(if (case.id == "field-rule") "OtherTwin" else "OtherProbe", after.first()["name"].asText())
                (after.first() as ObjectNode).set<JsonNode>("name", before.first()["name"])
            }
            HostControl.CONDITION -> after.zip(before).forEach { (changed, unchanged) ->
                assertEquals(if (case.id == "dispatch") "FieldFilled(Code)" else "FieldFilled(Reference)",
                    changed["Rule"]["errorCondition"].asText())
                (changed["Rule"] as ObjectNode).set<JsonNode>(
                    "errorCondition", unchanged["Rule"]["errorCondition"])
            }
        }
        assertEquals(completed, undone, "${case.id}: control changes only the named member")
    }

    private val metadataHeaderMembers = listOf("modelType", "modelVersion", "labels", "annotations", "modelReferences")

    private fun assertMetadataCompletion(original: ObjectNode, template: JsonNode, completed: ObjectNode) {
        val expectedHeader = (original["header"] as ObjectNode).deepCopy()
        metadataHeaderMembers.forEach { expectedHeader.set<JsonNode>(it, template["header"][it]) }
        assertEquals(expectedHeader, completed["header"])
        val restored = completed.deepCopy()
        restored.set<JsonNode>("header", original["header"])
        for (member in listOf("modelInfo", "modelConfig")) {
            assertEquals(template["content"][member], completed["content"][member])
            (restored["content"] as ObjectNode).remove(member)
        }
        assertEquals(original, restored, "metadata completion preserves the entire declaration tree")
    }

    private enum class HostCompletion { VERSION, METADATA }
    private enum class HostControl { CONDITION, FIRST_RULE_NAME }
    private data class HostCase(
        val id: String, val source: String, val control: HostControl,
        val completedCodes: List<String>?, val controlCodes: List<String>,
    )

    @Test
    fun originalReportFixturesRetainTheirKernelDisposition() {
        val mapper = ObjectMapper()
        val rows = HarvestedModels.expand(mapper.readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/harvested-report-checks.json")).readText()))
        assertEquals(316, rows.size)
        assertEquals(316, rows.map { it["id"].asText() }.distinct().size)
        assertEquals(mapOf("CHECK" to 273, "INPUT" to 42, "PREPARATION" to 1),
            rows.groupingBy { it["kernelPhase"].asText() }.eachCount())
        val control = originalOutcome(host())
        assertEquals(null, control.failure)
        assertEquals(emptyList<RankedNotification>(), control.callbacks)
        assertEquals(emptySet<String>(), kernelModelClasses(host()))
        val observations = mapper.createArrayNode()
        val misses = mutableListOf<String>()
        val clusters = sortedMapOf<String, Int>()
        for (row in rows) {
            val id = row["id"].asText()
            val model = row["model"].asText()
            val outcome = originalOutcome(model)
            var ingressFailure: IndexOutOfBoundsException? = null
            val a12 = try {
                kernelModelClasses(model)
            } catch (failure: IndexOutOfBoundsException) {
                ingressFailure = failure
                null
            }
            val ours = check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code }.toSet()
            observations.addObject().apply {
                put("id", id)
                put("phase", outcome.phase.name)
                put("failure", outcome.failure?.javaClass?.name)
                put("message", outcome.failure?.message)
                put("oracleFailure", ingressFailure?.javaClass?.name)
                putArray("callbacks").apply { outcome.callbacks.forEach { add(it.message) } }
                putArray("classes").apply { a12?.sorted()?.forEach { add(it) } }
            }
            val phase = OriginalPhase.valueOf(row["kernelPhase"].asText())
            val expectedFailure = when (phase) {
                OriginalPhase.INPUT -> outcome.failure is ModelInputException &&
                    outcome.failure.message.orEmpty().contains("Illegal unquoted character ((CTRL-CHAR, code 9))")
                OriginalPhase.PREPARATION -> outcome.failure is IndexOutOfBoundsException &&
                    outcome.failure.message == "Index -1 out of bounds for length 0" && ingressFailure != null
                OriginalPhase.CHECK -> outcome.failure is NullPointerException
                OriginalPhase.EXPANSION -> false
            }
            val expectedClasses = if (phase == OriginalPhase.PREPARATION) null else setOf("CK_MODEL_NOT_BUILDABLE")
            if (outcome.phase != phase || !expectedFailure || outcome.callbacks.isNotEmpty() || a12 != expectedClasses)
                misses += "$id: ${outcome.phase}, ${outcome.failure}, callbacks=${outcome.callbacks.map { code(it.message) }}, classes=$a12"
            val verdict = if (a12 == null) "ORACLE-THREW" else checkerVerdict(a12, ours)
            if (verdict != null) {
                fun Set<String>.column() = if (isEmpty()) "(none)" else sorted().joinToString(",")
                val key = listOf(verdict, a12?.column() ?: checkNotNull(ingressFailure).javaClass.name, ours.column()).joinToString("\t")
                clusters[key] = clusters.getOrDefault(key, 0) + 1
            }
        }
        // Disposable detail; the captured inputs, assertions and committed ledger retain the population.
        val directory = File("build/checker-near-miss").apply { mkdirs() }
        mapper.writerWithDefaultPrettyPrinter().writeValue(File(directory, "report-harvest-observations.json"), observations)
        val ledger = clusters.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .joinToString("") { "${it.value}\t${it.key}\n" }
        File(directory, "report-harvest.tsv").writeText(ledger)
        assertEquals(emptyList<String>(), misses, "original model predictions")
        assertEquals(checkNotNull(javaClass.getResource("/checker-near-miss/report-harvest.tsv")).readText(), ledger)
    }

    private enum class OriginalPhase { INPUT, EXPANSION, PREPARATION, CHECK }

    private data class OriginalOutcome(val phase: OriginalPhase, val failure: RuntimeException?, val callbacks: List<RankedNotification>)

    private fun originalOutcome(model: String): OriginalOutcome {
        val prepared = try {
            KernelModelPreparation.expand(model, ReferenceResolvers.selfContained())
        } catch (failure: ModelInputException) {
            return OriginalOutcome(OriginalPhase.INPUT, failure, emptyList())
        } catch (failure: ModelConsistencyException) {
            return OriginalOutcome(OriginalPhase.EXPANSION, failure, emptyList())
        } catch (failure: IndexOutOfBoundsException) {
            return OriginalOutcome(OriginalPhase.PREPARATION, failure, emptyList())
        }
        val callbacks = mutableListOf<RankedNotification>()
        val failure = try {
            prepared.service().checkConsistency(prepared.model()) { callbacks += it }
            null
        } catch (failure: RuntimeException) {
            failure
        }
        return OriginalOutcome(OriginalPhase.CHECK, failure, callbacks)
    }

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
            val consistency = originalOutcome(row.model)
            assertEquals(OriginalPhase.CHECK, consistency.phase, row.id)
            assertEquals(null, consistency.failure, row.id)
            val whole = consistency.callbacks
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
