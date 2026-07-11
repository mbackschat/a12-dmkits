package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft.GroupBuilder
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.function.Consumer

/**
 * Differential — the **model-level** `modelConfig.supportedCharacters` slot is a real RUNTIME constraint, not just
 * authored metadata (IF120, graduated from IG61). The kernel bakes the slot into the generated code's legal charset
 * (`ExternalDocumentModelService` → `CodeGeneratorParameters(supportedCharacters, …)`) and `FormalChecker` raises
 * `ZeichenNichtImZeichensatz` on any value carrying a character outside the set. The interpreter must read the same
 * slot (via [ModelLoader]) and its [io.github.mbackschat.a12.dm.interpreter.model.LegalCharset] must fire the same
 * code — so a model DECLARING the slot agrees kernel-vs-interpreter (before the fix the interpreter was silent: it
 * only honored the consumer-supplied charset SPI, defaulting to BMP). Own-domain (insurance): a policy reference
 * restricted to `A-Z` + `0-9`. The kernel-only fires/does-not-fire lock is `SupportedCharactersLawsTest`.
 */
class SupportedCharactersDiffTest {

    /** The kernel's formal error for a character outside `supportedCharacters` (the interpreter emits the SAME code). */
    private val CHARSET = "ZeichenNichtImZeichensatz"

    // A policy whose config restricts every value to upper-case letters + digits (a policy reference charset).
    private val json = DocumentModelDraft.named("policy").locale("en_US")
        .supportedCharacters("A-Z", "0-9")
        .rootGroup("Policy", Consumer { g: GroupBuilder -> g.field("Ref", FieldKind.STRING) })
        .build().toJson()

    private val def = ModelLoader.load(json)

    /** Compare the kernel's vs interpreter's charset (code@pointer) pairs for `Ref = value`. */
    private fun agree(value: String, label: String) {
        val k = RuntimeLaws.messages(json, RuntimeLaws.doc().group("/Policy", 1).field(REF, value, 1, 1))
            .filter { it.code() == CHARSET }.map { "${it.code()}@${it.field()}" }.sorted()
        val i = DmInterpreter(def).validate(Document(def.kinds).group("/Policy", 1).field(REF, value, 1, 1))
            .messages.filter { it.code == CHARSET }.map { "${it.code}@${it.errorPath}" }.sorted()
        assertEquals(k, i, "$label — kernel=$k interp=$i")
    }

    @Test fun inSetValueIsClean() = agree("POL2024", "all A-Z/0-9 → clean")
    @Test fun lowerCaseIsOutOfSet() = agree("pol2024", "lower-case letters are outside A-Z → charset fires")
    @Test fun punctuationIsOutOfSet() = agree("POL-24", "the '-' is outside A-Z/0-9 → charset fires")
    @Test fun bmpAccentIsOutOfSet() = agree("PÖL", "an accented BMP letter is outside A-Z → charset fires")

    private companion object {
        const val REF = "/Policy/Ref"
    }
}
