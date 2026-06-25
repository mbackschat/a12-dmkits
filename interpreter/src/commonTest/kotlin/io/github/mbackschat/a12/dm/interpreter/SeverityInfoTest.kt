package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.Severity
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * IF23 — a rule may carry the kernel's third severity level, **INFO** (UI classification only; an INFO rule
 * fires and emits a message exactly like ERROR — probe-verified against the kernel). It must load, fire, and
 * carry INFO. Before the fix the `Severity` enum was `{ERROR, WARNING}` and `InterpreterModelLoader`'s
 * `Severity.valueOf("INFO")` threw on a valid model — a loud crash on `model eval`.
 */
class SeverityInfoTest {

    @Test
    fun infoRuleFiresAndCarriesInfoSeverity() {
        val def = ModelDef(
            listOf(FieldDef("/Order/Quantity", Kind.NUMBER)),
            listOf(RuleDef("/Order/InfoR", "/Order", "[Quantity] == 5", "INFO_R", "../Quantity", Severity.INFO)),
        )
        val doc = Document(def.kinds).group("/Order", 1).field("/Order/Quantity", "5", 1, 1)
        val msg = DmInterpreter(def).validate(doc).messages.single { it.code == "INFO_R" }
        assertEquals(Severity.INFO, msg.severity, "an INFO rule's fired message carries INFO severity, not a crash or a downgrade")
    }
}
