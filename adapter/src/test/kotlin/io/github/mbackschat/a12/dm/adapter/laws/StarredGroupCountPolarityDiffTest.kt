package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential + tri-check — the fired polarity of `NumberOfFilledGroups(G*)` over a **STARRED repeatable** group,
 * the other half of the group-count entity tally whose fixed-group half is `FixedGroupValueInFieldsPolarityDiffTest`.
 *
 * **The DECLARED extent is the whole point, and the zero-row row is what proves it.** The kernel spans its entity
 * iterator over the declared maximum (`setzeEndIndicesAufMaxGesetzt`), so unused capacity is an uncounted entity:
 * an EMPTY repeatable group is growable even though there is no row to look at, and a group filled to its declared
 * capacity is NOT growable even though a repeatable group intuitively "can always take another row". An
 * instantiated-row tally gets both of those backwards, which is exactly what the interpreter did before [IF312]:
 * `leafOmission(Expr.Star(<group path>))` reduced to "does any row exist", so a full group read as growable and an
 * empty one as fixed.
 *
 * The middle row is the ordinary control: capacity left, so both accounts agree on growable. The instantiated-but-
 * EMPTY row is the second measurement and it corrected a prediction written for this file: it is VALUE, not
 * OMISSION, because a repeatable terminal row is given STRUCTURALLY (IF46/IF68) — the row's presence is what
 * counts, not its content — so two rows in a capacity-2 group exhaust the declared extent whether or not either
 * holds a value. That is the same read [Interpreter.groupFillStates] already published for the group-fill
 * quantifiers, which is why this count reuses it instead of tallying cells.
 */
class StarredGroupCountPolarityDiffTest {

    private val model = DocumentModelDraft.named("order-shipment-count").locale("en_US")
        .rootGroup("Order") { order ->
            order.numberField("Threshold") { it.fractionDigits(0, 0) }
            order.group("Shipments") { g ->
                g.repeatable(2)
                g.field("Dispatched", FieldKind.BOOLEAN)
                g.field("Tracked", FieldKind.BOOLEAN)
            }
        }.build().toJson()

    private val bound = BoundModel.ofJson(model)

    private val modelJson = TypedRules.inject(
        model,
        bound.rule(bound.group(ROOT)).name(CODE).code(CODE).errorField(bound.num(THRESHOLD))
            .`when`(
                bound.num(THRESHOLD).asNumber()
                    .ne(Dsl.numberOfFilledGroups(bound.flattenGroup("$SHIPMENTS*").group())),
            )
            .severity(Severity.ERROR)
            .message(Dsl.en("The recorded threshold does not match the shipments actually recorded.")).build(),
    )

    private val definition = ModelLoader.load(modelJson)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(modelJson) }

    /** Tri-check `code|type` over a row list; `null` marks an instantiated but empty row. */
    private fun sigs(rows: List<String?>): List<String> {
        fun kernelDoc(): RuntimeLaws.Doc {
            val d = RuntimeLaws.doc().group(ROOT, 1).field(THRESHOLD, "5", 1, 1)
            rows.forEachIndexed { i, v ->
                d.group(SHIPMENTS, 1, i + 1)
                v?.let { d.field(DISPATCHED, it, 1, i + 1, 1) }
            }
            return d
        }
        val interpreterDoc = definition.newDocument().group(ROOT, 1).field(THRESHOLD, "5", 1, 1)
        rows.forEachIndexed { i, v ->
            interpreterDoc.group(SHIPMENTS, 1, i + 1)
            v?.let { interpreterDoc.field(DISPATCHED, it, 1, i + 1, 1) }
        }

        val groovy = RuntimeLaws.messages(modelJson, kernelDoc()).map { "${it.code()}|${it.type().name}" }.sorted()
        val javaStatic = javaEngine.validationSignaturesSorted(javaEngine.instance(kernelDoc()))
            .map { it.split("|").take(2).joinToString("|") }.sorted()
        val interpreter = DmInterpreter(definition).validateFull(interpreterDoc)
            .map { "${it.code}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

        assertEquals(groovy, javaStatic, "rows=$rows — groovy vs java-static")
        assertEquals(groovy, interpreter, "rows=$rows — kernel vs interpreter")
        return groovy
    }

    @Test fun theFiredPolarityReadsTheDeclaredExtentNotTheInstantiatedRows() {
        assertEquals(
            listOf(
                "filled to capacity => [$CODE|VALUE_ERROR]",
                "capacity left      => [$CODE|OMISSION_ERROR]",
                "an empty row       => [$CODE|VALUE_ERROR]",
                "no rows at all     => [$CODE|OMISSION_ERROR]",
            ),
            listOf(
                "filled to capacity => ${sigs(listOf("true", "true"))}",
                "capacity left      => ${sigs(listOf("true"))}",
                "an empty row       => ${sigs(listOf("true", null))}",
                "no rows at all     => ${sigs(emptyList())}",
            ),
            "the fired message type as BOTH kernel strategies report it",
        )
    }

    private companion object {
        const val ROOT = "/Order"
        const val THRESHOLD = "/Order/Threshold"
        const val SHIPMENTS = "/Order/Shipments"
        const val DISPATCHED = "/Order/Shipments/Dispatched"
        const val CODE = "SHIPMENT_TALLY"
    }
}
