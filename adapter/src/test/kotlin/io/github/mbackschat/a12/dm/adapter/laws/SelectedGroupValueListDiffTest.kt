package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.bridge.InterpreterConditionReadBack
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListValueListCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.StringLiteral
import io.github.mbackschat.a12.dm.rulekit.dsl.FieldListEntityListCondition
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListOperand
import io.github.mbackschat.a12.dm.rulekit.dsl.ValueListQuantifier
import io.github.mbackschat.a12.dm.rulekit.dsl.NumberLiteral
import io.github.mbackschat.a12.dm.rulekit.read.ReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.Locale

class SelectedGroupValueListDiffTest {
    private fun model(numeric: Boolean): String {
        val kind = if (numeric) FieldKind.NUMBER else FieldKind.STRING
        val draft = DocumentModelDraft.named("selected-parcel-values").locale("en_US")
            .rootGroup("Order") { order ->
                order.stringField("Reference") { }.stringField("Wanted") { }.field("Needle", kind)
                    .group("Packages") { packages ->
                        packages.repeatable(3).indexField("Code").stringField("Code") { }
                            .group("Pair") { it.field("A", kind).field("B", kind) }
                            .group("Rows") { it.repeatable(3).field("A", kind).field("B", kind) }
                    }
            }.build().toJson()
        val session = EditSession.read(draft)
        val order = session.group("/Order")
        val reference = order.str("Reference")
        val wanted = order.str("Wanted").field()
        val needle = order.field("Needle")
        for (starred in listOf(false, true)) for (fieldKey in listOf(false, true)) {
            val path = if (starred) "Packages/Rows*" else "Packages/Pair"
            val scope = order.flattenGroup(path)
            val group = if (fieldKey) scope.forIndex(wanted) else scope.forIndex("P1")
            val cells = listOf("A", "B").map { name ->
                if (starred) {
                    val list = order.flatten("$path/$name")
                    if (fieldKey) list.forIndex(wanted) else list.forIndex("P1")
                } else {
                    val field = order.field("$path/$name")
                    (if (fieldKey) field.semanticIndex(wanted) else field.semanticIndex("P1")).index()
                }
            }
            if (!numeric) for (quantifier in listOf(ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED)) {
                val code = "${if (starred) "STAR" else "PAIR"}_${if (fieldKey) "FIELD" else "LITERAL"}_CONSTANT_${quantifier.name}"
                order.addRule { it.name(code).code(code).errorField(reference)
                    .`when`(reference.filled().and(FieldListValueListCondition(quantifier,
                        listOf(group), listOf(StringLiteral("watched")))))
                    .message(Dsl.en("Check selected constants.")).build() }
            }
            for ((carrier, operands) in listOf("GROUP" to listOf<ValueListOperand>(group), "CELLS" to cells)) {
                for (valuesSide in listOf(false, true)) for ((name, quantifier) in listOf(
                    "ONE" to ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                    "NO" to ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                    "NOTALL" to ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED,
                )) {
                    val condition = FieldListEntityListCondition(quantifier,
                        if (valuesSide) listOf(needle) else operands,
                        if (valuesSide) operands else listOf(needle))
                    val code = "${if (starred) "STAR" else "PAIR"}_${if (fieldKey) "FIELD" else "LITERAL"}_${carrier}_${if (valuesSide) "VALUES" else "FIELDS"}_$name"
                    order.addRule { it.name(code).code(code).errorField(reference)
                        .`when`(reference.filled().and(condition)).message(Dsl.en("Check selected values.")).build() }
                }
            }
        }
        return session.toJson()
    }

    @Test
    fun portableReaderAndCheckerPreserveAdmittedSelections() {
        for (numeric in listOf(false, true)) {
            val json = model(numeric)
            assertEquals(emptyList<Any>(), check(ModelSource(json)).findings)
            val session = EditSession.read(json)
            val order = session.group("/Order")
            val kernel = io.github.mbackschat.a12.dm.adapter.KernelConditionReadBack()
                .readAll(json, ReferenceResolvers.selfContained())
            val portable = InterpreterConditionReadBack().readAll(json, ReferenceResolvers.selfContained())
            assertEquals(kernel.keys, portable.keys)
            for ((path, result) in kernel) {
                fun render(read: ReadResult): String {
                    val condition = assertInstanceOf(ReadResult.Converted::class.java, read, path).condition()
                    return order.rule { it.name("Read").code("Read").errorField(order.str("Reference"))
                        .`when`(condition).message(Dsl.en("Check selection.")).build() }.render()
                }
                assertEquals(render(result), render(portable.getValue(path)), path)
            }
        }
    }

    @Test
    fun absentUnselectedFixedGroupPreservesOmissionPolarity() {
        val json = DocumentModelDraft.named("absent-fixed-values").locale("en_US")
            .rootGroup("Order") { it.numberField("Needle") { }
                .group("Pair") { pair -> pair.numberField("A") { }.numberField("B") { } } }
            .build().toJson()
        val session = EditSession.read(json)
        val order = session.group("/Order")
        val needle = order.num("Needle").field()
        for (valuesSide in listOf(false, true)) {
            val condition = FieldListEntityListCondition(ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                if (valuesSide) listOf(needle) else listOf(order.flattenGroup("Pair")),
                if (valuesSide) listOf(order.flattenGroup("Pair")) else listOf(needle))
            val code = if (valuesSide) "Values" else "Fields"
            order.addRule { it.name(code).code(code).errorField(needle).`when`(condition)
                .message(Dsl.en("Check missing group.")).build() }
        }
        val model = session.toJson()
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
        val documents = mapOf("absent" to """{"Order":{"Needle":10}}""",
            "empty" to """{"Order":{"Needle":10,"Pair":{}}}""")
        for ((label, document) in documents) {
            val engine = RuntimeLaws.kernelEngine(model)
            assertEquals(listOf("Fields|OMISSION_ERROR", "Values|OMISSION_ERROR"),
                engine.validationMessages(engine.instance(document), null, Locale.US)
                    .map { "${it.errorCode}|${it.messageType}" }.sorted(), label)
        }
        DocumentDifferential.assertAgree(model, documents)
    }

    @Test
    fun computationReadsExpandedColumnsOnlyAsFarAsItsDecision() {
        val session = EditSession.read(model(true))
        val order = session.group("/Order")
        val group = order.flattenGroup("Packages/Rows*").forIndex("P1")
        val cells = listOf("A", "B").map { order.flatten("Packages/Rows*/$it").forIndex("P1") }
        val needle = order.field("Needle")
        val targets = mutableListOf<String>()
        for (carrier in listOf("Group", "Cells")) for (side in listOf("Values", "Fields")) {
            for (name in listOf("One", "No", "NotAll")) {
                val targetName = carrier + side + name
                targets += targetName
                order.numberField(targetName) { it.fractionDigits(0) }
                order.numberField("After$targetName") { it.fractionDigits(0) }
            }
        }
        var jsonModel = session.toJson()
        for ((carrier, operands) in listOf("Group" to listOf<ValueListOperand>(group), "Cells" to cells)) {
            for (valuesSide in listOf(false, true)) for ((name, quantifier) in listOf(
                "One" to ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                "No" to ValueListQuantifier.NO_FIELD_VALUE_INCLUDED,
                "NotAll" to ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED,
            )) {
                val targetName = carrier + (if (valuesSide) "Values" else "Fields") + name
                val target = order.num(targetName)
                val condition = FieldListEntityListCondition(quantifier,
                    if (valuesSide) listOf(needle) else operands,
                    if (valuesSide) operands else listOf(needle))
                jsonModel = TypedRules.inject(jsonModel, order.computation(target) {
                    it.name("${targetName}Comp").`when`(condition).calc(NumberLiteral.of("1"))
                        .message(Dsl.en("Check computed selection.")).build()
                })
                jsonModel = TypedRules.inject(jsonModel, order.computation(order.num("After$targetName")) {
                    it.name("After${targetName}Comp").`when`(target.filled().or(target.notFilled()))
                        .calc(NumberLiteral.of("1")).message(Dsl.en("Check dependent.")).build()
                })
            }
        }
        assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(jsonModel))
        val seeds = targets.flatMap { listOf(it, "After$it") }.joinToString(",") { "\"$it\":9" }
        val documents = linkedMapOf(
            "late invalid after column match" to """{"A":7,"B":"bad"},{"A":10,"B":8}""",
            "invalid reached without column match" to """{"A":7,"B":"bad"},{"A":7,"B":8}""",
            "only second field matches" to """{"A":7,"B":10},{"A":7,"B":8}""",
        ).mapValues { (_, rows) ->
            """{"Order":{"Reference":"dispatch","Wanted":"P1","Needle":10,$seeds,"Packages":[{"Code":"P1","Rows":[$rows]}]}}"""
        }
        for ((label, json) in documents) {
            val observed = DocumentDifferential.kernel(jsonModel, json, dynamic = true)
            fun outcome(target: String): String {
                fun of(name: String) = observed.outcomes[name]?.removePrefix("VALUE|") ?: "9"
                return of(target) + "/" + of("After$target")
            }
            for (target in targets.filter { it.startsWith("Group") }) {
                assertEquals(outcome(target.replaceFirst("Group", "Cells")), outcome(target), "$label: $target")
            }
            val expected = when (label) {
                "late invalid after column match" -> listOf("1/1", "CLEARED/1", "CLEARED/CLEARED")
                "invalid reached without column match" -> listOf("CLEARED/CLEARED", "CLEARED/CLEARED", "CLEARED/CLEARED")
                else -> listOf("1/1", "CLEARED/1", "1/1")
            }
            assertEquals(expected, listOf("GroupFieldsOne", "GroupFieldsNo", "GroupValuesOne").map(::outcome), label)
        }
        DocumentDifferential.assertAgree(jsonModel, documents)
    }

    @Test
    fun anEarlierFieldDecisionDoesNotReadALaterSelectedGroupsIndex() {
        for (starred in listOf(false, true)) {
            val session = EditSession.read(model(true))
            val order = session.group("/Order")
            order.numberField("WantedNumber") { }
            val needle = order.field("Needle")
            val wanted = order.field("WantedNumber")
            for (carrier in listOf("Group", "Cells")) for (op in listOf("One", "No", "NotAll")) {
                order.numberField(carrier + op) { }
                order.numberField("After" + carrier + op) { }
            }
            var model = session.toJson()
            val path = if (starred) "Packages/Rows*" else "Packages/Pair"
            for ((carrier, operands) in listOf("Group" to listOf<ValueListOperand>(order.flattenGroup(path).forIndex("P1")),
                "Cells" to listOf("A", "B").map {
                    if (starred) order.flatten("$path/$it").forIndex("P1") else order.field("$path/$it").semanticIndex("P1").index()
                })) {
                for ((name, quantifier) in listOf("One" to ValueListQuantifier.AT_LEAST_ONE_FIELD_VALUE_INCLUDED,
                    "No" to ValueListQuantifier.NO_FIELD_VALUE_INCLUDED, "NotAll" to ValueListQuantifier.NOT_ALL_FIELD_VALUES_INCLUDED)) {
                    model = TypedRules.inject(model, order.computation(order.num(carrier + name)) {
                        it.name(carrier + name + "Comp")
                            .`when`(FieldListEntityListCondition(quantifier, listOf(needle) + operands, listOf(wanted)))
                            .calc(NumberLiteral.of("1")).message(Dsl.en("Check ordered selection.")).build()
                    })
                    val target = order.num(carrier + name)
                    model = TypedRules.inject(model, order.computation(order.num("After" + carrier + name)) {
                        it.name("After" + carrier + name + "Comp").`when`(target.filled().or(target.notFilled()))
                            .calc(NumberLiteral.of("1")).message(Dsl.en("Check dependent selection.")).build()
                    })
                }
            }
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            val seeds = listOf("Group", "Cells").flatMap { carrier -> listOf("One", "No", "NotAll").flatMap { listOf("\"$carrier$it\":9", "\"After$carrier$it\":9") } }.joinToString(",")
            val documents = listOf(10, 7).associate { first -> first.toString() to
                """{"Order":{"Needle":$first,"WantedNumber":10,$seeds,"Packages":[{"Code":"P1","Pair":{"A":7,"B":7},"Rows":[{"A":7,"B":7}]},{"Code":"P1","Pair":{"A":7,"B":7},"Rows":[{"A":7,"B":7}]}]}}""" }
            for ((first, json) in documents) {
                val observed = DocumentDifferential.kernel(model, json, dynamic = true)
                assertEquals(if (first == "10") "VALUE|1" else "CLEARED", observed.outcomes["GroupOne"], first)
                assertEquals(if (first == "10") "VALUE|1" else "CLEARED", observed.outcomes["AfterGroupNo"], first)
                assertEquals(if (first == "7") "VALUE|1" else "CLEARED", observed.outcomes["GroupNotAll"], first)
                for (op in listOf("One", "No", "NotAll")) {
                    assertEquals(observed.outcomes["Cells$op"], observed.outcomes["Group$op"], "$first/$op")
                }
                // This control isolates read poison from duplicate-index diagnostic row ownership;
                // the complete diagnostic contract is compared by the other witnesses in this class.
                for (other in listOf(DocumentDifferential.kernel(model, json, dynamic = false),
                    DocumentDifferential.interpreter(model, json))) {
                    assertEquals(observed.outcomes, other.outcomes, "$starred/$first")
                    assertEquals(observed.operandErrors, other.operandErrors, "$starred/$first")
                }
            }
        }
    }

    @Test
    fun selectedParentAndDeclaredLowerSlotsControlPartialExtent() {
        for (numeric in listOf(false, true)) {
            val model = model(numeric)
            val dynamic = RuntimeLaws.kernelEngine(model)
            val static = RuntimeLaws.kernelJavaEngine(model)
            val prepared = PreparedModel.loadExpandedJson(model)
            for (match in listOf(0, 1, 3, -1)) {
                fun value(matching: Boolean) = if (numeric) { if (matching) "10" else "7" }
                    else if (matching) "\"watched\"" else "\"ordinary\""
                val rows = (1..3).joinToString(",") { """{"A":${value(false)},"B":${value(it == match)}}""" }
                val key = if (match == -1) "P2" else "P1"
                val json = """{"Order":{"Reference":"dispatch","Wanted":"P1","Needle":${value(true)},"Packages":[{"Code":"$key","Rows":[$rows]}]}}"""
                for ((parent, slots) in listOf(1 to listOf(1, 2), 1 to listOf(1, 2, 3),
                    2 to listOf(1, 2, 3), 0 to listOf(1, 2, 3), 1 to listOf(0))) {
                    val relevant = setOf(RuntimeLaws.Relevant.of("/Order/Reference", 0, 0),
                        RuntimeLaws.Relevant.of("/Order/Wanted", 0, 0),
                        RuntimeLaws.Relevant.of("/Order/Needle", 0, 0),
                        RuntimeLaws.Relevant.of("/Order/Packages/Code", 0, 0, 0)) +
                        slots.map { RuntimeLaws.Relevant.of("/Order/Packages/Rows", 0, parent, it) }
                    val label = "numeric=$numeric/match=$match/parent=$parent/slots=$slots"
                    val messages = dynamic.partialValidationMessages(dynamic.instance(json), relevant, null, Locale.US)
                    val expected = messages.map { "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}" }.sorted()
                    assertEquals(expected, static.partialValidationMessages(static.instance(json), relevant, null, Locale.US).map { "${it.errorCode}|${it.messageType}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}" }.sorted(), label)
                    val fired = messages.map { it.errorCode }.toSet()
                    val selectedParent = parent == 0 || parent == 1
                    val complete = slots == listOf(0) || slots.size == 3
                    val no = if (match == -1) parent == 0 && complete else match == 0 && selectedParent && complete
                    val one = match > 0 && selectedParent && (slots == listOf(0) || match in slots)
                    assertEquals(no, "STAR_LITERAL_GROUP_FIELDS_NO" in fired, label)
                    assertEquals(one, "STAR_LITERAL_GROUP_FIELDS_ONE" in fired, label)
                    val report = prepared.createInterpreter().validatePart(prepared.documents.readDocument(json), relevant.map { pointer ->
                        prepared.model.relevantPointer(pointer.path(), pointer.reps().map {
                            if (it == 0) RelevantCoordinate.All else RelevantCoordinate.Exact(it)
                        })
                    })
                    assertEquals(emptyList<Any>(), report.unsupported, label)
                    assertEquals(expected, report.findings.map {
                        "${it.code}|${it.type.name}_ERROR|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
                    }.sorted(), label)
                }
            }
        }
    }

    @Test
    fun selectionExpandsBothFieldsAndExcludesTheOtherParent() {
        val discrepancies = mutableListOf<String>()
        for (numeric in listOf(false, true)) {
            val model = model(numeric)
            assertEquals(emptyList<String>(), RuntimeLaws.consistencyMessages(model))
            val dynamic = RuntimeLaws.kernelEngine(model)
            val static = RuntimeLaws.kernelJavaEngine(model)
            val prepared = PreparedModel.loadExpandedJson(model)
            for (match in listOf("A", "B", "neither", "other-parent")) {
                fun value(matches: Boolean) = if (numeric) { if (matches) "10" else "7" }
                    else if (matches) "\"watched\"" else "\"ordinary\""
                fun pair(other: Boolean) = """{"A":${value(if (other) match == "other-parent" else match == "A")},"B":${value(!other && match == "B")}}"""
                val selected = pair(false)
                val other = pair(true)
                val json = """{"Order":{"Reference":"dispatch","Wanted":"P1","Needle":${value(true)},"Packages":[{"Code":"P1","Pair":$selected,"Rows":[$selected]},{"Code":"P2","Pair":$other,"Rows":[$other]}]}}"""
                val label = "numeric=$numeric/match=$match"
                val expected = dynamic.validationMessages(dynamic.instance(json), null, Locale.US)
                val kernel = expected.map {
                    "${it.errorCode}|${it.messageType}|${it.severity}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted()
                assertEquals(kernel, static.validationMessages(static.instance(json), null, Locale.US).map {
                    "${it.errorCode}|${it.messageType}|${it.severity}|${it.errorFieldPointer.fullName()}|${it.errorFieldPointer.repetitionIndexes()}"
                }.sorted(), "$label: kernel strategies")
                val fired = expected.map { it.errorCode }.toSet()
                for (starred in listOf("STAR", "PAIR")) for (key in listOf("FIELD", "LITERAL")) {
                    for (side in listOf("FIELDS", "VALUES")) for (op in listOf("ONE", "NO", "NOTALL")) {
                        val groupCode = "${starred}_${key}_GROUP_${side}_$op"
                        val controlCode = "${starred}_${key}_CELLS_${side}_$op"
                        assertEquals(controlCode in fired, groupCode in fired, "$label: $groupCode vs explicit fields")
                        val matches = match == "A" || match == "B"
                        val fires = when (op) {
                            "ONE" -> matches
                            "NO" -> !matches
                            else -> side == "FIELDS" || !matches
                        }
                        assertEquals(fires, groupCode in fired, "$label: $groupCode independent witness")
                    }
                }
                val report = prepared.createInterpreter().validateFull(prepared.documents.readDocument(json))
                val actual = report.findings.map {
                    "${it.code}|${it.type.name}_ERROR|${it.severity}|${it.errorPath.fullName}|${it.errorPath.repetitionIndexes}"
                }.sorted()
                if (report.unsupported.isNotEmpty() || actual != kernel) {
                    discrepancies += "$label: unsupported=${report.unsupported}; expected=$kernel actual=$actual"
                }
            }
        }
        assertEquals(emptyList<String>(), discrepancies)
    }
}
