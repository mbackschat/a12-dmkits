package io.github.mbackschat.a12.dm.adapter.laws

import com.mgmtp.a12.kernel.md.rt.api.IMessage
import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.DocumentConfig
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import io.github.mbackschat.a12.dm.modelkit.FieldKind
import io.github.mbackschat.a12.dm.rulekit.dsl.BoundModel
import io.github.mbackschat.a12.dm.rulekit.dsl.Condition
import io.github.mbackschat.a12.dm.rulekit.dsl.Dsl
import io.github.mbackschat.a12.dm.rulekit.dsl.DslRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — the group-SCOPED field-fill quantifiers `AllFieldsFilled(/Group*)` etc. (IG25), the interpreter
 * twin of the render-only `GroupScopedFieldFillReadTest`. A group scope means "the fields INSIDE the group"
 * (recursively, incl. nested subgroups — the kernel `ExpandService` → `getAllContainedFields`). Two crashes/
 * silent-wrongs it fixes: a bare group operand was treated as ONE field (quiet wrong), and a starred group
 * operand crashed (`pathOf(Star)`). Locks the interpreter against the kernel across the two ITERATION RANGES the
 * kernel uses (probed + source-confirmed 2026-07-06, KERNEL-FINDINGS): `AllFieldsFilled`/`NotAllFieldsFilled`
 * iterate the **full DECLARED repetition range** (an un-instantiated rep reads empty), while `AtLeastOne`/
 * `NoField`/`MoreThanOne`/`NotExactlyOne` clamp to the **instantiated** rows; `FieldsNotCollectivelyFilled`
 * mixes both. Authored via the typed DSL, rendered, run through BOTH engines over the same document.
 */
class GroupScopedFieldFillDiffTest {

    // Root { Gate, Addr { Street, City }, Lines*(repeatable 2) { X, Y } }  — a non-repeatable subgroup + a
    // repeatable group, so both ranges are exercised. repeatability 2 keeps the DECLARED-range cases small.
    private val modelJson = DocumentModelDraft.named("gsff").locale("en_US")
        .rootGroup("Root") { r -> r
            .field("Gate", FieldKind.STRING)
            .group("Addr") { a -> a.field("Street", FieldKind.STRING).field("City", FieldKind.STRING) }
            .group("Lines") { l -> l.repeatable(2).field("X", FieldKind.STRING).field("Y", FieldKind.STRING) } }
        .build().toJson()
    private val m = BoundModel.ofJson(modelJson)
    private fun render(c: Condition) = DslRenderer().render(c)
    private fun gated(c: Condition) = m.field(GATE).filled().and(c)

    private val addr = m.flattenGroup("/Root/Addr")
    private val lines = m.flattenGroup("/Root/Lines*")

    // bare non-repeatable group scope
    private val allAddr = render(gated(Dsl.allFieldsFilled(addr)))
    private val oneAddr = render(gated(Dsl.atLeastOneFieldFilled(addr)))
    private val noAddr = render(gated(Dsl.noFieldFilled(addr)))
    // starred repeatable group scope — DECLARED-range ops
    private val allLines = render(gated(Dsl.allFieldsFilled(lines)))
    private val notAllLines = render(gated(Dsl.notAllFieldsFilled(lines)))
    // starred repeatable group scope — INSTANTIATED-range ops
    private val oneLines = render(gated(Dsl.atLeastOneFieldFilled(lines)))
    private val noLines = render(gated(Dsl.noFieldFilled(lines)))
    private val moreLines = render(gated(Dsl.moreThanOneFieldFilled(lines)))
    private val notExactlyLines = render(gated(Dsl.notExactlyOneFieldFilled(lines)))
    private val notCollLines = render(gated(Dsl.fieldsNotCollectivelyFilled(lines)))
    // single starred FIELD — DECLARED range for AllFieldsFilled (the existing starQuantTally was wrong here)
    private val allX = render(gated(Dsl.allFieldsFilled(m.flatten("/Root/Lines*/X"))))

    private val fields = listOf(
        FieldDef(GATE, Kind.STRING), FieldDef(STREET, Kind.STRING), FieldDef(CITY, Kind.STRING),
        FieldDef(LX, Kind.STRING), FieldDef(LY, Kind.STRING))

    /** A scenario: which Addr fields are filled, and the per-Lines-row filled field sets. */
    private data class Scene(val addr: Set<String>, val addrInstantiated: Boolean, val lines: List<Set<String>>)

    private fun kDoc(s: Scene): RuntimeLaws.Doc {
        val doc = RuntimeLaws.doc().group("/Root", 1).field(GATE, "g", 1, 1)
        if (s.addrInstantiated) {
            doc.group("/Root/Addr", 1, 1)
            s.addr.forEach { doc.field("/Root/Addr/$it", "v", 1, 1, 1) }
        }
        s.lines.forEachIndexed { i, f -> val r = i + 1; doc.group("/Root/Lines", 1, r); f.forEach { doc.field("/Root/Lines/$it", "v", 1, r, 1) } }
        return doc
    }

    private fun iDoc(s: Scene): Document {
        val doc = Document(DocumentConfig(kinds, mapOf("/Root/Lines" to 2)))
        doc.field(GATE, "g", 1, 1)
        if (s.addrInstantiated) {
            doc.group("/Root/Addr", 1, 1)
            s.addr.forEach { doc.field("/Root/Addr/$it", "v", 1, 1, 1) }
        }
        s.lines.forEachIndexed { i, f -> val r = i + 1; doc.group("/Root/Lines", 1, r); f.forEach { doc.field("/Root/Lines/$it", "v", 1, r, 1) } }
        return doc
    }

    private val kinds = fields.associate { it.path to it.kind }
    private fun interp(cond: String) = DmInterpreter(ModelDef(fields, listOf(RuleDef("/Root/R", "/Root", cond, "NF", "../Gate")),
        repeatableGroups = setOf("/Root/Lines"), declaredReps = mapOf("/Root/Lines" to 2)))

    private fun kFires(cond: String, s: Scene): Boolean =
        RuntimeLaws.messages(RuntimeLaws.withRule(modelJson, "/Root", "R", "../Gate", "NF", cond), kDoc(s)).any { it.code() == "NF" }

    private fun iFires(cond: String, s: Scene): Boolean = interp(cond).validateFull(iDoc(s)).any { it.code == "NF" }

    private fun agree(cond: String, s: Scene) = assertEquals(kFires(cond, s), iFires(cond, s), "cond=$cond scene=$s")

    /** Assert both engines fire AND agree on the message polarity (VALUE vs OMISSION) — bit-exact typing. */
    private fun agreeFiredType(cond: String, s: Scene) {
        val kType = RuntimeLaws.typeOf(RuntimeLaws.withRule(modelJson, "/Root", "R", "../Gate", "NF", cond), "NF", kDoc(s)).orElseThrow()
        val iMsg = interp(cond).validate(iDoc(s)).messages.single { it.code == "NF" }
        assertEquals(kType == IMessage.MessageType.VALUE_ERROR, iMsg.type == MsgType.VALUE, "polarity cond=$cond scene=$s")
    }

    // ---- bare non-repeatable group scope (the silent-wrong case: was treated as ONE field) ----
    @Test fun bareGroupScope() {
        val full = Scene(setOf("Street", "City"), true, emptyList())
        val partial = Scene(setOf("Street"), true, emptyList())
        val empty = Scene(emptySet(), true, emptyList())
        val absent = Scene(emptySet(), false, emptyList())
        for (s in listOf(full, partial, empty, absent)) {
            agree(allAddr, s); agree(oneAddr, s); agree(noAddr, s)
        }
    }

    // ---- starred repeatable group scope, DECLARED-range ops (AllFieldsFilled / NotAllFieldsFilled) ----
    @Test fun starredScopeDeclaredRange() {
        val bothFull = Scene(emptySet(), false, listOf(setOf("X", "Y"), setOf("X", "Y")))   // 2/2 declared full → AllFilled TRUE
        val oneFull = Scene(emptySet(), false, listOf(setOf("X", "Y")))                     // 1/2 declared → AllFilled FALSE
        val oneRowPartial = Scene(emptySet(), false, listOf(setOf("X")))
        val noRows = Scene(emptySet(), false, emptyList())
        for (s in listOf(bothFull, oneFull, oneRowPartial, noRows)) {
            agree(allLines, s); agree(notAllLines, s); agree(allX, s)
        }
    }

    // ---- starred repeatable group scope, INSTANTIATED-range ops ----
    @Test fun starredScopeInstantiatedRange() {
        val noRows = Scene(emptySet(), false, emptyList())
        val oneCell = Scene(emptySet(), false, listOf(setOf("X")))
        val twoCells = Scene(emptySet(), false, listOf(setOf("X", "Y")))
        val emptyRow = Scene(emptySet(), false, listOf(emptySet()))
        val twoRowsMixed = Scene(emptySet(), false, listOf(setOf("X", "Y"), setOf("X")))
        for (s in listOf(noRows, oneCell, twoCells, emptyRow, twoRowsMixed)) {
            agree(oneLines, s); agree(noLines, s); agree(moreLines, s)
            agree(notExactlyLines, s); agree(notCollLines, s)
        }
    }

    // ---- message polarity (OMISSION vs VALUE) of a FIRED scope quantifier matches the kernel ----
    @Test fun firedPolarityMatchesKernel() {
        agreeFiredType(allAddr, Scene(setOf("Street", "City"), true, emptyList()))       // all filled → VALUE
        agreeFiredType(noAddr, Scene(emptySet(), true, emptyList()))                     // none → OMISSION
        agreeFiredType(oneAddr, Scene(setOf("Street"), true, emptyList()))               // ≥1 → VALUE
        agreeFiredType(allLines, Scene(emptySet(), false, listOf(setOf("X", "Y"), setOf("X", "Y"))))  // 2/2 → VALUE
        agreeFiredType(notAllLines, Scene(emptySet(), false, listOf(setOf("X"))))        // some empty → OMISSION
        agreeFiredType(noLines, Scene(emptySet(), false, emptyList()))                   // no rows → OMISSION
        agreeFiredType(moreLines, Scene(emptySet(), false, listOf(setOf("X", "Y"))))     // 2 filled → VALUE
        agreeFiredType(notExactlyLines, Scene(emptySet(), false, emptyList()))           // 0 filled → OMISSION
        agreeFiredType(notExactlyLines, Scene(emptySet(), false, listOf(setOf("X", "Y")))) // 2 filled → VALUE
        agreeFiredType(notCollLines, Scene(emptySet(), false, listOf(setOf("X"))))       // partial → OMISSION
    }

    private companion object {
        const val GATE = "/Root/Gate"
        const val STREET = "/Root/Addr/Street"
        const val CITY = "/Root/Addr/City"
        const val LX = "/Root/Lines/X"
        const val LY = "/Root/Lines/Y"
    }
}
