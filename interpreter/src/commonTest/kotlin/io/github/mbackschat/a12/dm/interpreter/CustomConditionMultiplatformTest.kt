package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.CustomCondition
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CustomCondition <name>` over the **multiplatform** SPI — runs on JVM AND JS (Node), proving the
 * cross-platform requirement: a pure-Kotlin [CustomCondition] (the twin of the kernel's `IData`-based
 * `ICustomCondition`, identical on JVM core + TypeScript) plugs into the interpreter on every target. The
 * common guarded-And semantics (fire iff `check` true after the guard, polarity VALUE, unregistered reported
 * unsupported) are confirmed against the live kernel in `adapter`; this locks them on the pure engine + JS.
 * Callback inputs and structural empty eligibility are locked by [CustomConditionInvocationTest]. A JVM
 * consumer adapts an existing kernel `ICustomCondition`; here we use a pure-Kotlin impl reading the error
 * field's value via the data view.
 */
class CustomConditionMultiplatformTest {

    private val def = ModelDef(
        listOf(FieldDef(NAME, Kind.STRING)),
        listOf(RuleDef("/Order/R", "/Order", COND, "CC", "../CustomerName")),
    )

    /** Fires iff the error field's value is "ACME" — reads it through the data view by the passed errorField. */
    private val nameIsAcme = CustomCondition { data, _, _, errorField -> data.getValue(errorField) == "ACME" }

    private fun docOf(name: String?): Document {
        val doc = def.newDocument().group("/Order", 1)
        if (name != null) doc.field(NAME, name, 1, 1)
        return doc
    }

    private fun message(name: String?) =
        DmInterpreter(def, customConditions = mapOf("NameIsAcme" to nameIsAcme))
            .validate(docOf(name)).messages.firstOrNull { it.code == "CC" }

    @Test fun firesWhenConditionHolds() = assertEquals(true, message("ACME") != null, "ACME → custom condition true → fires")
    @Test fun noFireWhenConditionFalse() = assertEquals(false, message("Other") != null, "Other → custom condition false → no fire")
    @Test fun guardedAndIsSilentWhenEmpty() =
        assertEquals(false, message(null) != null, "empty FieldFilled guard blocks the complete And")
    @Test fun polarityIsValue() = assertEquals(MsgType.VALUE, message("ACME")?.type, "a fired custom condition is a VALUE error")

    @Test fun unregisteredConditionIsReportedUnsupported() {
        val outcome = DmInterpreter(def).validate(docOf("ACME"))   // no registry → "NameIsAcme" unregistered
        assertTrue(outcome.messages.none { it.code == "CC" }, "an unregistered custom condition must not fire")
        assertTrue(outcome.unsupported.any { it.rulePath == "/Order/R" }, "and the rule is reported unsupported (fail-fast)")
    }

    private companion object {
        const val NAME = "/Order/CustomerName"
        const val COND = "FieldFilled(CustomerName) And CustomCondition NameIsAcme"
    }
}
