package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.core.ReferenceResolvers
import io.github.mbackschat.a12.dm.core.Severity
import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.LocalizedText
import io.github.mbackschat.a12.dm.rulekit.write.ModelWriter

/**
 * The typed Library carrier behind the 2026-10-02 message-parameter grids (SPEC-2026-10-01-02, -03).
 *
 * Its nesting is the one those entries' parent walks need: `Loans` sits one level below the root, `Shelf` two
 * and `Bin` three. `Book` is declared twice, so a bare `Book` is the short-name ambiguity, while `Borrower` is
 * unique and never read by a condition here unless a row says so. Message text stays raw because its tokens,
 * the invalid ones included, are the measured input.
 */
internal object MessageParameterProbes {
    private val oracle = KernelConsistencyOracle()

    fun library(shortNames: Boolean = true): String {
        val draft = DocumentModelDraft.named("library-message-parameters").locale("en_US")
            .rootGroup("Library") { library -> library.stringField("Branch") { }
                .group("Loans") { loans -> loans.stringField("Book") { }
                    .group("Shelf") { shelf -> shelf.stringField("Slot") { }
                        .group("Bin") { it.stringField("Tag") { } } } }
                .group("Returns") { returns -> returns.stringField("Book") { }.stringField("Borrower") { } }
            }.build().toJson()
        return EditSession.read(draft).setFieldRefByShortNameAllowed(shortNames).toJson()
    }

    /**
     * One rule declared at [declaring], its error field that group's [errorField], reading what [reads] acquires
     * from the session and carrying [messages].
     */
    fun rule(base: String, declaring: String, errorField: String, vararg messages: LocalizedText,
        reads: (EditSession) -> Condition): String {
        val edit = EditSession.read(base)
        val group = edit.group(declaring)
        val condition = reads(edit)
        return ModelWriter.addRule(base, group.rule {
            it.name("CheckMessageParameter").code("MESSAGE_PARAMETER").errorField(group.field(errorField))
                .`when`(condition).message(*messages).build()
        })
    }

    fun en(parameter: String?): LocalizedText = Dsl.en(parameter?.let { "Check $it." } ?: "Check.")

    /** The common hosts replay these exact typed model bytes and separately recorded kernel classes. */
    fun replay(grid: String, rows: List<Triple<String, String, List<String>>>, declines: Map<String, String> = emptyMap(),
        resource: String = "message-parameter-decoder.json") {
        val ids = rows.map { it.first }.toSet()
        org.junit.jupiter.api.Assertions.assertEquals(rows.size, ids.size, "$grid: independent identities")
        require(ids.containsAll(declines.keys) && declines.values.all { it.isNotBlank() })
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val expected = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(rows.mapIndexed { ordinal, (id, model, classes) ->
            mapOf("id" to "$grid:$id", "grid" to grid, "ordinal" to ordinal, "model" to model, "classes" to classes,
                "decline" to declines[id])
        }).toList()
        val stored = HarvestedModels.expand(mapper.readTree(checkNotNull(javaClass.getResource(
            "/checker-near-miss/$resource")).readText()))
            .filter { it["grid"].asText() == grid }.sortedBy { it["ordinal"].asInt() }
        org.junit.jupiter.api.Assertions.assertEquals(expected, stored, "$grid: exact portable replay")
    }

    /**
     * The kernel's ERROR classes, in report order; an empty list is admission. The one codeless sentence a message
     * can draw, a character outside the BMP (KERNEL-FINDINGS KF489), is named [UNSUPPORTED_SIGNS]; any other
     * codeless message still fails the probe.
     */
    fun kernel(model: String): List<String> = oracle.checkConsistency(model, ReferenceResolvers.selfContained())
        .filter { it.severity() == Severity.ERROR }.map {
            Regex("\\[([A-Z][A-Z0-9_]+)]").find(it.message())?.groupValues?.get(1)
                ?: UNSUPPORTED_SIGNS.takeIf { _ -> it.message().endsWith("The field contains one or several unsupported signs.") }
                ?: error(it.message())
        }

    const val UNSUPPORTED_SIGNS = "unsupported signs (codeless)"

    /** The portable checker's rejection-justifying codes, in report order. */
    fun portable(model: String): List<String> =
        rejectingCodes(model)
}
