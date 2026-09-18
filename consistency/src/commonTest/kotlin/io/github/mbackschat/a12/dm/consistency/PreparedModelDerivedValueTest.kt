package io.github.mbackschat.a12.dm.consistency

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * A value derived from the whole model is computed ONCE per model, not once per cell that reads it.
 *
 * **This is a measured performance defect's guard, not a style preference.** The set shape's value was a
 * function that materialized a five-member projection of every element on every call, and every cell keyed on
 * [FactRead.DeclarationSetShape] calls it twice — once when produced and once when validated against another
 * model. On the 200-program synthesis profile that put `setShapeValue` at **0.53 s of self time plus ~1.0 s of
 * garbage collection in a 1.2 s check**: a plain-Node check of that model went from the 129 ms its own lane
 * published on 2026-09-17 to 1.2 s, measured with `node --cpu-prof` against the packed package on 2026-09-18.
 *
 * Identity is the oracle because it is the one a rebuild cannot satisfy: a function returning a fresh `map`
 * hands out a new instance every call and fails here, while structural equality would pass either way and lock
 * nothing. [PreparedModel.resolvableByPath] already carried the same contract in its own words — "built once
 * per [PreparedModel] rather than per reference" — which is why this is a guard on the CLASS rather than on
 * the one property that broke it.
 */
class PreparedModelDerivedValueTest {

    @Test
    fun theSetShapeValueIsHandedOutRatherThanRebuiltPerRead() {
        val prepared = prepare(admit(ModelSource(MODEL)) as Admission.Admitted)

        val first = prepared.valueOf(FactRead.DeclarationSetShape)
        val second = prepared.valueOf(FactRead.DeclarationSetShape)

        assertIs<Any>(first, "the set shape must have a value, or this row locks nothing")
        assertSame(
            first,
            second,
            "the set shape's value is rebuilt per read, so every cell keyed on it pays for a projection of " +
                "the whole model twice",
        )
    }

    @Test
    fun theOtherPerModelIndexesAreHandedOutToo() {
        val prepared = prepare(admit(ModelSource(MODEL)) as Admission.Admitted)

        assertSame(prepared.resolvableByPath, prepared.resolvableByPath, "resolvableByPath is rebuilt per call")
        assertSame(prepared.bodiesByPath, prepared.bodiesByPath, "bodiesByPath is rebuilt per call")
        assertSame(prepared.rowsByPath, prepared.rowsByPath, "rowsByPath is rebuilt per call")
    }

    private companion object {
        /** An ordinary two-group model: the row is about derivation, so the content only has to be read. */
        private val MODEL = """
            {"header":{"id":"derived","locales":[{"code":"en_US"}]},
             "content":{"modelRoot":{"rootGroups":[
              {"type":"Group","id":"g0","name":"Basket","Group":{"elements":[
                {"type":"Field","id":"f0","name":"Sku","Field":{"fieldType":{"type":"StringType"}}},
                {"type":"Field","id":"f1","name":"Quantity","Field":{"fieldType":{"type":"NumberType"}}},
                {"type":"Group","id":"g1","name":"Lines","Group":{"repeatable":true,"elements":[
                  {"type":"Field","id":"f2","name":"Amount","Field":{"fieldType":{"type":"NumberType"}}}
                ]}}
              ]}}
             ]}}}
        """.trimIndent()
    }
}
