package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.RelevantCoordinate
import io.github.mbackschat.a12.dm.interpreter.model.RelevantEntity
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a fired message references when its condition selects a row by semantic index (a12-kernel-lean
 * SPEC-2026-10-07-02). Each rule sits in a depot and reports on `Note`; the selection is a bin read by the depot's
 * `Wanted` key, a literal key, a group under the bin, or a starred list under it, and three rules put the selection
 * on an `Or` branch the evaluation never reaches. The differential compares the referenced and fill-to-fix pointers
 * of every message on both kernel strategies and the interpreter, across a match, a miss, an empty key, a
 * duplicated key and an invalid selected payload.
 *
 * Own domain (a store's depots and their keyed bins).
 */
class SelectedMessageReferenceDiffTest {

    private val model: String = run {
        val draft = DocumentModelDraft.named("store-selected-references").locale("en_US")
            .rootGroup("Store") { s -> s
                .stringField("Ref") { }
                .group("Depots") { d -> d.repeatable(2)
                    .stringField("Wanted") { }
                    .stringField("Note") { }
                    .group("Bins") { b -> b.repeatable(4).indexField("BinCode")
                        .stringField("BinCode") { }
                        .numberField("Stock") { it.fractionDigits(0) }
                        .group("Shelf") { sh -> sh
                            .numberField("Aisle") { it.fractionDigits(0) }
                            .numberField("Level") { it.fractionDigits(0) } }
                        .group("Slots") { sl -> sl.repeatable(3).numberField("Qty") { it.fractionDigits(0) } } } } }
            .build().toJson()
        val session = EditSession.read(draft)
        val depot = session.group("/Store/Depots")
        val note = depot.str("Note")
        val wanted = depot.field("Wanted")
        val stock = session.group("/Store/Depots/Bins").num("Stock")
        val shelf = depot.flattenGroup("Bins/Shelf").forIndex(wanted)
        val slots = depot.flatten("Bins/Slots*/Qty").forIndex(wanted)
        val rules: List<Pair<String, Condition>> = listOf(
            "RefDynamic" to note.filled().and(stock.forIndex(wanted).ge(0)),
            "RefLiteral" to note.filled().and(stock.forIndex("b").ge(0)),
            "RefGroup" to note.filled().and(Dsl.sum(shelf).ge(0)),
            "RefUnreached" to note.filled().or(stock.forIndex(wanted).gt(0)),
            "RefUnreachedGroup" to note.filled().or(Dsl.sum(shelf).gt(0)),
            "RefUnreachedStar" to note.filled().or(Dsl.sum(slots).gt(0)),
        )
        var json = session.toJson()
        for ((name, condition) in rules) {
            json = TypedRules.inject(json, depot.rule {
                it.name(name).code(name).errorField(note.field()).`when`(condition)
                    .message(Dsl.en("Check the depot.")).build()
            })
        }
        json
    }

    private fun bin(code: String, stock: String) =
        """{"BinCode":"$code","Stock":"$stock","Shelf":{"Aisle":"1","Level":"2"},"Slots":[{"Qty":"3"}]}"""

    private fun depot(wanted: String?, note: String?, vararg bins: String) = "{" + listOfNotNull(
        wanted?.let { "\"Wanted\":\"$it\"" }, note?.let { "\"Note\":\"$it\"" },
        "\"Bins\":[" + bins.joinToString(",") + "]",
    ).joinToString(",") + "}"

    private fun store(vararg depots: String) = """{"Store":{"Ref":"S-1","Depots":[${depots.joinToString(",")}]}}"""

    private val documents = linkedMapOf(
        "the first bin selected" to store(depot("a", "n", bin("a", "1"), bin("b", "2"))),
        "the second bin selected" to store(depot("b", "n", bin("a", "1"), bin("b", "2"))),
        "no bin carries the key" to store(depot("zz", "n", bin("a", "1"), bin("b", "2"))),
        "the key is empty" to store(depot(null, "n", bin("a", "1"), bin("b", "2"))),
        "the selected payload is invalid" to store(depot("a", "n", bin("a", "x"), bin("b", "2"))),
        "the note is empty" to store(depot("a", null, bin("a", "1"), bin("b", "2"))),
        "two depots select different bins" to
            store(depot("a", "n", bin("a", "1"), bin("b", "2")), depot("b", "n", bin("a", "4"), bin("b", "5"))),
    )

    @Test
    fun theModelIsKernelConsistent() {
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(model))
    }

    @Test
    fun allThreeEnginesAgreeOnEveryDocument() {
        DocumentDifferential.assertAgree(model, documents)
    }

    /**
     * Which row a DUPLICATED key's pointer names, pinned on both kernel strategies alone. The value reads refuse the
     * duplicate and their rules stay silent, while the unreached selections still name one row, and it is not the
     * first or the last matching one in general: A12 names the row its index cache was written with last, in the
     * iteration order of a hash map over the Document's cells; the interpreter names the first match instead, a
     * characterized divergence (INTERPRETER-FINDINGS IF376). These two
     * rows happen to name the first match, which is why they are kept out of the agreement above.
     */
    @Test
    fun aDuplicatedKeysPointerIsTheKernelsCacheRow() {
        val duplicates = linkedMapOf(
            "rows 2 and 3" to (store(depot("b", "n", bin("a", "1"), bin("b", "2"), bin("b", "3"))) to 2),
            "rows 1 and 3" to (store(depot("b", "n", bin("b", "1"), bin("a", "2"), bin("b", "3"))) to 1),
        )
        val observed = duplicates.mapValues { (_, probe) ->
            listOf(true, false).map { dynamic ->
                DocumentDifferential.kernel(model, probe.first, dynamic).messages
                    .single { it.toString().startsWith("RefUnreached|") }.referenced
                    .filter { it.endsWith("/Stock") }
            }
        }
        val expected = duplicates.mapValues { (_, probe) ->
            List(2) { listOf("/Store[1]/Depots[1]/Bins[${probe.second}]/Stock") }
        }
        assertEquals(expected, observed, "the duplicated key's pointer row, on both strategies")
    }

    @Test
    fun indexedMessageAdmissionUsesAuthoredAncestryAndPositiveKeyedPresence() {
        val edit = EditSession.read(model)
        val depot = edit.group("/Store/Depots")
        depot.addGroup("Signals") { it.addField("Ready", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING) }
        val base = edit.toJson()
        val note = depot.str("Note")
        val stock = depot.num("Bins/Stock")
        val selected = stock.field().semanticIndex("b")
        val bins = depot.flattenGroup("Bins").forIndex("b")
        val off = depot.flattenGroup("Signals")
        val comparison = stock.forIndex("b").gt(0)
        data class Row(val id: String, val condition: Condition, val expected: List<String>, val ancestry: Boolean = false)
        val invalidGroup = listOf("INVALID_GROUP")
        val invalidPresence = listOf("INVALID_USE_OF_SEMANTIC_INDEX")
        val rows = listOf(
            Row("ancestry-parent", depot.ref().filled(), listOf("ITER_PARAM_WITH_STARREFERENCE"), true),
            Row("ancestry-field", stock.filled(), emptyList(), true),
            Row("ancestry-star", Dsl.atLeastOneFieldFilled(depot.flatten("Bins*/Stock")),
                listOf("ITER_PARAM_WITH_STARREFERENCE"), true),
            Row("ancestry-key", selected.filled(), invalidGroup, true),
            Row("key-field-presence", selected.filled(), emptyList()),
            Row("key-group-presence", bins.filled(), emptyList()),
            Row("key-comparison", comparison, invalidPresence),
            Row("key-negative", selected.notFilled(), invalidPresence),
            Row("key-and", selected.filled().and(comparison), emptyList()),
            Row("key-or-unrelated", selected.filled().or(note.filled()), invalidPresence),
            Row("key-or-covered", selected.filled().or(bins.filled()), emptyList()),
            Row("key-all-fields", Dsl.allFieldsFilled(selected.index(), note.field()), emptyList()),
            Row("key-all-groups", Dsl.allGroupsFilled(bins, off), emptyList()),
            Row("key-any-fields", Dsl.atLeastOneFieldFilled(selected.index(), note.field()), invalidPresence),
            Row("key-many-fields", Dsl.moreThanOneFieldFilled(selected.index(), note.field()), invalidPresence),
            Row("key-any-groups", Dsl.atLeastOneGroupFilled(bins, off), invalidPresence),
        )
        assertEquals(rows.size, rows.map { it.id }.toSet().size)
        val authored = rows.map { row ->
            val condition = if (row.ancestry) row.condition else note.filled().and(row.condition)
            val token = if (row.ancestry) Dsl.repetitionIndex(depot.group("Bins")) else Dsl.repetitionIndex(bins)
            row.id to io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(base, depot.rule {
                it.name("MessageProbe").code("MESSAGE_PROBE")
                    .errorField(if (row.ancestry) stock.field() else note.field()).`when`(condition)
                    .message(Dsl.en("Bin " + token)).build()
            })
        }
        val kernel = authored.map { (id, json) -> id to MessageParameterProbes.kernel(json) }
        MessageParameterProbes.replay("indexed-group", authored.mapIndexed { i, (id, json) ->
            Triple(id, json, rows[i].expected) })
        assertEquals(rows.map { it.id to it.expected }, kernel, "independent kernel admission")
        assertEquals(kernel, authored.map { (id, json) -> id to MessageParameterProbes.portable(json) },
            "portable admission uses the same authored conditions")
    }

    /**
     * Whether a keyed row token's proof needs every operand under the key, as the unkeyed row token's does
     * (KERNEL-FINDINGS KF675). The grid above pairs the keyed operand with `Note`, outside the keyed row, in every
     * list-presence row, so it cannot tell "list presence never proves a keyed row" from "only when an operand lies
     * outside it". These rows hold every operand under key `b`, with the mixed row re-measured beside them.
     */
    @Test
    fun aKeyedTokensListProofNeedsEveryOperandUnderTheKey() {
        val edit = EditSession.read(model)
        val depot = edit.group("/Store/Depots")
        depot.addGroup("Signals") { it.addField("Ready", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING) }
        edit.group("/Store/Depots/Bins").addGroup("Tag") { it.addField("Colour", io.github.mbackschat.a12.dm.modelkit.FieldKind.STRING) }
        val base = edit.toJson()
        val note = depot.str("Note")
        val stock = depot.num("Bins/Stock").field().semanticIndex("b")
        val aisle = depot.field("Bins/Shelf/Aisle").semanticIndex("b")
        val bins = depot.flattenGroup("Bins").forIndex("b")
        val shelf = depot.flattenGroup("Bins/Shelf").forIndex("b")
        val tag = depot.flattenGroup("Bins/Tag").forIndex("b")
        val signals = depot.flattenGroup("Signals")
        val rows = linkedMapOf(
            "any-fields, one operand outside the key" to Dsl.atLeastOneFieldFilled(stock.index(), note.field()),
            "any-fields, every operand under the key" to Dsl.atLeastOneFieldFilled(stock.index(), aisle.index()),
            "many-fields, every operand under the key" to Dsl.moreThanOneFieldFilled(stock.index(), aisle.index()),
            "any-groups, one operand outside the key" to Dsl.atLeastOneGroupFilled(bins, signals),
            "any-groups, every operand under the key" to Dsl.atLeastOneGroupFilled(shelf, tag),
        )
        val authored = rows.mapValues { (_, condition) ->
            io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(base, depot.rule {
                it.name("MessageProbe").code("MESSAGE_PROBE").errorField(note.field())
                    .`when`(note.filled().and(condition)).message(Dsl.en("Bin " + Dsl.repetitionIndex(bins))).build()
            })
        }
        val kernel = authored.mapValues { (_, json) -> MessageParameterProbes.kernel(json) }
        assertEquals(linkedMapOf<String, List<String>>(
            "any-fields, one operand outside the key" to listOf("INVALID_USE_OF_SEMANTIC_INDEX"),
            "any-fields, every operand under the key" to emptyList(),
            "many-fields, every operand under the key" to emptyList(),
            "any-groups, one operand outside the key" to listOf("INVALID_USE_OF_SEMANTIC_INDEX"),
            "any-groups, every operand under the key" to emptyList(),
        ), kernel, "A12")
        assertEquals(kernel, authored.mapValues { (_, json) -> MessageParameterProbes.portable(json) }, "portable")
    }

    @Test
    fun indexMessageAdmissionTracksTheIndexedGroupsOwnLevel() {
        val edit = EditSession.read(model)
        val depot = edit.group("/Store/Depots")
        val stock = depot.num("Bins/Stock")
        val code = depot.field("Bins/BinCode")
        val rows = listOf(
            "parent" to depot.ref().filled(),
            "owner-star" to Dsl.atLeastOneFieldFilled(depot.flatten("Bins*/Stock")),
            "owner-key" to stock.field().semanticIndex("b").filled(),
            "lower-star" to Dsl.atLeastOneFieldFilled(depot.flatten("Bins/Slots*/Qty")),
        )
        val authored = rows.map { (id, condition) -> id to io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(model,
            depot.rule { it.name("MessageProbe").code("MESSAGE_PROBE").errorField(stock)
                .`when`(stock.filled().and(condition)).message(Dsl.en(Dsl.indexValue(code))).build() }) }
        val expected = rows.map { (id, _) -> id to if (id != "lower-star")
            listOf("INVALID_INDEX_FIELD_REP_ITER") else emptyList() }
        assertEquals(expected, authored.map { (id, json) -> id to MessageParameterProbes.kernel(json) })
        MessageParameterProbes.replay("indexed-ancestry", authored.mapIndexed { i, (id, json) ->
            Triple(id, json, expected[i].second) })
        assertEquals(expected, authored.map { (id, json) -> id to MessageParameterProbes.portable(json) })
    }

    @Test
    fun portableMessageReplaysHaveIndependentKernelTextControls() {
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val rows = HarvestedModels.index(mapper.readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/message-parameter-decoder.json")).readText()))
        val document = """{"Store":{"Depots":[{"Note":"n","Bins":[{"BinCode":"a"},{"BinCode":"b","Stock":1,"Slots":[{},{},{"Qty":2}]}]}]}}"""
        for ((id, path, text) in listOf(
            Triple("indexed-group:key-field-presence", "/Store[1]/Depots[1]/Note", "Bin 2"),
            Triple("indexed-ancestry:lower-star", "/Store[1]/Depots[1]/Bins[2]/Stock", "b"),
        )) DocumentDifferential.assertMessageTexts(rows.getValue(id)["model"].asText(), document, "MESSAGE_PROBE", listOf(path to text))
    }

    @Test
    fun keyedGroupMessageUsesItsSelectedRowUnderEachParent() {
        val edit = EditSession.read(model)
        val depot = edit.group("/Store/Depots")
        val note = depot.str("Note")
        val stock = depot.field("Bins/Stock")
        val wanted = depot.field("Wanted")
        val literal = depot.flattenGroup("Bins").forIndex("b")
        val dynamic = depot.flattenGroup("Bins").forIndex(wanted)
        val cases = listOf(
            Triple("literal", false, store(depot("b", "n", bin("a", "1"), bin("b", "2")))),
            Triple("field", true, store(depot("b", "n", bin("a", "1"), bin("b", "2")))),
            Triple("missing", false, store(depot("b", "n", bin("a", "1")))),
            Triple("duplicate", false, store(depot("b", "n", bin("b", "1"), bin("b", "2")))),
            Triple("parents", true, store(depot("b", "n", bin("a", "1"), bin("b", "2")),
                depot("b", "n", bin("b", "3"), bin("a", "4")))),
        )
        org.junit.jupiter.api.Assertions.assertAll(cases.map { (id, fieldKey, json) -> org.junit.jupiter.api.function.Executable {
            val selected = if (fieldKey) stock.semanticIndex(wanted) else stock.semanticIndex("b")
            val authored = io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(model, depot.rule {
                it.name("MessageProbe").code("MESSAGE_PROBE").errorField(note)
                    .`when`(note.filled().and(selected.filled()))
                    .message(Dsl.en("Bin [" + Dsl.repetitionIndex(if (fieldKey) dynamic else literal) + "]")).build()
            })
            assertEquals(emptyList<String>(), MessageParameterProbes.kernel(authored), id)
            val expected = when (id) {
                "missing", "duplicate" -> emptyList()
                "parents" -> listOf("/Store[1]/Depots[1]/Note" to "Bin [2]", "/Store[1]/Depots[2]/Note" to "Bin [1]")
                else -> listOf("/Store[1]/Depots[1]/Note" to "Bin [2]")
            }
            DocumentDifferential.assertMessageTexts(authored, json, "MESSAGE_PROBE", expected)
        } })
    }

    @Test
    fun indexValueMessageUsesTheErrorFieldsIndexedAncestor() {
        val edit = EditSession.read(model)
        val depot = edit.group("/Store/Depots")
        val stock = depot.num("Bins/Stock")
        val quantity = depot.num("Bins/Slots/Qty")
        val code = depot.field("Bins/BinCode")
        val rows = listOf(
            Triple("filled", false, store(depot(null, "n", bin("b", "2")))),
            Triple("absent", false, store(depot(null, "n", """{"Stock":2}"""))),
            Triple("deeper", true, store(depot(null, "n", bin("a", "1"),
                """{"BinCode":"b","Slots":[{},{},{"Qty":2}]}"""))),
        )
        org.junit.jupiter.api.Assertions.assertAll(rows.map { (id, deeper, json) -> org.junit.jupiter.api.function.Executable {
            val target = if (deeper) quantity else stock
            val authored = io.github.mbackschat.a12.dm.rulekit.write.ModelWriter.addRule(model, depot.rule {
                it.name("MessageProbe").code("MESSAGE_PROBE").errorField(target)
                    .`when`(target.filled())
                    .message(Dsl.en("Code [" + Dsl.indexValue(code) + "]")).build()
            })
            assertEquals(emptyList<String>(), MessageParameterProbes.kernel(authored), id)
            val expected = when (id) {
                "absent" -> listOf("/Store[1]/Depots[1]/Bins[1]/Stock" to "Code []")
                "deeper" -> listOf("/Store[1]/Depots[1]/Bins[1]/Slots[1]/Qty" to "Code [a]",
                    "/Store[1]/Depots[1]/Bins[2]/Slots[3]/Qty" to "Code [b]")
                else -> listOf("/Store[1]/Depots[1]/Bins[1]/Stock" to "Code [b]")
            }
            DocumentDifferential.assertMessageTexts(authored, json, "MESSAGE_PROBE", expected)
        } })
    }

    private val cacheDepot = "/Store/Depots"
    private val cachePayloads = listOf("Note", "Wanted", "Bins/Stock", "Bins/Shelf/Aisle", "Bins/Shelf/Level", "Bins/Slots/Qty")
        .map { RelevantEntity.anyRep("$cacheDepot/$it") }.toSet()
    private val cacheUnreachedCodes = setOf("RefUnreached", "RefUnreachedGroup", "RefUnreachedStar")

    private data class CacheSelectionCase(
        val id: String,
        val relevant: Set<RelevantEntity>?,
        val selectedRow: Int,
        val duplicate: Boolean = false,
    )

    private fun cacheIndexRows(vararg rows: Int): Set<RelevantEntity> =
        rows.map { RelevantEntity("$cacheDepot/Bins/BinCode", listOf(1, 1, it, 1)) }.toSet()

    private fun cacheExpectedReferences(parent: Int, selected: Int): List<MessageSignature> {
        val depot = "/Store[1]/Depots[$parent]"
        val bin = "$depot/Bins[$selected]"
        val common = listOf("$depot/Note", "$depot/Wanted", "$bin/BinCode")
        return listOf(
            "RefUnreached" to listOf("$bin/Stock"),
            "RefUnreachedGroup" to listOf("$bin/Shelf[1]/Aisle", "$bin/Shelf[1]/Level"),
            "RefUnreachedStar" to listOf("$bin/Slots[0]/Qty"),
        ).map { (code, payloads) ->
            MessageSignature.fields(code, "VALUE_ERROR", "$depot/Note", (common + payloads).sorted(), emptyList())
        }.sorted()
    }

    /** IF376 permits only the chosen duplicate row to differ; generated diagnostics and unknown/wildcard coordinates remain exact. */
    private fun cacheFirstAdmittedBoundary(messages: List<MessageSignature>, parent: Int, rows: List<Int>): List<MessageSignature> {
        val prefixes = rows.map { "/Store[1]/Depots[$parent]/Bins[$it]/" }
        val first = prefixes.first()
        val selected = messages.filter { it.code in cacheUnreachedCodes }.flatMap { listOf(it.pointer) + it.referenced + it.fillToFix }
            .mapNotNull { original -> prefixes.firstOrNull(original::startsWith) }.distinct()
        assertEquals(1, selected.size, "every member of one queried association uses the same admitted duplicate row")
        return messages.map { message ->
            if (message.code !in cacheUnreachedCodes) message else {
                fun pointer(original: String): String = prefixes.firstOrNull(original::startsWith)
                    ?.let { first + original.removePrefix(it) } ?: original
                message.copy(pointerKey = pointer(message.pointerKey), referencedKeys = message.referencedKeys.map(::pointer).sorted(),
                    fillToFixKeys = message.fillToFixKeys.map(::pointer).sorted())
            }
        }.sorted()
    }

    @Test
    fun selectedMessageAssociationsAdmitOnlyRelevantIndexRows() {
        val input = store(depot("b", "n", bin("a", "1"), bin("b", "2"), bin("b", "3")))
        val cases = listOf(
            CacheSelectionCase("full-duplicate", null, 2, duplicate = true),
            CacheSelectionCase("partial-both", cachePayloads + cacheIndexRows(2, 3), 2, duplicate = true),
            CacheSelectionCase("partial-first", cachePayloads + cacheIndexRows(2), 2),
            CacheSelectionCase("partial-second", cachePayloads + cacheIndexRows(3), 3),
            CacheSelectionCase("partial-no-index", cachePayloads, -5),
        )
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        for (case in cases) {
            val kernel = listOf(true, false).map { dynamic ->
                DocumentDifferential.kernelMessages(model, input, dynamic, case.relevant)
            }
            for ((strategy, observed) in kernel.withIndex()) {
                val projected = if (case.duplicate) cacheFirstAdmittedBoundary(observed, 1, listOf(2, 3)) else observed
                assertEquals(cacheExpectedReferences(1, case.selectedRow),
                    projected.filter { it.code in cacheUnreachedCodes }, "${case.id}/kernel-$strategy references")
                if (case.duplicate) {
                    val rows = observed.filter { it.code == "RefUnreached" }.flatMap { it.referenced }
                        .filter { it.endsWith("/Stock") }
                    println("${case.id}/kernel-$strategy raw duplicate selection: $rows")
                    assertEquals(2, observed.count { it.code == "uniqueIndex" }, "${case.id}/kernel-$strategy duplicate owners")
                    assertEquals(listOf(
                        "/Store[1]/Depots[1]/Bins[2]/BinCode", "/Store[1]/Depots[1]/Bins[3]/BinCode",
                    ), observed.filter { it.code == "uniqueIndex" }.map { it.pointer }.sorted())
                }
            }
            val dynamic = if (case.duplicate) cacheFirstAdmittedBoundary(kernel[0], 1, listOf(2, 3)) else kernel[0]
            val static = if (case.duplicate) cacheFirstAdmittedBoundary(kernel[1], 1, listOf(2, 3)) else kernel[1]
            assertEquals(dynamic, static, "${case.id}/kernel metadata apart from IF376")
            val interpreted = DocumentDifferential.interpreterMessages(model, input, case.relevant)
            assertEquals(cacheExpectedReferences(1, case.selectedRow), interpreted.filter { it.code in cacheUnreachedCodes },
                "${case.id}/first-admitted interpreter policy")
            assertEquals(dynamic, interpreted, "${case.id}/complete metadata apart from IF376")
        }
        val none = emptySet<RelevantEntity>()
        assertEquals(emptyList<MessageSignature>(), DocumentDifferential.kernelMessages(model, input, true, none))
        assertEquals(emptyList<MessageSignature>(), DocumentDifferential.kernelMessages(model, input, false, none))
        assertEquals(emptyList<MessageSignature>(), DocumentDifferential.interpreterMessages(model, input, none))
    }

    @Test
    fun selectedMessageQueriesKeepTheirOwnKeyAndParent() {
        val input = store(
            depot("c", "n", bin("a", "1"), bin("a", "2"), bin("c", "3")),
            depot("c", "n", bin("c", "4"), bin("d", "5")),
        )
        for (relevant in listOf(null, setOf(RelevantEntity.anyRep("/Store")))) {
            val dynamic = DocumentDifferential.kernelMessages(model, input, true, relevant)
            assertEquals((cacheExpectedReferences(1, 3) + cacheExpectedReferences(2, 1)).sorted(),
                dynamic.filter { it.code in cacheUnreachedCodes }.sorted(), "unique key survives another key's duplicate")
            assertEquals(dynamic, DocumentDifferential.kernelMessages(model, input, false, relevant))
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(model, input, relevant))
        }
    }

    @Test
    fun absentSelectedReferencesRetainUnknownRowsAndExactFillToFix() {
        val input = store(depot("b", "n", bin("a", "1")))
        val index = "/Store[1]/Depots[1]/Bins[-5]/BinCode"
        val stock = "/Store[1]/Depots[1]/Bins[-5]/Stock"
        val note = "/Store[1]/Depots[1]/Note"
        val expected = MessageSignature.fields("RefLiteral", "OMISSION_ERROR", note,
            listOf(index, stock, note).sorted(), listOf(index, stock).sorted())
        for (relevant in listOf(null, setOf(RelevantEntity.anyRep("/Store")))) {
            val dynamic = DocumentDifferential.kernelMessages(model, input, true, relevant)
            assertEquals(expected, dynamic.single { it.code == "RefLiteral" }, "literal missing-key omission")
            assertEquals(dynamic, DocumentDifferential.kernelMessages(model, input, false, relevant))
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(model, input, relevant))
        }
    }

    private fun cacheDefaultModel(required: Boolean = false, selectedError: Boolean = false, omission: Boolean = false): String {
        val draft = DocumentModelDraft.named("store-bin-default-references").locale("en_US")
            .rootGroup("Store") { store -> store.stringField("Ref") { }
                .group("Depots") { depots -> depots.repeatable(2)
                    .enumField("Wanted") { it.value("a").value("b") }.stringField("Note") { }
                    .numberField("Anchor") { it.fractionDigits(0) }
                    .group("Bins") { bins -> bins.repeatable(4).indexField("BinCode")
                        .enumField("BinCode") {
                            it.value("a").value("b").sValue("b")
                            if (required) it.required()
                        }.numberField("Stock") { it.fractionDigits(0) }
                        .group("Shelf") { shelf -> shelf.numberField("Aisle") { it.fractionDigits(0) }
                            .numberField("Level") { it.fractionDigits(0) } }
                        .group("Slots") { slots -> slots.repeatable(3).numberField("Qty") { it.fractionDigits(0) } }
                    }
                }
            }.build().toJson()
        val edit = EditSession.read(draft)
        val depot = edit.group(cacheDepot)
        val note = depot.str("Note")
        val wanted = depot.field("Wanted")
        val selectedStock = depot.num("Bins/Stock").forIndex(wanted)
        val shelf = depot.flattenGroup("Bins/Shelf").forIndex(wanted)
        val slots = depot.flatten("Bins/Slots*/Qty").forIndex(wanted)
        val independent = if (omission) note.filled().and(depot.num("Anchor").eq(0)) else note.filled()
        val conditions = listOf(
            "RefDynamic" to note.filled().and(selectedStock.ge(0).or(selectedStock.lt(0))),
            "RefUnreached" to independent.or(selectedStock.gt(0)),
            "RefUnreachedGroup" to independent.or(Dsl.sum(shelf).gt(0)),
            "RefUnreachedStar" to independent.or(Dsl.sum(slots).gt(0)),
        )
        for ((code, condition) in conditions.filter { !selectedError || it.first in setOf("RefDynamic", "RefUnreached") }) depot.addRule {
            it.name(code).code(code).errorField(if (selectedError) depot.field("Bins/Stock") else note.field()).`when`(condition)
                .message(Dsl.en("Check the depot.")).build()
        }
        return edit.toJson()
    }

    private data class CacheDefaultCase(val id: String, val bins: List<String>, val fullRow: Int, val readType: String?)

    @Test
    fun defaultMessageAssociationsRemainSeparateFromAuthoredReadAvailability() {
        val json = cacheDefaultModel()
        val mismatches = mutableListOf<String>()
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(json))
        val absent = """{"Stock":"2","Shelf":{"Aisle":"1","Level":"2"},"Slots":[{"Qty":"3"}]}"""
        val cases = listOf(
            CacheDefaultCase("absent", listOf(absent), 1, "VALUE_ERROR"),
            CacheDefaultCase("empty", listOf(bin("", "2")), 1, "VALUE_ERROR"),
            CacheDefaultCase("explicit", listOf(bin("b", "2")), 1, "VALUE_ERROR"),
            CacheDefaultCase("invalid", listOf(bin("invalid", "2")), 1, null),
            CacheDefaultCase("zero", emptyList(), -5, "OMISSION_ERROR"),
            CacheDefaultCase("two-empty", listOf(absent, absent), -5, null),
        )
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        for (case in cases) {
            val input = store(depot("b", "n", *case.bins.toTypedArray()))
            for (partial in listOf(false, true)) {
                val relevant = if (partial) setOf(RelevantEntity.anyRep("/Store")) else null
                val selected = if (partial && case.id != "explicit") -5 else case.fullRow
                val readType = if (partial && case.id in setOf("absent", "empty", "invalid", "two-empty")) null else case.readType
                val dynamic = DocumentDifferential.kernelMessages(json, input, true, relevant)
                assertEquals(cacheExpectedReferences(1, selected), dynamic.filter { it.code in cacheUnreachedCodes },
                    "${case.id}/partial=$partial cache pointers")
                assertEquals(listOfNotNull(readType), dynamic.filter { it.code == "RefDynamic" }.map { it.type },
                    "${case.id}/partial=$partial definite read or UNKNOWN")
                if (case.id == "invalid") assertEquals(1, dynamic.count {
                    it.pointer == "/Store[1]/Depots[1]/Bins[1]/BinCode" && it.code !in cacheUnreachedCodes
                }, "${case.id}/partial=$partial invalid stored index remains reported")
                assertEquals(dynamic, DocumentDifferential.kernelMessages(json, input, false, relevant))
                val actual = DocumentDifferential.interpreterMessages(json, input, relevant)
                if (dynamic != actual) mismatches += "${case.id}/partial=$partial expected=$dynamic actual=$actual"
            }
        }
        val mandatory = cacheDefaultModel(required = true)
        assertEquals(emptyList<String>(), MessageParameterProbes.kernel(mandatory))
        val input = store(depot("b", "n", bin("invalid", "2")))
        for (relevant in listOf(null, setOf(RelevantEntity.anyRep("/Store")))) {
            val dynamic = DocumentDifferential.kernelMessages(mandatory, input, true, relevant)
            assertEquals(cacheExpectedReferences(1, -5), dynamic.filter { it.code in cacheUnreachedCodes }, "mandatory invalid index gets no default")
            assertEquals(emptyList<String>(), dynamic.filter { it.code == "RefDynamic" }.map { it.type })
            assertEquals(dynamic, DocumentDifferential.kernelMessages(mandatory, input, false, relevant))
            assertEquals(dynamic, DocumentDifferential.interpreterMessages(mandatory, input, relevant))
        }
        assertEquals(emptyList<String>(), mismatches)
    }

    @Test
    fun selectedMessageAssociationsDoNotLeakBetweenCallsOrMutateTheDocument() {
        val input = store(depot("b", "n", bin("a", "1"), bin("b", "2"), bin("b", "3")))
        val prepared = PreparedModel.loadExpandedJson(cacheDefaultModel())
        val interpreter = prepared.createInterpreter()
        val snapshot = prepared.documents.readDocument(input)
        val original = prepared.documents.encodeDocument(snapshot)
        val relevant = (cachePayloads + cacheIndexRows(3)).map { entity ->
            prepared.model.relevantPointer(entity.path, entity.indices.map {
                if (it == 0) RelevantCoordinate.all() else RelevantCoordinate.exact(it)
            })
        }
        val selected = listOf(
            interpreter.validateFull(snapshot), interpreter.validatePart(snapshot, relevant), interpreter.validateFull(snapshot),
        ).map { report ->
            assertEquals(emptyList<Any>(), report.unsupported)
            report.findings.filter { it.code in cacheUnreachedCodes }.forEach { finding ->
                assertEquals("Check the depot.", finding.text)
                assertEquals("ERROR", finding.severity.toString())
            }
            report.findings.single { it.code == "RefUnreached" }.referenced
                .single { it.fullName == "$cacheDepot/Bins/Stock" }.repetitionIndexes[2]
        }
        assertEquals(listOf(2, 3, 2), selected)
        assertEquals(original, prepared.documents.encodeDocument(snapshot))
    }

    @Test
    fun associatedPointersRetainOmissionMetadataAndSelectedErrorTargets() {
        val failures = mutableListOf<String>()
        for (selectedError in listOf(false, true)) {
            val json = cacheDefaultModel(selectedError = selectedError, omission = !selectedError)
            val admission = MessageParameterProbes.kernel(json)
            assertEquals(emptyList<String>(), admission)
            for ((label, bins) in listOf("invalid" to listOf(bin("invalid", "2")),
                "duplicate" to listOf(bin("b", "2"), bin("b", "3")))) {
                val input = store(depot("b", "n", *bins.toTypedArray()))
                for (partial in listOf(false, true)) {
                    val relevant = if (partial) setOf(RelevantEntity.anyRep("/Store")) else null
                    val dynamic = DocumentDifferential.kernelMessages(json, input, true, relevant)
                    val static = DocumentDifferential.kernelMessages(json, input, false, relevant)
                    assertEquals(dynamic, static)
                    val projected = if (label == "duplicate") cacheFirstAdmittedBoundary(dynamic, 1, listOf(1, 2)) else dynamic
                    val selected = if (label == "invalid" && partial) -5 else 1
                    val depot = "/Store[1]/Depots[1]"
                    val expected = cacheExpectedReferences(1, selected).let { messages ->
                        if (selectedError) messages.filter { it.code == "RefUnreached" }
                            .map { it.copy(pointerKey = "$depot/Bins[$selected]/Stock[1]") }
                        else messages.map { message ->
                            val references = (message.referencedKeys + "$depot/Anchor[1]").sorted()
                            message.copy(type = "OMISSION_ERROR", referencedKeys = references,
                                fillToFixKeys = references.filterNot { it == "$depot/Note[1]" })
                        }
                    }
                    assertEquals(expected, projected.filter { it.code in cacheUnreachedCodes })
                    val actual = DocumentDifferential.interpreterMessages(json, input, relevant)
                    if (projected != actual) failures += "selectedError=$selectedError/$label/partial=$partial expected=$projected actual=$actual"
                }
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

}
