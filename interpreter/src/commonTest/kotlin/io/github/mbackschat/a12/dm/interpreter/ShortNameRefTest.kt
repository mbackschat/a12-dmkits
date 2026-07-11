package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Multiplatform (JVM + JS) lock for bare SHORT-NAME field-ref resolution (IF119) — the PURE interpreter
 * (parser resolver + eval), so it runs on Kotlin/JS too. A catalog with two sibling subgroups: `Sku`/`Region`
 * are UNIQUE short names, `Direct` is a root field, and one variant duplicates `Region`. The kernel
 * differential pins the firing in `adapter.laws.ShortNameRefDiffTest`; this proves the kernel-free build
 * reproduces it and — crucially — that a NON-resolvable ref fails LOUDLY (never a silent wrong value): the
 * flag-off and ambiguous cases throw "no kind declared" rather than mis-binding to a value.
 */
class ShortNameRefTest {

    private val DIRECT = "/Catalog/Direct"
    private val SKU = "/Catalog/Warehouse/Sku"
    private val REGION = "/Catalog/Store/Region"
    private val DUP_REGION = "/Catalog/Warehouse/Region"

    private fun model(cond: String, flag: Boolean, ambiguous: Boolean = false) = ModelDef(
        fields = buildList {
            add(FieldDef(DIRECT, Kind.STRING))
            add(FieldDef(SKU, Kind.STRING))
            add(FieldDef(REGION, Kind.STRING))
            if (ambiguous) add(FieldDef(DUP_REGION, Kind.STRING))   // a second field with short name `Region`
        },
        rules = listOf(RuleDef("/Catalog/RR", "/Catalog", cond, "RR", "../Store/Region")),
        fieldRefByShortNameAllowed = flag,
    )

    private fun fires(cond: String, flag: Boolean, ambiguous: Boolean, vararg cells: Pair<String, String>): Boolean {
        val m = model(cond, flag, ambiguous)
        val doc = m.newDocument()
        cells.forEach { (p, v) -> doc.field(p, v, *IntArray(p.trim('/').split('/').size) { 1 }) }
        return DmInterpreter(m).validateFull(doc).any { it.code == "RR" }
    }

    @Test fun crossGroupUniqueResolvesWhenFlagOn() =
        assertEquals(true, fires("[Region] == \"R\"", flag = true, ambiguous = false, REGION to "R"))

    @Test fun crossGroupUniqueSilentOnMismatch() =
        assertEquals(false, fires("[Region] == \"R\"", flag = true, ambiguous = false, REGION to "X"))

    @Test fun sameGroupRefResolvesRegardless() =
        assertEquals(true, fires("[Direct] == \"D\"", flag = true, ambiguous = false, DIRECT to "D"))

    @Test fun flagOffDoesNotResolveCrossGroup() {
        // no model-wide fallback → [Sku] binds to the nonexistent /Catalog/Sku → loud, never silently wrong.
        assertFailsWith<IllegalStateException> { fires("[Sku] == \"S\"", flag = false, ambiguous = false, SKU to "S") }
    }

    @Test fun ambiguousShortNameDoesNotResolve() {
        // `Region` in two groups → not unique → the interpreter does NOT pick one (matching the kernel's
        // MVK_FIELDNAME_NOT_UNIQUE reject): the ref stays /Catalog/Region and reading it fails loudly.
        assertFailsWith<IllegalStateException> { fires("[Region] == \"R\"", flag = true, ambiguous = true, REGION to "R") }
    }
}
