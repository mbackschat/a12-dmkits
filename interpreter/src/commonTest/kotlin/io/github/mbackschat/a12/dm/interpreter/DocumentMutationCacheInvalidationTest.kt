package io.github.mbackschat.a12.dm.interpreter

import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.model.FieldDef
import io.github.mbackschat.a12.dm.interpreter.model.Kind
import io.github.mbackschat.a12.dm.interpreter.model.ModelDef
import io.github.mbackschat.a12.dm.interpreter.model.RuleDef
import io.github.mbackschat.a12.dm.interpreter.model.ValidationOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * IF142 (graduated from IG81) — a [Document] mutation invalidates EVERY document-derived cache, not just the
 * lookup index. `Document.add` cleared `cachedIndex` but left the document-level `rowContextsCache` (a `Having`
 * filter's per-row contexts) and `correlationBucketCache` (a `$` equality-correlation's hash buckets) warm, so on
 * the PUBLIC API a query → mutate → query reused stale row sets / `$`-correlation counts (the internal eval path
 * never mutates after querying, but a library consumer can). The fix routes every mutation through one complete
 * invalidation. This locks the CLASS with one test per cache: warm a `Having`-scoped query and a `$`-correlation
 * query, mutate so each cache's output MUST change, and prove each re-query equals a fresh document. JVM + JS.
 */
class DocumentMutationCacheInvalidationTest {

    private val note = "/Order/Note"
    private val amount = "/Order/Lines/Amount"
    private val tag = "/Order/Lines/Tag"

    private fun model(rule: RuleDef) = ModelDef(
        fields = listOf(FieldDef(note, Kind.STRING), FieldDef(amount, Kind.NUMBER, scale = 2), FieldDef(tag, Kind.STRING)),
        rules = listOf(rule),
        repeatableGroups = setOf("/Order/Lines"),
    )

    private fun line(d: Document, row: Int, amt: String, t: String) =
        d.group("/Order/Lines", 1, row).field(amount, amt, 1, row, 1).field(tag, t, 1, row, 1)

    private fun sigs(o: ValidationOutcome): List<String> {
        assertTrue(o.unsupported.isEmpty(), "sanity: the rule must be supported (else its cache is never exercised): ${o.unsupported}")
        return o.messages.map { "${it.code}|${it.type}|${it.errorPath}" }.sorted()
    }

    /** Warm on [seed], mutate with [mutate], and assert the re-query equals a fresh document with the same rows. */
    private fun assertMutationMatchesFresh(rule: RuleDef, seed: (Document) -> Unit, mutate: (Document) -> Unit) {
        val def = model(rule)
        val engine = DmInterpreter(def)

        val doc = def.newDocument().also { it.group("/Order", 1).field(note, "n", 1, 1); seed(it) }
        sigs(engine.validate(doc))            // WARM the document-derived caches
        mutate(doc)                           // mutate through Document.add
        val afterMutation = sigs(engine.validate(doc))

        val fresh = def.newDocument().also { it.group("/Order", 1).field(note, "n", 1, 1); seed(it); mutate(it) }
        assertEquals(sigs(engine.validate(fresh)), afterMutation,
            "query→mutate→query on one Document must equal a freshly-built one — the derived cache must be invalidated on mutation")
    }

    @Test
    fun havingRowContextCacheIsInvalidatedOnMutation() {
        // A `Having`-filtered fill quantifier over the Lines star (warms rowContextsCache). Seed has no "hot" line,
        // so it is silent; adding a filled "hot" line makes a fresh doc fire — a stale cache would still be silent.
        assertMutationMatchesFresh(
            RuleDef("/Order/HOTQ", "/Order",
                "AtLeastOneFieldFilled(/Order/Lines*/Amount Having [/Order/Lines/Tag] == \"hot\")", "HOTQ", "../Note"),
            seed = { line(it, 1, "10.00", "basic") },
            mutate = { line(it, 2, "20.00", "hot") },
        )
    }

    @Test
    fun correlationBucketCacheIsInvalidatedOnMutation() {
        // A per-row rule whose `Having` correlates on the OUTER row's Tag (`$`) and fires when MORE THAN ONE
        // same-tag line has a filled Amount (warms correlationBucketCache). Seed has one "x" line (count 1 → silent);
        // adding a second "x" line makes a fresh doc's count 2 → fires — a stale bucket would still count 1.
        assertMutationMatchesFresh(
            RuleDef("/Order/Lines/RC", "/Order/Lines",
                "MoreThanOneFieldFilled(/Order/Lines*/Amount Having [/Order/Lines/Tag] == [\$/Order/Lines/Tag])", "RC", "../Amount"),
            seed = { line(it, 1, "10.00", "x") },
            mutate = { line(it, 2, "20.00", "x") },
        )
    }
}
