package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.adapter.KernelConsistencyOracle
import io.github.mbackschat.a12.dm.consistency.ModelSource
import io.github.mbackschat.a12.dm.consistency.check
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

    /** The kernel's ERROR classes, in report order; an empty list is admission. */
    fun kernel(model: String): List<String> = oracle.checkConsistency(model, ReferenceResolvers.selfContained())
        .filter { it.severity() == Severity.ERROR }.map {
            Regex("\\[([A-Z][A-Z0-9_]+)]").find(it.message())?.groupValues?.get(1) ?: error(it.message())
        }

    /** The portable checker's rejection-justifying codes, in report order. */
    fun portable(model: String): List<String> =
        check(ModelSource(model)).findings.filter { it.justifiesRejection }.map { it.code }
}
