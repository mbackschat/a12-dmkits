package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.edit.EditSession
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity

/**
 * The models the separate-destination topology family needs BEYOND its original one-level Number shape: a
 * two-level repeatable Number chain and the one- and two-level String siblings (a12-kernel-lean
 * `SPEC-2026-08-26-01`, `-02`, `-03`).
 *
 * **Why they are separate models rather than more fields on `RetainedComputationFixture`.** That fixture is
 * serialized into a committed artifact the `:interpreter` common tests embed, so widening it would move a
 * shared baseline for a question that is only about depth and target family. These models carry exactly one
 * direct projection each, so a topology observation cannot be explained by a second computation.
 *
 * Each model pairs a direct copy computation with the ordinary row-iterated PRESENCE rule the later-validation
 * control needs. The rule condition is A12's error condition, so it reports exactly at the rows whose target
 * holds a value — which is what makes "padded empty rows do not fire" observable.
 *
 * The `Amount` ceiling and the `Label` length ceiling exist for the same reason: a source value above them
 * makes the copy source-classify as ERRORED, which is the only way to author the non-creation control.
 */
internal object SeparateDestinationDepthFixtures {

    const val ROOT = "/Order"
    const val LINES = "$ROOT/Lines"
    const val OUTER = "$ROOT/Outer"
    const val INNER = "$OUTER/Inner"

    const val CANDIDATE = "Candidate"
    const val AMOUNT = "Amount"
    const val SOURCE = "Source"
    const val LABEL = "Label"

    const val NESTED_CANDIDATE = "$INNER/$CANDIDATE"
    const val NESTED_AMOUNT = "$INNER/$AMOUNT"
    const val LINE_SOURCE = "$LINES/$SOURCE"
    const val LINE_LABEL = "$LINES/$LABEL"
    const val NESTED_SOURCE = "$INNER/$SOURCE"
    const val NESTED_LABEL = "$INNER/$LABEL"

    const val AMOUNT_CODE = "AMOUNT_POSITIVE"
    const val LABEL_CODE = "LABEL_PRESENT"

    /** Two repeatable levels, `Outer` then `Inner`, with a direct `Amount := [Candidate]` Number projection. */
    fun nestedNumberModel(): String {
        val structure = DocumentModelDraft.named("nested-amount-projection").locale("en_US")
            .rootGroup("Order") { order ->
                order.group("Outer") { outer ->
                    outer.repeatable(5).group("Inner") { inner ->
                        inner.repeatable(5)
                            .numberField(CANDIDATE) { it.fractionDigits(0) }
                            .numberField(AMOUNT) { it.fractionDigits(0).max("9") }
                    }
                }
            }
            .build().toJson()
        val session = EditSession.read(structure)
        val inner = session.group(INNER)
        val candidate = inner.num(CANDIDATE)
        val amount = inner.num(AMOUNT)
        inner.addComputation(amount) { computation ->
            computation.name("AmountProjection")
                .commonPrecondition(candidate.filled())
                .calc(candidate.value())
                .message(Dsl.en("Project the selected amount."))
                .build()
        }
        inner.addRule { rule ->
            rule.name("AmountPositive").code(AMOUNT_CODE).errorField(amount)
                .`when`(amount.asNumber().gt(0))
                .severity(Severity.ERROR)
                .message(Dsl.en("This line carries a projected amount."))
                .build()
        }
        return session.validate().toJson()
    }

    /** One repeatable `Lines` level with a direct `Label := [Source]` String projection. */
    fun flatStringModel(): String = withStringProjection(
        DocumentModelDraft.named("line-label-projection").locale("en_US")
            .rootGroup("Order") { order ->
                order.group("Lines") { lines -> stringCells(lines.repeatable(5)) }
            }
            .build().toJson(),
        LINES,
    )

    /** The same direct String projection one repeatable level deeper, under `Outer` then `Inner`. */
    fun nestedStringModel(): String = withStringProjection(
        DocumentModelDraft.named("nested-label-projection").locale("en_US")
            .rootGroup("Order") { order ->
                order.group("Outer") { outer ->
                    outer.repeatable(5).group("Inner") { inner -> stringCells(inner.repeatable(5)) }
                }
            }
            .build().toJson(),
        INNER,
    )

    private fun stringCells(group: DocumentModelDraft.GroupBuilder) {
        group
            .stringField(SOURCE) { it.maxLength(20) }
            .stringField(LABEL) { it.maxLength(3) }
    }

    private fun withStringProjection(structure: String, groupPath: String): String {
        val session = EditSession.read(structure)
        val group = session.group(groupPath)
        val source = group.str(SOURCE)
        val label = group.str(LABEL)
        group.addComputation(label) { computation ->
            computation.name("LabelProjection")
                .commonPrecondition(source.filled())
                .calc(source.asString().expression())
                .message(Dsl.en("Project the selected label."))
                .build()
        }
        group.addRule { rule ->
            rule.name("LabelPresent").code(LABEL_CODE).errorField(label)
                .`when`(label.length().gt(0))
                .severity(Severity.ERROR)
                .message(Dsl.en("This line carries a projected label."))
                .build()
        }
        return session.validate().toJson()
    }
}
