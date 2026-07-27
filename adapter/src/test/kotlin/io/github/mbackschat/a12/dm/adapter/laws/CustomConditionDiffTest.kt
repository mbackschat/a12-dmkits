package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.core.rt.a12internal.validation.ICustomCondition
import com.mgmtp.a12.kernel.md.document.api.IDocument
import com.mgmtp.a12.kernel.md.document.api.IEntityInstance
import com.mgmtp.a12.kernel.md.document.api.IFieldInstance
import io.github.mbackschat.a12.dm.adapter.KernelCustomConditions
import io.github.mbackschat.a12.dm.interpreter.CustomCondition as PublicCustomCondition
import io.github.mbackschat.a12.dm.interpreter.CustomConditionRegistry
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.FieldPlacementInput
import io.github.mbackschat.a12.dm.interpreter.GroupPlacementInput
import io.github.mbackschat.a12.dm.interpreter.InterpreterConfiguration
import io.github.mbackschat.a12.dm.interpreter.Interpreter
import io.github.mbackschat.a12.dm.interpreter.InterpreterIntegrationError
import io.github.mbackschat.a12.dm.interpreter.InterpreterIntegrationPhase
import io.github.mbackschat.a12.dm.interpreter.InterpreterIntegrationReason
import io.github.mbackschat.a12.dm.interpreter.DocumentInputV1
import io.github.mbackschat.a12.dm.interpreter.PreparedModel
import io.github.mbackschat.a12.dm.interpreter.ValidationFindingType
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Differential — `CustomCondition <name>`, the host-callback predicate the rule language can't express. This
 * is the "works with existing implementations" proof: the interpreter side runs a REAL kernel <em>core</em>
 * [ICustomCondition] (the `IData`-based contract projects already implement) through the
 * [KernelCustomConditions] bridge, while the kernel oracle runs the SAME logic as an md condition over its
 * modern `validateFull(DocumentV2, …customConditionFactory)` path (the path that reliably invokes data-reading
 * conditions — KERNEL-FINDINGS "CustomCondition"). Identical firing + polarity across scenarios proves the
 * interpreter's MECHANISM matches the kernel for the common `FieldFilled(error) And CustomCondition` shape
 * (guarded row, polarity VALUE) AND that the bridge faithfully feeds an existing core condition the data.
 * Callback-owned formal/relevance decisions and fieldless-leaf empty eligibility are locked separately by
 * [CustomConditionInvocationDiffTest].
 *
 * Both sides express the same trivial business logic ("the error field's value is ACME"); the differential
 * verifies the surrounding mechanism, not the (identical) predicate.
 */
class CustomConditionDiffTest {

    private val order = RuntimeLaws.model("models/order.dm.json")

    // The condition is authored with the rulekit typed DSL ([m]) and rendered to the canonical string fed to
    // BOTH engines (kernel injection + the interpreter) — no free-text condition literal.
    private val m = BoundModel.ofJson(order)
    private val cond = DslRenderer().render(m.field(NAME).filled().and(Dsl.customCondition("NameIsAcme")))
    private val kModel = RuntimeLaws.withRule(order, "/Order", "R", "../CustomerName", "CC", cond)
    private val javaEngine by lazy { RuntimeLaws.kernelJavaEngine(kModel) }
    private val def = ModelDef(
        listOf(FieldDef(NAME, Kind.STRING)),
        listOf(RuleDef("/Order/R", "/Order", cond, "CC", "../CustomerName")),
    )

    /** Kernel side — an md condition reading the error field's value from the document (fires iff "ACME"). */
    private val mdNameIsAcme = object : com.mgmtp.a12.kernel.md.rt.api.ICustomCondition {
        override fun check(
            doc: IDocument,
            relevant: Set<IEntityInstance>?,
            formallyIncorrect: Set<IEntityInstance>,
            errorEntity: IEntityInstance,
        ): Boolean {
            val v = doc.entityInstances.filterIsInstance<IFieldInstance>()
                .firstOrNull { it.path == errorEntity.path && it.repetitions.contentEquals(errorEntity.repetitions) }
                ?.value?.orElse(null)?.toString()
            return v == "ACME"
        }
    }

    /** Interpreter side — a REAL kernel CORE condition (the kind projects ship), run via the bridge. */
    private val coreNameIsAcme = ICustomCondition { data, _, _, errorField -> data.getValue(errorField) == "ACME" }

    private val publicPrepared by lazy { PreparedModel.loadExpandedJson(kModel) }
    private val publicInterpreter by lazy {
        val conditions = CustomConditionRegistry.builder()
            .register("NameIsAcme", PublicCustomCondition { invocation ->
                invocation.document.value(invocation.errorPointer) == "ACME"
            })
            .build()
        publicPrepared.createInterpreter(
            InterpreterConfiguration.builder()
                .customConditions(conditions)
                .build(),
        )
    }

    /** Run the SAME logic on both engines for CustomerName = [name] (null = empty); compare firing + polarity. */
    private fun agree(name: String?, msg: String) {
        val kDoc = RuntimeLaws.doc().group("/Order", 1)
        val iDoc = def.newDocument().group("/Order", 1)
        if (name != null) {
            kDoc.field(NAME, name, 1, 1)
            iDoc.field(NAME, name, 1, 1)
        }
        val k = RuntimeLaws.typeOf(kModel, "CC", kDoc, mapOf("NameIsAcme" to mdNameIsAcme)).map { it.name }.orElse(null)
        val j = javaEngine.messages(javaEngine.instance(kDoc), mapOf("NameIsAcme" to mdNameIsAcme))
            .firstOrNull { it.code() == "CC" }?.type()?.name
        val i = DmInterpreter(def, customConditions = KernelCustomConditions.fromCore(mapOf("NameIsAcme" to coreNameIsAcme)))
            .validate(iDoc).messages.firstOrNull { it.code == "CC" }
            ?.type?.let { if (it == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        val fields = name?.let { listOf(FieldPlacementInput(NAME, listOf(1, 1), it)) }.orEmpty()
        val publicSnapshot = publicPrepared.documents.readDocument(
            DocumentInputV1(
                groups = listOf(GroupPlacementInput("/Order", listOf(1))),
                fields = fields,
            ),
        )
        val public = publicInterpreter.validateFull(publicSnapshot).findings
            .firstOrNull { it.code == "CC" }
            ?.type
            ?.let { if (it == ValidationFindingType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR" }
        assertEquals(false, publicPrepared.createInterpreter().supportReport().fullySupported)
        assertEquals(true, publicInterpreter.supportReport().fullySupported)
        assertEquals(k, j, "$msg  (Groovy-dynamic=$k generated-Java=$j)")
        assertEquals(k, i, "$msg  (Groovy-dynamic=$k interpreter=$i)")
        assertEquals(k, public, "$msg  (Groovy-dynamic=$k public-interpreter=$public)")
    }

    @Test fun acmeFires() = agree("ACME", "value ACME → condition true → fires VALUE")
    @Test fun otherNoFire() = agree("Other", "value Other → condition false → no fire")
    @Test fun guardedAndIsSilentWhenEmpty() = agree(null, "empty guard blocks the complete And → no fire")

    @Test
    fun publicConditionFailureIsNormalizedAtTheEvaluationBoundary() {
        val failure = IllegalStateException("condition failed")
        val interpreter = publicPrepared.createInterpreter(
            InterpreterConfiguration.builder()
                .customConditions(
                    CustomConditionRegistry.builder()
                        .register("NameIsAcme", PublicCustomCondition { throw failure })
                        .build(),
                )
                .build(),
        )

        val error = assertThrows(InterpreterIntegrationError::class.java) {
            interpreter.validateFull(publicSnapshot("ACME"))
        }

        assertEquals(InterpreterIntegrationPhase.EVALUATE, error.phase)
        assertEquals(InterpreterIntegrationReason.CALLBACK_THREW, error.reason)
        assertSame(failure, error.cause)
    }

    @Test
    fun publicConditionCannotReenterItsActiveInterpreter() {
        lateinit var interpreter: Interpreter
        lateinit var snapshot: io.github.mbackschat.a12.dm.interpreter.DocumentSnapshot
        val conditions = CustomConditionRegistry.builder()
            .register("NameIsAcme", PublicCustomCondition {
                interpreter.validateFull(snapshot)
                false
            })
            .build()
        interpreter = publicPrepared.createInterpreter(
            InterpreterConfiguration.builder().customConditions(conditions).build(),
        )
        snapshot = publicSnapshot("ACME")

        val error = assertThrows(InterpreterIntegrationError::class.java) {
            interpreter.validateFull(snapshot)
        }

        assertEquals(InterpreterIntegrationPhase.EVALUATE, error.phase)
        assertEquals(InterpreterIntegrationReason.REENTRANT_EVALUATION, error.reason)
    }

    private fun publicSnapshot(name: String) =
        publicPrepared.documents.readDocument(
            DocumentInputV1(
                groups = listOf(GroupPlacementInput("/Order", listOf(1))),
                fields = listOf(FieldPlacementInput(NAME, listOf(1, 1), name)),
            ),
        )

    private companion object {
        const val NAME = "/Order/CustomerName"
    }
}
