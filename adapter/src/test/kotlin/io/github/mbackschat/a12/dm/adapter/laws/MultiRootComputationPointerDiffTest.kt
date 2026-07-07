package io.github.mbackschat.a12.dm.adapter.laws

import io.github.mbackschat.a12.dm.interpreter.DmInterpreter
import io.github.mbackschat.a12.dm.interpreter.eval.Document
import io.github.mbackschat.a12.dm.interpreter.eval.formatPointer
import io.github.mbackschat.a12.dm.interpreter.eval.newDocument
import io.github.mbackschat.a12.dm.interpreter.load.ModelLoader
import io.github.mbackschat.a12.dm.interpreter.model.ComputeOutcome
import io.github.mbackschat.a12.dm.interpreter.model.MsgType
import io.github.mbackschat.a12.dm.modelkit.DocumentModelDraft
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Differential — IF66 (graduated from IG41 "multi-root error-entity path rendering"): a computation's error entity
 * (its computed field) may NOT be a descendant of the computation's own group. Two legal shapes exercise this:
 * a **multi-root** `header`/`content` model whose computation targets a field in ANOTHER top-level root, and a
 * **sibling-subtree** target within one root (`/Root/A/comp` writing `/Root/B/field`). In both, the DM-JSON
 * `computedFieldRelPath` walks `../` up past the computation's group.
 *
 * The interpreter synthesized the implicit-validation error-entity relpath as `"../" + targetPath.removePrefix(group)`,
 * which assumed the target sat under the group. For a cross-subtree target the `removePrefix` no-op'd and left an
 * absolute path, so the relpath became a `..//abs/path` double-slash that `resolveErrorPath` mangled into a wrong,
 * over-long pointer (e.g. `/Body//Header/RiskTier` instead of `/Header/RiskTier`). The fix computes the general
 * relative path from the rule element to the absolute target ([DmInterpreter] `relativePath`), correct for a
 * descendant, a sibling subtree, and a cross-root target alike.
 *
 * Both kernel strategies build the implicit validation from the same md-model computation rule ⟹ codegen-parity
 * (the flagship shape carries a static-JAVA tri-check). The `compute` pointer was never mangled (it renders
 * `targetPath` directly) — locked here too so the whole pointer surface stays pinned. Own-domain insurance /
 * claims; the cross-root computation is injected as stored-form (not typed-DSL-authorable — DEV-API-GAPS).
 */
class MultiRootComputationPointerDiffTest {

    // ── multi-root: /Policy (RiskTier) + /Assessment (Note); computation on /Assessment writes /Policy/RiskTier ──
    private val multiRoot = DocumentModelDraft.named("insurance").locale("en_US")
        .rootGroup("Policy") { p -> p.stringField("RiskTier") { } }
        .rootGroup("Assessment") { a -> a.stringField("Note") { } }
        .build().toJson()

    private class Scenario(val kModel: String, val kDoc: RuntimeLaws.Doc, val interp: DmInterpreter, val iDoc: Document)

    /** Inject a cross-target computation [name] into [group] writing [relPath] the constant [value]; seed the
     *  target [targetPath] with a disagreeing [stored] value and instantiate [group] (so it iterates). */
    private fun scenario(base: String, group: String, name: String, relPath: String, value: String,
                         targetPath: String, stored: String, extraField: Pair<String, String>): Scenario {
        val kModel = RuntimeLaws.withComputation(base, group, name, relPath, "\"$value\"")
        val def = ModelLoader.load(kModel)
        val tReps = IntArray(targetPath.trim('/').split('/').size) { 1 }
        val (exPath, exVal) = extraField
        val exReps = IntArray(exPath.trim('/').split('/').size) { 1 }
        val kDoc = RuntimeLaws.doc()
        val iDoc = def.newDocument()
        // instantiate the computation's group chain in both engines (kernel needs every ancestor row placed).
        groupChain(group).forEach { g -> val r = IntArray(g.trim('/').split('/').size) { 1 }; kDoc.group(g, *r); iDoc.group(g, *r) }
        groupChain(parentOf(targetPath)).forEach { g -> val r = IntArray(g.trim('/').split('/').size) { 1 }; kDoc.group(g, *r); iDoc.group(g, *r) }
        kDoc.field(targetPath, stored, *tReps); iDoc.field(targetPath, stored, *tReps)
        kDoc.field(exPath, exVal, *exReps); iDoc.field(exPath, exVal, *exReps)
        return Scenario(kModel, kDoc, DmInterpreter(def), iDoc)
    }

    private fun kValidate(s: Scenario, code: String) = RuntimeLaws.messages(s.kModel, s.kDoc).filter { it.code() == code }
        .map { "${it.field()}|${it.type().name}" }.sorted()
    private fun iValidate(s: Scenario, code: String) = s.interp.validateFull(s.iDoc).filter { it.code == code }
        .map { "${it.errorPath}|${if (it.type == MsgType.OMISSION) "OMISSION_ERROR" else "VALUE_ERROR"}" }.sorted()

    private fun kCompute(s: Scenario, target: String) = RuntimeLaws.compute(s.kModel, s.kDoc).filter { it.path == target }
        .map { "${it.pointer()}|${if (it.cleared()) "CLEARED" else it.value().orElse("")}" }.sorted()
    private fun iCompute(s: Scenario, target: String) = s.interp.compute(s.iDoc).filter { it.path == target && it.outcome != ComputeOutcome.CLEARED }
        .map { "${formatPointer(it.path, it.indices)}|${it.value ?: ""}" }.sorted()

    @Test fun multiRootValidationPointsAtTheOtherRootsField() {
        // Computation on /Assessment writes /Policy/RiskTier = "standard"; stored "premium" disagrees → fires,
        // pointer must be /Policy[1]/RiskTier (the other root), NOT a mangled /Assessment//Policy/... form.
        val s = scenario(multiRoot, "/Assessment", "SetTier", "../../Policy/RiskTier", "standard",
            "/Policy/RiskTier", "premium", "/Assessment/Note" to "seen")
        assertEquals(kValidate(s, "SetTier"), iValidate(s, "SetTier"),
            "multi-root implicit validation pointer\n  kernel=${kValidate(s, "SetTier")}\n  interp=${iValidate(s, "SetTier")}")
        assertEquals(listOf("/Policy[1]/RiskTier|VALUE_ERROR"), iValidate(s, "SetTier"), "expected the /Policy root pointer")
    }

    @Test fun multiRootComputePointerMatches() {
        val s = scenario(multiRoot, "/Assessment", "SetTier", "../../Policy/RiskTier", "standard",
            "/Policy/RiskTier", "premium", "/Assessment/Note" to "seen")
        assertEquals(kCompute(s, "/Policy/RiskTier"), iCompute(s, "/Policy/RiskTier"),
            "compute pointer/value\n  kernel=${kCompute(s, "/Policy/RiskTier")}\n  interp=${iCompute(s, "/Policy/RiskTier")}")
    }

    @Test fun multiRootCodegenAgrees() {
        val s = scenario(multiRoot, "/Assessment", "SetTier", "../../Policy/RiskTier", "standard",
            "/Policy/RiskTier", "premium", "/Assessment/Note" to "seen")
        val groovy = kValidate(s, "SetTier")
        val engine = RuntimeLaws.kernelJavaEngine(s.kModel)
        val javaStatic = engine.messages(engine.instance(s.kDoc)).filter { it.code() == "SetTier" }
            .map { "${it.field()}|${it.type().name}" }.sorted()
        assertEquals(groovy, iValidate(s, "SetTier"), "codegen (interp≠groovy)\n  groovy=$groovy\n  interp=${iValidate(s, "SetTier")}")
        assertEquals(groovy, javaStatic, "codegen (java-static≠groovy)\n  groovy=$groovy\n  java=$javaStatic")
    }

    // ── second instance: single root, sibling subtree (/Claim/Incident writes /Claim/Payout/Status) ────────────
    private val siblingSubtree = DocumentModelDraft.named("claim").locale("en_US")
        .rootGroup("Claim") { c ->
            c.group("Incident") { i -> i.stringField("Note") { } }.group("Payout") { p -> p.stringField("Status") { } }
        }.build().toJson()

    @Test fun siblingSubtreeValidationPointsAtTheSiblingField() {
        val s = scenario(siblingSubtree, "/Claim/Incident", "SetStatus", "../../Payout/Status", "open",
            "/Claim/Payout/Status", "closed", "/Claim/Incident/Note" to "seen")
        assertEquals(kValidate(s, "SetStatus"), iValidate(s, "SetStatus"),
            "sibling-subtree implicit validation pointer\n  kernel=${kValidate(s, "SetStatus")}\n  interp=${iValidate(s, "SetStatus")}")
        assertEquals(listOf("/Claim[1]/Payout[1]/Status|VALUE_ERROR"), iValidate(s, "SetStatus"), "expected the sibling-subtree pointer")
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────────────────────────
    private fun parentOf(path: String): String = "/" + path.trim('/').split('/').dropLast(1).joinToString("/")
    /** Every ancestor-group prefix of [group] (root-first), e.g. `/A/B` → [`/A`, `/A/B`]. */
    private fun groupChain(group: String): List<String> {
        val parts = group.trim('/').split('/')
        return (1..parts.size).map { "/" + parts.take(it).joinToString("/") }
    }
}
