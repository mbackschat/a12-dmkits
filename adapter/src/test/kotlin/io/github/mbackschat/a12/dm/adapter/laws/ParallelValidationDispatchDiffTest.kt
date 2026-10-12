package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SPEC05/10: method dispatch, joined availability and ordinary frames are separate validation stages. */
class ParallelValidationDispatchDiffTest {
    private fun model(repeated: Boolean = true, frame: Int? = null, rootRules: Boolean = false, indexedMessage: Boolean = false): String {
        val draft = DocumentModelDraft.named("warehouse-join").locale("en_US").rootGroup("Warehouse") { root ->
            if (repeated) root.repeatable(2)
            fun rows(parent: DocumentModelDraft.GroupBuilder, name: String) {
                parent.group(name) { it.repeatable(5).indexField("Code")
                    .numberField("Code") { f -> f.fractionDigits(0) }
                    .numberField("Quantity") { f -> f.fractionDigits(0) } }
            }
            if (frame == null) rows(root, "Orders") else root.group("Batches") { it.repeatable(frame); rows(it, "Orders") }
            rows(root, "Stock")
        }.build().toJson()
        val edit = EditSession.read(draft)
        val root = edit.group("/Warehouse")
        val left = edit.group(if (frame == null) "/Warehouse/Orders" else "/Warehouse/Batches/Orders")
        val right = edit.group("/Warehouse/Stock")
        val l = left.num("Quantity")
        val r = right.num("Quantity")
        val conditions = if (frame != null) linkedMapOf(
            "Both" to l.filled().and(r.filled()),
            "Groups" to Dsl.groupFilled(left.ref()).and(r.filled()),
            "Less" to l.lt(r),
        ) else linkedMapOf(
            "Both" to l.filled().and(r.filled()),
            "Either" to l.filled().or(r.filled()),
            "Less" to l.lt(r),
            "GroupOr" to Dsl.groupFilled(left.ref()).or(r.filled()),
            "Groups" to Dsl.groupFilled(left.ref()).and(Dsl.groupFilled(right.ref())),
            "Absent" to Dsl.groupNotFilled(left.ref()).and(r.filled()),
            "Ancestor" to Dsl.groupFilled(root.ref()).and(l.filled()).and(r.filled()),
        )
        var json = edit.toJson()
        for ((code, condition) in conditions) {
            val owner = if (rootRules) root else left
            json = io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(json, owner.rule { it.name(code + "Rule").code(code).errorField(l)
                .`when`(condition).message(Dsl.en(if (indexedMessage) "Code [" + Dsl.indexValue(left.field("Code")) + "]"
                    else "Check warehouse allocation.")).build() })
        }
        return json
    }

    @Test
    fun indexMessageKeepsTheParallelTokenWithoutAnErrorSideRow() {
        val model = model(repeated = false, indexedMessage = true)
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
        DocumentDifferential.assertMessageTexts(model, doc(false, row(1), row(3)), "Either",
            listOf("/Warehouse[1]/Orders[-5]/Quantity" to "Code [3]", "/Warehouse[1]/Orders[1]/Quantity" to "Code [1]"))
    }

    private fun row(code: Int, quantity: Int? = 1) = """{"Code":$code${quantity?.let { ",\"Quantity\":$it" } ?: ""}}"""
    private fun doc(repeated: Boolean, left: String, right: String): String {
        val body = """{"Orders":[$left],"Stock":[$right]}"""
        return """{"Warehouse":${if (repeated) "[$body]" else body}}"""
    }
    private val mismatches = mutableListOf<String>()

    private fun observe(label: String, model: String, document: String, relevant: Set<RelevantEntity>? = null,
                        pin: (List<MessageSignature>) -> Unit = {}): List<MessageSignature> {
        val kernel = DocumentDifferential.kernelMessages(model, document, true, relevant)
        pin(kernel)
        assertEquals(kernel, DocumentDifferential.kernelMessages(model, document, false, relevant), "$label strategies")
        try {
            val actual = DocumentDifferential.interpreterMessages(model, document, relevant)
            if (kernel != actual) mismatches += "$label expected=$kernel actual=$actual"
        } catch (failure: AssertionError) { mismatches += "$label ${failure.message}" }
        return kernel
    }

    @Test
    fun groupContentAndIndexAvailabilityPrecedeGroupAbsence() {
        val model = model()
        val inputs = listOf(
            "matched" to doc(true, row(1), row(1)),
            "empty-payloads" to doc(true, row(1, null), row(1, null)),
            "missing" to doc(true, "", row(1)),
            "invalid-index" to doc(true, """{"Code":"bad"}""", row(1)),
            "separate-parents" to """{"Warehouse":[{"Orders":[${row(1)}]},{"Stock":[${row(1)}]}]}""",
        )
        for ((label, document) in inputs) observe(label, model, document) { messages ->
            val codes = messages.filter { it.code in setOf("Both", "Either", "GroupOr", "Groups", "Absent", "Ancestor") }.map { it.code }.sorted()
            assertEquals(when (label) {
                "matched" -> listOf("Ancestor", "Both", "Either", "GroupOr", "Groups")
                "empty-payloads" -> listOf("GroupOr", "Groups")
                "missing", "separate-parents" -> if (label == "missing") listOf("Absent", "Either", "GroupOr") else listOf("Absent", "Either", "Either", "GroupOr", "GroupOr")
                else -> listOf("Either", "GroupOr")
            }, codes, label)
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun selection(errorRows: List<Int>, indexRows: List<Int> = listOf(0)): Set<RelevantEntity> = buildSet {
        for (row in errorRows) add(RelevantEntity("/Warehouse/Orders/Quantity", if (row == 0) listOf(0, 0, 0) else listOf(1, row, 1)))
        for (row in indexRows) add(RelevantEntity("/Warehouse/Orders/Code", listOf(0, row, 0)))
        add(RelevantEntity("/Warehouse/Stock", listOf(0, 0)))
    }

    @Test
    fun compressedDispatchDoesNotWidenOperandRelevance() {
        for (repeated in listOf(true, false)) for (rootRules in listOf(false, true)) {
            val model = model(repeated, rootRules = rootRules)
            val document = doc(repeated, row(1) + "," + row(2), row(1) + "," + row(3))
            for ((label, selection) in listOf("first" to selection(listOf(1)), "two" to selection(listOf(1, 2)),
                "wildcard" to selection(listOf(0)), "concrete-indices" to selection(listOf(0), listOf(1, 2)))) {
                observe("$repeated/$rootRules/$label", model, document, selection) { messages ->
                    assertEquals(if (label == "wildcard") 1 else 0, messages.count { it.code == "Less" },
                        "$repeated/$label unmatched numeric read")
                    val either = messages.filter { it.code == "Either" }
                    assertEquals(if (label == "first") { if (repeated) 0 else 2 } else 3, either.size, "$repeated/$label dispatch")
                    assertEquals(if (repeated && label == "first") 0 else 3, messages.count { it.code == "GroupOr" }, "$repeated/$label group dispatch")
                    assertEquals(if (label != "wildcard") 0 else 1,
                        messages.count { it.code == "Absent" }, "$repeated/$label absence")
                }
            }
        }
        val repeated = model()
        val body = """{"Orders":[${row(1)},${row(2)}],"Stock":[${row(1)},${row(3)}]}"""
        val document = """{"Warehouse":[$body,$body]}"""
        for ((label, errors) in listOf(
            "first-outer" to listOf(listOf(1, 0, 0)),
            "same-inner" to listOf(listOf(1, 1, 1), listOf(2, 1, 1)),
            "different-inner" to listOf(listOf(1, 1, 1), listOf(2, 2, 1)),
        )) {
            val relevant = setOf(RelevantEntity("/Warehouse/Orders/Code", listOf(0, 0, 0)),
                RelevantEntity("/Warehouse/Stock", listOf(0, 0))) +
                errors.map { RelevantEntity("/Warehouse/Orders/Quantity", it) }
            observe(label, repeated, document, relevant) { messages ->
                assertEquals(when (label) { "first-outer" -> 3; "same-inner" -> 0; else -> 6 },
                    messages.count { it.code == "GroupOr" }, label)
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun ordinaryFrameDomainIsSharedByFullAndPartialValidation() {
        for (bound in listOf(3, 6)) {
            val model = model(repeated = false, frame = bound)
            for (emptyFirst in listOf(false, true)) {
                val first = if (emptyFirst) "{}" else """{"Orders":[${row(1)}]}"""
                val document = """{"Warehouse":{"Batches":[$first,{"Orders":[${row(2)}]}],"Stock":[${row(1, 2)}]}}"""
                for (relevant in listOf(null, setOf(RelevantEntity("/Warehouse", listOf(0))))) {
                    observe("frame$bound/$emptyFirst/$relevant", model, document, relevant) { messages ->
                        assertEquals(if (bound == 3) 3 else 2, messages.count { it.code == "Less" }, "frame$bound absent slots")
                        assertEquals(if (emptyFirst) 0 else 1, messages.count { it.code == "Both" }, "frame$bound matched key")
                    }
                }
                if (bound == 3 && !emptyFirst) {
                    val frameSelection = setOf(RelevantEntity("/Warehouse/Batches", listOf(0, 1)), RelevantEntity("/Warehouse/Stock", listOf(0, 0)))
                    observe("frame1 selector", model, document, frameSelection) { messages ->
                        assertEquals(1, messages.count { it.code == "Less" })
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    private fun parallelAssociationReferences(row: Int): List<String> = listOf(
        "/Warehouse[1]/Orders[$row]/Code",
        "/Warehouse[1]/Orders[$row]/Quantity",
        "/Warehouse[1]/Stock[1]/Code",
        "/Warehouse[1]/Stock[1]/Quantity",
    ).sorted()

    private fun parallelAssociationMessage(code: String, row: Int) = MessageSignature(
        code, "VALUE_ERROR", "/Warehouse[1]/Orders[$row]/Quantity",
        parallelAssociationReferences(row), emptyList(),
    )

    /** IF376 keeps first-admitted message identity; duplicate value reads retain their separate unavailable state. */
    private fun projectParallelDuplicateWinner(messages: List<MessageSignature>, label: String): List<MessageSignature> {
        val associationCodes = setOf("Either", "GroupOr")
        val selected = messages.filter { it.code in associationCodes }.map { message ->
            val row = (1..2).singleOrNull { message.pointer == "/Warehouse[1]/Orders[$it]/Quantity" }
            checkNotNull(row) { "$label has no admitted duplicate owner: $message" }
            assertEquals(parallelAssociationMessage(message.code, row), message,
                "$label changes only one coherent duplicate association, with exact type/ref/fill")
            row
        }
        assertEquals(2, selected.size, "$label retains both independently firing rules")
        assertEquals(1, selected.distinct().size, "$label uses one association for the queried key")
        return messages.map { message ->
            if (message.code in associationCodes) parallelAssociationMessage(message.code, 1) else message
        }.sorted()
    }

    @Test
    fun parallelMessageIdentityDoesNotMakeDuplicateValuesAvailable() {
        val failures = mutableListOf<String>()
        val model = model(repeated = false)
        val document = doc(false, row(1, 2) + "," + row(1, 3), row(1, 1))
        val cases = listOf(
            Triple("full", null, 1),
            Triple("partial-both", selection(listOf(0), listOf(1, 2)), 1),
            Triple("partial-first", selection(listOf(0), listOf(1)), 1),
            Triple("partial-second", selection(listOf(0), listOf(2)), 2),
        )
        assertEquals(cases.size, cases.map { it.first }.toSet().size)
        for ((id, relevant, selectedRow) in cases) {
            val duplicate = id == "full" || id == "partial-both"
            val expectedUnique = if (duplicate) (1..2).map { row ->
                val pointer = "/Warehouse[1]/Orders[$row]/Code"
                MessageSignature("uniqueIndex", "VALUE_ERROR", pointer, listOf(pointer), emptyList())
            }.sorted() else emptyList()
            val expectedCodes = if (duplicate) listOf("Either", "GroupOr") else
                listOf("Ancestor", "Both", "Either", "GroupOr", "Groups")
            val kernel = listOf(true, false).map { dynamic ->
                val strategy = if (dynamic) "groovy" else "java"
                val observed = DocumentDifferential.kernelMessages(model, document, dynamic, relevant)
                assertEquals(expectedUnique, observed.filter { it.code == "uniqueIndex" }, "$id/$strategy physical duplicate owners")
                assertEquals(expectedCodes, observed.filter { it.code != "uniqueIndex" }.map { it.code }.sorted(),
                    "$id/$strategy duplicate value availability")
                assertEquals(if (duplicate) 0 else 1, observed.count { it.code == "Both" },
                    "$id/$strategy association identity cannot admit duplicate values")
                if (duplicate) {
                    println("$id/$strategy raw parallel duplicate owners: ${observed.filter { it.code in setOf("Either", "GroupOr") }}")
                    projectParallelDuplicateWinner(observed, "$id/$strategy")
                } else {
                    assertEquals(parallelAssociationMessage("Either", selectedRow), observed.single { it.code == "Either" },
                        "$id/$strategy admits only the selected index row")
                    observed
                }
            }
            assertEquals(kernel[0], kernel[1], "$id/kernel metadata apart from IF376")
            try {
                val interpreted = DocumentDifferential.interpreterMessages(model, document, relevant)
                assertEquals(parallelAssociationMessage("Either", selectedRow), interpreted.single { it.code == "Either" },
                    "$id/explicit first-admitted interpreter policy")
                assertEquals(expectedUnique, interpreted.filter { it.code == "uniqueIndex" }, "$id/interpreter physical duplicate owners")
                assertEquals(if (duplicate) 0 else 1, interpreted.count { it.code == "Both" }, "$id/interpreter duplicate value availability")
                assertEquals(kernel[0], interpreted, "$id/complete metadata apart from IF376")
            } catch (failure: AssertionError) { failures += "$id: ${failure.message}" }
        }
        assertEquals(emptyList<String>(), failures)
    }

}
